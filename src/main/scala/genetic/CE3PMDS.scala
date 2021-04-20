package genetic

import java.util
import java.util.Comparator

import attributes.Coverage
import org.uma.jmetal.algorithm.multiobjective.mocell.MOCell
import org.uma.jmetal.operator.{CrossoverOperator, MutationOperator, SelectionOperator}
import org.uma.jmetal.solution.BinarySolution
import org.uma.jmetal.util.{JMetalLogger, SolutionListUtils}
import org.uma.jmetal.util.archive.BoundedArchive
import org.uma.jmetal.util.archive.impl.CrowdingDistanceArchive
import org.uma.jmetal.util.neighborhood.impl.C9
import org.uma.jmetal.util.solutionattribute.impl.{CrowdingDistance, DominanceRanking}
import problem.attributes.Clase
import problem.conceptdrift.MeasureBasedDriftDetector
import problem.evaluator.{EPMEvaluator, EPMStreamingEvaluator}
import problem.filters.{CoverageRatioFilter, Filter}
import problem.qualitymeasures.QualityMeasure
import problem.{EPMProblem, EPMSparkStreamingProblem, EPMStreamingAlgorithm}
import utils.{BitSet, ResultWriter}

import scala.collection.JavaConverters._

class CE3PMDS(
                  problem: EPMSparkStreamingProblem,
                  confidenceThreshold: Double,
                  supportThreshold: Double,
                  maxEvaluations: Int,
                  populationSize: Int,
                  crossoverOperator: CrossoverOperator[BinarySolution],
                  mutationOperator: MutationOperator[BinarySolution],
                  selectionOperator: SelectionOperator[util.List[BinarySolution], BinarySolution],
                  evaluator: EPMStreamingEvaluator
                )
  extends MOCell[BinarySolution](
    problem,
    maxEvaluations,
    populationSize,
    new CrowdingDistanceArchive[BinarySolution](populationSize),
    new C9(math.sqrt(populationSize).toInt, math.sqrt(populationSize).toInt),
    crossoverOperator,
    mutationOperator,
    selectionOperator,
    evaluator
  )
  with EPMStreamingAlgorithm with Serializable {


  /** The elite population, where the best patterns found so far are stored */
  private var elite: util.List[BinarySolution] = new util.ArrayList[BinarySolution]()

  /** The post-processing filters to be applied after the execution of the EA */
  private var filters: Seq[Filter[BinarySolution]] = Array(new CoverageRatioFilter[BinarySolution](1))

  /** A bit set which marks the instances covered on the previous generations. This is for checking the re-init criteria */
  private var previousCoverage: BitSet = new BitSet(1)

  /** The generation (or evaluation) where the last change in {@code previousCoverage} occurred */
  private var lastChange: Int = 0

  /** NOT USED: for the coverage ratio filter. */
  private val z_alfa_2: Double = 1.96

  /** The percentage of generations (or evaluations) without change for triggering the re-initialisation opeartor */
  private val REINIT_PCT: Float = 0.25f

  /** For oriented initialisation: maximum percentage of variables randomly initialised. */
  private val PCT_COVERAGE: Double = 0.25

  /** For oriented initialisation: maximum percentage of individuals where only {@code PCT_COVERAGE} of its variables are initialised */
  private val PCT_INDS_ORIENTED: Double = 0.75


  /** The timestamp of the data stream we are analysing */
  private var TIMESTAMP = 0

  /** The detector in charge of real drift */
  val realDriftDetector = new MeasureBasedDriftDetector("CONF", confidenceThreshold, 0.6)

  /** The detetector in charge of virtual drift */
  val virtualDriftDetector = new MeasureBasedDriftDetector("Supp", supportThreshold, 0.6)

  /** Class for writting the results extracted into a file */
  var writer = new ResultWriter(
    "tra",
    "tst",
    "tra_summ",
    "rules",
    null,
    problem,
    evaluator.getObjectives,
    true)

  private var EXECUTION_TIME: Long = 0
  private var MEMORY: Double = 0

  /**
    * The current class to be processed.
    */
  private var CURRENT_CLASS = 0;


  override def setExecutionTime(time: Long) = EXECUTION_TIME = time

  override def setMemory(memory: Double) = MEMORY = memory

  /**
    * The initial population is created by adding the previously extracted individuals
    *
    * @return
    */
  override def createInitialPopulation(): util.List[BinarySolution] = {

    problem.fixClass(CURRENT_CLASS)
    archive.getSolutionList.clear()

    if (this.problem.getPreviousResultsStreaming().isEmpty) {
      (0 until getMaxPopulationSize).map(x => getProblem.createSolution()).asJava
    } else {
      val previousClassPopulation = this.problem.getPreviousResultsStreaming().filter(ind => ind.getAttribute(classOf[Clase[BinarySolution]]) == CURRENT_CLASS)
      previousClassPopulation.foreach(ind => {
        ind.getAttributes.remove(classOf[DominanceRanking[BinarySolution]])
        ind.getAttributes.remove(classOf[CrowdingDistance[BinarySolution]])
      })
      val numIndividualsToGenerate = getMaxPopulationSize - previousClassPopulation.length

      val generatedIndividuals = (0 until numIndividualsToGenerate).map(x => getProblem.createSolution())

      // Return
      (previousClassPopulation ++ generatedIndividuals).asJava
    }
  }


  /**
    * Updating the progress in FEPDS is performed by checking the reinitialisation criterion and reinitialising if necessary.
    * Also, the elite population is updated
    */
  override def updateProgress(): Unit = {
    super.updateProgress() // ONLY NSGA-II (updates the evaluation number)

    JMetalLogger.logger.finest("Evaluations: " + evaluations)

    // Check reinitialisation
    if(evaluations % populationSize == 0) { // check reinitialisation criteria when all individuals are evaluated.
      if (checkReinitialisationCriterion(getPopulation, this.previousCoverage, lastChange, REINIT_PCT)) {
        JMetalLogger.logger.finest("Reinitialisation at evaluation " + evaluations + " out of " + maxEvaluations)
        population = coverageBasedInitialisation(population, evaluator.classes(CURRENT_CLASS))

        // evaluate the new generated population to avoid errors
        evaluator.evaluate(population, problem)
      }
    }
  }

  /**
    * The result is the elite population
    *
    * @return
    */
  override def getResult: util.List[BinarySolution] = super.getResult


  /**
    * It starts the learning procedure for the extraction of a new pattern set model
    */
  def startLearningProcess(classesToRun: Seq[Int]): Unit = {
    JMetalLogger.logger.fine("Starting FEPDS-Spark execution.")
    elite = new util.ArrayList[BinarySolution]()

    classesToRun.foreach(clas => {
      CURRENT_CLASS = clas
      previousCoverage.clear()
      lastChange = 0
      try {
        super.run() // Run NSGA-II with the overrided methods

        // Return the non-dominated individuals, but first, evalute the population
        val nonDominatedPopulation = getResult //SolutionListUtils.getNondominatedSolutions(population)
        JMetalLogger.logger.finest("Size Of NonDominatedPopulation: " + nonDominatedPopulation.size())

        // Add to the elite the result of applying the filters on the nonDominatedPopulation
        var individuals: util.List[BinarySolution] = nonDominatedPopulation
        for (filter <- filters) {
          individuals = filter.doFilter(individuals, CURRENT_CLASS, this.evaluator.asInstanceOf[EPMEvaluator])
        }

        JMetalLogger.logger.info("Size after Token Competition: " + individuals.size())

        elite.addAll(individuals)
      } catch {
        case e: Exception => {
          // Somethinig went wrong. Notify and return an empty list
          println("An error has occurred at processing timestamp " + TIMESTAMP + " for class " + clas + ". Please check logs.")
          JMetalLogger.logger.severe(e.getMessage + ":\n" + e.printStackTrace())
          elite.addAll(new util.ArrayList[BinarySolution]())
        }
      }
    })

    // At the end, replace the previous results with this one
    this.problem.replacePreviousResults(elite.asScala, classesToRun)
    //println("-------------------------")
    JMetalLogger.logger.fine("Finished CE3P-MDS execution.")

  }

  override def getName: String = "CE3P-MDS"


  override def run(): Unit = {
    // ONLY FOR NSGA-III AS THE IMPLEMENTATION OF JMETAL MINIMISES !!
    TIMESTAMP += 1

    var toRun = evaluator.classes   // Get the classes with examples in this batch:
      .map(_.cardinality() > 0)     // Check if there is examples for the class
      .zipWithIndex                 // Get the index of the class to be executed
      .filter(_._1)                 // Get only the elements with TRUE values
      .map(_._2)                    // Get the integer of the class

    // TEST-THEN-TRAIN. First, test the results
    val model = problem.getPreviousResultsStreaming().asJava
    var doTraining = true

    if (!model.isEmpty) {
      QualityMeasure.setMeasuresReversed(false) // For test, get the normal values.

      // Get only patterns of the classes with examples in this new batch
      val filteredModel = model.asScala.filter(toRun contains _.getAttribute(classOf[Clase[BinarySolution]])).asJava

      evaluator.evaluateTest(model, problem) // Evaluate and enqueue
      evaluator.enqueue(filteredModel)

      if (!filteredModel.isEmpty) {
        // write the results in the file (return to expert)
        writer.setPopulation(filteredModel.asScala)
        writer.writeStreamingResults(TIMESTAMP, EXECUTION_TIME, MEMORY)
      }
      // Check if average confidence  for each class is below the threshold
      // This is performed because we only want to execute on those classes below the threshold and not in the other ones.
      val toExecRealDrift = realDriftDetector.detect(filteredModel.asScala).toSeq
      val toExecVirtualDrift = virtualDriftDetector.detect(filteredModel.asScala).toSeq

      val toExec: Seq[Int] = (toExecVirtualDrift union toExecRealDrift) distinct


      if (toExec.nonEmpty) {
        JMetalLogger.logger.info("DRIFT DETECTECT!! : From Real Drift: " + toExecRealDrift + "  -   From Virtual Drift: " + toExecVirtualDrift)
        toRun = toExec.toSeq
        doTraining = true
      } else {
        JMetalLogger.logger.info("Above threshold. SKIPPING TRAINING.")
        doTraining = false
      }
    }

    if (doTraining) {
      QualityMeasure.setMeasuresReversed(true)  // MOCell implementation minimises the objectives!!
      //problem.setAttributes(problem.generateAttributes())
      problem.generateFuzzySets()
      startLearningProcess(toRun)
    }

  }


  /**
    * It checks whether the population must be reinitialised.
    *
    * @param population       The population
    * @param previousCoverage The coverage of the previous population
    * @param lastChange       The evaluation where the last change in the population's coverage have been made
    * @param percentage       The maximum percentage of evaluation to allow no evolution.
    * @return
    */
  def checkReinitialisationCriterion(population: util.List[BinarySolution], previousCoverage: BitSet, lastChange: Int, percentage: Float): Boolean = {

    // Get the current coverage of the whole population
    val currentCoverage: BitSet = population.asScala.map(ind => ind.getAttribute(classOf[Coverage[BinarySolution]]).asInstanceOf[BitSet]).reduce(_ | _)

    if (previousCoverage == null) {
      this.previousCoverage = currentCoverage
      this.lastChange = evaluations
      return false
    }

    if (previousCoverage.cardinality() == 0 && currentCoverage.cardinality() != 0) {
      this.previousCoverage = currentCoverage
      this.lastChange = evaluations
      return false
    }
    // Calculate if there are new covered examples not previously covered
    val newCovered: BitSet = (previousCoverage ^ currentCoverage) & (~previousCoverage)

    if (newCovered.cardinality() > 0) {
      JMetalLogger.logger.finer("New examples covered at evaluation: " + evaluations)
      this.previousCoverage = currentCoverage
      this.lastChange = evaluations
      false
    } else {
      val evalsToReinit = (maxEvaluations * percentage).toInt
      (evaluations - lastChange) >= evalsToReinit
    }
  }


  /**
    * It performs the coverage-based re-initialisation procedure.
    *
    * @param population
    * @param clase
    */
  def coverageBasedInitialisation(population: util.List[BinarySolution], clase: BitSet): util.List[BinarySolution] = {
    // First of all, perform COVERAGE RATIO filter
    val newPopulation = filters(0).doFilter(population, CURRENT_CLASS, evaluator)
    lastChange = evaluations  // iterations


    // Then get the coverage of the result and determines the number of new individuals that must be generated
    val currentCoverage = if(newPopulation.isEmpty) {
      new BitSet(clase.capacity)
    } else {
      newPopulation.asScala.map(ind => ind.getAttribute(classOf[Coverage[BinarySolution]]).asInstanceOf[BitSet]).reduce(_ | _)
    }
    previousCoverage = currentCoverage

    // Find a non-covered example of the class
    val nonCoveredExamples = ~currentCoverage & clase

    if (nonCoveredExamples.cardinality() > 0) {
      // At least an example of the class is not covered
      while (nonCoveredExamples.nextSetBit(0) != -1 && newPopulation.size() < populationSize) {
        val example = nonCoveredExamples.nextSetBit(0)

        val newIndividual: BinarySolution = problem.coverageBasedInitialisation(example, evaluator, PCT_COVERAGE)
        newPopulation.add(newIndividual)

        // Mark the example as covered in order to cover a new one
        nonCoveredExamples.unset(example)
      }

      // If there are remainin individuals, generate them randomly
      if (newPopulation.size() < populationSize) {
        val newInds = (0 until (populationSize - newPopulation.size())).map(x => getProblem.createSolution())
        newPopulation.addAll(newInds.asJava)
      }
      newPopulation
    } else {
      // All examples of the class have been already covered: Random initialisation
      val newInds = (0 until (populationSize - newPopulation.size())).map(x => getProblem.createSolution())
      newPopulation.addAll(newInds.asJava)
      newPopulation
    }


  }


  override def reproduction(population: util.List[BinarySolution]): util.List[BinarySolution] = super.reproduction(population)
}
