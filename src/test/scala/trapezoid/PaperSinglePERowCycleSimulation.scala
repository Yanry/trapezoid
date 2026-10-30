package trapezoid

import spinal.core.sim._
import spinal.core._
import trapezoid.model.DenseMatrix

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.collection.mutable
import scala.sys.process.Process

/** Real Verilator throughput simulation of one paper-sized PE row.
  *
  * The datapath keeps the paper widths (128-wide fibers, 128 MAC lanes and
  * 16 output lanes) but instantiates exactly one TrapezoidPERow. The workload
  * is one active output row of a common 128x128 tile for every mode. Commands
  * are streamed while responses are consumed, so the reported boundary is
  * PE-row throughput rather than isolated command latency.
  *
  * Run with:
  *   sbt -batch 'Test/runMain trapezoid.PaperSinglePERowCycleSimulation'
  *
  * Optional filters:
  *   --mode=TrIp|DenseIp|TrGt|TrGs|all
  *   --category='MS x D'
  *   --samples=1
  */
object PaperSinglePERowCycleSimulation extends App {
  private val cfg = TrapezoidConfig.paper.copy(peRows = 1)
  private val dimension = cfg.fiberWidth
  private val samples = args.collectFirst {
    case argument if argument.startsWith("--samples=") =>
      argument.stripPrefix("--samples=").toInt
  }.getOrElse(1)
  require(samples >= 1)

  private val modeFilter = args.collectFirst {
    case argument if argument.startsWith("--mode=") =>
      argument.stripPrefix("--mode=")
  }
  private val categoryFilter = args.collectFirst {
    case argument if argument.startsWith("--category=") =>
      argument.stripPrefix("--category=")
  }
  private val modes = Vector("DenseIp", "TrIp", "TrGt", "TrGs")
    .filter(mode => modeFilter.forall(value => value == "all" || value == mode))

  private case class Category(name: String, aBasisPoints: Int,
                              bBasisPoints: Int, paperMode: String)
  private case class Workload(category: Category, sample: Int,
                              a: DenseMatrix, b: DenseMatrix)
  private sealed trait CommandSpec {
    def mode: String
    def endOfTile: Boolean
  }
  private case class TripCommand(mode: String, columns: Vector[Int],
                                 endOfTile: Boolean = false) extends CommandSpec
  private case class GustavsonCommand(mode: String, kIndices: Vector[Int],
                                      columns: Vector[Int],
                                      endOfTile: Boolean = false) extends CommandSpec
  private case class Measurement(category: Category, sample: Int,
                                 mode: String, cycles: Long,
                                 commandCount: Int, responseCount: Int,
                                 outputCount: Int, usefulMacs: Long,
                                 laneSlots: Long, checksum: Long,
                                 controlPrepareCycles: Long,
                                 routeCycles: Long, macCycles: Long,
                                 mrnCycles: Long, responseCycles: Long)

  private val categories = Vector(
    Category("HS x HS", 50, 50, "TrGt"),
    Category("HS x MS", 50, 2000, "TrGs"),
    Category("HS x D", 50, 10000, "TrGs"),
    Category("MS x MS", 2000, 2000, "TrIp"),
    Category("MS x D", 2000, 10000, "TrIp"),
    Category("D x D", 10000, 10000, "DenseIp")
  ).filter(category => categoryFilter.forall(_ == category.name))

  require(modes.nonEmpty, s"unknown mode filter $modeFilter")
  require(categories.nonEmpty, s"unknown category filter $categoryFilter")

  private def nonZerosFor(basisPoints: Int): Int =
    math.max(1, ((dimension.toLong * basisPoints + 5000) / 10000).toInt)

  private def activeVector(basisPoints: Int, sample: Int,
                           salt: Int): Vector[Long] = {
    val nonZeros = if (basisPoints == 10000) dimension
      else nonZerosFor(basisPoints)
    Vector.tabulate(dimension) { index =>
      if (index < nonZeros) 1L + ((index + sample + salt) & 3) else 0L
    }
  }

  private def workloadFor(category: Category, sample: Int): Workload = {
    val aRow = activeVector(category.aBasisPoints, sample, 3)
    val bNonZeros = if (category.bBasisPoints == 10000) dimension
      else nonZerosFor(category.bBasisPoints)
    val a = DenseMatrix(Vector.tabulate(dimension, dimension) { (row, k) =>
      if (row == 0) aRow(k) else 0L
    })
    val b = DenseMatrix(Vector.tabulate(dimension, dimension) { (k, column) =>
      if (column < bNonZeros) 1L + ((k + column + sample + 11) & 3)
      else 0L
    })
    Workload(category, sample, a, b)
  }

  private val workloads = categories.flatMap { category =>
    Vector.tabulate(samples)(sample => workloadFor(category, sample))
  }

  private val kernelDirectory = Paths.get(
    "simWorkspace", "paper-single-pe-row-kernel").toAbsolutePath.normalize()
  private val requiredKernelSources = Vector(
    "TrapezoidPERow.v",
    "MultiFiberIntersection.v",
    "BenesDistributionFabric.v",
    "PipelinedBenesRouteControlGenerator.v",
    "TrapezoidBenesRouteControl.sv"
  ).map(kernelDirectory.resolve)
  private val routeControlSource = Paths.get(
    "src/main/resources/rtl/TrapezoidBenesRouteControl.sv")
    .toAbsolutePath.normalize()
  private val kernelInputs = Vector(
    Paths.get("src/main/scala/trapezoid/TrapezoidPERow.scala"),
    Paths.get("src/main/scala/trapezoid/MultiFiberIntersection.scala"),
    Paths.get("src/main/scala/trapezoid/TrapezoidTypes.scala"),
    Paths.get("src/main/scala/trapezoid/TrapezoidConfig.scala"),
    routeControlSource
  ).map(_.toAbsolutePath.normalize())

  private def kernelIsCurrent: Boolean = {
    requiredKernelSources.forall(Files.isRegularFile(_)) && {
      val oldestOutput = requiredKernelSources.map(path =>
        Files.getLastModifiedTime(path).toMillis).min
      val newestInput = kernelInputs.map(path =>
        Files.getLastModifiedTime(path).toMillis).max
      oldestOutput >= newestInput
    }
  }

  private def generateKernel(): Unit = {
    Files.createDirectories(kernelDirectory)
    val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val status = Process(Seq(
      java,
      "-Xms1G",
      "-Xmx12G",
      "-XX:+UseG1GC",
      "-cp",
      System.getProperty("java.class.path"),
      "trapezoid.GeneratePaperPERowSimulationRtl",
      kernelDirectory.toString
    )).!
    require(status == 0,
      s"paper PE-row RTL generation failed with status $status")
    Files.copy(routeControlSource,
      kernelDirectory.resolve(routeControlSource.getFileName),
      StandardCopyOption.REPLACE_EXISTING)
    require(requiredKernelSources.forall(Files.isRegularFile(_)),
      s"paper PE-row RTL is incomplete in $kernelDirectory")
  }

  private def markHierarchical(path: Path, moduleName: String): Unit = {
    val marker = "/*verilator hier_block*/"
    val source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    val cleaned = source.replace("\n  " + marker, "")
    val declaration = s"module $moduleName ("
    val declarationIndex = cleaned.indexOf(declaration)
    require(declarationIndex >= 0,
      s"cannot locate $moduleName declaration in $path")
    val headerEnd = cleaned.indexOf(");", declarationIndex)
    require(headerEnd >= 0,
      s"cannot locate $moduleName port-list end in $path")
    val annotated = cleaned.substring(0, headerEnd + 2) +
      "\n  " + marker + cleaned.substring(headerEnd + 2)
    Files.write(path, annotated.getBytes(StandardCharsets.UTF_8))
  }

  if (!kernelIsCurrent) generateKernel()
  markHierarchical(kernelDirectory.resolve("TrapezoidPERow.v"),
    "TrapezoidPERow")
  markHierarchical(kernelDirectory.resolve(
    "PipelinedBenesRouteControlGenerator.v"),
    "PipelinedBenesRouteControlGenerator")

  private val kernelSources: Vector[Path] = {
    val stream = Files.list(kernelDirectory)
    try {
      import scala.collection.JavaConverters._
      stream.iterator().asScala.filter(path =>
        path.getFileName.toString.endsWith(".v") ||
          path.getFileName.toString.endsWith(".sv")).toVector
    } finally stream.close()
  }

  private class PaperPERowSimulationTop extends Component {
    val io = TrapezoidPERowIo(cfg)
    val core = new PaperPERowBlackBox(cfg, kernelSources)
    core.io.command.valid := io.command.valid
    core.io.command.payload := io.command.payload
    io.command.ready := core.io.command.ready
    io.response.valid := core.io.response.valid
    io.response.payload := core.io.response.payload
    core.io.response.ready := io.response.ready
    io.controlPrepareCycles := core.io.controlPrepareCycles
    io.routeCycles := core.io.routeCycles
    io.macCycles := core.io.macCycles
    io.mrnCycles := core.io.mrnCycles
    io.responseCycles := core.io.responseCycles
  }

  private def modeValue(mode: String): TrapezoidDataflow.E = mode match {
    case "DenseIp" => TrapezoidDataflow.DenseIp
    case "TrIp" => TrapezoidDataflow.TrIp
    case "TrGt" => TrapezoidDataflow.TrGt
    case "TrGs" => TrapezoidDataflow.TrGs
    case other => throw new IllegalArgumentException(s"unknown mode $other")
  }

  private def clearPayload(dut: PaperPERowSimulationTop): Unit = {
    dut.io.command.payload.endOfTile #= false
    dut.io.command.payload.mode #= TrapezoidDataflow.DenseIp
    for (fiber <- 0 until cfg.maxFibers) {
      dut.io.command.payload.trip.a(fiber).valid #= false
      dut.io.command.payload.trip.a(fiber).tag #= 0
      dut.io.command.payload.trip.a(fiber).mask #= 0
      dut.io.command.payload.trip.b(fiber).valid #= false
      dut.io.command.payload.trip.b(fiber).tag #= 0
      dut.io.command.payload.trip.b(fiber).mask #= 0
      for (index <- 0 until dimension) {
        dut.io.command.payload.trip.a(fiber).values(index) #= 0
        dut.io.command.payload.trip.b(fiber).values(index) #= 0
      }
    }
    for (index <- 0 until cfg.macLanes) {
      dut.io.command.payload.gustavson.a(index).valid #= false
      dut.io.command.payload.gustavson.a(index).row #= 0
      dut.io.command.payload.gustavson.a(index).k #= 0
      dut.io.command.payload.gustavson.a(index).value #= 0
    }
    for (lane <- 0 until cfg.cacheLineWords) {
      dut.io.command.payload.gustavson.b(lane).valid #= false
      dut.io.command.payload.gustavson.b(lane).aIndex #= 0
      dut.io.command.payload.gustavson.b(lane).k #= 0
      dut.io.command.payload.gustavson.b(lane).column #= 0
      dut.io.command.payload.gustavson.b(lane).value #= 0
    }
  }

  private def mask(values: Seq[Long]): BigInt = values.zipWithIndex.foldLeft(
      BigInt(0)) { case (result, (value, index)) =>
    if (value == 0) result else result.setBit(index)
  }

  private def checksum(values: collection.Map[(Int, Int), Long]): Long =
    values.toSeq.sortBy(_._1).foldLeft(0L) {
      case (sum, ((row, column), value)) =>
        sum + value * (row * dimension.toLong + column + 1)
    }

  private def signed(value: BigInt, width: Int): Long = {
    val sign = BigInt(1) << (width - 1)
    val modulus = BigInt(1) << width
    (if ((value & sign) != 0) value - modulus else value).toLong
  }

  private def expected(workload: Workload): Map[(Int, Int), Long] = {
    val values = mutable.Map.empty[(Int, Int), Long]
    for (k <- 0 until dimension if workload.a(0, k) != 0;
         column <- 0 until dimension if workload.b(k, column) != 0) {
      val key = (0, column)
      values(key) = values.getOrElse(key, 0L) +
        workload.a(0, k) * workload.b(k, column)
    }
    values.toMap
  }

  private def tripWork(workload: Workload, columns: Seq[Int]): Int =
    columns.map { column =>
      (0 until dimension).count(k =>
        workload.a(0, k) != 0 && workload.b(k, column) != 0)
    }.sum

  private def commandsFor(workload: Workload, mode: String): Vector[CommandSpec] = {
    val sparseColumns = (0 until dimension).filter(column =>
      (0 until dimension).exists(k => workload.b(k, column) != 0)).toVector
    val columns = if (mode == "DenseIp") (0 until dimension).toVector
      else sparseColumns
    val aIndices = (0 until dimension).filter(k => workload.a(0, k) != 0).toVector
    val commands = mutable.ArrayBuffer.empty[CommandSpec]

    mode match {
      case "DenseIp" | "TrIp" =>
        var columnOffset = 0
        while (columnOffset < columns.size) {
          var groupSize = if (mode == "DenseIp") 1
            else math.min(cfg.maxFibers, columns.size - columnOffset)
          while (groupSize > 1 &&
              tripWork(workload,
                columns.slice(columnOffset, columnOffset + groupSize)) >
                cfg.macLanes) {
            groupSize -= 1
          }
          commands += TripCommand(mode,
            columns.slice(columnOffset, columnOffset + groupSize))
          columnOffset += groupSize
        }
      case "TrGs" =>
        for (k <- aIndices;
             group <- columns.grouped(cfg.cacheLineWords)) {
          commands += GustavsonCommand(mode, Vector(k), group.toVector)
        }
      case "TrGt" =>
        for (column <- columns;
             group <- aIndices.grouped(cfg.localBanks)) {
          commands += GustavsonCommand(mode, group.toVector, Vector(column))
        }
      case other => throw new IllegalArgumentException(s"unknown mode $other")
    }

    require(commands.nonEmpty, s"$mode generated no PE-row commands")
    commands.toVector.zipWithIndex.map { case (command, index) =>
      val last = index == commands.size - 1
      command match {
        case value: TripCommand => value.copy(endOfTile = last)
        case value: GustavsonCommand => value.copy(endOfTile = last)
      }
    }
  }

  private def runSimulation(): Unit = {
    val simConfig = SimConfig.withVerilator
      .disableCache
      .workspaceName("PaperSinglePERowCycleSimulation-all")
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
      .addSimulatorFlag("--hierarchical")
      .addSimulatorFlag("--verilate-jobs 1")
      .addSimulatorFlag("--build-dep-bin /usr/bin/verilator_bin")
      .addSimulatorFlag("--output-split 20000")
      .addSimulatorFlag("--output-split-cfuncs 20000")
    val measurements = mutable.ArrayBuffer.empty[Measurement]

    simConfig.compile(new PaperPERowSimulationTop).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.command.valid #= false
      dut.io.response.ready #= true
      clearPayload(dut)

      def step(): Unit = dut.clockDomain.waitSampling()

      def driveCommand(workload: Workload, command: CommandSpec): Unit = {
        clearPayload(dut)
        dut.io.command.payload.mode #= modeValue(command.mode)
        dut.io.command.payload.endOfTile #= command.endOfTile
        command match {
          case TripCommand(_, columns, _) =>
            val aValues = (0 until dimension).map(k => workload.a(0, k))
            dut.io.command.payload.trip.a(0).valid #= true
            dut.io.command.payload.trip.a(0).tag #= 0
            val dense = command.mode == "DenseIp"
            val fullMask = (BigInt(1) << dimension) - 1
            dut.io.command.payload.trip.a(0).mask #=
              (if (dense) fullMask else mask(aValues))
            for (index <- 0 until dimension) {
              dut.io.command.payload.trip.a(0).values(index) #= aValues(index)
            }
            for ((column, fiber) <- columns.zipWithIndex) {
              val bValues = (0 until dimension).map(k => workload.b(k, column))
              dut.io.command.payload.trip.b(fiber).valid #= true
              dut.io.command.payload.trip.b(fiber).tag #= column
              dut.io.command.payload.trip.b(fiber).mask #=
                (if (dense) fullMask else mask(bValues))
              for (index <- 0 until dimension) {
                dut.io.command.payload.trip.b(fiber).values(index) #=
                  bValues(index)
              }
            }
          case GustavsonCommand("TrGs", kIndices, columns, _) =>
            require(kIndices.size == 1)
            val k = kIndices.head
            dut.io.command.payload.gustavson.a(0).valid #= true
            dut.io.command.payload.gustavson.a(0).row #= 0
            dut.io.command.payload.gustavson.a(0).k #= k
            dut.io.command.payload.gustavson.a(0).value #= workload.a(0, k)
            for ((column, lane) <- columns.zipWithIndex) {
              dut.io.command.payload.gustavson.b(lane).valid #= true
              dut.io.command.payload.gustavson.b(lane).aIndex #= 0
              dut.io.command.payload.gustavson.b(lane).k #= k
              dut.io.command.payload.gustavson.b(lane).column #= column
              dut.io.command.payload.gustavson.b(lane).value #=
                workload.b(k, column)
            }
          case GustavsonCommand("TrGt", kIndices, columns, _) =>
            require(columns.size == 1 && kIndices.size <= cfg.localBanks)
            val column = columns.head
            for ((k, lane) <- kIndices.zipWithIndex) {
              val aIndex = lane * cfg.macLanesPerSubrow
              dut.io.command.payload.gustavson.a(aIndex).valid #= true
              dut.io.command.payload.gustavson.a(aIndex).row #= 0
              dut.io.command.payload.gustavson.a(aIndex).k #= k
              dut.io.command.payload.gustavson.a(aIndex).value #=
                workload.a(0, k)
              dut.io.command.payload.gustavson.b(lane).valid #= true
              dut.io.command.payload.gustavson.b(lane).aIndex #= aIndex
              dut.io.command.payload.gustavson.b(lane).k #= k
              dut.io.command.payload.gustavson.b(lane).column #= column
              dut.io.command.payload.gustavson.b(lane).value #=
                workload.b(k, column)
            }
          case other =>
            throw new IllegalArgumentException(s"unsupported command $other")
        }
      }

      def run(workload: Workload, mode: String): Measurement = {
        val commands = commandsFor(workload, mode)
        val expectedValues = expected(workload)
        println(s"Starting ${workload.category.name} $mode " +
          s"sample=${workload.sample} commands=${commands.size}")
        val observed = mutable.Map.empty[(Int, Int), Long]
        var nextCommand = 0
        var acceptedCommands = 0
        var responses = 0
        var cycles = 0L
        var firstAccepted = -1L
        var lastResponse = -1L
        dut.io.command.valid #= false
        dut.io.response.ready #= true
        step()
        val controlPrepareStart = dut.io.controlPrepareCycles.toLong
        val routeStart = dut.io.routeCycles.toLong
        val macStart = dut.io.macCycles.toLong
        val mrnStart = dut.io.mrnCycles.toLong
        val responseStart = dut.io.responseCycles.toLong

        dut.io.command.valid #= false
        dut.io.response.ready #= true
        val cycleLimit = 100000L
        while (responses < commands.size && cycles < cycleLimit) {
          if (nextCommand < commands.size) {
            driveCommand(workload, commands(nextCommand))
            dut.io.command.valid #= true
          } else {
            dut.io.command.valid #= false
          }
          dut.io.response.ready #= true
          sleep(1)

          val commandFire = dut.io.command.valid.toBoolean &&
            dut.io.command.ready.toBoolean
          val responseFire = dut.io.response.valid.toBoolean &&
            dut.io.response.ready.toBoolean
          if (commandFire) {
            if (firstAccepted < 0) firstAccepted = cycles
            acceptedCommands += 1
            nextCommand += 1
          }
          if (responseFire) {
            require(!dut.io.response.payload.overflow.toBoolean,
              s"$mode ${workload.category.name} reported overflow")
            val response = dut.io.response.payload
            for (index <- 0 until response.count.toInt) {
              val result = response.results(index)
              val key = (result.row.toInt, result.column.toInt)
              val value = signed(result.value.toBigInt, cfg.accumulatorWidth)
              observed(key) = observed.getOrElse(key, 0L) + value
            }
            responses += 1
            lastResponse = cycles
          }
          step()
          cycles += 1
        }
        dut.io.command.valid #= false

        require(cycles < cycleLimit,
          s"$mode ${workload.category.name} timed out after $cycleLimit cycles: " +
            s"accepted=$acceptedCommands responses=$responses commands=${commands.size}")
        require(acceptedCommands == commands.size,
          s"$mode accepted $acceptedCommands/${commands.size} commands")
        require(observed.toMap == expectedValues,
          s"$mode ${workload.category.name} sample=${workload.sample}: " +
            s"observed=$observed expected=$expectedValues")
        require(firstAccepted >= 0 && lastResponse >= firstAccepted)
        val usefulMacs = (for {
          row <- 0 until dimension
          k <- 0 until dimension if workload.a(row, k) != 0
          column <- 0 until dimension if workload.b(k, column) != 0
        } yield 1L).sum
        val measurement = Measurement(workload.category, workload.sample, mode,
          lastResponse - firstAccepted + 1, commands.size, responses,
          observed.size, usefulMacs, commands.size.toLong * cfg.macLanes,
          checksum(observed),
          dut.io.controlPrepareCycles.toLong - controlPrepareStart,
          dut.io.routeCycles.toLong - routeStart,
          dut.io.macCycles.toLong - macStart,
          dut.io.mrnCycles.toLong - mrnStart,
          dut.io.responseCycles.toLong - responseStart)
        println(s"Completed ${workload.category.name} $mode: " +
          s"cycles=${measurement.cycles} control=${measurement.controlPrepareCycles}")
        measurement
      }

      for (workload <- workloads; mode <- modes) {
        measurements += run(workload, mode)
      }
    }

    def densityText(basisPoints: Int): String =
      f"${basisPoints / 100.0}%.2f%%"

    println("\nTrapezoid paper-scale single PE-row RTL throughput simulation")
    println(s"Configuration: fiberWidth=${cfg.fiberWidth}, macLanes=${cfg.macLanes}, " +
      s"outputLanes=${cfg.outputLanes}, peRows=${cfg.peRows}; samples=$samples")
    println("Workload: one active output row of the same 128x128 tile for every mode.")
    println("Boundary: first command acceptance through the final response consumption.")
    println("Commands are streamed while responses are consumed by the testbench.")
    println("| Category | A density | B density | Paper mode | " +
      modes.mkString(" | ") + " |")
    println("| :--- | ---: | ---: | :--- | " +
      modes.map(_ => "---:").mkString(" | ") + " |")
    categories.foreach { category =>
      val values = modes.map { mode =>
        val selected = measurements.filter(measurement =>
          measurement.category == category && measurement.mode == mode)
        f"${selected.map(_.cycles).sum.toDouble / selected.size}%.1f"
      }
      println(s"| ${category.name} | ${densityText(category.aBasisPoints)} | " +
        s"${densityText(category.bBasisPoints)} | ${category.paperMode} | " +
        s"${values.mkString(" | ")} |")
    }
    println("\nDetailed streamed measurements")
    println("| Category | Mode | E2E cycles | Control prepare | Route | " +
      "MAC | MRN | Response | " +
      "Commands | Responses | Output coordinates | Useful MACs | Lane slots | " +
      "Utilization | Checksum |")
    println("| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | " +
      "---: | ---: | " +
      "---: | ---: | ---: | ---: | ---: |")
    measurements.foreach { measurement =>
      val utilization = 100.0 * measurement.usefulMacs / measurement.laneSlots
      println(s"| ${measurement.category.name} | ${measurement.mode} | " +
        s"${measurement.cycles} | ${measurement.controlPrepareCycles} | " +
        s"${measurement.routeCycles} | " +
        s"${measurement.macCycles} | ${measurement.mrnCycles} | " +
        s"${measurement.responseCycles} | ${measurement.commandCount} | " +
        s"${measurement.responseCount} | ${measurement.outputCount} | " +
        s"${measurement.usefulMacs} | ${measurement.laneSlots} | " +
        f"$utilization%.2f%% | ${measurement.checksum} |")
    }
  }

  runSimulation()
}
