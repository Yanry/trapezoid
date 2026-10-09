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
}
