package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable

class ResultWritebackDmaSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 24,
    coordinateWidth = 8,
    fiberWidth = 4,
    maxFibers = 2,
    macLanes = 4,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 1,
    localDepthPerBank = 8
  )
  private val tileCfg = TrIpTileConfig(cfg, maxColumns = 4)
  private val matrixCfg = TrIpMatrixConfig(
    tileCfg, maxMatrixRows = 2, maxMatrixColumns = 4, maxKTiles = 1)
  private val cacheCfg = CacheConfig(
    wordWidth = 8,
    wordsPerLine = 8,
    addressWidth = 8,
    bankCount = 2,
    setsPerBank = 2,
    clientCount = 2
  )
  private val systemCfg = EndToEndSystemConfig(
    matrixCfg,
    GustavsonCacheConfig(
      GustavsonTileConfig(cfg, accumulatorEntries = 4), cacheCfg),
    CacheClusterConfig(cacheCfg, clusterCount = 1),
    maxCResults = 8,
    writeOutstandingDepth = 8
  )

  private val simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("writeback pipelines requests and waits for every ordered response") {
    simConfig.compile(new ResultWritebackDma(systemCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.start.valid #= false
      dut.io.input.valid #= false
      dut.io.close #= false
      dut.io.writeRequest.ready #= true
      dut.io.writeResponse.valid #= false
      dut.io.writeResponse.payload.address #= 0
      dut.io.writeResponse.payload.error #= false

      dut.io.start.payload #= 64
      dut.io.start.valid #= true
      while (!dut.io.start.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.start.valid #= false

      val resultCount = 6
      val responseLatency = 8L
      val pendingResponses = mutable.Queue.empty[(Int, Long)]
      val requestAddresses = mutable.ArrayBuffer.empty[Int]
      val requestEdges = mutable.ArrayBuffer.empty[Long]
      val responseEdges = mutable.ArrayBuffer.empty[Long]
      var activeResponse: Option[Int] = None
      var nextInput = 0
      var edge = 0L

      while (!dut.io.done.toBoolean && edge < 200) {
        dut.io.input.valid #= (nextInput < resultCount)
        dut.io.input.payload.valid #= true
        dut.io.input.payload.row #= nextInput / 3
        dut.io.input.payload.column #= nextInput % 3
        dut.io.input.payload.value #= nextInput + 1
        dut.io.input.payload.endOfTile #= (nextInput == resultCount - 1)
        dut.io.close #= (nextInput == resultCount)

        if (activeResponse.isEmpty &&
            pendingResponses.headOption.exists(_._2 <= edge + 1)) {
          activeResponse = Some(pendingResponses.dequeue()._1)
        }
        dut.io.writeResponse.valid #= activeResponse.nonEmpty
        activeResponse.foreach(dut.io.writeResponse.payload.address #= _)

        sleep(1)
        val inputFire = dut.io.input.valid.toBoolean && dut.io.input.ready.toBoolean
        val requestFire = dut.io.writeRequest.valid.toBoolean &&
          dut.io.writeRequest.ready.toBoolean
        val responseFire = dut.io.writeResponse.valid.toBoolean &&
          dut.io.writeResponse.ready.toBoolean
        val requestAddress = if (requestFire)
          dut.io.writeRequest.payload.address.toInt else 0

        dut.clockDomain.waitSampling()
        edge += 1

        if (inputFire) nextInput += 1
        if (requestFire) {
          requestAddresses += requestAddress
          requestEdges += edge
          pendingResponses.enqueue(requestAddress -> (edge + responseLatency))
        }
        if (responseFire) {
          responseEdges += edge
          activeResponse = None
        }
      }

      assert(dut.io.done.toBoolean, "writeback timed out")
      assert(!dut.io.error.toBoolean)
      assert(dut.io.writtenResults.toLong == resultCount)
      assert(requestAddresses == (64 until 64 + resultCount))
      assert(requestEdges.sliding(2).forall {
        case Seq(previous, current) => current == previous + 1
        case _ => true
      }, "the DMA request stream must sustain one result per cycle")
      assert(requestEdges.last < responseEdges.head,
        "all requests should be in flight before the first delayed response")
      assert(responseEdges.size == resultCount)
      assert(edge >= responseEdges.last,
        "completion must not precede the final write response")
    }
  }
}
