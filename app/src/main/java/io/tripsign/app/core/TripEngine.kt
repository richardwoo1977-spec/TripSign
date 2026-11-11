// ver 1.1.0 | 2026-11-06 16:48
package io.tripsign.app.core

import kotlin.math.max

data class EngineConfig(
    /** P must be held this long before a trip is closed (parking maneuvers with R/D do not split trips). */
    val parkConfirmMs: Long = 3_000L,
    /** Speed 0 in a non-P gear for this long closes the trip (no P used). */
    val standstillEndMs: Long = 10 * 60_000L,
    /** Below this distance and with no speed, a P->D->P is treated as "no movement". */
    val minMoveKm: Double = 0.05,
    /** Odometer advance while parked/not recording above this becomes a GAP (미기록 구간). */
    val gapThresholdKm: Double = 0.3,
    /** Speed integration is skipped across sample gaps longer than this. */
    val maxSampleGapMs: Long = 5_000L,
    /** Starts a trip from speed alone if a gear event was missed. */
    val speedStartMps: Double = 2.0,
    /** Speed below this counts as standing still. */
    val stillMps: Double = 0.1,
    /** A late odometer update shortly after a trip ends is absorbed instead of becoming a GAP. */
    val lateOdoGraceMs: Long = 15_000L,
    val lateOdoMaxKm: Double = 1.5
)

sealed interface EngineOutput {
    data class TripStarted(val tripId: String, val startMs: Long, val startOdoKm: Double?) : EngineOutput
    data class TripFinished(val entry: Entry) : EngineOutput
    data class NoMoveIgnored(val startMs: Long, val endMs: Long) : EngineOutput
    data class GapDetected(val entry: Entry) : EngineOutput
    data class AnomalyDetected(val entry: Entry) : EngineOutput
}

/**
 * Pure trip state machine. All times are wall-clock milliseconds supplied by the caller,
 * so tests and the car service drive it the same way.
 */
class TripEngine(
    private val config: EngineConfig = EngineConfig(),
    lastRecordedOdoKm: Double? = null
) {
    private class Active(
        val id: String,
        val startMs: Long,
        var startOdoKm: Double?,
        var speedKm: Double = 0.0,
        var maxMps: Double = 0.0,
        var lastMps: Double? = null,
        var lastSpeedMs: Long? = null,
        var parkSinceMs: Long? = null,
        var stillSinceMs: Long? = null
    )

    private var gear: Gear = Gear.UNKNOWN
    private var odoKm: Double? = null
    private var referenceOdoKm: Double? = lastRecordedOdoKm
    private var active: Active? = null
    private var lastFinishMs: Long? = null

    val isDriving: Boolean get() = active != null
    val currentGear: Gear get() = gear
    val lastOdoKm: Double? get() = odoKm
    val activeTripId: String? get() = active?.id
    val activeSpeedKm: Double get() = active?.speedKm ?: 0.0
    val activeStartMs: Long? get() = active?.startMs
    val activeStartOdoKm: Double? get() = active?.startOdoKm
    val activeMaxMps: Double get() = active?.maxMps ?: 0.0

    /**
     * 0.5.2: a drive that was still open when the head unit went off (car switched off outside P). The service saved it
     * and puts it back here; [forceFinish] then closes it at the last moment it was seen.
     */
    fun restore(id: String, startMs: Long, startOdoKm: Double?, speedKm: Double, maxMps: Double) {
        if (active != null) return
        active = Active(id = id, startMs = startMs, startOdoKm = startOdoKm, speedKm = speedKm, maxMps = maxMps)
    }

    /** Closes the open drive at [endMs] (car switched off or woke up from sleep without a P). */
    fun forceFinish(endMs: Long): List<EngineOutput> {
        val trip = active ?: return emptyList()
        return finish(maxOf(endMs, trip.startMs))
    }

    fun onOdometer(km: Double, tMs: Long): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        odoKm = km
        val trip = active
        if (trip == null) {
            checkContinuity(km, tMs, out)
        } else if (trip.startOdoKm == null) {
            // Odometer arrived after the trip started; back out what we already integrated.
            trip.startOdoKm = km - trip.speedKm
        }
        out += tick(tMs)
        return out
    }

    fun onGear(newGear: Gear, tMs: Long): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        gear = newGear
        val trip = active
        if (trip == null) {
            if (newGear == Gear.DRIVE || newGear == Gear.REVERSE || newGear == Gear.NEUTRAL) {
                out += start(tMs)
            }
        } else {
            if (newGear == Gear.PARK) {
                if (trip.parkSinceMs == null) trip.parkSinceMs = tMs
            } else if (newGear != Gear.UNKNOWN) {
                trip.parkSinceMs = null
            }
        }
        out += tick(tMs)
        return out
    }

    fun onSpeed(mps: Double, tMs: Long): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        val speed = max(0.0, mps)
        if (active == null && speed >= config.speedStartMps && gear != Gear.PARK) {
            out += start(tMs)
        }
        val trip = active
        if (trip != null) {
            val lastT = trip.lastSpeedMs
            val lastV = trip.lastMps
            if (lastT != null && lastV != null) {
                val dt = tMs - lastT
                if (dt in 1..config.maxSampleGapMs) {
                    trip.speedKm += (lastV + speed) / 2.0 * dt / 1000.0 / 1000.0
                }
            }
            trip.lastSpeedMs = tMs
            trip.lastMps = speed
            if (speed > trip.maxMps) trip.maxMps = speed
            if (speed < config.stillMps) {
                if (trip.stillSinceMs == null) trip.stillSinceMs = tMs
            } else {
                trip.stillSinceMs = null
            }
        }
        out += tick(tMs)
        return out
    }

    fun tick(tMs: Long): List<EngineOutput> {
        val trip = active ?: return emptyList()
        val park = trip.parkSinceMs
        if (park != null && tMs - park >= config.parkConfirmMs) {
            return finish(park)
        }
        val still = trip.stillSinceMs
        if (still != null && gear != Gear.PARK && tMs - still >= config.standstillEndMs) {
            return finish(still)
        }
        return emptyList()
    }

    private fun start(tMs: Long): List<EngineOutput> {
        val out = mutableListOf<EngineOutput>()
        val km = odoKm
        if (km != null) checkContinuity(km, tMs, out)
        val id = "T$tMs"
        active = Active(id = id, startMs = tMs, startOdoKm = km)
        out += EngineOutput.TripStarted(id, tMs, km)
        return out
    }

    private fun finish(endMs: Long): List<EngineOutput> {
        val trip = active ?: return emptyList()
        active = null
        lastFinishMs = endMs
        val endOdo = odoKm
        val startOdo = trip.startOdoKm
        val odoDelta = if (startOdo != null && endOdo != null) endOdo - startOdo else null
        val maxKmh = trip.maxMps * 3.6
        val moved = maxKmh > 1.0 ||
            trip.speedKm >= config.minMoveKm ||
            (odoDelta != null && odoDelta >= config.minMoveKm)
        val out = mutableListOf<EngineOutput>()
        if (!moved) {
            out += EngineOutput.NoMoveIgnored(trip.startMs, endMs)
            return out
        }
        if (odoDelta != null && odoDelta < -0.05) {
            out += EngineOutput.AnomalyDetected(
                Entry(
                    type = RecordType.ANOMALY,
                    tripId = trip.id,
                    startMs = trip.startMs,
                    endMs = endMs,
                    startOdoKm = startOdo,
                    endOdoKm = endOdo,
                    note = "주행 중 계기판 거리 감소"
                )
            )
        }
        val useOdo = odoDelta != null && odoDelta > 0.0
        val distance = if (useOdo) odoDelta!! else trip.speedKm
        out += EngineOutput.TripFinished(
            Entry(
                type = RecordType.TRIP,
                tripId = trip.id,
                startMs = trip.startMs,
                endMs = endMs,
                startOdoKm = startOdo,
                endOdoKm = endOdo,
                speedKm = trip.speedKm,
                distanceKm = distance,
                distanceSource = if (useOdo) DistanceSource.ODOMETER else DistanceSource.SPEED,
                maxSpeedKmh = maxKmh
            )
        )
        if (endOdo != null) referenceOdoKm = endOdo
        return out
    }

    private fun checkContinuity(km: Double, tMs: Long, out: MutableList<EngineOutput>) {
        val ref = referenceOdoKm
        if (ref == null) {
            referenceOdoKm = km
            return
        }
        val diff = km - ref
        val finishedAt = lastFinishMs
        if (finishedAt != null && tMs - finishedAt <= config.lateOdoGraceMs && diff in 0.0..config.lateOdoMaxKm) {
            referenceOdoKm = km
            return
        }
        if (diff > config.gapThresholdKm) {
            out += EngineOutput.GapDetected(
                Entry(
                    type = RecordType.GAP,
                    tripId = "G$tMs",
                    startMs = tMs,
                    endMs = tMs,
                    startOdoKm = ref,
                    endOdoKm = km,
                    distanceKm = diff,
                    distanceSource = DistanceSource.ODOMETER,
                    note = "기록되지 않은 주행"
                )
            )
            referenceOdoKm = km
        } else if (diff < -0.05) {
            out += EngineOutput.AnomalyDetected(
                Entry(
                    type = RecordType.ANOMALY,
                    tripId = "A$tMs",
                    startMs = tMs,
                    endMs = tMs,
                    startOdoKm = ref,
                    endOdoKm = km,
                    note = "계기판 거리 감소"
                )
            )
            referenceOdoKm = km
        }
    }
}
