// ver 1.2.0 | 2026-11-07 13:25
package io.tripsign.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** 0.5.2 rules: 미인증 운행, skipped purpose, administrator changes, phone checks, the driver's window. */
class IdentityRulesTest {
    private object S : Signer { override fun sign(data: ByteArray) = "S" }

    private class Builder {
        val list = mutableListOf<SealedEntry>()
        fun add(e: Entry, at: Long = 0L) { list += Chain.seal(list.lastOrNull(), e, at, S) }
    }

    private val t0 = 1_793_000_000_000L
    private fun trip(id: String, start: Long, a: Double, b: Double, note: String = "") =
        Entry(RecordType.TRIP, id, start, start + 600_000L, a, b, b - a, b - a, DistanceSource.ODOMETER, note = note)
    private val hong = Driver("영업팀", "홍길동")
    private val kim = Driver("총무팀", "김하늘")

    @Test
    fun unverifiedTripIsNeverBusinessEvenWithAPurpose() {
        val b = Builder()
        b.add(trip("A", t0, 100.0, 120.0, Logbook.NOTE_UNVERIFIED))
        b.add(Entry(RecordType.PURPOSE, "A", purpose = Purpose.BUSINESS), t0 + 700_000L)
        b.add(trip("B", t0 + 3_600_000L, 120.0, 130.0))
        b.add(Entry(RecordType.PURPOSE, "B", purpose = Purpose.BUSINESS), t0 + 4_300_000L)
        val rows = Logbook.rows(b.list)
        assertTrue(rows[0].unverified)
        assertNull(rows[0].businessKm)
        assertFalse(rows[0].unclassified)
        val t = Logbook.totals(rows)
        assertEquals(30L, t.km)
        assertEquals(10L, t.businessKm)
        assertEquals(20L, t.unverifiedKm)
        assertEquals(1, t.unverifiedCount)
        assertEquals(0, t.unclassifiedCount)
        assertEquals(33.3, t.ratioPct!!, 1e-9)
    }

    @Test
    fun skipKeepsUnclassifiedAndAdminChangeIsMarked() {
        val b = Builder()
        b.add(trip("A", t0, 100.0, 110.0))
        b.add(Entry(RecordType.PURPOSE, "A", purpose = null, note = Logbook.NOTE_SKIP), t0 + 650_000L)
        var r = Logbook.rows(b.list).single()
        assertTrue(r.purposeSkipped)
        assertTrue(r.unclassified)
        assertEquals(0, r.purposeChanges)
        assertFalse(Logbook.driverMayClassify(r, "A", t0 + 700_000L, parkedIdle = true))
        b.add(Entry(RecordType.PURPOSE, "A", purpose = Purpose.COMMUTE, note = Logbook.adminNote(kim)), t0 + 90_000_000L)
        r = Logbook.rows(b.list).single()
        assertTrue(r.adminEdited)
        assertEquals(Purpose.COMMUTE, r.purpose)
        assertEquals(10L, r.commuteKm)
        assertEquals(0, r.purposeChanges)              // the skip is not a "change"
        assertEquals(kim, Logbook.adminOf(b.list.last().entry.note))
        assertTrue(r.classifiedLate)
    }

    @Test
    fun driverWindowOnlyForNewestTripParkedAndWithinTenMinutes() {
        val b = Builder()
        b.add(trip("A", t0, 100.0, 110.0))
        b.add(trip("B", t0 + 3_600_000L, 110.0, 115.0))
        val rows = Logbook.rows(b.list)
        val endB = rows[1].endMs
        assertTrue(Logbook.driverMayClassify(rows[1], "B", endB + 60_000L, parkedIdle = true))
        assertFalse(Logbook.driverMayClassify(rows[1], "B", endB + 60_000L, parkedIdle = false))   // moving
        assertFalse(Logbook.driverMayClassify(rows[1], "B", endB + Logbook.DRIVER_WINDOW_MS + 1, parkedIdle = true))
        assertFalse(Logbook.driverMayClassify(rows[0], "B", rows[0].endMs + 60_000L, parkedIdle = true))   // older trip
        // a driver choice may still be changed inside the window
        b.add(Entry(RecordType.PURPOSE, "B", purpose = Purpose.PERSONAL), endB + 30_000L)
        val r = Logbook.rows(b.list)[1]
        assertTrue(Logbook.driverMayClassify(r, "B", endB + 120_000L, parkedIdle = true))
    }

    @Test
    fun phoneCheckConfirmsOnlyTheAssignRightAfterIt() {
        val b = Builder()
        b.add(trip("A", t0, 100.0, 110.0, Logbook.NOTE_UNVERIFIED))
        b.add(Entry(RecordType.ASSIGN, "A", note = hong.encode()), t0 + 700_000L)   // not confirmed (old PIN path)
        assertFalse(Logbook.rows(b.list).single().authConfirmed)
        b.add(Entry(RecordType.AUTH, "A", note = "tsa1~x" + Logbook.SEP + "sig"), t0 + 800_000L)
        b.add(Entry(RecordType.ASSIGN, "A", note = kim.encode()), t0 + 800_000L)
        val r = Logbook.rows(b.list).single()
        assertTrue(r.authConfirmed)
        assertEquals(kim, r.driver)
        assertTrue(r.unverified)                        // the 미인증 mark stays as history
        assertNull(r.businessKm)
    }

    @Test
    fun authProofParsesAndVerifiesWithTheServerKey() {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val kp = g.generateKeyPair()
        val text = listOf("tsa1", "abcdefghijklmnopqrstuvwxyz", "ab".repeat(16), "confirm", "T123",
            "e".repeat(64), "f".repeat(64), "ho**@example.com", "010-****-5678", "1793000000000", "1793000060000").joinToString("~")
        val sig = Signature.getInstance("SHA256withECDSA").run { initSign(kp.private); update(text.toByteArray()); Base64.getEncoder().encodeToString(sign()) }
        val pub = AuthProof.publicKey(Base64.getEncoder().encodeToString(kp.public.encoded))
        val p = AuthProof.parse(text, sig)
        assertNotNull(p)
        assertEquals("T123", p!!.target)
        assertEquals("e".repeat(64), p.personId)
        assertEquals(1_793_000_060_000L, p.claimedAtMs)
        assertTrue(AuthProof.signatureOk(p.text, p.signature, pub))
        assertFalse(AuthProof.signatureOk(text.replace("confirm", "enroll"), sig, pub))
        assertEquals(p, AuthProof.fromNote(p.note()))
        assertNull(AuthProof.parse("tsa0~a", sig))
    }

    @Test
    fun macsMatchTheServer() {
        // same values as server/tripsign_auth.py qr_mac / claim_mac with key = bytes(0..31)
        val k = ByteArray(32) { it.toByte() }
        assertEquals("1d3c850c609b97de", AuthProof.qrMac(k, "abcdefghijklmnopqrstuvwxyz", 176543210L))
        assertEquals("2ec82b7f0d1d4652f213b4cea769e69b", AuthProof.claimMac(k, "abcdefghijklmnopqrstuvwxyz", "123456"))
        assertEquals(176543210L, AuthProof.step(1_765_432_105_000L, 10))
        assertEquals(32, AuthProof.carId(ByteArray(91) { 1 }).length)
    }

    @Test
    fun restoredDriveClosesAtTheLastMomentSeen() {
        val e = TripEngine(EngineConfig(), 100.0)
        e.restore("T1", t0, 100.0, 4.0, 15.0)
        assertTrue(e.isDriving)
        e.onOdometer(104.2, t0 + 3_000_000L)                // first reading after the restart: no GAP for the open drive
        val out = e.forceFinish(t0 + 900_000L)
        val fin = out.filterIsInstance<EngineOutput.TripFinished>().single().entry
        assertEquals(t0 + 900_000L, fin.endMs)
        assertEquals(4.2, fin.distanceKm, 1e-9)
        assertFalse(e.isDriving)
        // the next reading continues from where that drive ended
        assertTrue(e.onOdometer(104.2, t0 + 3_100_000L).none { it is EngineOutput.GapDetected })
    }

    /** 0.5.2a 안 B: phone check, then the administrator's purpose — only then the km counts; the mark stays. */
    @Test
    fun unverifiedCountsOnlyAfterPhoneCheckAndThenAdminPurpose() {
        val b = Builder()
        b.add(trip("A", t0, 100.0, 120.0, Logbook.NOTE_UNVERIFIED))
        // administrator before any phone check: does not count
        b.add(Entry(RecordType.PURPOSE, "A", purpose = Purpose.BUSINESS, note = Logbook.adminNote(kim)), t0 + 700_000L)
        var r = Logbook.rows(b.list).single()
        assertFalse(r.settledLater); assertNull(r.businessKm); assertFalse(r.counts); assertFalse(r.adminMayClassifyUnverified)
        // phone check right before the user record
        b.add(Entry(RecordType.AUTH, "A", note = "tsa1"), t0 + 800_000L)
        b.add(Entry(RecordType.ASSIGN, "A", note = hong.encode()), t0 + 800_001L)
        r = Logbook.rows(b.list).single()
        assertTrue(r.authConfirmed); assertTrue(r.adminMayClassifyUnverified)
        assertFalse(r.settledLater)                        // the earlier admin purpose came before the check
        assertNull(r.businessKm)
        var t = Logbook.totals(listOf(r))
        assertEquals(20L, t.unverifiedKm); assertEquals(0L, t.businessKm)
        // administrator after the check: counts, mark stays
        b.add(Entry(RecordType.PURPOSE, "A", purpose = Purpose.COMMUTE, note = Logbook.adminNote(kim)), t0 + 900_000L)
        r = Logbook.rows(b.list).single()
        assertTrue(r.unverified); assertTrue(r.settledLater); assertTrue(r.counts)
        assertEquals(20L, r.commuteKm); assertNull(r.businessKm)
        t = Logbook.totals(listOf(r))
        assertEquals(20L, t.commuteKm); assertEquals(0L, t.unverifiedKm); assertEquals(0, t.unverifiedCount)
        assertEquals(100.0, t.ratioPct!!, 1e-9)
        // a driver's (non-admin) purpose after the check does not settle it
        b.add(Entry(RecordType.PURPOSE, "A", purpose = Purpose.BUSINESS), t0 + 950_000L)
        r = Logbook.rows(b.list).single()
        assertFalse(r.settledLater); assertNull(r.businessKm)
        // personal by the administrator: settled, counts as personal
        b.add(Entry(RecordType.PURPOSE, "A", purpose = Purpose.PERSONAL, note = Logbook.adminNote(kim)), t0 + 990_000L)
        r = Logbook.rows(b.list).single()
        assertTrue(r.settledLater); assertNull(r.commuteKm); assertNull(r.businessKm)
        t = Logbook.totals(listOf(r))
        assertEquals(20L, t.personalKm); assertEquals(0L, t.unverifiedKm)
    }

    /** A user record without a phone check right before it does not confirm (so the administrator cannot settle). */
    @Test
    fun assignWithoutAuthRightBeforeDoesNotConfirm() {
        val b = Builder()
        b.add(trip("A", t0, 100.0, 110.0, Logbook.NOTE_UNVERIFIED))
        b.add(Entry(RecordType.AUTH, "A", note = "tsa1"), t0 + 800_000L)
        b.add(Entry(RecordType.WARN, "W1", note = "screen"), t0 + 800_001L)
        b.add(Entry(RecordType.ASSIGN, "A", note = hong.encode()), t0 + 800_002L)
        b.add(Entry(RecordType.PURPOSE, "A", purpose = Purpose.BUSINESS, note = Logbook.adminNote(kim)), t0 + 900_000L)
        val r = Logbook.rows(b.list).single()
        assertFalse(r.authConfirmed); assertFalse(r.settledLater); assertNull(r.businessKm)
    }

    /** 0.5.2g: the 미인증 운행 card at the next car start — once, only for the newest record, gone after 내가 아니다. */
    @Test
    fun pendingUnverifiedShowsOnceAtTheNextStart() {
        val b = Builder()
        val ign1 = t0 - 60_000L
        b.add(trip("A", t0, 100.0, 102.0, Logbook.NOTE_UNVERIFIED))   // ends t0 + 600 000
        val ign2 = t0 + 900_000L
        var rows = Logbook.rows(b.list)
        // before any new start: nothing (the arrival QR handles it)
        assertNull(Logbook.pendingUnverified(rows, ign1 - 1, ign1))
        // the first start after it: shown
        assertEquals("A", Logbook.pendingUnverified(rows, ign1, ign2)?.id)
        // a later start with no drive in between: not again
        assertNull(Logbook.pendingUnverified(rows, ign2, ign2 + 600_000L))
        // 내가 아니다 at this start: gone, and the ledger keeps it
        b.add(Entry(RecordType.DENY, "A", ign2 + 5_000L, ign2 + 5_000L, note = Logbook.NOTE_NOT_ME), ign2 + 5_000L)
        rows = Logbook.rows(b.list)
        assertNull(Logbook.pendingUnverified(rows, ign1, ign2))
        assertEquals(1, rows.single().denyCount)
        assertEquals(ign2 + 5_000L, rows.single().deniedAtMs)
        // nothing about the trip itself changed
        assertTrue(rows.single().unverified); assertNull(rows.single().driver); assertEquals(2L, rows.single().km)
    }

    @Test
    fun pendingUnverifiedIgnoresConfirmedVerifiedAndOlderTrips() {
        val b = Builder()
        val ign1 = t0 - 60_000L
        b.add(trip("A", t0, 100.0, 102.0, Logbook.NOTE_UNVERIFIED))
        b.add(trip("B", t0 + 700_000L, 102.0, 103.0))                  // a newer drive: A is the logbook's business
        assertNull(Logbook.pendingUnverified(Logbook.rows(b.list), ign1, t0 + 2_000_000L))
        val c = Builder()
        c.add(trip("A", t0, 100.0, 102.0, Logbook.NOTE_UNVERIFIED))
        c.add(Entry(RecordType.AUTH, "A", note = "tsa1"), t0 + 650_000L)
        c.add(Entry(RecordType.ASSIGN, "A", note = hong.encode()), t0 + 650_000L)   // confirmed by a phone check
        assertNull(Logbook.pendingUnverified(Logbook.rows(c.list), ign1, t0 + 900_000L))
        val d = Builder()
        d.add(trip("A", t0, 100.0, 102.0))                             // an ordinary drive
        assertNull(Logbook.pendingUnverified(Logbook.rows(d.list), ign1, t0 + 900_000L))
        assertNull(Logbook.pendingUnverified(emptyList(), ign1, t0))
    }

    /** DENY travels with its trip in an export, like the purpose and the phone check. */
    @Test
    fun exportKeepsDenyWithItsTrip() {
        val b = Builder()
        b.add(trip("A", t0, 100.0, 102.0, Logbook.NOTE_UNVERIFIED))
        b.add(trip("B", t0 + 3_600_000L, 102.0, 103.0))
        b.add(Entry(RecordType.DENY, "A", t0 + 3_000_000L, t0 + 3_000_000L, note = Logbook.NOTE_NOT_ME), t0 + 5_000_000L)
        val zone = java.time.ZoneId.of("Asia/Seoul")
        val m = java.time.YearMonth.from(java.time.Instant.ofEpochMilli(t0).atZone(zone))
        val slice = ExportPack.select(b.list, m, zone)
        assertTrue(slice.main.any { it.entry.type == RecordType.DENY && it.entry.tripId == "A" })
    }
}
