// ver 1.1.0 | 2026-11-04 12:10
package io.tripsign.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ChainAndAnalyticsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private object TestSigner : Signer {
        override fun sign(data: ByteArray): String = "S" + Chain.sha256Hex(String(data, Charsets.UTF_8)).take(16)
    }

    private object TestVerifier : Verifier {
        override fun verify(data: ByteArray, signature: String): Boolean =
            signature == "S" + Chain.sha256Hex(String(data, Charsets.UTF_8)).take(16)
    }

    private fun trip(id: String, start: Long, km: Double) = Entry(
        type = RecordType.TRIP, tripId = id, startMs = start, endMs = start + 600_000L,
        startOdoKm = 100.0, endOdoKm = 100.0 + km, distanceKm = km, distanceSource = DistanceSource.ODOMETER
    )

    private fun ledger(vararg entries: Entry): List<SealedEntry> {
        val out = mutableListOf<SealedEntry>()
        entries.forEachIndexed { i, e -> out += Chain.seal(out.lastOrNull(), e, 1_000L + i, TestSigner) }
        return out
    }

    @Test
    fun intactChainVerifies() {
        val l = ledger(trip("T1", 0L, 5.0), trip("T2", 1_000_000L, 7.0), Entry(RecordType.PURPOSE, "T1", purpose = Purpose.BUSINESS))
        val c = Chain.verify(l, TestVerifier)
        assertTrue(c.ok)
        assertEquals(3, c.count)
        assertEquals(l.last().hash, c.headHash)
    }

    @Test
    fun editedDistanceIsDetected() {
        val l = ledger(trip("T1", 0L, 5.0), trip("T2", 1_000_000L, 7.0)).toMutableList()
        l[0] = l[0].copy(entry = l[0].entry.copy(distanceKm = 50.0))
        val c = Chain.verify(l, TestVerifier)
        assertFalse(c.ok)
        assertEquals(1L, c.firstBadSeq)
    }

    @Test
    fun deletedEntryIsDetected() {
        val l = ledger(trip("T1", 0L, 5.0), trip("T2", 1_000_000L, 7.0), trip("T3", 2_000_000L, 9.0)).toMutableList()
        l.removeAt(1)
        assertFalse(Chain.verify(l, TestVerifier).ok)
    }

    @Test
    fun forgedSignatureIsDetected() {
        val l = ledger(trip("T1", 0L, 5.0)).toMutableList()
        l[0] = l[0].copy(signature = "SFAKE")
        assertFalse(Chain.verify(l, TestVerifier).ok)
    }

    @Test
    fun latestPurposeWinsAndChangesAreCounted() {
        val l = ledger(
            trip("T1", 0L, 5.0),
            Entry(RecordType.PURPOSE, "T1", purpose = Purpose.PERSONAL),
            Entry(RecordType.PURPOSE, "T1", purpose = Purpose.BUSINESS)
        )
        val v = Analytics.buildViews(l)
        assertEquals(1, v.size)
        assertEquals(Purpose.BUSINESS, v[0].purpose)
        assertEquals(1, v[0].purposeChanges)
    }

    @Test
    fun gapCountsInTotalButNotAsBusiness() {
        val gap = Entry(RecordType.GAP, "G1", startMs = 2_000_000L, endMs = 2_000_000L, distanceKm = 10.0, distanceSource = DistanceSource.ODOMETER)
        val l = ledger(trip("T1", 0L, 10.0), Entry(RecordType.PURPOSE, "T1", purpose = Purpose.BUSINESS), gap)
        val s = Analytics.stats(Analytics.buildViews(l), Range(0L, 10_000_000L))
        assertEquals(20.0, s.totalKm, 0.001)
        assertEquals(0.5, s.businessRatio, 0.001)
        assertEquals(10.0, s.gapKm, 0.001)
    }

    @Test
    fun brokenSignatureGivesGradeD() {
        val l = ledger(trip("T1", 0L, 10.0))
        val t = Analytics.trust(Analytics.buildViews(l), Range(0L, 10_000_000L), signatureOk = false)
        assertEquals("D", t.grade)
        assertEquals(0, t.score)
    }

    @Test
    fun monthRangeAndPrevious() {
        val anchor = java.time.LocalDate.of(2026, 3, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val m = Analytics.range(Period.MONTH, anchor, zone)
        val p = Analytics.previous(Period.MONTH, m, zone)
        val feb1 = java.time.LocalDate.of(2026, 2, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        val mar1 = java.time.LocalDate.of(2026, 3, 1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(mar1, m.fromMs)
        assertEquals(feb1, p.fromMs)
        assertEquals(mar1, p.toMs)
    }

    @Test
    fun demoDataIsValidAndCoversLastYear() {
        val l = DemoData.build(zone)
        assertTrue(Chain.verify(l, DemoData.DemoVerifier).ok)
        val views = Analytics.buildViews(l)
        val cmp = Analytics.comparisons(views, DemoData.anchorMs(zone), zone)
        assertEquals(4, cmp.size)
        assertTrue(cmp.all { it.hasBefore })
        val month = Analytics.stats(views, Analytics.range(Period.MONTH, DemoData.anchorMs(zone), zone))
        assertTrue(month.totalKm > 1000.0)
        assertTrue(month.businessRatio > 0.8)
        val trust = Analytics.trust(views, Analytics.range(Period.MONTH, DemoData.anchorMs(zone), zone), true)
        assertTrue(trust.score in 80..100)
    }

    @Test
    fun trustIssuesListOnlyDeductionsBiggestFirst() {
        val gap = Entry(RecordType.GAP, "G1", startMs = 2_000_000L, endMs = 2_000_000L, distanceKm = 10.0, distanceSource = DistanceSource.ODOMETER)
        val l = ledger(
            trip("T1", 0L, 30.0),
            Entry(RecordType.PURPOSE, "T1", purpose = Purpose.BUSINESS),
            trip("T2", 1_000_000L, 10.0),
            gap
        )
        val t = Analytics.trust(Analytics.buildViews(l), Range(0L, 10_000_000L), true)
        val kinds = t.issues.map { it.kind }
        assertEquals(listOf(TrustIssueKind.GAP, TrustIssueKind.UNCLASSIFIED), kinds)
        assertEquals(100 - t.gapPenalty - t.unclassifiedPenalty, t.score)
        val clean = Analytics.trust(Analytics.buildViews(ledger(trip("T9", 0L, 5.0))), Range(0L, 10_000_000L), true)
        assertEquals(1, clean.issues.size)
    }

    @Test
    fun monthBucketsCoverEveryDay() {
        val views = Analytics.buildViews(DemoData.build(zone))
        val b = Analytics.buckets(views, Period.MONTH, DemoData.anchorMs(zone), zone)
        assertEquals(31, b.size)
        val month = Analytics.stats(views, Analytics.range(Period.MONTH, DemoData.anchorMs(zone), zone))
        assertEquals(month.totalKm, b.sumOf { it.km }, 0.01)
    }
}
