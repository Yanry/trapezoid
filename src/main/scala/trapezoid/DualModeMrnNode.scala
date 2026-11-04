package trapezoid

import spinal.core._
import spinal.lib._

object MrnNodeMode extends SpinalEnum {
  val IpReduce, GustavsonMerge = newElement()
}

case class MrnNodeEntry(cfg: TrapezoidConfig) extends Bundle {
  val valid = Bool()
  val row = UInt(cfg.coordinateWidth bits)
  val column = UInt(cfg.coordinateWidth bits)
  val value = SInt(cfg.accumulatorWidth bits)
}

case class DualModeMrnRequest(cfg: TrapezoidConfig) extends Bundle {
  val mode = MrnNodeMode()
  val left = MrnNodeEntry(cfg)
  val right = MrnNodeEntry(cfg)
}

case class DualModeMrnResponse(cfg: TrapezoidConfig) extends Bundle {
  val first = MrnNodeEntry(cfg)
  val second = MrnNodeEntry(cfg)
  val consumeLeft = Bool()
  val consumeRight = Bool()
}

object DualModeMrnPolicy {
  def sameCoordinate(
      leftValid: Bool,
      leftRow: UInt,
      leftColumn: UInt,
      rightValid: Bool,
      rightRow: UInt,
      rightColumn: UInt
  ): Bool = leftValid && rightValid && leftRow === rightRow &&
    leftColumn === rightColumn

  def leftBefore(
      leftRow: UInt,
      leftColumn: UInt,
      rightRow: UInt,
      rightColumn: UInt
  ): Bool = leftRow < rightRow ||
    (leftRow === rightRow && leftColumn < rightColumn)
}

case class DualModeMrnCellResult(cfg: TrapezoidConfig) extends Bundle {
  val sameCoordinate = Bool()
  val leftBeforeRight = Bool()
  val reducedValue = SInt(cfg.accumulatorWidth bits)
}

object DualModeMrnCell {
  def apply(
      cfg: TrapezoidConfig,
      leftValid: Bool,
      leftRow: UInt,
      leftColumn: UInt,
      leftValue: SInt,
      rightValid: Bool,
      rightRow: UInt,
      rightColumn: UInt,
      rightValue: SInt
  ): DualModeMrnCellResult = {
    val result = DualModeMrnCellResult(cfg)
    val bothValid = leftValid && rightValid
    result.sameCoordinate := DualModeMrnPolicy.sameCoordinate(
      leftValid, leftRow, leftColumn, rightValid, rightRow, rightColumn)
    result.leftBeforeRight := bothValid &&
      DualModeMrnPolicy.leftBefore(leftRow, leftColumn, rightRow, rightColumn)
    val gatedLeft = Mux(result.sameCoordinate, leftValue,
      S(0, cfg.accumulatorWidth bits))
    val gatedRight = Mux(result.sameCoordinate, rightValue,
      S(0, cfg.accumulatorWidth bits))
    result.reducedValue := (gatedLeft + gatedRight).resize(cfg.accumulatorWidth)
    result
  }

  def response(
      cfg: TrapezoidConfig,
      request: DualModeMrnRequest,
      cell: DualModeMrnCellResult
  ): DualModeMrnResponse = {
    val result = DualModeMrnResponse(cfg)
    result := DualModeMrnResponse(cfg).getZero
    when(request.mode === MrnNodeMode.IpReduce) {
      result.consumeLeft := request.left.valid
      result.consumeRight := request.right.valid
      when(cell.sameCoordinate) {
        result.first.valid := True
        result.first.row := request.left.row
        result.first.column := request.left.column
        result.first.value := cell.reducedValue
      } otherwise {
        result.first := request.left
        result.second := request.right
      }
    } otherwise {
      when(cell.sameCoordinate) {
        result.first.valid := True
        result.first.row := request.left.row
        result.first.column := request.left.column
        result.first.value := cell.reducedValue
        result.consumeLeft := True
        result.consumeRight := True
      } elsewhen(request.left.valid &&
          (!request.right.valid || cell.leftBeforeRight)) {
        result.first := request.left
        result.consumeLeft := True
      } otherwise {
        result.first := request.right
        result.consumeRight := request.right.valid
      }
    }
    result
  }
}

/** One MRN compare/reduce node shared by IP and Gustavson policies.
  *
  * `pipelineOutput` is used when the node owns its elastic stage.  A temporal
  * merge engine can disable it when its stream indices and SRAM write port are
  * already the stage boundary, allowing one merge decision per cycle.
  */
class DualModeMrnNode(
    cfg: TrapezoidConfig,
    pipelineOutput: Boolean = true
) extends Component {
  val io = new Bundle {
    val input = slave(Stream(DualModeMrnRequest(cfg)))
    val output = master(Stream(DualModeMrnResponse(cfg)))
  }

  val transformed = Stream(DualModeMrnResponse(cfg))
  transformed.valid := io.input.valid
  io.input.ready := transformed.ready
  val cell = DualModeMrnCell(
    cfg,
    io.input.payload.left.valid,
    io.input.payload.left.row,
    io.input.payload.left.column,
    io.input.payload.left.value,
    io.input.payload.right.valid,
    io.input.payload.right.row,
    io.input.payload.right.column,
    io.input.payload.right.value)
  transformed.payload := DualModeMrnCell.response(cfg, io.input.payload, cell)

  if (pipelineOutput) io.output << transformed.m2sPipe()
  else io.output << transformed
}
