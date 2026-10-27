package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.{SpinalConfig, Verilog}
import spinal.core.sim._
import trapezoid.model.{BitmaskFiber, MatrixReference}

import scala.collection.mutable

class UnifiedEndToEndSystemSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 32,
    coordinateWidth = 8,
    fiberWidth = 4,
    maxFibers = 2,
    macLanes = 4,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 2,
    localDepthPerBank = 8
  )
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
    clientCount = 2 * cfg.localBanks + cfg.peRows + 2,
    requestIdWidth = 3
  )
  private val gustCfg = GustavsonCacheConfig(
    GustavsonTileConfig(cfg, accumulatorEntries = 4,
      fifoDepthPerSubrow = 2),
    cacheCfg,
    maxLinesPerRow = 2
  )
  private val systemCfg = EndToEndSystemConfig(
    matrixCfg, gustCfg, CacheClusterConfig(cacheCfg, clusterCount = 2),
    maxCResults = 4)
  private val fiberCacheCfg = FiberCacheConfig(cfg, cacheCfg)

  private val ipABase = 16
  private val ipBBase = 32
  private val rowPointerBase = 48
  private val aEntryBase = 56
  private val bDescriptorBase = 64
  private val bRowBase = 80
  private val cBase = 112

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("mode-specialized end-to-end profiles elaborate independently") {
    val profiles = Seq(
      EndToEndHardwareProfile.IpOnly,
      EndToEndHardwareProfile.TrGtOnly,
      EndToEndHardwareProfile.TrGsOnly)
    profiles.zipWithIndex.foreach { case (profile, index) =>
      SpinalConfig(
        mode = Verilog,
        targetDirectory = s"simWorkspace/unified-profile-$index",
        oneFilePerComponent = true
      ).generate(new UnifiedEndToEndSystem(systemCfg,
        hardwareProfile = profile))
    }
  }

  private def encodeFiber(fiber: BitmaskFiber): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    val valuesOffset = 1 + cfg.fiberWidth
    val tagOffset = valuesOffset + cfg.fiberWidth * cfg.dataWidth
    var record = BigInt(1) | (fiber.mask << 1) |
      (BigInt(fiber.tag) << tagOffset)
    for (entry <- 0 until cfg.fiberWidth) {
      record |= (BigInt(fiber.values.lift(entry).getOrElse(0L)) & valueMask) <<
        (valuesOffset + entry * cfg.dataWidth)
    }
    record
  }

  private def encodeAEntry(k: Int, value: Int): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    BigInt(1) | ((BigInt(value) & valueMask) << 1) |
      (BigInt(k) << (1 + cfg.dataWidth))
  }

  private def encodeBDescriptor(base: Int, lines: Int): BigInt =
    BigInt(base) | (BigInt(lines) << cacheCfg.addressWidth)

  private def encodeBLine(entries: Seq[(Int, Int)]): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    entries.zipWithIndex.foldLeft(BigInt(0)) {
      case (line, ((column, value), lane)) =>
        val offset = lane * gustCfg.elementWidth
        line | (BigInt(1) << offset) |
          ((BigInt(value) & valueMask) << (offset + 1)) |
          (BigInt(column) << (offset + 1 + cfg.dataWidth))
    }
  }

  private val a = Vector(Vector(1L, 2L), Vector(0L, 3L))
  private val b = Vector(Vector(4L, 5L), Vector(0L, 0L))
  private val expected = Map(
    (0, 0) -> 4L, (0, 1) -> 5L)

  private def inputMemory: Map[Int, BigInt] = {
    val aFibers = Vector(
      MatrixReference.encodeFiber(0, Vector(1L, 2L, 0L, 0L)),
      MatrixReference.encodeFiber(1, Vector(0L, 3L, 0L, 0L)))
    val bFibers = Vector(
      MatrixReference.encodeFiber(0, Vector(4L, 0L, 0L, 0L)),
      MatrixReference.encodeFiber(1, Vector(5L, 0L, 0L, 0L)))
    require(fiberCacheCfg.linesPerFiber == 1)
    Map(
      ipABase -> encodeFiber(aFibers(0)),
      ipABase + 1 -> encodeFiber(aFibers(1)),
      ipBBase -> encodeFiber(bFibers(0)),
      ipBBase + 1 -> encodeFiber(bFibers(1)),
      rowPointerBase -> BigInt(0),
      rowPointerBase + 1 -> BigInt(2),
      rowPointerBase + 2 -> BigInt(3),
      aEntryBase -> encodeAEntry(0, 1),
      aEntryBase + 1 -> encodeAEntry(1, 2),
      aEntryBase + 2 -> encodeAEntry(1, 3),
      bDescriptorBase -> encodeBDescriptor(bRowBase, 1),
      bDescriptorBase + 1 -> encodeBDescriptor(bRowBase + 1, 1),
      bRowBase -> encodeBLine(Seq(0 -> 4, 1 -> 5)),
      bRowBase + 1 -> encodeBLine(Seq.empty)
    )
  }

  private def signed(value: BigInt, width: Int): Long = {
    val sign = BigInt(1) << (width - 1)
    val modulus = BigInt(1) << width
    (if ((value & sign) != 0) value - modulus else value).toLong
  }

  private def decodeResult(data: BigInt): ((Int, Int), Long) = {
    assert((data & 1) == 1)
    val coordinateMask = (BigInt(1) << cfg.coordinateWidth) - 1
    val row = ((data >> 1) & coordinateMask).toInt
    val column = ((data >> (1 + cfg.coordinateWidth)) & coordinateMask).toInt
    val valueBits = (data >> (1 + 2 * cfg.coordinateWidth)) &
      ((BigInt(1) << cfg.accumulatorWidth) - 1)
    (row, column) -> signed(valueBits, cfg.accumulatorWidth)
  }

  test("one descriptor covers CSR DMA, cache clusters, compute, and C write responses") {
    simConfig.compile(new UnifiedEndToEndSystem(systemCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.descriptor.valid #= false
      dut.io.dmaReadRequest.ready #= true
      dut.io.dmaReadResponse.valid #= false
      dut.io.dmaReadResponse.payload.bank #= 0
      dut.io.dmaReadResponse.payload.address #= 0
      dut.io.dmaReadResponse.payload.data #= 0
      dut.io.dmaReadResponse.payload.error #= false
      dut.io.dmaWriteRequest.ready #= true
      dut.io.dmaWriteResponse.valid #= false
      dut.io.dmaWriteResponse.payload.address #= 0
      dut.io.dmaWriteResponse.payload.error #= false

      val memory = inputMemory
      val writes = mutable.ArrayBuffer.empty[(Int, BigInt)]
      var lastWriteResponseEdge = -1L
      var edge = 0L

      fork {
        while (true) {
          dut.io.dmaReadRequest.ready #= true
          dut.io.dmaReadResponse.valid #= false
          sleep(1)
          if (dut.io.dmaReadRequest.valid.toBoolean) {
            val bank = dut.io.dmaReadRequest.payload.bank.toInt
            val address = dut.io.dmaReadRequest.payload.address.toInt
            assert(bank == (address & (cacheCfg.bankCount - 1)))
            assert(memory.contains(address), s"unmapped DMA read $address")
            dut.clockDomain.waitSampling()
            dut.io.dmaReadRequest.ready #= false
            dut.clockDomain.waitSampling(2)
            dut.io.dmaReadResponse.payload.bank #= bank
            dut.io.dmaReadResponse.payload.address #= address
            dut.io.dmaReadResponse.payload.data #= memory(address)
            dut.io.dmaReadResponse.payload.error #= false
            dut.io.dmaReadResponse.valid #= true
            while (!dut.io.dmaReadResponse.ready.toBoolean) {
              dut.clockDomain.waitSampling()
            }
            dut.clockDomain.waitSampling()
            dut.io.dmaReadResponse.valid #= false
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      fork {
        while (true) {
          dut.io.dmaWriteRequest.ready #= true
          dut.io.dmaWriteResponse.valid #= false
          sleep(1)
          if (dut.io.dmaWriteRequest.valid.toBoolean) {
            val address = dut.io.dmaWriteRequest.payload.address.toInt
            val data = dut.io.dmaWriteRequest.payload.data.toBigInt
            writes += address -> data
            dut.clockDomain.waitSampling()
            dut.io.dmaWriteRequest.ready #= false
            dut.clockDomain.waitSampling(3)
            dut.io.dmaWriteResponse.payload.address #= address
            dut.io.dmaWriteResponse.payload.error #= false
            dut.io.dmaWriteResponse.valid #= true
            while (!dut.io.dmaWriteResponse.ready.toBoolean) {
              dut.clockDomain.waitSampling()
            }
            lastWriteResponseEdge = edge
            dut.clockDomain.waitSampling()
            dut.io.dmaWriteResponse.valid #= false
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          edge += 1
        }
      }

      def run(mode: TrapezoidDataflow.E): Long = {
        dut.io.clear #= true
        dut.clockDomain.waitSampling(2)
        dut.io.clear #= false
        writes.clear()
        lastWriteResponseEdge = -1L

        dut.io.descriptor.payload.mode #= mode
        dut.io.descriptor.payload.rowCount #= 2
        dut.io.descriptor.payload.columnCount #= 2
        dut.io.descriptor.payload.kTileCount #= 1
        dut.io.descriptor.payload.ipABaseAddress #= ipABase
        dut.io.descriptor.payload.ipBBaseAddress #= ipBBase
        dut.io.descriptor.payload.csrRowPointerBase #= rowPointerBase
        dut.io.descriptor.payload.csrAEntryBase #= aEntryBase
        dut.io.descriptor.payload.csrBRowDescriptorBase #= bDescriptorBase
        dut.io.descriptor.payload.cBaseAddress #= cBase
        dut.io.descriptor.valid #= true
        while (!dut.io.descriptor.ready.toBoolean) dut.clockDomain.waitSampling()
        val startEdge = edge
        dut.clockDomain.waitSampling()
        dut.io.descriptor.valid #= false

        var timeout = 0
        while (!dut.io.done.toBoolean && timeout < 5000) {
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        assert(timeout < 5000,
          s"mode $mode timed out phase=${dut.io.trgtPhase.toInt} " +
            s"schedulerBusy=${dut.io.trgtSchedulerBusy.toBoolean} " +
            s"fifo=${dut.io.trgtFifoOccupancy.toLong} " +
            s"assigned=${dut.io.trgtAssignedTiles.toLong} " +
            s"completed=${dut.io.trgtCompletedTiles.toLong}")
        val doneEdge = edge
        assert(!dut.io.error.toBoolean, s"mode $mode reported an error")
        assert(writes.map(_._1) == (cBase until cBase + expected.size))
        assert(writes.map(entry => decodeResult(entry._2)).toMap == expected)
        assert(dut.io.writtenResults.toLong == expected.size)
        if (mode == TrapezoidDataflow.TrGt) {
          assert(dut.io.trgtAssignedTiles.toLong == 1,
            "two short CSR rows should share one packed TrGT tile")
          assert(dut.io.trgtCompletedTiles.toLong == 1)
        }
        assert(lastWriteResponseEdge >= startEdge)
        assert(doneEdge >= lastWriteResponseEdge,
          "completion must not precede the final C write response")
        doneEdge - startEdge + 1
      }

      val cycles = Seq(
        run(TrapezoidDataflow.DenseIp),
        run(TrapezoidDataflow.TrIp),
        run(TrapezoidDataflow.TrGt),
        run(TrapezoidDataflow.TrGs))
      assert(cycles.forall(_ > 0))
    }
  }
}
