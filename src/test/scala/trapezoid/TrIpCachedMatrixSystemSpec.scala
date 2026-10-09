package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._
import trapezoid.model.{BitmaskFiber, MatrixReference}

import scala.collection.mutable
import scala.util.Random

class TrIpCachedMatrixSystemSpec extends AnyFunSuite {
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
  private val tileCfg = TrIpTileConfig(cfg, maxColumns = 2)
  private val matrixCfg = TrIpMatrixConfig(tileCfg,
    maxMatrixRows = 2, maxMatrixColumns = 2, maxKTiles = 1)
  private val cacheCfg = CacheConfig(
    wordWidth = 8,
    wordsPerLine = 4,
    addressWidth = 8,
    bankCount = 2,
    setsPerBank = 8,
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

  test("integrated matrix cache refills once and hits on a repeated descriptor") {
    simConfig.compile(new TrIpCachedMatrixSystem(matrixCfg, cacheCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.descriptor.valid #= false
      dut.io.result.ready #= false
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.refillResponse.payload.bank #= 0
      dut.io.refillResponse.payload.address #= 0
      dut.io.refillResponse.payload.data #= 0
      dut.io.refillResponse.payload.error #= false

      val aBase = 4
      val bBase = 8
      val aFibers = Vector(
        MatrixReference.encodeFiber(0, Vector(1L, 2L, 0L, 0L)),
        MatrixReference.encodeFiber(1, Vector(0L, 3L, 0L, 0L)))
      val bFibers = Vector(
        MatrixReference.encodeFiber(0, Vector(4L, 5L, 0L, 0L)),
        MatrixReference.encodeFiber(1, Vector(6L, 7L, 0L, 0L)))

      def region(base: Int, fibers: Vector[BitmaskFiber]): Map[Int, BigInt] =
        fibers.zipWithIndex.flatMap { case (fiber, index) =>
          recordLines(encodeRecord(fiber)).zipWithIndex.map { case (data, line) =>
            base + index * fiberCacheCfg.linesPerFiber + line -> data
          }
        }.toMap

      val memory = region(aBase, aFibers) ++ region(bBase, bFibers)
      val refillAddresses = mutable.ArrayBuffer.empty[Int]
      fork {
        while (true) {
          dut.io.refillRequest.ready #= true
          dut.io.refillResponse.valid #= false
          sleep(1)
          if (dut.io.refillRequest.valid.toBoolean) {
            val bank = dut.io.refillRequest.payload.bank.toInt
            val address = dut.io.refillRequest.payload.address.toInt
            assert(bank == (address & (cacheCfg.bankCount - 1)))
            assert(memory.contains(address), s"unexpected refill address $address")
            refillAddresses += address
            dut.clockDomain.waitSampling()
            dut.io.refillRequest.ready #= false
            dut.clockDomain.waitSampling(2)
            dut.io.refillResponse.payload.bank #= bank
            dut.io.refillResponse.payload.address #= address
            dut.io.refillResponse.payload.data #= memory(address)
            dut.io.refillResponse.payload.error #= false
            dut.io.refillResponse.valid #= true
            var accepted = false
            while (!accepted) {
              sleep(1)
              accepted = dut.io.refillResponse.ready.toBoolean
              dut.clockDomain.waitSampling()
            }
            sleep(1)
            dut.io.refillResponse.valid #= false
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      def runDescriptor(seed: Int): Map[(Int, Int), Long] = {
        dut.io.descriptor.payload.matrix.rowCount #= 2
        dut.io.descriptor.payload.matrix.columnCount #= 2
        dut.io.descriptor.payload.matrix.kTileCount #= 1
        dut.io.descriptor.payload.matrix.modes(0) #= TrapezoidDataflow.DenseIp
        dut.io.descriptor.payload.aBaseAddress #= aBase
        dut.io.descriptor.payload.bBaseAddress #= bBase
        dut.io.descriptor.valid #= true
        var accepted = false
        var timeout = 0
        while (!accepted && timeout < 100) {
          sleep(1)
          accepted = dut.io.descriptor.ready.toBoolean
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        dut.io.descriptor.valid #= false
        assert(accepted)
        sleep(1)
        assert(!dut.io.done.toBoolean)

        val observed = mutable.Map.empty[(Int, Int), Long]
        val random = new Random(seed)
        timeout = 0
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
        dut.io.result.ready #= false
        assert(timeout < 3000)
        observed.toMap
      }

      val expected = Map(
        (0, 0) -> 14L, (0, 1) -> 20L,
        (1, 0) -> 15L, (1, 1) -> 21L)
      val firstResult = runDescriptor(0x51)
      assert(firstResult == expected,
        s"result=$firstResult refills=$refillAddresses " +
          s"hit=${dut.io.cacheHitCount.toLong} miss=${dut.io.cacheMissCount.toLong} " +
          s"filled=${dut.io.cacheRefillCount.toLong} descriptorError=" +
          s"${dut.io.descriptorError.toBoolean} addressError=" +
          s"${dut.io.addressError.toBoolean} memoryError=${dut.io.memoryError.toBoolean}")
      assert(refillAddresses.sorted == memory.keys.toSeq.sorted)
      assert(dut.io.cacheHitCount.toLong == 0)
      assert(dut.io.cacheMissCount.toLong == memory.size)
      assert(dut.io.cacheRefillCount.toLong == memory.size)

      assert(runDescriptor(0x52) == expected)
      assert(refillAddresses.size == memory.size)
      assert(dut.io.cacheHitCount.toLong == memory.size)
      assert(dut.io.cacheMissCount.toLong == memory.size)
      assert(dut.io.cacheRefillCount.toLong == memory.size)
      assert(!dut.io.descriptorError.toBoolean)
      assert(!dut.io.addressError.toBoolean)
      assert(!dut.io.innerError.toBoolean)
      assert(!dut.io.memoryError.toBoolean)
      assert(dut.io.completedOutputTiles.toLong == 1)
    }
  }
}
