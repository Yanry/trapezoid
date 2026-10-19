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
  private val routeValidOffset = 0
  private val routeAFiberOffset = routeValidOffset + 1
  private val routeBFiberOffset = routeAFiberOffset + cfg.fiberIndexWidth
  private val routeCoordinateOffset = routeBFiberOffset + cfg.fiberIndexWidth
  private val routeAValueOffset = routeCoordinateOffset + cfg.fiberValueIndexWidth
  private val routeBValueOffset = routeAValueOffset + cfg.fiberValueIndexWidth
  private val routeWidth = routeBValueOffset + cfg.fiberValueIndexWidth

  private case class CompactedRoutes(entries: Vector[Bits], count: UInt)

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

  /** Stable hierarchical compaction of pair-major route candidates.
    *
    * Each node concatenates two already compacted streams by shifting the
    * right stream by the number of valid entries on the left. The barrel
    * stages avoid the old output-lane by candidate all-to-all comparison.
    */
  private def compactRoutes(inputs: IndexedSeq[Bits]): CompactedRoutes = {
    require(inputs.nonEmpty)
    if (inputs.length == 1) {
      val count = UInt(cfg.matchCountWidth bits)
      count := inputs.head(routeValidOffset).asUInt.resize(cfg.matchCountWidth)
      CompactedRoutes(Vector(inputs.head), count)
    } else {
      val split = inputs.length / 2
      val left = compactRoutes(inputs.take(split))
      val right = compactRoutes(inputs.drop(split))
      val capacity = scala.math.min(cfg.macLanes,
        left.entries.length + right.entries.length)
      val storedCountWidth = log2Up(capacity + 1)
      val leftStoredCount = UInt(storedCountWidth bits)
      val leftFillsOutput = left.count >= capacity
      leftStoredCount := Mux(leftFillsOutput,
        U(capacity, storedCountWidth bits), left.count.resize(storedCountWidth))

      val zeroRoute = B(0, routeWidth bits)
      var shiftedRight = Vector.tabulate(capacity) { index =>
        if (index < right.entries.length) right.entries(index) else zeroRoute
      }
      var distance = 1
      var stage = 0
      while (distance < capacity) {
        val previous = shiftedRight
        shiftedRight = Vector.tabulate(capacity) { index =>
          val shifted = if (index >= distance) previous(index - distance) else zeroRoute
          Mux(leftStoredCount(stage), shifted, previous(index))
        }
        distance *= 2
        stage += 1
      }

      val entries = Vector.tabulate(capacity) { index =>
        val leftEntry = if (index < left.entries.length) left.entries(index) else zeroRoute
        Mux(leftFillsOutput ||
          U(index, storedCountWidth bits) < leftStoredCount,
          leftEntry, shiftedRight(index))
      }
      val count = UInt(cfg.matchCountWidth bits)
      count := (left.count + right.count).resize(cfg.matchCountWidth)
      CompactedRoutes(entries, count)
    }
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
  for (a <- 0 until cfg.maxFibers; b <- 0 until cfg.maxFibers) {
    val pair = a * cfg.maxFibers + b
    pairMasks(pair) := Mux(io.aValid(a) && io.bValid(b),
      io.aMask(a) & io.bMask(b), B(0, cfg.fiberWidth bits))
  }

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

  private val candidates = for {
    a <- 0 until cfg.maxFibers
    b <- 0 until cfg.maxFibers
    coordinate <- 0 until cfg.fiberWidth
  } yield {
    val pair = a * cfg.maxFibers + b
    val route = Bits(routeWidth bits)
    route := 0
    route(routeValidOffset) := pairMasks(pair)(coordinate)
    route(routeAFiberOffset + cfg.fiberIndexWidth - 1 downto routeAFiberOffset) :=
      U(a, cfg.fiberIndexWidth bits).asBits
    route(routeBFiberOffset + cfg.fiberIndexWidth - 1 downto routeBFiberOffset) :=
      U(b, cfg.fiberIndexWidth bits).asBits
    route(routeCoordinateOffset + cfg.fiberValueIndexWidth - 1 downto
      routeCoordinateOffset) := U(coordinate, cfg.fiberValueIndexWidth bits).asBits
    route(routeAValueOffset + cfg.fiberValueIndexWidth - 1 downto
      routeAValueOffset) := aValueRanks(a)(coordinate).asBits
    route(routeBValueOffset + cfg.fiberValueIndexWidth - 1 downto
      routeBValueOffset) := bValueRanks(b)(coordinate).asBits
    route
  }
  private val compacted = compactRoutes(candidates)
  require(compacted.entries.length == cfg.macLanes)
  io.count := compacted.count
  io.overflow := compacted.count > cfg.macLanes

  for (lane <- 0 until cfg.macLanes) {
    val route = compacted.entries(lane)
    io.routes(lane).valid := route(routeValidOffset)
    io.routes(lane).aFiber := route(
      routeAFiberOffset + cfg.fiberIndexWidth - 1 downto routeAFiberOffset).asUInt
    io.routes(lane).bFiber := route(
      routeBFiberOffset + cfg.fiberIndexWidth - 1 downto routeBFiberOffset).asUInt
    io.routes(lane).coordinate := route(
      routeCoordinateOffset + cfg.fiberValueIndexWidth - 1 downto
        routeCoordinateOffset).asUInt
    io.routes(lane).aValueIndex := route(
      routeAValueOffset + cfg.fiberValueIndexWidth - 1 downto
        routeAValueOffset).asUInt
    io.routes(lane).bValueIndex := route(
      routeBValueOffset + cfg.fiberValueIndexWidth - 1 downto
        routeBValueOffset).asUInt
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
