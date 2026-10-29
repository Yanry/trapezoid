package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.collection.mutable

class BankedReadCacheSpec extends AnyFunSuite {
  private val cacheCfg = CacheConfig(
    wordWidth = 8,
    wordsPerLine = 4,
    addressWidth = 6,
    bankCount = 2,
    setsPerBank = 2,
    clientCount = 2,
    requestIdWidth = 4
  )

  private def simConfig = SimConfig.withVerilator
    .addSimulatorFlag("-CFLAGS -std=c++14")
    .addSimulatorFlag("-LDFLAGS -std=c++14")

  test("banked cache arbitrates conflicts and refills misses with routed responses") {
    simConfig.compile(new BankedReadCache(cacheCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      for (client <- 0 until cacheCfg.clientCount) {
        dut.io.requests(client).valid #= false
        dut.io.responses(client).ready #= false
      }

      def line(address: Int): BigInt =
        BigInt(address + 4) << 24 | BigInt(address + 3) << 16 |
          BigInt(address + 2) << 8 | BigInt(address + 1)

      val failedAddress = 31
      fork {
        while (true) {
          dut.io.refillRequest.ready #= true
          dut.io.refillResponse.valid #= false
          sleep(1)
          if (dut.io.refillRequest.valid.toBoolean) {
            val bank = dut.io.refillRequest.payload.bank.toInt
            val address = dut.io.refillRequest.payload.address.toInt
            dut.clockDomain.waitSampling()
            sleep(1)
            dut.io.refillRequest.ready #= false
            dut.clockDomain.waitSampling(2)
            dut.io.refillResponse.payload.bank #= bank
            dut.io.refillResponse.payload.address #= address
            dut.io.refillResponse.payload.data #=
              (if (address == failedAddress) BigInt(0) else line(address))
            dut.io.refillResponse.payload.error #= (address == failedAddress)
            dut.io.refillResponse.valid #= true
            var accepted = false
            while (!accepted) {
              sleep(1)
              accepted = dut.io.refillResponse.ready.toBoolean
              dut.clockDomain.waitSampling()
            }
            sleep(1)
            dut.io.refillResponse.valid #= false
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      def transact(client: Int, address: Int, id: Int): (BigInt, Boolean, Boolean) = {
        val request = dut.io.requests(client)
        val response = dut.io.responses(client)
        request.payload.address #= address
        request.payload.requestId #= id
        request.valid #= true
        var accepted = false
        var timeout = 0
        while (!accepted && timeout < 500) {
          sleep(1)
          accepted = request.ready.toBoolean
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        sleep(1)
        request.valid #= false
        response.ready #= true
        var received = false
        var result = (BigInt(0), false, false)
        while (!received && timeout < 1000) {
          sleep(1)
          received = response.valid.toBoolean
          if (received) {
            assert(response.payload.requestId.toInt == id)
            result = (response.payload.data.toBigInt,
              response.payload.wasHit.toBoolean,
              response.payload.error.toBoolean)
          }
          dut.clockDomain.waitSampling()
          timeout += 1
        }
        sleep(1)
        response.ready #= false
        assert(accepted && received, s"cache transaction timed out for address $address")
        result
      }

      assert(transact(0, address = 0, id = 1) == ((line(0), false, false)))
      assert(transact(0, address = 0, id = 2) == ((line(0), true, false)))
      assert(dut.io.hitCount.toLong == 1)
      assert(dut.io.missCount.toLong == 1)
      assert(dut.io.refillCount.toLong == 1)

      // Different banks accept requests in the same cycle.
      for ((client, address) <- Seq(0 -> 8, 1 -> 9)) {
        dut.io.requests(client).payload.address #= address
        dut.io.requests(client).payload.requestId #= (client + 3)
        dut.io.requests(client).valid #= true
        dut.io.responses(client).ready #= true
      }
      sleep(1)
      assert(dut.io.requests(0).ready.toBoolean)
      assert(dut.io.requests(1).ready.toBoolean)
      dut.clockDomain.waitSampling()
      sleep(1)
      dut.io.requests(0).valid #= false
      dut.io.requests(1).valid #= false

      val parallelResults = mutable.Map.empty[Int, BigInt]
      var timeout = 0
      while (parallelResults.size < 2 && timeout < 1000) {
        sleep(1)
        for (client <- 0 until 2 if dut.io.responses(client).valid.toBoolean) {
          parallelResults(client) = dut.io.responses(client).payload.data.toBigInt
          assert(!dut.io.responses(client).payload.wasHit.toBoolean)
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      dut.io.responses(0).ready #= false
      dut.io.responses(1).ready #= false
      assert(parallelResults.toMap == Map(0 -> line(8), 1 -> line(9)))
      assert(transact(0, address = 0, id = 12)._2 == false,
        "a different tag in the same bank/set must evict the old line")

      // Same-bank requests are serialized. Fixed priority initially selects client 0.
      for ((client, address) <- Seq(0 -> 2, 1 -> 6)) {
        dut.io.requests(client).payload.address #= address
        dut.io.requests(client).payload.requestId #= (client + 5)
        dut.io.requests(client).valid #= true
        dut.io.responses(client).ready #= true
      }
      sleep(1)
      assert(dut.io.requests(0).ready.toBoolean)
      assert(!dut.io.requests(1).ready.toBoolean)
      dut.clockDomain.waitSampling()
      sleep(1)
      dut.io.requests(0).valid #= false

      var client1Accepted = false
      val conflictResults = mutable.Map.empty[Int, BigInt]
      timeout = 0
      while (conflictResults.size < 2 && timeout < 1500) {
        sleep(1)
        if (!client1Accepted && dut.io.requests(1).ready.toBoolean) {
          client1Accepted = true
        }
        for (client <- 0 until 2 if dut.io.responses(client).valid.toBoolean) {
          conflictResults(client) = dut.io.responses(client).payload.data.toBigInt
        }
        dut.clockDomain.waitSampling()
        if (client1Accepted) {
          sleep(1)
          dut.io.requests(1).valid #= false
        }
        timeout += 1
      }
      dut.io.responses(0).ready #= false
      dut.io.responses(1).ready #= false
      assert(client1Accepted)
      assert(conflictResults.toMap == Map(0 -> line(2), 1 -> line(6)))

      val failed = transact(0, failedAddress, id = 9)
      assert(failed._2 == false && failed._3)
      val missesAfterFailure = dut.io.missCount.toLong
      assert(transact(0, failedAddress, id = 10)._3)
      assert(dut.io.missCount.toLong == missesAfterFailure + 1,
        "an errored refill must not install a cache line")

      dut.io.clear #= true
      dut.clockDomain.waitSampling()
      dut.io.clear #= false
      sleep(1)
      assert(dut.io.hitCount.toLong == 0)
      assert(dut.io.missCount.toLong == 0)
      assert(dut.io.refillCount.toLong == 0)
      assert(transact(0, address = 9, id = 11)._2 == false,
        "clear must invalidate all cache lines")
    }
  }

  test("independent bank misses accept out-of-order refill responses") {
    simConfig.compile(new BankedReadCache(cacheCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      for (client <- 0 until cacheCfg.clientCount) {
        dut.io.requests(client).payload.address #= client
        dut.io.requests(client).payload.requestId #= (client + 1)
        dut.io.requests(client).valid #= true
        dut.io.responses(client).ready #= false
      }

      sleep(1)
      assert(dut.io.requests(0).ready.toBoolean)
      assert(dut.io.requests(1).ready.toBoolean)
      dut.clockDomain.waitSampling()
      sleep(1)
      dut.io.requests(0).valid #= false
      dut.io.requests(1).valid #= false

      val refills = mutable.ArrayBuffer.empty[(Int, Int)]
      var timeout = 0
      while (refills.size < 2 && timeout < 100) {
        sleep(1)
        if (dut.io.refillRequest.valid.toBoolean &&
            dut.io.refillRequest.ready.toBoolean) {
          refills += ((dut.io.refillRequest.payload.bank.toInt,
            dut.io.refillRequest.payload.address.toInt))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      dut.io.refillRequest.ready #= false
      assert(refills.toSeq == Seq(0 -> 0, 1 -> 1))
      assert(dut.io.bankBusy.toBigInt == 3)

      def returnRefill(bank: Int, address: Int): Unit = {
        dut.io.refillResponse.payload.bank #= bank
        dut.io.refillResponse.payload.address #= address
        dut.io.refillResponse.payload.data #= (BigInt(address + 1) * 0x01010101L)
        dut.io.refillResponse.payload.error #= false
        dut.io.refillResponse.valid #= true
        var accepted = false
        var responseTimeout = 0
        while (!accepted && responseTimeout < 100) {
          sleep(1)
          accepted = dut.io.refillResponse.ready.toBoolean
          dut.clockDomain.waitSampling()
          responseTimeout += 1
        }
        dut.io.refillResponse.valid #= false
        assert(accepted, s"refill response for bank $bank timed out")
      }

      for ((bank, address) <- refills.reverse) returnRefill(bank, address)

      timeout = 0
      while ((!dut.io.responses(0).valid.toBoolean ||
          !dut.io.responses(1).valid.toBoolean) && timeout < 100) {
        dut.clockDomain.waitSampling()
        sleep(1)
        timeout += 1
      }
      assert(dut.io.responses(0).valid.toBoolean)
      assert(dut.io.responses(1).valid.toBoolean)
      for (client <- 0 until cacheCfg.clientCount) {
        assert(dut.io.responses(client).payload.requestId.toInt == client + 1)
        assert(dut.io.responses(client).payload.data.toBigInt ==
          BigInt(client + 1) * 0x01010101L)
        assert(!dut.io.responses(client).payload.wasHit.toBoolean)
        assert(!dut.io.responses(client).payload.error.toBoolean)
        dut.io.responses(client).ready #= true
      }
      dut.clockDomain.waitSampling()
      sleep(1)
      assert(dut.io.hitCount.toLong == 0)
      assert(dut.io.missCount.toLong == 2)
      assert(dut.io.refillCount.toLong == 2)
      assert(dut.io.bankBusy.toBigInt == 0)
    }
  }

  test("multiple refill channels issue and complete independent banks together") {
    val multiCfg = cacheCfg.copy(
      bankCount = 4,
      clientCount = 2,
      mshrsPerBank = 2,
      refillChannelCount = 2)
    simConfig.compile(new BankedReadCache(multiCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.refillRequest.ready #= true
      dut.io.extraRefillRequests(0).ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.extraRefillResponses(0).valid #= false
      for (client <- 0 until multiCfg.clientCount) {
        dut.io.requests(client).payload.address #= client
        dut.io.requests(client).payload.requestId #= client + 1
        dut.io.requests(client).valid #= true
        dut.io.responses(client).ready #= true
      }

      dut.clockDomain.waitSampling()
      dut.io.requests(0).valid #= false
      dut.io.requests(1).valid #= false

      var request0 = Option.empty[(Int, Int)]
      var request1 = Option.empty[(Int, Int)]
      var timeout = 0
      while ((request0.isEmpty || request1.isEmpty) && timeout < 100) {
        sleep(1)
        if (dut.io.refillRequest.valid.toBoolean) {
          request0 = Some((dut.io.refillRequest.payload.bank.toInt,
            dut.io.refillRequest.payload.address.toInt))
        }
        if (dut.io.extraRefillRequests(0).valid.toBoolean) {
          request1 = Some((dut.io.extraRefillRequests(0).payload.bank.toInt,
            dut.io.extraRefillRequests(0).payload.address.toInt))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(request0.nonEmpty && request1.nonEmpty)
      assert(request0.get._1 != request1.get._1)

      val line0 = BigInt("11223344", 16)
      val line1 = BigInt("55667788", 16)
      dut.io.refillResponse.payload.bank #= request0.get._1
      dut.io.refillResponse.payload.address #= request0.get._2
      dut.io.refillResponse.payload.data #= line0
      dut.io.refillResponse.payload.error #= false
      dut.io.refillResponse.valid #= true
      dut.io.extraRefillResponses(0).payload.bank #= request1.get._1
      dut.io.extraRefillResponses(0).payload.address #= request1.get._2
      dut.io.extraRefillResponses(0).payload.data #= line1
      dut.io.extraRefillResponses(0).payload.error #= false
      dut.io.extraRefillResponses(0).valid #= true
      dut.clockDomain.waitSampling()
      dut.io.refillResponse.valid #= false
      dut.io.extraRefillResponses(0).valid #= false

      val responses = mutable.Map.empty[Int, BigInt]
      timeout = 0
      while (responses.size < 2 && timeout < 100) {
        sleep(1)
        for (client <- 0 until multiCfg.clientCount
             if dut.io.responses(client).valid.toBoolean) {
          responses(client) = dut.io.responses(client).payload.data.toBigInt
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(responses.values.toSet == Set(line0, line1))
      assert(dut.io.refillCount.toLong == 2)
    }
  }

  test("one bank keeps multiple set misses in MSHRs before refill") {
    simConfig.compile(new BankedReadCache(cacheCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      for (client <- 0 until cacheCfg.clientCount) {
        dut.io.requests(client).payload.address #= client * 2
        dut.io.requests(client).payload.requestId #= client + 1
        dut.io.requests(client).valid #= true
        dut.io.responses(client).ready #= false
      }

      val accepted = Array.fill(cacheCfg.clientCount)(false)
      val refills = mutable.ArrayBuffer.empty[(Int, Int)]
      var timeout = 0
      while ((accepted.contains(false) || refills.size < 2) && timeout < 100) {
        sleep(1)
        for (client <- 0 until cacheCfg.clientCount) {
          if (!accepted(client) && dut.io.requests(client).ready.toBoolean) {
            accepted(client) = true
          }
        }
        if (dut.io.refillRequest.valid.toBoolean) {
          refills += ((dut.io.refillRequest.payload.bank.toInt,
            dut.io.refillRequest.payload.address.toInt))
        }
        dut.clockDomain.waitSampling()
        for (client <- 0 until cacheCfg.clientCount if accepted(client)) {
          dut.io.requests(client).valid #= false
        }
        timeout += 1
      }
      assert(accepted.forall(identity))
      assert(refills.map(_._1).toSet == Set(0))
      assert(refills.map(_._2).toSet == Set(0, 2))

      for ((bank, address) <- refills.reverse) {
        dut.io.refillResponse.payload.bank #= bank
        dut.io.refillResponse.payload.address #= address
        dut.io.refillResponse.payload.data #= BigInt(address + 1) * 0x01010101L
        dut.io.refillResponse.payload.error #= false
        dut.io.refillResponse.valid #= true
        var responseAccepted = false
        while (!responseAccepted) {
          sleep(1)
          responseAccepted = dut.io.refillResponse.ready.toBoolean
          dut.clockDomain.waitSampling()
        }
        dut.io.refillResponse.valid #= false
      }

      for (client <- 0 until cacheCfg.clientCount) {
        dut.io.responses(client).ready #= true
      }
      val responses = mutable.Map.empty[Int, BigInt]
      timeout = 0
      while (responses.size < 2 && timeout < 100) {
        sleep(1)
        for (client <- 0 until cacheCfg.clientCount
             if dut.io.responses(client).valid.toBoolean) {
          responses(client) = dut.io.responses(client).payload.data.toBigInt
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(responses == Map(
        0 -> BigInt(0x01010101L), 1 -> BigInt(0x03030303L)))
    }
  }

  test("one client keeps multiple bank misses outstanding and retires in request order") {
    simConfig.compile(new BankedReadCache(cacheCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.refillRequest.ready #= false
      dut.io.refillResponse.valid #= false
      for (client <- 0 until cacheCfg.clientCount) {
        dut.io.requests(client).valid #= false
        dut.io.responses(client).ready #= false
      }

      def issue(address: Int, requestId: Int): Unit = {
        dut.io.requests(0).payload.address #= address
        dut.io.requests(0).payload.requestId #= requestId
        dut.io.requests(0).valid #= true
        while (!dut.io.requests(0).ready.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        dut.clockDomain.waitSampling()
        dut.io.requests(0).valid #= false
      }

      issue(address = 0, requestId = 5)
      issue(address = 1, requestId = 6)
      assert(dut.io.clientOutstanding.toBigInt.testBit(0))

      val refills = mutable.ArrayBuffer.empty[(Int, Int)]
      dut.io.refillRequest.ready #= true
      var timeout = 0
      while (refills.size < 2 && timeout < 100) {
        sleep(1)
        if (dut.io.refillRequest.valid.toBoolean &&
            dut.io.refillRequest.ready.toBoolean) {
          refills += ((dut.io.refillRequest.payload.bank.toInt,
            dut.io.refillRequest.payload.address.toInt))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      dut.io.refillRequest.ready #= false
      assert(refills.toSeq == Seq(0 -> 0, 1 -> 1))

      def returnRefill(bank: Int, address: Int): Unit = {
        dut.io.refillResponse.payload.bank #= bank
        dut.io.refillResponse.payload.address #= address
        dut.io.refillResponse.payload.data #= BigInt(address + 1) * 0x01010101L
        dut.io.refillResponse.payload.error #= false
        dut.io.refillResponse.valid #= true
        while (!dut.io.refillResponse.ready.toBoolean) {
          dut.clockDomain.waitSampling()
        }
        dut.clockDomain.waitSampling()
        dut.io.refillResponse.valid #= false
      }

      returnRefill(bank = 1, address = 1)
      sleep(1)
      assert(!dut.io.responses(0).valid.toBoolean,
        "the younger bank completion must wait for the older request ID")
      returnRefill(bank = 0, address = 0)

      dut.io.responses(0).ready #= true
      val observed = mutable.ArrayBuffer.empty[(Int, BigInt)]
      timeout = 0
      while (observed.size < 2 && timeout < 100) {
        sleep(1)
        if (dut.io.responses(0).valid.toBoolean) {
          observed += ((dut.io.responses(0).payload.requestId.toInt,
            dut.io.responses(0).payload.data.toBigInt))
        }
        dut.clockDomain.waitSampling()
        timeout += 1
      }
      assert(observed.toSeq == Seq(
        5 -> BigInt(0x01010101L),
        6 -> BigInt(0x02020202L)))
      sleep(1)
      assert(!dut.io.clientOutstanding.toBigInt.testBit(0))
      assert(dut.io.bankBusy.toBigInt == 0)
    }
  }

  test("set-associative ways retain conflicting tags and replace round robin") {
    val associativeCfg = CacheConfig(
      wordWidth = 8,
      wordsPerLine = 4,
      addressWidth = 6,
      bankCount = 1,
      setsPerBank = 2,
      clientCount = 1,
      requestIdWidth = 4,
      ways = 2
    )

    simConfig.compile(new BankedReadCache(associativeCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.requests(0).valid #= false
      dut.io.responses(0).ready #= false

      def line(address: Int): BigInt = BigInt(address + 1) * 0x01010101L

      fork {
        while (true) {
          dut.io.refillResponse.valid #= false
          sleep(1)
          if (dut.io.refillRequest.valid.toBoolean) {
            val address = dut.io.refillRequest.payload.address.toInt
            dut.clockDomain.waitSampling()
            dut.io.refillResponse.payload.bank #= 0
            dut.io.refillResponse.payload.address #= address
            dut.io.refillResponse.payload.data #= line(address)
            dut.io.refillResponse.payload.error #= false
            dut.io.refillResponse.valid #= true
            while (!dut.io.refillResponse.ready.toBoolean) {
              dut.clockDomain.waitSampling()
            }
            dut.clockDomain.waitSampling()
            dut.io.refillResponse.valid #= false
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      def transact(address: Int, requestId: Int): Boolean = {
        dut.io.requests(0).payload.address #= address
        dut.io.requests(0).payload.requestId #= requestId
        dut.io.requests(0).valid #= true
        while (!dut.io.requests(0).ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.requests(0).valid #= false
        dut.io.responses(0).ready #= true
        while (!dut.io.responses(0).valid.toBoolean) dut.clockDomain.waitSampling()
        assert(dut.io.responses(0).payload.data.toBigInt == line(address))
        val wasHit = dut.io.responses(0).payload.wasHit.toBoolean
        dut.clockDomain.waitSampling()
        dut.io.responses(0).ready #= false
        wasHit
      }

      assert(!transact(0, 1))
      assert(!transact(2, 2))
      assert(transact(0, 3))
      assert(transact(2, 4))
      assert(!transact(4, 5))
      assert(transact(2, 6), "round-robin refill must retain the other way")
      assert(!transact(0, 7), "the oldest way must be replaced after the set fills")
    }
  }

  test("cluster address mapping preserves every global set index") {
    val clusteredCfg = CacheConfig(
      wordWidth = 8,
      wordsPerLine = 4,
      addressWidth = 6,
      bankCount = 4,
      setsPerBank = 2,
      clientCount = 1,
      requestIdWidth = 4
    )
    val clusterCfg = CacheClusterConfig(clusteredCfg, clusterCount = 2)

    simConfig.compile(new ClusteredReadCache(clusterCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.refillRequest.ready #= true
      dut.io.refillResponse.valid #= false
      dut.io.requests(0).valid #= false
      dut.io.responses(0).ready #= false

      def line(address: Int): BigInt = BigInt(address + 1) * 0x01010101L

      fork {
        while (true) {
          dut.io.refillResponse.valid #= false
          sleep(1)
          if (dut.io.refillRequest.valid.toBoolean) {
            val bank = dut.io.refillRequest.payload.bank.toInt
            val address = dut.io.refillRequest.payload.address.toInt
            dut.clockDomain.waitSampling()
            dut.io.refillResponse.payload.bank #= bank
            dut.io.refillResponse.payload.address #= address
            dut.io.refillResponse.payload.data #= line(address)
            dut.io.refillResponse.payload.error #= false
            dut.io.refillResponse.valid #= true
            while (!dut.io.refillResponse.ready.toBoolean) {
              dut.clockDomain.waitSampling()
            }
            dut.clockDomain.waitSampling()
            dut.io.refillResponse.valid #= false
          } else {
            dut.clockDomain.waitSampling()
          }
        }
      }

      def transact(address: Int, requestId: Int): Boolean = {
        dut.io.requests(0).payload.address #= address
        dut.io.requests(0).payload.requestId #= requestId
        dut.io.requests(0).valid #= true
        while (!dut.io.requests(0).ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.requests(0).valid #= false
        dut.io.responses(0).ready #= true
        while (!dut.io.responses(0).valid.toBoolean) dut.clockDomain.waitSampling()
        assert(dut.io.responses(0).payload.data.toBigInt == line(address))
        val wasHit = dut.io.responses(0).payload.wasHit.toBoolean
        dut.clockDomain.waitSampling()
        dut.io.responses(0).ready #= false
        wasHit
      }

      assert(!transact(0, 1))
      assert(!transact(4, 2))
      assert(transact(0, 3),
        "global set 1 must not evict global set 0 inside a cluster")
      assert(transact(4, 4))
    }
  }

  test("cluster ROB preserves client issue order across reverse refill completion") {
    val clusteredCfg = CacheConfig(
      wordWidth = 8,
      wordsPerLine = 4,
      addressWidth = 6,
      bankCount = 4,
      setsPerBank = 2,
      clientCount = 1,
      requestIdWidth = 4,
      maxOutstandingPerClient = 4
    )
    val clusterCfg = CacheClusterConfig(clusteredCfg, clusterCount = 2)

    simConfig.compile(new ClusteredReadCache(clusterCfg)).doSim { dut =>
      dut.clockDomain.forkStimulus(10)
      dut.io.clear #= false
      dut.io.refillRequest.ready #= false
      dut.io.refillResponse.valid #= false
      dut.io.requests(0).valid #= false
      dut.io.responses(0).ready #= true

      def line(address: Int): BigInt = BigInt(address + 1) * 0x01010101L

      def issue(address: Int, requestId: Int): Unit = {
        dut.io.requests(0).payload.address #= address
        dut.io.requests(0).payload.requestId #= requestId
        dut.io.requests(0).valid #= true
        while (!dut.io.requests(0).ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.requests(0).valid #= false
      }

      issue(address = 0, requestId = 1)
      issue(address = 2, requestId = 2)

      val refills = mutable.ArrayBuffer.empty[(Int, Int)]
      dut.io.refillRequest.ready #= true
      while (refills.map(_._1).toSet.size < 2) {
        if (dut.io.refillRequest.valid.toBoolean) {
          val refill = (dut.io.refillRequest.payload.address.toInt,
            dut.io.refillRequest.payload.bank.toInt)
          if (!refills.exists(_._1 == refill._1)) refills += refill
        }
        dut.clockDomain.waitSampling()
      }
      assert(refills.map(_._1).toSet == Set(0, 2))

      def complete(address: Int, bank: Int): Unit = {
        dut.io.refillResponse.payload.address #= address
        dut.io.refillResponse.payload.bank #= bank
        dut.io.refillResponse.payload.data #= line(address)
        dut.io.refillResponse.payload.error #= false
        dut.io.refillResponse.valid #= true
        while (!dut.io.refillResponse.ready.toBoolean) dut.clockDomain.waitSampling()
        dut.clockDomain.waitSampling()
        dut.io.refillResponse.valid #= false
      }

      val first = refills.find(_._1 == 0).get
      val second = refills.find(_._1 == 2).get
      complete(second._1, second._2)
      for (_ <- 0 until 4) {
        assert(!dut.io.responses(0).valid.toBoolean,
          "a later cluster response must wait in the cluster ROB")
        dut.clockDomain.waitSampling()
      }

      complete(first._1, first._2)
      val responses = mutable.ArrayBuffer.empty[(Int, BigInt)]
      while (responses.length < 2) {
        if (dut.io.responses(0).valid.toBoolean) {
          responses += ((dut.io.responses(0).payload.requestId.toInt,
            dut.io.responses(0).payload.data.toBigInt))
        }
        dut.clockDomain.waitSampling()
      }
      assert(responses == Seq(1 -> line(0), 2 -> line(2)))
    }
  }
}
