package trapezoid

import spinal.core._
import spinal.lib._

/** Four-bank PE-local SRAM implementing the paper's TrIP scatter-buffer shape.
  * Each bank stores one multi-word line and supports one byte-masked line write
  * plus one synchronous line read per cycle.
  */
class BankedLocalBuffer(cfg: TrapezoidConfig) extends Component {
  require(cfg.accumulatorWidth % 8 == 0, "masked SRAM writes require byte width")

  val io = new Bundle {
    val bus = slave(LocalBufferBus(cfg))
  }

  for (bank <- 0 until cfg.localBanks) {
    val memory = Mem(Bits(cfg.localLineWidth bits), cfg.localDepthPerBank)
    when(io.bus.writes(bank).valid) {
      memory.write(
        address = io.bus.writes(bank).payload.address,
        data = io.bus.writes(bank).payload.data,
        mask = io.bus.writes(bank).payload.mask
      )
    }
    io.bus.readResponses(bank).payload := memory.readSync(
      address = io.bus.readCommands(bank).payload,
      enable = io.bus.readCommands(bank).valid
    )
    io.bus.readResponses(bank).valid := RegNext(io.bus.readCommands(bank).valid) init False
  }
}
