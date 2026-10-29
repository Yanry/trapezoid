package trapezoid

import spinal.core._
import spinal.lib._

object CacheBankState extends SpinalEnum {
  val Idle, Lookup = newElement()
}

object CacheMshrState extends SpinalEnum {
  val Free, Reserved, RequestRefill, WaitRefill, Respond = newElement()
}

/** Multi-client banked read cache with per-bank MSHRs and refill channels. */
class BankedReadCache(cacheCfg: CacheConfig) extends Component {
  private val validBitCount = cacheCfg.setsPerBank * cacheCfg.ways
  private val outstandingCountWidth =
    log2Up(cacheCfg.maxOutstandingPerClient + 1)
  private val mshrIndexWidth = scala.math.max(1, log2Up(cacheCfg.mshrsPerBank))

  val io = new Bundle {
    val clear = in Bool()
    val requests = Vec(slave(Stream(CacheReadRequest(cacheCfg))), cacheCfg.clientCount)
    val responses = Vec(master(Stream(CacheReadResponse(cacheCfg))), cacheCfg.clientCount)
    val refillRequest = master(Stream(CacheRefillRequest(cacheCfg)))
    val refillResponse = slave(Stream(CacheRefillResponse(cacheCfg)))
    val extraRefillRequests = if (cacheCfg.refillChannelCount > 1)
      Vec(master(Stream(CacheRefillRequest(cacheCfg))),
        cacheCfg.refillChannelCount - 1) else null
    val extraRefillResponses = if (cacheCfg.refillChannelCount > 1)
      Vec(slave(Stream(CacheRefillResponse(cacheCfg))),
        cacheCfg.refillChannelCount - 1) else null
    val bankBusy = out Bits(cacheCfg.bankCount bits)
    val clientOutstanding = out Bits(cacheCfg.clientCount bits)
    val hitCount = out UInt(32 bits)
    val missCount = out UInt(32 bits)
    val refillCount = out UInt(32 bits)
  }

  private val refillRequestPorts = (0 until cacheCfg.refillChannelCount).map {
    channel => if (channel == 0) io.refillRequest
      else io.extraRefillRequests(channel - 1)
  }
  private val refillResponsePorts = (0 until cacheCfg.refillChannelCount).map {
    channel => if (channel == 0) io.refillResponse
      else io.extraRefillResponses(channel - 1)
  }

  private def bankOf(address: UInt): UInt = if (cacheCfg.bankCount == 1) {
    U(0, cacheCfg.bankIndexWidth bits)
  } else address(log2Up(cacheCfg.bankCount) - 1 downto 0)

  private def setOf(address: UInt): UInt = {
    val low = log2Up(cacheCfg.bankCount)
    address(low + log2Up(cacheCfg.setsPerBank) - 1 downto low)
  }

  private def tagOf(address: UInt): UInt = {
    val low = log2Up(cacheCfg.bankCount) + log2Up(cacheCfg.setsPerBank)
    address(cacheCfg.addressWidth - 1 downto low)
  }

  val bankStates = Vec((0 until cacheCfg.bankCount)
    .map(_ => RegInit(CacheBankState.Idle)))
  val lookupMshr = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(UInt(mshrIndexWidth bits)) init 0))
  val validBits = Vec((0 until cacheCfg.bankCount).map(_ =>
    Reg(Bits(validBitCount bits)) init 0))
  val dataMemories = Array.fill(cacheCfg.bankCount, cacheCfg.ways)(
    new TrapezoidSyncSram(cacheCfg.lineWidth, cacheCfg.setsPerBank))
  val tagMemories = Array.fill(cacheCfg.bankCount, cacheCfg.ways)(
    new TrapezoidSyncSram(cacheCfg.tagWidth, cacheCfg.setsPerBank))
  val replacementMemories = Array.fill(cacheCfg.bankCount)(
    Mem(UInt(cacheCfg.wayIndexWidth bits), cacheCfg.setsPerBank))

  val mshrStates = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ =>
      RegInit(CacheMshrState.Free))))
  val mshrAddress = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ =>
      Reg(UInt(cacheCfg.addressWidth bits)) init 0)))
  val mshrSet = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ =>
      Reg(UInt(cacheCfg.setIndexWidth bits)) init 0)))
  val mshrClient = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ =>
      Reg(UInt(cacheCfg.clientIndexWidth bits)) init 0)))
  val mshrRequestId = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ =>
      Reg(UInt(cacheCfg.requestIdWidth bits)) init 0)))
  val mshrVictim = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ =>
      Reg(UInt(cacheCfg.wayIndexWidth bits)) init 0)))
  val mshrData = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ =>
      Reg(Bits(cacheCfg.lineWidth bits)) init 0)))
  val mshrWasHit = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ => RegInit(False))))
  val mshrError = Array.fill(cacheCfg.bankCount)(
    Vec((0 until cacheCfg.mshrsPerBank).map(_ => RegInit(False))))

  val outstandingCounts = Vec((0 until cacheCfg.clientCount).map(_ =>
    Reg(UInt(outstandingCountWidth bits)) init 0))
  val requestOrder = Array.fill(cacheCfg.clientCount)(
    StreamFifo(UInt(cacheCfg.requestIdWidth bits),
      cacheCfg.maxOutstandingPerClient))
  val responseQueues = Array.fill(cacheCfg.clientCount)(
    StreamFifo(CacheReadResponse(cacheCfg),
      cacheCfg.maxOutstandingPerClient))
  val hitCount = Reg(UInt(32 bits)) init 0
  val missCount = Reg(UInt(32 bits)) init 0
  val refillCount = Reg(UInt(32 bits)) init 0

  for (client <- 0 until cacheCfg.clientCount) {
    requestOrder(client).io.flush := io.clear
    responseQueues(client).io.flush := io.clear
    requestOrder(client).io.push.valid := False
    requestOrder(client).io.push.payload := io.requests(client).payload.requestId
    responseQueues(client).io.push.valid := False
    responseQueues(client).io.push.payload := CacheReadResponse(cacheCfg).getZero
    io.responses(client) <> responseQueues(client).io.pop
    io.clientOutstanding(client) := outstandingCounts(client) =/= 0
  }

  val freeMshrFound = Vec(Bool(), cacheCfg.bankCount)
  val freeMshrIndex = Vec(UInt(mshrIndexWidth bits), cacheCfg.bankCount)
  for (bank <- 0 until cacheCfg.bankCount) {
    val free = Bits(cacheCfg.mshrsPerBank bits)
    for (slot <- 0 until cacheCfg.mshrsPerBank) {
      free(slot) := mshrStates(bank)(slot) === CacheMshrState.Free
    }
    freeMshrFound(bank) := free.orR
    freeMshrIndex(bank) := OHToUInt(OHMasking.first(free)).resized
    io.bankBusy(bank) := bankStates(bank) =/= CacheBankState.Idle ||
      !free.andR
  }

  val bankAccepts = Vec(Bool(), cacheCfg.bankCount)
  val selectedClients = Vec(UInt(cacheCfg.clientIndexWidth bits), cacheCfg.bankCount)
  for (bank <- 0 until cacheCfg.bankCount) {
    var earlierEligible: Bool = False
    val choices = Vec(Bool(), cacheCfg.clientCount)
    for (client <- 0 until cacheCfg.clientCount) {
      val eligible = io.requests(client).valid &&
        outstandingCounts(client) < cacheCfg.maxOutstandingPerClient &&
        requestOrder(client).io.push.ready &&
        bankOf(io.requests(client).payload.address) === bank
      choices(client) := eligible && !earlierEligible
      earlierEligible = earlierEligible || eligible
    }
    bankAccepts(bank) := choices.asBits.orR && freeMshrFound(bank) &&
      bankStates(bank) === CacheBankState.Idle
    selectedClients(bank) := 0
    for (client <- 0 until cacheCfg.clientCount) {
      when(choices(client)) { selectedClients(bank) := client }
    }
  }

  for (client <- 0 until cacheCfg.clientCount) {
    io.requests(client).ready := False
    for (bank <- 0 until cacheCfg.bankCount) {
      when(bankAccepts(bank) && selectedClients(bank) === client) {
        io.requests(client).ready := !io.clear
      }
    }
  }

  val readWayData = Array.fill(cacheCfg.bankCount, cacheCfg.ways)(
    Bits(cacheCfg.lineWidth bits))
  val readWayTags = Array.fill(cacheCfg.bankCount, cacheCfg.ways)(
    UInt(cacheCfg.tagWidth bits))
  val readReplacement = Vec(UInt(cacheCfg.wayIndexWidth bits), cacheCfg.bankCount)
  val hitEvents = Bits(cacheCfg.bankCount bits)
  val missEvents = Bits(cacheCfg.bankCount bits)
  hitEvents := 0
  missEvents := 0

  val refillWriteValid = Vec(Bool(), cacheCfg.bankCount)
  val refillWriteSlot = Vec(UInt(mshrIndexWidth bits), cacheCfg.bankCount)
  val refillWriteData = Vec(Bits(cacheCfg.lineWidth bits), cacheCfg.bankCount)
  val refillWriteError = Vec(Bool(), cacheCfg.bankCount)
  refillWriteValid.foreach(_ := False)
  refillWriteSlot.foreach(_ := 0)
  refillWriteData.foreach(_ := 0)
  refillWriteError.foreach(_ := False)
  val refillSuccessEvents = Bits(cacheCfg.refillChannelCount bits)
  refillSuccessEvents := 0

  val responseSelections = Array.fill(cacheCfg.refillChannelCount,
    cacheCfg.bankCount, cacheCfg.mshrsPerBank)(Bool())
  val earlierResponseChannel = Array.fill(cacheCfg.bankCount)(False: Bool)
  for (channel <- 0 until cacheCfg.refillChannelCount) {
    var channelSelected: Bool = False
    for (bank <- 0 until cacheCfg.bankCount) {
      var earlierSlot: Bool = False
      for (slot <- 0 until cacheCfg.mshrsPerBank) {
        val matches = refillResponsePorts(channel).valid &&
          refillResponsePorts(channel).payload.bank === bank &&
          mshrStates(bank)(slot) === CacheMshrState.WaitRefill &&
          refillResponsePorts(channel).payload.address === mshrAddress(bank)(slot)
        responseSelections(channel)(bank)(slot) := matches &&
          !earlierSlot && !earlierResponseChannel(bank)
        earlierSlot = earlierSlot || matches
        channelSelected = channelSelected ||
          responseSelections(channel)(bank)(slot)
      }
      earlierResponseChannel(bank) = earlierResponseChannel(bank) || earlierSlot
    }
    refillResponsePorts(channel).ready := !io.clear && channelSelected
    for (bank <- 0 until cacheCfg.bankCount; slot <- 0 until cacheCfg.mshrsPerBank) {
      when(refillResponsePorts(channel).fire &&
          responseSelections(channel)(bank)(slot)) {
        refillWriteValid(bank) := True
        refillWriteSlot(bank) := slot
        refillWriteData(bank) := refillResponsePorts(channel).payload.data
        refillWriteError(bank) := refillResponsePorts(channel).payload.error
        mshrData(bank)(slot) := refillResponsePorts(channel).payload.data
        mshrError(bank)(slot) := refillResponsePorts(channel).payload.error
        mshrStates(bank)(slot) := CacheMshrState.Respond
        refillSuccessEvents(channel) := !refillResponsePorts(channel).payload.error
      }
    }
  }

  for (bank <- 0 until cacheCfg.bankCount) {
    val selectedRequest = CacheReadRequest(cacheCfg)
    selectedRequest := CacheReadRequest(cacheCfg).getZero
    for (client <- 0 until cacheCfg.clientCount) {
      when(selectedClients(bank) === client) {
        selectedRequest := io.requests(client).payload
      }
    }
    val accept = bankAccepts(bank) && !io.clear
    val requestSet = setOf(selectedRequest.address)
    for (way <- 0 until cacheCfg.ways) {
      val writeSlot = refillWriteSlot(bank)
      val refillWrite = refillWriteValid(bank) &&
        !refillWriteError(bank) && mshrVictim(bank)(writeSlot) === way
      dataMemories(bank)(way).io.read_enable := accept
      dataMemories(bank)(way).io.read_address := requestSet
      dataMemories(bank)(way).io.write_enable := refillWrite
      dataMemories(bank)(way).io.write_address := mshrSet(bank)(writeSlot)
      dataMemories(bank)(way).io.write_data := refillWriteData(bank)
      tagMemories(bank)(way).io.read_enable := accept
      tagMemories(bank)(way).io.read_address := requestSet
      tagMemories(bank)(way).io.write_enable := refillWrite
      tagMemories(bank)(way).io.write_address := mshrSet(bank)(writeSlot)
      tagMemories(bank)(way).io.write_data :=
        tagOf(mshrAddress(bank)(writeSlot)).asBits
      readWayData(bank)(way) := dataMemories(bank)(way).io.read_data
      readWayTags(bank)(way) := tagMemories(bank)(way).io.read_data.asUInt
    }
    readReplacement(bank) := replacementMemories(bank).readSync(requestSet, accept)

    when(accept) {
      val slot = freeMshrIndex(bank)
      lookupMshr(bank) := slot
      mshrStates(bank)(slot) := CacheMshrState.Reserved
      mshrAddress(bank)(slot) := selectedRequest.address
      mshrSet(bank)(slot) := requestSet
      mshrClient(bank)(slot) := selectedClients(bank)
      mshrRequestId(bank)(slot) := selectedRequest.requestId
      mshrWasHit(bank)(slot) := False
      mshrError(bank)(slot) := False
      bankStates(bank) := CacheBankState.Lookup
    }

    when(bankStates(bank) === CacheBankState.Lookup) {
      val slot = lookupMshr(bank)
      val hitWays = Bits(cacheCfg.ways bits)
      val invalidWays = Bits(cacheCfg.ways bits)
      val selectedHitData = Bits(cacheCfg.lineWidth bits)
      val selectedVictim = UInt(cacheCfg.wayIndexWidth bits)
      selectedHitData := 0
      selectedVictim := readReplacement(bank)
      for (way <- 0 until cacheCfg.ways) {
        val validIndex = if (cacheCfg.ways == 1) mshrSet(bank)(slot)
          else (mshrSet(bank)(slot).asBits ##
            U(way, cacheCfg.wayIndexWidth bits).asBits).asUInt
        hitWays(way) := validBits(bank)(validIndex) &&
          readWayTags(bank)(way) === tagOf(mshrAddress(bank)(slot))
        invalidWays(way) := !validBits(bank)(validIndex)
        when(hitWays(way)) { selectedHitData := readWayData(bank)(way) }
      }
      for (way <- (0 until cacheCfg.ways).reverse) {
        when(invalidWays(way)) { selectedVictim := way }
      }
      mshrVictim(bank)(slot) := selectedVictim
      when(hitWays.orR) {
        mshrData(bank)(slot) := selectedHitData
        mshrWasHit(bank)(slot) := True
        mshrStates(bank)(slot) := CacheMshrState.Respond
        hitEvents(bank) := True
      } otherwise {
        mshrStates(bank)(slot) := CacheMshrState.RequestRefill
        missEvents(bank) := True
      }
      bankStates(bank) := CacheBankState.Idle
    }

    when(refillWriteValid(bank)) {
      val slot = refillWriteSlot(bank)
      when(!refillWriteError(bank)) {
        val validIndex = if (cacheCfg.ways == 1) mshrSet(bank)(slot)
          else (mshrSet(bank)(slot).asBits ##
            mshrVictim(bank)(slot).asBits).asUInt
        validBits(bank)(validIndex) := True
        replacementMemories(bank).write(mshrSet(bank)(slot),
          if (cacheCfg.ways == 1) U(0, cacheCfg.wayIndexWidth bits)
          else (mshrVictim(bank)(slot) + 1).resized)
      }
    }
  }

  val hitsThisCycle = CountOne(hitEvents).resize(32)
  val missesThisCycle = CountOne(missEvents).resize(32)
  val refillsThisCycle = CountOne(refillSuccessEvents).resize(32)
  when(hitsThisCycle =/= 0) { hitCount := hitCount + hitsThisCycle }
  when(missesThisCycle =/= 0) { missCount := missCount + missesThisCycle }
  when(refillsThisCycle =/= 0) { refillCount := refillCount + refillsThisCycle }

  val earlierIssuedBank = Array.fill(cacheCfg.bankCount)(False: Bool)
  for (channel <- 0 until cacheCfg.refillChannelCount) {
    val choices = Bits(cacheCfg.bankCount * cacheCfg.mshrsPerBank bits)
    var earlier: Bool = False
    for (bank <- 0 until cacheCfg.bankCount; slot <- 0 until cacheCfg.mshrsPerBank) {
      val index = bank * cacheCfg.mshrsPerBank + slot
      val eligible = mshrStates(bank)(slot) === CacheMshrState.RequestRefill &&
        !earlierIssuedBank(bank)
      choices(index) := eligible && !earlier
      earlier = earlier || eligible
    }
    refillRequestPorts(channel).valid := !io.clear && choices.orR
    refillRequestPorts(channel).payload := CacheRefillRequest(cacheCfg).getZero
    for (bank <- 0 until cacheCfg.bankCount; slot <- 0 until cacheCfg.mshrsPerBank) {
      val index = bank * cacheCfg.mshrsPerBank + slot
      when(choices(index)) {
        refillRequestPorts(channel).payload.bank := bank
        refillRequestPorts(channel).payload.address := mshrAddress(bank)(slot)
        when(refillRequestPorts(channel).fire) {
          mshrStates(bank)(slot) := CacheMshrState.WaitRefill
        }
      }
    }
    for (bank <- 0 until cacheCfg.bankCount) {
      val bankSelected = (0 until cacheCfg.mshrsPerBank)
        .map(slot => choices(bank * cacheCfg.mshrsPerBank + slot))
        .reduce(_ || _)
      earlierIssuedBank(bank) = earlierIssuedBank(bank) || bankSelected
    }
  }

  val acceptedByClient = Bits(cacheCfg.clientCount bits)
  acceptedByClient := 0
  for (bank <- 0 until cacheCfg.bankCount) {
    when(bankAccepts(bank) && !io.clear) {
      for (client <- 0 until cacheCfg.clientCount) {
        when(selectedClients(bank) === client) {
          acceptedByClient(client) := True
          requestOrder(client).io.push.valid := True
          requestOrder(client).io.push.payload := io.requests(client).payload.requestId
        }
      }
    }
  }

  for (client <- 0 until cacheCfg.clientCount) {
    var earlierResponse: Bool = False
    for (bank <- 0 until cacheCfg.bankCount; slot <- 0 until cacheCfg.mshrsPerBank) {
      val eligible = !io.clear &&
        mshrStates(bank)(slot) === CacheMshrState.Respond &&
        mshrClient(bank)(slot) === client && requestOrder(client).io.pop.valid &&
        requestOrder(client).io.pop.payload === mshrRequestId(bank)(slot)
      val selected = eligible && !earlierResponse
      earlierResponse = earlierResponse || eligible
      when(selected) {
        responseQueues(client).io.push.valid := True
        responseQueues(client).io.push.payload.requestId := mshrRequestId(bank)(slot)
        responseQueues(client).io.push.payload.data := mshrData(bank)(slot)
        responseQueues(client).io.push.payload.wasHit := mshrWasHit(bank)(slot)
        responseQueues(client).io.push.payload.error := mshrError(bank)(slot)
        when(responseQueues(client).io.push.fire) {
          mshrStates(bank)(slot) := CacheMshrState.Free
        }
      }
    }
    requestOrder(client).io.pop.ready := responseQueues(client).io.push.fire
    switch(acceptedByClient(client) ## io.responses(client).fire) {
      is(B"10") { outstandingCounts(client) := outstandingCounts(client) + 1 }
      is(B"01") { outstandingCounts(client) := outstandingCounts(client) - 1 }
    }
  }

  io.hitCount := hitCount
  io.missCount := missCount
  io.refillCount := refillCount

  when(io.clear) {
    bankStates.foreach(_ := CacheBankState.Idle)
    validBits.foreach(_ := 0)
    for (bank <- 0 until cacheCfg.bankCount; slot <- 0 until cacheCfg.mshrsPerBank) {
      mshrStates(bank)(slot) := CacheMshrState.Free
    }
    outstandingCounts.foreach(_ := 0)
    hitCount := 0
    missCount := 0
    refillCount := 0
  }
}

object GenerateBankedReadCache extends App {
  SpinalConfig(
    mode = Verilog,
    targetDirectory = "generated-cache",
    oneFilePerComponent = true
  ).generate(new BankedReadCache(CacheConfig(
    wordWidth = 32,
    wordsPerLine = 16,
    addressWidth = 24,
    bankCount = 32,
    setsPerBank = 128,
    clientCount = 32,
    ways = 16,
    mshrsPerBank = 4,
    refillChannelCount = 4
  )))
}
