package utils

import org.apache.commons.math3.distribution.{NormalDistribution, TDistribution}


/**
  * miscellaneous useful functions
  */
object Utils {

  /**
    * Calculate the mean of a set of numbers
    *
    * @param list
    * @tparam T
    * @return
    */
  def mean[T: Numeric](list: Seq[T]): Double = {
    val suma: T = list.sum
    val sum: Double = implicitly[Numeric[T]].toDouble(suma)

    sum / list.size.toDouble
  }


  /**
    * Calcualtes the standard deviation of a set of parameters
    *
    * @param list
    * @tparam T
    * @return
    */
  def standardDeviation[T: Numeric](list: Seq[T]): Double = {
    val media = mean(list)
    math.sqrt(list.map(x => {
      val value = implicitly[Numeric[T]].toDouble(x)
      math.pow(value - media, 2)
    })
      .sum / (list.size - 1.0))
  }

  /**
    * It calculates the confidence interval for the mean with the given confidence level
    *
    * @param list
    * @param confidenceLevel
    * @tparam T
    * @return a pair with the bounds of the interval
    */
  def meanConfidenceInterval[T: Numeric](list: Seq[T], confidenceLevel: Double): (Double, Double) = {
    if (list.length == 1) {
      val avg = mean(list)
      return (avg - 0.1, avg + 0.1)
    }

    if (list.length >= 30) {
      zScoreConfidenceInterval(list, confidenceLevel)
    } else {
      tTestConfidenceInterval(list, confidenceLevel)
    }
  }


  private def tTestConfidenceInterval[T: Numeric](list: Seq[T], confidence: Double): (Double, Double) = {
    val t = new TDistribution(list.length - 1)
    val avg: Double = mean(list)
    val n: Double = list.length
    val value: Double = standardDeviation(list) / math.sqrt(n)
    val statistic: Double = t.inverseCumulativeProbability(1 - (1 - confidence) / 2)
    val intervalWidth: Double = statistic * value

    (avg - intervalWidth, avg + intervalWidth)
  }


  private def zScoreConfidenceInterval[T: Numeric](list: Seq[T], confidence: Double): (Double, Double) = {
    val avg = mean(list)
    val std = standardDeviation(list)
    val n = if(std != 0) new NormalDistribution(avg, std)  else new NormalDistribution(avg, 0.000001)
    val z_alfa_2 = n.inverseCumulativeProbability(1 - (1 - confidence / 2))
    val intervalWidth = z_alfa_2 * (std / math.sqrt(list.length))

    (avg - intervalWidth, avg + intervalWidth)
  }
}
