package problem.conceptdrift

import attributes.TestMeasures
import org.uma.jmetal.solution.BinarySolution
import problem.attributes.Clase
import problem.qualitymeasures.QualityMeasure
import utils.Utils

import scala.collection.mutable


/**
  * Drift detection based on a measure using a confidence interval for the mean value
  *
  * @param measure
  * @param measureThreshold
  * @param confidenceLevel
  */
class MeasureBasedDriftDetector(measure: String, measureThreshold: Double, confidenceLevel: Double) {
  val nClasses = 100
  var currentConfidenceIntervals: Array[Option[(Double, Double)]] = Array.fill[Option[(Double, Double)]](nClasses)(None)


  /**
    * It detects real drift by means of looking at some quality measures of the model.
    *
    * @param model
    * @return It returns a set of ints representing those classes which have suffered concept drift
    */
  def detect(model: Seq[BinarySolution]): Iterable[Int] = {
    model.groupBy(
      _.getAttribute(classOf[Clase[BinarySolution]]).asInstanceOf[Int] // Group individuals by class
    ).flatMap(group => {

      val measures = group._2.map( // Get confidence of each individual in the group
        _.getAttribute(classOf[TestMeasures[BinarySolution]])
          .asInstanceOf[mutable.ArraySeq[QualityMeasure]]
          .filter(_.getShort_name equals (measure))
          .head
          .getValue
      )

      // Calculate average and confidence interval
      //val confidenceInterval = Utils.meanConfidenceInterval(measures, confidenceLevel)
      val avgMeasure = Utils.mean(measures)

      val clazz = group._1

      // If the average measure is below the threshold, then mark the class to be re-executed
      if(avgMeasure < measureThreshold) Some(clazz) else None

      /*if (currentConfidenceIntervals(clazz).isDefined) {
        // If a confidence have been defined, only execute if the mean is OUTSIDE the interval and restart it
        if (avgMeasure < currentConfidenceIntervals(clazz).get._1 || avgMeasure > currentConfidenceIntervals(clazz).get._2) {
          currentConfidenceIntervals(clazz) = None
          Some(clazz)
        } else {
          None
        }
      } else {
        currentConfidenceIntervals(clazz) = Some(confidenceInterval)
        if (avgMeasure < measureThreshold)
          Some(clazz)
        else
          None
      }*/

    })
  }
}
