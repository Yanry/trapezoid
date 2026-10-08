package trapezoid

import spinal.core._
import spinal.lib._

/** Compute-array boundary.  Scheduling, cache refills, and DMA remain outside
  * this block; each row can be independently backpressured.
  */
class Trapezoid(cfg: TrapezoidConfig = TrapezoidConfig.small) extends Component {
  val io = new Bundle {
    val commands = Vec(slave(Stream(PERowCommand(cfg))), cfg.peRows)
    val responses = Vec(master(Stream(PERowResponse(cfg))), cfg.peRows)
    val localBuffers = Vec(slave(LocalBufferBus(cfg)), cfg.peRows)
  }

  for (row <- 0 until cfg.peRows) {
    val pe = new TrapezoidPERow(cfg)
    val localBuffer = new BankedLocalBuffer(cfg)
    pe.io.command <> io.commands(row)
    pe.io.response <> io.responses(row)
    localBuffer.io.bus <> io.localBuffers(row)
  }
}

object GenerateTrapezoid extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated",
    oneFilePerComponent = true
  ).generate(new Trapezoid(TrapezoidConfig.small))
}

object GeneratePaperTrapezoid extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-paper",
    oneFilePerComponent = true
  ).generate(new Trapezoid(TrapezoidConfig.paper))
}
