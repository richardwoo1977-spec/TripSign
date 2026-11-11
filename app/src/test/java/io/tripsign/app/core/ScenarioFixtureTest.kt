// ver 1.1.0 | 2026-11-05 09:23
package io.tripsign.app.core

import org.junit.Test
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.Base64

/**
 * Builds signed export packs for the "what has this phone already received?" simulations
 * (tools/sim_coverage.js). Each scenario is a list of exports a person could really make:
 * at a moment (asOf), for a range of months, from the car's ledger as it was at that moment.
 * Runs only when TS_FIXTURE_DIR is set.
 */
class ScenarioFixtureTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private class EcSigner(val kp: KeyPair) : Signer {
        override fun sign(data: ByteArray): String {
            val s = Signature.getInstance("SHA256withECDSA"); s.initSign(kp.private); s.update(data)
            return Base64.getEncoder().encodeToString(s.sign())
        }
    }

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** Sample ledger + optional extra entries (by creation time), re-sealed in creation order with [kp]. */
    private fun ledger(kp: KeyPair, extra: List<Pair<Entry, Long>> = emptyList(), keepAfterMs: Long? = null): List<SealedEntry> {
        val base = DemoData.build(zone).map { it.entry to it.createdAtMs }
        val all = (base + extra).sortedBy { it.second }.filter { keepAfterMs == null || it.second >= keepAfterMs }
        val out = ArrayList<SealedEntry>(all.size)
        val signer = EcSigner(kp)
        for ((e, at) in all) out += Chain.seal(out.lastOrNull(), e, at, signer)
        return out
    }

    private fun pack(kp: KeyPair, led: List<SealedEntry>, asOf: Long, from: YearMonth, to: YearMonth, fy: Int = 1): String {
        val known = led.filter { it.createdAtMs <= asOf }
        val slice = ExportPack.select(known, from, to, zone)
        val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
        val header = linkedMapOf(
            "from" to from.toString(), "to" to to.toString(), "head" to (known.lastOrNull()?.seq ?: 0L).toString(),
            "born" to (led.firstOrNull()?.createdAtMs ?: 0L).toString(),
            "fy" to fy.toString(), "tzo" to "540", "pub" to pub, "made" to asOf.toString()
        )
        return ExportPack.payloadText(header, slice)
    }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\u001F", "\\u001f")

    @Test
    fun scenarios() {
        val dir = System.getenv("TS_FIXTURE_DIR") ?: return
        val a = keys()
        val led = ledger(a)
        val ym = { y: Int, m: Int -> YearMonth.of(y, m) }
        val sc = LinkedHashMap<String, Pair<Long, List<String>>>()   // name -> (today on the phone, packs)

        // S1 every month on the 1st at 09:00, "that month only"
        sc["S1_monthly_on_1st"] = ms(2026, 10, 1, 9, 30) to (1..9).map { m -> pack(a, led, ms(2026, m + 1, 1, 9, 0), ym(2026, m), ym(2026, m)) }
        // S2 the director's case: fiscal year on 5/10, then fiscal year on 6/10
        sc["S2_fy_0510_then_0610"] = ms(2026, 6, 10, 18, 30) to listOf(
            pack(a, led, ms(2026, 5, 10, 18, 0), ym(2026, 1), ym(2026, 5)),
            pack(a, led, ms(2026, 6, 10, 18, 0), ym(2026, 1), ym(2026, 6)))
        // S3 mid-month, "that month only", twice: May 11..31 never sent
        sc["S3_month_only_midmonth"] = ms(2026, 6, 10, 18, 30) to listOf(
            pack(a, led, ms(2026, 5, 10, 18, 0), ym(2026, 5), ym(2026, 5)),
            pack(a, led, ms(2026, 6, 10, 18, 0), ym(2026, 6), ym(2026, 6)))
        // S4 forgot February and March
        sc["S4_forgot_two_months"] = ms(2026, 5, 1, 10, 0) to listOf(
            pack(a, led, ms(2026, 2, 1, 9, 0), ym(2026, 1), ym(2026, 1)),
            pack(a, led, ms(2026, 5, 1, 9, 0), ym(2026, 4), ym(2026, 4)))
        // S5 a May trip re-classified on 6/15 after a 6/10 export, then exported again on 6/20
        val mayTrip = led.first { it.entry.type == RecordType.TRIP && YearMonth.from(java.time.Instant.ofEpochMilli(it.entry.startMs).atZone(zone)) == ym(2026, 5) }
        val led5 = ledger(a, listOf(Entry(RecordType.PURPOSE, mayTrip.entry.tripId, purpose = Purpose.PERSONAL, note = "목적 변경") to ms(2026, 6, 15, 12, 0)))
        sc["S5_reclassified_after_export"] = ms(2026, 6, 20, 19, 0) to listOf(
            pack(a, led5, ms(2026, 6, 10, 18, 0), ym(2026, 1), ym(2026, 6)),
            pack(a, led5, ms(2026, 6, 20, 18, 0), ym(2026, 1), ym(2026, 6)))
        File(dir, "s5_trip.txt").writeText(mayTrip.entry.tripId)
        // S6 app reinstalled on 7/1: new key, new ledger that starts at #1 with trips from July
        val b = keys()
        val ledB = ledger(b, listOf(Entry(RecordType.VEHICLE, "-", note = Vehicle("그랜저 HEV", "123가4567").encode()) to ms(2026, 7, 1, 0, 0)), keepAfterMs = ms(2026, 7, 1, 0, 0))
        sc["S6_reinstalled_new_key"] = ms(2026, 8, 1, 10, 0) to listOf(
            pack(a, led, ms(2026, 6, 30, 20, 0), ym(2026, 1), ym(2026, 6)),
            pack(b, ledB, ms(2026, 8, 1, 9, 0), ym(2026, 1), ym(2026, 7)))
        // S7 nothing stored on this phone
        sc["S7_empty_phone"] = ms(2026, 6, 10, 18, 0) to emptyList()
        // S8 the same export received twice
        val p8 = pack(a, led, ms(2026, 6, 10, 18, 0), ym(2026, 1), ym(2026, 6))
        sc["S8_same_pack_twice"] = ms(2026, 6, 10, 18, 30) to listOf(p8, p8)
        // S9 fiscal year starting in April; today 2026-05-10
        sc["S9_fy_april"] = ms(2026, 5, 10, 18, 30) to listOf(
            pack(a, led, ms(2026, 3, 31, 20, 0), ym(2025, 4), ym(2026, 3), fy = 4),
            pack(a, led, ms(2026, 5, 10, 18, 0), ym(2026, 4), ym(2026, 5), fy = 4))
        // S10 exported at 00:05 on 6/1 while a trip that started 5/31 23:50 was still running (ends 00:20)
        val tStart = ms(2026, 5, 31, 23, 50); val tEnd = ms(2026, 6, 1, 0, 20)
        val led10 = ledger(a, listOf(Entry(RecordType.TRIP, "T$tStart", tStart, tEnd, 50000.0, 50012.0, 12.0, 12.0, DistanceSource.ODOMETER, 60.0) to tEnd + 1000))
        sc["S10_export_during_last_trip"] = ms(2026, 6, 1, 0, 10) to listOf(pack(a, led10, ms(2026, 6, 1, 0, 5), ym(2026, 1), ym(2026, 5)))
        // S11 fiscal year sent on the last day of the month at 18:00, trips continue that evening
        sc["S11_fy_on_last_day_evening"] = ms(2026, 7, 1, 9, 0) to listOf(pack(a, led, ms(2026, 6, 30, 18, 0), ym(2026, 1), ym(2026, 6)))

        // S12 the emulator drive test: app installed on 11/4, fiscal year sent the same afternoon (months 1~10 held nothing)
        val c = keys()
        val t12 = ms(2026, 11, 4, 16, 7); val t12e = ms(2026, 11, 4, 16, 8)
        val led12 = ledger(c, listOf(
            Entry(RecordType.VEHICLE, "-", note = Vehicle("아이오닉5", "12가3456").encode()) to ms(2026, 11, 4, 15, 40),
            Entry(RecordType.TRIP, "T$t12", t12, t12e, 0.0, 2.0, 1.1, 2.0, DistanceSource.ODOMETER, 42.0) to t12e + 1000
        ), keepAfterMs = ms(2026, 11, 4, 15, 40))
        sc["S12_installed_midyear"] = ms(2026, 11, 5, 9, 12) to listOf(pack(c, led12, ms(2026, 11, 4, 16, 10), ym(2026, 1), ym(2026, 11)))
        // S13 installed on 5/20, fiscal year sent on 7/2: Jan~Apr held nothing, May counts from 5/20
        val d = keys()
        val led13 = ledger(d, listOf(Entry(RecordType.VEHICLE, "-", note = Vehicle("그랜저 HEV", "123가4567").encode()) to ms(2026, 5, 20, 0, 0)), keepAfterMs = ms(2026, 5, 20, 0, 0))
        sc["S13_installed_midmonth_past"] = ms(2026, 7, 2, 10, 0) to listOf(pack(d, led13, ms(2026, 7, 2, 9, 0), ym(2026, 1), ym(2026, 6)))

        val sb = StringBuilder("[\n")
        sc.entries.forEachIndexed { i, (name, v) ->
            sb.append("{\"name\":\"").append(name).append("\",\"today\":").append(v.first).append(",\"packs\":[")
            v.second.forEachIndexed { j, t -> if (j > 0) sb.append(','); sb.append('"').append(esc(t)).append('"') }
            sb.append("]}").append(if (i < sc.size - 1) ",\n" else "\n")
        }
        sb.append("]\n")
        File(dir, "scenarios.json").writeText(sb.toString())
    }
}
