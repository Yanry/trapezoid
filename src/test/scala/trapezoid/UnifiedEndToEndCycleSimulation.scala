package trapezoid

import spinal.core.sim._
import trapezoid.model.{BitmaskFiber, DenseMatrix, MatrixReference}

import scala.collection.mutable
import scala.util.Random

/** Actual RTL descriptor-to-final-C-write-response cycle benchmark. */
object EndToEndCycleSimulation extends App {
  private val dimension = 16
  private val samplesPerCategory = 3
  private val readLatencyCycles = 12
  private val writeLatencyCycles = 6

  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 32,
    coordinateWidth = 8,
    fiberWidth = 8,
    maxFibers = 2,
    macLanes = 16,
    cacheLineWords = 4,
    outputLanes = 4,
    peRows = 4,
    localBanks = 4,
    localWordsPerBank = 4,
    localDepthPerBank = 32,
    responseFifoDepth = 2
  )
  private val tileCfg = TrIpTileConfig(cfg, maxColumns = dimension,
    maxRowsOverride = 8)
  private val matrixCfg = TrIpMatrixConfig(tileCfg,
    maxMatrixRows = dimension, maxMatrixColumns = dimension, maxKTiles = 2)
  private val cacheCfg = CacheConfig(
    wordWidth = 8,
    wordsPerLine = 16,
    addressWidth = 12,
    bankCount = 8,
    setsPerBank = 64,
    clientCount = cfg.peRows + 4,
    requestIdWidth = 8
  )
  private val gustCfg = GustavsonCacheConfig(
    GustavsonTileConfig(cfg, accumulatorEntries = dimension,
      fifoDepthPerSubrow = 8),
    cacheCfg,
    maxLinesPerRow = 8
  )
  private val systemCfg = EndToEndSystemConfig(
    matrixCfg, gustCfg, CacheClusterConfig(cacheCfg, clusterCount = 4),
    maxCResults = dimension * dimension)
  private val fiberCacheCfg = FiberCacheConfig(cfg, cacheCfg)

  private val highlySparseBasisPoints = 50
  private val mildlySparseBasisPoints = 2000
  private val denseBasisPoints = 10000

  private val ipABase = 0x100
  private val ipBBase = 0x340
  private val rowPointerBase = 0x600
  private val aEntryBase = 0x640
  private val bDescriptorBase = 0x800
  private val bRowBase = 0x900
  private val cBase = 0xc00

  private case class Category(name: String, aBasisPoints: Int,
                              bBasisPoints: Int, paperMode: String)
  private case class Workload(category: Category, sample: Int,
                              a: DenseMatrix, b: DenseMatrix)
  private case class PendingRead(bank: Int, address: Int, data: BigInt,
                                 readyEdge: Long)
  private case class PendingWrite(address: Int, readyEdge: Long)
  private case class Measurement(
      category: Category,
      sample: Int,
      mode: String,
      totalCycles: Long,
      firstWriteCycles: Long,
      readDmaActiveCycles: Long,
      writeDmaActiveCycles: Long,
      readRequests: Long,
      writeRequests: Long,
      cacheHits: Long,
      cacheMisses: Long,
      usefulMacs: Long,
      checksum: Long)

  private val categories = Vector(
    Category("HS x HS", highlySparseBasisPoints, highlySparseBasisPoints, "TrGt"),
    Category("HS x MS", highlySparseBasisPoints, mildlySparseBasisPoints, "TrGs"),
    Category("HS x D", highlySparseBasisPoints, denseBasisPoints, "TrGs"),
    Category("MS x MS", mildlySparseBasisPoints, mildlySparseBasisPoints, "TrIp"),
    Category("MS x D", mildlySparseBasisPoints, denseBasisPoints, "TrIp"),
    Category("D x D", denseBasisPoints, denseBasisPoints, "DenseIp")
  )
  private val modeOrder = Vector("DenseIp", "TrIp", "TrGt", "TrGs")
  private val categoryFilter = sys.props.get("trapezoid.bench.category")
  private val modeFilter = sys.props.get("trapezoid.bench.mode")
  private val selectedCategories = categories.filter(category =>
    categoryFilter.forall(_ == category.name))
  private val selectedModes = modeOrder.filter(mode => modeFilter.forall(_ == mode))
  require(selectedCategories.nonEmpty, s"unknown benchmark category $categoryFilter")
  require(selectedModes.nonEmpty, s"unknown benchmark mode $modeFilter")

  private def nonZerosFor(basisPoints: Int): Int =
    ((dimension.toLong * dimension * basisPoints + 5000) / 10000).toInt

  private def exactDensityMatrix(basisPoints: Int, seed: Int): DenseMatrix = {
    val cells = dimension * dimension
    val selected = (0 +: new Random(seed).shuffle((1 until cells).toVector))
      .take(nonZerosFor(basisPoints)).toSet
    DenseMatrix(Vector.tabulate(dimension, dimension) { (row, column) =>
      val index = row * dimension + column
      if (selected(index)) 1L + ((index + seed) & 3) else 0L
    })
  }

  private val workloads = categories.zipWithIndex.flatMap {
    case (category, categoryIndex) =>
      Vector.tabulate(samplesPerCategory) { sample =>
        Workload(category, sample,
          exactDensityMatrix(category.aBasisPoints,
            0x5100 + categoryIndex * 97 + sample),
          exactDensityMatrix(category.bBasisPoints,
            0x7900 + categoryIndex * 101 + sample))
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

  private def encodeDenseFiber(tag: Int, dense: Vector[Long]): BigInt = {
    val allValid = (BigInt(1) << cfg.fiberWidth) - 1
    encodeFiber(BitmaskFiber(tag, cfg.fiberWidth, allValid, dense))
  }

  private def recordLines(record: BigInt): Vector[BigInt] = {
    val lineMask = (BigInt(1) << cacheCfg.lineWidth) - 1
    Vector.tabulate(fiberCacheCfg.linesPerFiber) { line =>
      (record >> (line * cacheCfg.lineWidth)) & lineMask
    }
  }

  private def ipMemory(workload: Workload, modeName: String): Map[Int, BigInt] = {
    val denseInput = modeName == "DenseIp"
    val kTiles = dimension / cfg.fiberWidth
    val aLines = for {
      row <- 0 until dimension
      tile <- 0 until kTiles
      dense = workload.a.values(row).slice(tile * cfg.fiberWidth,
        (tile + 1) * cfg.fiberWidth)
      fiber = if (denseInput) encodeDenseFiber(row, dense)
        else encodeFiber(MatrixReference.encodeFiber(row, dense))
      (data, line) <- recordLines(fiber).zipWithIndex
      ordinal = row * matrixCfg.maxKTiles + tile
    } yield ipABase + ordinal * fiberCacheCfg.linesPerFiber + line -> data
    val bLines = for {
      tile <- 0 until kTiles
      column <- 0 until dimension
      dense = Vector.tabulate(cfg.fiberWidth) { offset =>
        workload.b(tile * cfg.fiberWidth + offset, column)
      }
      fiber = if (denseInput) encodeDenseFiber(column, dense)
        else encodeFiber(MatrixReference.encodeFiber(column, dense))
      (data, line) <- recordLines(fiber).zipWithIndex
      ordinal = tile * matrixCfg.maxMatrixColumns + column
    } yield ipBBase + ordinal * fiberCacheCfg.linesPerFiber + line -> data
    (aLines ++ bLines).toMap
  }

  private def encodeAEntry(k: Int, value: Long): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    BigInt(1) | ((BigInt(value) & valueMask) << 1) |
      (BigInt(k) << (1 + cfg.dataWidth))
  }

  private def encodeBLine(entries: Seq[(Int, Long)]): BigInt = {
    val valueMask = (BigInt(1) << cfg.dataWidth) - 1
    entries.zipWithIndex.foldLeft(BigInt(0)) {
      case (line, ((column, value), lane)) =>
        val offset = lane * gustCfg.elementWidth
        line | (BigInt(1) << offset) |
          ((BigInt(value) & valueMask) << (offset + 1)) |
          (BigInt(column) << (offset + 1 + cfg.dataWidth))
    }
  }

  private def csrMemory(workload: Workload): Map[Int, BigInt] = {
    val memory = mutable.Map.empty[Int, BigInt]
    var entryOrdinal = 0
    for (row <- 0 until dimension) {
      memory(rowPointerBase + row) = entryOrdinal
      for (k <- 0 until dimension if workload.a(row, k) != 0) {
        memory(aEntryBase + entryOrdinal) = encodeAEntry(k, workload.a(row, k))
        entryOrdinal += 1
      }
    }
    memory(rowPointerBase + dimension) = entryOrdinal

    var nextBAddress = bRowBase
    for (k <- 0 until dimension) {
      val entries = (0 until dimension).collect {
        case column if workload.b(k, column) != 0 =>
          column -> workload.b(k, column)
      }
      val groups = if (entries.isEmpty) Vector(Vector.empty[(Int, Long)])
        else entries.grouped(gustCfg.elementsPerLine).map(_.toVector).toVector
      memory(bDescriptorBase + k) = BigInt(nextBAddress) |
        (BigInt(groups.size) << cacheCfg.addressWidth)
      groups.foreach { group =>
        memory(nextBAddress) = encodeBLine(group)
        nextBAddress += 1
      }
    }
    memory.toMap
  }

  private def usefulMacs(workload: Workload): Long = (for {
    row <- 0 until dimension
    k <- 0 until dimension
    if workload.a(row, k) != 0
    column <- 0 until dimension
    if workload.b(k, column) != 0
  } yield 1L).sum

  private def signed(value: BigInt, width: Int): Long = {
    val sign = BigInt(1) << (width - 1)
    val modulus = BigInt(1) << width
    (if ((value & sign) != 0) value - modulus else value).toLong
  }

  private def decodeResult(data: BigInt): ((Int, Int), Long) = {
    require((data & 1) == 1)
    val coordinateMask = (BigInt(1) << cfg.coordinateWidth) - 1
    val row = ((data >> 1) & coordinateMask).toInt
    val column = ((data >> (1 + cfg.coordinateWidth)) & coordinateMask).toInt
    val valueBits = (data >> (1 + 2 * cfg.coordinateWidth)) &
      ((BigInt(1) << cfg.accumulatorWidth) - 1)
    (row, column) -> signed(valueBits, cfg.accumulatorWidth)
  }

  private def checksum(values: collection.Map[(Int, Int), Long]): Long =
    values.toSeq.sortBy(_._1).foldLeft(0L) {
      case (sum, ((row, column), value)) =>
        sum + value * (row * dimension + column + 1L)
    }

  private def expectedEntries(workload: Workload): Map[(Int, Int), Long] = {
    val expected = MatrixReference.multiply(workload.a, workload.b)
    (for {
      row <- 0 until dimension
      column <- 0 until dimension
      value = expected(row, column)
      if value != 0
    } yield (row, column) -> value).toMap
  }

  private val simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  private val measurements = mutable.ArrayBuffer.empty[Measurement]
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

    def run(workload: Workload, modeName: String): Measurement = {
      dut.io.clear #= true
      dut.io.descriptor.valid #= false
      dut.io.dmaReadResponse.valid #= false
      dut.io.dmaWriteResponse.valid #= false
      dut.clockDomain.waitSampling(2)
      dut.io.clear #= false

      val mode = modeName match {
        case "DenseIp" => TrapezoidDataflow.DenseIp
        case "TrIp" => TrapezoidDataflow.TrIp
        case "TrGt" => TrapezoidDataflow.TrGt
        case "TrGs" => TrapezoidDataflow.TrGs
      }
      val memory = ipMemory(workload, modeName) ++ csrMemory(workload)
      val expected = expectedEntries(workload)
      val observed = mutable.Map.empty[(Int, Int), Long]
      val pendingReads = mutable.Queue.empty[PendingRead]
      val pendingWrites = mutable.Queue.empty[PendingWrite]
      var activeRead: Option[PendingRead] = None
      var activeWrite: Option[PendingWrite] = None
      var edge = 0L
      var startEdge = -1L
      var firstWriteEdge = -1L
      var finalEdge = -1L
      var readRequests = 0L
      var writeRequests = 0L
      var readActiveCycles = 0L
      var writeActiveCycles = 0L
      val timeoutEdges = 1000000L

      dut.io.descriptor.payload.mode #= mode
      dut.io.descriptor.payload.rowCount #= dimension
      dut.io.descriptor.payload.columnCount #= dimension
      dut.io.descriptor.payload.kTileCount #= dimension / cfg.fiberWidth
      dut.io.descriptor.payload.ipABaseAddress #= ipABase
      dut.io.descriptor.payload.ipBBaseAddress #= ipBBase
      dut.io.descriptor.payload.csrRowPointerBase #= rowPointerBase
      dut.io.descriptor.payload.csrAEntryBase #= aEntryBase
      dut.io.descriptor.payload.csrBRowDescriptorBase #= bDescriptorBase
      dut.io.descriptor.payload.cBaseAddress #= cBase
      dut.io.descriptor.valid #= true

      while (finalEdge < 0 && edge < timeoutEdges) {
        if (activeRead.isEmpty &&
            pendingReads.headOption.exists(_.readyEdge <= edge + 1)) {
          activeRead = Some(pendingReads.dequeue())
        }
        if (activeWrite.isEmpty &&
            pendingWrites.headOption.exists(_.readyEdge <= edge + 1)) {
          activeWrite = Some(pendingWrites.dequeue())
        }
        if (activeRead.nonEmpty || pendingReads.nonEmpty) readActiveCycles += 1
        if (activeWrite.nonEmpty || pendingWrites.nonEmpty) writeActiveCycles += 1

        dut.io.dmaReadRequest.ready #= true
        dut.io.dmaReadResponse.valid #= activeRead.nonEmpty
        activeRead.foreach { response =>
          dut.io.dmaReadResponse.payload.bank #= response.bank
          dut.io.dmaReadResponse.payload.address #= response.address
          dut.io.dmaReadResponse.payload.data #= response.data
          dut.io.dmaReadResponse.payload.error #= false
        }
        dut.io.dmaWriteRequest.ready #= true
        dut.io.dmaWriteResponse.valid #= activeWrite.nonEmpty
        activeWrite.foreach { response =>
          dut.io.dmaWriteResponse.payload.address #= response.address
          dut.io.dmaWriteResponse.payload.error #= false
        }

        sleep(1)
        val descriptorFire = dut.io.descriptor.valid.toBoolean &&
          dut.io.descriptor.ready.toBoolean
        val readRequestFire = dut.io.dmaReadRequest.valid.toBoolean &&
          dut.io.dmaReadRequest.ready.toBoolean
        val readResponseFire = activeRead.nonEmpty &&
          dut.io.dmaReadResponse.ready.toBoolean
        val writeRequestFire = dut.io.dmaWriteRequest.valid.toBoolean &&
          dut.io.dmaWriteRequest.ready.toBoolean
        val writeResponseFire = activeWrite.nonEmpty &&
          dut.io.dmaWriteResponse.ready.toBoolean
        val readBank = if (readRequestFire)
          dut.io.dmaReadRequest.payload.bank.toInt else 0
        val readAddress = if (readRequestFire)
          dut.io.dmaReadRequest.payload.address.toInt else 0
        val writeAddress = if (writeRequestFire)
          dut.io.dmaWriteRequest.payload.address.toInt else 0
        val writeData = if (writeRequestFire)
          dut.io.dmaWriteRequest.payload.data.toBigInt else BigInt(0)

        dut.clockDomain.waitSampling()
        edge += 1

        if (descriptorFire) {
          startEdge = edge
          dut.io.descriptor.valid #= false
        }
        if (readRequestFire) {
          require(memory.contains(readAddress),
            s"$modeName requested unmapped line $readAddress")
          require(readBank == (readAddress & (cacheCfg.bankCount - 1)))
          pendingReads.enqueue(PendingRead(readBank, readAddress,
            memory(readAddress), edge + readLatencyCycles))
          readRequests += 1
        }
        if (readResponseFire) activeRead = None
        if (writeRequestFire) {
          if (firstWriteEdge < 0) firstWriteEdge = edge
          observed += decodeResult(writeData)
          pendingWrites.enqueue(PendingWrite(writeAddress,
            edge + writeLatencyCycles))
          writeRequests += 1
        }
        if (writeResponseFire) activeWrite = None
        if (dut.io.done.toBoolean) finalEdge = edge
      }

      require(startEdge >= 0, s"$modeName descriptor was not accepted")
      require(finalEdge >= 0,
        s"$modeName ${workload.category.name} timed out: " +
          s"trgtPhase=${dut.io.trgtPhase.toInt}, " +
          s"schedulerBusy=${dut.io.trgtSchedulerBusy.toBoolean}, " +
          s"fifo=${dut.io.trgtFifoOccupancy.toInt}")
      require(!dut.io.error.toBoolean,
        s"$modeName ${workload.category.name} reported an RTL error")
      require(observed.toMap == expected,
        s"$modeName ${workload.category.name} result mismatch")
      require(writeRequests == expected.size)

      Measurement(workload.category, workload.sample, modeName,
        finalEdge - startEdge + 1,
        if (firstWriteEdge < 0) 0 else firstWriteEdge - startEdge + 1,
        readActiveCycles, writeActiveCycles, readRequests, writeRequests,
        dut.io.cacheHitCount.toLong, dut.io.cacheMissCount.toLong,
        usefulMacs(workload), checksum(observed))
    }

    for (workload <- workloads if selectedCategories.contains(workload.category);
         mode <- selectedModes) {
      measurements += run(workload, mode)
    }
  }

  for (workload <- workloads if selectedCategories.contains(workload.category)) {
    val selected = measurements.filter(measurement =>
      measurement.category == workload.category &&
        measurement.sample == workload.sample)
    require(selected.size == selectedModes.size)
    require(selected.map(_.checksum).distinct.size == 1,
      s"all modes must agree for ${workload.category.name} sample ${workload.sample}")
  }

  private def densityText(basisPoints: Int): String =
    f"${100.0 * nonZerosFor(basisPoints) / (dimension * dimension)}%.2f%%"

  private def average(category: Category, mode: String)
                     (select: Measurement => Long): Double = {
    val selected = measurements.filter(measurement =>
      measurement.category == category && measurement.mode == mode)
    require(selected.size == samplesPerCategory)
    selected.map(select).sum.toDouble / selected.size
  }

  println("\nTrapezoid RTL descriptor-to-C-write-response end-to-end cycles")
  println(s"Matrix: ${dimension}x${dimension}; array: ${cfg.peRows} PE rows x " +
    s"${cfg.macLanes} MACs; cache: ${systemCfg.clusters.clusterCount} clusters x " +
    s"${systemCfg.clusters.banksPerCluster} banks")
  println(s"Cold cache; read DMA latency: $readLatencyCycles cycles; " +
    s"write response latency: $writeLatencyCycles cycles")
  println("Boundary: unified descriptor acceptance through the final C DMA write response.")
  println(s"| Category | A density | B density | Paper mode | " +
    s"${selectedModes.mkString(" | ")} |")
  println(s"| :--- | ---: | ---: | :--- | " +
    s"${selectedModes.map(_ => "---:").mkString(" | ")} |")
  selectedCategories.foreach { category =>
    val cycles = selectedModes.map(mode =>
      f"${average(category, mode)(_.totalCycles)}%.1f")
    println(s"| ${category.name} | ${densityText(category.aBasisPoints)} | " +
      s"${densityText(category.bBasisPoints)} | ${category.paperMode} | " +
      s"${cycles.mkString(" | ")} |")
  }

  println("\nDetailed RTL measurements")
  println("| Category | Mode | Total | First C write | Read DMA active | " +
    "Write DMA active | Reads | Writes | Hits | Misses | Useful MACs |")
  println("| :--- | :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
  for (category <- selectedCategories; mode <- selectedModes) {
    println(f"| ${category.name}%s | $mode%s | " +
      f"${average(category, mode)(_.totalCycles)}%.1f | " +
      f"${average(category, mode)(_.firstWriteCycles)}%.1f | " +
      f"${average(category, mode)(_.readDmaActiveCycles)}%.1f | " +
      f"${average(category, mode)(_.writeDmaActiveCycles)}%.1f | " +
      f"${average(category, mode)(_.readRequests)}%.1f | " +
      f"${average(category, mode)(_.writeRequests)}%.1f | " +
      f"${average(category, mode)(_.cacheHits)}%.1f | " +
      f"${average(category, mode)(_.cacheMisses)}%.1f | " +
      f"${average(category, mode)(_.usefulMacs)}%.1f |")
  }
  println("\nEvery total is counted from Verilator RTL clock edges. CSR pointer walking, " +
    "clustered-cache misses/refills, compute, result backpressure, and final C write " +
    "responses are inside the measured boundary.")
}
