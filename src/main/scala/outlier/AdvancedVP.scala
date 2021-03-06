package outlier

import mtree._
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.streaming.api.scala.function.ProcessWindowFunction
import org.apache.flink.streaming.api.windowing.windows.TimeWindow
import org.apache.flink.util.Collector

import scala.collection.mutable

case class AdvancedVPState(var tree: MTree[Data], var hashMap: mutable.HashMap[Int, Data])

class AdvancedVP(time_slide: Int, range: Double, k: Int) extends ProcessWindowFunction[(Int, Data), (Long, Int), Int, TimeWindow] {

  lazy val state: ValueState[AdvancedVPState] = getRuntimeContext
    .getState(new ValueStateDescriptor[AdvancedVPState]("myTree", classOf[AdvancedVPState]))

  override def process(key: Int, context: Context, elements: scala.Iterable[(Int, Data)], out: Collector[(Long, Int)]): Unit = {
    val window = context.window
    //populate Mtree
    var current: AdvancedVPState = state.value
    if (current == null) {
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
      var myHash = new mutable.HashMap[Int, Data]()
      for (el <- elements) {
        myTree.add(el._2)
        myHash.+=((el._2.id, el._2))
      }
      current = AdvancedVPState(myTree, myHash)
    } else {
      elements
        .filter(el => el._2.arrival >= window.getEnd - time_slide)
        .foreach(el => {
          current.tree.add(el._2)
          current.hashMap.+=((el._2.id, el._2))
        })
    }

    //Get neighbors
    elements
      .filter(p => p._2.arrival >= (window.getEnd - time_slide))
      .foreach(p => {
        val tmpData = new Data(p._2.value, p._2.arrival, p._2.flag, p._2.id)
        val query: MTree[Data]#Query = current.tree.getNearestByRange(tmpData, range)
        val iter = query.iterator()
        while (iter.hasNext) {
          val node = iter.next().data
          if (node.id != tmpData.id) {
            if (node.arrival < (window.getEnd - time_slide)) {
              if (tmpData.flag == 0) {
                current.hashMap(tmpData.id).insert_nn_before(node.arrival, k)
              }
              if (node.flag == 0) {
                current.hashMap(node.id).count_after += 1
                if (current.hashMap(node.id).count_after >= k)
                  current.hashMap(node.id).safe_inlier = true
              }
            } else {
              if (tmpData.flag == 0) {
                current.hashMap(tmpData.id).count_after += 1
                if (current.hashMap(tmpData.id).count_after >= k)
                  current.hashMap(tmpData.id).safe_inlier = true
              }
            }
          }
        }
      })

    //Variable for number of outliers
    var outliers = 0

    current.hashMap.values.foreach(p => {
      if (p.flag == 0 && !p.safe_inlier) {
        val nnBefore = p.nn_before.count(_ >= window.getStart)
        if (p.count_after + nnBefore < k) outliers += 1
      }
    })
    out.collect((window.getEnd, outliers))

    //Remove expiring objects and flagged ones from state
    elements
      .filter(el => el._2.arrival < window.getStart + time_slide)
      .foreach(el => {
        current.tree.remove(el._2)
        current.hashMap.-=(el._2.id)
      })
    //update state
    state.update(current)
  }
}

