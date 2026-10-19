package trapezoid

import trapezoid.model.DenseMatrix

import scala.collection.mutable
import scala.util.Random

/** Paper-configuration end-to-end cycle-level system model.
  *
  * The boundary starts with A and B resident in HBM and ends after C has been
  * written back to HBM. It models the paper's array, cache organization, HBM
  * bandwidth, sparse storage formats, dataflow issue rates, and PE pipeline.
  * The paper does not publish an HBM command latency, so only transfer cycles
  * are charged; both a streamed total and a serialized phase upper bound are
  * reported. Address-level cache conflicts, NoC arbitration, and DMA command
  * latency remain outside this reproducible approximation.
  */
object PaperEndToEndCycleSimulation extends App {
  private val cfg = TrapezoidConfig.paper
  private val dimension = 256
  private val samplesPerCategory = 3

  private val clockGHz = 1
  private val cacheClusters = 4
  private val banksPerCluster = 32
  private val cacheBanks = cacheClusters * banksPerCluster
  private val cacheBytes = 16L * 1024 * 1024
  private val localBufferBytes = 1L * 1024 * 1024
  private val cacheLineBytes = 64L
  private val hbmBytesPerCycle = 2000L
  private val fp32Bytes = 4L
  private val coordinateBytes = 4L
  private val pointerBytes = 4L
  private val pePipelineStages = 5L

  require(cfg.peRows == 128)
  require(cfg.macLanes == 128)
  require(cfg.cacheLineWords == 16)
  require(cacheClusters == 4 && banksPerCluster == 32)
  require(dimension % cfg.fiberWidth == 0)

  private case class Category(name: String, aBasisPoints: Int,
                              bBasisPoints: Int, mode: String)
  private case class MatrixStats(nonZeros: Long)
  private case class ProductStats(effectualMacs: Long, rowWork: Vector[Long],
                                  outputNonZeros: Long, checksum: Long)
  private case class Measurement(
      category: Category,
      sample: Int,
      effectualMacs: Long,
      inputBytes: Long,
      outputBytes: Long,
      hbmReadCycles: Long,
      cacheReadCycles: Long,
      computeCycles: Long,
      outputCycles: Long,
      streamedCycles: Long,
      serializedCycles: Long,
      checksum: Long)

  private val highlySparseBasisPoints = 50
  private val mildlySparseBasisPoints = 2000
  private val denseBasisPoints = 10000

  private val categories = Vector(
    Category("HS x HS", highlySparseBasisPoints, highlySparseBasisPoints, "TrGt"),
    Category("HS x MS", highlySparseBasisPoints, mildlySparseBasisPoints, "TrGs"),
    Category("HS x D", highlySparseBasisPoints, denseBasisPoints, "TrGs"),
    Category("MS x MS", mildlySparseBasisPoints, mildlySparseBasisPoints, "TrIp"),
    Category("MS x D", mildlySparseBasisPoints, denseBasisPoints, "TrIp"),
    Category("D x D", denseBasisPoints, denseBasisPoints, "DenseIp")
  )

  private def ceilDiv(value: Long, divisor: Long): Long =
    if (value == 0) 0 else (value + divisor - 1) / divisor

  private def lineRounded(bytes: Long): Long =
    ceilDiv(bytes, cacheLineBytes) * cacheLineBytes

  private def nonZerosFor(basisPoints: Int): Int =
    ((dimension.toLong * dimension * basisPoints + 5000) / 10000).toInt

  private def exactDensityMatrix(basisPoints: Int, seed: Int): DenseMatrix = {
    val cells = dimension * dimension
    val selected = new Random(seed).shuffle((0 until cells).toVector)
      .take(nonZerosFor(basisPoints)).toSet
    DenseMatrix(Vector.tabulate(dimension, dimension) { (row, column) =>
      val index = row * dimension + column
      if (selected(index)) 1L + ((index + seed) & 3) else 0L
    })
  }

  private def matrixStats(matrix: DenseMatrix): MatrixStats = {
    MatrixStats(matrix.values.iterator.flatMap(_.iterator).count(_ != 0).toLong)
  }

  private def multiplyStats(a: DenseMatrix, b: DenseMatrix): ProductStats = {
    val output = Array.fill[Long](dimension * dimension)(0L)
    val rowWork = Array.fill[Long](dimension)(0L)
    var effectualMacs = 0L
    for {
      row <- 0 until dimension
      k <- 0 until dimension
      aValue = a(row, k)
      if aValue != 0
      column <- 0 until dimension
      bValue = b(k, column)
      if bValue != 0
    } {
      output(row * dimension + column) += aValue * bValue
      rowWork(row) += 1
      effectualMacs += 1
    }
    var outputNonZeros = 0L
    var checksum = 0L
    for (index <- output.indices if output(index) != 0) {
      outputNonZeros += 1
      checksum += output(index) * (index + 1L)
    }
    ProductStats(effectualMacs, rowWork.toVector, outputNonZeros, checksum)
  }

  /** CSR/CSC for HS, bitmask fibers for MS, and uncompressed FP32 for D. */
  private def encodedBytes(nonZeros: Long, basisPoints: Int): Long = {
    val cells = dimension.toLong * dimension
    if (basisPoints == denseBasisPoints) {
      cells * fp32Bytes
    } else if (basisPoints < 100) {
      nonZeros * (fp32Bytes + coordinateBytes) +
        (dimension + 1L) * pointerBytes
    } else {
      nonZeros * fp32Bytes +
        dimension.toLong * ceilDiv(dimension, 8)
    }
  }

  private def outputBytes(nonZeros: Long): Long = {
    val cells = dimension.toLong * dimension
    val basisPoints = ((nonZeros * 10000) / cells).toInt
    if (nonZeros == cells) encodedBytes(nonZeros, denseBasisPoints)
    else if (basisPoints < 100) encodedBytes(nonZeros, highlySparseBasisPoints)
    else encodedBytes(nonZeros, mildlySparseBasisPoints)
  }

  private def kTileMask(values: IndexedSeq[Long], tile: Int): BigInt = {
    val start = tile * cfg.fiberWidth
    var mask = BigInt(0)
    for (offset <- 0 until cfg.fiberWidth if values(start + offset) != 0) {
      mask = mask.setBit(offset)
    }
    mask
  }

  private def denseIpCycles: Long = {
    val rowWaves = ceilDiv(dimension, cfg.peRows)
    val kTiles = ceilDiv(dimension, cfg.macLanes)
    rowWaves * dimension * kTiles
  }

  private def trIpCycles(a: DenseMatrix, b: DenseMatrix): Long = {
    val kTiles = dimension / cfg.fiberWidth
    val rowsPerWave = cfg.peRows * cfg.maxFibers
    var cycles = 0L
    for (tile <- 0 until kTiles;
         rowBase <- 0 until dimension by rowsPerWave) {
      val aMasks = Vector.tabulate(dimension)(row =>
        kTileMask(a.values(row), tile))
      val bMasks = Vector.tabulate(dimension) { column =>
        kTileMask(Vector.tabulate(dimension)(k => b(k, column)), tile)
      }
      val rowsByPe = Vector.tabulate(cfg.peRows) { pe =>
        Vector.tabulate(cfg.maxFibers)(fiber =>
          rowBase + pe * cfg.maxFibers + fiber)
      }

      def workFits(aOffset: Int, groupSize: Int,
                   bBase: Int, columnCount: Int): Boolean =
        rowsByPe.forall { rows =>
          var work = 0
          for {
            fiber <- aOffset until math.min(cfg.maxFibers, aOffset + groupSize)
            row = rows(fiber)
            if row < dimension
            column <- bBase until bBase + columnCount
          } work += (aMasks(row) & bMasks(column)).bitCount
          work <= cfg.macLanes
        }

      var aOffset = 0
      var groupSize = cfg.maxFibers
      while (aOffset < cfg.maxFibers && rowsByPe.exists(rows =>
          rows.slice(aOffset, aOffset + groupSize).exists(_ < dimension))) {
        while (groupSize > 1 && (0 until dimension).exists(column =>
            !workFits(aOffset, groupSize, column, 1))) {
          groupSize -= 1
        }
        var bBase = 0
        while (bBase < dimension) {
          val candidate = math.min(cfg.maxFibers, dimension - bBase)
          val selected = (candidate to 1 by -1)
            .find(columns => workFits(aOffset, groupSize, bBase, columns))
            .getOrElse(throw new IllegalStateException(
              "one TrIP fiber pair exceeds the paper PE-row capacity"))
          bBase += selected
          cycles += 1
        }
        aOffset += groupSize
        groupSize = cfg.maxFibers - aOffset
      }
    }
    cycles
  }

  private def computeCycles(mode: String, product: ProductStats,
                            a: DenseMatrix, b: DenseMatrix): Long = mode match {
    case "DenseIp" => denseIpCycles
    case "TrIp" => trIpCycles(a, b)
    case "TrGt" =>
      product.rowWork.grouped(cfg.peRows * cfg.localBanks)
        .map(group => group.max).sum
    case "TrGs" =>
      product.rowWork.grouped(cfg.peRows)
        .map(group => group.map(work => ceilDiv(work, cfg.cacheLineWords)).max).sum
  }

  private val measurements = mutable.ArrayBuffer.empty[Measurement]
  for ((category, categoryIndex) <- categories.zipWithIndex;
       sample <- 0 until samplesPerCategory) {
    val a = exactDensityMatrix(category.aBasisPoints,
      0x5100 + categoryIndex * 97 + sample)
    val b = exactDensityMatrix(category.bBasisPoints,
      0x7900 + categoryIndex * 101 + sample)
    val aStats = matrixStats(a)
    val bStats = matrixStats(b)
    val product = multiplyStats(a, b)
    require(aStats.nonZeros == nonZerosFor(category.aBasisPoints))
    require(bStats.nonZeros == nonZerosFor(category.bBasisPoints))
    require(product.effectualMacs == product.rowWork.sum)

    val aBytes = lineRounded(encodedBytes(aStats.nonZeros, category.aBasisPoints))
    val bBytes = lineRounded(encodedBytes(bStats.nonZeros, category.bBasisPoints))
    val cBytes = lineRounded(outputBytes(product.outputNonZeros))
    val inputBytes = aBytes + bBytes
    require(inputBytes <= cacheBytes,
      s"${category.name} inputs exceed the paper's 16 MB global cache")

    val inputLines = inputBytes / cacheLineBytes
    val outputLines = cBytes / cacheLineBytes
    val hbmReadCycles = ceilDiv(inputBytes, hbmBytesPerCycle)
    val cacheReadCycles = ceilDiv(inputLines, cacheBanks)
    val inputCycles = math.max(hbmReadCycles, cacheReadCycles)
    val compute = computeCycles(category.mode, product, a, b)
    val hbmWriteCycles = ceilDiv(cBytes, hbmBytesPerCycle)
    val cacheWriteCycles = ceilDiv(outputLines, cacheBanks)
    val outputCycles = math.max(hbmWriteCycles, cacheWriteCycles)
    val pipelineCycles = pePipelineStages - 1
    val streamedCycles = math.max(inputCycles, compute) + pipelineCycles + outputCycles
    val serializedCycles = inputCycles + compute + pipelineCycles + outputCycles

    measurements += Measurement(category, sample, product.effectualMacs,
      inputBytes, cBytes, hbmReadCycles, cacheReadCycles, compute,
      outputCycles, streamedCycles, serializedCycles, product.checksum)
  }

  private def average(category: Category)(select: Measurement => Long): Double = {
    val selected = measurements.filter(_.category == category)
    selected.map(select).sum.toDouble / selected.size
  }

  private def densityText(basisPoints: Int): String =
    f"${basisPoints / 100.0}%.2f%%"

  println("\nTrapezoid paper-configuration end-to-end cycle simulation")
  println(s"Matrix: ${dimension}x${dimension} * ${dimension}x${dimension}; " +
    s"clock: ${clockGHz}GHz; array: ${cfg.peRows}x${cfg.macLanes} FP32 MACs")
  println(s"Memory: ${cacheBytes / (1024 * 1024)}MB cache " +
    s"($cacheClusters clusters x $banksPerCluster banks, ${cacheLineBytes}B lines), " +
    s"${localBufferBytes / (1024 * 1024)}MB local buffers, 2TB/s HBM")
  println(s"Density points: HS=${densityText(highlySparseBasisPoints)}, " +
    s"MS=${densityText(mildlySparseBasisPoints)}, D=${densityText(denseBasisPoints)}; " +
    s"$samplesPerCategory deterministic samples/category")
  println("Boundary: descriptor acceptance with operands in HBM through C writeback to HBM.")
  println("| Category | A density | B density | Mode | Useful MACs | Input KiB | " +
    "Output KiB | HBM read | Cache read | Compute | Output | E2E streamed | " +
    "E2E serialized |")
  println("| :--- | ---: | ---: | :--- | ---: | ---: | ---: | ---: | ---: | " +
    "---: | ---: | ---: | ---: |")
  categories.foreach { category =>
    println(f"| ${category.name}%s | ${densityText(category.aBasisPoints)}%s | " +
      f"${densityText(category.bBasisPoints)}%s | ${category.mode}%s | " +
      f"${average(category)(_.effectualMacs)}%.1f | " +
      f"${average(category)(_.inputBytes) / 1024.0}%.2f | " +
      f"${average(category)(_.outputBytes) / 1024.0}%.2f | " +
      f"${average(category)(_.hbmReadCycles)}%.1f | " +
      f"${average(category)(_.cacheReadCycles)}%.1f | " +
      f"${average(category)(_.computeCycles)}%.1f | " +
      f"${average(category)(_.outputCycles)}%.1f | " +
      f"${average(category)(_.streamedCycles)}%.1f | " +
      f"${average(category)(_.serializedCycles)}%.1f |")
  }
  val combinedChecksum = measurements.map(_.checksum).foldLeft(0L)(_ ^ _)
  println(s"Combined result checksum: $combinedChecksum")
  println("E2E streamed overlaps operand transfer with execution; E2E serialized " +
    "is the no-overlap upper bound.")
  println("The paper does not publish fixed HBM command latency, so this model charges " +
    "bandwidth service cycles but no additional first-line latency.")
  println("Address-level cache conflicts, NoC arbitration, DMA setup, and backpressure " +
    "are not yet modeled.")
}
