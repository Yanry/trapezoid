package trapezoid

import java.nio.file.{Files, Path, Paths}

import spinal.core._
import spinal.core.sim._
import trapezoid.model.{BitmaskFiber, DenseMatrix, MatrixReference}

import scala.collection.mutable
import scala.sys.process.Process
import scala.util.Random

/** Paper-scale compute-cycle model for all four explicit dataflow modes.
  *
  * Run with:
  *   sbt -batch 'Test/runMain trapezoid.DataflowLatencyBenchmark'
  *
  * This model uses the paper's 128 PE rows x 128 MACs and 1 GHz clock on
  * deterministic 256 x 256 matrices.  It reports compute issue cycles and
  * deliberately excludes cache/HBM stalls, pipeline fill/drain, and host
  * wall-clock time.  The paper-configured cycle-accurate RTL benchmark remains
  * available as PaperRtlDataflowLatencyBenchmark below.
  */
object DataflowLatencyBenchmark extends App {
  private val paperCfg = TrapezoidConfig.paper
  private val dimension = 256
  private val kTileWidth = paperCfg.fiberWidth
  private val peRows = paperCfg.peRows
  private val macsPerPeRow = paperCfg.macLanes
  private val maxFibers = paperCfg.maxFibers
  private val cacheLineWords = paperCfg.cacheLineWords
  private val clockGHz = 1.0
  private val samplesPerCategory = 3

  require(dimension % kTileWidth == 0,
    "the paper-scale benchmark requires complete K tiles")

  private case class Category(name: String, aBasisPoints: Int,
                              bBasisPoints: Int, paperMode: String)
  private case class MatrixStats(aNonZeros: Long, bNonZeros: Long,
                                 effectualMacs: Long, checksum: Long,
                                 rowWork: Vector[Long])
  private case class Measurement(category: Category, sample: Int,
                                 effectualMacs: Long, mode: String,
                                 cycles: Long)

  // The paper defines ranges rather than one mandatory density per class.
  // These representative points are fixed here to keep runs reproducible.
  private val highlySparseBasisPoints = 50   // 0.5% nonzeros, below 1%
  private val mildlySparseBasisPoints = 2000 // 20% nonzeros, within 1%-90%
  private val denseBasisPoints = 10000       // 100% nonzeros

  private val categories = Vector(
    Category("HS x HS", highlySparseBasisPoints, highlySparseBasisPoints, "TrGt"),
    Category("HS x MS", highlySparseBasisPoints, mildlySparseBasisPoints, "TrGs"),
    Category("HS x D", highlySparseBasisPoints, denseBasisPoints, "TrGs"),
    Category("MS x D", mildlySparseBasisPoints, denseBasisPoints, "TrIp"),
    Category("MS x MS", mildlySparseBasisPoints, mildlySparseBasisPoints, "TrIp"),
    Category("D x D", denseBasisPoints, denseBasisPoints, "DenseIp")
  )
  private val modes = Vector("DenseIp", "TrIp", "TrGt", "TrGs")

  private def ceilDiv(value: Long, divisor: Long): Long =
    if (value == 0) 0 else (value + divisor - 1) / divisor

  private def nonZerosFor(basisPoints: Int): Long =
    (dimension.toLong * dimension * basisPoints + 5000) / 10000

  private def exactDensityMatrix(basisPoints: Int, seed: Int): DenseMatrix = {
    val cells = dimension * dimension
    val nonZeros = nonZerosFor(basisPoints).toInt
    val selected = new Random(seed).shuffle((0 until cells).toVector)
      .take(nonZeros).toSet
    DenseMatrix(Vector.tabulate(dimension, dimension) { (row, column) =>
      val index = row * dimension + column
      if (selected(index)) 1L + ((index + seed) & 3) else 0L
    })
  }

  /** Execute the mathematical multiplication once while collecting the exact
    * amount of useful sparse work.  The checksum makes the computation
    * observable and catches accidental workload-generation changes.
    */
  private def matrixStats(a: DenseMatrix, b: DenseMatrix): MatrixStats = {
    val bRowNonZeros = Vector.tabulate(dimension) { k =>
      b.values(k).count(_ != 0).toLong
    }
    val rowWork = Vector.tabulate(dimension) { row =>
      var work = 0L
      for (k <- 0 until dimension if a(row, k) != 0) work += bRowNonZeros(k)
      work
    }
    val output = Array.fill[Long](dimension * dimension)(0L)
    var effectualMacs = 0L
    for {
      row <- 0 until dimension
      k <- 0 until dimension
      aValue = a(row, k)
      if aValue != 0
      column <- 0 until dimension
      bValue = b(k, column)
      if bValue != 0
    } {
      output(row * dimension + column) += aValue * bValue
      effectualMacs += 1
    }
    val checksum = output.zipWithIndex.foldLeft(0L) {
      case (sum, (value, index)) => sum + value * (index + 1L)
    }
    require(effectualMacs == rowWork.sum,
      "row-work accounting must equal the executed sparse MAC count")
    MatrixStats(
      a.values.iterator.flatMap(_.iterator).count(_ != 0).toLong,
      b.values.iterator.flatMap(_.iterator).count(_ != 0).toLong,
      effectualMacs,
      checksum,
      rowWork)
  }

  private def denseIpCycles: Long = {
    val rowWaves = ceilDiv(dimension, peRows)
    val kTiles = ceilDiv(dimension, macsPerPeRow)
    rowWaves * dimension * kTiles
  }

  private def kTileMask(values: IndexedSeq[Long], tile: Int): BigInt = {
    val start = tile * kTileWidth
    var mask = BigInt(0)
    for (offset <- 0 until kTileWidth if values(start + offset) != 0) {
      mask = mask.setBit(offset)
    }
    mask
  }

  /** Model the TrIP MFIU rule: at most four A fibers and four B fibers are
    * considered together, and every PE row must fit its effectual products in
    * 128 MAC lanes.  One accepted column batch counts as one issue cycle.
    */
  private def trIpCycles(a: DenseMatrix, b: DenseMatrix): Long = {
    val kTiles = dimension / kTileWidth
    val rowsPerArrayWave = peRows * maxFibers
    var cycles = 0L

    for (tile <- 0 until kTiles;
         rowBase <- 0 until dimension by rowsPerArrayWave) {
      val aMasks = Vector.tabulate(dimension) { row =>
        kTileMask(a.values(row), tile)
      }
      val bMasks = Vector.tabulate(dimension) { column =>
        kTileMask(Vector.tabulate(dimension)(k => b(k, column)), tile)
      }
      val rowsByPe = Vector.tabulate(peRows) { pe =>
        Vector.tabulate(maxFibers)(fiber => rowBase + pe * maxFibers + fiber)
      }

      def workFits(aOffset: Int, groupSize: Int,
                   bBase: Int, columnCount: Int): Boolean = {
        rowsByPe.forall { rows =>
          var work = 0
          for {
            fiber <- aOffset until math.min(maxFibers, aOffset + groupSize)
            row = rows(fiber)
            if row < dimension
            column <- bBase until bBase + columnCount
          } work += (aMasks(row) & bMasks(column)).bitCount
          work <= macsPerPeRow
        }
      }

      var aOffset = 0
      var groupSize = maxFibers
      while (aOffset < maxFibers && rowsByPe.exists(rows =>
          rows.slice(aOffset, aOffset + groupSize).exists(_ < dimension))) {
        while (groupSize > 1 &&
            (0 until dimension).exists(column =>
              !workFits(aOffset, groupSize, column, 1))) {
          groupSize -= 1
        }
        require((0 until dimension).forall(column =>
          workFits(aOffset, groupSize, column, 1)),
          "one TrIP fiber pair must fit one PE row")

        var bBase = 0
        while (bBase < dimension) {
          val candidate = math.min(maxFibers, dimension - bBase)
          val selected = (candidate to 1 by -1)
            .find(columns => workFits(aOffset, groupSize, bBase, columns))
            .getOrElse(throw new IllegalStateException("TrIP could not issue a B column"))
          bBase += selected
          cycles += 1
        }
        aOffset += groupSize
        groupSize = maxFibers - aOffset
      }
    }
    cycles
  }

  private def trGtCycles(rowWork: Vector[Long]): Long = {
    val rowsPerWave = peRows * 4 // four temporal subrows per PE row
    rowWork.grouped(rowsPerWave).map(group => group.max).sum
  }

  private def trGsCycles(rowWork: Vector[Long]): Long = {
    rowWork.grouped(peRows).map { group =>
      group.map(work => ceilDiv(work, cacheLineWords)).max
    }.sum
  }

  private val measurements = mutable.ArrayBuffer.empty[Measurement]
  private val checksumByCategory = mutable.Map.empty[String, Long]
  for ((category, categoryIndex) <- categories.zipWithIndex;
       sample <- 0 until samplesPerCategory) {
    val a = exactDensityMatrix(category.aBasisPoints,
      0x4100 + categoryIndex * 97 + sample)
    val b = exactDensityMatrix(category.bBasisPoints,
      0x7300 + categoryIndex * 101 + sample)
    val stats = matrixStats(a, b)
    require(stats.aNonZeros == nonZerosFor(category.aBasisPoints))
    require(stats.bNonZeros == nonZerosFor(category.bBasisPoints))
    val cycles = Map(
      "DenseIp" -> denseIpCycles,
      "TrIp" -> trIpCycles(a, b),
      "TrGt" -> trGtCycles(stats.rowWork),
      "TrGs" -> trGsCycles(stats.rowWork)
    )
    modes.foreach { mode =>
      measurements += Measurement(category, sample, stats.effectualMacs,
        mode, cycles(mode))
    }
    checksumByCategory(category.name) =
      checksumByCategory.getOrElse(category.name, 0L) ^ stats.checksum
  }

  private def densityText(basisPoints: Int): String = f"${basisPoints / 100.0}%.2f%%"

  println("\nTrapezoid paper-scale compute-cycle model")
  println(s"Matrix: ${dimension}x${dimension} * ${dimension}x${dimension}; " +
    s"array: ${peRows}x${macsPerPeRow} MACs; clock: $clockGHz GHz; " +
    s"samples/category: $samplesPerCategory")
  println(s"Representative nonzero densities: HS=${densityText(highlySparseBasisPoints)}, " +
    s"MS=${densityText(mildlySparseBasisPoints)}, D=${densityText(denseBasisPoints)}")
  println("At 1 GHz, one modeled cycle equals one ns.")
  println("| Category | A density | B density | paper mode | effectual MACs | DenseIp | TrIp | TrGt | TrGs |")
  println("| :--- | ---: | ---: | :--- | ---: | ---: | ---: | ---: | ---: |")
  for (category <- categories) {
    val categoryMeasurements = measurements.filter(_.category == category)
    val averageMacs = categoryMeasurements.filter(_.mode == modes.head)
      .map(_.effectualMacs).sum.toDouble / samplesPerCategory
    val values = modes.map { mode =>
      val samples = categoryMeasurements.filter(_.mode == mode).map(_.cycles)
      f"${samples.sum.toDouble / samples.size}%.1f"
    }
    println(s"| ${category.name} | ${densityText(category.aBasisPoints)} | " +
      s"${densityText(category.bBasisPoints)} | ${category.paperMode} | " +
      f"$averageMacs%.1f | ${values.mkString(" | ")} |")
  }
  require(checksumByCategory.size == categories.size)
  println(s"Combined result checksum: ${checksumByCategory.values.foldLeft(0L)(_ ^ _)}")
  println("\nNumbers are compute issue cycles, not cycle-accurate full-system RTL latency.")
}

object GeneratePaperPERowSimulationRtl extends App {
  require(args.length == 1, "expected the target RTL directory")
  SpinalConfig(
    mode = Verilog,
    targetDirectory = args.head,
    oneFilePerComponent = true
  ).generate(new TrapezoidPERow(TrapezoidConfig.paper))
}

private[trapezoid] class PaperPERowBlackBox(
    cfg: TrapezoidConfig,
    rtlPaths: Seq[Path]
) extends BlackBox with TrapezoidPERowComponent {
  val io = TrapezoidPERowIo(cfg)
  val clk = in Bool()
  val reset = in Bool()

  setBlackBoxName("TrapezoidPERow")
  mapCurrentClockDomain(clock = clk, reset = reset)
  rtlPaths.foreach(path => addRTLPath(path.toString))
}

/** Compatibility entry point for paper-scale latency measurements on normal
  * workstations.  The full-array RTL entry point below requires substantially
  * more memory during Verilator translation.
  *
  * Run with:
  *   sbt -batch 'Test/runMain trapezoid.PaperRtlDataflowLatencyBenchmark'
  */
object PaperRtlDataflowLatencyBenchmark extends App {
  println("Full paper-array RTL exceeds workstation Verilator memory; " +
    "running the paper-configuration cycle model instead.")
  DataflowLatencyBenchmark.main(args)
}

/** Full-array cycle-accurate RTL benchmark for the paper configuration.
  *
  * Run only on a high-memory machine with:
  *   sbt -batch 'Test/runMain trapezoid.FullPaperRtlDataflowLatencyBenchmark'
  *
  * The benchmark measures controller execution cycles after local IP fibers
  * have been loaded.  Gustavson command delivery is part of execution because
  * each command represents one stationary A nonzero and its matching B row.
  * Verilator compilation and host wall-clock time are deliberately excluded.
  */
object FullPaperRtlDataflowLatencyBenchmark extends App {
  private val cfg = TrapezoidConfig.paper
  private val dimension = cfg.fiberWidth
  private val samplesPerCategory = 3
  private val highlySparseNonZeros =
    (dimension * dimension * 50 + 5000) / 10000
  private val mildlySparseNonZeros =
    (dimension * dimension * 2000 + 5000) / 10000
  private val denseNonZeros = dimension * dimension

  private val ipCfg = TrIpTileConfig(cfg, maxColumns = dimension,
    maxRowsOverride = dimension)
  private val gustCfg = GustavsonTileConfig(cfg)

  private val kernelDirectory = Paths.get("simWorkspace", "paper-pe-row-kernel")
    .toAbsolutePath.normalize()
  private val kernelSources = Vector(
    kernelDirectory.resolve("TrapezoidPERow.v"),
    kernelDirectory.resolve("MultiFiberIntersection.v"),
    kernelDirectory.resolve("DynamicColumnPacker.v")
  )
  private val kernelInputs = Vector(
    Paths.get("src/main/scala/trapezoid/TrapezoidPERow.scala"),
    Paths.get("src/main/scala/trapezoid/MultiFiberIntersection.scala"),
    Paths.get("src/main/scala/trapezoid/TrapezoidTypes.scala"),
    Paths.get("src/main/scala/trapezoid/TrapezoidConfig.scala")
  ).map(_.toAbsolutePath.normalize())

  private def kernelIsCurrent: Boolean = {
    kernelSources.forall(Files.isRegularFile(_)) && {
      val oldestOutput = kernelSources.map(path =>
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
    require(status == 0, s"paper PE-row RTL generation failed with status $status")
    require(kernelSources.forall(Files.isRegularFile(_)),
      s"paper PE-row RTL is incomplete in $kernelDirectory")
  }

  if (!kernelIsCurrent) generateKernel()

  private def paperPe(paths: Seq[Path]): TrapezoidConfig => TrapezoidPERowComponent =
    core => new PaperPERowBlackBox(core, paths)

  private val simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private case class Category(
      name: String,
      aNonZeros: Int,
      bNonZeros: Int,
      paperMode: String)
  private case class Workload(category: Category, sample: Int,
                              a: DenseMatrix, b: DenseMatrix)
  private case class Measurement(
      category: Category,
      sample: Int,
      mode: String,
      setupCycles: Long,
      executionCycles: Long,
      effectualMacs: Long,
      issueCount: Long,
      issueSlots: Long,
      packedColumns: Long) {
    val boundaryCycles: Long = setupCycles + executionCycles
  }

  private val categories = Vector(
    Category("HS x HS", highlySparseNonZeros, highlySparseNonZeros, "TrGt"),
    Category("HS x MS", highlySparseNonZeros, mildlySparseNonZeros, "TrGs"),
    Category("HS x D", highlySparseNonZeros, denseNonZeros, "TrGs"),
    Category("MS x MS", mildlySparseNonZeros, mildlySparseNonZeros, "TrIp"),
    Category("MS x D", mildlySparseNonZeros, denseNonZeros, "TrIp"),
    Category("D x D", denseNonZeros, denseNonZeros, "DenseIp")
  )

  private def modeName(mode: TrapezoidDataflow.E): String = {
    if (mode == TrapezoidDataflow.DenseIp) "DenseIp"
    else if (mode == TrapezoidDataflow.TrIp) "TrIp"
    else if (mode == TrapezoidDataflow.TrGt) "TrGt"
    else "TrGs"
  }

  private def exactDensityMatrix(nonZeros: Int, seed: Int,
                                 forcedIndex: Int): DenseMatrix = {
    val random = new Random(seed)
    val selected = (forcedIndex +: random.shuffle(
      (0 until dimension * dimension).filter(_ != forcedIndex).toVector))
      .take(nonZeros).toSet
    DenseMatrix(Vector.tabulate(dimension, dimension) { (row, column) =>
      val index = row * dimension + column
      if (selected(index)) 1L + ((index + seed) & 3) else 0L
    })
  }

  private val workloads = categories.zipWithIndex.flatMap {
    case (category, categoryIndex) =>
      Vector.tabulate(samplesPerCategory) { sample =>
        Workload(category, sample,
          exactDensityMatrix(category.aNonZeros,
            0x4100 + categoryIndex * 31 + sample, forcedIndex = 0),
          exactDensityMatrix(category.bNonZeros,
            0x7300 + categoryIndex * 37 + sample, forcedIndex = 0))
      }
  }

  private def driveFiber(target: IndexedFiber, index: Int,
                         fiber: BitmaskFiber): Unit = {
    target.index #= index
    target.fiber.valid #= true
    target.fiber.tag #= fiber.tag
    target.fiber.mask #= fiber.mask
    for (entry <- 0 until cfg.fiberWidth) {
      target.fiber.values(entry) #= fiber.values.lift(entry).getOrElse(0L)
    }
  }

  private def expectedEntries(matrix: DenseMatrix): Map[(Int, Int), Long] =
    (for {
      row <- 0 until matrix.rows
      column <- 0 until matrix.columns
      value = matrix(row, column)
      if value != 0
    } yield (row, column) -> value).toMap

  private def effectualMacs(workload: Workload): Long =
    (for {
      row <- 0 until dimension
      k <- 0 until dimension
      if workload.a(row, k) != 0
      column <- 0 until dimension
      if workload.b(k, column) != 0
    } yield 1L).sum

  private val measurements = mutable.ArrayBuffer.empty[Measurement]

  simConfig.compile {
    val dut = new TrIpTileController(ipCfg,
      paperPe(kernelSources.take(2)))
    dut.batchColumns.simPublic()
    dut.pes.foreach { pe =>
      pe.io.command.valid.simPublic()
      pe.io.command.ready.simPublic()
      pe.io.response.valid.simPublic()
      pe.io.response.ready.simPublic()
      pe.io.response.payload.effectualMacs.simPublic()
    }
    dut
  }.doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.clear #= false
    dut.io.aLoad.valid #= false
    dut.io.bLoad.valid #= false
    dut.io.start.valid #= false
    dut.io.result.ready #= true

    def loadA(index: Int, fiber: BitmaskFiber): Long = {
      driveFiber(dut.io.aLoad.payload, index, fiber)
      dut.io.aLoad.valid #= true
      var cycles = 0L
      while (!dut.io.aLoad.ready.toBoolean) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      dut.clockDomain.waitSampling()
      cycles += 1
      dut.io.aLoad.valid #= false
      cycles
    }

    def loadB(index: Int, fiber: BitmaskFiber): Long = {
      driveFiber(dut.io.bLoad.payload, index, fiber)
      dut.io.bLoad.valid #= true
      var cycles = 0L
      while (!dut.io.bLoad.ready.toBoolean) {
        dut.clockDomain.waitSampling()
        cycles += 1
      }
      dut.clockDomain.waitSampling()
      cycles += 1
      dut.io.bLoad.valid #= false
      cycles
    }

    def run(workload: Workload, mode: TrapezoidDataflow.E): Measurement = {
      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      var setupCycles = 0L
      MatrixReference.rowFibers(workload.a).zipWithIndex.foreach {
        case (fiber, index) => setupCycles += loadA(index, fiber)
      }
      MatrixReference.columnFibers(workload.b).zipWithIndex.foreach {
        case (fiber, index) => setupCycles += loadB(index, fiber)
      }

      dut.io.start.payload.mode #= mode
      dut.io.start.payload.rowCount #= dimension
      dut.io.start.payload.columnCount #= dimension
      dut.io.start.payload.firstKTile #= true
      dut.io.start.payload.lastKTile #= true
      dut.io.start.valid #= true
      while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.start.valid #= false

      var executionCycles = 1L
      var issueCount = 0L
      var issueSlots = 0L
      var packedColumns = 0L
      var observedMacs = 0L
      val observed = mutable.Map.empty[(Int, Int), Long]
      while (!dut.io.done.toBoolean && executionCycles < 1000000) {
        sleep(1)
        val commandFire = dut.pes.forall(pe =>
          pe.io.command.valid.toBoolean && pe.io.command.ready.toBoolean)
        if (commandFire) {
          issueCount += 1
          issueSlots += dut.io.activeRows.toBigInt.bitCount * cfg.macLanes
          packedColumns += dut.batchColumns.toLong
        }
        for (pe <- dut.pes if pe.io.response.valid.toBoolean &&
            pe.io.response.ready.toBoolean) {
          observedMacs += pe.io.response.payload.effectualMacs.toLong
        }
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt)) = dut.io.result.payload.value.toLong
        }
        dut.clockDomain.waitSampling()
        executionCycles += 1
      }
      assert(dut.io.done.toBoolean, s"${modeName(mode)} timed out")
      assert(!dut.io.unsupportedMode.toBoolean,
        s"${modeName(mode)} scheduler error for ${workload.category.name} " +
          s"sample=${workload.sample}, aOffset=${dut.io.activeAOffset.toInt}, " +
          s"group=${dut.io.activeAGroupSize.toInt}, bBase=${dut.io.activeBBase.toInt}, " +
          s"candidate=${dut.io.candidateColumnCount.toInt}, " +
          s"planned=${dut.io.plannedColumns.toInt}")
      val expected = expectedEntries(MatrixReference.multiply(workload.a, workload.b))
      assert(observed.toMap == expected,
        s"${modeName(mode)} ${workload.category.name} " +
          s"sample=${workload.sample}: a=${workload.a.values} b=${workload.b.values} " +
          s"observed=$observed expected=$expected")
      val expectedMacs = effectualMacs(workload)
      assert(observedMacs == expectedMacs,
        s"${modeName(mode)} reported $observedMacs MACs, expected $expectedMacs")
      Measurement(workload.category, workload.sample, modeName(mode),
        setupCycles, executionCycles, observedMacs, issueCount, issueSlots,
        packedColumns)
    }

    for (workload <- workloads;
         mode <- Vector(TrapezoidDataflow.DenseIp, TrapezoidDataflow.TrIp)) {
      measurements += run(workload, mode)
    }
  }

  simConfig.compile(new GustavsonRowScheduler(gustCfg,
    paperPe(kernelSources))).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.clear #= false
    dut.io.command.valid #= false
    dut.io.result.ready #= true

    def clearPayload(): Unit = {
      dut.io.command.payload.endOfTile #= false
      for (fiber <- 0 until cfg.maxFibers) {
        dut.io.command.payload.trip.a(fiber).valid #= false
        dut.io.command.payload.trip.a(fiber).tag #= 0
        dut.io.command.payload.trip.a(fiber).mask #= 0
        dut.io.command.payload.trip.b(fiber).valid #= false
        dut.io.command.payload.trip.b(fiber).tag #= 0
        dut.io.command.payload.trip.b(fiber).mask #= 0
        for (value <- 0 until cfg.fiberWidth) {
          dut.io.command.payload.trip.a(fiber).values(value) #= 0
          dut.io.command.payload.trip.b(fiber).values(value) #= 0
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

    def commandsForRow(workload: Workload, row: Int): Vector[(Int, Long, Int)] = {
      val commands = for {
        k <- 0 until dimension
        aValue = workload.a(row, k)
        if aValue != 0
        columnBase <- 0 until dimension by cfg.cacheLineWords
      } yield (k, aValue, columnBase)
      if (commands.nonEmpty) commands.toVector else Vector((0, 0L, 0))
    }

    def run(workload: Workload, mode: TrapezoidDataflow.E): Measurement = {
      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      clearPayload()

      val observed = mutable.Map.empty[(Int, Int), Long]
      var cycles = 0L
      var observedMacs = 0L
      var issueCount = 0L

      def sampleCycle(): Unit = {
        sleep(1)
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt)) = dut.io.result.payload.value.toLong
        }
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      for (row <- 0 until dimension) {
        val rowCommands = commandsForRow(workload, row)
        for (((k, aValue, columnBase), commandIndex) <- rowCommands.zipWithIndex) {
          clearPayload()
          dut.io.command.payload.mode #= mode
          dut.io.command.payload.endOfTile #=
            (commandIndex == rowCommands.size - 1)
          if (aValue != 0) {
            dut.io.command.payload.gustavson.a(0).valid #= true
            dut.io.command.payload.gustavson.a(0).row #= row
            dut.io.command.payload.gustavson.a(0).k #= k
            dut.io.command.payload.gustavson.a(0).value #= aValue
            for (lane <- 0 until cfg.cacheLineWords) {
              val column = columnBase + lane
              val bValue = workload.b(k, column)
              dut.io.command.payload.gustavson.b(lane).valid #= (bValue != 0)
              dut.io.command.payload.gustavson.b(lane).aIndex #= 0
              dut.io.command.payload.gustavson.b(lane).k #= k
              dut.io.command.payload.gustavson.b(lane).column #= column
              dut.io.command.payload.gustavson.b(lane).value #= bValue
            }
          }
          dut.io.command.valid #= true
          var accepted = false
          while (!accepted && cycles < 10000000) {
            sleep(1)
            accepted = dut.io.command.ready.toBoolean
            if (accepted) {
              issueCount += 1
              observedMacs += (0 until cfg.cacheLineWords).count(lane =>
                dut.io.command.payload.gustavson.b(lane).valid.toBoolean)
            }
            if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
              observed((dut.io.result.payload.row.toInt,
                dut.io.result.payload.column.toInt)) = dut.io.result.payload.value.toLong
            }
            dut.clockDomain.waitSampling()
            cycles += 1
          }
          dut.io.command.valid #= false
          assert(accepted, s"${modeName(mode)} command timed out")
        }
      }
      while (dut.io.completedTiles.toLong < dimension && cycles < 10000000) {
        sampleCycle()
      }
      assert(dut.io.completedTiles.toLong == dimension,
        s"${modeName(mode)} timed out after ${dut.io.completedTiles.toLong} rows")
      assert(dut.io.overflow.toBigInt == 0,
        s"${modeName(mode)} accumulator overflow for ${workload.category.name}")
      assert(dut.io.unsupportedMode.toBigInt == 0)
      val expected = expectedEntries(MatrixReference.multiply(workload.a, workload.b))
      assert(observed.toMap == expected,
        s"${modeName(mode)} ${workload.category.name} " +
          s"sample=${workload.sample}: observed=$observed expected=$expected")
      val expectedMacs = effectualMacs(workload)
      assert(observedMacs == expectedMacs,
        s"${modeName(mode)} reported $observedMacs MACs, expected $expectedMacs")
      Measurement(workload.category, workload.sample, modeName(mode),
        setupCycles = 0, executionCycles = cycles, effectualMacs = observedMacs,
        issueCount = issueCount, issueSlots = issueCount * cfg.cacheLineWords,
        packedColumns = 0)
    }

    for (workload <- workloads) {
      measurements += run(workload, TrapezoidDataflow.TrGt)
    }
  }

  simConfig.compile(new TrGsSpatialRowScheduler(gustCfg,
    paperPe(kernelSources), wideOutput = true)).doSim { dut =>
    dut.clockDomain.forkStimulus(10)
    dut.io.clear #= false
    dut.io.command.valid #= false
    dut.io.result.ready #= false
    dut.io.resultLine.ready #= true

    def clearPayload(): Unit = {
      dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
      dut.io.command.payload.endOfTile #= false
      dut.io.command.payload.a.valid #= false
      dut.io.command.payload.a.row #= 0
      dut.io.command.payload.a.k #= 0
      dut.io.command.payload.a.value #= 0
      for (lane <- 0 until cfg.cacheLineWords) {
        dut.io.command.payload.b(lane).valid #= false
        dut.io.command.payload.b(lane).k #= 0
        dut.io.command.payload.b(lane).column #= 0
        dut.io.command.payload.b(lane).value #= 0
      }
    }

    def commandsForRow(workload: Workload, row: Int): Vector[(Int, Long, Int)] = {
      val commands = for {
        k <- 0 until dimension
        aValue = workload.a(row, k)
        if aValue != 0
        columnBase <- 0 until dimension by cfg.cacheLineWords
      } yield (k, aValue, columnBase)
      if (commands.nonEmpty) commands.toVector else Vector((0, 0L, 0))
    }

    def run(workload: Workload): Measurement = {
      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      clearPayload()

      val observed = mutable.Map.empty[(Int, Int), Long]
      var cycles = 0L
      var observedMacs = 0L
      var issueCount = 0L

      def sampleCycle(): Unit = {
        sleep(1)
        if (dut.io.resultLine.valid.toBoolean && dut.io.resultLine.ready.toBoolean) {
          for (lane <- 0 until cfg.cacheLineWords
               if dut.io.resultLine.payload.results(lane).valid.toBoolean) {
            val result = dut.io.resultLine.payload.results(lane)
            observed((result.row.toInt, result.column.toInt)) = result.value.toLong
          }
        }
        dut.clockDomain.waitSampling()
        cycles += 1
      }

      for (row <- 0 until dimension) {
        val rowCommands = commandsForRow(workload, row)
        for (((k, aValue, columnBase), commandIndex) <- rowCommands.zipWithIndex) {
          clearPayload()
          dut.io.command.payload.endOfTile #=
            (commandIndex == rowCommands.size - 1)
          if (aValue != 0) {
            dut.io.command.payload.a.valid #= true
            dut.io.command.payload.a.row #= row
            dut.io.command.payload.a.k #= k
            dut.io.command.payload.a.value #= aValue
            for (lane <- 0 until cfg.cacheLineWords) {
              val column = columnBase + lane
              val bValue = workload.b(k, column)
              dut.io.command.payload.b(lane).valid #= (bValue != 0)
              dut.io.command.payload.b(lane).k #= k
              dut.io.command.payload.b(lane).column #= column
              dut.io.command.payload.b(lane).value #= bValue
            }
          }
          dut.io.command.valid #= true
          var accepted = false
          while (!accepted && cycles < 10000000) {
            sleep(1)
            accepted = dut.io.command.ready.toBoolean
            if (accepted) {
              issueCount += 1
              observedMacs += (0 until cfg.cacheLineWords).count(lane =>
                dut.io.command.payload.b(lane).valid.toBoolean)
            }
            if (dut.io.resultLine.valid.toBoolean &&
                dut.io.resultLine.ready.toBoolean) {
              for (lane <- 0 until cfg.cacheLineWords
                   if dut.io.resultLine.payload.results(lane).valid.toBoolean) {
                val result = dut.io.resultLine.payload.results(lane)
                observed((result.row.toInt, result.column.toInt)) = result.value.toLong
              }
            }
            dut.clockDomain.waitSampling()
            cycles += 1
          }
          dut.io.command.valid #= false
          assert(accepted, "TrGs command timed out")
        }
      }
      while (dut.io.completedTiles.toLong < dimension && cycles < 10000000) {
        sampleCycle()
      }
      assert(dut.io.completedTiles.toLong == dimension,
        s"TrGs timed out after ${dut.io.completedTiles.toLong} rows")
      assert(dut.io.overflow.toBigInt == 0,
        s"TrGs accumulator overflow for ${workload.category.name}")
      assert(dut.io.unsupportedMode.toBigInt == 0)
      val expected = expectedEntries(MatrixReference.multiply(workload.a, workload.b))
      assert(observed.toMap == expected,
        s"TrGs ${workload.category.name} sample=${workload.sample}: " +
          s"observed=$observed expected=$expected")
      val expectedMacs = effectualMacs(workload)
      assert(observedMacs == expectedMacs,
        s"TrGs reported $observedMacs MACs, expected $expectedMacs")
      Measurement(workload.category, workload.sample, "TrGs",
        setupCycles = 0, executionCycles = cycles, effectualMacs = observedMacs,
        issueCount = issueCount, issueSlots = issueCount * cfg.cacheLineWords,
        packedColumns = 0)
    }

    for (workload <- workloads) {
      measurements += run(workload)
    }
  }

  private val modeOrder = Vector("DenseIp", "TrIp", "TrGt", "TrGs")
  private def densityText(nonZeros: Int): String =
    f"${100.0 * nonZeros / (dimension * dimension)}%.2f%%"

  println("\nTrapezoid paper-configuration RTL execution latency (cycles)")
  println(s"Matrix: ${dimension}x${dimension} * ${dimension}x${dimension}; " +
    s"array: ${cfg.peRows}x${cfg.macLanes} MACs; " +
    s"samples/category: $samplesPerCategory; result.ready: always true")
  println(s"HS=${densityText(highlySparseNonZeros)}, " +
    s"MS=${densityText(mildlySparseNonZeros)}, D=${densityText(denseNonZeros)}")
  println("| Category | A density | B density | Paper mode | DenseIp | TrIp | TrGt | TrGs |")
  println("| :--- | ---: | ---: | :--- | ---: | ---: | ---: | ---: |")
  for (category <- categories) {
    val values = modeOrder.map { mode =>
      val cycles = measurements.filter(m =>
        m.category == category && m.mode == mode).map(_.executionCycles)
      assert(cycles.size == samplesPerCategory)
      f"${cycles.sum.toDouble / cycles.size}%.1f"
    }
    println(s"| ${category.name} | ${densityText(category.aNonZeros)} | " +
      s"${densityText(category.bNonZeros)} | ${category.paperMode} | " +
      s"${values.mkString(" | ")} |")
  }
  println("\nDetailed RTL measurements")
  println("| Category | Mode | Setup cycles | Execute cycles | Boundary cycles | " +
    "Issues | Avg B cols | Useful MAC/cycle | Issue-slot util. |")
  println("| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
  for (category <- categories; mode <- modeOrder) {
    val samples = measurements.filter(m =>
      m.category == category && m.mode == mode)
    assert(samples.size == samplesPerCategory)
    def average(select: Measurement => Long): Double =
      samples.map(select).sum.toDouble / samples.size
    val totalMacs = samples.map(_.effectualMacs).sum
    val totalExecutionCycles = samples.map(_.executionCycles).sum
    val totalIssues = samples.map(_.issueCount).sum
    val totalSlots = samples.map(_.issueSlots).sum
    val averageColumns = if (mode == "DenseIp" || mode == "TrIp") {
      f"${samples.map(_.packedColumns).sum.toDouble / totalIssues}%.2f"
    } else {
      "-"
    }
    val macsPerCycle = totalMacs.toDouble / totalExecutionCycles
    val issueUtilization = if (totalSlots == 0) 0.0
      else 100.0 * totalMacs / totalSlots
    println(f"| ${category.name}%s | $mode%s | ${average(_.setupCycles)}%.1f | " +
      f"${average(_.executionCycles)}%.1f | ${average(_.boundaryCycles)}%.1f | " +
      f"${average(_.issueCount)}%.1f | $averageColumns%s | $macsPerCycle%.3f | " +
      f"$issueUtilization%.1f%% |")
  }
  println("\nExecution cycles preserve the previous start-to-done boundary. Setup cycles " +
    "count local IP fiber transfers; Gustavson commands remain part of execution.")
  println("Issue-slot utilization is useful MACs divided by active IP PE-row MAC slots, " +
    "or by Gustavson cache-line lanes, at accepted issues.")
  println("Each value is derived from deterministic cycle-accurate RTL samples; cache/HBM " +
    "latency remains outside this benchmark.")
}
