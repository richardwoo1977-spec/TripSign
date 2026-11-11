// ver 1.0.1 | 2026-11-04 20:20
package io.tripsign.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId

class LogbookTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private object S : Signer { override fun sign(data: ByteArray) = "S" }

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private class Builder {
        val list = mutableListOf<SealedEntry>()
        fun add(e: Entry, at: Long = 0L) { list += Chain.seal(list.lastOrNull(), e, at, S) }
    }

    private fun trip(id: String, start: Long, a: Double?, b: Double?, km: Double, src: DistanceSource = DistanceSource.ODOMETER) =
        Entry(RecordType.TRIP, id, start, start + 600_000L, a, b, km, km, src)

    @Test
    fun integerRuleTelescopesAndKeepsMeasuredValue() {
        val b = Builder()
        val t = ms(2026, 10, 30, 9, 0)
        b.add(trip("A", t, 47525.8, 47526.4, 0.6))          // crosses a km mark: 47525 -> 47526 = 1
        b.add(trip("B", t + 3_600_000, 47526.4, 47527.0, 0.6)) // 47526 -> 47527 = 1 (47527.0 is a mark)
        b.add(trip("C", t + 7_200_000, 47527.0, 47527.6, 0.6)) // 47527 -> 47527 = 0
        val rows = Logbook.rows(b.list)
        assertEquals(listOf(1L, 1L, 0L), rows.map { it.km })
        assertEquals(listOf(47525L, 47526L, 47527L), rows.map { it.odoBefore })
        // rows chain: each ⑤ equals the previous ⑥, so the sum of ⑦ equals last ⑥ − first ⑤
        assertEquals(rows.last().odoAfter!! - rows.first().odoBefore!!, Logbook.totals(rows).km)
        assertEquals(0.6, rows[2].measuredKm, 1e-9)   // kept in the ledger, never printed
    }

    @Test
    fun binaryNoiseDoesNotDropAKm() {
        assertEquals(47526L, Logbook.meter(47525.99999999))
        assertEquals(47525L, Logbook.meter(47525.9))
    }

    @Test
    fun purposeColumnsAndTotalsAndRatio() {
        val b = Builder()
        val t = ms(2026, 10, 1, 8, 0)
        b.add(trip("C1", t, 1000.0, 1020.0, 20.0)); b.add(Entry(RecordType.PURPOSE, "C1", purpose = Purpose.COMMUTE), t + 700_000)
        b.add(trip("B1", t + 3_600_000, 1020.0, 1050.0, 30.0)); b.add(Entry(RecordType.PURPOSE, "B1", purpose = Purpose.BUSINESS), t + 4_300_000)
        b.add(trip("P1", t + 7_200_000, 1050.0, 1057.0, 7.0)); b.add(Entry(RecordType.PURPOSE, "P1", purpose = Purpose.PERSONAL), t + 7_900_000)
        b.add(trip("U1", t + 10_800_000, 1057.0, 1060.0, 3.0))
        b.add(Entry(RecordType.GAP, "G1", t + 20_000_000, t + 20_000_000, 1060.0, 1072.0, 0.0, 12.0, DistanceSource.ODOMETER))
        val rows = Logbook.rows(b.list)
        val tot = Logbook.totals(rows)
        assertEquals(72L, tot.km)
        assertEquals(20L, tot.commuteKm)
        assertEquals(30L, tot.businessKm)
        assertEquals(7L, tot.personalKm)
        assertEquals(3L, tot.unclassifiedKm)
        assertEquals(12L, tot.gapKm)
        assertEquals(1, tot.unclassifiedCount)
        assertEquals(1, tot.gapCount)
        assertEquals(tot.km, tot.commuteKm + tot.businessKm + tot.personalKm + tot.unclassifiedKm + tot.gapKm)
        assertEquals(50L, tot.workKm)
        assertEquals(69.4, tot.ratioPct!!, 1e-9)   // 50 / 72 = 69.44..
        val c1 = rows.first { it.id == "C1" }
        assertEquals(20L, c1.commuteKm); assertNull(c1.businessKm)
        val g = rows.first { it.id == "G1" }
        assertNull(g.commuteKm); assertNull(g.businessKm); assertFalse(g.unclassified)
    }

    @Test
    fun ratioRoundsHalfUpAndIsNullWithoutDriving() {
        assertEquals(86.6, LogTotals(20002, 17320, 0, 0, 0, 0, 0, 0, 1).ratioPct!!, 1e-9) // 86.5913..
        assertEquals(50.1, LogTotals(1000, 501, 0, 0, 0, 0, 0, 0, 1).ratioPct!!, 1e-9)
        assertEquals(66.7, LogTotals(3, 2, 0, 0, 0, 0, 0, 0, 1).ratioPct!!, 1e-9)
        assertNull(LogTotals(0, 0, 0, 0, 0, 0, 0, 0, 0).ratioPct)
    }

    @Test
    fun latestPurposeAndDriverWinHistoryCounted() {
        val b = Builder()
        val t = ms(2026, 10, 2, 10, 0)
        b.add(trip("T", t, 10.0, 25.0, 15.0))
        b.add(Entry(RecordType.ASSIGN, "T", note = Driver("영업팀", "김하늘").encode()), t + 700_000)
        b.add(Entry(RecordType.PURPOSE, "T", purpose = Purpose.PERSONAL), t + 800_000)
        b.add(Entry(RecordType.PURPOSE, "T", purpose = Purpose.BUSINESS), t + 900_000)
        b.add(Entry(RecordType.ASSIGN, "T", note = Driver("경영지원본부", "황보예린").encode()), t + 950_000)
        val r = Logbook.rows(b.list).single()
        assertEquals(Purpose.BUSINESS, r.purpose)
        assertEquals(1, r.purposeChanges)
        assertEquals(Driver("경영지원본부", "황보예린"), r.driver)
        assertFalse(r.classifiedLate)
    }

    @Test
    fun lateClassificationIsAFact() {
        val b = Builder()
        val t = ms(2026, 10, 3, 10, 0)
        b.add(trip("T", t, 10.0, 25.0, 15.0))
        b.add(Entry(RecordType.PURPOSE, "T", purpose = Purpose.BUSINESS), t + 600_000 + 25 * 3_600_000L)
        assertTrue(Logbook.rows(b.list).single().classifiedLate)
    }

    @Test
    fun noOdometerFallsBackToRoundedDistance() {
        val b = Builder()
        b.add(trip("S", ms(2026, 10, 4, 9, 0), null, null, 12.5, DistanceSource.SPEED))
        val r = Logbook.rows(b.list).single()
        assertTrue(r.noOdometer)
        assertEquals(13L, r.km)
    }

    @Test
    fun cleanLimitsAndStripsSeparators() {
        assertEquals("남궁민수가", Logbook.clean("남궁민수가나", Logbook.NAME_MAX))
        assertEquals("해외영업2팀", Logbook.clean(" 해외|영업\t2팀\n", Logbook.DEPT_MAX))
        val d = Driver("A\u001FB", "C")
        assertEquals(Driver("AB", "C"), Driver.decode(d.encode()))
        val v = Vehicle("그랜저", "12가3456")
        assertEquals(v, Vehicle.decode(v.encode()))
    }

    @Test
    fun fiscalYearAndMonthFilters() {
        val fy = FiscalYear.of(YearMonth.of(2027, 3), 4)
        assertEquals(FiscalYear(2026, 4), fy)
        assertTrue(fy.contains(YearMonth.of(2026, 4)))
        assertTrue(fy.contains(YearMonth.of(2027, 3)))
        assertFalse(fy.contains(YearMonth.of(2027, 4)))
        assertEquals(FiscalYear(2026, 1), FiscalYear.of(YearMonth.of(2026, 12), 1))
        val b = Builder()
        b.add(trip("M3", ms(2026, 3, 31, 23, 50), 1.0, 2.0, 1.0))
        b.add(trip("A4", ms(2026, 4, 1, 0, 10), 2.0, 5.0, 3.0))
        val rows = Logbook.rows(b.list)
        assertEquals(listOf("A4"), Logbook.month(rows, YearMonth.of(2026, 4), zone).map { it.id })
        assertEquals(listOf("A4"), Logbook.fiscalYear(rows, FiscalYear(2026, 4), zone).map { it.id })
        assertEquals(listOf("M3"), Logbook.fiscalYear(rows, FiscalYear(2025, 4), zone).map { it.id })
    }
}
