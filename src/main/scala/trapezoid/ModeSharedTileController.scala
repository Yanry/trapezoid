package trapezoid

import spinal.core._
import spinal.lib._

object ModeSharedPeClient extends SpinalEnum {
  val TrIp, TrGt, TrGs = newElement()
}

/** Tile-level TrIP/TrGT/TrGS integration around one PE and local buffer per row.
  *
  * TrIP reserves every PE row for one matrix tile. TrGT reserves rows
  * independently, allowing Gustavson tiles to overlap when TrIP is idle. All
  * three paths issue into the same physical TrapezoidPERow, matching the
  * paper's mode-selected compute organization.
  */
class ModeSharedTileController(
    tripCfg: TrIpTileConfig,
    gustavsonCfg: GustavsonTileConfig,
    wideTrGsOutput: Boolean = false
) extends Component {
  private val cfg = tripCfg.core
  private val peInFlightWidth = log2Up(cfg.pePipelineStages + 2)
  require(gustavsonCfg.core == cfg,
    "TrIP, TrGT, and TrGS must use the same PE-row configuration")

  val io = new Bundle {
    val clear = in Bool()

    val tripALoad = slave(Stream(IndexedFiber(cfg, tripCfg.rowIndexWidth)))
    val tripBLoad = slave(Stream(IndexedFiber(cfg, tripCfg.columnIndexWidth)))
    val tripStart = slave(Stream(TrIpTileStart(tripCfg)))
    val tripResult = master(Stream(MatrixResult(cfg)))
    val tripBusy = out Bool()
    val tripDone = out Bool()
    val tripAwaitingKTile = out Bool()
    val tripUnsupportedMode = out Bool()

    val trgtALoad = Vec(slave(Stream(IndexedGustavsonA(cfg))), cfg.peRows)
    val trgtStart = Vec(slave(Stream(GustavsonStart())), cfg.peRows)
    val trgtBPush = Vec(
      Vec(slave(Stream(GustavsonB(cfg))), cfg.localBanks), cfg.peRows)
    val trgtFinish = in Bits(cfg.peRows bits)
    val trgtResult = Vec(master(Stream(MatrixResult(cfg))), cfg.peRows)
    val trgtBusy = out Bits(cfg.peRows bits)
    val trgtDone = out Bits(cfg.peRows bits)
    val trgtOverflow = out Bits(cfg.peRows bits)
    val trgtUnsupportedMode = out Bits(cfg.peRows bits)

    val trgsCommand = Vec(slave(Stream(TrGsCommand(cfg))), cfg.peRows)
    val trgsResult = Vec(master(Stream(MatrixResult(cfg))), cfg.peRows)
    val trgsResultLine = Vec(master(Stream(MatrixResultLine(cfg))), cfg.peRows)
    val trgsBusy = out Bits(cfg.peRows bits)
    val trgsDone = out Bits(cfg.peRows bits)
    val trgsOverflow = out Bits(cfg.peRows bits)
    val trgsUnsupportedMode = out Bits(cfg.peRows bits)
    val trgsAccumulatorCycles = out Vec(UInt(32 bits), cfg.peRows)
    val trgsAccumulatorUpdates = out Vec(UInt(32 bits), cfg.peRows)
    val trgsBankReplayCycles = out Vec(UInt(32 bits), cfg.peRows)

    val localBufferOwners = out Vec(LocalBufferOwner(), cfg.peRows)
  }

  val trip = new TrIpTileController(
    tripCfg, modeSharedLocalBuffers = true, externalPeArray = true)
  val trgt = Array.fill(cfg.peRows)(
    new TrGtSubrowScheduler(gustavsonCfg,
      externalFifoStorage = true, externalPe = true))
  val trgs = Array.fill(cfg.peRows)(
    new TrGsSpatialEngine(gustavsonCfg, wideOutput = wideTrGsOutput,
      externalPe = true,
      externalAccumulatorStorage = true))
  val peRows = Array.fill(cfg.peRows)(new TrapezoidPERow(cfg))
  val trgtOwned = Vec((0 until cfg.peRows).map(_ => RegInit(False)))
  val trgsOwned = Vec((0 until cfg.peRows).map(_ => RegInit(False)))
  val peInFlight = Vec((0 until cfg.peRows).map(_ =>
    Reg(UInt(peInFlightWidth bits)) init 0))

  trip.io.clear := io.clear
  trip.io.aLoad.valid := io.tripALoad.valid
  trip.io.aLoad.payload := io.tripALoad.payload
  io.tripALoad.ready := trip.io.aLoad.ready
  trip.io.bLoad.valid := io.tripBLoad.valid
  trip.io.bLoad.payload := io.tripBLoad.payload
  io.tripBLoad.ready := trip.io.bLoad.ready

  val anyGustavsonOwned = trgtOwned.asBits.orR || trgsOwned.asBits.orR
  val anyPeInFlight = Vec((0 until cfg.peRows).map(row =>
    peInFlight(row) =/= 0)).asBits.orR
  val tripMayStart = !anyGustavsonOwned && !anyPeInFlight
  trip.io.start.valid := io.tripStart.valid && tripMayStart
  trip.io.start.payload := io.tripStart.payload
  io.tripStart.ready := trip.io.start.ready && tripMayStart
  io.tripResult <> trip.io.result
  io.tripBusy := trip.io.busy
  io.tripDone := trip.io.done
  io.tripAwaitingKTile := trip.io.awaitingKTile
  io.tripUnsupportedMode := trip.io.unsupportedMode

  val tripClaimsArray = io.tripStart.valid && tripMayStart
  for (row <- 0 until cfg.peRows) {
    val scheduler = trgt(row)
    val spatial = trgs(row)
    val pe = peRows(row)
    val rowFree = !trgtOwned(row) && !trgsOwned(row) && peInFlight(row) === 0
    val trgtMayStart = !trip.io.busy && !tripClaimsArray && rowFree

    scheduler.io.clear := io.clear
    scheduler.io.fifoStorageActive := trgtOwned(row)
    scheduler.io.aLoad.valid := io.trgtALoad(row).valid
    scheduler.io.aLoad.payload := io.trgtALoad(row).payload
    io.trgtALoad(row).ready := scheduler.io.aLoad.ready
    scheduler.io.start.valid := io.trgtStart(row).valid && trgtMayStart
    scheduler.io.start.payload := io.trgtStart(row).payload
    io.trgtStart(row).ready := scheduler.io.start.ready && trgtMayStart
    scheduler.io.finish := io.trgtFinish(row)
    for (subrow <- 0 until cfg.localBanks) {
      scheduler.io.bPush(subrow).valid := io.trgtBPush(row)(subrow).valid
      scheduler.io.bPush(subrow).payload := io.trgtBPush(row)(subrow).payload
      io.trgtBPush(row)(subrow).ready := scheduler.io.bPush(subrow).ready
    }
    io.trgtResult(row) <> scheduler.io.result
    io.trgtBusy(row) := scheduler.io.busy
    io.trgtDone(row) := scheduler.io.done
    io.trgtOverflow(row) := scheduler.io.overflow
    io.trgtUnsupportedMode(row) := scheduler.io.unsupportedMode

    val trgtClaimsRow = io.trgtStart(row).valid && trgtMayStart
    val trgsMayStart = !trip.io.busy && !tripClaimsArray && rowFree &&
      !trgtClaimsRow
    val trgsSelected = trgsOwned(row) || trgsMayStart
    spatial.io.clear := io.clear
    spatial.io.command.valid := io.trgsCommand(row).valid && trgsSelected
    spatial.io.command.payload := io.trgsCommand(row).payload
    io.trgsCommand(row).ready := spatial.io.command.ready && trgsSelected
    io.trgsResult(row) <> spatial.io.result
    io.trgsResultLine(row) <> spatial.io.resultLine
    io.trgsBusy(row) := spatial.io.busy
    io.trgsDone(row) := spatial.io.done
    io.trgsOverflow(row) := spatial.io.overflow
    io.trgsUnsupportedMode(row) := spatial.io.unsupportedMode
    io.trgsAccumulatorCycles(row) := spatial.io.accumulatorCycles
    io.trgsAccumulatorUpdates(row) := spatial.io.accumulatorUpdates
    io.trgsBankReplayCycles(row) := spatial.io.bankReplayCycles

    val commandFromTrgt = trgtOwned(row)
    val commandFromTrgs = !commandFromTrgt && trgsSelected
    pe.io.command.valid := Mux(commandFromTrgt,
      scheduler.io.peCommand.valid, Mux(commandFromTrgs,
        spatial.io.peCommand.valid, trip.io.peCommands(row).valid))
    pe.io.command.payload := Mux(commandFromTrgt,
      scheduler.io.peCommand.payload, Mux(commandFromTrgs,
        spatial.io.peCommand.payload, trip.io.peCommands(row).payload))
    scheduler.io.peCommand.ready := pe.io.command.ready && commandFromTrgt
    spatial.io.peCommand.ready := pe.io.command.ready && commandFromTrgs
    trip.io.peCommands(row).ready := pe.io.command.ready &&
      !commandFromTrgt && !commandFromTrgs

    // Response ownership is captured with the command so a clear or mode
    // transition cannot redirect an in-flight PE response to the other client.
    val responseOwner = Reg(ModeSharedPeClient()) init ModeSharedPeClient.TrIp
    when(pe.io.command.fire) {
      when(commandFromTrgt) {
        responseOwner := ModeSharedPeClient.TrGt
      } elsewhen(commandFromTrgs) {
        responseOwner := ModeSharedPeClient.TrGs
      } otherwise {
        responseOwner := ModeSharedPeClient.TrIp
      }
    }
    scheduler.io.peResponse.valid :=
      pe.io.response.valid && responseOwner === ModeSharedPeClient.TrGt
    scheduler.io.peResponse.payload := pe.io.response.payload
    spatial.io.peResponse.valid :=
      pe.io.response.valid && responseOwner === ModeSharedPeClient.TrGs
    spatial.io.peResponse.payload := pe.io.response.payload
    trip.io.peResponses(row).valid :=
      pe.io.response.valid && responseOwner === ModeSharedPeClient.TrIp
    trip.io.peResponses(row).payload := pe.io.response.payload
    pe.io.response.ready := trip.io.peResponses(row).ready
    switch(responseOwner) {
      is(ModeSharedPeClient.TrGt) {
        pe.io.response.ready := scheduler.io.peResponse.ready
      }
      is(ModeSharedPeClient.TrGs) {
        pe.io.response.ready := spatial.io.peResponse.ready
      }
    }
    when(pe.io.command.fire =/= pe.io.response.fire) {
      when(pe.io.command.fire) {
        peInFlight(row) := peInFlight(row) + 1
      } otherwise {
        peInFlight(row) := peInFlight(row) - 1
      }
    }

    val owner = Mux(trgtOwned(row) || trgsOwned(row),
      LocalBufferOwner.Gustavson, LocalBufferOwner.TrIp)
    io.localBufferOwners(row) := owner
    trip.io.localBufferOwners(row) := owner
    for (bank <- 0 until cfg.localBanks) {
      val buffer = trip.io.gustavsonLocalBuffers(row)
      val trgsUsesBuffer = trgsOwned(row)
      buffer.writes(bank).valid := Mux(trgsUsesBuffer,
        spatial.io.accumulatorStorage.writes(bank).valid,
        scheduler.io.fifoStorage.writes(bank).valid)
      buffer.writes(bank).payload := Mux(trgsUsesBuffer,
        spatial.io.accumulatorStorage.writes(bank).payload,
        scheduler.io.fifoStorage.writes(bank).payload)
      buffer.readCommands(bank).valid := Mux(trgsUsesBuffer,
        spatial.io.accumulatorStorage.readCommands(bank).valid,
        scheduler.io.fifoStorage.readCommands(bank).valid)
      buffer.readCommands(bank).payload := Mux(trgsUsesBuffer,
        spatial.io.accumulatorStorage.readCommands(bank).payload,
        scheduler.io.fifoStorage.readCommands(bank).payload)

      val readForTrgs = RegInit(False)
      when(buffer.readCommands(bank).valid) {
        readForTrgs := trgsUsesBuffer
      }
      scheduler.io.fifoStorage.readResponses(bank).valid :=
        buffer.readResponses(bank).valid && !readForTrgs
      scheduler.io.fifoStorage.readResponses(bank).payload :=
        buffer.readResponses(bank).payload
      spatial.io.accumulatorStorage.readResponses(bank).valid :=
        buffer.readResponses(bank).valid && readForTrgs
      spatial.io.accumulatorStorage.readResponses(bank).payload :=
        buffer.readResponses(bank).payload
    }

    when(scheduler.io.start.fire) {
      trgtOwned(row) := True
    }
    when(spatial.io.command.fire && !trgsOwned(row)) {
      trgsOwned(row) := True
    }
    when(trgtOwned(row) && scheduler.io.done && !scheduler.io.busy) {
      trgtOwned(row) := False
    }
    when(trgsOwned(row) && spatial.io.done && !spatial.io.busy) {
      trgsOwned(row) := False
    }
    when(io.clear) {
      trgtOwned(row) := False
      trgsOwned(row) := False
    }
  }
}

object GenerateModeSharedTileController extends App {
  private val cfg = TrapezoidConfig.small
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-shared-tile",
    oneFilePerComponent = true
  ).generate(new ModeSharedTileController(
    TrIpTileConfig(cfg), GustavsonTileConfig(cfg)))
}
