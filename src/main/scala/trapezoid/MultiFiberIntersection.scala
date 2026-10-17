package trapezoid

import spinal.core._
import spinal.lib._

/** Paper Fig. 11's multi-fiber intersection unit (MFIU).
  *
  * All A/B masks are intersected pairwise.  Effectual computations are
  * compacted into monotonically numbered MAC routes.  The value indices are
  * ranks within each compressed fiber, which is the logical result of the
  * shift unit in Fig. 12.
  */
class MultiFiberIntersection(cfg: TrapezoidConfig) extends Component {
  private val pairCount = cfg.maxFibers * cfg.maxFibers

  private def parallelPrefix(values: Seq[UInt]): Seq[UInt] = {
    var level = values.map(_.resize(cfg.matchCountWidth))
    var distance = 1
    while (distance < values.length) {
      val previous = level
      level = previous.indices.map { index =>
        if (index >= distance) {
          (previous(index) + previous(index - distance)).resize(cfg.matchCountWidth)
        } else {
          previous(index)
        }
      }
      distance *= 2
    }
    level
  }

  val io = new Bundle {
    val aValid = in Vec(Bool(), cfg.maxFibers)
    val aMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val bValid = in Vec(Bool(), cfg.maxFibers)
    val bMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val routes = out Vec(IntersectionRoute(cfg), cfg.macLanes)
    val count = out UInt(cfg.matchCountWidth bits)
    val overflow = out Bool()
  }

  // Intersections are first formed independently for each A/B fiber pair.
  // This mirrors the paper's parallel MFIU organization and avoids a single
  // maxFibers^2 * fiberWidth-long prefix dependency.
  val pairMasks = Vec(Bits(cfg.fiberWidth bits), pairCount)
  val pairMatches = Vec(UInt(cfg.matchCountWidth bits), pairCount)
  val pairLocalRanks = Vec(
    Vec(UInt(cfg.matchCountWidth bits), cfg.fiberWidth), pairCount)
  val pairOffsets = Vec(UInt(cfg.matchCountWidth bits), pairCount + 1)
  for (a <- 0 until cfg.maxFibers; b <- 0 until cfg.maxFibers) {
    val pair = a * cfg.maxFibers + b
    pairMasks(pair) := Mux(io.aValid(a) && io.bValid(b),
      io.aMask(a) & io.bMask(b), B(0, cfg.fiberWidth bits))
    val localPrefix = parallelPrefix((0 until cfg.fiberWidth).map { coordinate =>
      pairMasks(pair)(coordinate).asUInt.resize(cfg.matchCountWidth)
    })
    pairMatches(pair) := localPrefix.last
    pairLocalRanks(pair)(0) := 0
    for (coordinate <- 1 until cfg.fiberWidth) {
      pairLocalRanks(pair)(coordinate) := localPrefix(coordinate - 1)
    }
  }

  // The paper's cumulative match indices are produced by a prefix tree.
  // Keep the pair-local popcounts in parallel, then scan only the 4 x 4 pair
  // counts; this bounds the offset path to log2(maxFibers^2) adder levels.
  val inclusivePairCounts = parallelPrefix(pairMatches.toSeq)
  pairOffsets(0) := 0
  for (pair <- 0 until pairCount) {
    pairOffsets(pair + 1) := inclusivePairCounts(pair)
  }

  io.count := pairOffsets.last
  io.overflow := pairOffsets.last > cfg.macLanes

  // The shift unit's compressed-value ranks depend only on one source fiber.
  // Build each rank network once and share it across all destination lanes.
  val aValueRanks = Vec(
    Vec(UInt(cfg.fiberValueIndexWidth bits), cfg.fiberWidth), cfg.maxFibers)
  val bValueRanks = Vec(
    Vec(UInt(cfg.fiberValueIndexWidth bits), cfg.fiberWidth), cfg.maxFibers)
  for (fiber <- 0 until cfg.maxFibers) {
    val aPrefix = parallelPrefix((0 until cfg.fiberWidth).map { coordinate =>
      io.aMask(fiber)(coordinate).asUInt.resize(cfg.matchCountWidth)
    })
    val bPrefix = parallelPrefix((0 until cfg.fiberWidth).map { coordinate =>
      io.bMask(fiber)(coordinate).asUInt.resize(cfg.matchCountWidth)
    })
    aValueRanks(fiber)(0) := 0
    bValueRanks(fiber)(0) := 0
    for (coordinate <- 1 until cfg.fiberWidth) {
      aValueRanks(fiber)(coordinate) := aPrefix(coordinate - 1).resized
      bValueRanks(fiber)(coordinate) := bPrefix(coordinate - 1).resized
    }
  }

  for (lane <- 0 until cfg.macLanes) {
    io.routes(lane).valid := False
    io.routes(lane).aFiber := 0
    io.routes(lane).bFiber := 0
    io.routes(lane).coordinate := 0
    io.routes(lane).aValueIndex := 0
    io.routes(lane).bValueIndex := 0

    for (a <- 0 until cfg.maxFibers; b <- 0 until cfg.maxFibers; k <- 0 until cfg.fiberWidth) {
      val pair = a * cfg.maxFibers + b
      val globalRank =
        (pairOffsets(pair) + pairLocalRanks(pair)(k)).resize(cfg.matchCountWidth)
      when(pairMasks(pair)(k) && globalRank === lane) {
        io.routes(lane).valid := True
        io.routes(lane).aFiber := a
        io.routes(lane).bFiber := b
        io.routes(lane).coordinate := k
        io.routes(lane).aValueIndex := aValueRanks(a)(k)
        io.routes(lane).bValueIndex := bValueRanks(b)(k)
      }
    }
  }
}

/** Chooses the largest prefix (one through four) of B columns that fits in a
  * PE row.  A system scheduler takes the minimum choice over all PE rows.
  */
class DynamicColumnPacker(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val aValid = in Vec(Bool(), cfg.maxFibers)
    val aMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val bValid = in Vec(Bool(), cfg.maxFibers)
    val bMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val selectedColumns = out UInt(log2Up(cfg.maxFibers + 1) bits)
    val selectedMatches = out UInt(cfg.matchCountWidth bits)
  }

  val cumulative = Vec(UInt(cfg.matchCountWidth bits), cfg.maxFibers + 1)
  cumulative(0) := 0
  for (b <- 0 until cfg.maxFibers) {
    var columnMatches = U(0, cfg.matchCountWidth bits)
    for (a <- 0 until cfg.maxFibers) {
      val matches = CountOne(io.aMask(a) & io.bMask(b)).resize(cfg.matchCountWidth)
      columnMatches = (columnMatches + Mux(io.aValid(a) && io.bValid(b), matches,
        U(0, cfg.matchCountWidth bits))).resized
    }
    cumulative(b + 1) := cumulative(b) + columnMatches
  }

  io.selectedColumns := 0
  io.selectedMatches := 0
  for (columns <- 1 to cfg.maxFibers) {
    when(cumulative(columns) <= cfg.macLanes) {
      io.selectedColumns := columns
      io.selectedMatches := cumulative(columns)
    }
  }
}
