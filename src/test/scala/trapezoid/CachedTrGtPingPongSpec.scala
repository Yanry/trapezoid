package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable

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
      dut.io.cacheRequest.ready #= true
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
      dut.io.schedulerDoneIn #= true
      dut.io.schedulerOverflowIn #= false
      dut.io.schedulerUnsupportedModeIn #= false

      val requestIds = mutable.ArrayBuffer.empty[Int]
      fork {
        while (requestIds.length < 2) {
          if (dut.io.cacheRequest.valid.toBoolean &&
              dut.io.cacheRequest.ready.toBoolean) {
            val requestId = dut.io.cacheRequest.payload.requestId.toInt
            requestIds += requestId
            dut.clockDomain.waitSampling()
            dut.io.cacheResponse.payload.requestId #= requestId
            dut.io.cacheResponse.payload.data #= 0
            dut.io.cacheResponse.payload.wasHit #= false
            dut.io.cacheResponse.payload.error #= false
            dut.io.cacheResponse.valid #= true
            while (!dut.io.cacheResponse.ready.toBoolean) {
              dut.clockDomain.waitSampling()
            }
            dut.clockDomain.waitSampling()
            dut.io.cacheResponse.valid #= false
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      val loadedValues = mutable.ArrayBuffer.empty[Int]
      fork {
        while (loadedValues.length < 2) {
          if (dut.io.schedulerALoad.valid.toBoolean &&
              dut.io.schedulerALoad.ready.toBoolean) {
            val value = dut.io.schedulerALoad.payload.element.value.toInt
            if (loadedValues.lastOption.forall(_ != value)) {
              loadedValues += value
            }
          }
          dut.clockDomain.waitSampling()
        }
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

      var timeout = 0
      while ((loadedValues.length < 2 || requestIds.length < 2) && timeout < 200) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(timeout < 200,
        s"timed out: loadedValues=$loadedValues requestIds=$requestIds")
      assert(loadedValues == Seq(3, 5))
      assert(requestIds == Seq(0, 2),
        "the response reorder tag must encode groupId above lineId")
    }
  }
}
