package trapezoid

import spinal.core._
import spinal.lib._

/** SRAM-backed synchronous FIFO used by one TrGT PE subrow.
  *
  * The memory models one bank of the PE-local buffer.  One prefetched head
  * element keeps the Stream payload stable while the consumer is stalled.
  */
class GustavsonBFifo(cfg: TrapezoidConfig, depth: Int) extends Component {
  private val pointerWidth = scala.math.max(1, log2Up(depth))
  private val countWidth = log2Up(depth + 1)

  val io = new Bundle {
    val clear = in Bool()
    val push = slave(Stream(GustavsonB(cfg)))
    val pop = master(Stream(GustavsonB(cfg)))
    val occupancy = out UInt(countWidth bits)
  }

  val storage = Mem(GustavsonB(cfg), depth)
  val readPointer = Reg(UInt(pointerWidth bits)) init 0
  val writePointer = Reg(UInt(pointerWidth bits)) init 0
  val occupancy = Reg(UInt(countWidth bits)) init 0
  val storedCount = Reg(UInt(countWidth bits)) init 0
  val outputValid = RegInit(False)

  // Conservatively stop writes while full so a bank never depends on a
  // particular read-during-write behavior from the target SRAM.
  io.push.ready := !io.clear && occupancy =/= depth
  io.pop.valid := !io.clear && outputValid
  io.occupancy := occupancy

  when(io.push.fire) {
    storage.write(writePointer, io.push.payload)
    when(writePointer === depth - 1) { writePointer := 0 }
      .otherwise { writePointer := writePointer + 1 }
  }

  val issueRead = !io.clear && storedCount =/= 0 && (!outputValid || io.pop.fire)
  val readPayload = storage.readSync(readPointer, issueRead)
  io.pop.payload := readPayload
  when(issueRead) {
    when(readPointer === depth - 1) { readPointer := 0 }
      .otherwise { readPointer := readPointer + 1 }
  }

  switch(io.push.fire ## issueRead) {
    is(B"10") { storedCount := storedCount + 1 }
    is(B"01") { storedCount := storedCount - 1 }
  }
  outputValid := (outputValid && !io.pop.fire) || issueRead

  switch(io.push.fire ## io.pop.fire) {
    is(B"10") { occupancy := occupancy + 1 }
    is(B"01") { occupancy := occupancy - 1 }
  }

  when(io.clear) {
    readPointer := 0
    writePointer := 0
    occupancy := 0
    storedCount := 0
    outputValid := False
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

object TrGsSpatialState extends SpinalEnum {
  val Idle, Run, Output, AbortDrain = newElement()
}

/** TrGS engine with one accumulator slot per spatial output column. */
class TrGsSpatialEngine(tileCfg: GustavsonTileConfig) extends Component {
  private val cfg = tileCfg.core
  private val slots = tileCfg.accumulatorEntries
  private val inFlightWidth = log2Up(cfg.responseFifoDepth + 5)
  require((slots & (slots - 1)) == 0,
    "TrGS spatial accumulator entries must be a power of two")
  require(slots >= cfg.macLanes,
    "TrGS needs at least one accumulator slot per spatial MAC lane")
  require(cfg.coordinateWidth >= tileCfg.entryIndexWidth)

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(TrGsCommand(cfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val overflow = out Bool()
    val unsupportedMode = out Bool()
  }

  val state = RegInit(TrGsSpatialState.Idle)
  val inputClosed = RegInit(False)
  val doneReg = RegInit(False)
  val overflowReg = RegInit(False)
  val modeErrorReg = RegInit(False)
  val inFlight = Reg(UInt(inFlightWidth bits)) init 0
  val outputCursor = Reg(UInt(tileCfg.entryIndexWidth bits)) init 0

  val slotValid = Vec((0 until slots).map(_ => RegInit(False)))
  val slotRow = Vec((0 until slots).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val slotColumn = Vec((0 until slots).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val slotValue = Vec((0 until slots).map(_ =>
    Reg(SInt(cfg.accumulatorWidth bits)) init 0))

  io.busy := state =/= TrGsSpatialState.Idle
  io.done := doneReg
  io.overflow := overflowReg
  io.unsupportedMode := modeErrorReg

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

  val pe = new TrapezoidPERow(cfg)
  val supportedMode = io.command.payload.mode === TrapezoidDataflow.TrGs
  val acceptsSupported = (state === TrGsSpatialState.Idle ||
    (state === TrGsSpatialState.Run && !inputClosed)) && supportedMode
  pe.io.command.valid := !io.clear && io.command.valid && acceptsSupported
  pe.io.command.payload := adapted
  io.command.ready := !io.clear && Mux(supportedMode,
    acceptsSupported && pe.io.command.ready,
    state === TrGsSpatialState.Idle)

  val commandFire = io.command.fire && supportedMode
  val responseFire = pe.io.response.fire
  val accumulateResponse = responseFire && state === TrGsSpatialState.Run
  pe.io.response.ready := !io.clear &&
    (state === TrGsSpatialState.Run || state === TrGsSpatialState.AbortDrain)

  val response = pe.io.response.payload
  var responseConflict: Bool = False
  for (lane <- 0 until cfg.outputLanes;
       earlier <- 0 until lane) {
    val laneResult = response.results(lane)
    val earlierResult = response.results(earlier)
    responseConflict = responseConflict ||
      (laneResult.valid && earlierResult.valid &&
        laneResult.column(tileCfg.entryIndexWidth - 1 downto 0) ===
          earlierResult.column(tileCfg.entryIndexWidth - 1 downto 0))
  }

  val newEntry = Bits(cfg.outputLanes bits)
  for (lane <- 0 until cfg.outputLanes) {
    val result = response.results(lane)
    val slot = result.column(tileCfg.entryIndexWidth - 1 downto 0)
    val coordinateCollision = slotValid(slot) &&
      (slotRow(slot) =/= result.row || slotColumn(slot) =/= result.column)
    newEntry(lane) := result.valid && !slotValid(slot)
    when(accumulateResponse && result.valid && !responseConflict) {
      when(coordinateCollision) {
        overflowReg := True
      } otherwise {
        for (entry <- 0 until slots) {
          when(slot === entry) {
            when(slotValid(entry)) {
              slotValue(entry) := (slotValue(entry) + result.value).resized
            } otherwise {
              slotValid(entry) := True
              slotRow(entry) := result.row
              slotColumn(entry) := result.column
              slotValue(entry) := result.value
            }
          }
        }
      }
    }
  }
  val outputChoices = Vec(Bool(), slots)
  var earlierOutput: Bool = False
  for (entry <- 0 until slots) {
    val eligible = slotValid(entry) &&
      U(entry, tileCfg.entryIndexWidth bits) >= outputCursor
    outputChoices(entry) := eligible && !earlierOutput
    earlierOutput = earlierOutput || eligible
  }
  val outputFound = outputChoices.asBits.orR
  val outputSlot = UInt(tileCfg.entryIndexWidth bits)
  outputSlot := 0
  for (entry <- 0 until slots) {
    when(outputChoices(entry)) { outputSlot := entry }
  }
  val laterOutput = Vec(Bool(), slots)
  for (entry <- 0 until slots) {
    laterOutput(entry) := slotValid(entry) &&
      U(entry, tileCfg.entryIndexWidth bits) > outputSlot
  }

  io.result.valid := state === TrGsSpatialState.Output && outputFound
  io.result.payload.valid := True
  io.result.payload.row := slotRow(outputSlot)
  io.result.payload.column := slotColumn(outputSlot)
  io.result.payload.value := slotValue(outputSlot)
  io.result.payload.endOfTile := !laterOutput.asBits.orR

  when(commandFire =/= responseFire) {
    when(commandFire) { inFlight := inFlight + 1 }
      .otherwise { inFlight := inFlight - 1 }
  }

  switch(state) {
    is(TrGsSpatialState.Idle) {
      when(io.command.fire) {
        doneReg := False
        when(supportedMode) {
          for (entry <- 0 until slots) slotValid(entry) := False
          overflowReg := False
          modeErrorReg := False
          inputClosed := io.command.payload.endOfTile
          state := TrGsSpatialState.Run
        } otherwise {
          modeErrorReg := True
          doneReg := True
        }
      }
    }

    is(TrGsSpatialState.Run) {
      when(commandFire && io.command.payload.endOfTile) {
        inputClosed := True
      }
      when(responseFire) {
        when(responseConflict) { overflowReg := True }
        when(response.results(0).endOfTile) {
          inputClosed := False
          when(slotValid.asBits.orR || (!responseConflict && newEntry.orR)) {
            outputCursor := 0
            state := TrGsSpatialState.Output
          } otherwise {
            doneReg := True
            state := TrGsSpatialState.Idle
          }
        }
      }
    }

    is(TrGsSpatialState.Output) {
      when(io.result.fire) {
        when(io.result.payload.endOfTile) {
          for (entry <- 0 until slots) slotValid(entry) := False
          doneReg := True
          state := TrGsSpatialState.Idle
        } otherwise {
          outputCursor := outputSlot + 1
        }
      }
    }

    is(TrGsSpatialState.AbortDrain) {
      when(responseFire && inFlight === 1) {
        inFlight := 0
        state := TrGsSpatialState.Idle
      }
    }
  }

  when(io.clear) {
    for (entry <- 0 until slots) slotValid(entry) := False
    inputClosed := False
    doneReg := False
    overflowReg := False
    modeErrorReg := False
    when(inFlight === 0) { state := TrGsSpatialState.Idle }
      .otherwise { state := TrGsSpatialState.AbortDrain }
  }
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

object GeneratePaperTrGsSpatialEngine extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-paper-trgs",
    oneFilePerComponent = true
  ).generate(new TrGsSpatialEngine(GustavsonTileConfig(TrapezoidConfig.paper)))
}
