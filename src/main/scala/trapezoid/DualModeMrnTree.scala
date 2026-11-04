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
class DualModeMrnLevel(
    cfg: TrapezoidConfig,
    level: Int,
    supportsTemporalMerge: Boolean = false
) extends Component {
  require(level >= 0 && level < cfg.mrnReductionLevels)

  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
    val temporalInput = slave(Stream(DualModeMrnRequest(cfg)))
    val temporalOutput = master(Stream(DualModeMrnResponse(cfg)))
  }

  private val reductionDistance = 1 << level
  val transformed = Stream(PERowReductionPayload(cfg))
  transformed.valid := io.input.valid
  io.input.ready := transformed.ready
  transformed.payload.mode := io.input.payload.mode
  transformed.payload.endOfTile := io.input.payload.endOfTile
  transformed.payload.selectedColumns := io.input.payload.selectedColumns
  transformed.payload.effectualMacs := io.input.payload.effectualMacs

  val useTemporal = if (supportsTemporalMerge) {
    io.temporalInput.valid && !io.input.valid
  } else {
    False
  }
  val sharedCell = if (supportsTemporalMerge) {
    val spatialLeft = io.input.payload.lanes(0)
    val spatialRight = io.input.payload.lanes(1)
    val cell = DualModeMrnCell(
      cfg,
      Mux(useTemporal, io.temporalInput.payload.left.valid, spatialLeft.valid),
      Mux(useTemporal, io.temporalInput.payload.left.row, spatialLeft.row),
      Mux(useTemporal, io.temporalInput.payload.left.column, spatialLeft.column),
      Mux(useTemporal, io.temporalInput.payload.left.value, spatialLeft.value),
      Mux(useTemporal, io.temporalInput.payload.right.valid, spatialRight.valid),
      Mux(useTemporal, io.temporalInput.payload.right.row, spatialRight.row),
      Mux(useTemporal, io.temporalInput.payload.right.column, spatialRight.column),
      Mux(useTemporal, io.temporalInput.payload.right.value, spatialRight.value))
    io.temporalOutput.valid := useTemporal
    io.temporalOutput.payload := DualModeMrnCell.response(
      cfg, io.temporalInput.payload, cell)
    io.temporalInput.ready := !io.input.valid && io.temporalOutput.ready
    cell
  } else {
    io.temporalInput.ready := False
    io.temporalOutput.valid := False
    io.temporalOutput.payload := DualModeMrnResponse(cfg).getZero
    null
  }

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
      val cell = if (supportsTemporalMerge && position == 1) {
        sharedCell
      } else {
        DualModeMrnCell(
          cfg,
          left.valid, left.row, left.column, left.value,
          right.valid, right.row, right.column, right.value)
      }

      output.value := Mux(cell.sameCoordinate, cell.reducedValue, right.value)
      output.segmentStart := right.valid &&
        (right.segmentStart || left.segmentStart)
      orderViolation = orderViolation ||
        (mergeMode && left.valid && right.valid && !cell.sameCoordinate &&
          !cell.leftBeforeRight)
    } else {
      output.value := right.value
      output.segmentStart := right.segmentStart
    }
  }

  transformed.payload.overflow := io.input.payload.overflow || orderViolation
  io.output << transformed.m2sPipe()
}

/** Paper-shaped PE-row MRN: log2(lanes) registered comparator/adder/mux levels.
  *
  * The current PE response format still emits every coordinate segment, so the
  * levels implement a segmented prefix reduction.  Keeping the levels as an
  * explicit dual-mode component removes the previous fixed inline tree.  Its
  * level-zero cell is also the physical temporal merge resource used by the
  * streaming Gustavson engine while the spatial pipeline is idle.
  */
class DualModeMrnTree(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val input = slave(Stream(PERowReductionPayload(cfg)))
    val output = master(Stream(PERowReductionPayload(cfg)))
    val temporalInput = slave(Stream(DualModeMrnRequest(cfg)))
    val temporalOutput = master(Stream(DualModeMrnResponse(cfg)))
  }

  var stage = io.input
  for (levelIndex <- 0 until cfg.mrnReductionLevels) {
    val level = new DualModeMrnLevel(cfg, levelIndex,
      supportsTemporalMerge = levelIndex == 0)
    level.io.input << stage
    if (levelIndex == 0) {
      level.io.temporalInput << io.temporalInput
      io.temporalOutput << level.io.temporalOutput
    } else {
      level.io.temporalInput.valid := False
      level.io.temporalInput.payload := DualModeMrnRequest(cfg).getZero
      level.io.temporalOutput.ready := True
    }
    stage = level.io.output
  }
  io.output << stage
}
