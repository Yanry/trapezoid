package trapezoid

import spinal.core._
import spinal.lib._

/** Small synchronous FIFO used by one TrGT PE subrow. */
class GustavsonBFifo(cfg: TrapezoidConfig, depth: Int) extends Component {
  private val pointerWidth = scala.math.max(1, log2Up(depth))
  private val countWidth = log2Up(depth + 1)

  val io = new Bundle {
    val clear = in Bool()
    val push = slave(Stream(GustavsonB(cfg)))
    val pop = master(Stream(GustavsonB(cfg)))
    val occupancy = out UInt(countWidth bits)
  }

  val storage = Vec((0 until depth).map(_ => Reg(GustavsonB(cfg))))
  val readPointer = Reg(UInt(pointerWidth bits)) init 0
  val writePointer = Reg(UInt(pointerWidth bits)) init 0
  val occupancy = Reg(UInt(countWidth bits)) init 0

  // A full register FIFO waits one cycle after a pop, avoiding ambiguous
  // same-address read/write behavior without assuming a specific RAM mode.
  io.push.ready := !io.clear && occupancy =/= depth
  io.pop.valid := !io.clear && occupancy =/= 0
  io.pop.payload := storage(readPointer)
  io.occupancy := occupancy

  when(io.push.fire) {
    storage(writePointer) := io.push.payload
    when(writePointer === depth - 1) { writePointer := 0 }
      .otherwise { writePointer := writePointer + 1 }
  }
  when(io.pop.fire) {
    when(readPointer === depth - 1) { readPointer := 0 }
      .otherwise { readPointer := readPointer + 1 }
  }
  switch(io.push.fire ## io.pop.fire) {
    is(B"10") { occupancy := occupancy + 1 }
    is(B"01") { occupancy := occupancy - 1 }
  }

  when(io.clear) {
    readPointer := 0
    writePointer := 0
    occupancy := 0
  }
}

/** Four-subrow TrGT front end.
  *
  * Each local-buffer bank is represented by an independent B FIFO.  At most
  * one FIFO head per subrow is issued in a command, matching the paper's four
  * temporal gather streams.  Lane population may range from zero to four and
  * is unrelated to whether TrGT is selected.
  */
class TrGtSubrowScheduler(tileCfg: GustavsonTileConfig) extends Component {
  private val cfg = tileCfg.core
  private val subrows = cfg.localBanks

  val io = new Bundle {
    val clear = in Bool()
    val aLoad = slave(Stream(IndexedGustavsonA(cfg)))
    val start = slave(Stream(GustavsonStart()))
    val bPush = Vec(slave(Stream(GustavsonB(cfg))), subrows)
    val finish = in Bool()
    val result = master(Stream(MatrixResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val overflow = out Bool()
    val unsupportedMode = out Bool()
    val fifoOccupancy = out Vec(UInt(tileCfg.fifoCountWidth bits), subrows)
    val acceptedBElements = out UInt(32 bits)
    val issuedBElements = out UInt(32 bits)
  }

  val aStorage = Vec((0 until cfg.macLanes).map(_ => Reg(GustavsonA(cfg))))
  val aLoaded = Vec((0 until cfg.macLanes).map(_ => RegInit(False)))
  val running = RegInit(False)
  val finishSeen = RegInit(False)
  val modeError = RegInit(False)
  val controlDone = RegInit(False)
  val acceptedBElements = Reg(UInt(32 bits)) init 0
  val issuedBElements = Reg(UInt(32 bits)) init 0
  io.acceptedBElements := acceptedBElements
  io.issuedBElements := issuedBElements

  val fifos = Array.fill(subrows)(new GustavsonBFifo(cfg, tileCfg.fifoDepthPerSubrow))
  for (subrow <- 0 until subrows) {
    fifos(subrow).io.clear := io.clear
    fifos(subrow).io.push.valid := io.bPush(subrow).valid && running && !finishSeen
    fifos(subrow).io.push.payload := io.bPush(subrow).payload
    io.bPush(subrow).ready := fifos(subrow).io.push.ready && running && !finishSeen
    io.fifoOccupancy(subrow) := fifos(subrow).io.occupancy
  }

  val engine = new GustavsonTileEngine(tileCfg)
  engine.io.clear := io.clear
  io.result <> engine.io.result
  io.busy := running || engine.io.busy
  io.done := controlDone || (!running && engine.io.done)
  io.overflow := engine.io.overflow
  io.unsupportedMode := modeError || engine.io.unsupportedMode

  io.aLoad.ready := !io.clear && !running && !engine.io.busy && !io.start.valid
  when(io.aLoad.fire) {
    for (index <- 0 until cfg.macLanes) {
      when(io.aLoad.payload.index === index) {
        aStorage(index) := io.aLoad.payload.element
        aLoaded(index) := True
      }
    }
  }

  io.start.ready := !io.clear && !running && !engine.io.busy && !io.aLoad.valid
  when(io.start.fire) {
    controlDone := False
    acceptedBElements := 0
    issuedBElements := 0
    modeError := io.start.payload.mode =/= TrapezoidDataflow.TrGt
    when(io.start.payload.mode === TrapezoidDataflow.TrGt) {
      running := True
      finishSeen := False
    } otherwise {
      controlDone := True
    }
  }
  when(running && io.finish) { finishSeen := True }

  val acceptedBits = Bits(subrows bits)
  val issuedBits = Bits(subrows bits)
  for (subrow <- 0 until subrows) {
    acceptedBits(subrow) := io.bPush(subrow).fire
    issuedBits(subrow) := fifos(subrow).io.pop.fire
  }
  val acceptedThisCycle = CountOne(acceptedBits).resize(32)
  when(acceptedThisCycle =/= 0) {
    acceptedBElements := acceptedBElements + acceptedThisCycle
  }

  val anyFifoValid = fifos.map(_.io.pop.valid).reduce(_ || _)
  val issueFinal = running && finishSeen && !anyFifoValid
  val scheduledCommand = PERowCommand(cfg)
  scheduledCommand.mode := TrapezoidDataflow.TrGt
  scheduledCommand.endOfTile := issueFinal
  scheduledCommand.trip := TrIpPayload(cfg).getZero
  for (index <- 0 until cfg.macLanes) {
    scheduledCommand.gustavson.a(index).valid := aLoaded(index) && aStorage(index).valid
    scheduledCommand.gustavson.a(index).row := aStorage(index).row
    scheduledCommand.gustavson.a(index).k := aStorage(index).k
    scheduledCommand.gustavson.a(index).value := aStorage(index).value
  }
  for (lane <- 0 until cfg.cacheLineWords) {
    if (lane < subrows) {
      scheduledCommand.gustavson.b(lane).valid := fifos(lane).io.pop.valid
      scheduledCommand.gustavson.b(lane).aIndex := fifos(lane).io.pop.payload.aIndex
      scheduledCommand.gustavson.b(lane).k := fifos(lane).io.pop.payload.k
      scheduledCommand.gustavson.b(lane).column := fifos(lane).io.pop.payload.column
      scheduledCommand.gustavson.b(lane).value := fifos(lane).io.pop.payload.value
    } else {
      scheduledCommand.gustavson.b(lane) := GustavsonB(cfg).getZero
    }
  }

  engine.io.command.valid := !io.clear && running && (anyFifoValid || issueFinal)
  engine.io.command.payload := scheduledCommand
  for (subrow <- 0 until subrows) {
    fifos(subrow).io.pop.ready := engine.io.command.fire && anyFifoValid
  }
  val issuedThisCycle = CountOne(issuedBits).resize(32)
  when(issuedThisCycle =/= 0) {
    issuedBElements := issuedBElements + issuedThisCycle
  }
  when(engine.io.command.fire && issueFinal) {
    running := False
    finishSeen := False
  }

  when(io.clear) {
    for (index <- 0 until cfg.macLanes) aLoaded(index) := False
    running := False
    finishSeen := False
    modeError := False
    controlDone := False
    acceptedBElements := 0
    issuedBElements := 0
  }
}

/** TrGS adapter that broadcasts one A nonzero and spatially distributes a
  * cache-line of B elements by their column coordinates.
  */
class TrGsSpatialEngine(tileCfg: GustavsonTileConfig) extends Component {
  private val cfg = tileCfg.core

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(TrGsCommand(cfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val overflow = out Bool()
    val unsupportedMode = out Bool()
  }

  val engine = new GustavsonTileEngine(tileCfg)
  engine.io.clear := io.clear
  io.result <> engine.io.result
  io.busy := engine.io.busy
  io.done := engine.io.done
  io.overflow := engine.io.overflow
  io.unsupportedMode := engine.io.unsupportedMode

  val adapted = PERowCommand(cfg)
  adapted.mode := io.command.payload.mode
  adapted.endOfTile := io.command.payload.endOfTile
  adapted.trip := TrIpPayload(cfg).getZero
  for (index <- 0 until cfg.macLanes) {
    if (index == 0) adapted.gustavson.a(index) := io.command.payload.a
    else adapted.gustavson.a(index) := GustavsonA(cfg).getZero
  }
  for (lane <- 0 until cfg.cacheLineWords) {
    adapted.gustavson.b(lane).valid := io.command.payload.b(lane).valid
    adapted.gustavson.b(lane).aIndex := 0
    adapted.gustavson.b(lane).k := io.command.payload.b(lane).k
    adapted.gustavson.b(lane).column := io.command.payload.b(lane).column
    adapted.gustavson.b(lane).value := io.command.payload.b(lane).value
  }

  engine.io.command.valid := io.command.valid
  engine.io.command.payload := adapted
  io.command.ready := engine.io.command.ready
}

object GenerateTrGtSubrowScheduler extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-trgt",
    oneFilePerComponent = true
  ).generate(new TrGtSubrowScheduler(GustavsonTileConfig(TrapezoidConfig.small)))
}

object GenerateTrGsSpatialEngine extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-trgs",
    oneFilePerComponent = true
  ).generate(new TrGsSpatialEngine(GustavsonTileConfig(TrapezoidConfig.small)))
}
