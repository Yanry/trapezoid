package trapezoid

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import trapezoid.model.{BitmaskFiber, DenseMatrix, MatrixReference}

import scala.collection.mutable
import scala.util.Random

/** Cycle-accurate RTL benchmark for every dataflow and sparsity category.
  *
  * DenseIp and TrIp start at an accepted cached-matrix descriptor. TrGt and
  * TrGs start at the first cached B-row command because the Gustavson CSR
  * descriptor frontend is not implemented yet. All modes include cold-cache
  * lookup, deterministic HBM refill latency, RTL execution, accumulation, and
  * result drain. Final C memory writes remain outside the current RTL boundary.
  */
object CacheToResultCycleSimulation extends App {
  private val dimension = 16
  private val samplesPerCategory = 3
  private val hbmLatencyCycles = 12

  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 32,
    coordinateWidth = 8,
    fiberWidth = 8,
    maxFibers = 2,
    macLanes = 16,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 4,
    localBanks = 4,
    localWordsPerBank = 4,
    localDepthPerBank = 32,
    responseFifoDepth = 2
  )
  private val tileCfg = TrIpTileConfig(cfg, maxColumns = dimension,
    maxRowsOverride = 8)
  private val matrixCfg = TrIpMatrixConfig(tileCfg,
    maxMatrixRows = dimension, maxMatrixColumns = dimension, maxKTiles = 2)
  private val cacheCfg = CacheConfig(
    wordWidth = 8,
    wordsPerLine = 16,
    addressWidth = 12,
    bankCount = 8,
    setsPerBank = 64,
    clientCount = 2,
    requestIdWidth = 8
  )
  private val gustTileCfg = GustavsonTileConfig(cfg,
    accumulatorEntries = dimension, fifoDepthPerSubrow = 8)
  private val gustCfg = GustavsonCacheConfig(gustTileCfg, cacheCfg,
    maxLinesPerRow = 8)
  private val fiberCacheCfg = FiberCacheConfig(cfg, cacheCfg)

  require(dimension % cfg.fiberWidth == 0)

  private val highlySparseBasisPoints = 50
  private val mildlySparseBasisPoints = 2000
  private val denseBasisPoints = 10000

  private case class Category(name: String, aBasisPoints: Int,
                              bBasisPoints: Int, paperMode: String)
  private case class Workload(category: Category, sample: Int,
                              a: DenseMatrix, b: DenseMatrix)
  private case class PendingRefill(bank: Int, address: Int, data: BigInt,
                                   readyEdge: Long)
  private case class Measurement(
      category: Category,
      sample: Int,
      mode: String,
      totalCycles: Long,
      firstResultCycles: Long,
      inputAcceptedCycles: Long,
      hbmActiveCycles: Long,
      refillRequests: Long,
      cacheHits: Long,
      cacheMisses: Long,
      usefulMacs: Long,
      resultCount: Int,
      checksum: Long)
  private case class BRowRegion(baseAddress: Int, lineCount: Int)
  private case class GustavsonMemory(lines: Map[Int, BigInt],
                                     rows: Vector[BRowRegion])
  private case class GustavsonCommand(row: Int, k: Int, value: Long,
                                      valid: Boolean, endOfTile: Boolean,
                                      region: BRowRegion)

  private val categories = Vector(
    Category("HS x HS", highlySparseBasisPoints, highlySparseBasisPoints, "TrGt"),
    Category("HS x MS", highlySparseBasisPoints, mildlySparseBasisPoints, "TrGs"),
    Category("HS x D", highlySparseBasisPoints, denseBasisPoints, "TrGs"),
    Category("MS x MS", mildlySparseBasisPoints, mildlySparseBasisPoints, "TrIp"),
    Category("MS x D", mildlySparseBasisPoints, denseBasisPoints, "TrIp"),
    Category("D x D", denseBasisPoints, denseBasisPoints, "DenseIp")
  )
  private val modeOrder = Vector("DenseIp", "TrIp", "TrGt", "TrGs")

  private def nonZerosFor(basisPoints: Int): Int =
    ((dimension.toLong * dimension * basisPoints + 5000) / 10000).toInt

  private def exactDensityMatrix(basisPoints: Int, seed: Int): DenseMatrix = {
    val cells = dimension * dimension
    val selected = (0 +: new Random(seed).shuffle((1 until cells).toVector))
      .take(nonZerosFor(basisPoints)).toSet
    DenseMatrix(Vector.tabulate(dimension, dimension) { (row, column) =>
      val index = row * dimension + column
      if (selected(index)) 1L + ((index + seed) & 3) else 0L
    })
  }

  private val workloads = categories.zipWithIndex.flatMap {
    case (category, categoryIndex) =>
      Vector.tabulate(samplesPerCategory) { sample =>
        Workload(category, sample,
          exactDensityMatrix(category.aBasisPoints,
            0x5100 + categoryIndex * 97 + sample),
          exactDensityMatrix(category.bBasisPoints,
            0x7900 + categoryIndex * 101 + sample))
      }
  }

  private val simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private final class FixedLatencyMemory(
      image: Map[Int, BigInt],
      latencyCycles: Int
  ) {
    private val pending = mutable.Queue.empty[PendingRefill]
    private var active: Option[PendingRefill] = None
    var requestCount = 0L
    var activeCycles = 0L

    def accept(bank: Int, address: Int, edge: Long): Unit = {
      require(image.contains(address), s"refill requested unmapped line $address")
      pending.enqueue(PendingRefill(bank, address, image(address),
        edge + latencyCycles))
      requestCount += 1
    }

    def responseFor(edge: Long): Option[PendingRefill] = {
      if (active.isEmpty && pending.headOption.exists(_.readyEdge <= edge)) {
        active = Some(pending.dequeue())
      }
      if (active.nonEmpty || pending.nonEmpty) activeCycles += 1
      active
    }

    def completeResponse(): Unit = active = None
  }

  private def expectedEntries(workload: Workload): Map[(Int, Int), Long] = {
    val expected = MatrixReference.multiply(workload.a, workload.b)
    (for {
      row <- 0 until dimension
      column <- 0 until dimension
      value = expected(row, column)
      if value != 0
    } yield (row, column) -> value).toMap
  }

  private def usefulMacs(workload: Workload): Long = (for {
    row <- 0 until dimension
    k <- 0 until dimension
    if workload.a(row, k) != 0
    column <- 0 until dimension
    if workload.b(k, column) != 0
  } yield 1L).sum

  private def checksum(values: collection.Map[(Int, Int), Long]): Long =
    values.toSeq.sortBy(_._1).foldLeft(0L) {
      case (sum, ((row, column), value)) =>
        sum + value * (row * dimension + column + 1L)
    }

  private def encodeFiber(fiber: BitmaskFiber): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    val valuesOffset = 1 + cfg.fiberWidth
    val tagOffset = valuesOffset + cfg.fiberWidth * cfg.dataWidth
    var record = BigInt(1) | (fiber.mask << 1) |
      (BigInt(fiber.tag) << tagOffset)
    for (entry <- 0 until cfg.fiberWidth) {
      val value = fiber.values.lift(entry).getOrElse(0L)
      record |= (BigInt(value) & valueMask) <<
        (valuesOffset + entry * cfg.dataWidth)
    }
    record
  }

  private def recordLines(record: BigInt): Vector[BigInt] = {
    val lineMask = (BigInt(1) << cacheCfg.lineWidth) - 1
    Vector.tabulate(fiberCacheCfg.linesPerFiber) { line =>
      (record >> (line * cacheCfg.lineWidth)) & lineMask
    }
  }

  private val ipABase = 0x100
  private val ipBBase = 0x340

  private def ipMemory(workload: Workload): Map[Int, BigInt] = {
    val kTiles = dimension / cfg.fiberWidth
    val aLines = for {
      row <- 0 until dimension
      tile <- 0 until kTiles
      fiber = MatrixReference.encodeFiber(row,
        workload.a.values(row).slice(tile * cfg.fiberWidth,
          (tile + 1) * cfg.fiberWidth))
      (data, line) <- recordLines(encodeFiber(fiber)).zipWithIndex
      ordinal = row * matrixCfg.maxKTiles + tile
    } yield ipABase + ordinal * fiberCacheCfg.linesPerFiber + line -> data
    val bLines = for {
      tile <- 0 until kTiles
      column <- 0 until dimension
      dense = Vector.tabulate(cfg.fiberWidth) { offset =>
        workload.b(tile * cfg.fiberWidth + offset, column)
      }
      fiber = MatrixReference.encodeFiber(column, dense)
      (data, line) <- recordLines(encodeFiber(fiber)).zipWithIndex
      ordinal = tile * matrixCfg.maxMatrixColumns + column
    } yield ipBBase + ordinal * fiberCacheCfg.linesPerFiber + line -> data
    (aLines ++ bLines).toMap
  }

  private def runIpModes(): Vector[Measurement] = {
    val measurements = mutable.ArrayBuffer.empty[Measurement]
    simConfig.compile(new TrIpCachedMatrixSystem(matrixCfg, cacheCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.descriptor.valid #= false
      dut.io.result.ready #= true
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.refillResponse.payload.bank #= 0
      dut.io.refillResponse.payload.address #= 0
      dut.io.refillResponse.payload.data #= 0
      dut.io.refillResponse.payload.error #= false

      def run(workload: Workload, modeName: String): Measurement = {
        dut.io.clear #= true
        dut.io.descriptor.valid #= false
        dut.io.refillResponse.valid #= false
        dut.clockDomain.waitSampling(2)
        dut.io.clear #= false

        val mode = if (modeName == "DenseIp") TrapezoidDataflow.DenseIp
          else TrapezoidDataflow.TrIp
        dut.io.descriptor.payload.matrix.rowCount #= dimension
        dut.io.descriptor.payload.matrix.columnCount #= dimension
        dut.io.descriptor.payload.matrix.kTileCount #= dimension / cfg.fiberWidth
        for (tile <- 0 until matrixCfg.maxKTiles) {
          dut.io.descriptor.payload.matrix.modes(tile) #= mode
        }
        dut.io.descriptor.payload.aBaseAddress #= ipABase
        dut.io.descriptor.payload.bBaseAddress #= ipBBase
        dut.io.descriptor.valid #= true

        val memory = new FixedLatencyMemory(ipMemory(workload), hbmLatencyCycles)
        val expected = expectedEntries(workload)
        val observed = mutable.Map.empty[(Int, Int), Long]
        var elapsedEdges = 0L
        var startEdge = -1L
        var firstResultEdge = -1L
        var finalResultEdge = -1L
        var descriptorAccepted = false
        val timeoutEdges = 200000L

        while (finalResultEdge < 0 && elapsedEdges < timeoutEdges) {
          val nextEdge = elapsedEdges + 1
          val refill = memory.responseFor(nextEdge)
          dut.io.refillResponse.valid #= refill.nonEmpty
          refill.foreach { response =>
            dut.io.refillResponse.payload.bank #= response.bank
            dut.io.refillResponse.payload.address #= response.address
            dut.io.refillResponse.payload.data #= response.data
            dut.io.refillResponse.payload.error #= false
          }

          sleep(1)
          val descriptorFire = dut.io.descriptor.valid.toBoolean &&
            dut.io.descriptor.ready.toBoolean
          val requestFire = dut.io.refillRequest.valid.toBoolean &&
            dut.io.refillRequest.ready.toBoolean
          val responseFire = refill.nonEmpty && dut.io.refillResponse.ready.toBoolean
          val resultFire = dut.io.result.valid.toBoolean &&
            dut.io.result.ready.toBoolean
          val resultValid = resultFire &&
            dut.io.result.payload.element.valid.toBoolean
          val resultRow = if (resultValid)
            dut.io.result.payload.element.row.toInt else 0
          val resultColumn = if (resultValid)
            dut.io.result.payload.element.column.toInt else 0
          val resultValue = if (resultValid)
            dut.io.result.payload.element.value.toLong else 0L
          val endOfMatrix = resultFire &&
            dut.io.result.payload.endOfMatrix.toBoolean
          val matrixDone = dut.io.done.toBoolean
          val requestBank = if (requestFire)
            dut.io.refillRequest.payload.bank.toInt else 0
          val requestAddress = if (requestFire)
            dut.io.refillRequest.payload.address.toInt else 0

          dut.clockDomain.waitSampling()
          elapsedEdges = nextEdge

          if (descriptorFire) {
            startEdge = elapsedEdges
            descriptorAccepted = true
            dut.io.descriptor.valid #= false
          }
          if (requestFire) memory.accept(requestBank, requestAddress, elapsedEdges)
          if (responseFire) memory.completeResponse()
          if (resultValid) {
            if (firstResultEdge < 0) firstResultEdge = elapsedEdges
            observed((resultRow, resultColumn)) = resultValue
          }
          if (endOfMatrix || matrixDone) finalResultEdge = elapsedEdges
        }

        assert(descriptorAccepted, s"$modeName descriptor was not accepted")
        assert(finalResultEdge >= 0,
          s"$modeName ${workload.category.name} timed out")
        assert(observed.toMap == expected,
          s"$modeName ${workload.category.name} result mismatch: " +
            s"observed=$observed expected=$expected")
        assert(!dut.io.descriptorError.toBoolean)
        assert(!dut.io.addressError.toBoolean)
        assert(!dut.io.innerError.toBoolean)
        assert(!dut.io.memoryError.toBoolean)
        Measurement(workload.category, workload.sample, modeName,
          finalResultEdge - startEdge + 1,
          if (firstResultEdge < 0) 0 else firstResultEdge - startEdge + 1,
          inputAcceptedCycles = 1,
          memory.activeCycles,
          memory.requestCount,
          dut.io.cacheHitCount.toLong,
          dut.io.cacheMissCount.toLong,
          usefulMacs(workload),
          observed.size,
          checksum(observed))
      }

      for (workload <- workloads;
           mode <- Vector("DenseIp", "TrIp")) {
        measurements += run(workload, mode)
      }
    }
    measurements.toVector
  }

  private val gustBBase = 0x600

  private def encodeGustavsonLine(entries: Seq[(Int, Long)]): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    entries.zipWithIndex.foldLeft(BigInt(0)) {
      case (packed, ((column, value), lane)) =>
        val offset = lane * gustCfg.elementWidth
        packed | (BigInt(1) << offset) |
          ((BigInt(value) & valueMask) << (offset + 1)) |
          (BigInt(column) << (offset + 1 + cfg.dataWidth))
    }
  }

  private def gustavsonMemory(b: DenseMatrix): GustavsonMemory = {
    val lines = mutable.Map.empty[Int, BigInt]
    val rows = Vector.newBuilder[BRowRegion]
    var nextAddress = gustBBase
    for (k <- 0 until dimension) {
      val entries = (0 until dimension).collect {
        case column if b(k, column) != 0 => column -> b(k, column)
      }
      val groups = if (entries.isEmpty) Vector(Vector.empty[(Int, Long)])
        else entries.grouped(gustCfg.elementsPerLine).map(_.toVector).toVector
      rows += BRowRegion(nextAddress, groups.size)
      groups.foreach { group =>
        lines(nextAddress) = encodeGustavsonLine(group)
        nextAddress += 1
      }
    }
    GustavsonMemory(lines.toMap, rows.result())
  }

  private def gustavsonCommands(workload: Workload,
                                 memory: GustavsonMemory): Vector[GustavsonCommand] = {
    (0 until dimension).flatMap { row =>
      val active = (0 until dimension).collect {
        case k if workload.a(row, k) != 0 &&
            workload.b.values(k).exists(_ != 0) =>
          (k, workload.a(row, k))
      }.toVector
      val rowWork = if (active.nonEmpty) active else Vector(0 -> 0L)
      rowWork.zipWithIndex.map { case ((k, value), index) =>
        GustavsonCommand(row, k, value, active.nonEmpty,
          index == rowWork.size - 1, memory.rows(k))
      }
    }.toVector
  }

  private class CachedGustavsonBenchmarkHarness(spatial: Boolean) extends Component {
    val io = new Bundle {
      val clear = in Bool()
      val command = slave(Stream(CachedGustavsonBRowCommand(gustCfg)))
      val result = master(Stream(MatrixResult(cfg)))
      val refillRequest = master(Stream(CacheRefillRequest(cacheCfg)))
      val refillResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
      val completedTiles = out UInt(32 bits)
      val cacheHitCount = out UInt(32 bits)
      val cacheMissCount = out UInt(32 bits)
      val error = out Bool()
    }

    if (spatial) {
      val system = new CachedTrGsSpatialSystem(gustCfg)
      system.io.clear := io.clear
      system.io.command.valid := io.command.valid
      system.io.command.payload := io.command.payload
      io.command.ready := system.io.command.ready
      io.result.valid := system.io.result.valid
      io.result.payload := system.io.result.payload
      system.io.result.ready := io.result.ready
      io.refillRequest.valid := system.io.refillRequest.valid
      io.refillRequest.payload := system.io.refillRequest.payload
      system.io.refillRequest.ready := io.refillRequest.ready
      system.io.refillResponse.valid := io.refillResponse.valid
      system.io.refillResponse.payload := io.refillResponse.payload
      io.refillResponse.ready := system.io.refillResponse.ready
      io.completedTiles := system.io.completedTiles
      io.cacheHitCount := system.io.cacheHitCount
      io.cacheMissCount := system.io.cacheMissCount
      io.error := system.io.memoryError || system.io.addressError ||
        system.io.adapterUnsupportedMode || system.io.schedulerOverflow.orR ||
        system.io.schedulerUnsupportedMode.orR
    } else {
      val system = new CachedGustavsonSystem(gustCfg)
      system.io.clear := io.clear
      system.io.command.valid := io.command.valid
      system.io.command.payload := io.command.payload
      io.command.ready := system.io.command.ready
      io.result.valid := system.io.result.valid
      io.result.payload := system.io.result.payload
      system.io.result.ready := io.result.ready
      io.refillRequest.valid := system.io.refillRequest.valid
      io.refillRequest.payload := system.io.refillRequest.payload
      system.io.refillRequest.ready := io.refillRequest.ready
      system.io.refillResponse.valid := io.refillResponse.valid
      system.io.refillResponse.payload := io.refillResponse.payload
      io.refillResponse.ready := system.io.refillResponse.ready
      io.completedTiles := system.io.completedTiles
      io.cacheHitCount := system.io.cacheHitCount
      io.cacheMissCount := system.io.cacheMissCount
      io.error := system.io.memoryError || system.io.addressError ||
        system.io.adapterUnsupportedMode || system.io.schedulerOverflow.orR ||
        system.io.schedulerUnsupportedMode.orR
    }
  }

  private def runGustavsonMode(modeName: String,
                               spatial: Boolean): Vector[Measurement] = {
    val measurements = mutable.ArrayBuffer.empty[Measurement]
    simConfig.compile(new CachedGustavsonBenchmarkHarness(spatial)).doSim { dut =>
      val mode = if (modeName == "TrGt") TrapezoidDataflow.TrGt
        else TrapezoidDataflow.TrGs
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.command.valid #= false
      dut.io.result.ready #= true
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.refillResponse.payload.bank #= 0
      dut.io.refillResponse.payload.address #= 0
      dut.io.refillResponse.payload.data #= 0
      dut.io.refillResponse.payload.error #= false

      def run(workload: Workload): Measurement = {
        dut.io.clear #= true
        dut.io.command.valid #= false
        dut.io.refillResponse.valid #= false
        dut.clockDomain.waitSampling(2)
        dut.io.clear #= false

        val image = gustavsonMemory(workload.b)
        val commands = gustavsonCommands(workload, image)
        val expected = expectedEntries(workload)

        def driveCommand(index: Int): Unit = {
          if (index < commands.size) {
            val command = commands(index)
            dut.io.command.valid #= true
            dut.io.command.payload.mode #= mode
            dut.io.command.payload.endOfTile #= command.endOfTile
            dut.io.command.payload.aIndex #= command.k
            dut.io.command.payload.a.valid #= command.valid
            dut.io.command.payload.a.row #= command.row
            dut.io.command.payload.a.k #= command.k
            dut.io.command.payload.a.value #= command.value
            dut.io.command.payload.bBaseAddress #= command.region.baseAddress
            dut.io.command.payload.lineCount #= command.region.lineCount
          } else {
            dut.io.command.valid #= false
          }
        }

        val memory = new FixedLatencyMemory(image.lines, hbmLatencyCycles)
        val observed = mutable.Map.empty[(Int, Int), Long]
        var commandIndex = 0
        var elapsedEdges = 0L
        var startEdge = -1L
        var lastInputEdge = -1L
        var firstResultEdge = -1L
        var finalEdge = -1L
        val timeoutEdges = 1000000L
        driveCommand(commandIndex)

        while (finalEdge < 0 && elapsedEdges < timeoutEdges) {
          val nextEdge = elapsedEdges + 1
          val refill = memory.responseFor(nextEdge)
          dut.io.refillResponse.valid #= refill.nonEmpty
          refill.foreach { response =>
            dut.io.refillResponse.payload.bank #= response.bank
            dut.io.refillResponse.payload.address #= response.address
            dut.io.refillResponse.payload.data #= response.data
            dut.io.refillResponse.payload.error #= false
          }

          sleep(1)
          val commandFire = dut.io.command.valid.toBoolean &&
            dut.io.command.ready.toBoolean
          val requestFire = dut.io.refillRequest.valid.toBoolean &&
            dut.io.refillRequest.ready.toBoolean
          val responseFire = refill.nonEmpty && dut.io.refillResponse.ready.toBoolean
          val resultFire = dut.io.result.valid.toBoolean &&
            dut.io.result.ready.toBoolean && dut.io.result.payload.valid.toBoolean
          val resultRow = if (resultFire) dut.io.result.payload.row.toInt else 0
          val resultColumn = if (resultFire) dut.io.result.payload.column.toInt else 0
          val resultValue = if (resultFire) dut.io.result.payload.value.toLong else 0L
          val requestBank = if (requestFire)
            dut.io.refillRequest.payload.bank.toInt else 0
          val requestAddress = if (requestFire)
            dut.io.refillRequest.payload.address.toInt else 0

          dut.clockDomain.waitSampling()
          elapsedEdges = nextEdge

          if (commandFire) {
            if (startEdge < 0) startEdge = elapsedEdges
            commandIndex += 1
            lastInputEdge = elapsedEdges
            driveCommand(commandIndex)
          }
          if (requestFire) memory.accept(requestBank, requestAddress, elapsedEdges)
          if (responseFire) memory.completeResponse()
          if (resultFire) {
            if (firstResultEdge < 0) firstResultEdge = elapsedEdges
            observed((resultRow, resultColumn)) = resultValue
          }
          if (commandIndex == commands.size &&
              observed.size == expected.size &&
              dut.io.completedTiles.toLong == dimension) {
            finalEdge = elapsedEdges
          }
        }

        assert(startEdge >= 0, s"$modeName accepted no commands")
        assert(finalEdge >= 0,
          s"$modeName ${workload.category.name} timed out")
        assert(observed.toMap == expected,
          s"$modeName ${workload.category.name} result mismatch: " +
            s"observed=$observed expected=$expected")
        assert(!dut.io.error.toBoolean,
          s"$modeName ${workload.category.name} reported a hardware error")
        Measurement(workload.category, workload.sample, modeName,
          finalEdge - startEdge + 1,
          if (firstResultEdge < 0) 0 else firstResultEdge - startEdge + 1,
          lastInputEdge - startEdge + 1,
          memory.activeCycles,
          memory.requestCount,
          dut.io.cacheHitCount.toLong,
          dut.io.cacheMissCount.toLong,
          usefulMacs(workload),
          observed.size,
          checksum(observed))
      }

      workloads.foreach(workload => measurements += run(workload))
    }
    measurements.toVector
  }

  private val measurements =
    runIpModes() ++ runGustavsonMode("TrGt", spatial = false) ++
      runGustavsonMode("TrGs", spatial = true)

  for (workload <- workloads) {
    val selected = measurements.filter(measurement =>
      measurement.category == workload.category &&
        measurement.sample == workload.sample)
    require(selected.size == modeOrder.size)
    require(selected.map(_.checksum).distinct.size == 1,
      s"all modes must agree for ${workload.category.name} sample ${workload.sample}")
  }

  private def densityText(basisPoints: Int): String =
    f"${100.0 * nonZerosFor(basisPoints) / (dimension * dimension)}%.2f%%"

  private def average(category: Category, mode: String)
                     (select: Measurement => Long): Double = {
    val selected = measurements.filter(measurement =>
      measurement.category == category && measurement.mode == mode)
    require(selected.size == samplesPerCategory)
    selected.map(select).sum.toDouble / selected.size
  }

  println("\nTrapezoid actual RTL cache-to-result end-to-end cycles")
  println(s"Matrix: ${dimension}x${dimension} * ${dimension}x${dimension}; " +
    s"RTL array: ${cfg.peRows} PE rows x ${cfg.macLanes} MACs; " +
    s"samples/category: $samplesPerCategory")
  println(s"Cold cache per run; fixed HBM refill latency: $hbmLatencyCycles cycles; " +
    "result.ready: always true")
  println("Boundary: accepted descriptor (IP) or first B-row command (Gustavson) " +
    "through final RTL result/tile completion.")
  println("| Category | A density | B density | Paper mode | DenseIp | TrIp | TrGt | TrGs |")
  println("| :--- | ---: | ---: | :--- | ---: | ---: | ---: | ---: | ---: |")
  categories.foreach { category =>
    val cycles = modeOrder.map(mode => f"${average(category, mode)(_.totalCycles)}%.1f")
    println(s"| ${category.name} | ${densityText(category.aBasisPoints)} | " +
      s"${densityText(category.bBasisPoints)} | ${category.paperMode} | " +
      s"${cycles.mkString(" | ")} |")
  }

  println("\nDetailed RTL measurements")
  println("| Category | Mode | Total | First result | Inputs accepted | HBM active | " +
    "Refills | Hits | Misses | Useful MACs | Results |")
  println("| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
  for (category <- categories; mode <- modeOrder) {
    println(f"| ${category.name}%s | $mode%s | " +
      f"${average(category, mode)(_.totalCycles)}%.1f | " +
      f"${average(category, mode)(_.firstResultCycles)}%.1f | " +
      f"${average(category, mode)(_.inputAcceptedCycles)}%.1f | " +
      f"${average(category, mode)(_.hbmActiveCycles)}%.1f | " +
      f"${average(category, mode)(_.refillRequests)}%.1f | " +
      f"${average(category, mode)(_.cacheHits)}%.1f | " +
      f"${average(category, mode)(_.cacheMisses)}%.1f | " +
      f"${average(category, mode)(_.usefulMacs)}%.1f | " +
      f"${average(category, mode)(_.resultCount)}%.1f |")
  }
  println("\nEvery table cycle is counted from Verilator RTL clock edges; no analytic " +
    "compute-cycle formula is used.")
  println("Gustavson A/CSR descriptor fetch and final C memory writes remain outside " +
    "the current RTL boundary.")
}
