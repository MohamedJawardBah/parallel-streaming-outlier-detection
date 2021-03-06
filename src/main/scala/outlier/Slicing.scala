package outlier

import mtree._
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.streaming.api.scala.function.ProcessWindowFunction
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector

import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case class SlicingState(var trees: mutable.HashMap[Long, MTree[Data]], var triggers: mutable.HashMap[Long, mutable.Set[Int]])

class Slicing(c_time_slide: Int, c_range: Double, c_k: Int) extends ProcessWindowFunction[(Int, Data), (Long, Int), Int, TimeWindow] {

  val slide = c_time_slide
  val R = c_range
  val k = c_k
  val outliers_trigger = -1L

  lazy val state: ValueState[SlicingState] = getRuntimeContext
    .getState(new ValueStateDescriptor[SlicingState]("myTree", classOf[SlicingState]))

  override def process(key: Int, context: Context, elements: scala.Iterable[(Int, Data)], out: Collector[(Long, Int)]): Unit = {
    val window = context.window
    //new variables
    val latest_slide = window.getEnd - slide
    val nonRandomPromotion = new PromotionFunction[Data] {
      /**
        * Chooses (promotes) a pair of objects according to some criteria that is
        * suitable for the application using the M-Tree.
        *
        * @param dataSet          The set of objects to choose a pair from.
        * @param distanceFunction A function that can be used for choosing the
        *                         promoted objects.
        * @return A pair of chosen objects.
        */
      override def process(dataSet: java.util.Set[Data], distanceFunction: DistanceFunction[_ >: Data]): utils.Pair[Data] = {
        utils.Utils.minMax[Data](dataSet)
      }
    }
    val mySplit = new ComposedSplitFunction[Data](nonRandomPromotion, new PartitionFunctions.BalancedPartition[Data])
    val myTree = new MTree[Data](k, DistanceFunctions.EUCLIDEAN, mySplit)
    //populate mtree
    if (state.value() == null) {
      var myTrigger = mutable.HashMap[Long, mutable.Set[Int]]()
      myTrigger.+=((outliers_trigger, mutable.Set()))
      var next_slide = window.getStart
      while(next_slide <= window.getEnd - slide){
        myTrigger.+=((next_slide, mutable.Set()))
        next_slide += slide
      }
      for (el <- elements) {
        myTree.add(el._2)
      }
      val myTrees = mutable.HashMap[Long, MTree[Data]]((latest_slide, myTree))
      state.update(SlicingState(myTrees, myTrigger))
    } else {
      elements
        .filter(el => el._2.arrival >= window.getEnd - slide)
        .foreach(el => {
          myTree.add(el._2)
        })
      var max = state.value().triggers.keySet.max + slide
      while (max <= window.getEnd - slide){
        state.value().triggers.+=((max, mutable.Set[Int]()))
        max += slide
      }
      state.value().trees.+=((latest_slide, myTree))
    }

    //Trigger leftover slides
    val slow_triggers = state.value().triggers.keySet.filter(p => p < window.getStart && p!= -1L).toList
    for(slow <- slow_triggers){
      val slow_triggers_points = state.value().triggers(slow).toList
      elements
        .filter(p => slow_triggers_points.contains(p._2.id))
        .foreach(p =>trigger_point(p._2, window))
      state.value().triggers.remove(slow)
    }

    //Insert new points
    elements
      .filter(p => p._2.arrival >= window.getEnd - slide && p._2.flag == 0)
      .foreach(p => {
        insert_point(p._2, window)
      })

    //Trigger previous outliers
    val triggered_outliers = state.value().triggers(outliers_trigger).toList
    state.value().triggers(outliers_trigger).clear()
    elements
      .filter(p => triggered_outliers.contains(p._2.id))
      .foreach(p =>trigger_point(p._2, window))

    //Report outliers
    val outliers = elements.count(p => {
      p._2.flag == 0 &&
        !p._2.safe_inlier &&
        p._2.count_after + p._2.slices_before.filter(_._1 >= window.getStart).values.sum < k
    })
    out.collect((window.getEnd, outliers))

    //Trigger expiring list
    state.value().trees.remove(window.getStart)
    val triggered: List[Int] = state.value().triggers(window.getStart).toList
    state.value().triggers.remove(window.getStart)
    elements
      .filter(p => triggered.contains(p._2.id))
      .foreach(p =>trigger_point(p._2, window))
  }

  def trigger_point(point: Data, window: TimeWindow): Unit = {
    var next_slide = //find starting slide
      if (point.last_check != 0L) point.last_check + slide
      else get_slide(point.arrival, window) + slide
    //Find no of neighbors
    var neigh_counter = point.count_after +
      point.slices_before.filter(_._1 >= window.getStart + slide).values.sum
    while (neigh_counter < k && next_slide <= window.getEnd - slide) {
      val myTree = state.value().trees.getOrElse(next_slide, null)
      if (myTree != null) {
        val query: MTree[Data]#Query = myTree.getNearestByRange(point, R)
        val iter = query.iterator()
        //Update point's metadata
        while (iter.hasNext) {
          iter.next()
          point.count_after += 1
          neigh_counter += 1
        }
        if (point.count_after >= k) point.safe_inlier = true
      }
      point.last_check = next_slide
      next_slide += slide
    }
    if (neigh_counter < k) state.value().triggers(outliers_trigger).+=(point.id)
  }

  def insert_point(point: Data, window: TimeWindow): Unit = {
    var (neigh_counter, next_slide) = (0, window.getEnd - slide)
    while (neigh_counter < k && next_slide >= window.getStart) { //Query each slide's MTREE
      val myTree = state.value().trees.getOrElse(next_slide, null)
      if (myTree != null) {
        val query: MTree[Data]#Query = myTree.getNearestByRange(point, R)
        val iter = query.iterator()
        //If it has neighbors insert it into the slide's trigger
        if (iter.hasNext)
          state.value().triggers(next_slide).+=(point.id)
        //Update point's metadata
        while (iter.hasNext) {
          val node = iter.next().data
          if (next_slide == window.getEnd - slide) {
            if (node.id != point.id) {
              point.count_after += 1
              neigh_counter += 1
            }
          } else {
            point.slices_before.update(next_slide, point.slices_before.getOrElse(next_slide, 0) + 1)
            neigh_counter += 1
          }
        }
        if (next_slide == window.getEnd - slide && neigh_counter >= k) point.safe_inlier = true
      }
      next_slide -= slide
    }
    //If it is outlier insert into trigger list
    if (neigh_counter < k) state.value().triggers(outliers_trigger).+=(point.id)
  }

  def get_slide(arrival: Long, window: TimeWindow): Long = {
    val first = arrival - window.getStart
    val div = first / slide
    val int_div = div.toInt
    window.getStart + (int_div * slide)
  }
}

