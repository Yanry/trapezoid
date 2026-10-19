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
        base -> encodeLine(Seq(Some(1 -> 5), None, Some(3 -> 2), None)),
        (base + 1) -> encodeLine(Seq(Some(2 -> -1), Some(3 -> 4), None, None)))
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

  test("cached TrGS spatial system refills, hits, and rejects TrGT before memory") {
    simConfig.compile(new CachedTrGsSpatialSystem(gustCfg)).doSim { dut =>
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

      val base = 30
      val memory = Map(
        base -> encodeLine(Seq(Some(1 -> 5), None, Some(3 -> 2), None)),
        (base + 1) -> encodeLine(Seq(Some(2 -> -1), Some(3 -> 4), None, None)))
      val refillAddresses = mutable.ArrayBuffer.empty[Int]

      fork {
        while (true) {
          dut.io.refillRequest.ready #= true
          dut.io.refillResponse.valid #= false
          sleep(1)
          if (dut.io.refillRequest.valid.toBoolean) {
            val bank = dut.io.refillRequest.payload.bank.toInt
            val address = dut.io.refillRequest.payload.address.toInt
            refillAddresses += address
            dut.clockDomain.waitSampling()
            dut.io.refillRequest.ready #= false
            dut.clockDomain.waitSampling(2)
            dut.io.refillResponse.payload.bank #= bank
            dut.io.refillResponse.payload.address #= address
            dut.io.refillResponse.payload.data #= memory(address)
            dut.io.refillResponse.payload.error #= false
            dut.io.refillResponse.valid #= true
            while (!dut.io.refillResponse.ready.toBoolean) {
              dut.clockDomain.waitSampling()
            }
            dut.clockDomain.waitSampling()
            dut.io.refillResponse.valid #= false
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      def issue(mode: TrapezoidDataflow.E, row: Int, aValue: Int): Unit = {
        dut.io.command.payload.mode #= mode
        dut.io.command.payload.endOfTile #= true
        dut.io.command.payload.aIndex #= 2
        dut.io.command.payload.a.valid #= true
        dut.io.command.payload.a.row #= row
        dut.io.command.payload.a.k #= 5
        dut.io.command.payload.a.value #= aValue
        dut.io.command.payload.bBaseAddress #= base
        dut.io.command.payload.lineCount #= 2
        dut.io.command.valid #= true
        sleep(1)
        while (!dut.io.command.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        sleep(1)
        dut.io.command.valid #= false
      }

      issue(TrapezoidDataflow.TrGt, row = 1, aValue = 9)
      assert(dut.io.commandDone.toBoolean)
      assert(dut.io.adapterUnsupportedMode.toBoolean)
      assert(dut.io.requestedLines.toLong == 0)
      assert(dut.io.assignedTiles.toLong == 0)
      assert(refillAddresses.isEmpty)

      def run(row: Int, aValue: Int, seed: Int): Seq[(Int, Int, Long)] = {
        val completedBefore = dut.io.completedTiles.toLong
        issue(TrapezoidDataflow.TrGs, row, aValue)
        val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
        val random = new Random(seed)
        var sawEnd = false
        var timeout = 0
        while ((!sawEnd || dut.io.completedTiles.toLong == completedBefore) &&
            timeout < 1000) {
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
        assert(timeout < 1000, s"row=$row results=$observed")
        observed.toSeq
      }

      assert(run(row = 8, aValue = 2, seed = 0x81) ==
        Seq((8, 1, 10L), (8, 2, -2L), (8, 3, 12L)))
      assert(refillAddresses.sorted == Seq(base, base + 1))
      assert(dut.io.cacheMissCount.toLong == 2)
      assert(dut.io.cacheRefillCount.toLong == 2)

      assert(run(row = 9, aValue = 3, seed = 0x82) ==
        Seq((9, 1, 15L), (9, 2, -3L), (9, 3, 18L)))
      assert(refillAddresses.size == 2)
      assert(dut.io.cacheHitCount.toLong == 2)
      assert(dut.io.assignedTiles.toLong == 2)
      assert(dut.io.completedTiles.toLong == 2)
      assert(!dut.io.memoryError.toBoolean)
      assert(!dut.io.addressError.toBoolean)
      assert(!dut.io.adapterUnsupportedMode.toBoolean)
      assert(dut.io.schedulerOverflow.toBigInt == 0)
      assert(dut.io.schedulerUnsupportedMode.toBigInt == 0)
    }
  }

  test("clustered TrGS system overlaps independent cache clients and tiles") {
    val clusteredCacheCfg = cacheCfg.copy(clientCount = 2)
    val clusteredGustCfg = gustCfg.copy(cache = clusteredCacheCfg)
    simConfig.compile(new ClusteredCachedTrGsSpatialSystem(
      clusteredGustCfg, wideWriteback = true)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.command.valid #= false
      dut.io.result.ready #= false
      dut.io.resultLine.ready #= false
      dut.io.refillRequest.ready #= false
      dut.io.refillResponse.valid #= false
      dut.io.refillResponse.payload.bank #= 0
      dut.io.refillResponse.payload.address #= 0
      dut.io.refillResponse.payload.data #= 0
      dut.io.refillResponse.payload.error #= false

      val memory = Map(
        40 -> encodeLine(Seq(Some(1 -> 3), None, None, None)),
        41 -> encodeLine(Seq(Some(2 -> 5), None, None, None)))

      def issue(row: Int, aValue: Int, address: Int): Unit = {
        dut.io.command.payload.mode #= TrapezoidDataflow.TrGs
        dut.io.command.payload.endOfTile #= true
        dut.io.command.payload.aIndex #= 0
        dut.io.command.payload.a.valid #= true
        dut.io.command.payload.a.row #= row
        dut.io.command.payload.a.k #= 0
        dut.io.command.payload.a.value #= aValue
        dut.io.command.payload.bBaseAddress #= address
        dut.io.command.payload.lineCount #= 1
        dut.io.command.valid #= true
        sleep(1)
        var timeout = 0
        while (!dut.io.command.ready.toBoolean && timeout < 100) {
          dut.clockDomain.waitSampling()
          sleep(1)
          timeout += 1
        }
        assert(timeout < 100, s"cluster command for row $row timed out")
        dut.clockDomain.waitSampling()
        dut.io.command.valid #= false
      }

      issue(row = 10, aValue = 2, address = 40)
      issue(row = 20, aValue = 4, address = 41)

      var timeout = 0
      sleep(1)
      while (dut.io.cacheClientOutstanding.toBigInt != 3 && timeout < 100) {
        dut.clockDomain.waitSampling()
        sleep(1)
        timeout += 1
      }
      assert(timeout < 100, "both cache clients did not become outstanding")
      assert(dut.io.clusterOwned.toBigInt == 3)
      assert(dut.io.adapterBusy.toBigInt == 3)

      val refillAddresses = mutable.ArrayBuffer.empty[Int]
      for (_ <- 0 until 2) {
        dut.io.refillRequest.ready #= true
        sleep(1)
        while (!dut.io.refillRequest.valid.toBoolean) {
          dut.clockDomain.waitSampling()
          sleep(1)
        }
        val bank = dut.io.refillRequest.payload.bank.toInt
        val address = dut.io.refillRequest.payload.address.toInt
        refillAddresses += address
        dut.clockDomain.waitSampling()
        dut.io.refillRequest.ready #= false

        dut.io.refillResponse.payload.bank #= bank
        dut.io.refillResponse.payload.address #= address
        dut.io.refillResponse.payload.data #= memory(address)
        dut.io.refillResponse.payload.error #= false
        dut.io.refillResponse.valid #= true
        sleep(1)
        while (!dut.io.refillResponse.ready.toBoolean) {
          dut.clockDomain.waitSampling()
          sleep(1)
        }
        dut.clockDomain.waitSampling()
        dut.io.refillResponse.valid #= false
      }

      timeout = 0
      while ((dut.io.assignedTiles.toLong != 2 ||
          dut.io.allocated.toBigInt.bitCount != 2) && timeout < 200) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(timeout < 200,
        s"assigned=${dut.io.assignedTiles.toLong} allocated=${dut.io.allocated.toBigInt}")

      val observed = mutable.ArrayBuffer.empty[(Int, Int, Long)]
      dut.io.resultLine.ready #= true
      timeout = 0
      while (observed.size < 2 && timeout < 300) {
        sleep(1)
        if (dut.io.resultLine.valid.toBoolean) {
          for (lane <- 0 until cfg.cacheLineWords
               if dut.io.resultLine.payload.results(lane).valid.toBoolean) {
            val result = dut.io.resultLine.payload.results(lane)
            observed += ((result.row.toInt, result.column.toInt,
              result.value.toLong))
          }
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(observed.toSet == Set((10, 1, 6L), (20, 2, 20L)))

      while ((dut.io.completedTiles.toLong != 2 ||
          dut.io.clusterOwned.toBigInt != 0) && timeout < 340) {
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(dut.io.completedTiles.toLong == 2)
      assert(dut.io.clusterOwned.toBigInt == 0)
      assert(dut.io.cacheClientOutstanding.toBigInt == 0)
      assert(refillAddresses.sorted == Seq(40, 41))
      assert(dut.io.cacheMissCount.toLong == 2)
      assert(dut.io.cacheRefillCount.toLong == 2)
      assert(dut.io.schedulerOverflow.toBigInt == 0)
      assert(dut.io.memoryError.toBigInt == 0)
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

  test("TrGT cache adapter emits one ordered element per temporal subrow cycle") {
    simConfig.compile(new GustavsonBRowCacheAdapter(gustCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.rowCommand.valid #= false
      dut.io.cacheRequest.ready #= true
      dut.io.cacheResponse.valid #= false
      dut.io.cacheResponse.payload.wasHit #= true
      dut.io.cacheResponse.payload.error #= false
      dut.io.peCommand.ready #= false

      dut.io.rowCommand.payload.mode #= TrapezoidDataflow.TrGt
      dut.io.rowCommand.payload.endOfTile #= true
      dut.io.rowCommand.payload.aIndex #= 2
      dut.io.rowCommand.payload.a.valid #= true
      dut.io.rowCommand.payload.a.row #= 7
      dut.io.rowCommand.payload.a.k #= 5
      dut.io.rowCommand.payload.a.value #= 3
      dut.io.rowCommand.payload.bBaseAddress #= 12
      dut.io.rowCommand.payload.lineCount #= 1
      dut.io.rowCommand.valid #= true
      while (!dut.io.rowCommand.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.rowCommand.valid #= false

      while (!dut.io.cacheResponse.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.io.cacheResponse.payload.requestId #= 0
      dut.io.cacheResponse.payload.data #= encodeLine(Seq(
        Some(1 -> 5), None, Some(3 -> 2), Some(4 -> -1)))
      dut.io.cacheResponse.valid #= true
      dut.clockDomain.waitSampling()
      dut.io.cacheResponse.valid #= false

      val expected = Seq((1, 5, false), (3, 2, false), (4, -1, true))
      for (((column, value, endOfTile), element) <- expected.zipWithIndex) {
        while (!dut.io.peCommand.valid.toBoolean) dut.clockDomain.waitSampling()
        val command = dut.io.peCommand.payload
        assert(command.gustavson.b(2).valid.toBoolean)
        assert(command.gustavson.b(2).aIndex.toInt == 2)
        assert(command.gustavson.b(2).column.toInt == column)
        assert(command.gustavson.b(2).value.toInt == value)
        assert(command.endOfTile.toBoolean == endOfTile)
        for (lane <- 0 until cfg.cacheLineWords if lane != 2) {
          assert(!command.gustavson.b(lane).valid.toBoolean)
        }

        if (element == 0) {
          dut.clockDomain.waitSampling(2)
          assert(dut.io.peCommand.payload.gustavson.b(2).column.toInt == column)
          assert(dut.io.peCommand.payload.gustavson.b(2).value.toInt == value)
        }
        dut.io.peCommand.ready #= true
        dut.clockDomain.waitSampling()
        dut.io.peCommand.ready #= false
        sleep(1)
      }

      sleep(1)
      assert(dut.io.done.toBoolean)
      assert(dut.io.requestedLines.toLong == 1)
      assert(dut.io.emittedLines.toLong == 1)
    }
  }

  test("TrGS cache adapter overlaps the next request with buffered line output") {
    simConfig.compile(new GustavsonBRowCacheAdapter(gustCfg,
      allowTrGt = false, allowTrGs = true,
      emitSpatialCommand = true)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.rowCommand.valid #= false
      dut.io.cacheRequest.ready #= true
      dut.io.cacheResponse.valid #= false
      dut.io.cacheResponse.payload.wasHit #= true
      dut.io.cacheResponse.payload.error #= false
      dut.io.spatialCommand.ready #= true

      dut.io.rowCommand.payload.mode #= TrapezoidDataflow.TrGs
      dut.io.rowCommand.payload.endOfTile #= true
      dut.io.rowCommand.payload.aIndex #= 3
      dut.io.rowCommand.payload.a.valid #= true
      dut.io.rowCommand.payload.a.row #= 6
      dut.io.rowCommand.payload.a.k #= 4
      dut.io.rowCommand.payload.a.value #= 2
      dut.io.rowCommand.payload.bBaseAddress #= 40
      dut.io.rowCommand.payload.lineCount #= 3
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

      for (line <- 0 until 3) {
        waitFor(dut.io.cacheRequest.valid.toBoolean,
          s"cache request $line timed out")
        assert(dut.io.cacheRequest.payload.requestId.toInt == line)
        if (line > 0) {
          assert(dut.io.spatialCommand.valid.toBoolean,
            s"request $line did not overlap line ${line - 1} output")
          assert(dut.io.spatialCommand.payload.b(0).column.toInt == line - 1)
          assert(!dut.io.spatialCommand.payload.endOfTile.toBoolean)
        }
        dut.clockDomain.waitSampling()

        dut.io.cacheResponse.payload.requestId #= line
        dut.io.cacheResponse.payload.data #=
          encodeLine(Seq(Some(line -> (line + 1)), None, None, None))
        dut.io.cacheResponse.valid #= true
        waitFor(dut.io.cacheResponse.ready.toBoolean,
          s"cache response $line timed out")
        dut.clockDomain.waitSampling()
        dut.io.cacheResponse.valid #= false
      }

      waitFor(dut.io.spatialCommand.valid.toBoolean,
        "final spatial command timed out")
      assert(dut.io.spatialCommand.payload.b(0).column.toInt == 2)
      assert(dut.io.spatialCommand.payload.endOfTile.toBoolean)
      dut.clockDomain.waitSampling()
      sleep(1)

      assert(dut.io.done.toBoolean)
      assert(dut.io.requestedLines.toLong == 3)
      assert(dut.io.emittedLines.toLong == 3)
      assert(!dut.io.memoryError.toBoolean)
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
      assert(dut.io.cacheRequest.valid.toBoolean,
        "second request must overlap the first buffered line output")
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
