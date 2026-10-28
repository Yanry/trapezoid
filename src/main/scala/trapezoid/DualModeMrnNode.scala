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

/** One elastic MRN compare/reduce node shared by IP and Gustavson policies. */
class DualModeMrnNode(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val input = slave(Stream(DualModeMrnRequest(cfg)))
    val output = master(Stream(DualModeMrnResponse(cfg)))
  }

  val transformed = Stream(DualModeMrnResponse(cfg))
  transformed.valid := io.input.valid
  io.input.ready := transformed.ready
  transformed.payload := DualModeMrnResponse(cfg).getZero

  val sameCoordinate = io.input.payload.left.valid &&
    io.input.payload.right.valid &&
    io.input.payload.left.row === io.input.payload.right.row &&
    io.input.payload.left.column === io.input.payload.right.column
  val leftBeforeRight = io.input.payload.left.row < io.input.payload.right.row ||
    (io.input.payload.left.row === io.input.payload.right.row &&
      io.input.payload.left.column < io.input.payload.right.column)

  when(io.input.payload.mode === MrnNodeMode.IpReduce) {
    transformed.payload.consumeLeft := io.input.payload.left.valid
    transformed.payload.consumeRight := io.input.payload.right.valid
    when(sameCoordinate) {
      transformed.payload.first.valid := True
      transformed.payload.first.row := io.input.payload.left.row
      transformed.payload.first.column := io.input.payload.left.column
      transformed.payload.first.value :=
        (io.input.payload.left.value + io.input.payload.right.value).resized
    } otherwise {
      transformed.payload.first := io.input.payload.left
      transformed.payload.second := io.input.payload.right
    }
  } otherwise {
    when(sameCoordinate) {
      transformed.payload.first.valid := True
      transformed.payload.first.row := io.input.payload.left.row
      transformed.payload.first.column := io.input.payload.left.column
      transformed.payload.first.value :=
        (io.input.payload.left.value + io.input.payload.right.value).resized
      transformed.payload.consumeLeft := True
      transformed.payload.consumeRight := True
    } elsewhen(io.input.payload.left.valid &&
        (!io.input.payload.right.valid || leftBeforeRight)) {
      transformed.payload.first := io.input.payload.left
      transformed.payload.consumeLeft := True
    } otherwise {
      transformed.payload.first := io.input.payload.right
      transformed.payload.consumeRight := io.input.payload.right.valid
    }
  }

  io.output << transformed.m2sPipe()
}
