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
  require(cfg.macLanes == cfg.distributionWidth,
    "MFIU Benes source packing requires a power-of-two MAC count")
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

  private def spreadSourceIndex(index: UInt): UInt = {
    val width = cfg.macLaneIndexWidth
    if (width == 7) {
      (index(4 downto 3) ## index(6 downto 5) ##
        index(2 downto 0)).asUInt
    } else {
      (index(width - 2 downto 0) ## index(width - 1)).asUInt
    }
  }

  private def compactedSourceIndex(physicalLane: Int): Int = {
    if (cfg.macLaneIndexWidth == 7) {
      val low = physicalLane & 0x7
      val middle = (physicalLane >> 3) & 0x3
      val high = (physicalLane >> 5) & 0x3
      low | (high << 3) | (middle << 5)
    } else {
      (physicalLane % 2) * (cfg.macLanes / 2) + physicalLane / 2
    }
  }

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
    val compactedIndex = compactedSourceIndex(lane)
    val aSource = compactedASources.entries(compactedIndex)
    val bSource = compactedBSources.entries(compactedIndex)
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
    io.routes(lane).aSourceIndex := spreadSourceIndex(route(
      routeASourceOffset + cfg.macLaneIndexWidth - 1 downto
        routeASourceOffset).asUInt)
    io.routes(lane).bSourceIndex := spreadSourceIndex(route(
      routeBSourceOffset + cfg.macLaneIndexWidth - 1 downto
        routeBSourceOffset).asUInt)
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
  private def selectValue(values: Seq[SInt], index: UInt): SInt = {
    var level = values.toVector
    var bit = 0
    while (level.length > 1) {
      val previous = level
      level = Vector.tabulate(previous.length / 2) { pair =>
        Mux(index(bit), previous(pair * 2 + 1), previous(pair * 2))
      }
      bit += 1
    }
    level.head
  }

  private def selectValid(values: Seq[Bool], index: UInt): Bool = {
    var level = values.toVector
    var bit = 0
    while (level.length > 1) {
      val previous = level
      level = Vector.tabulate(previous.length / 2) { pair =>
        Mux(index(bit), previous(pair * 2 + 1), previous(pair * 2))
      }
      bit += 1
    }
    level.head
  }

  val io = new Bundle {
    val sources = in Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
    val sourceValid = in Bits(cfg.macLanes bits)
    val laneValid = in Bits(cfg.macLanes bits)
    val sourceIndex = in Vec(UInt(cfg.macLaneIndexWidth bits), cfg.macLanes)
    val outputs = out Vec(SInt(cfg.dataWidth bits), cfg.macLanes)
    val routeError = out Bool()
  }

  // A power-of-two radix tree makes the fan-in and logic depth explicit. The
  // input vector is padded with invalid sources when macLanes is not a power
  // of two; an out-of-range route therefore becomes a route error.
  private val paddedSources = Vector.tabulate(cfg.distributionWidth) { index =>
    if (index < cfg.macLanes) io.sources(index)
    else S(0, cfg.dataWidth bits)
  }
  private val paddedSourceValid = Vector.tabulate(cfg.distributionWidth) { index =>
    if (index < cfg.macLanes) io.sourceValid(index) else False
  }

  val laneErrors = Bits(cfg.macLanes bits)
  for (lane <- 0 until cfg.macLanes) {
    val selectedValid = selectValid(paddedSourceValid, io.sourceIndex(lane))
    val selectedValue = selectValue(paddedSources, io.sourceIndex(lane))
    laneErrors(lane) := io.laneValid(lane) && !selectedValid
    io.outputs(lane) := Mux(io.laneValid(lane) && selectedValid,
      selectedValue, S(0, cfg.dataWidth bits))
  }
  io.routeError := laneErrors.orR
}

/** Programmable multicast Benes fabric used by the paper-style distribution
  * network. Each 2x2 switch has an independent select bit per output, so a
  * source can be copied to multiple destinations. The control generator is a
  * separate block because its stateful routing algorithm is not part of the
  * data path.
  */
class BenesDistributionFabric(cfg: TrapezoidConfig) extends Component {
  private val fabricWidth = cfg.distributionWidth
  private val controlWidth = cfg.benesControlWidth
  private val payloadWidth = cfg.dataWidth + 1

  val io = new Bundle {
    val sources = in Vec(Bits(payloadWidth bits), cfg.macLanes)
    val controls = in Bits(controlWidth bits)
    val outputs = out Vec(Bits(payloadWidth bits), cfg.macLanes)
  }

  private def route(inputs: Vector[Bits], controls: Vector[Bool]): Vector[Bits] = {
    require(inputs.nonEmpty && (inputs.length & (inputs.length - 1)) == 0)
    if (inputs.length == 1) {
      inputs
    } else if (inputs.length == 2) {
      Vector(
        Mux(controls(0), inputs(1), inputs(0)),
        Mux(controls(1), inputs(1), inputs(0)))
    } else {
      val width = inputs.length
      val firstControls = controls.take(width)
      val subnetWidth = width / 2
      val subnetLevels = 2 * log2Up(subnetWidth) - 1
      val subnetControlWidth = subnetWidth * subnetLevels
      val firstHorizontal = Vector.tabulate(subnetWidth) { switch =>
        Mux(firstControls(switch * 2), inputs(switch * 2 + 1),
          inputs(switch * 2))
      }
      val firstDiagonal = Vector.tabulate(subnetWidth) { switch =>
        Mux(firstControls(switch * 2 + 1), inputs(switch * 2 + 1),
          inputs(switch * 2))
      }
      val upperControls = controls.slice(width, width + subnetControlWidth)
      val lowerControls = controls.slice(width + subnetControlWidth,
        width + subnetControlWidth * 2)
      val upper = route(firstHorizontal, upperControls)
      val lower = route(firstDiagonal, lowerControls)
      val lastControls = controls.takeRight(width)
      Vector.tabulate(width) { output =>
        val switch = output / 2
        val select = lastControls(output)
        if (output % 2 == 0) Mux(select, lower(switch), upper(switch))
        else Mux(select, lower(switch), upper(switch))
      }
    }
  }

  private val paddedSources = Vector.tabulate(fabricWidth) { index =>
    if (index < cfg.macLanes) io.sources(index)
    else B(0, payloadWidth bits)
  }
  private val routed = route(paddedSources, (0 until controlWidth)
    .map(io.controls(_)).toVector)
  for (index <- 0 until cfg.macLanes) {
    io.outputs(index) := routed(index)
  }
}

/** Generates a Benes schedule that broadcasts one selected source to every
  * output. This is the common multicast case when one compressed operand is
  * reused by several effectual products.
  */
class BenesBroadcastControlGenerator(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val sourceIndex = in UInt(cfg.macLaneIndexWidth bits)
    val controls = out Bits(cfg.benesControlWidth bits)
  }

  private def zeroControls(width: Int): Vector[Bool] =
    Vector.fill(width)(False)

  private def broadcastControls(
      width: Int,
      sourceIndex: UInt): Vector[Bool] = {
    require(width >= 1 && (width & (width - 1)) == 0)
    if (width == 1) {
      Vector.empty
    } else if (width == 2) {
      Vector(sourceIndex(0), sourceIndex(0))
    } else {
      val subnetWidth = width / 2
      val pairIndex = sourceIndex(sourceIndex.getWidth - 1 downto 1)
      val first = Vector.tabulate(width) { control =>
        if (control % 2 == 0) {
          val pair = U(control / 2, pairIndex.getWidth bits)
          pairIndex === pair && sourceIndex(0)
        } else {
          False
        }
      }
      val upper = broadcastControls(subnetWidth, pairIndex)
      val subnetLevels = 2 * log2Up(subnetWidth) - 1
      val lower = zeroControls(subnetWidth * subnetLevels)
      val last = zeroControls(width)
      first ++ upper ++ lower ++ last
    }
  }

  private val generated = broadcastControls(cfg.distributionWidth,
    io.sourceIndex)
  require(generated.length == cfg.benesControlWidth)
  for (index <- generated.indices) {
    io.controls(index) := generated(index)
  }
}

/** Converts compact MFIU source metadata into controls for a multicast Benes.
  *
  * A legal route is solved recursively. The input-pair and output-pair
  * constraints form the standard Benes routing graph; a bounded combinational
  * two-colour propagation finds the two subnet assignments. Duplicate source
  * requests are allowed, which is the multicast behavior needed by TrIP.
  */
class BenesRouteControlGenerator(cfg: TrapezoidConfig)
    extends Component {
  val io = new Bundle {
    val routeValid = in Bits(cfg.macLanes bits)
    val sourceIndices = in Vec(UInt(cfg.macLaneIndexWidth bits), cfg.macLanes)
    val selectedSource = out UInt(cfg.macLaneIndexWidth bits)
    val controls = out Bits(cfg.benesControlWidth bits)
    val routable = out Bool()
  }

  io.selectedSource := 0

  private def solve(width: Int, valid: Vector[Bool],
                    sources: Vector[UInt]): (Vector[Bool], Bool) = {
    require(width >= 1 && (width & (width - 1)) == 0)
    if (width == 1) {
      val legal = !valid.head || sources.head === 0
      (Vector.empty, legal)
    } else if (width == 2) {
      (Vector.tabulate(2)(output =>
        Mux(valid(output), sources(output)(0), False)), True)
    } else {
      val sourceWidth = sources.head.getWidth
      val sourcePairs = sources.map(_(sourceWidth - 1 downto 1))
      val sourceSides = sources.map(_(0))
      val assignedStages = Array.tabulate(width + 1)(_ => Vec(Bool(), width))
      val colorStages = Array.tabulate(width + 1)(_ => Vec(Bool(), width))
      for (node <- 0 until width) {
        assignedStages(0)(node) := False
        colorStages(0)(node) := False
      }

      val propagationRounds = if (width <= 16) width else log2Up(width)
      // Small fabrics retain the complete graph walk. Paper-width fabrics use
      // the external RTL solver; this branch only bounds any accidental wide
      // elaboration and keeps the metadata path logarithmic.
      for (stage <- 0 until propagationRounds) {
        val previousAssigned = assignedStages(stage)
        val previousColor = colorStages(stage)
        val propagationCandidates = Vector.tabulate(width) { node =>
          val mate = node ^ 1
          val mateEligible = valid(node) && valid(mate) &&
            sources(node) =/= sources(mate) && previousAssigned(mate)
          var sourceNeighbourFound: Bool = False
          for (other <- 0 until width) {
            if (other != node) {
              sourceNeighbourFound = sourceNeighbourFound ||
                (valid(node) && valid(other) &&
                  sourcePairs(node) === sourcePairs(other) &&
                  sourceSides(node) =/= sourceSides(other) &&
                  previousAssigned(other))
            }
          }
          !previousAssigned(node) && (mateEligible || sourceNeighbourFound)
        }
        val propagationFound = propagationCandidates.reduce(_ || _)
        val seedChoices = Vec(Bool(), width)
        var earlierSeed: Bool = False
        for (node <- 0 until width) {
          val eligible = !propagationFound && !previousAssigned(node) &&
            valid(node)
          seedChoices(node) := eligible && !earlierSeed
          earlierSeed = earlierSeed || eligible
        }

        for (node <- 0 until width) {
          val mate = node ^ 1
          val mateEligible = valid(node) && valid(mate) &&
            sources(node) =/= sources(mate) && previousAssigned(mate)
          var selectedColor: Bool = False
          var neighbourFound: Bool = False
          selectedColor = Mux(mateEligible, !previousColor(mate), False)
          neighbourFound = mateEligible
          for (other <- 0 until width) {
            if (other != node) {
              val eligible = valid(node) && valid(other) &&
                sourcePairs(node) === sourcePairs(other) &&
                sourceSides(node) =/= sourceSides(other) &&
                previousAssigned(other)
              val choice = eligible && !neighbourFound
              selectedColor = Mux(choice, !previousColor(other), selectedColor)
              neighbourFound = neighbourFound || eligible
            }
          }
          assignedStages(stage + 1)(node) := previousAssigned(node) ||
            seedChoices(node) || propagationCandidates(node)
          colorStages(stage + 1)(node) := Mux(previousAssigned(node),
            previousColor(node), Mux(propagationCandidates(node),
              selectedColor, False))
        }
      }

      val routeColor = colorStages(propagationRounds).toVector
      var conflict: Bool = False
      for (node <- 0 until width) {
        val mate = node ^ 1
        if (node < mate) {
          conflict = conflict || (valid(node) && valid(mate) &&
            sources(node) =/= sources(mate) &&
            routeColor(node) === routeColor(mate))
        }
        for (other <- node + 1 until width) {
          conflict = conflict || (valid(node) && valid(other) &&
            sourcePairs(node) === sourcePairs(other) &&
            sourceSides(node) =/= sourceSides(other) &&
            routeColor(node) === routeColor(other))
        }
      }
      val legal = !conflict

      val firstControls = Vector.tabulate(width) { control =>
        val pair = control / 2
        val upper = control % 2 == 0
        val desiredColor = if (upper) False else True
        var selectedSource: UInt = U(pair * 2, sourceWidth bits)
        var found: Bool = False
        for (output <- 0 until width) {
          val eligible = valid(output) &&
            routeColor(output) === desiredColor &&
            sourcePairs(output) === pair
          val choice = eligible && !found
          selectedSource = Mux(choice, sources(output), selectedSource)
          found = found || eligible
        }
        selectedSource(0)
      }

      val upperValid = Vector.tabulate(width / 2) { output =>
        valid(output * 2) && !routeColor(output * 2) ||
          valid(output * 2 + 1) && !routeColor(output * 2 + 1)
      }
      val lowerValid = Vector.tabulate(width / 2) { output =>
        valid(output * 2) && routeColor(output * 2) ||
          valid(output * 2 + 1) && routeColor(output * 2 + 1)
      }
      val upperSources = Vector.tabulate(width / 2) { output =>
        var source: UInt = U(0,
          scala.math.max(1, log2Up(width / 2)) bits)
        source = Mux(valid(output * 2) && !routeColor(output * 2),
          sourcePairs(output * 2).resized, source)
        source = Mux(valid(output * 2 + 1) && !routeColor(output * 2 + 1),
          sourcePairs(output * 2 + 1).resized, source)
        source
      }
      val lowerSources = Vector.tabulate(width / 2) { output =>
        var source: UInt = U(0,
          scala.math.max(1, log2Up(width / 2)) bits)
        source = Mux(valid(output * 2) && routeColor(output * 2),
          sourcePairs(output * 2).resized, source)
        source = Mux(valid(output * 2 + 1) && routeColor(output * 2 + 1),
          sourcePairs(output * 2 + 1).resized, source)
        source
      }
      val (upperControls, upperLegal) = solve(width / 2,
        upperValid, upperSources)
      val (lowerControls, lowerLegal) = solve(width / 2,
        lowerValid, lowerSources)
      val lastControls = routeColor
      (firstControls ++ upperControls ++ lowerControls ++ lastControls,
        legal && upperLegal && lowerLegal)
    }
  }

  private val route = solve(cfg.distributionWidth,
    Vector.tabulate(cfg.distributionWidth)(index =>
      if (index < cfg.macLanes) io.routeValid(index) else False),
    Vector.tabulate(cfg.distributionWidth)(index =>
      if (index < cfg.macLanes) io.sourceIndices(index)
      else U(0, cfg.macLaneIndexWidth bits)))
  require(route._1.length == cfg.benesControlWidth)
  for (index <- 0 until cfg.benesControlWidth) io.controls(index) := route._1(index)
  io.routable := route._2 && io.routeValid.orR
}

/** RTL implementation of the wide-fabric route solver.
  *
  * Keeping the procedural graph walk in a BlackBox prevents the 128-lane
  * paper configuration from becoming a deeply nested Scala expression tree.
  * The generated controls still drive the same multicast Benes fabric.
  */
class BenesRouteControlBlackBox(cfg: TrapezoidConfig) extends BlackBox {
  require(cfg.distributionWidth > 16)

  addGeneric("LANES", cfg.distributionWidth)
  addGeneric("INDEX_WIDTH", cfg.macLaneIndexWidth)
  addGeneric("STAGES", cfg.benesStageCount)
  addGeneric("CONTROL_WIDTH", cfg.benesControlWidth)
  setDefinitionName("TrapezoidBenesRouteControl")

  val io = new Bundle {
    val clk = in Bool()
    val reset = in Bool()
    val startValid = in Bool()
    val startReady = out Bool()
    val resultValid = out Bool()
    val resultReady = in Bool()
    val routeValid = in Bits(cfg.distributionWidth bits)
    val sourceIndices = in Bits(
      (cfg.distributionWidth * cfg.macLaneIndexWidth) bits)
    val controls = out Bits(cfg.benesControlWidth bits)
    val routable = out Bool()
  }

  noIoPrefix()
  mapClockDomain(clock = io.clk, reset = io.reset)
  addRTLPath("src/main/resources/rtl/TrapezoidBenesRouteControl.sv")
}

class BenesBroadcastRouteControlGenerator(cfg: TrapezoidConfig)
    extends BenesRouteControlGenerator(cfg)

case class BenesRouteMetadata(cfg: TrapezoidConfig) extends Bundle {
  val routeValid = Bits(cfg.macLanes bits)
  val sourceIndices = Vec(UInt(cfg.macLaneIndexWidth bits), cfg.macLanes)
}

/** A command-local pair of A/B distribution metadata. */
case class PERowBenesMetadata(cfg: TrapezoidConfig) extends Bundle {
  val routeValid = Bits(cfg.macLanes bits)
  val aSourceIndices = Vec(UInt(cfg.macLaneIndexWidth bits), cfg.macLanes)
  val bSourceIndices = Vec(UInt(cfg.macLaneIndexWidth bits), cfg.macLanes)
}

case class BenesRouteControlResult(cfg: TrapezoidConfig) extends Bundle {
  val controls = Bits(cfg.benesControlWidth bits)
  val routable = Bool()
}

private[trapezoid] object StaticBenesControls {
  private def solvePermutation(mapping: Vector[Int]): Vector[Boolean] = {
    val width = mapping.length
    require(width >= 1 && (width & (width - 1)) == 0)
    require(mapping.sorted == (0 until width).toVector)
    if (width == 1) {
      Vector.empty
    } else if (width == 2) {
      Vector(mapping(0) == 1, mapping(1) == 1)
    } else {
      val neighbours = Array.fill(width)(
        scala.collection.mutable.ArrayBuffer.empty[Int])
      def connect(left: Int, right: Int): Unit = {
        neighbours(left) += right
        neighbours(right) += left
      }
      for (output <- 0 until width by 2) connect(output, output + 1)
      val sourceOutput = Array.ofDim[Int](width)
      for (output <- 0 until width) sourceOutput(mapping(output)) = output
      for (source <- 0 until width by 2) {
        connect(sourceOutput(source), sourceOutput(source + 1))
      }

      val colors = Array.fill[Option[Boolean]](width)(None)
      for (seed <- 0 until width if colors(seed).isEmpty) {
        val queue = scala.collection.mutable.Queue(seed)
        colors(seed) = Some(false)
        while (queue.nonEmpty) {
          val current = queue.dequeue()
          val nextColor = !colors(current).get
          neighbours(current).foreach { neighbour =>
            colors(neighbour) match {
              case Some(existing) => require(existing == nextColor)
              case None =>
                colors(neighbour) = Some(nextColor)
                queue.enqueue(neighbour)
            }
          }
        }
      }
      val routeColors = colors.map(_.get)
      val half = width / 2
      val firstControls = Vector.tabulate(width) { control =>
        val inputPair = control / 2
        val branch = control % 2 == 1
        val selected = mapping.indices.find { output =>
          mapping(output) / 2 == inputPair && routeColors(output) == branch
        }.get
        mapping(selected) % 2 == 1
      }
      val upperMapping = Vector.tabulate(half) { outputPair =>
        val outputs = Vector(outputPair * 2, outputPair * 2 + 1)
        mapping(outputs.find(output => !routeColors(output)).get) / 2
      }
      val lowerMapping = Vector.tabulate(half) { outputPair =>
        val outputs = Vector(outputPair * 2, outputPair * 2 + 1)
        mapping(outputs.find(output => routeColors(output)).get) / 2
      }
      firstControls ++ solvePermutation(upperMapping) ++
        solvePermutation(lowerMapping) ++ routeColors
    }
  }

  def spreadSourceIndex(width: Int, logicalIndex: Int): Int = {
    val indexWidth = log2Up(width)
    if (indexWidth == 7) {
      val low = logicalIndex & 0x7
      val middle = (logicalIndex >> 3) & 0x3
      val high = (logicalIndex >> 5) & 0x3
      low | (high << 3) | (middle << 5)
    } else {
      val mask = width - 1
      ((logicalIndex << 1) & mask) | (logicalIndex >> (indexWidth - 1))
    }
  }

  def spreadControls(width: Int): BigInt = {
    solvePermutation(Vector.tabulate(width)(spreadSourceIndex(width, _)))
      .zipWithIndex.foldLeft(BigInt(0)) { case (value, (enabled, index)) =>
        if (enabled) value | (BigInt(1) << index) else value
      }
  }
}

case class RestrictedBenesRouteToken(cfg: TrapezoidConfig) extends Bundle {
  val supported = Bool()
  val broadcast = Bool()
  val sourceIndex = UInt(cfg.macLaneIndexWidth bits)
}

/** Metadata-only control pipeline for the common MFIU route patterns.
  * Broadcast and compacted spread-permutation routes cover the regular sparse
  * cases without carrying the full lane metadata through every stage.
  */
class PipelinedRestrictedBenesRouteControlGenerator(cfg: TrapezoidConfig)
    extends Component {
  addAttribute("verilator hier_block")
  private val width = cfg.distributionWidth
  private val pipelineStages = log2Up(width)
  private val spreadControls = B(
    StaticBenesControls.spreadControls(width), cfg.benesControlWidth bits)

  val io = new Bundle {
    val start = slave(Stream(BenesRouteMetadata(cfg)))
    val startSupported = out Bool()
    val result = master(Stream(BenesRouteControlResult(cfg)))
  }

  val firstValid = OHMasking.first(io.start.payload.routeValid)
  val broadcastSource = UInt(cfg.macLaneIndexWidth bits)
  broadcastSource := 0
  for (lane <- 0 until cfg.macLanes) {
    when(firstValid(lane)) {
      broadcastSource := io.start.payload.sourceIndices(lane)
    }
  }
  val broadcastMismatch = (0 until cfg.macLanes).map { lane =>
    io.start.payload.routeValid(lane) &&
      io.start.payload.sourceIndices(lane) =/= broadcastSource
  }.reduce(_ || _)
  val spreadMismatch = (0 until cfg.macLanes).map { lane =>
    io.start.payload.routeValid(lane) &&
      io.start.payload.sourceIndices(lane) =/=
        StaticBenesControls.spreadSourceIndex(width, lane)
  }.reduce(_ || _)
  val nonEmpty = io.start.payload.routeValid.orR
  val broadcastSupported = nonEmpty && !broadcastMismatch
  val spreadSupported = nonEmpty && !spreadMismatch
  io.startSupported := broadcastSupported || spreadSupported

  val tokenInput = Stream(RestrictedBenesRouteToken(cfg))
  tokenInput.valid := io.start.valid
  tokenInput.payload.supported := io.startSupported
  tokenInput.payload.broadcast := broadcastSupported
  tokenInput.payload.sourceIndex := broadcastSource
  io.start.ready := tokenInput.ready

  var tokenStage = tokenInput.m2sPipe()
  for (_ <- 1 until pipelineStages) tokenStage = tokenStage.m2sPipe()

  val broadcastControls = new BenesBroadcastControlGenerator(cfg)
  broadcastControls.io.sourceIndex := tokenStage.payload.sourceIndex
  io.result.valid := tokenStage.valid
  io.result.payload.controls := Mux(tokenStage.payload.broadcast,
    broadcastControls.io.controls, spreadControls)
  io.result.payload.routable := tokenStage.payload.supported
  tokenStage.ready := io.result.ready
}

/** Fixed-latency metadata path for wide PE rows.
  *
  * The route metadata is solved by the same multicast Benes control generator
  * for every fabric width. The seven elastic stages for a 128-lane fabric
  * carry the generated per-level switch controls; there is no wide-fabric
  * direct source-selection fallback in the datapath.
  */
class PipelinedBenesRouteControlGenerator(cfg: TrapezoidConfig)
    extends Component {
  addAttribute("verilator hier_block")

  val io = new Bundle {
    val start = slave(Stream(BenesRouteMetadata(cfg)))
    val result = master(Stream(BenesRouteControlResult(cfg)))
  }

  val solved = Stream(BenesRouteControlResult(cfg))
  solved.valid := io.start.valid
  if (cfg.distributionWidth <= 16) {
    val solver = new BenesRouteControlGenerator(cfg)
    solver.io.routeValid := io.start.payload.routeValid
    solver.io.sourceIndices := io.start.payload.sourceIndices
    solved.payload.controls := solver.io.controls
    solved.payload.routable := solver.io.routable
  } else {
    val solver = new BenesRouteControlBlackBox(cfg)
    solver.io.startValid := io.start.valid
    io.start.ready := solver.io.startReady
    solver.io.resultReady := io.result.ready
    io.result.valid := solver.io.resultValid
    solver.io.routeValid := io.start.payload.routeValid
    for (lane <- 0 until cfg.distributionWidth) {
      solver.io.sourceIndices(
        lane * cfg.macLaneIndexWidth + cfg.macLaneIndexWidth - 1 downto
          lane * cfg.macLaneIndexWidth) :=
        io.start.payload.sourceIndices(lane).asBits
    }
    io.result.payload.controls := solver.io.controls
    io.result.payload.routable := solver.io.routable
  }
  if (cfg.distributionWidth <= 16) {
    io.start.ready := solved.ready
  }

  if (cfg.distributionWidth <= 16) {
    // Small fabrics keep the exact Scala solver and use the same elastic
    // stage count as the paper-width implementation.
    var staged = solved
    for (_ <- 0 until log2Up(cfg.distributionWidth)) {
      staged = staged.m2sPipe()
    }
    io.result << staged
  }
}

/** Complete source-to-all-lanes multicast path used to validate the runtime
  * Benes control interface independently from the PE-row scheduler.
  */
class BenesBroadcastNetwork(cfg: TrapezoidConfig) extends Component {
  private val payloadWidth = cfg.dataWidth + 1

  val io = new Bundle {
    val sources = in Vec(Bits(payloadWidth bits), cfg.macLanes)
    val sourceIndex = in UInt(cfg.macLaneIndexWidth bits)
    val outputs = out Vec(Bits(payloadWidth bits), cfg.macLanes)
  }

  val controlGenerator = new BenesBroadcastControlGenerator(cfg)
  val fabric = new BenesDistributionFabric(cfg)
  controlGenerator.io.sourceIndex := io.sourceIndex
  fabric.io.sources := io.sources
  fabric.io.controls := controlGenerator.io.controls
  io.outputs := fabric.io.outputs
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
