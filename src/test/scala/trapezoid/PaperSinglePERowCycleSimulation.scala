package trapezoid

import spinal.core.sim._
import trapezoid.model.DenseMatrix

import scala.collection.mutable

/** Real Verilator simulation of one paper-sized PE row.
  *
  * The datapath keeps the paper widths (128-wide fibers, 128 MAC lanes and
  * 16 output lanes) but instantiates exactly one TrapezoidPERow.  This is a
  * PE-row test, not a full-array or descriptor/DMA test.  Every mode is run
  * against all six HS/MS/D density pairs and every response is checked against
  * a software reference.
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
  private val fiberWidth = cfg.fiberWidth
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
                              a: Vector[Long], b: Vector[Long])
  private case class Measurement(category: Category, sample: Int,
                                 mode: String, cycles: Long,
                                 commandCount: Int, responseCount: Int,
                                 outputCount: Int, checksum: Long)

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
    math.max(1, ((fiberWidth.toLong * basisPoints + 5000) / 10000).toInt)

  private def densityVector(basisPoints: Int, sample: Int,
                            salt: Int): Vector[Long] = {
    val nonZeros = if (basisPoints == 10000) fiberWidth
      else nonZerosFor(basisPoints)
    Vector.tabulate(fiberWidth) { index =>
      if (index < nonZeros) 1L + ((index + sample + salt) & 3) else 0L
    }
  }

  private val workloads = categories.flatMap { category =>
    Vector.tabulate(samples) { sample =>
      Workload(category, sample,
        densityVector(category.aBasisPoints, sample, 3),
        densityVector(category.bBasisPoints, sample, 11))
    }
  }

  private def modeValue(mode: String): TrapezoidDataflow.E = mode match {
    case "DenseIp" => TrapezoidDataflow.DenseIp
    case "TrIp" => TrapezoidDataflow.TrIp
    case "TrGt" => TrapezoidDataflow.TrGt
    case "TrGs" => TrapezoidDataflow.TrGs
    case other => throw new IllegalArgumentException(s"unknown mode $other")
  }

  private def clearPayload(dut: TrapezoidPERow): Unit = {
    dut.io.command.payload.endOfTile #= false
    dut.io.command.payload.mode #= TrapezoidDataflow.DenseIp
    for (fiber <- 0 until cfg.maxFibers) {
      dut.io.command.payload.trip.a(fiber).valid #= false
      dut.io.command.payload.trip.a(fiber).tag #= 0
      dut.io.command.payload.trip.a(fiber).mask #= 0
      dut.io.command.payload.trip.b(fiber).valid #= false
      dut.io.command.payload.trip.b(fiber).tag #= 0
      dut.io.command.payload.trip.b(fiber).mask #= 0
      for (index <- 0 until fiberWidth) {
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

  private def mask(values: Vector[Long]): BigInt = values.zipWithIndex.foldLeft(
      BigInt(0)) { case (result, (value, index)) =>
    if (value == 0) result else result.setBit(index)
  }

  private def checksum(values: collection.Map[(Int, Int), Long]): Long =
    values.toSeq.sortBy(_._1).foldLeft(0L) {
      case (sum, ((row, column), value)) => sum + value * (row * 128L + column + 1)
    }

  private def signed(value: BigInt, width: Int): Long = {
    val sign = BigInt(1) << (width - 1)
    val modulus = BigInt(1) << width
    (if ((value & sign) != 0) value - modulus else value).toLong
  }

  private def expectedGustavson(workload: Workload): Map[(Int, Int), Long] = {
    val values = mutable.Map.empty[(Int, Int), Long]
    for (k <- 0 until fiberWidth if workload.a(k) != 0;
         column <- 0 until fiberWidth if workload.b(column) != 0) {
      val bValue = 1L + ((k + column + workload.sample + 11) & 3)
      val key = (3, column)
      values(key) = values.getOrElse(key, 0L) + workload.a(k) * bValue
    }
    values.toMap
  }

  private def runSimulation(): Unit = {
    val simConfig = SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
    val measurements = mutable.ArrayBuffer.empty[Measurement]

    simConfig.compile(new TrapezoidPERow(cfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.command.valid #= false
      dut.io.response.ready #= true

      def step(): Unit = dut.clockDomain.waitSampling()

      def prepareTripCommand(workload: Workload, mode: String): Unit = {
        val a = workload.a
        val b = workload.b
        val aMask = mask(a)
        val bMask = mask(b)
        clearPayload(dut)
        dut.io.command.payload.mode #= modeValue(mode)
        dut.io.command.payload.endOfTile #= true
        dut.io.command.payload.trip.a(0).valid #= true
        dut.io.command.payload.trip.a(0).tag #= 3
        dut.io.command.payload.trip.a(0).mask #= aMask
        dut.io.command.payload.trip.b(0).valid #= true
        dut.io.command.payload.trip.b(0).tag #= 5
        dut.io.command.payload.trip.b(0).mask #= bMask
        for (index <- 0 until fiberWidth) {
          dut.io.command.payload.trip.a(0).values(index) #= a(index)
          dut.io.command.payload.trip.b(0).values(index) #= b(index)
        }
      }

      def prepareGustavsonCommand(workload: Workload, mode: String,
                                  kIndices: Seq[Int], columnBase: Int,
                                  endOfTile: Boolean): Unit = {
        clearPayload(dut)
        dut.io.command.payload.mode #= modeValue(mode)
        dut.io.command.payload.endOfTile #= endOfTile
        if (mode == "TrGs") {
          val k = kIndices.head
          dut.io.command.payload.gustavson.a(0).valid #= true
          dut.io.command.payload.gustavson.a(0).row #= 3
          dut.io.command.payload.gustavson.a(0).k #= k
          dut.io.command.payload.gustavson.a(0).value #= workload.a(k)
          for (lane <- 0 until cfg.cacheLineWords) {
            val column = columnBase + lane
            if (column < fiberWidth && workload.b(column) != 0) {
              dut.io.command.payload.gustavson.b(lane).valid #= true
              dut.io.command.payload.gustavson.b(lane).aIndex #= 0
              dut.io.command.payload.gustavson.b(lane).k #= k
              dut.io.command.payload.gustavson.b(lane).column #= column
              dut.io.command.payload.gustavson.b(lane).value #=
                1L + ((k + column + workload.sample + 11) & 3)
            }
          }
        } else {
          for ((k, lane) <- kIndices.zipWithIndex) {
            val aIndex = lane * cfg.macLanesPerSubrow
            dut.io.command.payload.gustavson.a(aIndex).valid #= true
            dut.io.command.payload.gustavson.a(aIndex).row #= 3
            dut.io.command.payload.gustavson.a(aIndex).k #= k
            dut.io.command.payload.gustavson.a(aIndex).value #= workload.a(k)
            val column = columnBase
            dut.io.command.payload.gustavson.b(lane).valid #= true
            dut.io.command.payload.gustavson.b(lane).aIndex #= aIndex
            dut.io.command.payload.gustavson.b(lane).k #= k
            dut.io.command.payload.gustavson.b(lane).column #= column
            dut.io.command.payload.gustavson.b(lane).value #=
              1L + ((k + column + workload.sample + 11) & 3)
          }
        }
      }

      def issueAndCollect(observed: mutable.Map[(Int, Int), Long]): Long = {
        var cycles = 0L
        dut.io.command.valid #= true
        while (!dut.io.command.ready.toBoolean) {
          step()
          cycles += 1
        }
        step()
        cycles += 1
        dut.io.command.valid #= false
        while (!dut.io.response.valid.toBoolean) {
          step()
          cycles += 1
        }
        require(!dut.io.response.payload.overflow.toBoolean,
          "single PE row reported overflow")
        val response = dut.io.response.payload
        for (index <- 0 until response.count.toInt) {
          val result = response.results(index)
          val key = (result.row.toInt, result.column.toInt)
          val value = signed(result.value.toBigInt, cfg.accumulatorWidth)
          observed(key) = observed.getOrElse(key, 0L) + value
        }
        step()
        cycles += 1
        cycles
      }

      def run(workload: Workload, mode: String): Measurement = {
        dut.io.command.valid #= false
        dut.io.response.ready #= true
        step()

        val observed = mutable.Map.empty[(Int, Int), Long]
        val (commandCount, elapsedCycles) = if (mode == "DenseIp" || mode == "TrIp") {
          prepareTripCommand(workload, mode)
          (1, issueAndCollect(observed))
        } else if (mode == "TrGs") {
          val aIndices = workload.a.indices.filter(workload.a(_) != 0)
          val bColumns = workload.b.indices.filter(workload.b(_) != 0)
          val bGroups = bColumns.grouped(cfg.cacheLineWords).toVector
          var count = 0
          var elapsed = 0L
          for ((k, kIndex) <- aIndices.zipWithIndex;
               (columnGroup, groupIndex) <- bGroups.zipWithIndex) {
            prepareGustavsonCommand(workload, mode, Seq(k), columnGroup.head,
              endOfTile = kIndex == aIndices.size - 1 &&
                groupIndex == bGroups.size - 1)
            elapsed += issueAndCollect(observed)
            count += 1
          }
          (count, elapsed)
        } else {
          val aIndices = workload.a.indices.filter(workload.a(_) != 0)
          val bColumns = workload.b.indices.filter(workload.b(_) != 0)
          var count = 0
          var elapsed = 0L
          val aGroups = aIndices.grouped(cfg.localBanks).toVector
          for ((column, columnIndex) <- bColumns.zipWithIndex;
               (group, groupIndex) <- aGroups.zipWithIndex) {
            prepareGustavsonCommand(workload, mode, group, column,
              endOfTile = columnIndex == bColumns.size - 1 &&
                groupIndex == aGroups.size - 1)
            elapsed += issueAndCollect(observed)
            count += 1
          }
          (count, elapsed)
        }

        val expected = if (mode == "DenseIp" || mode == "TrIp") {
          val value = (0 until fiberWidth).map(index =>
            workload.a(index) * workload.b(index)).sum
          if (value == 0) Map.empty[(Int, Int), Long]
          else Map((3, 5) -> value)
        } else expectedGustavson(workload)
        require(observed.toMap == expected,
          s"$mode ${workload.category.name} sample=${workload.sample}: " +
            s"observed=$observed expected=$expected")
        Measurement(workload.category, workload.sample, mode, elapsedCycles,
          commandCount, commandCount, observed.size, checksum(observed))
      }

      for (workload <- workloads; mode <- modes) {
        measurements += run(workload, mode)
      }
    }

    println("\nTrapezoid paper-scale single PE-row RTL simulation")
    println(s"Configuration: fiberWidth=${cfg.fiberWidth}, macLanes=${cfg.macLanes}, " +
      s"outputLanes=${cfg.outputLanes}, peRows=${cfg.peRows}; samples=$samples")
    println("Boundary: direct PERow command acceptance through response consumption.")
    println("All four modes run every HS/MS/D density pair; cycles are Verilator RTL edges.")
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
      println(s"| ${category.name} | ${category.aBasisPoints / 100.0}%% | " +
        s"${category.bBasisPoints / 100.0}%% | ${category.paperMode} | " +
        s"${values.mkString(" | ")} |")
    }
    println("\nDetailed command counts and checksums")
    println("| Category | Mode | Commands | Responses | Output coordinates | Checksum |")
    println("| :--- | :--- | ---: | ---: | ---: | ---: |")
    measurements.foreach { measurement =>
      println(s"| ${measurement.category.name} | ${measurement.mode} | " +
        s"${measurement.commandCount} | ${measurement.responseCount} | " +
        s"${measurement.outputCount} | ${measurement.checksum} |")
    }
  }

  runSimulation()
}
