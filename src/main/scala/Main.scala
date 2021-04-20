import java.util
import java.util.logging.Level
import com.yahoo.labs.samoa.instances.Instance
import genetic.CE3PMDS
import genetic.operators.crossover.NPointCrossover
import genetic.operators.mutation.BiasedMutationDNF
import moa.streams.ArffFileStream
import net.sourceforge.jFuzzyLogic.membership.MembershipFunction
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.spark.SparkConf
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.SparkSession
import org.apache.spark.streaming.kafka010.ConsumerStrategies.Subscribe
import org.apache.spark.streaming.kafka010.KafkaUtils
import org.apache.spark.streaming.kafka010.LocationStrategies.PreferConsistent
import org.apache.spark.streaming.{Milliseconds, StreamingContext}
import org.uma.jmetal.algorithm.Algorithm
import org.uma.jmetal.operator.CrossoverOperator
import org.uma.jmetal.operator.impl.selection.BinaryTournamentSelection
import org.uma.jmetal.solution.BinarySolution
import org.uma.jmetal.util.comparator.{DominanceComparator, RankingAndCrowdingDistanceComparator}
import org.uma.jmetal.util.pseudorandom.JMetalRandom
import org.uma.jmetal.util.{AlgorithmRunner, JMetalLogger}
import picocli.CommandLine
import picocli.CommandLine.{Option, Parameters}
import problem._
import problem.evaluator.{EPMEvaluator, EPMStreamingEvaluator}
import problem.qualitymeasures.QualityMeasure
import utils.ResultWriter
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer



class Main extends Runnable with Serializable {

  @Parameters(index = "0", paramLabel = "trainingFile", description = Array("The training file in ARFF format."))
  var trainingFile: String = _

  @Parameters(index = "1", paramLabel = "testFile", description = Array("The test file in ARFF format."))
  var testFile: String = _

  @Option(names = Array("-h", "--help"), usageHelp = true, description = Array("Show this help message and exit."))
  var help = false

  @Option(names = Array("-t", "--training"), paramLabel = "PATH", description = Array("The path for storing the training results file."))
  var resultTraining: String = "tra"

  @Option(names = Array("-T", "--test"), paramLabel = "PATH", description = Array("The path for storing the test results file."))
  var resultTest: String = "test"

  @Option(names = Array("-r", "--rules"), paramLabel = "PATH", description = Array("The path for storing the rules file."))
  var resultRules: String = "rules"

  @Option(names = Array("-l", "--labels"), paramLabel = "NUMBER", description = Array("The number of fuzzy linguistic labels for each variable."))
  var numLabels: Int = 3

  @Option(names = Array("-Q"), paramLabel = "SIZE", description = Array("The size of the FIFO queue of FEPDS"))
  var QUEUE_SIZE: Int = 0

  @Option(names = Array("-v"), description = Array("Show INFO messages."))
  var verbose: Boolean = false

  @Option(names = Array("-c", "--crossover"), description = Array("The crossover probability. By default is 0.6"))
  var CROSSOVER_PROBABILITY: Double = 0.6

  @Option(names = Array("-m", "--mutation"), description = Array("The mutation probability. By default is 0.1"))
  var MUTATION_PROBABILITY: Double = 0.1

  @Option(names = Array("-B"), description = Array("Big Data processing using Spark"))
  var bigDataProcessing: Boolean = false

  @Option(names = Array("-S"), description = Array("Streaming processing"))
  var streamingProcessing: Boolean = false

  @Option(names = Array("-p"), paramLabel = "VALUE", description = Array("Population Size. NOTE: Should be a number equals to the size of a n x n grid, e.g., 49 is for 7x7 grids."))
  var POPULATION_SIZE: Int = 49

  @Option(names = Array("-e"), paramLabel = "VALUE", description = Array("Maximum number of evaluations"))
  var MAX_EVALUATIONS: Int = 5000

  @Option(names = Array("-C"), paramLabel = "VALUE", description = Array("Chunk size for non-big data streaming processing"))
  var CHUNK_SIZE: Int = 5000

  @Option(names = Array("--time"), paramLabel = "SECONDS", description = Array("Data collect time (in milliseconds) for the Spark Streaming engine"))
  var COLLECT_TIME: Long = 1000

  @Option(names = Array("-n"), paramLabel = "PARTITIONS", description = Array("The number of partitions employed for Big Data"))
  var NUM_PARTITIONS: Int = 8

  @Option(names = Array("--kafkabroker"), paramLabel = "NAME", description = Array("The host an port of the kafka broker being used"))
  var KAFKA_BROKERS: String = "localhost:9092"

  @Option(names = Array("-o", "--objectives"), split=",", paramLabel = "NAME(S)", description = Array("A comma-separated list of quality measures to be used as objectives"))
  var OBJECTIVES: Array[String] = Array("WRAccNorm", "SuppDiff" )

  @Option(names = Array("--topics"), paramLabel = "NAME(S)", description = Array("A comma-separated list of kafka topics to be employed"))
  var TOPICS: Array[String] = Array("test")
  //Kafka params

  @Option(names = Array("--maxSamples"), paramLabel = "Number", description = Array("The maximum number of samples to process before stop the process"))
  var MAX_INSTANCES: Int = -1

  //Streams de entrada
  val kafkaParams: Map[String, Object] = Map[String, Object](
    "bootstrap.servers" -> KAFKA_BROKERS,
    "key.deserializer" -> classOf[StringDeserializer],
    "value.deserializer" -> classOf[StringDeserializer],
    "num.partitions" -> "1",
    "group.id" -> "SIMIDAT-Group",
    "auto.offset.reset" -> "latest",  //"earliest",  // earliest only for debug purposes
    "enable.auto.commit" -> (false: java.lang.Boolean)
  )

  /**  Maximum amount of empty batches in a row until considering timeout and finish execution. */
  val MAX_EMPTY_BATCHES_TIMEOUT: Int = 10

  override def run(): Unit = {

    if(help){
      new CommandLine(this).usage(System.err)
      return
    }

    if(verbose) {
      JMetalLogger.logger.setLevel(Level.FINE)
    } else {
      JMetalLogger.logger.setLevel(Level.SEVERE)
    }

    val rand = JMetalRandom.getInstance()
    rand.setSeed(1)

    // The problem
    val problem = new EPMSparkStreamingProblem
    problem.setInitialisationMethod(problem.ORIENTED_INITIALISATION)
    problem.setRandomGenerator(rand)
    problem.setNumLabels(numLabels)

    // The default crossover, mutation and selection operators to be employed on those algorithm who request it
    val crossoverProbability = CROSSOVER_PROBABILITY
    val crossover = new NPointCrossover[BinarySolution](crossoverProbability, 2, problem.rand)
    val mutationProbability = MUTATION_PROBABILITY
    val mutation = new BiasedMutationDNF(mutationProbability, problem.rand)

    // The evaluator (OBJECTIVES: WRAcc, TPR & Confidence)
    val evaluator = new EPMStreamingEvaluator(QUEUE_SIZE)
    evaluator.setBigDataProcessing(false)
    val objs: Seq[QualityMeasure] = OBJECTIVES.map(getQualityMeasure)
    evaluator.setObjectives(objs)
    problem.setNumberOfObjectives(objs.length)

    // The method
    val algorithm: CE3PMDS = new CE3PMDS(problem,
      0.6,
      0.1,
      MAX_EVALUATIONS,
      POPULATION_SIZE,
      crossover.asInstanceOf[CrossoverOperator[BinarySolution]],
      mutation,
      new BinaryTournamentSelection[BinarySolution](new RankingAndCrowdingDistanceComparator[BinarySolution]),
      evaluator
    )

    // Define the result writer
    algorithm.writer = new ResultWriter(resultTraining,
      resultTest,
      resultTest + "_summ",
      resultRules,
      null,
      problem,
      evaluator.getObjectives,
      true)

    if(!bigDataProcessing){
      if(!streamingProcessing) {
        // Traditional processing
        traditionalExecution(algorithm, problem, evaluator)
      } else {
        // Streaming processing with MOA (non-big data)
        streamingTraditionalExecution(algorithm, problem, evaluator, -1)
      }
    } else {
      if(!streamingProcessing){
        // Big Data traditional processing
        bigDataTraditionalExecution(algorithm, problem.asInstanceOf[EPMProblem], evaluator, NUM_PARTITIONS)
      } else {
        // Spark-Streaming processing
        sparkStreamingExecution(algorithm,problem, evaluator, NUM_PARTITIONS)
      }
    }

    println("Finished")

  }



  /**
    * It performs a traditional execution with a given method
    *
    * @param algorithm
    * @param problem
    */
  def traditionalExecution(algorithm: Algorithm[util.List[BinarySolution]], problem: EPMProblem, evaluator: EPMEvaluator): Unit ={
    JMetalLogger.logger.info("Loading Data...")
    val t_ini = System.currentTimeMillis()
    problem.readDataset(trainingFile)
    JMetalLogger.logger.info("NumExamples: " + problem.numExamples)
    evaluator.initialise(problem)

    JMetalLogger.logger.info("Executing " + algorithm.getName+ "...")
    val time = new AlgorithmRunner.Executor(algorithm).execute()
    val result = algorithm.getResult

    JMetalLogger.logger.info("Testing results...")
    problem.readDataset(testFile)
    evaluator.initialise(problem)
    evaluator.evaluateTest(result, problem)
    println("Execution time: " + (System.currentTimeMillis() - t_ini) + " ms")
    JMetalLogger.logger.info("Writting results...")
    val writer = new ResultWriter(resultTraining, resultTest, resultTest + "_summ", resultRules, result.asScala, problem, evaluator.getObjectives, true)
    writer.writeResults(0)

  }


  /**
    * It performs straming processing with MOA (i.e., without parallel processing)
    *
    * @param algorithm
    * @param problem
    * @param evaluator
    * @param classColumn
    */
  def streamingTraditionalExecution(algorithm: EPMStreamingAlgorithm, problem: EPMStreamingProblem, evaluator: EPMStreamingEvaluator, classColumn: Int): Unit ={
    val stream = new ArffFileStream(trainingFile, classColumn)
    stream.prepareForUse()

    while(stream.hasMoreInstances) {
      var dataSeq: mutable.Seq[Instance] = new ArrayBuffer[Instance]()

      while (dataSeq.size < CHUNK_SIZE && stream.hasMoreInstances) {  // Collect chunk of data
        dataSeq = dataSeq :+ stream.nextInstance().getData
      }

      //problem.readDataset(args(0))
      val t_ini = System.currentTimeMillis()
      problem.readDataset(dataSeq, stream.getHeader)
      evaluator.initialise(problem)

      // Run the mehod
      val result = new AlgorithmRunner.Executor(algorithm).execute()
      val t_end = System.currentTimeMillis()

      val execTime = t_end - t_ini
      val memory: Double = (Runtime.getRuntime.totalMemory() - Runtime.getRuntime.freeMemory()) / (1024.0*1024.0)

      JMetalLogger.logger.info("Execution time: " + execTime + " ms.  Memory: " + memory +" MiB.")

      algorithm.setExecutionTime(execTime)
      algorithm.setMemory(memory)
    }
  }

  /**
    * It executes the algorithm with the Big Data configuration using Spark
    * @param algorithm
    * @param problem
    * @param evaluator
    * @param numPartitions
    */
  def bigDataTraditionalExecution(algorithm: Algorithm[util.List[BinarySolution]], problem: EPMProblem, evaluator: EPMEvaluator, numPartitions: Int): Unit ={
    // Set Spark Context
    val conf: SparkConf = getSparkConfiguration
    val spark: SparkSession = SparkSession.builder.config(conf).config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      // use this if you need to increment Kryo buffer size. Default 64k
      .config("spark.kryoserializer.buffer", "1024k")
      // use this if you need to increment Kryo buffer max size. Default 64m
      .config("spark.kryoserializer.buffer.max", "1024m")
      .appName(algorithm.getName)
      //.master("local[*]")
      .getOrCreate()

    spark.sparkContext.setLogLevel("ERROR")

    // Execute the method
    JMetalLogger.logger.info("Loading Data...")
    problem.readDatasetBigDataAsDataFrame(trainingFile, numPartitions, spark)
    problem.generateAttributes(spark)
    problem.generateFuzzySets()
    evaluator.initialise(problem)

    JMetalLogger.logger.info("Executing " + algorithm.getName+ "...")
    val time = new AlgorithmRunner.Executor(algorithm).execute()
    val result = algorithm.getResult

    JMetalLogger.logger.info("Testing results...")
    problem.readDatasetBigDataAsDataFrame(testFile, numPartitions, spark)
    evaluator.initialise(problem)
    evaluator.evaluateTest(result, problem)

    JMetalLogger.logger.info("Writting results...")
    val writer = new ResultWriter(resultTraining, resultTest, resultTest + "_summ", resultRules, result.asScala, problem, evaluator.getObjectives, true)
    writer.writeResults(0)

    println("Execution time " + time.getComputingTime + " ms.")
  }


  /**
    * It executes the algorithm by means of Spark Streaming:
    *
    * First, the algorithm must read an ARFF file with the header in order to get the attributes information. This file
    * must be provided by means of the {@code trainingFile} parameter.
    *
    * After that, data is collected from a kafka broker on the host {@code KAFKA_BROKERS}  reading the information on topics
    * {@code TOPICS} using the format of the ARFF file provided.
    *
    * Next, the problem an evaluator are initialised with the given data and the algorithm is executed.
    *
    * @param algorithm      the algorithm to be executed
    * @param problem        the EPM problem to be executed
    * @param evaluator      the evaluator to be employed
    * @param numPartitions  the number of partitions in Spark
    */
  private def sparkStreamingExecution(algorithm: EPMStreamingAlgorithm, problem: EPMSparkStreamingProblem, evaluator: EPMEvaluator, numPartitions: Int): Unit = {

    // First of all, read the header file
    problem.readDataset(trainingFile)
    val header = problem.getData.toString

    // Set Spark Context
    val conf: SparkConf = getSparkConfiguration
    conf.setAppName(algorithm.getName)
    //conf.setMaster("local[*]")
    //conf.setJars(Array("antlrworks-1.2.jar"))

    val spark: SparkSession = SparkSession.builder.config(conf).config("spark.serializer", "org.apache.spark.serializer.KryoSerializer")
      // use this if you need to increment Kryo buffer size. Default 64k
      .config("spark.kryoserializer.buffer", "1024k")
      // use this if you need to increment Kryo buffer max size. Default 64m
      .config("spark.kryoserializer.buffer.max", "1024m")
      //.appName(algorithm.getName)
      //.master("local[*]")
      .getOrCreate()

    spark.sparkContext.setLogLevel("ERROR")

    // create streaming context which collect data each COLLECT_TIME Milliseconds
    val ssc = new StreamingContext(spark.sparkContext, Milliseconds(COLLECT_TIME))

    var INSTANCES_PROCESSED = 0
    val stream = KafkaUtils.createDirectStream[String, String](
      ssc,
      PreferConsistent,
      Subscribe[String, String](TOPICS, kafkaParams)
    ).map(record => record.value())

    var firstBatch = true
    var emptyBatch = 0
    var totalTime: Long = 0


    stream.repartition(NUM_PARTITIONS).cache().foreachRDD(rdd => {
      if (!rdd.isEmpty()) {
        emptyBatch = 0
        //println("Processing RDD...")
        var t_ini = System.currentTimeMillis()
        processRDD(rdd, algorithm, problem, evaluator, numPartitions, firstBatch, header)
        totalTime += (System.currentTimeMillis() - t_ini)
        INSTANCES_PROCESSED += problem.numExamples

        if (firstBatch) {
          firstBatch = false
        }

        if (MAX_INSTANCES != -1) {
          if (INSTANCES_PROCESSED >= MAX_INSTANCES) {
            println("TOTAL EXECUTION TIME: " + totalTime + " ms.")
            ssc.stop(true, false)
          }
        }
      } else {
        emptyBatch += 1
        if(emptyBatch >= MAX_EMPTY_BATCHES_TIMEOUT)  {
          println("TOTAL EXECUTION TIME: " + totalTime + " ms.")
          ssc.stop(true, false)
        }
      }
    })

    // Starts streaming process
    ssc.start()
    ssc.awaitTermination()

  }


  /**
    * It processes the incoming RDD. The process firstly expands the fuzzy definitions if necessary. After that, it
    * initialises the EPM Problem and execute the FEPDS-Spark method.
    *
    * @param rdd           The RDD to process.
    * @param algorithm     The algorithm in charge of the processing
    * @param problem       The EPM problem class definition
    * @param evaluator     The evaluator for measuring patterns quality
    * @param numPartitions The number of data partitions employed in Spark
    * @param firstBatch    Is this the first batch we are processing?
    * @param header        The ARFF header with data information.
    */
  def processRDD(rdd: RDD[String], algorithm: EPMStreamingAlgorithm, problem: EPMSparkStreamingProblem, evaluator: EPMEvaluator, numPartitions: Int, firstBatch: Boolean, header: String): Unit = {
    var t_ini = System.currentTimeMillis()
    JMetalLogger.logger.info("Starting FEPDS-Spark execution on time: " + t_ini)

    // For each non-empty rdd that arrives into the system: load the problem and initialise the bitset structure
    //problem.readDatasetSparkStreaming(rdd, numPartitions, firstBatch)
    problem.readDatasetFromRDD_String(rdd, header)
    //println("Reading time: " + (System.currentTimeMillis() - t_ini) + " ms.")

    // Check when min and max change in order to update the fuzzy sets definitions.
    if (firstBatch) {
      problem.setAttributes(problem.generateAttributes())
     problem.generateFuzzySets()
    } else {
      for (i <- 0 until problem.getNumberOfVariables) {
        if (problem.getData.attribute(i).isNumeric) {
          val currentMin = problem.getFuzzySet(i, 0).getUniverseMin
          val currentMax = problem.getFuzzySet(i, numLabels - 1).getUniverseMax
          val dat = problem.getData.attributeToDoubleArray(i)
          val dataMin = dat.min
          val dataMax = dat.max

          if (dataMin < currentMin && currentMax >= dataMax) { // only update min
            JMetalLogger.logger.info("FUZZY DEFINITIONS CHANGED !!")
            problem.getFuzzyVariable(i).asInstanceOf[ArrayBuffer[MembershipFunction]].clear()
            problem.getFuzzyVariable(i).asInstanceOf[ArrayBuffer[MembershipFunction]].insertAll(0, Fuzzy.generateTriangularLinguisticLabels(dataMin, currentMax, numLabels))
          } else if (dataMax > currentMax && currentMin <= dataMin) { // only update max
            JMetalLogger.logger.info("FUZZY DEFINITIONS CHANGED !!")
            problem.getFuzzyVariable(i).asInstanceOf[ArrayBuffer[MembershipFunction]].clear()
            problem.getFuzzyVariable(i).asInstanceOf[ArrayBuffer[MembershipFunction]].insertAll(0, Fuzzy.generateTriangularLinguisticLabels(currentMin, dataMax, numLabels))
          } else if (dataMin < currentMin && dataMax > currentMax) { // update both sides
            JMetalLogger.logger.info("FUZZY DEFINITIONS CHANGED !!")
            problem.getFuzzyVariable(i).asInstanceOf[ArrayBuffer[MembershipFunction]].clear()
            problem.getFuzzyVariable(i).asInstanceOf[ArrayBuffer[MembershipFunction]].insertAll(0, Fuzzy.generateTriangularLinguisticLabels(dataMin, dataMax, numLabels))
          }
        }
      }
    }


    evaluator.initialise(problem)

    // Run the mehod
    // val result = new AlgorithmRunner.Executor(algorithm).execute()
    t_ini = System.currentTimeMillis()
    algorithm.run()
    val t_end = System.currentTimeMillis()


    val execTime = t_end - t_ini
    val memory: Double = (Runtime.getRuntime.totalMemory() - Runtime.getRuntime.freeMemory()) / (1024.0 * 1024.0)
    //println("Execution time: " + execTime + " ms.")
    JMetalLogger.logger.info("Execution time: " + execTime + " ms.  Memory: " + memory + " MiB.")

    algorithm.setExecutionTime(execTime)
    algorithm.setMemory(memory)
  }

  /**
    * It returns the spark configuration
    * @return
    */
  private def getSparkConfiguration: SparkConf = {
    val conf = new SparkConf()
    conf.registerKryoClasses(
      Array(
        classOf[scala.collection.mutable.WrappedArray.ofRef[_]],
        classOf[org.apache.spark.sql.types.StructType],
        classOf[Array[org.apache.spark.sql.types.StructType]],
        classOf[org.apache.spark.sql.types.StructField],
        classOf[Array[org.apache.spark.sql.types.StructField]],
        Class.forName("org.apache.spark.sql.types.StringType$"),
        Class.forName("org.apache.spark.sql.types.LongType$"),
        Class.forName("org.apache.spark.sql.types.BooleanType$"),
        Class.forName("org.apache.spark.sql.types.DoubleType$"),
        classOf[org.apache.spark.sql.types.Metadata],
        classOf[org.apache.spark.sql.types.ArrayType],
        Class.forName("org.apache.spark.sql.execution.joins.UnsafeHashedRelation"),
        classOf[org.apache.spark.sql.catalyst.InternalRow],
        classOf[Array[org.apache.spark.sql.catalyst.InternalRow]],
        classOf[org.apache.spark.sql.catalyst.expressions.UnsafeRow],
        Class.forName("org.apache.spark.sql.execution.joins.LongHashedRelation"),
        Class.forName("org.apache.spark.sql.execution.joins.LongToUnsafeRowMap"),
        classOf[utils.BitSet],
        classOf[org.apache.spark.sql.types.DataType],
        classOf[Array[org.apache.spark.sql.types.DataType]],
        Class.forName("org.apache.spark.sql.types.NullType$"),
        Class.forName("org.apache.spark.sql.types.IntegerType$"),
        Class.forName("org.apache.spark.sql.types.TimestampType$"),
        Class.forName("org.apache.spark.internal.io.FileCommitProtocol$TaskCommitMessage"),
        Class.forName("scala.collection.immutable.Set$EmptySet$"),
        Class.forName("java.lang.Class")
      )
    )
  }

  /**
    * It returns a quality measure class by means of a string name
    * @param name
    * @return
    */
  def getQualityMeasure(name: String): QualityMeasure = {
    Class.forName(
      classOf[QualityMeasure].getPackage.getName +
        "." +
        name)
      .newInstance()
      .asInstanceOf[QualityMeasure]
  }

}

object Main {

  def main(args: Array[String]): Unit = {

    CommandLine.run(new Main(), System.err, args: _*)

  }

}
