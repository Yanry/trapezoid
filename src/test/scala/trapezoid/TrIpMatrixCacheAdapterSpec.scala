package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import spinal.lib.Stream
import trapezoid.model.{BitmaskFiber, MatrixReference}

import scala.collection.mutable
import scala.util.Random

class TrIpMatrixCacheAdapterSpec extends AnyFunSuite {
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
  private val cacheCfg = CacheConfig(
    wordWidth = 8,
    wordsPerLine = 4,
    addressWidth = 8,
    bankCount = 2,
    setsPerBank = 2,
    clientCount = 2,
    requestIdWidth = 2
  )
  private val fiberCacheCfg = FiberCacheConfig(cfg, cacheCfg)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private def encodeRecord(fiber: BitmaskFiber): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    val valuesOffset = 1 + cfg.fiberWidth
    val tagOffset = valuesOffset + cfg.fiberWidth * cfg.dataWidth
    var record = BigInt(1) | (fiber.mask << 1) | (BigInt(fiber.tag) << tagOffset)
    for (entry <- 0 until cfg.fiberWidth) {
      val value = fiber.values.lift(entry).getOrElse(0L)
      record |= (BigInt(value) & valueMask) << (valuesOffset + entry * cfg.dataWidth)
    }
    record
  }

  private def recordLines(record: BigInt): Vector[BigInt] = {
    val lineMask = (BigInt(1) << cacheCfg.lineWidth) - 1
    Vector.tabulate(fiberCacheCfg.linesPerFiber) { line =>
      (record >> (line * cacheCfg.lineWidth)) & lineMask
    }
  }

  private def serviceCache(
      dut: TrIpCachedMatrixController,
      request: Stream[CacheReadRequest],
      response: Stream[CacheReadResponse],
      memory: Map[Int, BigInt],
      observed: mutable.ArrayBuffer[(Int, Int)]
  ): Unit = fork {
    request.ready #= true
    response.valid #= false
    response.payload.requestId #= 0
    response.payload.data #= 0
    response.payload.wasHit #= true
    response.payload.error #= false
    while (true) {
      sleep(1)
      if (request.valid.toBoolean && request.ready.toBoolean) {
        val address = request.payload.address.toInt
        val requestId = request.payload.requestId.toInt
        observed += ((address, requestId))
        dut.clockDomain.waitSampling()
        request.ready #= false
        response.payload.requestId #= requestId
        response.payload.data #= memory(address)
        response.payload.wasHit #= true
        response.payload.error #= false
        response.valid #= true
        var accepted = false
        while (!accepted) {
          sleep(1)
          accepted = response.ready.toBoolean
          dut.clockDomain.waitSampling()
        }
        sleep(1)
        response.valid #= false
        request.ready #= true
      } else {
        dut.clockDomain.waitSampling()
      }
    }
  }

  test("fiber reader reassembles multiple lines and propagates cache errors") {
    simConfig.compile(new FiberCacheReader(fiberCacheCfg, indexWidth = 3)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.command.valid #= false
      dut.io.cacheRequest.ready #= true
      dut.io.cacheResponse.valid #= false
      dut.io.cacheResponse.payload.requestId #= 0
      dut.io.cacheResponse.payload.data #= 0
      dut.io.cacheResponse.payload.wasHit #= false
      dut.io.cacheResponse.payload.error #= false
      dut.io.result.ready #= false

      val fiber = MatrixReference.encodeFiber(7, Vector(-2L, 0L, 5L, -1L))
      val lines = recordLines(encodeRecord(fiber))

      dut.io.command.payload.address #= 10
      dut.io.command.payload.index #= 5
      dut.io.command.valid #= true
      sleep(1)
      assert(dut.io.command.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.command.valid #= false

      for (line <- lines.indices) {
        var timeout = 0
        while (!dut.io.cacheRequest.valid.toBoolean && timeout < 50) {
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        assert(dut.io.cacheRequest.payload.address.toInt == 10 + line)
        assert(dut.io.cacheRequest.payload.requestId.toInt == line)
        dut.clockDomain.waitSampling()
        dut.io.cacheResponse.payload.requestId #= line
        dut.io.cacheResponse.payload.data #= lines(line)
        dut.io.cacheResponse.payload.wasHit #= (line == 0)
        dut.io.cacheResponse.payload.error #= false
        dut.io.cacheResponse.valid #= true
        sleep(1)
        assert(dut.io.cacheResponse.ready.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.cacheResponse.valid #= false
      }

      var timeout = 0
      while (!dut.io.result.valid.toBoolean && timeout < 50) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.result.payload.index.toInt == 5)
      assert(dut.io.result.payload.fiber.valid.toBoolean)
      assert(dut.io.result.payload.fiber.tag.toInt == 7)
      assert(dut.io.result.payload.fiber.mask.toBigInt == fiber.mask)
      val expectedValues = fiber.values.padTo(cfg.fiberWidth, 0L)
      for (entry <- 0 until cfg.fiberWidth) {
        assert(dut.io.result.payload.fiber.values(entry).toLong == expectedValues(entry))
      }
      val heldData = dut.io.result.payload.fiber.values(0).toLong
      dut.clockDomain.waitSampling(3)
      assert(dut.io.result.valid.toBoolean)
      assert(dut.io.result.payload.fiber.values(0).toLong == heldData)
      dut.io.result.ready #= true
      dut.clockDomain.waitSampling()
      dut.io.result.ready #= false

      dut.io.command.payload.address #= 20
      dut.io.command.payload.index #= 2
      dut.io.command.valid #= true
      sleep(1)
      assert(dut.io.command.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.command.valid #= false
      for (line <- lines.indices) {
        while (!dut.io.cacheRequest.valid.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.cacheResponse.payload.requestId #= line
        dut.io.cacheResponse.payload.data #= lines(line)
        dut.io.cacheResponse.payload.error #= (line == lines.size - 1)
        dut.io.cacheResponse.valid #= true
        sleep(1)
        assert(dut.io.cacheResponse.ready.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.cacheResponse.valid #= false
      }
      while (!dut.io.result.valid.toBoolean) dut.clockDomain.waitSampling()
      assert(dut.io.result.payload.error.toBoolean)
      assert(!dut.io.result.payload.fiber.valid.toBoolean)
    }
  }

  test("cached matrix controller derives fiber addresses and computes a sparse DenseIp tile") {
    val tileCfg = TrIpTileConfig(cfg, maxColumns = 2)
    val matrixCfg = TrIpMatrixConfig(tileCfg,
      maxMatrixRows = 2, maxMatrixColumns = 2, maxKTiles = 1)
    simConfig.compile(new TrIpCachedMatrixController(matrixCfg, cacheCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.descriptor.valid #= false
      dut.io.result.ready #= false

      val aBase = 4
      val bBase = 32
      val aFibers = Vector(
        MatrixReference.encodeFiber(0, Vector(1L, 2L, 0L, 0L)),
        MatrixReference.encodeFiber(1, Vector(0L, 3L, 0L, 0L)))
      val bFibers = Vector(
        MatrixReference.encodeFiber(0, Vector(4L, 5L, 0L, 0L)),
        MatrixReference.encodeFiber(1, Vector(6L, 7L, 0L, 0L)))

      def memory(base: Int, fibers: Vector[BitmaskFiber]): Map[Int, BigInt] =
        fibers.zipWithIndex.flatMap { case (fiber, index) =>
          recordLines(encodeRecord(fiber)).zipWithIndex.map { case (line, offset) =>
            base + index * fiberCacheCfg.linesPerFiber + offset -> line
          }
        }.toMap

      val aRequests = mutable.ArrayBuffer.empty[(Int, Int)]
      val bRequests = mutable.ArrayBuffer.empty[(Int, Int)]
      serviceCache(dut, dut.io.aCacheRequest, dut.io.aCacheResponse,
        memory(aBase, aFibers), aRequests)
      serviceCache(dut, dut.io.bCacheRequest, dut.io.bCacheResponse,
        memory(bBase, bFibers), bRequests)

      dut.io.descriptor.payload.matrix.rowCount #= 2
      dut.io.descriptor.payload.matrix.columnCount #= 2
      dut.io.descriptor.payload.matrix.kTileCount #= 1
      dut.io.descriptor.payload.matrix.modes(0) #= TrapezoidDataflow.DenseIp
      dut.io.descriptor.payload.aBaseAddress #= aBase
      dut.io.descriptor.payload.bBaseAddress #= bBase
      dut.io.descriptor.valid #= true
      sleep(1)
      assert(dut.io.descriptor.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.descriptor.valid #= false

      val observed = mutable.Map.empty[(Int, Int), Long]
      val random = new Random(0xcace)
      var timeout = 0
      while (!dut.io.done.toBoolean && timeout < 3000) {
        dut.io.result.ready #= random.nextBoolean()
        sleep(1)
        if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
          val element = dut.io.result.payload.element
          observed((element.row.toInt, element.column.toInt)) = element.value.toLong
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(timeout < 3000)
      assert(observed.toMap == Map(
        (0, 0) -> 14L, (0, 1) -> 20L,
        (1, 0) -> 15L, (1, 1) -> 21L))
      assert(aRequests.toSeq == Seq(4 -> 0, 5 -> 1, 6 -> 0, 7 -> 1))
      assert(bRequests.toSeq == Seq(32 -> 0, 33 -> 1, 34 -> 0, 35 -> 1))
      assert(!dut.io.descriptorError.toBoolean)
      assert(!dut.io.innerError.toBoolean)
      assert(!dut.io.memoryError.toBoolean)
      assert(dut.io.completedOutputTiles.toLong == 1)
    }
  }
}
