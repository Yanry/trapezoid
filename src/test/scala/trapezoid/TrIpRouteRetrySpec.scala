package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import trapezoid.model.{BitmaskFiber, DenseMatrix, MatrixReference}

import scala.collection.mutable

class TrIpRouteRetrySpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8, accumulatorWidth = 32, coordinateWidth = 8,
    fiberWidth = 8, maxFibers = 2, macLanes = 16,
    cacheLineWords = 4, outputLanes = 4, peRows = 4,
    localDepthPerBank = 32)
  private val tileCfg = TrIpTileConfig(cfg, maxColumns = 8)

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private class RejectingPERow(config: TrapezoidConfig, permanent: Boolean)
      extends TrapezoidPERow(config) {
    val reject = reduced.lanes(0).valid && reduced.lanes(0).row >= 6 &&
      reduced.lanes(0).column === 2 &&
      (if (permanent) True else segmentPrefix.last > 1)
    nextResponse.overflow.allowOverride
    nextResponse.count.allowOverride
    when(reject) {
      nextResponse.overflow := True
      nextResponse.count := 0
    }
    for (result <- nextResponse.results) {
      result.valid.allowOverride
      when(reject) { result.valid := False }
    }
  }

  private def initialize(dut: TrIpTileController): Unit = {
    dut.io.clear #= false
    dut.io.aLoad.valid #= false
    dut.io.bLoad.valid #= false
    dut.io.start.valid #= false
    dut.io.result.ready #= false
    dut.clockDomain.waitSampling(10)
  }

  private def driveFiber(target: IndexedFiber, index: Int, fiber: BitmaskFiber): Unit = {
    target.index #= index
    target.fiber.valid #= true
    target.fiber.tag #= fiber.tag
    target.fiber.mask #= fiber.mask
    for (entry <- 0 until cfg.fiberWidth) {
      target.fiber.values(entry) #= fiber.values.lift(entry).getOrElse(0L)
    }
  }

  private def load(dut: TrIpTileController, a: DenseMatrix, b: DenseMatrix): Unit = {
    for ((fiber, index) <- MatrixReference.rowFibers(a).zipWithIndex) {
      driveFiber(dut.io.aLoad.payload, index, fiber)
      dut.io.aLoad.valid #= true
      sleep(1)
      while (!dut.io.aLoad.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.aLoad.valid #= false
    }
    for ((fiber, index) <- MatrixReference.columnFibers(b).zipWithIndex) {
      driveFiber(dut.io.bLoad.payload, index, fiber)
      dut.io.bLoad.valid #= true
      sleep(1)
      while (!dut.io.bLoad.ready.toBoolean) dut.clockDomain.waitSampling()
      dut.clockDomain.waitSampling()
      dut.io.bLoad.valid #= false
    }
  }

  private def start(dut: TrIpTileController, mode: TrapezoidDataflow.E,
                    rows: Int, columns: Int, first: Boolean, last: Boolean): Unit = {
    dut.io.start.payload.mode #= mode
    dut.io.start.payload.rowCount #= rows
    dut.io.start.payload.columnCount #= columns
    dut.io.start.payload.firstKTile #= first
    dut.io.start.payload.lastKTile #= last
    dut.io.start.valid #= true
    sleep(1)
    assert(dut.io.start.ready.toBoolean)
    dut.clockDomain.waitSampling()
    dut.io.start.valid #= false
  }

  private def collect(dut: TrIpTileController, expectError: Boolean = false,
                      expectOutput: Boolean = true): Map[(Int, Int), Long] = {
    val observed = mutable.LinkedHashMap.empty[(Int, Int), Long]
    var finished = false
    var cycle = 0
    var lastMarkers = 0
    while (!finished && cycle < 30000) {
      dut.io.result.ready #= (cycle % 7 >= 3)
      sleep(1)
      if (dut.io.result.valid.toBoolean) {
        assert(expectOutput)
        if (dut.io.result.ready.toBoolean) {
          val result = dut.io.result.payload
          val coordinate = (result.row.toInt, result.column.toInt)
          assert(!observed.contains(coordinate), s"duplicate result $coordinate")
          observed(coordinate) = result.value.toLong
          if (result.endOfTile.toBoolean) lastMarkers += 1
        }
      }
      finished = dut.io.done.toBoolean
      dut.clockDomain.waitSampling()
      cycle += 1
    }
    assert(finished, "retry scheduler timed out")
    assert(dut.io.unsupportedMode.toBoolean == expectError)
    assert(lastMarkers == (if (observed.nonEmpty) 1 else 0))
    assert(observed.keys.toVector == observed.keys.toVector.sorted)
    observed.toMap
  }

  private def nonzeroResults(a: DenseMatrix, b: DenseMatrix): Map[(Int, Int), Long] = {
    val product = MatrixReference.multiply(a, b)
    (for {
      row <- 0 until product.rows
      column <- 0 until product.columns
      if product(row, column) != 0
    } yield (row, column) -> product(row, column)).toMap
  }

  test("unroutable B multicast retries without losing the second K tile or duplicating other PE rows") {
    val firstA = DenseMatrix(Vector.tabulate(8, 8) { (row, coordinate) =>
      if (coordinate == 0) (if (row == 6) 1L else row + 1L) else 0L
    })
    val firstB = DenseMatrix(Vector.tabulate(8, 6) { (coordinate, column) =>
      if (coordinate == 0) (if (column == 0) -5L else column + 1L) else 0L
    })
    val aMasks = Vector(1, 2, 4, 8, 16, 32, 0x69, 0xa6)
    val bMasks = Vector(0xee, 0x37, 1, 2, 4, 8)
    val secondA = DenseMatrix(Vector.tabulate(8, 8) { (row, coordinate) =>
      if ((aMasks(row) & (1 << coordinate)) != 0) 1L else 0L
    })
    val secondB = DenseMatrix(Vector.tabulate(8, 6) { (coordinate, column) =>
      if ((bMasks(column) & (1 << coordinate)) == 0) 0L
      else if (column == 0 && coordinate == 3) -2L
      else if (column == 0 && coordinate == 5) -3L
      else if (column == 0 && coordinate == 6) -4L
      else column + coordinate + 1L
    })
    val expectedFirst = nonzeroResults(firstA, firstB)
    val expectedSecond = nonzeroResults(secondA, secondB)
    val expected = (expectedFirst.keySet ++ expectedSecond.keySet).map { coordinate =>
      coordinate -> (expectedFirst.getOrElse(coordinate, 0L) +
        expectedSecond.getOrElse(coordinate, 0L))
    }.filter(_._2 != 0).toMap
    assert(expectedFirst((6, 0)) == -5)
    assert(expectedSecond((6, 0)) == -9)
    assert(expected((6, 0)) == -14)

    simConfig.compile(new TrIpTileController(tileCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initialize(dut)
      for (mode <- Seq(TrapezoidDataflow.TrIp, TrapezoidDataflow.DenseIp)) {
        dut.io.clear #= true
        dut.clockDomain.waitSampling()
        dut.io.clear #= false
        load(dut, firstA, firstB)
        start(dut, mode, 8, 6, first = true, last = false)
        assert(collect(dut, expectOutput = false).isEmpty)
        assert(dut.io.awaitingKTile.toBoolean)
        load(dut, secondA, secondB)
        start(dut, mode, 8, 6, first = false, last = true)
        val observed = collect(dut)
        assert(observed == expected)
        assert(observed((6, 0)) == -14)
        assert(dut.io.routeRetryCount.toLong > 0)
        assert(!dut.io.awaitingKTile.toBoolean)
      }
    }
  }

  test("single A fallback preserves physical banks and does not replay earlier B columns") {
    simConfig.compile(new TrIpTileController(tileCfg,
      config => new RejectingPERow(config, permanent = false))).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initialize(dut)
      val a = DenseMatrix(Vector.tabulate(8, 8) { (row, coordinate) =>
        if (coordinate == 0) row + 1L else 0L
      })
      val b = DenseMatrix(Vector.tabulate(8, 6) { (coordinate, column) =>
        if (coordinate == 0) column + 2L else 0L
      })
      load(dut, a, b)
      start(dut, TrapezoidDataflow.TrIp, 8, 6, first = true, last = true)
      assert(collect(dut) == nonzeroResults(a, b))
      assert(dut.io.routeRetryCount.toLong == 2)
    }
  }

  private val systemTileCfg = TrIpTileConfig(cfg, maxColumns = 16)
  private val cacheCfg = CacheConfig(
    wordWidth = 8, wordsPerLine = 16, addressWidth = 12,
    bankCount = 8, setsPerBank = 64, clientCount = 14, requestIdWidth = 8)
  private val systemCfg = EndToEndSystemConfig(
    TrIpMatrixConfig(systemTileCfg, 16, 16, 2),
    GustavsonCacheConfig(GustavsonTileConfig(cfg, accumulatorEntries = 64),
      cacheCfg, maxLinesPerRow = 8),
    CacheClusterConfig(cacheCfg, clusterCount = 4), maxCResults = 256)

  private def encodeFiber(fiber: BitmaskFiber): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    val valuesOffset = 1 + cfg.fiberWidth
    val tagOffset = valuesOffset + cfg.fiberWidth * cfg.dataWidth
    var record = BigInt(1) | (fiber.mask << 1) | (BigInt(fiber.tag) << tagOffset)
    for ((value, index) <- fiber.values.zipWithIndex) {
      record |= (BigInt(value) & valueMask) << (valuesOffset + index * cfg.dataWidth)
    }
    record
  }

  private def runSystem(dut: UnifiedEndToEndSystem, a: DenseMatrix,
                        b: DenseMatrix, expectError: Boolean): Unit = {
    val aBase = 128
    val bBase = 512
    val cBase = 2048
    val memory = mutable.Map.empty[Int, BigInt]
    for (row <- 0 until 16; tile <- 0 until 2) {
      memory(aBase + row * 2 + tile) = encodeFiber(
        MatrixReference.encodeFiber(row, a.values(row).slice(tile * 8, tile * 8 + 8)))
    }
    for (tile <- 0 until 2; column <- 0 until 16) {
      memory(bBase + tile * 16 + column) = encodeFiber(
        MatrixReference.encodeFiber(column,
          Vector.tabulate(8)(coordinate => b(tile * 8 + coordinate, column))))
    }
    for (row <- 0 until cfg.peRows) dut.io.mrnProgram(row).valid #= false
    dut.io.clear #= false
    dut.io.descriptor.valid #= false
    dut.io.dmaReadRequest.ready #= false
    dut.io.dmaReadResponse.valid #= false
    dut.io.dmaReadResponse.payload.bank #= 0
    dut.io.dmaReadResponse.payload.address #= 0
    dut.io.dmaReadResponse.payload.data #= 0
    dut.io.dmaReadResponse.payload.error #= false
    dut.io.dmaWriteRequest.ready #= false
    dut.io.dmaWriteResponse.valid #= false
    dut.io.dmaWriteResponse.payload.address #= 0
    dut.io.dmaWriteResponse.payload.error #= false
    dut.clockDomain.waitSampling(10)
    val descriptor = dut.io.descriptor.payload
    descriptor.mode #= TrapezoidDataflow.TrIp
    descriptor.rowCount #= 16
    descriptor.columnCount #= 16
    descriptor.kTileCount #= 2
    descriptor.ipABaseAddress #= aBase
    descriptor.ipBBaseAddress #= bBase
    descriptor.csrRowPointerBase #= 0
    descriptor.csrAEntryBase #= 0
    descriptor.csrBRowDescriptorBase #= 0
    descriptor.cBaseAddress #= cBase
    var descriptorAccepted = false
    val reads = mutable.Queue.empty[(Int, Int, Int)]
    val writes = mutable.Queue.empty[(Int, Int)]
    val observed = mutable.Map.empty[(Int, Int), Long]
    var cycle = 0
    var lastWriteResponse = -1
    while (!dut.io.done.toBoolean && cycle < 60000) {
      dut.io.descriptor.valid #= !descriptorAccepted
      dut.io.dmaReadRequest.ready #= (cycle % 5 != 0)
      dut.io.dmaWriteRequest.ready #= (cycle % 7 >= 2)
      val readResponse = reads.headOption.filter(_._3 <= cycle)
      dut.io.dmaReadResponse.valid #= readResponse.nonEmpty
      readResponse.foreach { case (bank, address, _) =>
        dut.io.dmaReadResponse.payload.bank #= bank
        dut.io.dmaReadResponse.payload.address #= address
        dut.io.dmaReadResponse.payload.data #= memory(address)
      }
      val writeResponse = writes.headOption.filter(_._2 <= cycle)
      dut.io.dmaWriteResponse.valid #= writeResponse.nonEmpty
      writeResponse.foreach { case (address, _) =>
        dut.io.dmaWriteResponse.payload.address #= address
      }
      sleep(1)
      if (dut.io.descriptor.valid.toBoolean && dut.io.descriptor.ready.toBoolean) {
        descriptorAccepted = true
      }
      if (dut.io.dmaReadRequest.valid.toBoolean && dut.io.dmaReadRequest.ready.toBoolean) {
        val request = dut.io.dmaReadRequest.payload
        val address = request.address.toInt
        assert(memory.contains(address), s"unmapped DMA address $address")
        reads.enqueue((request.bank.toInt, address, cycle + 4))
      }
      if (dut.io.dmaReadResponse.valid.toBoolean && dut.io.dmaReadResponse.ready.toBoolean) {
        reads.dequeue()
      }
      if (dut.io.dmaWriteRequest.valid.toBoolean && dut.io.dmaWriteRequest.ready.toBoolean) {
        val request = dut.io.dmaWriteRequest.payload
        val address = request.address.toInt
        val data = request.data.toBigInt
        val row = ((data >> 1) & 255).toInt
        val column = ((data >> 9) & 255).toInt
        val bits = (data >> 17) & ((BigInt(1) << 32) - 1)
        val value = (if (bits.testBit(31)) bits - (BigInt(1) << 32) else bits).toLong
        assert(address == cBase + observed.size)
        assert(!observed.contains((row, column)))
        observed((row, column)) = value
        writes.enqueue((address, cycle + 11))
      }
      if (dut.io.dmaWriteResponse.valid.toBoolean && dut.io.dmaWriteResponse.ready.toBoolean) {
        writes.dequeue()
        lastWriteResponse = cycle
      }
      dut.clockDomain.waitSampling()
      sleep(1)
      cycle += 1
    }
    assert(descriptorAccepted)
    assert(cycle < 60000, "descriptor-to-write-response simulation timed out")
    assert(dut.io.error.toBoolean == expectError)
    assert(!dut.io.busy.toBoolean)
    assert(reads.isEmpty && writes.isEmpty)
    if (expectError) {
      assert(observed.isEmpty)
      assert(dut.io.writtenResults.toLong == 0)
    } else {
      assert(observed.toMap == nonzeroResults(a, b))
      assert(observed((6, 0)) == -14)
      assert(dut.io.writtenResults.toLong == observed.size)
      assert(lastWriteResponse >= 0 && cycle > lastWriteResponse)
    }
  }

  private def systemMatrices: (DenseMatrix, DenseMatrix) = {
    val masks = Vector(1, 2, 4, 8, 16, 32, 0x69, 0xa6)
    val a = DenseMatrix(Vector.tabulate(16, 16) { (row, coordinate) =>
      if (coordinate < 8) {
        if (coordinate == 0) (if (row == 6) 1L else row + 1L) else 0L
      } else if ((masks(row % 8) & (1 << (coordinate - 8))) != 0) 1L
      else 0L
    })
    val b = DenseMatrix(Vector.tabulate(16, 16) { (coordinate, column) =>
      if (coordinate < 8) {
        if (coordinate == 0) (if (column == 0) -5L else column + 1L) else 0L
      } else {
        val local = coordinate - 8
        val mask = if (column == 0) 0xee else if (column == 1) 0x37 else 1 << (column % 8)
        if ((mask & (1 << local)) == 0) 0L
        else if (column == 0 && local == 3) -2L
        else if (column == 0 && local == 5) -3L
        else if (column == 0 && local == 6) -4L
        else column + local + 1L
      }
    })
    (a, b)
  }

  test("unified system writes the complete signed 16 by 16 matrix after an unroutable batch") {
    simConfig.compile {
      val dut = new UnifiedEndToEndSystem(systemCfg)
      dut.sharedTile.trip.io.routeRetryCount.simPublic()
      dut
    }.doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (a, b) = systemMatrices
      runSystem(dut, a, b, expectError = false)
      assert(dut.sharedTile.trip.io.routeRetryCount.toLong > 0)
    }
  }

  test("irrecoverable PE failure reaches matrix and unified error status without false success") {
    simConfig.compile(new UnifiedEndToEndSystem(systemCfg,
      config => new RejectingPERow(config, permanent = true),
      hardwareProfile = EndToEndHardwareProfile.IpOnly)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      val (a, b) = systemMatrices
      runSystem(dut, a, b, expectError = true)
    }
  }

  test("failure after single A single B closes accumulation and permits a fresh tile") {
    simConfig.compile(new TrIpTileController(tileCfg,
      config => new RejectingPERow(config, permanent = true))).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      initialize(dut)
      val a = DenseMatrix(Vector.tabulate(8, 8) { (row, coordinate) =>
        if (coordinate == 0) row + 1L else 0L
      })
      val b = DenseMatrix(Vector.tabulate(8, 4) { (coordinate, column) =>
        if (coordinate == 0) column + 2L else 0L
      })
      load(dut, a, b)
      start(dut, TrapezoidDataflow.TrIp, 8, 4, first = true, last = false)
      assert(collect(dut, expectError = true, expectOutput = false).isEmpty)
      assert(!dut.io.awaitingKTile.toBoolean)
      assert(!dut.io.busy.toBoolean)
      assert(dut.io.routeRetryCount.toLong == 2)
      val freshA = DenseMatrix(Vector(Vector(2L) ++ Vector.fill(7)(0L)))
      val freshB = DenseMatrix(Vector(Vector(3L)) ++ Vector.fill(7)(Vector(0L)))
      load(dut, freshA, freshB)
      start(dut, TrapezoidDataflow.TrIp, 1, 1, first = true, last = true)
      assert(collect(dut) == Map((0, 0) -> 6L))
      assert(dut.io.routeRetryCount.toLong == 0)
    }
  }
}
