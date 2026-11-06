package trapezoid

import spinal.core._
import spinal.lib._

case class GustavsonAccumulatorEntry(cfg: TrapezoidConfig) extends Bundle {
  val row = UInt(cfg.coordinateWidth bits)
  val column = UInt(cfg.coordinateWidth bits)
  val value = SInt(cfg.accumulatorWidth bits)
}

object StreamingGustavsonState extends SpinalEnum {
  val Accept, WaitResponse, MergePrepare, MergeLoadOld, MergeCompare,
      OutputLoad, OutputEmit, AbortDrain = newElement()
}

/** Ordered streaming accumulator for TrGT/TrGS PE responses.
  *
  * The active sorted stream and one sorted PE response are merged into the
  * inactive buffer with one comparator/adder. Entries are interleaved across
  * the four PE-local SRAM banks. The two buffers alternate after every command,
  * removing the legacy compare-all/shift-all insertion network.
  */
class GustavsonTileEngine(
    tileCfg: GustavsonTileConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    externalPe: Boolean = false
) extends Component {
  private val cfg = tileCfg.core
  private val bankCount = cfg.localBanks
  private val bankBits = log2Up(bankCount)
  private val entriesPerBank = tileCfg.accumulatorEntries / bankCount
  private val bankAddressWidth = scala.math.max(1, log2Up(entriesPerBank))
  private val responseIndexWidth = cfg.outputCountWidth
  private val trGtHeadFifoDepth = scala.math.max(8, cfg.outputLanes * 2)
  private val trGtHeadPtrWidth = scala.math.max(1, log2Up(trGtHeadFifoDepth))
  private val trGtHeadCountWidth = log2Up(trGtHeadFifoDepth + 1)
  private val trGtOutstandingDepth = 4
  private val trGtOutstandingPtrWidth = scala.math.max(1,
    log2Up(trGtOutstandingDepth))
  private val trGtOutstandingCountWidth = log2Up(trGtOutstandingDepth + 1)
  require(tileCfg.accumulatorEntries % bankCount == 0,
    "streaming accumulator entries must divide across local banks")

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(PERowCommand(cfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val overflow = out Bool()
    val unsupportedMode = out Bool()
    val trGtDrainActive = out Bool()
    val activeEntries = out UInt(tileCfg.entryCountWidth bits)
    val peCommand = if (externalPe) master(Stream(PERowCommand(cfg))) else null
    val peResponse = if (externalPe) slave(Stream(PERowResponse(cfg))) else null
    val peTemporalMrnInput = if (externalPe)
      master(Stream(DualModeMrnRequest(cfg))) else null
    val peTemporalMrnOutput = if (externalPe)
      slave(Stream(DualModeMrnResponse(cfg))) else null
  }

  val state = RegInit(StreamingGustavsonState.Accept)
  val debugState = UInt(4 bits)
  debugState := state.asBits.asUInt.resize(4)
  val tileOpen = RegInit(False)
  val doneReg = RegInit(False)
  val overflowReg = RegInit(False)
  val modeErrorReg = RegInit(False)
  val commandInFlight = RegInit(False)
  val inputDone = RegInit(False)
  val mergingTrGt = RegInit(False)
  val activeBuffer = RegInit(False)
  val entryCount = Reg(UInt(tileCfg.entryCountWidth bits)) init 0
  val oldCount = Reg(UInt(tileCfg.entryCountWidth bits)) init 0
  val oldIndex = Reg(UInt(tileCfg.entryCountWidth bits)) init 0
  val newIndex = Reg(UInt(responseIndexWidth bits)) init 0
  val writeIndex = Reg(UInt(tileCfg.entryCountWidth bits)) init 0
  val outputIndex = Reg(UInt(tileCfg.entryIndexWidth bits)) init 0
  val oldEntry = Reg(GustavsonAccumulatorEntry(cfg))
  val oldEntryValid = RegInit(False)
  val oldNextEntry = Reg(GustavsonAccumulatorEntry(cfg))
  val oldNextValid = RegInit(False)
  val oldFetchIndex = Reg(UInt(tileCfg.entryCountWidth bits)) init 0
  val oldReadPending = RegInit(False)
  // The temporal MRN response owns one elastic stage.  Track accepted merge
  // decisions independently from committed SRAM writes so the next pair of
  // stream heads can enter while the previous result is written back.
  val temporalOutstanding = Reg(UInt(2 bits)) init 0
  val mergeCommitEntry = Reg(GustavsonAccumulatorEntry(cfg))
  val mergeCommitValid = RegInit(False)
  val responseReg = Reg(PERowResponse(cfg))
  // One synchronous SRAM queue per TrGT subrow extends head lifetime across
  // multiple PE responses. The SRAM read is followed by one elastic head
  // register, so the radix-K selector never observes a changing memory port.
  val trGtHeadMemories = Array.fill(bankCount)(
    Mem(Bits(GustavsonAccumulatorEntry(cfg).getBitsWidth bits),
      trGtHeadFifoDepth))
  val trGtHeadFifoHeads = Vec((0 until bankCount).map(_ =>
    Reg(UInt(trGtHeadPtrWidth bits)) init 0))
  val trGtHeadFifoTails = Vec((0 until bankCount).map(_ =>
    Reg(UInt(trGtHeadPtrWidth bits)) init 0))
  val trGtHeadFifoCounts = Vec((0 until bankCount).map(_ =>
    Reg(UInt(trGtHeadCountWidth bits)) init 0))
  val trGtHeadValids = Vec((0 until bankCount).map(_ => RegInit(False)))
  val trGtHeadStoredCounts = Vec((0 until bankCount).map(_ =>
    Reg(UInt(trGtHeadCountWidth bits)) init 0))
  val trGtCommandTags = Vec((0 until trGtOutstandingDepth).map(_ =>
    Reg(UInt(cfg.responseTagWidth bits)) init 0))
  val trGtCommandTagHead = Reg(UInt(trGtOutstandingPtrWidth bits)) init 0
  val trGtCommandTagTail = Reg(UInt(trGtOutstandingPtrWidth bits)) init 0
  val trGtCommandTagCount = Reg(UInt(trGtOutstandingCountWidth bits)) init 0
  // Reserve one possible head in every source FIFO for each issued command.
  // Near capacity, finish retiring the tagged batch before issuing again. The
  // array scheduler already performs the per-subrow radix-K B-stream merge, so
  // this drain is only a bounded capacity barrier, not a B-row order epoch.
  val trGtDrainEpoch = RegInit(False)
  val trGtLastIssuedValid = Vec((0 until bankCount).map(_ => RegInit(False)))
  val trGtLastIssuedRow = Vec((0 until bankCount).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val trGtLastIssuedColumn = Vec((0 until bankCount).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val readBankReg = Reg(UInt(bankBits bits)) init 0
  val readBufferReg = RegInit(False)

  val memories = Array.fill(2, bankCount)(
    Mem(Bits(GustavsonAccumulatorEntry(cfg).getBitsWidth bits), entriesPerBank))

  def logicalBank(index: UInt): UInt =
    index(bankBits - 1 downto 0).resize(bankBits)
  def logicalAddress(index: UInt): UInt =
    (index >> bankBits).resize(bankAddressWidth)

  val oldReadIssue = Bool()
  val oldReadArrivalConsumed = Bool()
  oldReadIssue := False
  oldReadArrivalConsumed := False

  val readEnable = Bool()
  val readIndex = UInt(tileCfg.entryIndexWidth bits)
  readEnable := oldReadIssue
  readIndex := oldFetchIndex.resize(tileCfg.entryIndexWidth)
  when(state === StreamingGustavsonState.OutputLoad) {
    readEnable := True
    readIndex := outputIndex
  }
  when(readEnable) {
    readBankReg := logicalBank(readIndex)
    readBufferReg := activeBuffer
  }

  val readData = Array.fill(2, bankCount)(
    Bits(GustavsonAccumulatorEntry(cfg).getBitsWidth bits))
  for (buffer <- 0 until 2; bank <- 0 until bankCount) {
    readData(buffer)(bank) := memories(buffer)(bank).readSync(
      logicalAddress(readIndex),
      readEnable && activeBuffer === (if (buffer == 1) True else False) &&
        logicalBank(readIndex) === bank)
  }
  val selectedReadData = Bits(GustavsonAccumulatorEntry(cfg).getBitsWidth bits)
  selectedReadData := 0
  for (buffer <- 0 until 2; bank <- 0 until bankCount) {
    when(readBufferReg === (if (buffer == 1) True else False) &&
        readBankReg === bank) {
      selectedReadData := readData(buffer)(bank)
    }
  }
  val selectedReadEntry = GustavsonAccumulatorEntry(cfg)
  selectedReadEntry.assignFromBits(selectedReadData)

  val writeEnable = Bool()
  val writeEntry = GustavsonAccumulatorEntry(cfg)
  writeEnable := False
  writeEntry := GustavsonAccumulatorEntry(cfg).getZero
  for (buffer <- 0 until 2; bank <- 0 until bankCount) {
    when(writeEnable && activeBuffer =/= (if (buffer == 1) True else False) &&
        logicalBank(writeIndex.resize(tileCfg.entryIndexWidth)) === bank) {
      memories(buffer)(bank).write(
        logicalAddress(writeIndex.resize(tileCfg.entryIndexWidth)),
        writeEntry.asBits)
    }
  }

  val supportedMode = io.command.payload.mode === TrapezoidDataflow.TrGt ||
    io.command.payload.mode === TrapezoidDataflow.TrGs
  val peCommandReady = Bool()
  val peResponseValid = Bool()
  val peResponsePayload = PERowResponse(cfg)
  val peResponseReady = Bool()
  val mergeResponseWindow = Bool()
  val temporalMrnInput = Stream(DualModeMrnRequest(cfg))
  val temporalMrnOutput = Stream(DualModeMrnResponse(cfg))
  val trGtTagAvailable = trGtCommandTagCount =/= 0
  val trGtTagMatch = trGtTagAvailable &&
    peResponsePayload.responseTag === trGtCommandTags(trGtCommandTagHead)
  val trGtHeadReservationAvailable = (0 until bankCount).map { source =>
    trGtHeadFifoCounts(source).resize(trGtHeadCountWidth + 1) +
      trGtCommandTagCount.resize(trGtHeadCountWidth + 1) <
      U(trGtHeadFifoDepth, trGtHeadCountWidth + 1 bits)
  }.reduce(_ && _)
  val trGtCommandHeadValid = Vec(Bool(), bankCount)
  val trGtCommandHeadRow = Vec(UInt(cfg.coordinateWidth bits), bankCount)
  val trGtCommandHeadColumn = Vec(UInt(cfg.coordinateWidth bits), bankCount)
  val trGtCommandOrderRegresses = Bits(bankCount bits)
  for (source <- 0 until bankCount) {
    val b = io.command.payload.gustavson.b(source)
    val a = io.command.payload.gustavson.a(b.aIndex)
    trGtCommandHeadValid(source) := b.valid && a.valid && a.k === b.k
    trGtCommandHeadRow(source) := a.row
    trGtCommandHeadColumn(source) := b.column
    trGtCommandOrderRegresses(source) := trGtLastIssuedValid(source) &&
      trGtCommandHeadValid(source) &&
      (a.row < trGtLastIssuedRow(source) ||
        (a.row === trGtLastIssuedRow(source) &&
          b.column < trGtLastIssuedColumn(source)))
  }
  // Direct PE clients may still present an unordered stream. Keep a
  // correctness fallback for those clients; the array-level TrGT scheduler is
  // expected to keep this condition false by merging its K virtual B FIFOs.
  val trGtCommandStartsNewEpoch =
    io.command.payload.mode === TrapezoidDataflow.TrGt &&
      trGtCommandOrderRegresses.orR
  val trGtCommandWindow = state === StreamingGustavsonState.MergeCompare &&
    mergingTrGt && !inputDone && !trGtDrainEpoch &&
    trGtHeadReservationAvailable && !trGtCommandStartsNewEpoch &&
    io.command.payload.mode === TrapezoidDataflow.TrGt &&
    trGtCommandTagCount < U(trGtOutstandingDepth,
      trGtOutstandingCountWidth bits)
  val commandWindow = state === StreamingGustavsonState.Accept ||
    trGtCommandWindow
  val peCommandValid = !io.clear && commandWindow &&
    io.command.valid && supportedMode
  val peCommandPayload = PERowCommand(cfg)
  peCommandPayload.mode := io.command.payload.mode
  peCommandPayload.endOfTile := io.command.payload.endOfTile
  peCommandPayload.trip := io.command.payload.trip
  peCommandPayload.gustavson := io.command.payload.gustavson
  peCommandPayload.responseTag := Mux(
    io.command.payload.mode === TrapezoidDataflow.TrGt,
    trGtCommandTagTail.resize(cfg.responseTagWidth),
    U(0, cfg.responseTagWidth bits))
  if (externalPe) {
    io.peCommand.valid := peCommandValid
    io.peCommand.payload := peCommandPayload
    peCommandReady := io.peCommand.ready
    peResponseValid := io.peResponse.valid
    peResponsePayload := io.peResponse.payload
    io.peResponse.ready := peResponseReady
    io.peTemporalMrnInput << temporalMrnInput
    temporalMrnOutput << io.peTemporalMrnOutput
  } else {
    val pe = peFactory(cfg)
    pe.io.command.valid := peCommandValid
    pe.io.command.payload := peCommandPayload
    peCommandReady := pe.io.command.ready
    peResponseValid := pe.io.response.valid
    peResponsePayload := pe.io.response.payload
    pe.io.response.ready := peResponseReady
    pe.io.temporalMrnInputs(0) << temporalMrnInput
    temporalMrnOutput << pe.io.temporalMrnOutputs(0)
    for (port <- 1 until cfg.localBanks) {
      pe.io.temporalMrnInputs(port).valid := False
      pe.io.temporalMrnInputs(port).payload := DualModeMrnRequest(cfg).getZero
      pe.io.temporalMrnOutputs(port).ready := True
    }
  }

  io.command.ready := !io.clear && commandWindow &&
    Mux(supportedMode, peCommandReady, True)
  val trGtResponseSpace = Bool()
  var trGtResponseSpaceExpr: Bool = True
  for (source <- 0 until bankCount) {
    val sourceMatches = Bits(cfg.outputLanes bits)
    for (result <- 0 until cfg.outputLanes) {
      sourceMatches(result) := peResponsePayload.results(result).valid &&
        peResponsePayload.destinationBank(result) === source
    }
    val enqueueCount = CountOne(sourceMatches).resize(trGtHeadCountWidth)
    trGtResponseSpaceExpr = trGtResponseSpaceExpr &&
      (trGtHeadFifoCounts(source) + enqueueCount <=
        U(trGtHeadFifoDepth, trGtHeadCountWidth bits))
  }
  trGtResponseSpace := trGtResponseSpaceExpr
  val trGtResponseCanRetire = trGtTagMatch &&
    (state === StreamingGustavsonState.AbortDrain || trGtResponseSpace)
  peResponseReady := !io.clear &&
    (state === StreamingGustavsonState.WaitResponse ||
      mergeResponseWindow || state === StreamingGustavsonState.AbortDrain) &&
    (!peResponseValid ||
      peResponsePayload.mode =/= TrapezoidDataflow.TrGt ||
        trGtResponseCanRetire)

  io.busy := tileOpen || state =/= StreamingGustavsonState.Accept
  io.done := doneReg
  io.overflow := overflowReg
  io.unsupportedMode := modeErrorReg
  io.trGtDrainActive := trGtDrainEpoch
  io.activeEntries := entryCount
  io.result.valid := state === StreamingGustavsonState.OutputEmit
  io.result.payload.valid := True
  io.result.payload.row := selectedReadEntry.row
  io.result.payload.column := selectedReadEntry.column
  io.result.payload.value := selectedReadEntry.value
  io.result.payload.endOfTile :=
    outputIndex.resize(tileCfg.entryCountWidth) + 1 === entryCount

  val trGtMerge = mergingTrGt
  val trGtCommandFire = io.command.fire && supportedMode &&
    io.command.payload.mode === TrapezoidDataflow.TrGt
  val sequentialNewAvailable =
    newIndex.resize(cfg.outputCountWidth) < responseReg.count
  val newAvailable = Mux(trGtMerge,
    trGtHeadFifoCounts.map(_ =/= 0).reduce(_ || _), sequentialNewAvailable)
  val oldAvailable = (oldEntryValid || oldReadPending) && oldIndex < oldCount
  mergeResponseWindow := state === StreamingGustavsonState.MergeCompare &&
    mergingTrGt && trGtResponseSpace &&
    (oldAvailable || newAvailable || temporalOutstanding =/= 0 ||
      trGtCommandTagCount =/= 0)
  val oldMergeEntry = GustavsonAccumulatorEntry(cfg)
  oldMergeEntry := oldEntry
  when(!oldEntryValid && oldReadPending) {
    oldMergeEntry := selectedReadEntry
  }
  val mergeActive = state === StreamingGustavsonState.MergeCompare
  val trGtHeadPops = Vec(Bool(), bankCount)
  val trGtHeadReadIssues = Vec(Bool(), bankCount)
  val trGtHeadReadData = Array.fill(bankCount)(
    Bits(GustavsonAccumulatorEntry(cfg).getBitsWidth bits))
  val trGtHeadReadEntries = Vec(GustavsonAccumulatorEntry(cfg), bankCount)
  for (source <- 0 until bankCount) {
    trGtHeadReadIssues(source) := !io.clear &&
      trGtHeadStoredCounts(source) =/= 0 &&
      (!trGtHeadValids(source) || trGtHeadPops(source))
    trGtHeadReadData(source) := trGtHeadMemories(source).readSync(
      trGtHeadFifoHeads(source), trGtHeadReadIssues(source))
    trGtHeadReadEntries(source).assignFromBits(trGtHeadReadData(source))
  }

  // TrGT candidates are four independently stateful subrow heads. TrGS keeps
  // the ordered response-window path because one cache line may produce more
  // than one result per local bank.
  private val mergeSourceCount = cfg.localBanks
  val mergeControl = new GustavsonRadixKMrnControl(cfg, mergeSourceCount)
  mergeControl.io.oldHead.valid := oldAvailable && mergeActive
  mergeControl.io.oldHead.row := oldMergeEntry.row
  mergeControl.io.oldHead.column := oldMergeEntry.column
  mergeControl.io.oldHead.value := oldMergeEntry.value
  for (source <- 0 until mergeSourceCount) {
    val sourcePositionWide = newIndex.resize(cfg.outputCountWidth) +
      U(source, cfg.outputCountWidth bits)
    val sourcePosition = sourcePositionWide.resize(
      scala.math.max(1, log2Up(cfg.outputLanes)))
    val sequentialResult = responseReg.results(sourcePosition)
    mergeControl.io.rightHeads(source).valid := mergeActive && Mux(trGtMerge,
      trGtHeadValids(source), sourcePositionWide < responseReg.count)
    mergeControl.io.rightHeads(source).row := Mux(trGtMerge,
      trGtHeadReadEntries(source).row, sequentialResult.row)
    mergeControl.io.rightHeads(source).column := Mux(trGtMerge,
      trGtHeadReadEntries(source).column, sequentialResult.column)
    mergeControl.io.rightHeads(source).value := Mux(trGtMerge,
      trGtHeadReadEntries(source).value, sequentialResult.value)
  }
  // An empty TrGT head is not end-of-stream while a tagged PE response is
  // outstanding: that response may refill the source with a coordinate below
  // the other visible heads.  Hold the radix-K decision until every source is
  // represented, or all responses have retired and an empty source is known
  // to be complete.  This preserves global coordinate order across response
  // boundaries without returning to per-response ping-pong merges.
  val trGtResponsesPending = trGtCommandTagCount =/= 0 || trGtCommandFire
  val trGtSourcesOrdered = (0 until mergeSourceCount).map { source =>
    trGtHeadValids(source) ||
      (!trGtResponsesPending && trGtHeadFifoCounts(source) === 0)
  }.reduce(_ && _)
  val mergeRequestAllowed = !trGtMerge || trGtSourcesOrdered
  temporalMrnInput.valid := mergeControl.io.request.valid &&
    mergeRequestAllowed
  temporalMrnInput.payload := mergeControl.io.request.payload
  mergeControl.io.request.ready := temporalMrnInput.ready &&
    mergeRequestAllowed
  temporalMrnOutput.ready := mergeActive || io.clear ||
    state === StreamingGustavsonState.AbortDrain

  when(mergeActive) {
    switch(temporalMrnInput.fire ## temporalMrnOutput.fire) {
      is(B"10") { temporalOutstanding := temporalOutstanding + 1 }
      is(B"01") { temporalOutstanding := temporalOutstanding - 1 }
    }
  }

  val trGtResponseRetire = peResponseValid && peResponseReady &&
    peResponsePayload.mode === TrapezoidDataflow.TrGt
  val trGtResponseFire = trGtResponseRetire &&
    state =/= StreamingGustavsonState.AbortDrain
  val trGtDequeue = mergeActive && trGtMerge && temporalMrnInput.fire &&
    mergeControl.io.consumeRight

  when(trGtCommandFire) {
    trGtCommandTags(trGtCommandTagTail) :=
      trGtCommandTagTail.resize(cfg.responseTagWidth)
    when(trGtCommandTagTail === trGtOutstandingDepth - 1) {
      trGtCommandTagTail := 0
    } otherwise {
      trGtCommandTagTail := trGtCommandTagTail + 1
    }
    for (source <- 0 until bankCount) {
      when(state === StreamingGustavsonState.Accept) {
        trGtLastIssuedValid(source) := trGtCommandHeadValid(source)
      } elsewhen(trGtCommandHeadValid(source)) {
        trGtLastIssuedValid(source) := True
      }
      when(trGtCommandHeadValid(source)) {
        trGtLastIssuedRow(source) := trGtCommandHeadRow(source)
        trGtLastIssuedColumn(source) := trGtCommandHeadColumn(source)
      }
    }
  }
  when(trGtResponseRetire) {
    assert(trGtTagMatch, "TrGT PE response tag did not match issue order")
    when(trGtCommandTagHead === trGtOutstandingDepth - 1) {
      trGtCommandTagHead := 0
    } otherwise {
      trGtCommandTagHead := trGtCommandTagHead + 1
    }
  }
  switch(trGtCommandFire ## trGtResponseRetire) {
    is(B"10") { trGtCommandTagCount := trGtCommandTagCount + 1 }
    is(B"01") { trGtCommandTagCount := trGtCommandTagCount - 1 }
  }
  when(mergingTrGt && !trGtHeadReservationAvailable) {
    trGtDrainEpoch := True
  }
  when(state === StreamingGustavsonState.MergeCompare && mergingTrGt &&
      io.command.valid && trGtCommandStartsNewEpoch) {
    trGtDrainEpoch := True
  }
  when(state === StreamingGustavsonState.Accept) {
    trGtDrainEpoch := False
  }

  // A response is a packed batch, so compute a stable per-source write
  // position for every lane before updating each FIFO tail. Each source is
  // consumed independently; a packed response is admitted atomically only
  // when every destination FIFO has enough capacity.
  for (source <- 0 until bankCount) {
    val sourceMatches = Bits(cfg.outputLanes bits)
    for (result <- 0 until cfg.outputLanes) {
      sourceMatches(result) := trGtResponseFire &&
        peResponsePayload.results(result).valid &&
        peResponsePayload.destinationBank(result) === source
    }
    val enqueueCount = CountOne(sourceMatches).resize(trGtHeadCountWidth)
    val enqueueEntry = GustavsonAccumulatorEntry(cfg)
    enqueueEntry := GustavsonAccumulatorEntry(cfg).getZero
    for (result <- 0 until cfg.outputLanes) {
      when(sourceMatches(result)) {
        enqueueEntry.row := peResponsePayload.results(result).row
        enqueueEntry.column := peResponsePayload.results(result).column
        enqueueEntry.value := peResponsePayload.results(result).value
      }
    }
    val dequeueSource = trGtDequeue &&
      mergeControl.io.selectedRightIndex === source
    trGtHeadPops(source) := dequeueSource
    val enqueueSource = sourceMatches.orR
    trGtHeadMemories(source).write(
      trGtHeadFifoTails(source), enqueueEntry.asBits,
      enable = enqueueSource)
    when(trGtResponseFire) {
      assert(enqueueCount <= 1,
        "one TrGT response produced multiple heads for one subrow")
    }
    when(enqueueSource || dequeueSource) {
      val nextCount = trGtHeadFifoCounts(source).resize(trGtHeadCountWidth) +
        Mux(enqueueSource, U(1, trGtHeadCountWidth bits),
          U(0, trGtHeadCountWidth bits)) -
        Mux(dequeueSource, U(1, trGtHeadCountWidth bits),
          U(0, trGtHeadCountWidth bits))
      trGtHeadFifoCounts(source) := nextCount.resized
    }
    when(enqueueSource) {
      when(trGtHeadFifoTails(source) === trGtHeadFifoDepth - 1) {
        trGtHeadFifoTails(source) := 0
      } otherwise {
        trGtHeadFifoTails(source) := trGtHeadFifoTails(source) + 1
      }
    }
    when(enqueueSource || trGtHeadReadIssues(source)) {
      val nextStored = trGtHeadStoredCounts(source) +
        Mux(enqueueSource, U(1, trGtHeadCountWidth bits),
          U(0, trGtHeadCountWidth bits)) -
        Mux(trGtHeadReadIssues(source), U(1, trGtHeadCountWidth bits),
          U(0, trGtHeadCountWidth bits))
      trGtHeadStoredCounts(source) := nextStored.resized
    }
    when(trGtHeadReadIssues(source)) {
      when(trGtHeadFifoHeads(source) === trGtHeadFifoDepth - 1) {
        trGtHeadFifoHeads(source) := 0
      } otherwise {
        trGtHeadFifoHeads(source) := trGtHeadFifoHeads(source) + 1
      }
    }
    trGtHeadValids(source) :=
      (trGtHeadValids(source) && !dequeueSource) ||
        trGtHeadReadIssues(source)
  }

  switch(state) {
    is(StreamingGustavsonState.Accept) {
      when(io.command.fire) {
        doneReg := False
        when(supportedMode) {
          modeErrorReg := False
          commandInFlight := io.command.payload.mode ===
            TrapezoidDataflow.TrGs
          inputDone := io.command.payload.endOfTile
          when(!tileOpen) {
            entryCount := 0
            overflowReg := False
            activeBuffer := False
          }
          tileOpen := True
          state := StreamingGustavsonState.WaitResponse
        } otherwise {
          entryCount := 0
          tileOpen := False
          overflowReg := False
          modeErrorReg := True
          doneReg := True
        }
      }
    }

    is(StreamingGustavsonState.WaitResponse) {
      when(peResponseValid && peResponseReady) {
        responseReg := peResponsePayload
        when(peResponsePayload.mode === TrapezoidDataflow.TrGs) {
          commandInFlight := False
        }
        mergingTrGt := peResponsePayload.mode === TrapezoidDataflow.TrGt
        overflowReg := overflowReg || peResponsePayload.overflow
        oldCount := entryCount
        oldIndex := 0
        newIndex := 0
        writeIndex := 0
        oldEntryValid := False
        oldNextValid := False
        oldFetchIndex := 0
        oldReadPending := False
        temporalOutstanding := 0
        state := StreamingGustavsonState.MergePrepare
      }
    }

    is(StreamingGustavsonState.MergePrepare) {
      when(oldCount === 0) {
        state := StreamingGustavsonState.MergeCompare
      } otherwise {
        oldReadIssue := True
        state := StreamingGustavsonState.MergeLoadOld
      }
    }

    is(StreamingGustavsonState.MergeLoadOld) {
      oldEntry := selectedReadEntry
      oldEntryValid := True
      oldReadArrivalConsumed := True
      when(oldFetchIndex < oldCount) {
        oldReadIssue := True
      }
      state := StreamingGustavsonState.MergeCompare
    }

    is(StreamingGustavsonState.MergeCompare) {
      when(io.command.fire) {
        inputDone := io.command.payload.endOfTile
      }
      when(trGtResponseFire) {
        overflowReg := overflowReg || peResponsePayload.overflow
      }
      when(!oldAvailable && !newAvailable && temporalOutstanding === 0 &&
          trGtCommandTagCount === 0 && !trGtCommandFire) {
        when(mergeCommitValid) {
          val canStore = writeIndex < tileCfg.accumulatorEntries
          writeEnable := canStore
          writeEntry := mergeCommitEntry
          when(canStore) {
            writeIndex := writeIndex + 1
          } otherwise {
            overflowReg := True
          }
          mergeCommitValid := False
        } otherwise {
          entryCount := writeIndex
          activeBuffer := !activeBuffer
          when(inputDone) {
            when(writeIndex === 0) {
              tileOpen := False
              doneReg := True
              state := StreamingGustavsonState.Accept
            } otherwise {
              outputIndex := 0
              state := StreamingGustavsonState.OutputLoad
            }
          } otherwise {
            state := StreamingGustavsonState.Accept
          }
        }
      } otherwise {
        when(temporalMrnOutput.fire) {
          val incoming = temporalMrnOutput.payload.first
          val sameCommitCoordinate = mergeCommitValid && incoming.valid &&
            mergeCommitEntry.row === incoming.row &&
            mergeCommitEntry.column === incoming.column
          when(incoming.valid) {
            when(!mergeCommitValid) {
              mergeCommitEntry.row := incoming.row
              mergeCommitEntry.column := incoming.column
              mergeCommitEntry.value := incoming.value
              mergeCommitValid := True
            } elsewhen(sameCommitCoordinate) {
              mergeCommitEntry.value :=
                (mergeCommitEntry.value + incoming.value).resized
            } otherwise {
              val canStore = writeIndex < tileCfg.accumulatorEntries
              writeEnable := canStore
              writeEntry := mergeCommitEntry
              when(canStore) {
                writeIndex := writeIndex + 1
              } otherwise {
                overflowReg := True
              }
              mergeCommitEntry.row := incoming.row
              mergeCommitEntry.column := incoming.column
              mergeCommitEntry.value := incoming.value
              mergeCommitValid := True
            }
          }
        }

        // The comparator decision is a control lookahead for the registered
        // MRN result.  Advancing on request acceptance, instead of waiting for
        // the response, allows one ordered merge decision per cycle.
        when(temporalMrnInput.fire) {
          when(mergeControl.io.consumeRight) {
            when(!trGtMerge) {
              newIndex := (newIndex +
                mergeControl.io.selectedRightIndex.resize(responseIndexWidth) +
                1).resized
            }
          }
          when(oldReadPending) {
            oldReadArrivalConsumed := True
          }
          when(mergeControl.io.consumeLeft) {
            when(oldEntryValid) {
              when(oldNextValid) {
                oldEntry := oldNextEntry
                oldEntryValid := True
                oldNextValid := False
                when(oldReadPending) {
                  oldNextEntry := selectedReadEntry
                  oldNextValid := True
                }
              } elsewhen(oldReadPending) {
                oldEntry := selectedReadEntry
                oldEntryValid := True
              } otherwise {
                oldEntryValid := False
              }
            } otherwise {
              oldEntryValid := False
            }
            when(oldIndex + 1 < oldCount) {
              oldIndex := oldIndex + 1
            } otherwise {
              oldIndex := oldCount
            }
            when(oldFetchIndex < oldCount) {
              oldReadIssue := True
            }
          } otherwise {
            when(oldReadPending) {
              when(oldEntryValid) {
                oldNextEntry := selectedReadEntry
                oldNextValid := True
              } otherwise {
                oldEntry := selectedReadEntry
                oldEntryValid := True
                when(oldFetchIndex < oldCount) {
                  oldReadIssue := True
                }
              }
            }
          }
        }
      }
    }

    is(StreamingGustavsonState.OutputLoad) {
      state := StreamingGustavsonState.OutputEmit
    }

    is(StreamingGustavsonState.OutputEmit) {
      when(io.result.ready) {
        when(io.result.payload.endOfTile) {
          entryCount := 0
          tileOpen := False
          doneReg := True
          state := StreamingGustavsonState.Accept
        } otherwise {
          outputIndex := outputIndex + 1
          state := StreamingGustavsonState.OutputLoad
        }
      }
    }

    is(StreamingGustavsonState.AbortDrain) {
      when(!commandInFlight && trGtCommandTagCount === 0) {
        state := StreamingGustavsonState.Accept
      }
    }
  }

  when(oldReadIssue) {
    oldFetchIndex := oldFetchIndex + 1
    oldReadPending := True
  } elsewhen(oldReadArrivalConsumed) {
    oldReadPending := False
  }

  when(state === StreamingGustavsonState.AbortDrain &&
      peResponseValid && peResponseReady &&
      peResponsePayload.mode === TrapezoidDataflow.TrGs) {
    commandInFlight := False
  }

  when(io.clear) {
    entryCount := 0
    tileOpen := False
    doneReg := False
    overflowReg := False
    modeErrorReg := False
    oldEntryValid := False
    oldNextValid := False
    oldFetchIndex := 0
    oldReadPending := False
    temporalOutstanding := 0
    mergeCommitValid := False
    trGtDrainEpoch := False
    inputDone := False
    mergingTrGt := False
    for (source <- 0 until bankCount) {
      trGtHeadFifoHeads(source) := 0
      trGtHeadFifoTails(source) := 0
      trGtHeadFifoCounts(source) := 0
      trGtHeadStoredCounts(source) := 0
      trGtHeadValids(source) := False
      trGtLastIssuedValid(source) := False
    }
    when(commandInFlight || trGtCommandTagCount =/= 0) {
      state := StreamingGustavsonState.AbortDrain
    }
      .otherwise { state := StreamingGustavsonState.Accept }
  }
}
