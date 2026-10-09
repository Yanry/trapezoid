package trapezoid

import spinal.core._
import spinal.lib._

object GustavsonTileState extends SpinalEnum {
  val Accept, WaitResponse, Merge, FindMinimum, Output, AbortDrain = newElement()
}

/** Multi-command coordinate accumulator for the TrGT and TrGS paths.
  *
  * Each command is first reduced by a PE row.  Results with the same matrix
  * coordinate are then accumulated across commands.  The command carrying
  * endOfTile closes the tile and causes all entries to be emitted in ascending
  * (row, column) order.  TrGT and TrGS are both legal at every lane density.
  */
class GustavsonTileEngine(tileCfg: GustavsonTileConfig) extends Component {
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
  val outputReg = Reg(MatrixResult(cfg))

  val entryValid = Vec((0 until tileCfg.accumulatorEntries).map(_ => RegInit(False)))
  val entryRow = Vec((0 until tileCfg.accumulatorEntries).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val entryColumn = Vec((0 until tileCfg.accumulatorEntries).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val entryValue = Vec((0 until tileCfg.accumulatorEntries).map(_ =>
    Reg(SInt(cfg.accumulatorWidth bits)) init 0))

  val activeCount = CountOne(entryValid.asBits).resize(tileCfg.entryCountWidth)
  io.busy := tileOpen || state =/= GustavsonTileState.Accept
  io.done := doneReg
  io.overflow := overflowReg
  io.unsupportedMode := modeError
  io.activeEntries := activeCount
  io.result.valid := state === GustavsonTileState.Output
  io.result.payload := outputReg

  val supportedMode = io.command.payload.mode === TrapezoidDataflow.TrGt ||
    io.command.payload.mode === TrapezoidDataflow.TrGs
  val pe = new TrapezoidPERow(cfg)
  pe.io.command.payload := io.command.payload
  pe.io.command.valid := !io.clear && state === GustavsonTileState.Accept &&
    io.command.valid && supportedMode
  io.command.ready := !io.clear && state === GustavsonTileState.Accept &&
    Mux(supportedMode, pe.io.command.ready, True)
  pe.io.response.ready :=
    (!io.clear && state === GustavsonTileState.WaitResponse) ||
      state === GustavsonTileState.AbortDrain

  val currentResult = responseReg.results(responseIndex)
  val coordinateMatches = Vec(Bool(), tileCfg.accumulatorEntries)
  val freeChoices = Vec(Bool(), tileCfg.accumulatorEntries)
  for (entry <- 0 until tileCfg.accumulatorEntries) {
    coordinateMatches(entry) := entryValid(entry) &&
      entryRow(entry) === currentResult.row && entryColumn(entry) === currentResult.column
    var earlierFree: Bool = False
    for (previous <- 0 until entry) earlierFree = earlierFree || !entryValid(previous)
    freeChoices(entry) := !entryValid(entry) && !earlierFree
  }
  val matchFound = coordinateMatches.asBits.orR
  val freeFound = freeChoices.asBits.orR
  val matchIndex = UInt(tileCfg.entryIndexWidth bits)
  val freeIndex = UInt(tileCfg.entryIndexWidth bits)
  matchIndex := 0
  freeIndex := 0
  for (entry <- 0 until tileCfg.accumulatorEntries) {
    when(coordinateMatches(entry)) { matchIndex := entry }
    when(freeChoices(entry)) { freeIndex := entry }
  }

  // A deterministic all-entry comparison produces one minimum coordinate.
  val minimumFlags = Vec(Bool(), tileCfg.accumulatorEntries)
  for (entry <- 0 until tileCfg.accumulatorEntries) {
    var beaten: Bool = False
    for (other <- 0 until tileCfg.accumulatorEntries if other != entry) {
      val lowerCoordinate = entryRow(other) < entryRow(entry) ||
        (entryRow(other) === entryRow(entry) && entryColumn(other) < entryColumn(entry))
      val equalEarlier = if (other < entry) {
        entryRow(other) === entryRow(entry) && entryColumn(other) === entryColumn(entry)
      } else False
      beaten = beaten || (entryValid(other) && (lowerCoordinate || equalEarlier))
    }
    minimumFlags(entry) := entryValid(entry) && !beaten
  }
  val minimumFound = minimumFlags.asBits.orR
  val minimumIndex = UInt(tileCfg.entryIndexWidth bits)
  minimumIndex := 0
  for (entry <- 0 until tileCfg.accumulatorEntries) {
    when(minimumFlags(entry)) { minimumIndex := entry }
  }

  switch(state) {
    is(GustavsonTileState.Accept) {
      when(io.command.fire) {
        doneReg := False
        when(supportedMode) {
          modeError := False
          commandInFlight := True
          finalCommand := io.command.payload.endOfTile
          when(!tileOpen) {
            for (entry <- 0 until tileCfg.accumulatorEntries) entryValid(entry) := False
            overflowReg := False
          }
          tileOpen := True
          state := GustavsonTileState.WaitResponse
        } otherwise {
          for (entry <- 0 until tileCfg.accumulatorEntries) entryValid(entry) := False
          tileOpen := False
          overflowReg := False
          modeError := True
          doneReg := True
        }
      }
    }

    is(GustavsonTileState.WaitResponse) {
      when(pe.io.response.fire) {
        responseReg := pe.io.response.payload
        responseIndex := 0
        commandInFlight := False
        when(pe.io.response.payload.count === 0) {
          when(finalCommand) { state := GustavsonTileState.FindMinimum }
            .otherwise { state := GustavsonTileState.Accept }
        } otherwise {
          state := GustavsonTileState.Merge
        }
      }
    }

    is(GustavsonTileState.Merge) {
      when(currentResult.valid) {
        when(matchFound) {
          entryValue(matchIndex) := (entryValue(matchIndex) + currentResult.value).resized
        } elsewhen(freeFound) {
          entryValid(freeIndex) := True
          entryRow(freeIndex) := currentResult.row
          entryColumn(freeIndex) := currentResult.column
          entryValue(freeIndex) := currentResult.value
        } otherwise {
          overflowReg := True
        }
      }

      when(responseIndex === (responseReg.count - 1).resized) {
        responseIndex := 0
        when(finalCommand) { state := GustavsonTileState.FindMinimum }
          .otherwise { state := GustavsonTileState.Accept }
      } otherwise {
        responseIndex := responseIndex + 1
      }
    }

    is(GustavsonTileState.FindMinimum) {
      when(minimumFound) {
        outputIndex := minimumIndex
        outputReg.valid := True
        outputReg.row := entryRow(minimumIndex)
        outputReg.column := entryColumn(minimumIndex)
        outputReg.value := entryValue(minimumIndex)
        outputReg.endOfTile := activeCount === 1
        state := GustavsonTileState.Output
      } otherwise {
        tileOpen := False
        doneReg := True
        state := GustavsonTileState.Accept
      }
    }

    is(GustavsonTileState.Output) {
      when(io.result.ready) {
        entryValid(outputIndex) := False
        when(outputReg.endOfTile) {
          tileOpen := False
          doneReg := True
          state := GustavsonTileState.Accept
        } otherwise {
          state := GustavsonTileState.FindMinimum
        }
      }
    }

    is(GustavsonTileState.AbortDrain) {
      when(!commandInFlight || pe.io.response.valid) {
        commandInFlight := False
        state := GustavsonTileState.Accept
      }
    }
  }

  when(io.clear) {
    for (entry <- 0 until tileCfg.accumulatorEntries) entryValid(entry) := False
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
class GustavsonArray(tileCfg: GustavsonTileConfig) extends Component {
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

  val engines = Array.fill(cfg.peRows)(new GustavsonTileEngine(tileCfg))
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

object GenerateGustavsonTileEngine extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-gustavson",
    oneFilePerComponent = true
  ).generate(new GustavsonTileEngine(GustavsonTileConfig(TrapezoidConfig.small)))
}

object GenerateGustavsonArray extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-gustavson-array",
    oneFilePerComponent = true
  ).generate(new GustavsonArray(GustavsonTileConfig(TrapezoidConfig.small)))
}
