// ver 1.1.0 | 2026-11-04 20:50
package io.tripsign.app.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Random

/**
 * Labeled sample data for demos ("예시 데이터"). Never written to the real ledger.
 * 13 months (2025-10 .. 2026-10). Includes the vehicle record (①②) and a user (④) for every trip.
 * Names and plate are invented.
 */
object DemoData {

    private data class Plan(val hour: Int, val minute: Int, val km: Double, val purpose: Purpose)

    val ANCHOR_DATE: LocalDate = LocalDate.of(2026, 10, 31)

    val USERS = listOf(Driver("영업팀", "김하늘"), Driver("영업팀", "이수"))

    fun anchorMs(zone: ZoneId): Long =
        ANCHOR_DATE.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

    object DemoSigner : Signer {
        override fun sign(data: ByteArray): String = "DEMO"
    }

    object DemoVerifier : Verifier {
        override fun verify(data: ByteArray, signature: String): Boolean = signature == "DEMO"
    }

    fun build(zone: ZoneId): List<SealedEntry> {
        val rnd = Random(20261104L)
        val out = mutableListOf<SealedEntry>()
        var odo = 21_400.0
        var day = LocalDate.of(2025, 10, 1)
        var changeDone = false
        var tripNo = 0
        fun add(entry: Entry, createdAtMs: Long) {
            out += Chain.seal(out.lastOrNull(), entry, createdAtMs, DemoSigner)
        }
        add(
            Entry(type = RecordType.VEHICLE, tripId = "-", note = Vehicle("그랜저 HEV", "123가4567").encode()),
            day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        )
        fun trip(date: LocalDate, hour: Int, minute: Int, km: Double, purpose: Purpose) {
            val start = date.atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
            val minutes = (km / 32.0 * 60.0).toLong().coerceAtLeast(6L)
            val end = start + minutes * 60_000L
            val id = "T$start"
            val startOdo = odo
            odo += km
            add(
                Entry(
                    type = RecordType.TRIP,
                    tripId = id,
                    startMs = start,
                    endMs = end,
                    startOdoKm = startOdo,
                    endOdoKm = odo,
                    speedKm = km * (0.985 + rnd.nextDouble() * 0.03),
                    distanceKm = km,
                    distanceSource = DistanceSource.ODOMETER,
                    maxSpeedKmh = 60.0 + rnd.nextInt(50)
                ),
                end + 1_000L
            )
            val user = if (tripNo++ % 5 == 4) USERS[1] else USERS[0]
            add(Entry(type = RecordType.ASSIGN, tripId = id, note = user.encode()), end + 1_500L)
            val late = rnd.nextDouble() < 0.25
            val classifiedAt = if (late) end + 26 * 3_600_000L else end + (20 + rnd.nextInt(90)) * 1_000L
            add(Entry(type = RecordType.PURPOSE, tripId = id, purpose = purpose), classifiedAt)
            if (!changeDone && date.year == 2026 && date.monthValue == 10 && purpose == Purpose.PERSONAL) {
                add(Entry(type = RecordType.PURPOSE, tripId = id, purpose = Purpose.BUSINESS, note = "목적 변경"), classifiedAt + 3_600_000L)
                changeDone = true
            }
        }
        while (!day.isAfter(ANCHOR_DATE)) {
            val weekday = day.dayOfWeek != DayOfWeek.SATURDAY && day.dayOfWeek != DayOfWeek.SUNDAY
            val seasonal = if (day.monthValue == 8 || day.monthValue == 1) 0.85 else 1.0
            val plan = mutableListOf<Plan>()
            if (weekday) {
                plan += Plan(8, 10 + rnd.nextInt(20), 17.0 + rnd.nextDouble() * 4.0, Purpose.COMMUTE)
                val visits = intArrayOf(1, 2, 2, 3)[rnd.nextInt(4)]
                val hours = intArrayOf(9, 10, 11, 14, 15, 16)
                val used = HashSet<Int>()
                while (used.size < visits) used.add(hours[rnd.nextInt(hours.size)])
                for (h in used) plan += Plan(h, rnd.nextInt(40), (6.0 + rnd.nextDouble() * 32.0) * seasonal, Purpose.BUSINESS)
                if (rnd.nextDouble() < 0.25) plan += Plan(12, 10 + rnd.nextInt(30), 3.0 + rnd.nextDouble() * 6.0, Purpose.PERSONAL)
                plan += Plan(18, 10 + rnd.nextInt(40), 17.0 + rnd.nextDouble() * 5.0, Purpose.COMMUTE)
            } else {
                val n = intArrayOf(0, 1, 2)[rnd.nextInt(3)]
                val hours = intArrayOf(10, 13, 15, 17)
                for (i in 0 until n) plan += Plan(hours[i * 2 + rnd.nextInt(2)], rnd.nextInt(50), 8.0 + rnd.nextDouble() * 37.0, Purpose.PERSONAL)
            }
            for (p in plan.sortedWith(compareBy<Plan>({ it.hour }, { it.minute }))) {
                trip(day, p.hour, p.minute, p.km, p.purpose)
            }
            day = day.plusDays(1)
        }
        return out
    }
}
