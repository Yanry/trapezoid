package trapezoid

import spinal.core._
import spinal.lib._

object LocalBufferOwner extends SpinalEnum {
  val TrIp, Gustavson = newElement()
}

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

/** Mode-owned access to one physical PE-local SRAM.
  *
  * TrIP and Gustavson clients are mutually exclusive for new commands. Read
  * ownership is captured per bank so a synchronous response still returns to
  * the requester when the execution mode changes in the following cycle.
  */
class ModeSharedLocalBuffer(cfg: TrapezoidConfig) extends Component {
  val io = new Bundle {
    val owner = in(LocalBufferOwner())
    val trip = slave(LocalBufferBus(cfg))
    val gustavson = slave(LocalBufferBus(cfg))
  }

  val storage = new BankedLocalBuffer(cfg)
  val readOwner = Vec((0 until cfg.localBanks).map(_ =>
    Reg(LocalBufferOwner()) init LocalBufferOwner.TrIp))

  for (bank <- 0 until cfg.localBanks) {
    val tripSelected = io.owner === LocalBufferOwner.TrIp
    val selectedWrite = Flow(LocalBufferWrite(cfg))
    val selectedRead = Flow(UInt(cfg.localAddressWidth bits))

    selectedWrite.valid := Mux(tripSelected,
      io.trip.writes(bank).valid, io.gustavson.writes(bank).valid)
    selectedWrite.payload := Mux(tripSelected,
      io.trip.writes(bank).payload, io.gustavson.writes(bank).payload)
    selectedRead.valid := Mux(tripSelected,
      io.trip.readCommands(bank).valid, io.gustavson.readCommands(bank).valid)
    selectedRead.payload := Mux(tripSelected,
      io.trip.readCommands(bank).payload,
      io.gustavson.readCommands(bank).payload)

    storage.io.bus.writes(bank) := selectedWrite
    storage.io.bus.readCommands(bank) := selectedRead
    when(selectedRead.valid) {
      readOwner(bank) := io.owner
    }

    io.trip.readResponses(bank).valid :=
      storage.io.bus.readResponses(bank).valid &&
        readOwner(bank) === LocalBufferOwner.TrIp
    io.gustavson.readResponses(bank).valid :=
      storage.io.bus.readResponses(bank).valid &&
        readOwner(bank) === LocalBufferOwner.Gustavson
    io.trip.readResponses(bank).payload :=
      storage.io.bus.readResponses(bank).payload
    io.gustavson.readResponses(bank).payload :=
      storage.io.bus.readResponses(bank).payload
  }
}
