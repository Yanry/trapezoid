package trapezoid

import spinal.core._
import spinal.lib._

object GustavsonTileState extends SpinalEnum {
  val Accept, WaitResponse, Merge, Output, AbortDrain = newElement()
}

/** Multi-command coordinate accumulator for the TrGT and TrGS paths.
  *
  * Each command is first reduced by a PE row.  Results with the same matrix
  * coordinate are then inserted into a compact coordinate-sorted accumulator.
  * The command carrying endOfTile closes the tile and streams the sorted prefix
  * directly. TrGT and TrGS are both legal at every lane density.
  */
class GustavsonTileEngine(
    tileCfg: GustavsonTileConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg),
    externalPe: Boolean = false
) extends Component {
  private val cfg = tileCfg.core
  private val responseIndexWidth = scala.math.max(1, log2Up(cfg.outputLanes))

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
  }

  val state = RegInit(GustavsonTileState.Accept)
  val tileOpen = RegInit(False)
  val doneReg = RegInit(False)
  val overflowReg = RegInit(False)
  val modeError = RegInit(False)
  val commandInFlight = RegInit(False)
  val finalCommand = RegInit(False)
  val responseIndex = Reg(UInt(responseIndexWidth bits)) init 0
  val responseReg = Reg(PERowResponse(cfg))
  val outputIndex = Reg(UInt(tileCfg.entryIndexWidth bits)) init 0

  val entryCount = Reg(UInt(tileCfg.entryCountWidth bits)) init 0
  val entryRow = Vec((0 until tileCfg.accumulatorEntries).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val entryColumn = Vec((0 until tileCfg.accumulatorEntries).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val entryValue = Vec((0 until tileCfg.accumulatorEntries).map(_ =>
    Reg(SInt(cfg.accumulatorWidth bits)) init 0))

  io.busy := tileOpen || state =/= GustavsonTileState.Accept
  io.done := doneReg
  io.overflow := overflowReg
  io.unsupportedMode := modeError
  io.activeEntries := entryCount
  io.result.valid := state === GustavsonTileState.Output
  io.result.payload.valid := True
  io.result.payload.row := entryRow(outputIndex)
  io.result.payload.column := entryColumn(outputIndex)
  io.result.payload.value := entryValue(outputIndex)
  io.result.payload.endOfTile :=
    outputIndex.resize(tileCfg.entryCountWidth) + 1 === entryCount

  val supportedMode = io.command.payload.mode === TrapezoidDataflow.TrGt ||
    io.command.payload.mode === TrapezoidDataflow.TrGs
  val peCommandReady = Bool()
  val peResponseValid = Bool()
  val peResponsePayload = PERowResponse(cfg)
  val peResponseReady = Bool()
  val peCommandValid = !io.clear && state === GustavsonTileState.Accept &&
    io.command.valid && supportedMode
  if (externalPe) {
    io.peCommand.valid := peCommandValid
    io.peCommand.payload := io.command.payload
    peCommandReady := io.peCommand.ready
    peResponseValid := io.peResponse.valid
    peResponsePayload := io.peResponse.payload
    io.peResponse.ready := peResponseReady
  } else {
    val pe = peFactory(cfg)
    pe.io.command.valid := peCommandValid
    pe.io.command.payload := io.command.payload
    peCommandReady := pe.io.command.ready
    peResponseValid := pe.io.response.valid
    peResponsePayload := pe.io.response.payload
    pe.io.response.ready := peResponseReady
  }
  io.command.ready := !io.clear && state === GustavsonTileState.Accept &&
    Mux(supportedMode, peCommandReady, True)
  peResponseReady :=
    (!io.clear && state === GustavsonTileState.WaitResponse) ||
      state === GustavsonTileState.AbortDrain

  val currentResult = responseReg.results(responseIndex)
  val coordinateMatches = Vec(Bool(), tileCfg.accumulatorEntries)
  val currentComesBefore = Vec(Bool(), tileCfg.accumulatorEntries)
  val insertionChoices = Vec(Bool(), tileCfg.accumulatorEntries)
  val insertionPrefix = Vec(Bool(), tileCfg.accumulatorEntries + 1)
  insertionPrefix(0) := False
  for (entry <- 0 until tileCfg.accumulatorEntries) {
    val active = U(entry, tileCfg.entryCountWidth bits) < entryCount
    coordinateMatches(entry) := active &&
      entryRow(entry) === currentResult.row && entryColumn(entry) === currentResult.column
    currentComesBefore(entry) := active &&
      (currentResult.row < entryRow(entry) ||
        (currentResult.row === entryRow(entry) &&
          currentResult.column < entryColumn(entry)))
    insertionChoices(entry) := currentComesBefore(entry) && !insertionPrefix(entry)
    insertionPrefix(entry + 1) := insertionPrefix(entry) || currentComesBefore(entry)
  }
  val matchFound = coordinateMatches.asBits.orR
  val matchIndex = UInt(tileCfg.entryIndexWidth bits)
  val insertionIndex = UInt(tileCfg.entryIndexWidth bits)
  matchIndex := 0
  insertionIndex := entryCount.resize(tileCfg.entryIndexWidth)
  for (entry <- 0 until tileCfg.accumulatorEntries) {
    when(coordinateMatches(entry)) { matchIndex := entry }
    when(insertionChoices(entry)) { insertionIndex := entry }
  }
  val insertionAccepted = currentResult.valid && !matchFound &&
    entryCount =/= tileCfg.accumulatorEntries

  switch(state) {
    is(GustavsonTileState.Accept) {
      when(io.command.fire) {
        doneReg := False
        when(supportedMode) {
          modeError := False
          commandInFlight := True
          finalCommand := io.command.payload.endOfTile
          when(!tileOpen) {
            entryCount := 0
            overflowReg := False
          }
          tileOpen := True
          state := GustavsonTileState.WaitResponse
        } otherwise {
          entryCount := 0
          tileOpen := False
          overflowReg := False
          modeError := True
          doneReg := True
        }
      }
    }

    is(GustavsonTileState.WaitResponse) {
      when(peResponseValid && peResponseReady) {
        responseReg := peResponsePayload
        responseIndex := 0
        commandInFlight := False
        when(peResponsePayload.overflow) { overflowReg := True }
        when(peResponsePayload.count === 0) {
          when(finalCommand) {
            when(entryCount === 0) {
              tileOpen := False
              doneReg := True
              state := GustavsonTileState.Accept
            } otherwise {
              outputIndex := 0
              state := GustavsonTileState.Output
            }
          } otherwise {
            state := GustavsonTileState.Accept
          }
        } otherwise {
          state := GustavsonTileState.Merge
        }
      }
    }

    is(GustavsonTileState.Merge) {
      when(currentResult.valid) {
        when(matchFound) {
          entryValue(matchIndex) := (entryValue(matchIndex) + currentResult.value).resized
        } elsewhen(entryCount =/= tileCfg.accumulatorEntries) {
          for (entry <- 1 until tileCfg.accumulatorEntries) {
            when(U(entry, tileCfg.entryCountWidth bits) >
                insertionIndex.resize(tileCfg.entryCountWidth) &&
                U(entry, tileCfg.entryCountWidth bits) <= entryCount) {
              entryRow(entry) := entryRow(entry - 1)
              entryColumn(entry) := entryColumn(entry - 1)
              entryValue(entry) := entryValue(entry - 1)
            }
          }
          for (entry <- 0 until tileCfg.accumulatorEntries) {
            when(insertionIndex === entry) {
              entryRow(entry) := currentResult.row
              entryColumn(entry) := currentResult.column
              entryValue(entry) := currentResult.value
            }
          }
          entryCount := entryCount + 1
        } otherwise {
          overflowReg := True
        }
      }

      when(responseIndex === (responseReg.count - 1).resized) {
        responseIndex := 0
        when(finalCommand) {
          when(entryCount =/= 0 || insertionAccepted) {
            outputIndex := 0
            state := GustavsonTileState.Output
          } otherwise {
            tileOpen := False
            doneReg := True
            state := GustavsonTileState.Accept
          }
        } otherwise {
          state := GustavsonTileState.Accept
        }
      } otherwise {
        responseIndex := responseIndex + 1
      }
    }

    is(GustavsonTileState.Output) {
      when(io.result.ready) {
        when(io.result.payload.endOfTile) {
          entryCount := 0
          tileOpen := False
          doneReg := True
          state := GustavsonTileState.Accept
        } otherwise {
          outputIndex := outputIndex + 1
        }
      }
    }

    is(GustavsonTileState.AbortDrain) {
      when(!commandInFlight || peResponseValid) {
        commandInFlight := False
        state := GustavsonTileState.Accept
      }
    }
  }

  when(io.clear) {
    entryCount := 0
    tileOpen := False
    doneReg := False
    overflowReg := False
    modeError := False
    when(commandInFlight) { state := GustavsonTileState.AbortDrain }
      .otherwise { state := GustavsonTileState.Accept }
  }
}

/** Multi-row Gustavson execution boundary.
  *
  * Each PE row owns an independent coordinate accumulator and Stream port, so
  * rows can execute TrGT or TrGS concurrently with independent backpressure.
  * Work assignment and load balancing remain explicit scheduler policies.
  */
class GustavsonArray(
    tileCfg: GustavsonTileConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg)
) extends Component {
  private val cfg = tileCfg.core

  val io = new Bundle {
    val clear = in Bool()
    val commands = Vec(slave(Stream(PERowCommand(cfg))), cfg.peRows)
    val results = Vec(master(Stream(MatrixResult(cfg))), cfg.peRows)
    val busy = out Bits(cfg.peRows bits)
    val done = out Bits(cfg.peRows bits)
    val overflow = out Bits(cfg.peRows bits)
    val unsupportedMode = out Bits(cfg.peRows bits)
    val activeEntries = out Vec(UInt(tileCfg.entryCountWidth bits), cfg.peRows)
  }

  val engines = Array.fill(cfg.peRows)(new GustavsonTileEngine(tileCfg, peFactory))
  for (row <- 0 until cfg.peRows) {
    engines(row).io.clear := io.clear
    engines(row).io.command <> io.commands(row)
    engines(row).io.result <> io.results(row)
    io.busy(row) := engines(row).io.busy
    io.done(row) := engines(row).io.done
    io.overflow(row) := engines(row).io.overflow
    io.unsupportedMode(row) := engines(row).io.unsupportedMode
    io.activeEntries(row) := engines(row).io.activeEntries
  }
}

/** Shared command/result boundary for the multi-row Gustavson array.
  *
  * Commands through one `endOfTile` marker stay on the same PE row.  Once that
  * marker is accepted, the next tile may use another free row while the first
  * row flushes.  Result arbitration remains locked through `endOfTile`, so two
  * concurrently executing tiles never interleave on the shared output.
  */
class GustavsonRowScheduler(
    tileCfg: GustavsonTileConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg)
) extends Component {
  private val cfg = tileCfg.core
  private val rowIndexWidth = scala.math.max(1, log2Up(cfg.peRows))

  val io = new Bundle {
    val clear = in Bool()
    val command = slave(Stream(PERowCommand(cfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val allocated = out Bits(cfg.peRows bits)
    val busy = out Bits(cfg.peRows bits)
    val overflow = out Bits(cfg.peRows bits)
    val unsupportedMode = out Bits(cfg.peRows bits)
    val assignedTiles = out UInt(32 bits)
    val completedTiles = out UInt(32 bits)
  }

  val array = new GustavsonArray(tileCfg, peFactory)
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

  val supportedInputMode = io.command.payload.mode === TrapezoidDataflow.TrGt ||
    io.command.payload.mode === TrapezoidDataflow.TrGs
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
    outputChoices(row) := array.io.results(row).valid && !earlierOutput
    earlierOutput = earlierOutput || array.io.results(row).valid
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
  for (row <- 0 until cfg.peRows) {
    array.io.results(row).ready := io.result.ready && outputRouteValid &&
      selectedOutputRow === row
    when(outputRouteValid && selectedOutputRow === row) {
      io.result.valid := array.io.results(row).valid
      io.result.payload := array.io.results(row).payload
    }
  }

  when(!outputLocked && outputFound) {
    outputLocked := True
    outputRow := chosenOutputRow
  }
  when(io.result.fire && io.result.payload.endOfTile) {
    outputLocked := False
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

object GenerateGustavsonTileEngine extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-gustavson",
    oneFilePerComponent = true
  ).generate(new GustavsonTileEngine(GustavsonTileConfig(TrapezoidConfig.small)))
}

object GeneratePaperGustavsonTileEngine extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-paper-gustavson",
    oneFilePerComponent = true
  ).generate(new GustavsonTileEngine(GustavsonTileConfig(TrapezoidConfig.paper)))
}

object GenerateGustavsonArray extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-gustavson-array",
    oneFilePerComponent = true
  ).generate(new GustavsonArray(GustavsonTileConfig(TrapezoidConfig.small)))
}

object GenerateGustavsonRowScheduler extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-gustavson-scheduler",
    oneFilePerComponent = true
  ).generate(new GustavsonRowScheduler(GustavsonTileConfig(TrapezoidConfig.small)))
}
