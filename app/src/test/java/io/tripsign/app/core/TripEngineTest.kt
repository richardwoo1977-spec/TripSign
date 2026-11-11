// ver 1.0.0 | 2026-11-04 14:50
package io.tripsign.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripEngineTest {

    private fun List<EngineOutput>.finished() = filterIsInstance<EngineOutput.TripFinished>()

    /** Drives at [mps] for [seconds], one sample per second, returning all outputs. */
    private fun drive(engine: TripEngine, fromMs: Long, seconds: Int, mps: Double): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        for (i in 0..seconds) out += engine.onSpeed(mps, fromMs + i * 1000L)
        return out
    }

    @Test
    fun normalTripUsesOdometerDistance() {
        val e = TripEngine()
        val out = mutableListOf<EngineOutput>()
        out += e.onOdometer(1000.0, 0L)
        out += e.onGear(Gear.DRIVE, 1_000L)
        out += drive(e, 1_000L, 120, 10.0)
        out += e.onOdometer(1001.2, 121_000L)
        out += e.onSpeed(0.0, 122_000L)
        out += e.onGear(Gear.PARK, 123_000L)
        out += e.tick(126_500L)
        val f = out.finished()
        assertEquals(1, f.size)
        assertEquals(1.2, f[0].entry.distanceKm, 0.001)
        assertEquals(DistanceSource.ODOMETER, f[0].entry.distanceSource)
        assertEquals(123_000L, f[0].entry.endMs)
        assertTrue(f[0].entry.speedKm > 1.1)
    }

    @Test
    fun gearChangeWithoutMovementIsIgnored() {
        val e = TripEngine()
        val out = mutableListOf<EngineOutput>()
        out += e.onOdometer(500.0, 0L)
        out += e.onGear(Gear.DRIVE, 1_000L)
        out += e.onSpeed(0.0, 2_000L)
        out += e.onGear(Gear.PARK, 3_000L)
        out += e.tick(10_000L)
        assertEquals(0, out.finished().size)
        assertEquals(1, out.filterIsInstance<EngineOutput.NoMoveIgnored>().size)
    }

    @Test
    fun reverseChatterWhileParkingKeepsOneTrip() {
        val e = TripEngine()
        val out = mutableListOf<EngineOutput>()
        out += e.onOdometer(2000.0, 0L)
        out += e.onGear(Gear.DRIVE, 1_000L)
        out += drive(e, 1_000L, 60, 12.0)
        out += e.onGear(Gear.PARK, 62_000L)
        out += e.onGear(Gear.REVERSE, 63_000L)
        out += drive(e, 63_000L, 3, 1.0)
        out += e.onGear(Gear.DRIVE, 67_000L)
        out += e.onGear(Gear.PARK, 68_000L)
        out += e.onOdometer(2000.8, 68_500L)
        out += e.tick(72_000L)
        val f = out.finished()
        assertEquals(1, f.size)
        assertEquals(68_000L, f[0].entry.endMs)
        assertEquals(1, out.filterIsInstance<EngineOutput.TripStarted>().size)
    }

    @Test
    fun longStandstillInDriveEndsTripAndNextMoveStartsNewOne() {
        val cfg = EngineConfig(standstillEndMs = 600_000L)
        val e = TripEngine(cfg)
        val out = mutableListOf<EngineOutput>()
        out += e.onOdometer(3000.0, 0L)
        out += e.onGear(Gear.DRIVE, 0L)
        out += drive(e, 0L, 100, 10.0)
        out += e.onOdometer(3001.0, 100_000L)
        out += e.onSpeed(0.0, 101_000L)
        out += e.tick(101_000L + 600_000L)
        val f = out.finished()
        assertEquals(1, f.size)
        assertEquals(101_000L, f[0].entry.endMs)
        out += e.onSpeed(5.0, 800_000L)
        assertEquals(2, out.filterIsInstance<EngineOutput.TripStarted>().size)
    }

    @Test
    fun odometerAdvanceWhileNotRecordingBecomesGap() {
        val e = TripEngine(lastRecordedOdoKm = 1000.0)
        val out = e.onOdometer(1012.5, 50_000L)
        val gaps = out.filterIsInstance<EngineOutput.GapDetected>()
        assertEquals(1, gaps.size)
        assertEquals(12.5, gaps[0].entry.distanceKm, 0.001)
        assertEquals(RecordType.GAP, gaps[0].entry.type)
    }

    @Test
    fun odometerGoingBackwardsIsAnomaly() {
        val e = TripEngine(lastRecordedOdoKm = 1000.0)
        val out = e.onOdometer(900.0, 50_000L)
        assertEquals(1, out.filterIsInstance<EngineOutput.AnomalyDetected>().size)
    }

    @Test
    fun speedAloneStartsTripWhenGearEventMissed() {
        val e = TripEngine()
        val out = mutableListOf<EngineOutput>()
        out += e.onOdometer(100.0, 0L)
        out += e.onSpeed(5.0, 1_000L)
        assertEquals(1, out.filterIsInstance<EngineOutput.TripStarted>().size)
        assertTrue(e.isDriving)
    }

    @Test
    fun coarseOdometerFallsBackToSpeedDistance() {
        val e = TripEngine()
        val out = mutableListOf<EngineOutput>()
        out += e.onOdometer(4000.0, 0L)
        out += e.onGear(Gear.DRIVE, 0L)
        out += drive(e, 0L, 60, 10.0)
        out += e.onGear(Gear.PARK, 61_000L)
        out += e.tick(65_000L)
        val f = out.finished()
        assertEquals(1, f.size)
        assertEquals(DistanceSource.SPEED, f[0].entry.distanceSource)
        assertEquals(0.6, f[0].entry.distanceKm, 0.01)
    }

    @Test
    fun lateOdometerUpdateAfterArrivalIsNotAGap() {
        val e = TripEngine()
        val out = mutableListOf<EngineOutput>()
        out += e.onOdometer(5000.0, 0L)
        out += e.onGear(Gear.DRIVE, 0L)
        out += drive(e, 0L, 60, 10.0)
        out += e.onGear(Gear.PARK, 61_000L)
        out += e.tick(65_000L)
        out += e.onOdometer(5001.0, 70_000L)
        assertEquals(0, out.filterIsInstance<EngineOutput.GapDetected>().size)
        assertNotNull(e.lastOdoKm)
    }
}
