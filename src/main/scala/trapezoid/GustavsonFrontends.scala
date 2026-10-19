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
class TrGsSpatialEngine(
    tileCfg: GustavsonTileConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    wideOutput: Boolean = false
) extends Component {
  private val cfg = tileCfg.core
  private val slots = tileCfg.accumulatorEntries
  private val writebackGroups = slots / cfg.cacheLineWords
  private val writebackGroupIndexWidth = scala.math.max(1, log2Up(writebackGroups))
  private val inFlightWidth = log2Up(cfg.responseFifoDepth + 5)
  require((slots & (slots - 1)) == 0,
    "TrGS spatial accumulator entries must be a power of two")
  require(slots >= cfg.macLanes,
    "TrGS needs at least one accumulator slot per spatial MAC lane")
  require(slots % cfg.cacheLineWords == 0,
    "TrGS accumulator slots must divide into complete writeback lines")
  require(cfg.coordinateWidth >= tileCfg.entryIndexWidth)

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(TrGsCommand(cfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val resultLine = master(Stream(MatrixResultLine(cfg)))
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
  val outputGroupCursor = Reg(UInt(writebackGroupIndexWidth bits)) init 0

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

  val pe = peFactory(cfg)
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

  io.result.payload.valid := True
  io.result.payload.row := slotRow(outputSlot)
  io.result.payload.column := slotColumn(outputSlot)
  io.result.payload.value := slotValue(outputSlot)
  io.result.payload.endOfTile := !laterOutput.asBits.orR

  val lineGroupValid = Vec(Bool(), writebackGroups)
  for (group <- 0 until writebackGroups) {
    lineGroupValid(group) := (0 until cfg.cacheLineWords)
      .map(lane => slotValid(group * cfg.cacheLineWords + lane)).reduce(_ || _)
  }
  val lineGroupChoices = Vec(Bool(), writebackGroups)
  var earlierLineGroup: Bool = False
  for (group <- 0 until writebackGroups) {
    val eligible = lineGroupValid(group) &&
      U(group, writebackGroupIndexWidth bits) >= outputGroupCursor
    lineGroupChoices(group) := eligible && !earlierLineGroup
    earlierLineGroup = earlierLineGroup || eligible
  }
  val lineFound = lineGroupChoices.asBits.orR
  val lineGroup = UInt(writebackGroupIndexWidth bits)
  lineGroup := 0
  for (group <- 0 until writebackGroups) {
    when(lineGroupChoices(group)) { lineGroup := group }
  }
  val laterLineGroups = Vec(Bool(), writebackGroups)
  for (group <- 0 until writebackGroups) {
    laterLineGroups(group) := lineGroupValid(group) &&
      U(group, writebackGroupIndexWidth bits) > lineGroup
  }
  val lineIsFinal = !laterLineGroups.asBits.orR
  val lineValids = Bits(cfg.cacheLineWords bits)
  io.resultLine.payload.endOfTile := lineIsFinal
  for (lane <- 0 until cfg.cacheLineWords) {
    io.resultLine.payload.results(lane).valid := False
    io.resultLine.payload.results(lane).row := 0
    io.resultLine.payload.results(lane).column := 0
    io.resultLine.payload.results(lane).value := 0
    lineValids(lane) := False
    for (group <- 0 until writebackGroups) {
      val entry = group * cfg.cacheLineWords + lane
      when(lineGroup === group) {
        lineValids(lane) := slotValid(entry)
        io.resultLine.payload.results(lane).valid := slotValid(entry)
        io.resultLine.payload.results(lane).row := slotRow(entry)
        io.resultLine.payload.results(lane).column := slotColumn(entry)
        io.resultLine.payload.results(lane).value := slotValue(entry)
      }
    }
  }
  io.resultLine.payload.count := CountOne(lineValids).resized
  for (lane <- 0 until cfg.cacheLineWords) {
    val laterInLine = if (lane == cfg.cacheLineWords - 1) False
      else lineValids(cfg.cacheLineWords - 1 downto lane + 1).orR
    io.resultLine.payload.results(lane).endOfTile :=
      lineIsFinal && lineValids(lane) && !laterInLine
  }

  if (wideOutput) {
    io.result.valid := False
    io.resultLine.valid := state === TrGsSpatialState.Output && lineFound
  } else {
    io.result.valid := state === TrGsSpatialState.Output && outputFound
    io.resultLine.valid := False
  }

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
            outputGroupCursor := 0
            state := TrGsSpatialState.Output
          } otherwise {
            doneReg := True
            state := TrGsSpatialState.Idle
          }
        }
      }
    }

    is(TrGsSpatialState.Output) {
      if (wideOutput) {
        when(io.resultLine.fire) {
          when(io.resultLine.payload.endOfTile) {
            for (entry <- 0 until slots) slotValid(entry) := False
            doneReg := True
            state := TrGsSpatialState.Idle
          } otherwise {
            outputGroupCursor := lineGroup + 1
          }
        }
      } else {
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

/** One spatial TrGS accumulator per physical PE row. */
class TrGsSpatialArray(
    tileCfg: GustavsonTileConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    wideOutput: Boolean = false
) extends Component {
  private val cfg = tileCfg.core

  val io = new Bundle {
    val clear = in Bool()
    val commands = Vec(slave(Stream(TrGsCommand(cfg))), cfg.peRows)
    val results = Vec(master(Stream(MatrixResult(cfg))), cfg.peRows)
    val resultLines = Vec(master(Stream(MatrixResultLine(cfg))), cfg.peRows)
    val busy = out Bits(cfg.peRows bits)
    val done = out Bits(cfg.peRows bits)
    val overflow = out Bits(cfg.peRows bits)
    val unsupportedMode = out Bits(cfg.peRows bits)
  }

  val engines = Array.fill(cfg.peRows)(
    new TrGsSpatialEngine(tileCfg, peFactory, wideOutput))
  for (row <- 0 until cfg.peRows) {
    engines(row).io.clear := io.clear
    engines(row).io.command <> io.commands(row)
    engines(row).io.result <> io.results(row)
    engines(row).io.resultLine <> io.resultLines(row)
    io.busy(row) := engines(row).io.busy
    io.done(row) := engines(row).io.done
    io.overflow(row) := engines(row).io.overflow
    io.unsupportedMode(row) := engines(row).io.unsupportedMode
  }
}

/** Shared narrow TrGS command/result boundary for all spatial PE rows.
  *
  * Commands up to one `endOfTile` marker remain on the same row.  A closed
  * tile can drain through the result port while a new tile starts on another
  * free row.  Arbitration is locked until the selected tile's final result.
  */
class TrGsSpatialRowScheduler(
    tileCfg: GustavsonTileConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    wideOutput: Boolean = false
) extends Component {
  private val cfg = tileCfg.core
  private val rowIndexWidth = scala.math.max(1, log2Up(cfg.peRows))

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(TrGsCommand(cfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val resultLine = master(Stream(MatrixResultLine(cfg)))
    val allocated = out Bits(cfg.peRows bits)
    val busy = out Bits(cfg.peRows bits)
    val overflow = out Bits(cfg.peRows bits)
    val unsupportedMode = out Bits(cfg.peRows bits)
    val assignedTiles = out UInt(32 bits)
    val completedTiles = out UInt(32 bits)
  }

  val array = new TrGsSpatialArray(tileCfg, peFactory, wideOutput)
  array.io.clear := io.clear
  io.busy := array.io.busy
  io.overflow := array.io.overflow
  io.unsupportedMode := array.io.unsupportedMode

  val allocated = Vec((0 until cfg.peRows).map(_ => RegInit(False)))
  val closing = Vec((0 until cfg.peRows).map(_ => RegInit(False)))
  val inputActive = RegInit(False)
  val inputRow = Reg(UInt(rowIndexWidth bits)) init 0
  val assignedTiles = Reg(UInt(32 bits)) init 0
  val completedTiles = Reg(UInt(32 bits)) init 0
  io.allocated := allocated.asBits
  io.assignedTiles := assignedTiles
  io.completedTiles := completedTiles

  val freeChoices = Vec(Bool(), cfg.peRows)
  var earlierFree: Bool = False
  for (row <- 0 until cfg.peRows) {
    freeChoices(row) := !allocated(row) && !earlierFree
    earlierFree = earlierFree || !allocated(row)
  }
  val freeFound = freeChoices.asBits.orR
  val freeRow = UInt(rowIndexWidth bits)
  freeRow := 0
  for (row <- 0 until cfg.peRows) {
    when(freeChoices(row)) { freeRow := row }
  }

  val selectedInputRow = UInt(rowIndexWidth bits)
  selectedInputRow := Mux(inputActive, inputRow, freeRow)
  val inputRouteValid = inputActive || freeFound
  io.command.ready := False
  for (row <- 0 until cfg.peRows) {
    array.io.commands(row).valid := io.command.valid && inputRouteValid &&
      selectedInputRow === row
    array.io.commands(row).payload := io.command.payload
    when(inputRouteValid && selectedInputRow === row) {
      io.command.ready := array.io.commands(row).ready
    }
  }

  val supportedInputMode = io.command.payload.mode === TrapezoidDataflow.TrGs
  when(io.command.fire) {
    when(!inputActive) {
      for (row <- 0 until cfg.peRows) {
        when(selectedInputRow === row) { allocated(row) := True }
      }
      assignedTiles := assignedTiles + 1
    }
    when(io.command.payload.endOfTile || !supportedInputMode) {
      inputActive := False
      for (row <- 0 until cfg.peRows) {
        when(selectedInputRow === row) { closing(row) := True }
      }
    } otherwise {
      inputActive := True
      inputRow := selectedInputRow
    }
  }

  val completionBits = Bits(cfg.peRows bits)
  for (row <- 0 until cfg.peRows) {
    completionBits(row) := allocated(row) && closing(row) && array.io.done(row)
    when(completionBits(row)) {
      allocated(row) := False
      closing(row) := False
    }
  }
  val completionsThisCycle = CountOne(completionBits).resize(32)
  when(completionsThisCycle =/= 0) {
    completedTiles := completedTiles + completionsThisCycle
  }

  val outputChoices = Vec(Bool(), cfg.peRows)
  var earlierOutput: Bool = False
  for (row <- 0 until cfg.peRows) {
    val rowValid = if (wideOutput) array.io.resultLines(row).valid
      else array.io.results(row).valid
    outputChoices(row) := rowValid && !earlierOutput
    earlierOutput = earlierOutput || rowValid
  }
  val outputFound = outputChoices.asBits.orR
  val chosenOutputRow = UInt(rowIndexWidth bits)
  chosenOutputRow := 0
  for (row <- 0 until cfg.peRows) {
    when(outputChoices(row)) { chosenOutputRow := row }
  }

  val outputLocked = RegInit(False)
  val outputRow = Reg(UInt(rowIndexWidth bits)) init 0
  val selectedOutputRow = UInt(rowIndexWidth bits)
  selectedOutputRow := Mux(outputLocked, outputRow, chosenOutputRow)
  val outputRouteValid = !io.clear && (outputLocked || outputFound)
  io.result.valid := False
  io.result.payload := MatrixResult(cfg).getZero
  io.resultLine.valid := False
  io.resultLine.payload := MatrixResultLine(cfg).getZero
  for (row <- 0 until cfg.peRows) {
    if (wideOutput) {
      array.io.results(row).ready := False
      array.io.resultLines(row).ready := io.resultLine.ready &&
        outputRouteValid && selectedOutputRow === row
      when(outputRouteValid && selectedOutputRow === row) {
        io.resultLine.valid := array.io.resultLines(row).valid
        io.resultLine.payload := array.io.resultLines(row).payload
      }
    } else {
      array.io.resultLines(row).ready := False
      array.io.results(row).ready := io.result.ready && outputRouteValid &&
        selectedOutputRow === row
      when(outputRouteValid && selectedOutputRow === row) {
        io.result.valid := array.io.results(row).valid
        io.result.payload := array.io.results(row).payload
      }
    }
  }

  when(!outputLocked && outputFound) {
    outputLocked := True
    outputRow := chosenOutputRow
  }
  if (wideOutput) {
    when(io.resultLine.fire && io.resultLine.payload.endOfTile) {
      outputLocked := False
    }
  } else {
    when(io.result.fire && io.result.payload.endOfTile) {
      outputLocked := False
    }
  }

  when(io.clear) {
    for (row <- 0 until cfg.peRows) {
      allocated(row) := False
      closing(row) := False
    }
    inputActive := False
    outputLocked := False
    assignedTiles := 0
    completedTiles := 0
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

object GenerateTrGsSpatialRowScheduler extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-trgs-scheduler",
    oneFilePerComponent = true
  ).generate(new TrGsSpatialRowScheduler(
    GustavsonTileConfig(TrapezoidConfig.small)))
}

object GeneratePaperTrGsSpatialEngine extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-paper-trgs",
    oneFilePerComponent = true
  ).generate(new TrGsSpatialEngine(
    GustavsonTileConfig(TrapezoidConfig.paper), wideOutput = true))
}

object GeneratePaperTrGsSpatialRowScheduler extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-paper-trgs-scheduler",
    oneFilePerComponent = true
  ).generate(new TrGsSpatialRowScheduler(
    GustavsonTileConfig(TrapezoidConfig.paper), wideOutput = true))
}
