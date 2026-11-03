package trapezoid

import spinal.core._
import spinal.lib._

/** One registered level of the PE-row merge-reduction network.
  *
  * Every active position contains the comparator, adder and mux structure used
  * by the paper's dual-mode MRN.  IP modes use the coordinate comparison to
  * form segmented reductions.  Gustavson modes additionally require the input
  * stream to remain coordinate ordered so the same levels can later be reused
  * as temporal merge nodes without changing their comparison policy.
  */
class DualModeMrnLevel(cfg: TrapezoidConfig, level: Int) extends Component {
  require(level >= 0 && level < cfg.mrnReductionLevels)

  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
  }

  private val reductionDistance = 1 << level
  val transformed = Stream(PERowReductionPayload(cfg))
  transformed.valid := io.input.valid
  io.input.ready := transformed.ready
  transformed.payload.mode := io.input.payload.mode
  transformed.payload.endOfTile := io.input.payload.endOfTile
  transformed.payload.selectedColumns := io.input.payload.selectedColumns
  transformed.payload.effectualMacs := io.input.payload.effectualMacs

  val mergeMode = io.input.payload.mode === TrapezoidDataflow.TrGt ||
    io.input.payload.mode === TrapezoidDataflow.TrGs
  var orderViolation: Bool = False

  for (position <- 0 until cfg.macLanes) {
    val right = io.input.payload.lanes(position)
    val output = transformed.payload.lanes(position)
    output.valid := right.valid
    output.row := right.row
    output.column := right.column
    output.destinationBank := right.destinationBank
    output.destinationWordOffset := right.destinationWordOffset

    if (position >= reductionDistance) {
      val left = io.input.payload.lanes(position - reductionDistance)
      val sameCoordinate = DualModeMrnPolicy.sameCoordinate(
        left.valid, left.row, left.column,
        right.valid, right.row, right.column)
      val leftBeforeRight = DualModeMrnPolicy.leftBefore(
        left.row, left.column, right.row, right.column)
      val gatedLeft = Mux(sameCoordinate, left.value,
        S(0, cfg.accumulatorWidth bits))
      val gatedRight = Mux(sameCoordinate, right.value,
        S(0, cfg.accumulatorWidth bits))
      val reducedValue = (gatedLeft + gatedRight)
        .resize(cfg.accumulatorWidth)

      output.value := Mux(sameCoordinate, reducedValue, right.value)
      output.segmentStart := right.valid &&
        (right.segmentStart || left.segmentStart)
      orderViolation = orderViolation ||
        (mergeMode && left.valid && right.valid && !sameCoordinate &&
          !leftBeforeRight)
    } else {
      output.value := right.value
      output.segmentStart := right.segmentStart
    }
  }

  transformed.payload.overflow := io.input.payload.overflow || orderViolation
  io.output << transformed.m2sPipe()
}

/** Paper-shaped PE-row MRN: one ingress register followed by log2(lanes)
  * registered comparator/adder/mux levels.
  *
  * The current PE response format still emits every coordinate segment, so the
  * levels implement a segmented prefix reduction.  Keeping the levels as an
  * explicit dual-mode component removes the previous fixed inline tree and
  * provides the physical boundary needed to connect Gustavson stream inputs in
  * a later step.
  */
class DualModeMrnTree(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
  }

  var stage = io.input.m2sPipe()
  for (levelIndex <- 0 until cfg.mrnReductionLevels) {
    val level = new DualModeMrnLevel(cfg, levelIndex)
    level.io.input << stage
    stage = level.io.output
  }
  io.output << stage
}
