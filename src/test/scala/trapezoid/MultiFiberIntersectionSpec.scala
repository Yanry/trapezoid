package trapezoid

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.sim._

import scala.util.Random

class MultiFiberIntersectionSpec extends AnyFunSuite {
  private val cfg = TrapezoidConfig(
    dataWidth = 8,
    accumulatorWidth = 24,
    coordinateWidth = 8,
    fiberWidth = 8,
    maxFibers = 4,
    macLanes = 16,
    cacheLineWords = 4,
    outputLanes = 16,
    peRows = 1,
    localDepthPerBank = 8
  )

  private case class ExpectedRoute(
      aFiber: Int,
      bFiber: Int,
      coordinate: Int,
      aValueIndex: Int,
      bValueIndex: Int)

  test("hierarchical compactor preserves pair-major routes and overflow count") {
    SimConfig.withVerilator
      .addSimulatorFlag("-CFLAGS -std=c++14")
      .addSimulatorFlag("-LDFLAGS -std=c++14")
      .compile(new MultiFiberIntersection(cfg))
      .doSim { dut =>
        val random = new Random(0x6d71)
        for (_ <- 0 until 100) {
          val aValid = Vector.fill(cfg.maxFibers)(random.nextBoolean())
          val bValid = Vector.fill(cfg.maxFibers)(random.nextBoolean())
          val aMasks = Vector.fill(cfg.maxFibers)(random.nextInt(1 << cfg.fiberWidth))
          val bMasks = Vector.fill(cfg.maxFibers)(random.nextInt(1 << cfg.fiberWidth))

          for (fiber <- 0 until cfg.maxFibers) {
            dut.io.aValid(fiber) #= aValid(fiber)
            dut.io.aMask(fiber) #= aMasks(fiber)
            dut.io.bValid(fiber) #= bValid(fiber)
            dut.io.bMask(fiber) #= bMasks(fiber)
          }
          sleep(1)

          val expected = for {
            a <- 0 until cfg.maxFibers
            b <- 0 until cfg.maxFibers
            coordinate <- 0 until cfg.fiberWidth
            if aValid(a) && bValid(b) &&
              ((aMasks(a) & bMasks(b) & (1 << coordinate)) != 0)
          } yield ExpectedRoute(
            a,
            b,
            coordinate,
            Integer.bitCount(aMasks(a) & ((1 << coordinate) - 1)),
            Integer.bitCount(bMasks(b) & ((1 << coordinate) - 1)))

          assert(dut.io.count.toInt == expected.length)
          assert(dut.io.overflow.toBoolean == (expected.length > cfg.macLanes))
          for (lane <- 0 until cfg.macLanes) {
            val route = dut.io.routes(lane)
            if (lane < expected.length) {
              val reference = expected(lane)
              assert(route.valid.toBoolean)
              assert(route.aFiber.toInt == reference.aFiber)
              assert(route.bFiber.toInt == reference.bFiber)
              assert(route.coordinate.toInt == reference.coordinate)
              assert(route.aValueIndex.toInt == reference.aValueIndex)
              assert(route.bValueIndex.toInt == reference.bValueIndex)
            } else {
              assert(!route.valid.toBoolean)
            }
          }
        }
      }
  }
}
