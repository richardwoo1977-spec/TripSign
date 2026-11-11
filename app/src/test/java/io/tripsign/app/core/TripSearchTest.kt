// ver 1.0.0 | 2026-11-04 15:15
package io.tripsign.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class TripSearchTest {

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val today = LocalDateTime.of(2026, 10, 31, 15, 0).atZone(zone).toInstant().toEpochMilli()

    private fun at(m: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(2026, m, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun view(startMs: Long, minutes: Long, km: Double) = TripView(
        id = "t$startMs", kind = ViewKind.TRIP, startMs = startMs, endMs = startMs + minutes * 60_000L,
        distanceKm = km, source = DistanceSource.ODOMETER, startOdoKm = 0.0, endOdoKm = km,
        purpose = Purpose.BUSINESS, classifiedAtMs = null, purposeChanges = 0
    )

    private fun q(s: String) = TripSearch.parse(s, today, zone)

    @Test
    fun datesInEveryWrittenForm() {
        val expected = SearchTerm.OnDate(LocalDate.of(2026, 10, 30))
        for (s in listOf("10/30", "2026-10-30", "2026.10.30", "10.30", "10월 30일", "10월30일", "30일")) {
            assertEquals(listOf(expected), q(s).terms)
        }
        // a month/day after today means last year
        assertEquals(listOf(SearchTerm.OnDate(LocalDate.of(2025, 12, 25))), q("12/25").terms)
        assertEquals(listOf(SearchTerm.InMonth(YearMonth.of(2026, 9))), q("9월").terms)
        assertEquals(listOf(SearchTerm.InMonth(YearMonth.of(2025, 11))), q("11월").terms)
    }

    @Test
    fun timesIncludingMorningAfternoon() {
        assertEquals(listOf(SearchTerm.AtTime(18 * 60 + 20)), q("18:20").terms)
        assertEquals(listOf(SearchTerm.InHour(18)), q("18시").terms)
        assertEquals(listOf(SearchTerm.InHour(18)), q("오후 6시").terms)
        assertEquals(listOf(SearchTerm.AtTime(9 * 60 + 30)), q("오전 9:30").terms)
        assertEquals(listOf(SearchTerm.AtTime(18 * 60 + 20)), q("18시 20분").terms)
        assertEquals(listOf(SearchTerm.InHour(0)), q("오전 12시").terms)
    }

    @Test
    fun distancesMatchWhatTheListShows() {
        val v = view(at(10, 30, 18, 20), 40, 21.94)
        assertTrue(TripSearch.matches(v, q("21.9"), zone))
        assertTrue(TripSearch.matches(v, q("21.9km"), zone))
        assertTrue(TripSearch.matches(v, q("22km"), zone))
        assertFalse(TripSearch.matches(v, q("21.8"), zone))
        assertTrue(TripSearch.matches(v, q("20~30km"), zone))
        assertTrue(TripSearch.matches(v, q("20 - 30"), zone))
        assertTrue(TripSearch.matches(v, q("20km 이상"), zone))
        assertFalse(TripSearch.matches(v, q("20km 이하"), zone))
        assertTrue(TripSearch.matches(v, q(">=21.94"), zone))
        assertFalse(TripSearch.matches(v, q(">21.94"), zone))
        // 21.95 is not a date (month 21), so it is a distance
        assertEquals(1, q("21.95").terms.size)
        assertTrue(q("21.95").terms[0] is SearchTerm.Distance)
    }

    @Test
    fun timeMatchesTripsRunningAtThatMinuteOrHour() {
        val v = view(at(10, 30, 18, 20), 40, 21.9) // 18:20 - 19:00
        assertTrue(TripSearch.matches(v, q("18:45"), zone))
        assertTrue(TripSearch.matches(v, q("19:00"), zone))
        assertFalse(TripSearch.matches(v, q("19:01"), zone))
        assertTrue(TripSearch.matches(v, q("18시"), zone))
        assertTrue(TripSearch.matches(v, q("19시"), zone))
        assertFalse(TripSearch.matches(v, q("17시"), zone))
    }

    @Test
    fun termsAreAndedAndUnknownIsReported() {
        val v = view(at(10, 30, 18, 20), 40, 21.9)
        assertTrue(TripSearch.matches(v, q("10/30 18시 20~30km"), zone))
        assertFalse(TripSearch.matches(v, q("10/29 18시"), zone))
        val bad = q("10/30 회사")
        assertEquals(listOf("회사"), bad.unknown)
        assertEquals(1, bad.terms.size)
        assertTrue(q("13/40").unknown.isNotEmpty())
        assertTrue(q("25:00").unknown.isNotEmpty())
        assertTrue(q("   ").isEmpty)
    }

    @Test
    fun tripsAcrossMidnight() {
        val v = view(at(10, 30, 23, 40), 40, 12.0) // 23:40 - 00:20
        assertTrue(TripSearch.matches(v, q("00:10"), zone))
        assertTrue(TripSearch.matches(v, q("23시"), zone))
        assertTrue(TripSearch.matches(v, q("10/30"), zone))
    }
}
