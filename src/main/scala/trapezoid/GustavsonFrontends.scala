package trapezoid

import spinal.core._
import spinal.lib._

/** Four independent TrGT queues stored in one physical PE-local buffer.
  *
  * Each subrow owns one bank. One prefetched element per bank keeps Stream
  * payloads stable under backpressure while preserving one pop per subrow and
  * cycle, as required by TrGT.
  */
class BankedGustavsonBFifo(cfg: TrapezoidConfig, depth: Int) extends Component {
  private val pointerWidth = scala.math.max(1, log2Up(depth))
  private val countWidth = log2Up(depth + 1)
  private val recordWidth = 1 + cfg.aRegisterIndexWidth +
    2 * cfg.coordinateWidth + cfg.dataWidth
  require(depth <= cfg.localDepthPerBank)
  require(recordWidth <= cfg.localLineWidth,
    "one local-buffer line must hold one packed TrGT B element")

  val io = new Bundle {
    val clear = in Bool()
    val pushes = Vec(slave(Stream(GustavsonB(cfg))), cfg.localBanks)
    val pops = Vec(master(Stream(GustavsonB(cfg))), cfg.localBanks)
    val occupancy = out Vec(UInt(countWidth bits), cfg.localBanks)
  }

  val storage = new BankedLocalBuffer(cfg)
  val readPointers = Vec((0 until cfg.localBanks).map(_ =>
    Reg(UInt(pointerWidth bits)) init 0))
  val writePointers = Vec((0 until cfg.localBanks).map(_ =>
    Reg(UInt(pointerWidth bits)) init 0))
  val occupancies = Vec((0 until cfg.localBanks).map(_ =>
    Reg(UInt(countWidth bits)) init 0))
  val storedCounts = Vec((0 until cfg.localBanks).map(_ =>
    Reg(UInt(countWidth bits)) init 0))
  val outputValids = Vec((0 until cfg.localBanks).map(_ => RegInit(False)))

  for (bank <- 0 until cfg.localBanks) {
    io.pushes(bank).ready := !io.clear && occupancies(bank) =/= depth
    io.pops(bank).valid := !io.clear && outputValids(bank)
    io.pops(bank).payload.assignFromBits(
      storage.io.bus.readResponses(bank).payload(recordWidth - 1 downto 0))
    io.occupancy(bank) := occupancies(bank)

    val writeData = Bits(cfg.localLineWidth bits)
    writeData := 0
    writeData(recordWidth - 1 downto 0) := io.pushes(bank).payload.asBits
    storage.io.bus.writes(bank).valid := io.pushes(bank).fire
    storage.io.bus.writes(bank).payload.address :=
      writePointers(bank).resize(cfg.localAddressWidth)
    storage.io.bus.writes(bank).payload.data := writeData
    storage.io.bus.writes(bank).payload.mask.setAll()

    val issueRead = !io.clear && storedCounts(bank) =/= 0 &&
      (!outputValids(bank) || io.pops(bank).fire)
    storage.io.bus.readCommands(bank).valid := issueRead
    storage.io.bus.readCommands(bank).payload :=
      readPointers(bank).resize(cfg.localAddressWidth)

    when(io.pushes(bank).fire) {
      when(writePointers(bank) === depth - 1) { writePointers(bank) := 0 }
        .otherwise { writePointers(bank) := writePointers(bank) + 1 }
    }
    when(issueRead) {
      when(readPointers(bank) === depth - 1) { readPointers(bank) := 0 }
        .otherwise { readPointers(bank) := readPointers(bank) + 1 }
    }

    switch(io.pushes(bank).fire ## issueRead) {
      is(B"10") { storedCounts(bank) := storedCounts(bank) + 1 }
      is(B"01") { storedCounts(bank) := storedCounts(bank) - 1 }
    }
    outputValids(bank) :=
      (outputValids(bank) && !io.pops(bank).fire) || issueRead

    switch(io.pushes(bank).fire ## io.pops(bank).fire) {
      is(B"10") { occupancies(bank) := occupancies(bank) + 1 }
      is(B"01") { occupancies(bank) := occupancies(bank) - 1 }
    }

    when(io.clear) {
      readPointers(bank) := 0
      writePointers(bank) := 0
      occupancies(bank) := 0
      storedCounts(bank) := 0
      outputValids(bank) := False
    }
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

  // Keep the stationary A values in the same four logical subrows used by
  // TrGT.  The flattened PERowCommand interface is retained at the PE-row
  // boundary, but the generated storage now follows the paper's temporal
  // organization instead of one monolithic register file.
  val aStorage = Array.fill(subrows)(
    Vec((0 until cfg.macLanesPerSubrow).map(_ => Reg(GustavsonA(cfg)))))
  val aLoaded = Array.fill(subrows)(
    Vec((0 until cfg.macLanesPerSubrow).map(_ => RegInit(False))))
  val running = RegInit(False)
  val finishSeen = RegInit(False)
  val aLoadRequiresReset = RegInit(True)
  val modeError = RegInit(False)
  val controlDone = RegInit(False)
  val acceptedBElements = Reg(UInt(32 bits)) init 0
  val issuedBElements = Reg(UInt(32 bits)) init 0
  io.acceptedBElements := acceptedBElements
  io.issuedBElements := issuedBElements

  val fifos = new BankedGustavsonBFifo(cfg, tileCfg.fifoDepthPerSubrow)
  fifos.io.clear := io.clear
  for (subrow <- 0 until subrows) {
    fifos.io.pushes(subrow).valid := io.bPush(subrow).valid && running && !finishSeen
    fifos.io.pushes(subrow).payload := io.bPush(subrow).payload
    io.bPush(subrow).ready := fifos.io.pushes(subrow).ready && running && !finishSeen
    io.fifoOccupancy(subrow) := fifos.io.occupancy(subrow)
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
    when(aLoadRequiresReset) {
      for (subrow <- 0 until subrows;
           offset <- 0 until cfg.macLanesPerSubrow) {
        aLoaded(subrow)(offset) := False
      }
      aLoadRequiresReset := False
    }
    for (subrow <- 0 until subrows;
         offset <- 0 until cfg.macLanesPerSubrow) {
      val index = subrow * cfg.macLanesPerSubrow + offset
      when(io.aLoad.payload.index === index) {
        aStorage(subrow)(offset) := io.aLoad.payload.element
        aLoaded(subrow)(offset) := True
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
    issuedBits(subrow) := fifos.io.pops(subrow).fire
  }
  val acceptedThisCycle = CountOne(acceptedBits).resize(32)
  when(acceptedThisCycle =/= 0) {
    acceptedBElements := acceptedBElements + acceptedThisCycle
  }

  val anyFifoValid = fifos.io.pops.map(_.valid).reduce(_ || _)
  val allHeadsAreFinal = (0 until subrows).map { subrow =>
    fifos.io.occupancy(subrow) <= 1
  }.reduce(_ && _)
  // The finish marker is attached to the last non-empty temporal issue when
  // possible.  An empty terminal command is only needed for an empty tile.
  val issueFinal = running && finishSeen &&
    ((!anyFifoValid) || allHeadsAreFinal)
  val scheduledCommand = PERowCommand(cfg)
  scheduledCommand.mode := TrapezoidDataflow.TrGt
  scheduledCommand.endOfTile := issueFinal
  scheduledCommand.trip := TrIpPayload(cfg).getZero
  for (subrow <- 0 until subrows;
       offset <- 0 until cfg.macLanesPerSubrow) {
    val index = subrow * cfg.macLanesPerSubrow + offset
    scheduledCommand.gustavson.a(index).valid :=
      aLoaded(subrow)(offset) && aStorage(subrow)(offset).valid
    scheduledCommand.gustavson.a(index).row := aStorage(subrow)(offset).row
    scheduledCommand.gustavson.a(index).k := aStorage(subrow)(offset).k
    scheduledCommand.gustavson.a(index).value :=
      aStorage(subrow)(offset).value
  }
  for (lane <- 0 until cfg.cacheLineWords) {
    if (lane < subrows) {
      scheduledCommand.gustavson.b(lane).valid := fifos.io.pops(lane).valid
      scheduledCommand.gustavson.b(lane).aIndex := fifos.io.pops(lane).payload.aIndex
      scheduledCommand.gustavson.b(lane).k := fifos.io.pops(lane).payload.k
      scheduledCommand.gustavson.b(lane).column := fifos.io.pops(lane).payload.column
      scheduledCommand.gustavson.b(lane).value := fifos.io.pops(lane).payload.value
    } else {
      scheduledCommand.gustavson.b(lane) := GustavsonB(cfg).getZero
    }
  }

  engine.io.command.valid := !io.clear && running && (anyFifoValid || issueFinal)
  engine.io.command.payload := scheduledCommand
  for (subrow <- 0 until subrows) {
    fifos.io.pops(subrow).ready := engine.io.command.fire && anyFifoValid
  }
  val issuedThisCycle = CountOne(issuedBits).resize(32)
  when(issuedThisCycle =/= 0) {
    issuedBElements := issuedBElements + issuedThisCycle
  }
  when(engine.io.command.fire && issueFinal) {
    running := False
    finishSeen := False
    // A remains stationary during the tile.  The next A load starts a new
    // temporal tile and invalidates the previous register contents.
    aLoadRequiresReset := True
  }

  when(io.clear) {
    for (subrow <- 0 until subrows;
         offset <- 0 until cfg.macLanesPerSubrow) {
      aLoaded(subrow)(offset) := False
    }
    running := False
    finishSeen := False
    aLoadRequiresReset := True
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
  private val responseIndexWidth = scala.math.max(1, log2Up(cfg.outputLanes))
  private val bankIndexWidth = log2Up(cfg.localBanks)
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
    val accumulatorCycles = out UInt(32 bits)
    val accumulatorUpdates = out UInt(32 bits)
    val bankReplayCycles = out UInt(32 bits)
  }

  val state = RegInit(TrGsSpatialState.Idle)
  val inputClosed = RegInit(False)
  val doneReg = RegInit(False)
  val overflowReg = RegInit(False)
  val modeErrorReg = RegInit(False)
  val inFlight = Reg(UInt(inFlightWidth bits)) init 0
  val responseActive = RegInit(False)
  val responseReg = Reg(PERowResponse(cfg))
  val pendingResults = Reg(Bits(cfg.outputLanes bits)) init 0
  val outputCursor = Reg(UInt(tileCfg.entryIndexWidth bits)) init 0
  val outputGroupCursor = Reg(UInt(writebackGroupIndexWidth bits)) init 0
  val tileRow = Reg(UInt(cfg.coordinateWidth bits)) init 0
  val tileColumnBase = Reg(UInt(cfg.coordinateWidth bits)) init 0
  val columnBlockValid = RegInit(False)
  val accumulatorCycles = Reg(UInt(32 bits)) init 0
  val accumulatorUpdates = Reg(UInt(32 bits)) init 0
  val bankReplayCycles = Reg(UInt(32 bits)) init 0

  private val accumulatorWordsPerBank = slots / cfg.localBanks

  // TrGS is spatial: output columns are distributed over the four local
  // banks, with consecutive words in a bank holding the remaining column
  // index.  The logical slot number is still used at the command boundary so
  // the existing result protocol remains unchanged.
  val slotValid = Array.fill(cfg.localBanks)(
    Vec((0 until accumulatorWordsPerBank).map(_ => RegInit(False))))
  val slotValue = Array.fill(cfg.localBanks)(
    Vec((0 until accumulatorWordsPerBank).map(_ =>
      Reg(SInt(cfg.accumulatorWidth bits)) init 0)))

  def slotBank(entry: Int): Int = entry % cfg.localBanks
  def slotWord(entry: Int): Int = entry / cfg.localBanks
  def slotValidAt(entry: Int): Bool = slotValid(slotBank(entry))(slotWord(entry))
  def slotValueAt(entry: Int): SInt = slotValue(slotBank(entry))(slotWord(entry))

  val slotValidBits = Bits(slots bits)
  for (entry <- 0 until slots) {
    slotValidBits(entry) := slotValidAt(entry)
  }

  io.busy := state =/= TrGsSpatialState.Idle
  io.done := doneReg
  io.overflow := overflowReg
  io.unsupportedMode := modeErrorReg
  io.accumulatorCycles := accumulatorCycles
  io.accumulatorUpdates := accumulatorUpdates
  io.bankReplayCycles := bankReplayCycles

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
  pe.io.response.ready := !io.clear &&
    (state === TrGsSpatialState.AbortDrain ||
      (state === TrGsSpatialState.Run && !responseActive))
  val commandColumnChoices = Vec(Bool(), cfg.cacheLineWords)
  var earlierCommandColumn: Bool = False
  for (lane <- 0 until cfg.cacheLineWords) {
    commandColumnChoices(lane) := io.command.payload.b(lane).valid &&
      !earlierCommandColumn
    earlierCommandColumn = earlierCommandColumn || io.command.payload.b(lane).valid
  }
  val commandHasColumn = commandColumnChoices.asBits.orR
  val commandFirstColumn = UInt(cfg.coordinateWidth bits)
  commandFirstColumn := 0
  for (lane <- 0 until cfg.cacheLineWords) {
    when(commandColumnChoices(lane)) {
      commandFirstColumn := io.command.payload.b(lane).column
    }
  }
  val commandColumnBase = UInt(cfg.coordinateWidth bits)
  commandColumnBase := commandFirstColumn
  commandColumnBase(tileCfg.entryIndexWidth - 1 downto 0) := 0
  var commandColumnMismatch: Bool = False
  for (lane <- 0 until cfg.cacheLineWords) {
    val laneColumnBase = UInt(cfg.coordinateWidth bits)
    laneColumnBase := io.command.payload.b(lane).column
    laneColumnBase(tileCfg.entryIndexWidth - 1 downto 0) := 0
    commandColumnMismatch = commandColumnMismatch ||
      (io.command.payload.b(lane).valid && laneColumnBase =/= commandColumnBase)
  }

  val bankGrants = Vec(Bits(cfg.outputLanes bits), cfg.localBanks)
  for (bank <- 0 until cfg.localBanks) {
    var earlierMatch: Bool = False
    for (lane <- 0 until cfg.outputLanes) {
      val result = responseReg.results(lane)
      val bankMatches = pendingResults(lane) && result.valid &&
        result.column(bankIndexWidth - 1 downto 0) === bank
      bankGrants(bank)(lane) := bankMatches && !earlierMatch
      earlierMatch = earlierMatch || bankMatches
    }
  }

  val issuedResults = Bits(cfg.outputLanes bits)
  for (lane <- 0 until cfg.outputLanes) {
    issuedResults(lane) := (0 until cfg.localBanks)
      .map(bank => bankGrants(bank)(lane)).reduce(_ || _)
  }
  val remainingResults = pendingResults & ~issuedResults
  val capturedResults = Bits(cfg.outputLanes bits)
  for (lane <- 0 until cfg.outputLanes) {
    capturedResults(lane) := pe.io.response.payload.results(lane).valid
  }
  val responseDrained = responseActive &&
    (!pendingResults.orR || !remainingResults.orR)
  val grantedNewEntry = Bits(cfg.localBanks bits)
  val grantedUpdates = Bits(cfg.localBanks bits)
  grantedNewEntry := 0
  grantedUpdates := 0

  for (bank <- 0 until cfg.localBanks) {
    val grantLane = UInt(responseIndexWidth bits)
    grantLane := 0
    for (lane <- 0 until cfg.outputLanes) {
      when(bankGrants(bank)(lane)) { grantLane := lane }
    }
    val grantFound = bankGrants(bank).orR
    val result = responseReg.results(grantLane)
    val slot = result.column(tileCfg.entryIndexWidth - 1 downto 0)
    val expectedColumn = UInt(cfg.coordinateWidth bits)
    expectedColumn := tileColumnBase | slot.resize(cfg.coordinateWidth)
    val coordinateCollision = tileRow =/= result.row ||
      expectedColumn =/= result.column
    val selectedSlotValid = Bool()
    selectedSlotValid := False
    for (entry <- 0 until slots) {
      when(slot === entry) { selectedSlotValid := slotValidAt(entry) }
    }
    grantedNewEntry(bank) := responseActive && grantFound &&
      !coordinateCollision && !selectedSlotValid
    grantedUpdates(bank) := responseActive && grantFound &&
      !coordinateCollision && !responseReg.overflow

    when(responseActive && grantFound && !responseReg.overflow) {
      when(coordinateCollision) {
        overflowReg := True
      } otherwise {
        for (entry <- 0 until slots) {
          when(slot === entry) {
            when(slotValidAt(entry)) {
              slotValueAt(entry) :=
                (slotValueAt(entry) + result.value).resized
            } otherwise {
              slotValidAt(entry) := True
              slotValueAt(entry) := result.value
            }
          }
        }
      }
    }
  }
  val outputChoices = Vec(Bool(), slots)
  var earlierOutput: Bool = False
  for (entry <- 0 until slots) {
    val eligible = slotValidAt(entry) &&
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
    laterOutput(entry) := slotValidAt(entry) &&
      U(entry, tileCfg.entryIndexWidth bits) > outputSlot
  }

  val selectedOutputValue = SInt(cfg.accumulatorWidth bits)
  selectedOutputValue := 0
  for (entry <- 0 until slots) {
    when(outputSlot === entry) {
      selectedOutputValue := slotValueAt(entry)
    }
  }

  io.result.payload.valid := True
  io.result.payload.row := tileRow
  io.result.payload.column := tileColumnBase |
    outputSlot.resize(cfg.coordinateWidth)
  io.result.payload.value := selectedOutputValue
  io.result.payload.endOfTile := !laterOutput.asBits.orR

  val lineGroupValid = Vec(Bool(), writebackGroups)
  for (group <- 0 until writebackGroups) {
    lineGroupValid(group) := (0 until cfg.cacheLineWords)
      .map(lane => slotValidAt(group * cfg.cacheLineWords + lane))
      .reduce(_ || _)
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
        lineValids(lane) := slotValidAt(entry)
        io.resultLine.payload.results(lane).valid := slotValidAt(entry)
        io.resultLine.payload.results(lane).row := tileRow
        io.resultLine.payload.results(lane).column := tileColumnBase |
          U(entry, cfg.coordinateWidth bits)
        io.resultLine.payload.results(lane).value := slotValueAt(entry)
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
  when(responseFire && state === TrGsSpatialState.Run) {
    responseReg := pe.io.response.payload
    pendingResults := capturedResults
    responseActive := True
  } elsewhen (responseActive) {
    pendingResults := remainingResults
    when(responseDrained) {
      responseActive := False
      pendingResults := 0
    }
  }
  when(responseActive && pendingResults.orR) {
    accumulatorCycles := accumulatorCycles + 1
    accumulatorUpdates := accumulatorUpdates + CountOne(grantedUpdates).resize(32)
    when(remainingResults.orR) {
      bankReplayCycles := bankReplayCycles + 1
    }
  }

  switch(state) {
    is(TrGsSpatialState.Idle) {
      when(io.command.fire) {
        doneReg := False
        when(supportedMode) {
          for (entry <- 0 until slots) slotValidAt(entry) := False
          tileRow := io.command.payload.a.row
          tileColumnBase := commandColumnBase
          columnBlockValid := commandHasColumn
          overflowReg := commandColumnMismatch
          modeErrorReg := False
          accumulatorCycles := 0
          accumulatorUpdates := 0
          bankReplayCycles := 0
          inputClosed := io.command.payload.endOfTile
          state := TrGsSpatialState.Run
        } otherwise {
          modeErrorReg := True
          doneReg := True
        }
      }
    }

    is(TrGsSpatialState.Run) {
      when(commandFire) {
        when(io.command.payload.a.row =/= tileRow || commandColumnMismatch ||
            (columnBlockValid && commandHasColumn &&
              commandColumnBase =/= tileColumnBase)) {
          overflowReg := True
        }
        when(!columnBlockValid && commandHasColumn) {
          tileColumnBase := commandColumnBase
          columnBlockValid := True
        }
      }
      when(commandFire && io.command.payload.endOfTile) {
        inputClosed := True
      }
      when(responseActive && responseReg.overflow) {
        overflowReg := True
      }
      when(responseDrained) {
        when(responseReg.results(0).endOfTile) {
          inputClosed := False
          when(slotValidBits.orR ||
              grantedNewEntry.orR) {
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
            for (entry <- 0 until slots) slotValidAt(entry) := False
            doneReg := True
            state := TrGsSpatialState.Idle
          } otherwise {
            outputGroupCursor := lineGroup + 1
          }
        }
      } else {
        when(io.result.fire) {
          when(io.result.payload.endOfTile) {
            for (entry <- 0 until slots) slotValidAt(entry) := False
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
    for (entry <- 0 until slots) slotValidAt(entry) := False
    columnBlockValid := False
    inputClosed := False
    doneReg := False
    overflowReg := False
    modeErrorReg := False
    accumulatorCycles := 0
    accumulatorUpdates := 0
    bankReplayCycles := 0
    responseActive := False
    pendingResults := 0
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
    val accumulatorCycles = out Vec(UInt(32 bits), cfg.peRows)
    val accumulatorUpdates = out Vec(UInt(32 bits), cfg.peRows)
    val bankReplayCycles = out Vec(UInt(32 bits), cfg.peRows)
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
    io.accumulatorCycles(row) := engines(row).io.accumulatorCycles
    io.accumulatorUpdates(row) := engines(row).io.accumulatorUpdates
    io.bankReplayCycles(row) := engines(row).io.bankReplayCycles
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
