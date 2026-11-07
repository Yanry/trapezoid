package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable

class SharedTrGtAdapterHarness(systemCfg: EndToEndSystemConfig) extends Component {
  private val cfg = systemCfg.core
  require(cfg.peRows == 1)
  val io = new Bundle {
    val clear = in Bool()
    val rowCommand = slave(Stream(CachedGustavsonBRowCommand(systemCfg.gustavson)))
    val cacheRequest = master(Stream(CacheReadRequest(systemCfg.cache)))
    val cacheResponse = slave(Stream(CacheReadResponse(systemCfg.cache)))
    val result = master(Stream(MatrixResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val occupancy = out UInt(16 bits)
    val phase = out UInt(4 bits)
    val groupReady = out Bits(2 bits)
    val aLoadComplete = out Bits(2 bits)
    val prefetchActive = out Bits(2 bits)
  }
  val adapter = new CachedTrGtSubrowAdapter(systemCfg,
    externalScheduler = true)
  val tile = new ModeSharedTileController(
    systemCfg.matrix.tile, systemCfg.gustavson.tile)
  adapter.io.clear := io.clear
  tile.io.clear := io.clear
  tile.io.mrnProgram(0).valid := False
  tile.io.mrnProgram(0).payload := SpatialMrnControlWrite(cfg).getZero
  adapter.io.rowCommand <> io.rowCommand
  adapter.io.cacheRequest <> io.cacheRequest
  adapter.io.cacheResponse <> io.cacheResponse
  io.result <> adapter.io.result
  io.busy := adapter.io.busy
  io.done := adapter.io.done
  io.phase := adapter.io.phase
  io.groupReady := adapter.io.groupReadyState
  io.aLoadComplete := adapter.io.aLoadCompleteState
  io.prefetchActive := adapter.io.prefetchActiveState

  tile.io.trgtALoad(0) <> adapter.io.schedulerALoad
  tile.io.trgtStart(0) <> adapter.io.schedulerStart
  for (subrow <- 0 until cfg.localBanks) {
    tile.io.trgtBPush(0)(subrow) <> adapter.io.schedulerBPush(subrow)
    adapter.io.schedulerFifoOccupancyIn(subrow) :=
      tile.io.trgtFifoOccupancy(0)(subrow)
  }
  tile.io.trgtSourceFinish(0) := adapter.io.schedulerSourceFinish
  tile.io.trgtFinish(0) := adapter.io.schedulerFinish
  adapter.io.schedulerResult <> tile.io.trgtResult(0)
  adapter.io.schedulerBusyIn := tile.io.trgtBusy(0)
  adapter.io.schedulerDoneIn := tile.io.trgtDone(0)
  adapter.io.schedulerOverflowIn := tile.io.trgtOverflow(0)
  adapter.io.schedulerUnsupportedModeIn := tile.io.trgtUnsupportedMode(0)
  io.occupancy := tile.io.trgtFifoOccupancy(0)
    .map(_.resize(16)).reduce(_ + _).resize(16)

  tile.io.tripALoad.valid := False
  tile.io.tripALoad.payload := IndexedFiber(cfg,
    systemCfg.matrix.tile.rowIndexWidth).getZero
  tile.io.tripBLoad.valid := False
  tile.io.tripBLoad.payload := IndexedFiber(cfg,
    systemCfg.matrix.tile.columnIndexWidth).getZero
  tile.io.tripStart.valid := False
  tile.io.tripStart.payload := TrIpTileStart(systemCfg.matrix.tile).getZero
  tile.io.tripResult.ready := True
  tile.io.trgsCommand(0).valid := False
  tile.io.trgsCommand(0).payload := TrGsCommand(cfg).getZero
  tile.io.trgsResult(0).ready := True
  tile.io.trgsResultLine(0).ready := True
}

class CachedTrGtPingPongSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 32,
    coordinateWidth = 8,
    fiberWidth = 4,
    maxFibers = 2,
    macLanes = 4,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 1,
    localDepthPerBank = 8)
  private val tileCfg = TrIpTileConfig(cfg, maxColumns = 2,
    maxRowsOverride = 2)
  private val matrixCfg = TrIpMatrixConfig(tileCfg,
    maxMatrixRows = 2, maxMatrixColumns = 2, maxKTiles = 1)
  private val cacheCfg = CacheConfig(
    wordWidth = 8,
    wordsPerLine = 8,
    addressWidth = 8,
    bankCount = 4,
    setsPerBank = 8,
    clientCount = 4,
    requestIdWidth = 4)
  private val gustCfg = GustavsonCacheConfig(
    GustavsonTileConfig(cfg, accumulatorEntries = 4,
      fifoDepthPerSubrow = 2, bPrefetchDepth = 2),
    cacheCfg,
    maxLinesPerRow = 2)
  private val systemCfg = EndToEndSystemConfig(
    matrixCfg, gustCfg, CacheClusterConfig(cacheCfg, clusterCount = 2),
    maxCResults = 4)

  private def simConfig = SimConfig.withVerilator
    .disableCache
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("TrGT collects the next group while the current group executes") {
    simConfig.compile(new CachedTrGtSubrowAdapter(systemCfg,
      externalScheduler = true)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.rowCommand.valid #= false
      dut.io.cacheRequest.ready #= false
      dut.io.cacheResponse.valid #= false
      dut.io.result.ready #= true
      dut.io.schedulerALoad.ready #= true
      dut.io.schedulerStart.ready #= true
      for (subrow <- 0 until cfg.localBanks) {
        dut.io.schedulerBPush(subrow).ready #= true
        dut.io.schedulerFifoOccupancyIn(subrow) #= 0
      }
      dut.io.schedulerResult.valid #= false
      dut.io.schedulerResult.payload.valid #= false
      dut.io.schedulerResult.payload.row #= 0
      dut.io.schedulerResult.payload.column #= 0
      dut.io.schedulerResult.payload.value #= 0
      dut.io.schedulerResult.payload.endOfTile #= false
      dut.io.schedulerBusyIn #= false
      dut.io.schedulerDoneIn #= false
      dut.io.schedulerOverflowIn #= false
      dut.io.schedulerUnsupportedModeIn #= false

      def acceptRequest(): Int = {
        while (!dut.io.cacheRequest.valid.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        val requestId = dut.io.cacheRequest.payload.requestId.toInt
        dut.io.cacheRequest.ready #= true
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.cacheRequest.ready #= false
        requestId
      }

      def respond(requestId: Int): Unit = {
        dut.io.cacheResponse.payload.requestId #= requestId
        dut.io.cacheResponse.payload.data #= 0
        dut.io.cacheResponse.payload.wasHit #= false
        dut.io.cacheResponse.payload.error #= false
        dut.io.cacheResponse.valid #= true
        sleep(1)
        while (!dut.io.cacheResponse.ready.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.cacheResponse.valid #= false
      }

      def issue(row: Int, value: Int, base: Int): Unit = {
        dut.io.rowCommand.payload.mode #= TrapezoidDataflow.TrGt
        dut.io.rowCommand.payload.endOfTile #= true
        dut.io.rowCommand.payload.aRowLength #= 1
        dut.io.rowCommand.payload.aIndex #= 0
        dut.io.rowCommand.payload.a.valid #= true
        dut.io.rowCommand.payload.a.row #= row
        dut.io.rowCommand.payload.a.k #= 0
        dut.io.rowCommand.payload.a.value #= value
        dut.io.rowCommand.payload.bBaseAddress #= base
        dut.io.rowCommand.payload.lineCount #= 1
        dut.io.rowCommand.valid #= true
        while (!dut.io.rowCommand.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.rowCommand.valid #= false
      }

      issue(row = 1, value = 3, base = 16)
      issue(row = 2, value = 5, base = 32)
      val requestIds = Seq(acceptRequest(), acceptRequest())
      respond(requestIds.head)
      respond(requestIds.last)

      var timeout = 0
      while ((dut.io.aLoadCompleteState.toBigInt != 3 ||
          dut.io.phase.toInt != CachedTrGtState.WaitDone.position) &&
          timeout < 500) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(timeout < 500,
        s"timed out: aLoadComplete=${dut.io.aLoadCompleteState.toBigInt} " +
          s"requestIds=$requestIds")
      assert(dut.io.aLoadCompleteState.toBigInt == 3,
        "the next group's A context must load while the first group executes")
      assert(requestIds == Seq(0, 8),
        "the next group's B window must refill while the first group executes")

      dut.io.schedulerDoneIn #= true
      timeout = 0
      while (dut.io.executeGroupState.toBoolean == false && timeout < 500) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(timeout < 500,
        s"second group did not become executable: " +
          s"phase=${dut.io.phase.toInt} busy=${dut.io.busy.toBoolean} " +
          s"done=${dut.io.done.toBoolean} memoryError=${dut.io.memoryError.toBoolean} " +
          s"ready=${dut.io.groupReadyState.toBigInt} " +
          s"aLoadComplete=${dut.io.aLoadCompleteState.toBigInt} " +
          s"prefetch=${dut.io.prefetchActiveState.toBigInt} " +
          s"execute=${dut.io.executeGroupState.toBoolean} " +
          s"fill=${dut.io.fillGroupState.toBoolean}")
      assert(dut.io.aLoadCompleteState.toBigInt == 2,
        "the retired context is cleared while the next context stays loaded")
    }
  }

  test("TrGT keeps independent cursors for sixteen dense B streams") {
    val denseCfg = TrapezoidConfig(
      dataWidth = 8, accumulatorWidth = 32, coordinateWidth = 8,
      fiberWidth = 8, maxFibers = 2, macLanes = 16,
      cacheLineWords = 4, outputLanes = 4, peRows = 1,
      localDepthPerBank = 32)
    val denseTileCfg = TrIpTileConfig(denseCfg, maxColumns = 16,
      maxRowsOverride = 2)
    val denseMatrixCfg = TrIpMatrixConfig(denseTileCfg,
      maxMatrixRows = 16, maxMatrixColumns = 16, maxKTiles = 2)
    val denseCacheCfg = CacheConfig(
      wordWidth = 8, wordsPerLine = 16, addressWidth = 12,
      bankCount = 8, setsPerBank = 64, clientCount = 4,
      requestIdWidth = 8)
    val denseGustCfg = GustavsonCacheConfig(
      GustavsonTileConfig(denseCfg, accumulatorEntries = 64,
        fifoDepthPerSubrow = 8, bPrefetchDepth = 2),
      denseCacheCfg, maxLinesPerRow = 8)
    val denseSystemCfg = EndToEndSystemConfig(
      denseMatrixCfg, denseGustCfg,
      CacheClusterConfig(denseCacheCfg, clusterCount = 4),
      maxCResults = 256)
    val linesPerSource = 4
    val baseAddress = 256
    val lineImage = (for {
      source <- 0 until denseCfg.macLanes
      line <- 0 until linesPerSource
    } yield {
      val data = (0 until denseGustCfg.elementsPerLine).foldLeft(BigInt(0)) {
        case (bits, element) =>
          val column = line * denseGustCfg.elementsPerLine + element
          val offset = element * denseGustCfg.elementWidth
          bits | (BigInt(1) << offset) | (BigInt(1) << (offset + 1)) |
            (BigInt(column) << (offset + 1 + denseCfg.dataWidth))
      }
      (baseAddress + source * linesPerSource + line) -> data
    }).toMap

    simConfig.compile {
      val dut = new SharedTrGtAdapterHarness(denseSystemCfg)
      dut.adapter.io.schedulerALoad.valid.simPublic()
      dut.adapter.io.schedulerALoad.ready.simPublic()
      dut.adapter.io.schedulerALoad.payload.index.simPublic()
      dut.adapter.io.schedulerALoad.payload.element.row.simPublic()
      dut.adapter.io.schedulerStart.valid.simPublic()
      dut.adapter.io.schedulerStart.ready.simPublic()
      dut.adapter.aValid(0).foreach(_.simPublic())
      dut.adapter.aValid(1).foreach(_.simPublic())
      for (subrow <- 0 until denseCfg.localBanks) {
        dut.adapter.io.schedulerBPush(subrow).valid.simPublic()
        dut.adapter.io.schedulerBPush(subrow).ready.simPublic()
        dut.adapter.io.schedulerBPush(subrow).payload.aIndex.simPublic()
      }
      dut
    }.doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.rowCommand.valid #= false
      dut.io.cacheRequest.ready #= true
      dut.io.cacheResponse.valid #= false
      dut.io.result.ready #= true

      val pending = mutable.Queue.empty[(Int, Int)]
      fork {
        while (true) {
          dut.io.cacheResponse.valid #= false
          sleep(1)
          if (dut.io.cacheRequest.valid.toBoolean &&
              dut.io.cacheRequest.ready.toBoolean) {
            pending.enqueue((dut.io.cacheRequest.payload.requestId.toInt,
              dut.io.cacheRequest.payload.address.toInt))
          }
          if (pending.nonEmpty) {
            val (requestId, address) = pending.front
            dut.io.cacheResponse.payload.requestId #= requestId
            dut.io.cacheResponse.payload.data #= lineImage(address)
            dut.io.cacheResponse.payload.wasHit #= false
            dut.io.cacheResponse.payload.error #= false
            dut.io.cacheResponse.valid #= true
          }
          dut.clockDomain.waitSampling()
          if (pending.nonEmpty && dut.io.cacheResponse.ready.toBoolean) {
            pending.dequeue()
          }
        }
      }

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      val aLoads = mutable.Map.empty[Int, mutable.Set[Int]]
      val bPushes = mutable.Map.empty[Int, Array[Int]]
      var executingRow = -1
      var previousReady = BigInt(0)
      val readyMasks = mutable.ArrayBuffer.empty[(Int, BigInt, BigInt)]
      def validMask(group: Int): BigInt =
        (0 until denseCfg.macLanes).foldLeft(BigInt(0)) { (mask, source) =>
          if (dut.adapter.aValid(group)(source).toBoolean)
            mask.setBit(source) else mask
        }
      var producerDone = false
      var submitted = 0
      fork {
        for (group <- 0 until 16; source <- 0 until denseCfg.macLanes) {
            dut.io.rowCommand.payload.mode #= TrapezoidDataflow.TrGt
            dut.io.rowCommand.payload.endOfTile #=
              source == denseCfg.macLanes - 1
            dut.io.rowCommand.payload.aRowLength #= denseCfg.macLanes
            dut.io.rowCommand.payload.aIndex #= source
            dut.io.rowCommand.payload.a.valid #= true
            dut.io.rowCommand.payload.a.row #= group
            dut.io.rowCommand.payload.a.k #= source
            dut.io.rowCommand.payload.a.value #= source + 1
            dut.io.rowCommand.payload.bBaseAddress #=
              baseAddress + source * linesPerSource
            dut.io.rowCommand.payload.lineCount #= linesPerSource
            dut.io.rowCommand.valid #= true
            sleep(1)
            while (!dut.io.rowCommand.ready.toBoolean) {
              dut.clockDomain.waitSampling()
              sleep(1)
            }
            dut.clockDomain.waitSampling()
            dut.io.rowCommand.valid #= false
            submitted += 1
        }
        producerDone = true
      }
      var timeout = 0
      while ((!producerDone || observed.size < 16 * 16 ||
          !dut.io.done.toBoolean || dut.io.busy.toBoolean) &&
          timeout < 100000) {
        val currentReady = dut.io.groupReady.toBigInt
        if (currentReady != previousReady) {
          readyMasks += ((submitted,
            validMask(0), validMask(1)))
          previousReady = currentReady
        }
        if (dut.adapter.io.schedulerALoad.valid.toBoolean &&
            dut.adapter.io.schedulerALoad.ready.toBoolean) {
          val row = dut.adapter.io.schedulerALoad.payload.element.row.toInt
          aLoads.getOrElseUpdate(row, mutable.Set.empty) +=
            dut.adapter.io.schedulerALoad.payload.index.toInt
        }
        if (dut.adapter.io.schedulerStart.valid.toBoolean &&
            dut.adapter.io.schedulerStart.ready.toBoolean) {
          executingRow += 1
        }
        for (subrow <- 0 until denseCfg.localBanks) {
          val stream = dut.adapter.io.schedulerBPush(subrow)
          if (stream.valid.toBoolean && stream.ready.toBoolean) {
            val counts = bPushes.getOrElseUpdate(executingRow,
              Array.fill(denseCfg.macLanes)(0))
            counts(stream.payload.aIndex.toInt) += 1
          }
        }
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          observed += ((dut.io.result.payload.row.toInt,
            dut.io.result.payload.column.toInt,
            dut.io.result.payload.value.toLong))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(timeout < 100000,
        s"submitted=$submitted observed=${observed.size} " +
          s"phase=${dut.io.phase.toInt} ready=0x${dut.io.groupReady.toBigInt.toString(16)} " +
          s"aLoad=0x${dut.io.aLoadComplete.toBigInt.toString(16)} " +
          s"prefetch=0x${dut.io.prefetchActive.toBigInt.toString(16)} " +
          s"busy=${dut.io.busy.toBoolean} done=${dut.io.done.toBoolean} " +
          s"occupancy=${dut.io.occupancy.toInt}")
      assert((0 until 16).forall(row =>
        aLoads.getOrElse(row, mutable.Set.empty).size == denseCfg.macLanes),
        s"aLoads=${aLoads.toSeq.sortBy(_._1).mkString(",")} " +
          s"readyMasks=${readyMasks.mkString(";")}")
      assert((0 until 16).forall(row =>
        bPushes.get(row).exists(_.forall(_ == 16))),
        s"bPushes=${bPushes.toSeq.sortBy(_._1).map { case (row, counts) =>
          s"$row:${counts.mkString(",")}" }.mkString(";")}")
      assert(observed == (for {
        group <- 0 until 16
        column <- 0 until 16
      } yield (group, column, 136L)))
    }
  }
}
