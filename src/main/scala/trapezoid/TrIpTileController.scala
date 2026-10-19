package trapezoid

import spinal.core._
import spinal.lib._

object TrIpTileState extends SpinalEnum {
  val Idle, Preflight, Plan, Dispatch, Collect, Scatter, ScatterAccumWait,
    ReadPlan, ReadWait, Output, AbortDrain = newElement()
}

/** Matrix-tile scheduler for the bitmask inner-product path.
  *
  * Rows of A and columns of B are loaded as compressed fibers.  The scheduler
  * partitions A fibers when necessary, finds a B-column prefix that fits every
  * active PE row, dispatches all rows atomically, scatters results into the
  * PE-row-private four-bank local buffers, and reads them back in row-major
  * load order. Every PE row matches all response lanes to the four banks at
  * one line address and scatters all four-word lines in one controller stage.
  * DenseIp and TrIp are both accepted at any input density; mode selection is
  * always controlled by the caller.
  */
class TrIpTileController(
    tileCfg: TrIpTileConfig,
    peFactory: TrapezoidConfig => TrapezoidPERowComponent =
      cfg => new TrapezoidPERow(cfg)
) extends Component {
  private val cfg = tileCfg.core
  private val fiberCounterWidth = log2Up(cfg.maxFibers + 1)
  private val peIndexWidth = scala.math.max(1, log2Up(cfg.peRows))
  private val cellCount = tileCfg.maxRows * tileCfg.maxColumns
  private val cellIndexWidth = scala.math.max(1, log2Up(cellCount))
  private val bankIndexWidth = log2Up(cfg.localBanks)
  private val pendingLineCount = cfg.peRows * cfg.localBanks
  private val pendingWordCount = pendingLineCount * cfg.localWordsPerBank

  val io = new Bundle {
    val clear = in Bool()
    val aLoad = slave(Stream(IndexedFiber(cfg, tileCfg.rowIndexWidth)))
    val bLoad = slave(Stream(IndexedFiber(cfg, tileCfg.columnIndexWidth)))
    val start = slave(Stream(TrIpTileStart(tileCfg)))
    val result = master(Stream(MatrixResult(cfg)))
    val busy = out Bool()
    val done = out Bool()
    val awaitingKTile = out Bool()
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
  val sequenceARowTags = Vec((0 until tileCfg.maxRows).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
  val sequenceBColumnTags = Vec((0 until tileCfg.maxColumns).map(_ =>
    Reg(UInt(cfg.coordinateWidth bits)) init 0))
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
  val outputCell = Reg(UInt(cellIndexWidth bits)) init 0
  val modeError = RegInit(False)
  val doneReg = RegInit(False)
  val commandInFlight = RegInit(False)
  val accumulationOpen = RegInit(False)
  val lastKTile = RegInit(True)
  val pendingValid = Vec((0 until pendingLineCount).map(_ => RegInit(False)))
  val pendingAddress = Vec((0 until pendingLineCount).map(_ =>
    Reg(UInt(cfg.localAddressWidth bits)) init 0))
  val pendingWordValid = Vec((0 until pendingWordCount).map(_ => RegInit(False)))
  val pendingWordExisting = Vec((0 until pendingWordCount).map(_ => RegInit(False)))
  val pendingWordValue = Vec((0 until pendingWordCount).map(_ =>
    Reg(SInt(cfg.accumulatorWidth bits)) init 0))
  val outputReg = Reg(MatrixResult(cfg))
  val resultValid = Vec((0 until cellCount).map(_ => RegInit(False)))

  io.busy := state =/= TrIpTileState.Idle
  io.done := doneReg
  io.awaitingKTile := accumulationOpen
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
    for (line <- 0 until pendingLineCount) pendingValid(line) := False
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

  val pes = Array.fill(cfg.peRows)(peFactory(cfg))
  val packers = Array.fill(cfg.peRows)(new DynamicColumnPacker(cfg))

  val candidateColumns = UInt(fiberCounterWidth bits)
  val remainingColumns = UInt(tileCfg.columnCountWidth bits)
  remainingColumns := columnCount - bBase.resize(tileCfg.columnCountWidth)
  val fiberLimitedColumns = Mux(remainingColumns > cfg.maxFibers,
    U(cfg.maxFibers, fiberCounterWidth bits), remainingColumns.resize(fiberCounterWidth))
  val lineOffset = bBase.resize(cfg.localWordIndexWidth)
  val lineRemaining = U(cfg.localWordsPerBank, cfg.localWordIndexWidth + 1 bits) -
    lineOffset.resize(cfg.localWordIndexWidth + 1)
  candidateColumns := Mux(fiberLimitedColumns.resize(cfg.localWordIndexWidth + 1) >
    lineRemaining, lineRemaining.resize(fiberCounterWidth), fiberLimitedColumns)

  val rowActive = Vec(Bool(), cfg.peRows)
  for (pe <- 0 until cfg.peRows) {
    rowActive(pe) := False
    for (fiber <- 0 until cfg.maxFibers) {
      val globalRow = pe * cfg.maxFibers + fiber
      val inGroup = U(fiber, fiberCounterWidth bits) >= aOffset &&
        U(fiber, fiberCounterWidth bits) < (aOffset + groupSize).resized
      if (globalRow < tileCfg.maxRows) {
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
      } else {
        packers(pe).io.aValid(fiber) := False
        packers(pe).io.aMask(fiber) := 0
        pes(pe).io.command.payload.trip.a(fiber) := CompressedFiber(cfg).getZero
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

  val localBuffers = Array.fill(cfg.peRows)(new BankedLocalBuffer(cfg))
  val scatterWordValid = Vec(Bool(), pendingWordCount)
  val scatterWordExisting = Vec(Bool(), pendingWordCount)
  val scatterWordValue = Vec(SInt(cfg.accumulatorWidth bits), pendingWordCount)
  for (word <- 0 until pendingWordCount) {
    scatterWordValid(word) := False
    scatterWordValue(word) := 0
  }

  val scatterLineAddress = (bBase >> cfg.localWordIndexWidth)
    .resize(cfg.localAddressWidth)
  val scatterLineBase = (bBase.resize(tileCfg.columnCountWidth + 1) >>
    cfg.localWordIndexWidth) << cfg.localWordIndexWidth

  for (pe <- 0 until cfg.peRows; fiber <- 0 until cfg.maxFibers;
       word <- 0 until cfg.localWordsPerBank) {
    val line = pe * cfg.localBanks + fiber
    val slot = line * cfg.localWordsPerBank + word
    val globalRow = pe * cfg.maxFibers + fiber
    val columnWide = scatterLineBase + word
    val columnIndex = columnWide.resize(tileCfg.columnIndexWidth)
    val wordSelected = columnWide >= bBase.resize(tileCfg.columnCountWidth + 1) &&
      columnWide < (bBase.resize(tileCfg.columnCountWidth + 1) +
        batchColumns.resize(tileCfg.columnCountWidth + 1))
    val rowAvailable = Bool()
    if (globalRow < tileCfg.maxRows) {
      rowAvailable := U(globalRow, tileCfg.rowCountWidth bits) < rowCount &&
        aLoaded(globalRow) && aStorage(globalRow).valid
    } else {
      rowAvailable := False
    }
    val columnAvailable = columnWide < columnCount.resize(tileCfg.columnCountWidth + 1) &&
      bLoaded(columnIndex) && bStorage(columnIndex).valid

    if (globalRow < tileCfg.maxRows) {
      for (lane <- 0 until cfg.outputLanes) {
        val result = responseRegs(pe).results(lane)
        when(state === TrIpTileState.Scatter && wordSelected && rowAvailable &&
            columnAvailable && result.valid && result.row === aStorage(globalRow).tag &&
            result.column === bStorage(columnIndex).tag) {
          scatterWordValid(slot) := True
          scatterWordValue(slot) := result.value
        }
      }

      val rowResultValid = Vec((0 until tileCfg.maxColumns).map(column =>
        resultValid(globalRow * tileCfg.maxColumns + column)))
      scatterWordExisting(slot) := scatterWordValid(slot) &&
        rowResultValid(columnIndex)
    } else {
      scatterWordExisting(slot) := False
    }
  }
  for (row <- 0 until tileCfg.maxRows; column <- 0 until tileCfg.maxColumns) {
    val pe = row / cfg.maxFibers
    val fiber = row % cfg.maxFibers
    val writesCoordinate = Vec(Bool(), cfg.localWordsPerBank)
    for (word <- 0 until cfg.localWordsPerBank) {
      val slot = (pe * cfg.localBanks + fiber) * cfg.localWordsPerBank + word
      val columnWide = scatterLineBase + word
      writesCoordinate(word) := scatterWordValid(slot) &&
        columnWide === column
    }
    when(writesCoordinate.asBits.orR) {
      resultValid(row * tileCfg.maxColumns + column) := True
    }
  }
  for (pe <- 0 until cfg.peRows; bank <- cfg.maxFibers until cfg.localBanks;
       word <- 0 until cfg.localWordsPerBank) {
    val slot = (pe * cfg.localBanks + bank) * cfg.localWordsPerBank + word
    scatterWordExisting(slot) := False
  }

  val scatterLineValid = Vec(Bool(), pendingLineCount)
  val scatterLineExisting = Vec(Bool(), pendingLineCount)
  for (line <- 0 until pendingLineCount) {
    val firstWord = line * cfg.localWordsPerBank
    scatterLineValid(line) := scatterWordValid
      .slice(firstWord, firstWord + cfg.localWordsPerBank).asBits.orR
    scatterLineExisting(line) := scatterWordExisting
      .slice(firstWord, firstWord + cfg.localWordsPerBank).asBits.orR
  }

  val outputRowSlot = (outputCell >> tileCfg.columnIndexWidth)
    .resize(tileCfg.rowIndexWidth)
  val outputColumnSlot = outputCell(tileCfg.columnIndexWidth - 1 downto 0)
  val outputPe = UInt(peIndexWidth bits)
  val outputLocalRow = UInt(cfg.fiberIndexWidth bits)
  outputPe := 0
  outputLocalRow := 0
  for (row <- 0 until tileCfg.maxRows) {
    when(outputRowSlot === row) {
      outputPe := row / cfg.maxFibers
      outputLocalRow := row % cfg.maxFibers
    }
  }
  val outputBank = outputLocalRow.resize(bankIndexWidth)
  val outputWord = outputColumnSlot.resize(cfg.localWordIndexWidth)
  val outputAddress = (outputColumnSlot >> cfg.localWordIndexWidth)
    .resize(cfg.localAddressWidth)

  for (pe <- 0 until cfg.peRows; bank <- 0 until cfg.localBanks) {
    val buffer = localBuffers(pe)
    val line = pe * cfg.localBanks + bank
    val directData = Bits(cfg.localLineWidth bits)
    val directMask = Bits(cfg.localLineBytes bits)
    val accumulatedData = Bits(cfg.localLineWidth bits)
    val accumulatedMask = Bits(cfg.localLineBytes bits)
    directData := 0
    directMask := 0
    accumulatedData := 0
    accumulatedMask := 0
    for (word <- 0 until cfg.localWordsPerBank) {
      val slot = line * cfg.localWordsPerBank + word
      val dataHigh = (word + 1) * cfg.accumulatorWidth - 1
      val dataLow = word * cfg.accumulatorWidth
      val maskHigh = (word + 1) * (cfg.accumulatorWidth / 8) - 1
      val maskLow = word * (cfg.accumulatorWidth / 8)
      directData(dataHigh downto dataLow) := scatterWordValue(slot).asBits
      directMask(maskHigh downto maskLow) := Mux(scatterWordValid(slot),
        B((BigInt(1) << (cfg.accumulatorWidth / 8)) - 1,
          (cfg.accumulatorWidth / 8) bits), B(0, (cfg.accumulatorWidth / 8) bits))

      val oldWord = buffer.io.bus.readResponses(bank).payload(dataHigh downto dataLow).asSInt
      val mergedWord = Mux(pendingWordExisting(slot),
        (oldWord + pendingWordValue(slot)).resized, pendingWordValue(slot))
      accumulatedData(dataHigh downto dataLow) := mergedWord.asBits
      accumulatedMask(maskHigh downto maskLow) := Mux(pendingWordValid(slot),
        B((BigInt(1) << (cfg.accumulatorWidth / 8)) - 1,
          (cfg.accumulatorWidth / 8) bits), B(0, (cfg.accumulatorWidth / 8) bits))
    }

    buffer.io.bus.writes(bank).valid := False
    buffer.io.bus.writes(bank).payload.address := 0
    buffer.io.bus.writes(bank).payload.data := 0
    buffer.io.bus.writes(bank).payload.mask := 0
    buffer.io.bus.readCommands(bank).valid := False
    buffer.io.bus.readCommands(bank).payload := 0

    when(state === TrIpTileState.Scatter && scatterLineValid(line) &&
        !scatterLineExisting(line)) {
      buffer.io.bus.writes(bank).valid := True
      buffer.io.bus.writes(bank).payload.address := scatterLineAddress
      buffer.io.bus.writes(bank).payload.data := directData
      buffer.io.bus.writes(bank).payload.mask := directMask
    }

    when(state === TrIpTileState.Scatter && scatterLineExisting(line)) {
      buffer.io.bus.readCommands(bank).valid := True
      buffer.io.bus.readCommands(bank).payload := scatterLineAddress
    }

    when(state === TrIpTileState.ScatterAccumWait && pendingValid(line) &&
        buffer.io.bus.readResponses(bank).valid) {
      buffer.io.bus.writes(bank).valid := True
      buffer.io.bus.writes(bank).payload.address := pendingAddress(line)
      buffer.io.bus.writes(bank).payload.data := accumulatedData
      buffer.io.bus.writes(bank).payload.mask := accumulatedMask
    }

    when(state === TrIpTileState.ReadPlan && resultValid(outputCell) &&
        outputPe === pe && outputBank === bank) {
      buffer.io.bus.readCommands(bank).valid := True
      buffer.io.bus.readCommands(bank).payload := outputAddress
    }
  }

  val accumulationIssued = scatterLineExisting.asBits.orR
  val accumulationReady = Bool()
  accumulationReady := True
  for (pe <- 0 until cfg.peRows; bank <- 0 until cfg.localBanks) {
    val line = pe * cfg.localBanks + bank
    when(pendingValid(line) &&
        !localBuffers(pe).io.bus.readResponses(bank).valid) {
      accumulationReady := False
    }
  }

  val selectedReadValid = Bool()
  val selectedReadData = Bits(cfg.accumulatorWidth bits)
  selectedReadValid := False
  selectedReadData := 0
  for (pe <- 0 until cfg.peRows; bank <- 0 until cfg.localBanks) {
    when(outputPe === pe && outputBank === bank) {
      selectedReadValid := localBuffers(pe).io.bus.readResponses(bank).valid
      for (word <- 0 until cfg.localWordsPerBank) {
        val high = (word + 1) * cfg.accumulatorWidth - 1
        val low = word * cfg.accumulatorWidth
        when(outputWord === word) {
          selectedReadData := localBuffers(pe).io.bus.readResponses(bank)
            .payload(high downto low)
        }
      }
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

  val sequenceTagMismatch = Bool()
  sequenceTagMismatch := False
  for (row <- 0 until tileCfg.maxRows) {
    when(U(row, tileCfg.rowCountWidth bits) < io.start.payload.rowCount &&
        aLoaded(row) && aStorage(row).tag =/= sequenceARowTags(row)) {
      sequenceTagMismatch := True
    }
  }
  for (column <- 0 until tileCfg.maxColumns) {
    when(U(column, tileCfg.columnCountWidth bits) < io.start.payload.columnCount &&
        bLoaded(column) && bStorage(column).tag =/= sequenceBColumnTags(column)) {
      sequenceTagMismatch := True
    }
  }

  def advanceSchedule(): Unit = {
    when(finalBatch) {
      bBase := 0
      when(finalAGroup) {
        when(lastKTile) {
          outputCell := 0
          state := TrIpTileState.ReadPlan
        } otherwise {
          state := TrIpTileState.Idle
          doneReg := True
        }
      } otherwise {
        aOffset := (aOffset + groupSize).resized
        groupSize := (U(cfg.maxFibers, fiberCounterWidth bits) -
          (aOffset + groupSize).resized).resized
        state := TrIpTileState.Preflight
      }
    } otherwise {
      bBase := (bBase + batchColumns).resized
      state := TrIpTileState.Plan
    }
  }

  switch(state) {
    is(TrIpTileState.Idle) {
      when(io.start.fire) {
        doneReg := False
        modeReg := io.start.payload.mode
        rowCount := io.start.payload.rowCount
        columnCount := io.start.payload.columnCount
        lastKTile := io.start.payload.lastKTile
        aOffset := 0
        groupSize := cfg.maxFibers
        bBase := 0
        outputCell := 0
        val invalidStart = (io.start.payload.mode =/= TrapezoidDataflow.DenseIp &&
          io.start.payload.mode =/= TrapezoidDataflow.TrIp) ||
          io.start.payload.rowCount > tileCfg.maxRows ||
          io.start.payload.columnCount > tileCfg.maxColumns ||
          (!io.start.payload.firstKTile && (!accumulationOpen ||
            io.start.payload.rowCount =/= rowCount ||
            io.start.payload.columnCount =/= columnCount || sequenceTagMismatch))
        modeError := invalidStart
        when(io.start.payload.firstKTile) {
          for (cell <- 0 until cellCount) resultValid(cell) := False
          for (row <- 0 until tileCfg.maxRows) sequenceARowTags(row) := aStorage(row).tag
          for (column <- 0 until tileCfg.maxColumns) {
            sequenceBColumnTags(column) := bStorage(column).tag
          }
        }
        when(!invalidStart) {
          when(io.start.payload.firstKTile) {
            accumulationOpen := !io.start.payload.lastKTile
          } elsewhen(io.start.payload.lastKTile) {
            accumulationOpen := False
          }
        }
        when(io.start.payload.rowCount === 0 || io.start.payload.columnCount === 0) {
          doneReg := True
        } otherwise {
          state := TrIpTileState.Preflight
        }
      }
    }

    is(TrIpTileState.Preflight) {
      // Scan every B column before dispatch.  If one column cannot fit, restart
      // the scan with a smaller A group so no completed column is replayed.
      when(modeError) {
        state := TrIpTileState.Idle
        doneReg := True
      } elsewhen(globalChoice === 0) {
        when(groupSize > 1) {
          groupSize := groupSize - 1
          bBase := 0
        } otherwise {
          modeError := True
          state := TrIpTileState.Idle
          doneReg := True
        }
      } elsewhen(bBase.resize(tileCfg.columnCountWidth + 1) + 1 >= columnCount) {
        bBase := 0
        state := TrIpTileState.Plan
      } otherwise {
        bBase := bBase + 1
      }
    }

    is(TrIpTileState.Plan) {
      when(modeError) {
        state := TrIpTileState.Idle
        doneReg := True
      } elsewhen(globalChoice =/= 0) {
        batchColumns := globalChoice
        state := TrIpTileState.Dispatch
      } otherwise {
        // A zero choice after dispatch has begun must not change the A group,
        // because that would replay already accumulated output coordinates.
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
        state := TrIpTileState.Scatter
      }
    }

    is(TrIpTileState.Scatter) {
      when(accumulationIssued) {
        for (pe <- 0 until cfg.peRows; bank <- 0 until cfg.localBanks) {
          val line = pe * cfg.localBanks + bank
          pendingValid(line) := scatterLineExisting(line)
          when(scatterLineExisting(line)) {
            pendingAddress(line) := scatterLineAddress
            for (word <- 0 until cfg.localWordsPerBank) {
              val slot = line * cfg.localWordsPerBank + word
              pendingWordValid(slot) := scatterWordValid(slot)
              pendingWordExisting(slot) := scatterWordExisting(slot)
              pendingWordValue(slot) := scatterWordValue(slot)
            }
          }
        }
        state := TrIpTileState.ScatterAccumWait
      } otherwise {
        advanceSchedule()
      }
    }

    is(TrIpTileState.ScatterAccumWait) {
      when(accumulationReady) {
        for (line <- 0 until pendingLineCount) pendingValid(line) := False
        advanceSchedule()
      }
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
    accumulationOpen := False
  }
}

object GenerateTrIpTileController extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-tile",
    oneFilePerComponent = true
  ).generate(new TrIpTileController(TrIpTileConfig(TrapezoidConfig.small)))
}
