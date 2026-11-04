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
    val activeEntries = out UInt(tileCfg.entryCountWidth bits)
    val peCommand = if (externalPe) master(Stream(PERowCommand(cfg))) else null
    val peResponse = if (externalPe) slave(Stream(PERowResponse(cfg))) else null
    val peTemporalMrnInput = if (externalPe)
      master(Stream(DualModeMrnRequest(cfg))) else null
    val peTemporalMrnOutput = if (externalPe)
      slave(Stream(DualModeMrnResponse(cfg))) else null
  }

  val state = RegInit(StreamingGustavsonState.Accept)
  val tileOpen = RegInit(False)
  val doneReg = RegInit(False)
  val overflowReg = RegInit(False)
  val modeErrorReg = RegInit(False)
  val commandInFlight = RegInit(False)
  val finalCommand = RegInit(False)
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
  val responseReg = Reg(PERowResponse(cfg))
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
  val temporalMrnInput = Stream(DualModeMrnRequest(cfg))
  val temporalMrnOutput = Stream(DualModeMrnResponse(cfg))
  val peCommandValid = !io.clear && state === StreamingGustavsonState.Accept &&
    io.command.valid && supportedMode
  if (externalPe) {
    io.peCommand.valid := peCommandValid
    io.peCommand.payload := io.command.payload
    peCommandReady := io.peCommand.ready
    peResponseValid := io.peResponse.valid
    peResponsePayload := io.peResponse.payload
    io.peResponse.ready := peResponseReady
    io.peTemporalMrnInput << temporalMrnInput
    temporalMrnOutput << io.peTemporalMrnOutput
  } else {
    val pe = peFactory(cfg)
    pe.io.command.valid := peCommandValid
    pe.io.command.payload := io.command.payload
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

  io.command.ready := !io.clear && state === StreamingGustavsonState.Accept &&
    Mux(supportedMode, peCommandReady, True)
  peResponseReady := !io.clear &&
    (state === StreamingGustavsonState.WaitResponse ||
      state === StreamingGustavsonState.AbortDrain)

  io.busy := tileOpen || state =/= StreamingGustavsonState.Accept
  io.done := doneReg
  io.overflow := overflowReg
  io.unsupportedMode := modeErrorReg
  io.activeEntries := entryCount
  io.result.valid := state === StreamingGustavsonState.OutputEmit
  io.result.payload.valid := True
  io.result.payload.row := selectedReadEntry.row
  io.result.payload.column := selectedReadEntry.column
  io.result.payload.value := selectedReadEntry.value
  io.result.payload.endOfTile :=
    outputIndex.resize(tileCfg.entryCountWidth) + 1 === entryCount

  val newAvailable = newIndex.resize(cfg.outputCountWidth) < responseReg.count
  val newEntry = GustavsonAccumulatorEntry(cfg)
  val selectedNewIndex = newIndex.resize(
    scala.math.max(1, log2Up(cfg.outputLanes)))
  newEntry.row := responseReg.results(selectedNewIndex).row
  newEntry.column := responseReg.results(selectedNewIndex).column
  newEntry.value := responseReg.results(selectedNewIndex).value
  val oldAvailable = (oldEntryValid || oldReadPending) && oldIndex < oldCount
  val oldMergeEntry = GustavsonAccumulatorEntry(cfg)
  oldMergeEntry := oldEntry
  when(!oldEntryValid && oldReadPending) {
    oldMergeEntry := selectedReadEntry
  }
  temporalMrnInput.valid := state === StreamingGustavsonState.MergeCompare &&
    (oldAvailable || newAvailable)
  temporalMrnInput.payload.mode := MrnNodeMode.GustavsonMerge
  temporalMrnInput.payload.left.valid := oldAvailable
  temporalMrnInput.payload.left.row := oldMergeEntry.row
  temporalMrnInput.payload.left.column := oldMergeEntry.column
  temporalMrnInput.payload.left.value := oldMergeEntry.value
  temporalMrnInput.payload.right.valid := newAvailable
  temporalMrnInput.payload.right.row := newEntry.row
  temporalMrnInput.payload.right.column := newEntry.column
  temporalMrnInput.payload.right.value := newEntry.value
  temporalMrnOutput.ready := state === StreamingGustavsonState.MergeCompare

  switch(state) {
    is(StreamingGustavsonState.Accept) {
      when(io.command.fire) {
        doneReg := False
        when(supportedMode) {
          modeErrorReg := False
          commandInFlight := True
          finalCommand := io.command.payload.endOfTile
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
        commandInFlight := False
        overflowReg := overflowReg || peResponsePayload.overflow
        oldCount := entryCount
        oldIndex := 0
        newIndex := 0
        writeIndex := 0
        oldEntryValid := False
        oldNextValid := False
        oldFetchIndex := 0
        oldReadPending := False
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
      when(!oldAvailable && !newAvailable) {
        entryCount := writeIndex
        activeBuffer := !activeBuffer
        when(finalCommand) {
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
      } elsewhen(temporalMrnOutput.fire) {
        val canStore = writeIndex < tileCfg.accumulatorEntries
        writeEnable := canStore && temporalMrnOutput.payload.first.valid
        writeEntry.row := temporalMrnOutput.payload.first.row
        writeEntry.column := temporalMrnOutput.payload.first.column
        writeEntry.value := temporalMrnOutput.payload.first.value
        when(canStore) {
          writeIndex := writeIndex + 1
        } otherwise {
          overflowReg := True
        }
        when(temporalMrnOutput.payload.consumeRight) {
          newIndex := newIndex + 1
        }
        when(oldReadPending) {
          oldReadArrivalConsumed := True
        }
        when(temporalMrnOutput.payload.consumeLeft) {
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
        state := StreamingGustavsonState.MergeCompare
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
      when(!commandInFlight || peResponseValid) {
        commandInFlight := False
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
    when(commandInFlight) { state := StreamingGustavsonState.AbortDrain }
      .otherwise { state := StreamingGustavsonState.Accept }
  }
}
