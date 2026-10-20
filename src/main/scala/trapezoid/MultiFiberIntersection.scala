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
  private val routeASourceOffset = routeBValueOffset + cfg.fiberValueIndexWidth
  private val routeBSourceOffset = routeASourceOffset + cfg.macLaneIndexWidth
  private val routeWidth = routeBSourceOffset + cfg.macLaneIndexWidth
  private val sourceValidOffset = 0
  private val sourceValueOffset = sourceValidOffset + 1
  private val sourceWidth = sourceValueOffset + cfg.dataWidth

  private case class CompactedEntries(entries: Vector[Bits], count: UInt)

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
  private def compactEntries(
      inputs: IndexedSeq[Bits],
      entryWidth: Int,
      capacityLimit: Int = cfg.macLanes): CompactedEntries = {
    require(inputs.nonEmpty)
    if (inputs.length == 1) {
      val count = UInt(cfg.matchCountWidth bits)
      count := inputs.head(0).asUInt.resize(cfg.matchCountWidth)
      CompactedEntries(Vector(inputs.head), count)
    } else {
      val split = inputs.length / 2
      val left = compactEntries(inputs.take(split), entryWidth, capacityLimit)
      val right = compactEntries(inputs.drop(split), entryWidth, capacityLimit)
      val capacity = scala.math.min(capacityLimit,
        left.entries.length + right.entries.length)
      val storedCountWidth = log2Up(capacity + 1)
      val leftStoredCount = UInt(storedCountWidth bits)
      val leftFillsOutput = left.count >= capacity
      leftStoredCount := Mux(leftFillsOutput,
        U(capacity, storedCountWidth bits), left.count.resize(storedCountWidth))

      val zeroEntry = B(0, entryWidth bits)
      var shiftedRight = Vector.tabulate(capacity) { index =>
        if (index < right.entries.length) right.entries(index) else zeroEntry
      }
      var distance = 1
      var stage = 0
      while (distance < capacity) {
        val previous = shiftedRight
        shiftedRight = Vector.tabulate(capacity) { index =>
          val shifted = if (index >= distance) previous(index - distance)
            else zeroEntry
          Mux(leftStoredCount(stage), shifted, previous(index))
        }
        distance *= 2
        stage += 1
      }

      val entries = Vector.tabulate(capacity) { index =>
        val leftEntry = if (index < left.entries.length) left.entries(index)
          else zeroEntry
        Mux(leftFillsOutput ||
          U(index, storedCountWidth bits) < leftStoredCount,
          leftEntry, shiftedRight(index))
      }
      val count = UInt(cfg.matchCountWidth bits)
      count := (left.count + right.count).resize(cfg.matchCountWidth)
      CompactedEntries(entries, count)
    }
  }

  val io = new Bundle {
    val aValid = in Vec(Bool(), cfg.maxFibers)
    val aMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val aValues = in Vec(
      Vec(SInt(cfg.dataWidth bits), cfg.fiberWidth), cfg.maxFibers)
    val bValid = in Vec(Bool(), cfg.maxFibers)
    val bMask = in Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
    val bValues = in Vec(
      Vec(SInt(cfg.dataWidth bits), cfg.fiberWidth), cfg.maxFibers)
    val routes = out Vec(IntersectionRoute(cfg), cfg.macLanes)
    val aSourceValid = out Bits(cfg.macLanes bits)
    val aSources = out Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
    val bSourceValid = out Bits(cfg.macLanes bits)
    val bSources = out Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
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

  val aCoordinateUnion = (0 until cfg.maxFibers).map { fiber =>
    Mux(io.aValid(fiber), io.aMask(fiber), B(0, cfg.fiberWidth bits))
  }.reduce(_ | _)
  val bCoordinateUnion = (0 until cfg.maxFibers).map { fiber =>
    Mux(io.bValid(fiber), io.bMask(fiber), B(0, cfg.fiberWidth bits))
  }.reduce(_ | _)
  val aParticipatingMasks = Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
  val bParticipatingMasks = Vec(Bits(cfg.fiberWidth bits), cfg.maxFibers)
  val aParticipatingRanks = Vec(
    Vec(UInt(cfg.fiberValueIndexWidth bits), cfg.fiberWidth), cfg.maxFibers)
  val bParticipatingRanks = Vec(
    Vec(UInt(cfg.fiberValueIndexWidth bits), cfg.fiberWidth), cfg.maxFibers)
  val aSourceOffsets = Vec(UInt(cfg.matchCountWidth bits), cfg.maxFibers)
  val bSourceOffsets = Vec(UInt(cfg.matchCountWidth bits), cfg.maxFibers)

  for (fiber <- 0 until cfg.maxFibers) {
    aParticipatingMasks(fiber) := Mux(io.aValid(fiber),
      io.aMask(fiber) & bCoordinateUnion, B(0, cfg.fiberWidth bits))
    bParticipatingMasks(fiber) := Mux(io.bValid(fiber),
      io.bMask(fiber) & aCoordinateUnion, B(0, cfg.fiberWidth bits))
    val aPrefix = parallelPrefix((0 until cfg.fiberWidth).map { coordinate =>
      aParticipatingMasks(fiber)(coordinate).asUInt.resize(cfg.matchCountWidth)
    })
    val bPrefix = parallelPrefix((0 until cfg.fiberWidth).map { coordinate =>
      bParticipatingMasks(fiber)(coordinate).asUInt.resize(cfg.matchCountWidth)
    })
    aParticipatingRanks(fiber)(0) := 0
    bParticipatingRanks(fiber)(0) := 0
    for (coordinate <- 1 until cfg.fiberWidth) {
      aParticipatingRanks(fiber)(coordinate) := aPrefix(coordinate - 1).resized
      bParticipatingRanks(fiber)(coordinate) := bPrefix(coordinate - 1).resized
    }
    if (fiber == 0) {
      aSourceOffsets(fiber) := 0
      bSourceOffsets(fiber) := 0
    } else {
      aSourceOffsets(fiber) := (aSourceOffsets(fiber - 1) +
        CountOne(aParticipatingMasks(fiber - 1))).resized
      bSourceOffsets(fiber) := (bSourceOffsets(fiber - 1) +
        CountOne(bParticipatingMasks(fiber - 1))).resized
    }
  }

  private val packedActivityWidth = 2
  private val packedActivityValidOffset = 0
  private val packedActivityValueOffset = 1
  private val aPackedActivity = (0 until cfg.maxFibers).map { fiber =>
    val candidates = (0 until cfg.fiberWidth).map { coordinate =>
      val entry = Bits(packedActivityWidth bits)
      entry(packedActivityValidOffset) := io.aValid(fiber) &&
        io.aMask(fiber)(coordinate)
      entry(packedActivityValueOffset) :=
        aParticipatingMasks(fiber)(coordinate)
      entry
    }
    compactEntries(candidates, packedActivityWidth, cfg.fiberWidth).entries
  }
  private val bPackedActivity = (0 until cfg.maxFibers).map { fiber =>
    val candidates = (0 until cfg.fiberWidth).map { coordinate =>
      val entry = Bits(packedActivityWidth bits)
      entry(packedActivityValidOffset) := io.bValid(fiber) &&
        io.bMask(fiber)(coordinate)
      entry(packedActivityValueOffset) :=
        bParticipatingMasks(fiber)(coordinate)
      entry
    }
    compactEntries(candidates, packedActivityWidth, cfg.fiberWidth).entries
  }

  private val aSourceCandidates = for {
    fiber <- 0 until cfg.maxFibers
    valueIndex <- 0 until cfg.fiberWidth
  } yield {
    val source = Bits(sourceWidth bits)
    source := 0
    source(sourceValidOffset) :=
      aPackedActivity(fiber)(valueIndex)(packedActivityValidOffset) &&
        aPackedActivity(fiber)(valueIndex)(packedActivityValueOffset)
    source(sourceValueOffset + cfg.dataWidth - 1 downto sourceValueOffset) :=
      io.aValues(fiber)(valueIndex).asBits
    source
  }
  private val bSourceCandidates = for {
    fiber <- 0 until cfg.maxFibers
    valueIndex <- 0 until cfg.fiberWidth
  } yield {
    val source = Bits(sourceWidth bits)
    source := 0
    source(sourceValidOffset) :=
      bPackedActivity(fiber)(valueIndex)(packedActivityValidOffset) &&
        bPackedActivity(fiber)(valueIndex)(packedActivityValueOffset)
    source(sourceValueOffset + cfg.dataWidth - 1 downto sourceValueOffset) :=
      io.bValues(fiber)(valueIndex).asBits
    source
  }
  private val compactedASources = compactEntries(aSourceCandidates, sourceWidth)
  private val compactedBSources = compactEntries(bSourceCandidates, sourceWidth)
  require(compactedASources.entries.length == cfg.macLanes)
  require(compactedBSources.entries.length == cfg.macLanes)

  for (lane <- 0 until cfg.macLanes) {
    val aSource = compactedASources.entries(lane)
    val bSource = compactedBSources.entries(lane)
    io.aSourceValid(lane) := aSource(sourceValidOffset)
    io.bSourceValid(lane) := bSource(sourceValidOffset)
    io.aSources(lane) := Mux(aSource(sourceValidOffset),
      aSource(sourceValueOffset + cfg.dataWidth - 1 downto
        sourceValueOffset).asSInt,
      S(0, cfg.dataWidth bits))
    io.bSources(lane) := Mux(bSource(sourceValidOffset),
      bSource(sourceValueOffset + cfg.dataWidth - 1 downto
        sourceValueOffset).asSInt,
      S(0, cfg.dataWidth bits))
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
    route(routeASourceOffset + cfg.macLaneIndexWidth - 1 downto
      routeASourceOffset) := (aSourceOffsets(a) +
        aParticipatingRanks(a)(coordinate)).resize(cfg.macLaneIndexWidth).asBits
    route(routeBSourceOffset + cfg.macLaneIndexWidth - 1 downto
      routeBSourceOffset) := (bSourceOffsets(b) +
        bParticipatingRanks(b)(coordinate)).resize(cfg.macLaneIndexWidth).asBits
    route
  }
  private val compacted = compactEntries(candidates, routeWidth)
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
    io.routes(lane).aSourceIndex := route(
      routeASourceOffset + cfg.macLaneIndexWidth - 1 downto
        routeASourceOffset).asUInt
    io.routes(lane).bSourceIndex := route(
      routeBSourceOffset + cfg.macLaneIndexWidth - 1 downto
        routeBSourceOffset).asUInt
  }
}

/** Logical boundary of one paper-style N-to-N operand distribution network.
  *
  * The MFIU/shift path presents at most `macLanes` contiguous source values.
  * Keeping source packing outside this block matches the interface expected by
  * a Benes switch fabric and avoids selecting directly from maxFibers complete
  * compressed-value arrays at every MAC lane.
  */
class OperandDistributionNetwork(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val sources = in Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
    val sourceValid = in Bits(cfg.macLanes bits)
    val laneValid = in Bits(cfg.macLanes bits)
    val sourceIndex = in Vec(UInt(cfg.macLaneIndexWidth bits), cfg.macLanes)
    val outputs = out Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
    val routeError = out Bool()
  }

  val laneErrors = Bits(cfg.macLanes bits)
  for (lane <- 0 until cfg.macLanes) {
    val selectedValid = io.sourceValid(io.sourceIndex(lane))
    laneErrors(lane) := io.laneValid(lane) && !selectedValid
    io.outputs(lane) := Mux(io.laneValid(lane) && selectedValid,
      io.sources(io.sourceIndex(lane)), S(0, cfg.dataWidth bits))
  }
  io.routeError := laneErrors.orR
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
