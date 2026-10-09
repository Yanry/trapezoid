package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable
import scala.util.Random

class GustavsonCacheAdapterSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 24,
    coordinateWidth = 8,
    fiberWidth = 4,
    maxFibers = 2,
    macLanes = 4,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 2,
    localDepthPerBank = 8
  )
  private val tileCfg = GustavsonTileConfig(cfg, accumulatorEntries = 8,
    fifoDepthPerSubrow = 2)
  private val cacheCfg = CacheConfig(
    wordWidth = 8,
    wordsPerLine = 12,
    addressWidth = 8,
    bankCount = 2,
    setsPerBank = 8,
    clientCount = 1,
    requestIdWidth = 2
  )
  private val gustCfg = GustavsonCacheConfig(tileCfg, cacheCfg, maxLinesPerRow = 4)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private def encodeLine(entries: Seq[Option[(Int, Int)]]): BigInt = {
    require(entries.size == gustCfg.elementsPerLine)
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    entries.zipWithIndex.foldLeft(BigInt(0)) {
      case (line, (Some((column, value)), lane)) =>
        val offset = lane * gustCfg.elementWidth
        line | (BigInt(1) << offset) |
          ((BigInt(value) & valueMask) << (offset + 1)) |
          (BigInt(column) << (offset + 1 + cfg.dataWidth))
      case (line, (None, _)) => line
    }
  }

  private def driveCommand(
      dut: CachedGustavsonSystem,
      mode: TrapezoidDataflow.E,
      row: Int,
      aValue: Int,
      base: Int,
      lines: Int
  ): Unit = {
    dut.io.command.payload.mode #= mode
    dut.io.command.payload.endOfTile #= true
    dut.io.command.payload.aIndex #= 2
    dut.io.command.payload.a.valid #= true
    dut.io.command.payload.a.row #= row
    dut.io.command.payload.a.k #= 5
    dut.io.command.payload.a.value #= aValue
    dut.io.command.payload.bBaseAddress #= base
    dut.io.command.payload.lineCount #= lines
    dut.io.command.valid #= true
    var accepted = false
    var timeout = 0
    while (!accepted && timeout < 200) {
      sleep(1)
      accepted = dut.io.command.ready.toBoolean
      dut.clockDomain.waitSampling()
      timeout += 1
    }
    sleep(1)
    dut.io.command.valid #= false
    assert(accepted, "cached Gustavson command timed out")
  }

  test("cached Gustavson rows refill once and run either mode at the same sparsity") {
    simConfig.compile(new CachedGustavsonSystem(gustCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.command.valid #= false
      dut.io.result.ready #= false
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.refillResponse.payload.bank #= 0
      dut.io.refillResponse.payload.address #= 0
      dut.io.refillResponse.payload.data #= 0
      dut.io.refillResponse.payload.error #= false

      val base = 10
      val memory = Map(
        base -> encodeLine(Seq(Some(3 -> 2), None, Some(1 -> 5), None)),
        (base + 1) -> encodeLine(Seq(Some(3 -> 4), Some(2 -> -1), None, None)))
      val refillAddresses = mutable.ArrayBuffer.empty[Int]

      fork {
        while (true) {
          dut.io.refillRequest.ready #= true
          dut.io.refillResponse.valid #= false
          sleep(1)
          if (dut.io.refillRequest.valid.toBoolean) {
            val bank = dut.io.refillRequest.payload.bank.toInt
            val address = dut.io.refillRequest.payload.address.toInt
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

      def run(mode: TrapezoidDataflow.E, row: Int, aValue: Int,
              seed: Int): Seq[(Int, Int, Long)] = {
        val completedBefore = dut.io.completedTiles.toLong
        driveCommand(dut, mode, row, aValue, base, lines = 2)
        val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
        val random = new Random(seed)
        var sawEnd = false
        var timeout = 0
        while ((!sawEnd || dut.io.completedTiles.toLong == completedBefore) && timeout < 1000) {
          dut.io.result.ready #= random.nextBoolean()
          sleep(1)
          if (dut.io.result.valid.toBoolean && dut.io.result.ready.toBoolean) {
            observed += ((dut.io.result.payload.row.toInt,
              dut.io.result.payload.column.toInt,
              dut.io.result.payload.value.toLong))
            sawEnd = dut.io.result.payload.endOfTile.toBoolean
          }
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        dut.io.result.ready #= false
        assert(timeout < 1000, s"mode=$mode results=$observed")
        assert(dut.io.commandDone.toBoolean)
        assert(dut.io.requestedLines.toLong == 2)
        assert(dut.io.emittedLines.toLong == 2)
        observed.toSeq
      }

      assert(run(TrapezoidDataflow.TrGt, row = 7, aValue = 3, seed = 0x71) ==
        Seq((7, 1, 15L), (7, 2, -3L), (7, 3, 18L)))
      assert(refillAddresses.sorted == Seq(base, base + 1))
      assert(dut.io.cacheHitCount.toLong == 0)
      assert(dut.io.cacheMissCount.toLong == 2)
      assert(dut.io.cacheRefillCount.toLong == 2)

      assert(run(TrapezoidDataflow.TrGs, row = 8, aValue = 2, seed = 0x72) ==
        Seq((8, 1, 10L), (8, 2, -2L), (8, 3, 12L)))
      assert(refillAddresses.size == 2)
      assert(dut.io.cacheHitCount.toLong == 2)
      assert(dut.io.cacheMissCount.toLong == 2)
      assert(dut.io.cacheRefillCount.toLong == 2)
      assert(!dut.io.memoryError.toBoolean)
      assert(!dut.io.addressError.toBoolean)
      assert(!dut.io.adapterUnsupportedMode.toBoolean)
      assert(dut.io.schedulerOverflow.toBigInt == 0)
      assert(dut.io.schedulerUnsupportedMode.toBigInt == 0)
    }
  }

  test("B-row adapter rejects unsupported modes and overflowing address ranges") {
    simConfig.compile(new GustavsonBRowCacheAdapter(gustCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.rowCommand.valid #= false
      dut.io.cacheRequest.ready #= true
      dut.io.cacheResponse.valid #= false
      dut.io.peCommand.ready #= true

      def issue(mode: TrapezoidDataflow.E, base: Int, lines: Int): Unit = {
        dut.io.rowCommand.payload.mode #= mode
        dut.io.rowCommand.payload.endOfTile #= true
        dut.io.rowCommand.payload.aIndex #= 0
        dut.io.rowCommand.payload.a.valid #= true
        dut.io.rowCommand.payload.a.row #= 0
        dut.io.rowCommand.payload.a.k #= 0
        dut.io.rowCommand.payload.a.value #= 1
        dut.io.rowCommand.payload.bBaseAddress #= base
        dut.io.rowCommand.payload.lineCount #= lines
        dut.io.rowCommand.valid #= true
        sleep(1)
        assert(dut.io.rowCommand.ready.toBoolean)
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.rowCommand.valid #= false
      }

      issue(TrapezoidDataflow.DenseIp, base = 1, lines = 1)
      assert(dut.io.done.toBoolean)
      assert(dut.io.unsupportedMode.toBoolean)
      assert(!dut.io.addressError.toBoolean)
      assert(!dut.io.cacheRequest.valid.toBoolean)
      assert(dut.io.requestedLines.toLong == 0)

      issue(TrapezoidDataflow.TrGt, base = 255, lines = 2)
      assert(dut.io.done.toBoolean)
      assert(!dut.io.unsupportedMode.toBoolean)
      assert(dut.io.addressError.toBoolean)
      assert(!dut.io.cacheRequest.valid.toBoolean)
      assert(dut.io.requestedLines.toLong == 0)
    }
  }

  test("B-row adapter closes an open tile after a cache response error") {
    simConfig.compile(new GustavsonBRowCacheAdapter(gustCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.rowCommand.valid #= false
      dut.io.cacheRequest.ready #= true
      dut.io.cacheResponse.valid #= false
      dut.io.cacheResponse.payload.requestId #= 0
      dut.io.cacheResponse.payload.data #= 0
      dut.io.cacheResponse.payload.wasHit #= false
      dut.io.cacheResponse.payload.error #= false
      dut.io.peCommand.ready #= true

      dut.io.rowCommand.payload.mode #= TrapezoidDataflow.TrGs
      dut.io.rowCommand.payload.endOfTile #= true
      dut.io.rowCommand.payload.aIndex #= 1
      dut.io.rowCommand.payload.a.valid #= true
      dut.io.rowCommand.payload.a.row #= 3
      dut.io.rowCommand.payload.a.k #= 4
      dut.io.rowCommand.payload.a.value #= 2
      dut.io.rowCommand.payload.bBaseAddress #= 20
      dut.io.rowCommand.payload.lineCount #= 2
      dut.io.rowCommand.valid #= true
      sleep(1)
      assert(dut.io.rowCommand.ready.toBoolean)
      dut.clockDomain.waitSampling()
      dut.io.rowCommand.valid #= false

      def waitFor(condition: => Boolean, message: String): Unit = {
        var timeout = 0
        sleep(1)
        while (!condition && timeout < 100) {
          dut.clockDomain.waitSampling()
          sleep(1)
          timeout += 1
        }
        assert(timeout < 100, message)
      }

      waitFor(dut.io.cacheRequest.valid.toBoolean, "first cache request timed out")
      assert(dut.io.cacheRequest.payload.requestId.toInt == 0)
      dut.clockDomain.waitSampling()

      dut.io.cacheResponse.payload.requestId #= 0
      dut.io.cacheResponse.payload.data #=
        encodeLine(Seq(Some(2 -> 5), None, None, None))
      dut.io.cacheResponse.payload.error #= false
      dut.io.cacheResponse.valid #= true
      waitFor(dut.io.cacheResponse.ready.toBoolean, "first cache response timed out")
      dut.clockDomain.waitSampling()
      dut.io.cacheResponse.valid #= false

      waitFor(dut.io.peCommand.valid.toBoolean, "first PE command timed out")
      assert(!dut.io.peCommand.payload.endOfTile.toBoolean)
      assert(dut.io.peCommand.payload.gustavson.b(0).valid.toBoolean)
      dut.clockDomain.waitSampling()

      waitFor(dut.io.cacheRequest.valid.toBoolean, "second cache request timed out")
      assert(dut.io.cacheRequest.payload.requestId.toInt == 1)
      dut.clockDomain.waitSampling()

      dut.io.cacheResponse.payload.requestId #= 1
      dut.io.cacheResponse.payload.data #= 0
      dut.io.cacheResponse.payload.error #= true
      dut.io.cacheResponse.valid #= true
      waitFor(dut.io.cacheResponse.ready.toBoolean, "error response timed out")
      dut.clockDomain.waitSampling()
      dut.io.cacheResponse.valid #= false

      waitFor(dut.io.peCommand.valid.toBoolean, "abort PE command timed out")
      assert(dut.io.peCommand.payload.endOfTile.toBoolean)
      for (lane <- 0 until cfg.cacheLineWords) {
        assert(!dut.io.peCommand.payload.gustavson.b(lane).valid.toBoolean)
      }
      dut.clockDomain.waitSampling()
      sleep(1)

      assert(dut.io.done.toBoolean)
      assert(dut.io.memoryError.toBoolean)
      assert(dut.io.requestedLines.toLong == 2)
      assert(dut.io.emittedLines.toLong == 1)
    }
  }
}
