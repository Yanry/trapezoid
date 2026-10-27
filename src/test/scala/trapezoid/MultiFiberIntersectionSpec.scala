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
      bValueIndex: Int,
      aSourceIndex: Int,
      bSourceIndex: Int)

  private def spreadSourceIndex(index: Int): Int = {
    val width = cfg.macLaneIndexWidth
    ((index << 1) & ((1 << width) - 1)) | (index >> (width - 1))
  }

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
            for (value <- 0 until cfg.fiberWidth) {
              dut.io.aValues(fiber)(value) #= fiber * 32 + value + 1
              dut.io.bValues(fiber)(value) #= -(fiber * 32 + value + 1)
            }
          }
          sleep(1)

          val aCoordinateUnion = (0 until cfg.maxFibers)
            .filter(fiber => aValid(fiber)).map(aMasks).foldLeft(0)(_ | _)
          val bCoordinateUnion = (0 until cfg.maxFibers)
            .filter(fiber => bValid(fiber)).map(bMasks).foldLeft(0)(_ | _)
          val activeACoordinates = (0 until cfg.maxFibers).map { fiber =>
            if (aValid(fiber)) aMasks(fiber) & bCoordinateUnion else 0
          }
          val activeBCoordinates = (0 until cfg.maxFibers).map { fiber =>
            if (bValid(fiber)) bMasks(fiber) & aCoordinateUnion else 0
          }
          val aSourceBase = activeACoordinates.scanLeft(0) {
            case (base, mask) => base + Integer.bitCount(mask)
          }
          val bSourceBase = activeBCoordinates.scanLeft(0) {
            case (base, mask) => base + Integer.bitCount(mask)
          }
          val expectedASources = for {
            fiber <- 0 until cfg.maxFibers
            coordinate <- 0 until cfg.fiberWidth
            if (activeACoordinates(fiber) & (1 << coordinate)) != 0
          } yield {
            val valueIndex = Integer.bitCount(aMasks(fiber) &
              ((1 << coordinate) - 1))
            fiber * 32 + valueIndex + 1
          }
          val expectedBSources = for {
            fiber <- 0 until cfg.maxFibers
            coordinate <- 0 until cfg.fiberWidth
            if (activeBCoordinates(fiber) & (1 << coordinate)) != 0
          } yield {
            val valueIndex = Integer.bitCount(bMasks(fiber) &
              ((1 << coordinate) - 1))
            -(fiber * 32 + valueIndex + 1)
          }

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
            Integer.bitCount(bMasks(b) & ((1 << coordinate) - 1)),
            {
              val rank = aSourceBase(a) + Integer.bitCount(activeACoordinates(a) &
                ((1 << coordinate) - 1))
              spreadSourceIndex(rank)
            },
            {
              val rank = bSourceBase(b) + Integer.bitCount(activeBCoordinates(b) &
                ((1 << coordinate) - 1))
              spreadSourceIndex(rank)
            })

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
              assert(route.aSourceIndex.toInt == reference.aSourceIndex)
              assert(route.bSourceIndex.toInt == reference.bSourceIndex)
            } else {
              assert(!route.valid.toBoolean)
            }
          }
          val observedAValid = dut.io.aSourceValid.toBigInt
          val observedBValid = dut.io.bSourceValid.toBigInt
          for (lane <- 0 until cfg.macLanes) {
            val compactedIndex = (lane % 2) * (cfg.macLanes / 2) + lane / 2
            if (compactedIndex < expectedASources.length) {
              assert(observedAValid.testBit(lane))
              assert(dut.io.aSources(lane).toInt == expectedASources(compactedIndex))
            } else {
              assert(!observedAValid.testBit(lane))
            }
            if (compactedIndex < expectedBSources.length) {
              assert(observedBValid.testBit(lane))
              assert(dut.io.bSources(lane).toInt == expectedBSources(compactedIndex))
            } else {
              assert(!observedBValid.testBit(lane))
            }
          }
        }
      }
  }
}
