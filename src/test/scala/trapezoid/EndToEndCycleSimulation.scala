package trapezoid

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import trapezoid.model.{BitmaskFiber, DenseMatrix, MatrixReference}

import scala.collection.mutable

/** Cycle-accurate RTL benchmark across the current cache-to-result boundaries.
  *
  * DenseIp and TrIp start at the cached matrix descriptor. TrGt and TrGs start
  * at the first cached B-row command because a Gustavson matrix-descriptor
  * frontend is not implemented yet. All modes include cold-cache lookup,
  * deterministic refill latency, PE execution, accumulation, and result drain.
  */
object EndToEndCycleSimulation extends App {
  private val dimension = 16
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
  require(dimension % gustCfg.elementsPerLine == 0)

  private val a = DenseMatrix(Vector.tabulate(dimension, dimension) {
    (row, k) => 1L + ((row + 2 * k) % 3)
  })
  private val b = DenseMatrix(Vector.tabulate(dimension, dimension) {
    (k, column) => 1L + ((2 * k + column) % 3)
  })
  private val expected = MatrixReference.multiply(a, b)
  private val expectedEntries = (for {
    row <- 0 until dimension
    column <- 0 until dimension
    value = expected(row, column)
    if value != 0
  } yield (row, column) -> value).toMap

  private val simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private case class PendingRefill(bank: Int, address: Int, data: BigInt,
                                   readyEdge: Long)

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

  private case class Measurement(
      mode: String,
      totalCycles: Long,
      firstResultCycles: Long,
      inputAcceptedCycles: Long,
      hbmActiveCycles: Long,
      refillRequests: Long,
      cacheHits: Long,
      cacheMisses: Long,
      resultCount: Int,
      checksum: Long)

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
  private val ipMemory: Map[Int, BigInt] = {
    val kTiles = dimension / cfg.fiberWidth
    val aLines = for {
      row <- 0 until dimension
      tile <- 0 until kTiles
      fiber = MatrixReference.encodeFiber(row,
        a.values(row).slice(tile * cfg.fiberWidth, (tile + 1) * cfg.fiberWidth))
      (data, line) <- recordLines(encodeFiber(fiber)).zipWithIndex
      ordinal = row * matrixCfg.maxKTiles + tile
    } yield ipABase + ordinal * fiberCacheCfg.linesPerFiber + line -> data
    val bLines = for {
      tile <- 0 until kTiles
      column <- 0 until dimension
      dense = Vector.tabulate(cfg.fiberWidth) { offset =>
        b(tile * cfg.fiberWidth + offset, column)
      }
      fiber = MatrixReference.encodeFiber(column, dense)
      (data, line) <- recordLines(encodeFiber(fiber)).zipWithIndex
      ordinal = tile * matrixCfg.maxMatrixColumns + column
    } yield ipBBase + ordinal * fiberCacheCfg.linesPerFiber + line -> data
    (aLines ++ bLines).toMap
  }

  private def checksum(values: collection.Map[(Int, Int), Long]): Long =
    values.toSeq.sortBy(_._1).foldLeft(0L) {
      case (sum, ((row, column), value)) =>
        sum + value * (row * dimension + column + 1L)
    }

  private def runIp(modeName: String): Measurement = {
    var measured: Measurement = null
    simConfig.compile(new TrIpCachedMatrixSystem(matrixCfg, cacheCfg)).doSim { dut =>
      val mode = if (modeName == "DenseIp") TrapezoidDataflow.DenseIp
        else TrapezoidDataflow.TrIp
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= true
      dut.io.descriptor.valid #= false
      dut.io.result.ready #= true
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.refillResponse.payload.bank #= 0
      dut.io.refillResponse.payload.address #= 0
      dut.io.refillResponse.payload.data #= 0
      dut.io.refillResponse.payload.error #= false
      dut.clockDomain.waitSampling(2)
      dut.io.clear #= false

      dut.io.descriptor.payload.matrix.rowCount #= dimension
      dut.io.descriptor.payload.matrix.columnCount #= dimension
      dut.io.descriptor.payload.matrix.kTileCount #= dimension / cfg.fiberWidth
      for (tile <- 0 until matrixCfg.maxKTiles) {
        dut.io.descriptor.payload.matrix.modes(tile) #= mode
      }
      dut.io.descriptor.payload.aBaseAddress #= ipABase
      dut.io.descriptor.payload.bBaseAddress #= ipBBase
      dut.io.descriptor.valid #= true

      val memory = new FixedLatencyMemory(ipMemory, hbmLatencyCycles)
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
        val resultValid = resultFire && dut.io.result.payload.element.valid.toBoolean
        val resultRow = if (resultValid) dut.io.result.payload.element.row.toInt else 0
        val resultColumn = if (resultValid) dut.io.result.payload.element.column.toInt else 0
        val resultValue = if (resultValid) dut.io.result.payload.element.value.toLong else 0L
        val endOfMatrix = resultFire && dut.io.result.payload.endOfMatrix.toBoolean
        val requestBank = if (requestFire) dut.io.refillRequest.payload.bank.toInt else 0
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
        if (endOfMatrix) finalResultEdge = elapsedEdges
      }

      assert(descriptorAccepted, s"$modeName descriptor was not accepted")
      assert(finalResultEdge >= 0, s"$modeName timed out")
      assert(observed.toMap == expectedEntries,
        s"$modeName result mismatch: observed=$observed expected=$expectedEntries")
      assert(!dut.io.descriptorError.toBoolean)
      assert(!dut.io.addressError.toBoolean)
      assert(!dut.io.innerError.toBoolean)
      assert(!dut.io.memoryError.toBoolean)
      measured = Measurement(
        modeName,
        finalResultEdge - startEdge + 1,
        firstResultEdge - startEdge + 1,
        inputAcceptedCycles = 1,
        memory.activeCycles,
        memory.requestCount,
        dut.io.cacheHitCount.toLong,
        dut.io.cacheMissCount.toLong,
        observed.size,
        checksum(observed))
    }
    measured
  }

  private val gustLinesPerRow = dimension / gustCfg.elementsPerLine
  private val gustBBase = 0x600
  private val gustMemory: Map[Int, BigInt] = (for {
    k <- 0 until dimension
    line <- 0 until gustLinesPerRow
    firstColumn = line * gustCfg.elementsPerLine
    valueMask = (BigInt(1) << cfg.dataWidth) - 1
    data = (0 until gustCfg.elementsPerLine).foldLeft(BigInt(0)) {
      (packed, lane) =>
        val column = firstColumn + lane
        val value = b(k, column)
        val offset = lane * gustCfg.elementWidth
        packed | (BigInt(1) << offset) |
          ((BigInt(value) & valueMask) << (offset + 1)) |
          (BigInt(column) << (offset + 1 + cfg.dataWidth))
    }
  } yield gustBBase + k * gustLinesPerRow + line -> data).toMap

  private case class GustavsonCommand(row: Int, k: Int, value: Long,
                                      endOfTile: Boolean)

  private val gustavsonCommands = (for {
    row <- 0 until dimension
    k <- 0 until dimension
  } yield GustavsonCommand(row, k, a(row, k), k == dimension - 1)).toVector

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
      val cacheRefillCount = out UInt(32 bits)
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
      io.cacheRefillCount := system.io.cacheRefillCount
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
      io.cacheRefillCount := system.io.cacheRefillCount
      io.error := system.io.memoryError || system.io.addressError ||
        system.io.adapterUnsupportedMode || system.io.schedulerOverflow.orR ||
        system.io.schedulerUnsupportedMode.orR
    }
  }

  private def runGustavson(modeName: String,
                           spatial: Boolean): Measurement = {
    var measured: Measurement = null
    simConfig.compile(new CachedGustavsonBenchmarkHarness(spatial)).doSim { dut =>
      val mode = if (modeName == "TrGt") TrapezoidDataflow.TrGt
        else TrapezoidDataflow.TrGs
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= true
      dut.io.command.valid #= false
      dut.io.result.ready #= true
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.refillResponse.payload.bank #= 0
      dut.io.refillResponse.payload.address #= 0
      dut.io.refillResponse.payload.data #= 0
      dut.io.refillResponse.payload.error #= false
      dut.clockDomain.waitSampling(2)
      dut.io.clear #= false

      def driveCommand(index: Int): Unit = {
        if (index < gustavsonCommands.size) {
          val command = gustavsonCommands(index)
          dut.io.command.valid #= true
          dut.io.command.payload.mode #= mode
          dut.io.command.payload.endOfTile #= command.endOfTile
          dut.io.command.payload.aIndex #= command.k
          dut.io.command.payload.a.valid #= true
          dut.io.command.payload.a.row #= command.row
          dut.io.command.payload.a.k #= command.k
          dut.io.command.payload.a.value #= command.value
          dut.io.command.payload.bBaseAddress #=
            gustBBase + command.k * gustLinesPerRow
          dut.io.command.payload.lineCount #= gustLinesPerRow
        } else {
          dut.io.command.valid #= false
        }
      }

      val memory = new FixedLatencyMemory(gustMemory, hbmLatencyCycles)
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
        val requestBank = if (requestFire) dut.io.refillRequest.payload.bank.toInt else 0
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
        if (commandIndex == gustavsonCommands.size &&
            observed.size == expectedEntries.size &&
            dut.io.completedTiles.toLong == dimension) {
          finalEdge = elapsedEdges
        }
      }

      assert(startEdge >= 0, s"$modeName accepted no commands")
      assert(finalEdge >= 0, s"$modeName timed out")
      assert(observed.toMap == expectedEntries,
        s"$modeName result mismatch: observed=$observed expected=$expectedEntries")
      assert(!dut.io.error.toBoolean, s"$modeName reported a hardware error")
      measured = Measurement(
        modeName,
        finalEdge - startEdge + 1,
        firstResultEdge - startEdge + 1,
        lastInputEdge - startEdge + 1,
        memory.activeCycles,
        memory.requestCount,
        dut.io.cacheHitCount.toLong,
        dut.io.cacheMissCount.toLong,
        observed.size,
        checksum(observed))
    }
    measured
  }

  private val measurements = Vector(
    runIp("DenseIp"),
    runIp("TrIp"),
    runGustavson("TrGt", spatial = false),
    runGustavson("TrGs", spatial = true)
  )

  require(measurements.map(_.checksum).distinct.size == 1,
    "all dataflows must produce the same result checksum")

  println("\nTrapezoid cache-to-result end-to-end RTL cycles")
  println(s"Matrix: ${dimension}x${dimension} * ${dimension}x${dimension}; " +
    s"array: ${cfg.peRows} PE rows x ${cfg.macLanes} MACs; " +
    s"cold cache; HBM refill latency: $hbmLatencyCycles cycles")
  println("Boundary: accepted matrix descriptor (IP) or first B-row command " +
    "(Gustavson) through final result and tile completion.")
  println("| Mode | Total cycles | First result | Inputs accepted | HBM active | " +
    "Refills | Hits | Misses | Results | Checksum |")
  println("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
  measurements.foreach { result =>
    println(s"| ${result.mode} | ${result.totalCycles} | " +
      s"${result.firstResultCycles} | ${result.inputAcceptedCycles} | " +
      s"${result.hbmActiveCycles} | ${result.refillRequests} | " +
      s"${result.cacheHits} | ${result.cacheMisses} | ${result.resultCount} | " +
      s"${result.checksum} |")
  }
  println("Gustavson A/CSR descriptor fetch and final C memory writes remain outside " +
    "the current RTL boundary.")
}
