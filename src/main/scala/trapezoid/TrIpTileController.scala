package trapezoid

import spinal.core._
import spinal.lib._

object TrIpTileState extends SpinalEnum {
  val Idle, Plan, Dispatch, Collect, Scatter, ReadPlan, ReadWait, Output, AbortDrain = newElement()
}

/** Matrix-tile scheduler for the bitmask inner-product path.
  *
  * Rows of A and columns of B are loaded as compressed fibers.  The scheduler
  * partitions A fibers when necessary, finds a B-column prefix that fits every
  * active PE row, dispatches all rows atomically, scatters results into the
  * four-bank local buffer, and reads them back in row-major load order.
  * DenseIp and TrIp are both accepted at any input density; mode selection is
  * always controlled by the caller.
  */
class TrIpTileController(tileCfg: TrIpTileConfig) extends Component {
  private val cfg = tileCfg.core
  private val fiberCounterWidth = log2Up(cfg.maxFibers + 1)
  private val peIndexWidth = scala.math.max(1, log2Up(cfg.peRows))
  private val outputIndexWidth = scala.math.max(1, log2Up(cfg.outputLanes))
  private val cellCount = tileCfg.maxRows * tileCfg.maxColumns
  private val cellIndexWidth = scala.math.max(1, log2Up(cellCount))
  private val bankIndexWidth = log2Up(cfg.localBanks)

  val io = new Bundle {
    val clear = in Bool()
    val aLoad = slave(Stream(IndexedFiber(cfg, tileCfg.rowIndexWidth)))
    val bLoad = slave(Stream(IndexedFiber(cfg, tileCfg.columnIndexWidth)))
    val start = slave(Stream(TrIpTileStart(tileCfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val unsupportedMode = out Bool()
    val activeAOffset = out UInt(fiberCounterWidth bits)
    val activeAGroupSize = out UInt(fiberCounterWidth bits)
    val activeBBase = out UInt(tileCfg.columnIndexWidth bits)
    val plannedColumns = out UInt(fiberCounterWidth bits)
    val candidateColumnCount = out UInt(fiberCounterWidth bits)
    val activeRows = out Bits(cfg.peRows bits)
  }

  val aStorage = Vec((0 until tileCfg.maxRows).map(_ => Reg(CompressedFiber(cfg))))
  val bStorage = Vec((0 until tileCfg.maxColumns).map(_ => Reg(CompressedFiber(cfg))))
  val aLoaded = Vec((0 until tileCfg.maxRows).map(_ => RegInit(False)))
  val bLoaded = Vec((0 until tileCfg.maxColumns).map(_ => RegInit(False)))

  val state = RegInit(TrIpTileState.Idle)
  val modeReg = Reg(TrapezoidDataflow()) init TrapezoidDataflow.TrIp
  val rowCount = Reg(UInt(tileCfg.rowCountWidth bits)) init 0
  val columnCount = Reg(UInt(tileCfg.columnCountWidth bits)) init 0
  val aOffset = Reg(UInt(fiberCounterWidth bits)) init 0
  val groupSize = Reg(UInt(fiberCounterWidth bits)) init cfg.maxFibers
  val bBase = Reg(UInt(tileCfg.columnIndexWidth bits)) init 0
  val batchColumns = Reg(UInt(fiberCounterWidth bits)) init 1
  val scatterPe = Reg(UInt(peIndexWidth bits)) init 0
  val scatterLane = Reg(UInt(outputIndexWidth bits)) init 0
  val outputCell = Reg(UInt(cellIndexWidth bits)) init 0
  val modeError = RegInit(False)
  val doneReg = RegInit(False)
  val commandInFlight = RegInit(False)
  val outputReg = Reg(MatrixResult(cfg))
  val resultValid = Vec((0 until cellCount).map(_ => RegInit(False)))

  io.busy := state =/= TrIpTileState.Idle
  io.done := doneReg
  io.unsupportedMode := modeError
  io.activeAOffset := aOffset
  io.activeAGroupSize := groupSize
  io.activeBBase := bBase

  io.aLoad.ready := !io.clear && state === TrIpTileState.Idle && !io.start.valid
  io.bLoad.ready := !io.clear && state === TrIpTileState.Idle && !io.start.valid
  io.start.ready := !io.clear && state === TrIpTileState.Idle &&
    !io.aLoad.valid && !io.bLoad.valid

  when(io.clear) {
    for (index <- 0 until tileCfg.maxRows) aLoaded(index) := False
    for (index <- 0 until tileCfg.maxColumns) bLoaded(index) := False
    modeError := False
    doneReg := False
    for (cell <- 0 until cellCount) resultValid(cell) := False
  }

  when(io.aLoad.fire) {
    for (index <- 0 until tileCfg.maxRows) {
      when(io.aLoad.payload.index === index) {
        aStorage(index) := io.aLoad.payload.fiber
        aLoaded(index) := True
      }
    }
  }
  when(io.bLoad.fire) {
    for (index <- 0 until tileCfg.maxColumns) {
      when(io.bLoad.payload.index === index) {
        bStorage(index) := io.bLoad.payload.fiber
        bLoaded(index) := True
      }
    }
  }

  val pes = Array.fill(cfg.peRows)(new TrapezoidPERow(cfg))
  val packers = Array.fill(cfg.peRows)(new DynamicColumnPacker(cfg))

  val candidateColumns = UInt(fiberCounterWidth bits)
  val remainingColumns = UInt(tileCfg.columnCountWidth bits)
  remainingColumns := columnCount - bBase.resize(tileCfg.columnCountWidth)
  candidateColumns := Mux(remainingColumns > cfg.maxFibers,
    U(cfg.maxFibers, fiberCounterWidth bits), remainingColumns.resize(fiberCounterWidth))

  val rowActive = Vec(Bool(), cfg.peRows)
  for (pe <- 0 until cfg.peRows) {
    rowActive(pe) := False
    for (fiber <- 0 until cfg.maxFibers) {
      val globalRow = pe * cfg.maxFibers + fiber
      val inGroup = U(fiber, fiberCounterWidth bits) >= aOffset &&
        U(fiber, fiberCounterWidth bits) < (aOffset + groupSize).resized
      val validA = inGroup && U(globalRow, tileCfg.rowCountWidth bits) < rowCount &&
        aLoaded(globalRow) && aStorage(globalRow).valid

      packers(pe).io.aValid(fiber) := validA
      packers(pe).io.aMask(fiber) := aStorage(globalRow).mask
      when(validA) { rowActive(pe) := True }

      pes(pe).io.command.payload.trip.a(fiber).valid := validA
      pes(pe).io.command.payload.trip.a(fiber).tag := aStorage(globalRow).tag
      pes(pe).io.command.payload.trip.a(fiber).mask := aStorage(globalRow).mask
      for (value <- 0 until cfg.fiberWidth) {
        pes(pe).io.command.payload.trip.a(fiber).values(value) :=
          aStorage(globalRow).values(value)
      }

      val columnIndex = (bBase + fiber).resized
      val validB = U(fiber, fiberCounterWidth bits) < candidateColumns &&
        bLoaded(columnIndex) && bStorage(columnIndex).valid
      packers(pe).io.bValid(fiber) := validB
      packers(pe).io.bMask(fiber) := bStorage(columnIndex).mask

      pes(pe).io.command.payload.trip.b(fiber).valid := validB &&
        U(fiber, fiberCounterWidth bits) < batchColumns
      pes(pe).io.command.payload.trip.b(fiber).tag := bStorage(columnIndex).tag
      pes(pe).io.command.payload.trip.b(fiber).mask := bStorage(columnIndex).mask
      for (value <- 0 until cfg.fiberWidth) {
        pes(pe).io.command.payload.trip.b(fiber).values(value) :=
          bStorage(columnIndex).values(value)
      }
    }

    pes(pe).io.command.payload.mode := modeReg
    pes(pe).io.command.payload.endOfTile := False
    pes(pe).io.command.payload.gustavson := GustavsonPayload(cfg).getZero
  }

  // The usable B prefix is the minimum capacity of every active PE row.
  var globalChoice = candidateColumns
  for (pe <- 0 until cfg.peRows) {
    val rowChoice = Mux(packers(pe).io.selectedColumns < candidateColumns,
      packers(pe).io.selectedColumns, candidateColumns)
    val activeChoice = Mux(rowActive(pe), rowChoice, candidateColumns)
    globalChoice = Mux(activeChoice < globalChoice, activeChoice, globalChoice)
  }
  io.plannedColumns := globalChoice
  io.candidateColumnCount := candidateColumns
  io.activeRows := rowActive.asBits

  val allPeReady = pes.map(_.io.command.ready).reduce(_ && _)
  for (pe <- 0 until cfg.peRows) {
    pes(pe).io.command.valid := !io.clear && state === TrIpTileState.Dispatch && allPeReady
  }

  val responseRegs = Vec((0 until cfg.peRows).map(_ => Reg(PERowResponse(cfg))))
  val allResponsesValid = pes.map(_.io.response.valid).reduce(_ && _)
  for (pe <- 0 until cfg.peRows) {
    pes(pe).io.response.ready :=
      (!io.clear && state === TrIpTileState.Collect && allResponsesValid) ||
        state === TrIpTileState.AbortDrain
  }

  val currentResponse = responseRegs(scatterPe)
  val currentResult = currentResponse.results(scatterLane)

  val rowFound = Bool()
  val rowSlot = UInt(tileCfg.rowIndexWidth bits)
  rowFound := False
  rowSlot := 0
  for (row <- 0 until tileCfg.maxRows) {
    when(aLoaded(row) && U(row, tileCfg.rowCountWidth bits) < rowCount &&
        aStorage(row).tag === currentResult.row) {
      rowFound := True
      rowSlot := row
    }
  }

  val columnFound = Bool()
  val columnSlot = UInt(tileCfg.columnIndexWidth bits)
  columnFound := False
  columnSlot := 0
  for (column <- 0 until tileCfg.maxColumns) {
    when(bLoaded(column) && U(column, tileCfg.columnCountWidth bits) < columnCount &&
        bStorage(column).tag === currentResult.column) {
      columnFound := True
      columnSlot := column
    }
  }

  val scatterCell = UInt(cellIndexWidth bits)
  scatterCell := ((rowSlot.resize(cellIndexWidth) << tileCfg.columnIndexWidth) +
    columnSlot.resize(cellIndexWidth)).resized
  val scatterBank = scatterCell.resize(bankIndexWidth)
  val scatterAddress = if (cellIndexWidth <= bankIndexWidth) {
    U(0, cfg.localAddressWidth bits)
  } else {
    scatterCell(cellIndexWidth - 1 downto bankIndexWidth).resize(cfg.localAddressWidth)
  }

  val localBuffer = new BankedLocalBuffer(cfg)
  for (bank <- 0 until cfg.localBanks) {
    localBuffer.io.bus.writes(bank).valid := False
    localBuffer.io.bus.writes(bank).payload.address := scatterAddress
    localBuffer.io.bus.writes(bank).payload.data := currentResult.value.asBits
    localBuffer.io.bus.writes(bank).payload.mask :=
      B((BigInt(1) << (cfg.accumulatorWidth / 8)) - 1, (cfg.accumulatorWidth / 8) bits)
    localBuffer.io.bus.readCommands(bank).valid := False
    localBuffer.io.bus.readCommands(bank).payload := 0
    when(state === TrIpTileState.Scatter && currentResult.valid && rowFound && columnFound &&
        scatterBank === bank) {
      localBuffer.io.bus.writes(bank).valid := True
    }
  }

  val readBank = outputCell.resize(bankIndexWidth)
  val readAddress = if (cellIndexWidth <= bankIndexWidth) {
    U(0, cfg.localAddressWidth bits)
  } else {
    outputCell(cellIndexWidth - 1 downto bankIndexWidth).resize(cfg.localAddressWidth)
  }
  for (bank <- 0 until cfg.localBanks) {
    when(state === TrIpTileState.ReadPlan && resultValid(outputCell) && readBank === bank) {
      localBuffer.io.bus.readCommands(bank).valid := True
      localBuffer.io.bus.readCommands(bank).payload := readAddress
    }
  }

  val selectedReadValid = Bool()
  val selectedReadData = Bits(cfg.accumulatorWidth bits)
  selectedReadValid := False
  selectedReadData := 0
  for (bank <- 0 until cfg.localBanks) {
    when(readBank === bank) {
      selectedReadValid := localBuffer.io.bus.readResponses(bank).valid
      selectedReadData := localBuffer.io.bus.readResponses(bank).payload
    }
  }

  io.result.valid := state === TrIpTileState.Output
  io.result.payload := outputReg

  val finalBatch = bBase.resize(tileCfg.columnCountWidth + 1) +
    batchColumns.resize(tileCfg.columnCountWidth + 1) >= columnCount
  val finalAGroup = aOffset + groupSize >= cfg.maxFibers
  val hasLaterCell = Bool()
  hasLaterCell := False
  for (cell <- 1 until cellCount) {
    when(U(cell, cellIndexWidth bits) > outputCell && resultValid(cell)) {
      hasLaterCell := True
    }
  }

  def advanceSchedule(): Unit = {
    when(finalBatch) {
      bBase := 0
      when(finalAGroup) {
        outputCell := 0
        state := TrIpTileState.ReadPlan
      } otherwise {
        aOffset := (aOffset + groupSize).resized
        groupSize := (U(cfg.maxFibers, fiberCounterWidth bits) -
          (aOffset + groupSize).resized).resized
        state := TrIpTileState.Plan
      }
    } otherwise {
      bBase := (bBase + batchColumns).resized
      state := TrIpTileState.Plan
    }
  }

  def advanceScatter(): Unit = {
    when(scatterLane === cfg.outputLanes - 1) {
      scatterLane := 0
      when(scatterPe === cfg.peRows - 1) {
        scatterPe := 0
        advanceSchedule()
      } otherwise {
        scatterPe := scatterPe + 1
      }
    } otherwise {
      scatterLane := scatterLane + 1
    }
  }

  switch(state) {
    is(TrIpTileState.Idle) {
      when(io.start.fire) {
        doneReg := False
        modeReg := io.start.payload.mode
        rowCount := io.start.payload.rowCount
        columnCount := io.start.payload.columnCount
        aOffset := 0
        groupSize := cfg.maxFibers
        bBase := 0
        outputCell := 0
        modeError := (io.start.payload.mode =/= TrapezoidDataflow.DenseIp &&
          io.start.payload.mode =/= TrapezoidDataflow.TrIp) ||
          io.start.payload.rowCount > tileCfg.maxRows ||
          io.start.payload.columnCount > tileCfg.maxColumns
        for (cell <- 0 until cellCount) resultValid(cell) := False
        when(io.start.payload.rowCount === 0 || io.start.payload.columnCount === 0) {
          doneReg := True
        } otherwise {
          state := TrIpTileState.Plan
        }
      }
    }

    is(TrIpTileState.Plan) {
      when(modeError) {
        state := TrIpTileState.Idle
        doneReg := True
      } elsewhen(globalChoice =/= 0) {
        batchColumns := globalChoice
        state := TrIpTileState.Dispatch
      } elsewhen(groupSize > 1) {
        // Replay the same B prefix with fewer A fibers until it fits.
        groupSize := groupSize - 1
      } otherwise {
        // Prevent deadlock if a future configuration violates macLanes >= fiberWidth.
        modeError := True
        state := TrIpTileState.Idle
        doneReg := True
      }
    }

    is(TrIpTileState.Dispatch) {
      when(allPeReady) {
        commandInFlight := True
        state := TrIpTileState.Collect
      }
    }

    is(TrIpTileState.Collect) {
      when(allResponsesValid) {
        for (pe <- 0 until cfg.peRows) responseRegs(pe) := pes(pe).io.response.payload
        commandInFlight := False
        scatterPe := 0
        scatterLane := 0
        state := TrIpTileState.Scatter
      }
    }

    is(TrIpTileState.Scatter) {
      when(currentResult.valid && rowFound && columnFound) {
        for (cell <- 0 until cellCount) {
          when(scatterCell === cell) { resultValid(cell) := True }
        }
      }
      advanceScatter()
    }

    is(TrIpTileState.ReadPlan) {
      when(resultValid(outputCell)) {
        state := TrIpTileState.ReadWait
      } elsewhen(outputCell === cellCount - 1) {
        state := TrIpTileState.Idle
        doneReg := True
      } otherwise {
        outputCell := outputCell + 1
      }
    }

    is(TrIpTileState.ReadWait) {
      when(selectedReadValid) {
        val outputRowSlot = if (tileCfg.maxRows == 1) {
          U(0, tileCfg.rowIndexWidth bits)
        } else {
          outputCell(cellIndexWidth - 1 downto tileCfg.columnIndexWidth)
            .resize(tileCfg.rowIndexWidth)
        }
        val outputColumnSlot = outputCell(tileCfg.columnIndexWidth - 1 downto 0)
        outputReg.valid := True
        outputReg.row := aStorage(outputRowSlot).tag
        outputReg.column := bStorage(outputColumnSlot).tag
        outputReg.value := selectedReadData.asSInt
        outputReg.endOfTile := !hasLaterCell
        state := TrIpTileState.Output
      }
    }

    is(TrIpTileState.Output) {
      when(io.result.ready) {
        when(outputReg.endOfTile) {
          state := TrIpTileState.Idle
          doneReg := True
        } otherwise {
          outputCell := outputCell + 1
          state := TrIpTileState.ReadPlan
        }
      }
    }

    is(TrIpTileState.AbortDrain) {
      when(!commandInFlight || allResponsesValid) {
        commandInFlight := False
        state := TrIpTileState.Idle
      }
    }
  }

  // An accepted PE command must be drained before a cleared controller can be reused.
  when(io.clear) {
    when(commandInFlight) {
      state := TrIpTileState.AbortDrain
    } otherwise {
      state := TrIpTileState.Idle
    }
    doneReg := False
    modeError := False
  }
}

object GenerateTrIpTileController extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-tile",
    oneFilePerComponent = true
  ).generate(new TrIpTileController(TrIpTileConfig(TrapezoidConfig.small)))
}
