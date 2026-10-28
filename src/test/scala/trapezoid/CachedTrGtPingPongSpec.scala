package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

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
    requestIdWidth = 3)
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
      assert(requestIds == Seq(0, 2),
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
      assert(dut.io.aLoadCompleteState.toBigInt == 3)
    }
  }
}
