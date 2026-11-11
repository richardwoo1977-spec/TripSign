// ver 1.3.0 | 2026-11-04 20:45
package io.tripsign.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

class ExportPackTest {
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    private class EcSigner(val kp: KeyPair) : Signer {
        override fun sign(data: ByteArray): String {
            val s = Signature.getInstance("SHA256withECDSA"); s.initSign(kp.private); s.update(data)
            return Base64.getEncoder().encodeToString(s.sign())
        }
    }

    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    /** Sept + Oct + one Nov trip, vehicle record first, drivers assigned, one late re-classification. */
    private fun ledger(signer: Signer): List<SealedEntry> {
        val out = mutableListOf<SealedEntry>()
        fun add(e: Entry, at: Long) { out += Chain.seal(out.lastOrNull(), e, at, signer) }
        add(Entry(RecordType.VEHICLE, "-", note = Vehicle("그랜저 HEV", "12가3456").encode()), ms(2026, 9, 1, 8, 0))
        var odo = 47000.3
        var t = ms(2026, 9, 28, 8, 0)
        val purposes = listOf(Purpose.COMMUTE, Purpose.BUSINESS, Purpose.BUSINESS, Purpose.PERSONAL, Purpose.COMMUTE)
        for (i in 0 until 45) {
            val km = 3.7 + (i % 7) * 4.1
            val id = "T$t"
            add(Entry(RecordType.TRIP, id, t, t + 1_800_000, odo, odo + km, km, km, DistanceSource.ODOMETER, 80.0), t + 1_801_000)
            add(Entry(RecordType.ASSIGN, id, note = Driver("영업팀", if (i % 3 == 0) "김하늘" else "남궁민수").encode()), t + 1_802_000)
            if (i != 44) add(Entry(RecordType.PURPOSE, id, purpose = purposes[i % purposes.size]), t + 1_860_000)
            odo += km
            t += if (i % 4 == 3) 20 * 3_600_000L else 3 * 3_600_000L
        }
        // re-classify an October trip in November (after the month's last TRIP)
        val oct = out.first { it.entry.type == RecordType.TRIP && YearMonth.from(java.time.Instant.ofEpochMilli(it.entry.startMs).atZone(zone)) == YearMonth.of(2026, 10) }
        add(Entry(RecordType.PURPOSE, oct.entry.tripId, purpose = Purpose.BUSINESS, note = "목적 변경"), t + 1_000)
        return out
    }

    @Test
    fun sliceCoversTheMonthAndLaterChanges() {
        val kp = keys()
        val all = ledger(EcSigner(kp))
        val slice = ExportPack.select(all, YearMonth.of(2026, 10), zone)
        val tripIds = all.filter { it.entry.type == RecordType.TRIP && YearMonth.from(java.time.Instant.ofEpochMilli(it.entry.startMs).atZone(zone)) == YearMonth.of(2026, 10) }.map { it.entry.tripId }.toSet()
        assertTrue(tripIds.isNotEmpty())
        val inSlice = slice.main.filter { it.entry.type == RecordType.TRIP }.map { it.entry.tripId }.toSet()
        assertTrue(inSlice.containsAll(tripIds))
        // contiguous
        slice.main.zipWithNext().forEach { (a, b) -> assertEquals(a.seq + 1, b.seq) }
        // the late PURPOSE change is the last ledger entry and must be inside
        assertEquals(all.last().seq, slice.main.last().seq)
        // vehicle record lies before the slice -> sent as extra
        assertEquals(RecordType.VEHICLE, slice.extra.single().entry.type)
        // neighbouring months touch: Sept slice ends at or after the seq where Oct slice starts minus one
        val sep = ExportPack.select(all, YearMonth.of(2026, 9), zone)
        assertTrue(sep.main.last().seq + 1 >= slice.main.first().seq)
        // the whole ledger from its first record: September starts at #1 (vehicle record inside, no extra)
        assertEquals(1L, sep.main.first().seq)
        assertTrue(sep.extra.isEmpty())
    }

    @Test
    fun roundTripThroughFramesAndVerifyLikeThePhone() {
        val kp = keys()
        val all = ledger(EcSigner(kp))
        val slice = ExportPack.select(all, YearMonth.of(2026, 10), zone)
        val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
        val header = linkedMapOf("from" to "2026-10", "to" to "2026-10", "fy" to "1", "pub" to pub, "made" to "1793000000000")
        val text = ExportPack.payloadText(header, slice)
        val gz = ExportPack.gzip(text)
        val frames = ExportPack.frames(gz, "ab12")
        // shuffled + duplicated + one corrupted copy: still reassembles
        val noisy = (frames + frames.take(3)).shuffled().toMutableList()
        noisy += frames[1].dropLast(1) + "A"
        val joined = ExportPack.join(noisy)
        assertNotNull(joined)
        assertEquals(text, ExportPack.gunzip(joined!!))
        // missing one frame -> null
        assertNull(ExportPack.join(frames.drop(1)))

        // phone-side verification, mirrored in Kotlin: rebuild the hash chain, check the head signature
        fun verify(t: String): Boolean {
            val ls = t.trimEnd('\n').split('\n')
            var prev: String? = null
            var headHash: String? = null
            var headSig: String? = null
            var extraOk = true
            for (line in ls.drop(1)) {
                val p = line.split('\t')
                var canon = p[1]
                if (p[0] == "E" && canon.endsWith("|")) canon += prev!!
                val hash = Chain.sha256Hex(canon)
                if (p[0] == "E") {
                    if (prev != null && canon.substringAfterLast('|') != prev) return false
                    prev = hash; headHash = hash; headSig = p[2]
                } else {
                    val v = Signature.getInstance("SHA256withECDSA"); v.initVerify(kp.public); v.update(hash.toByteArray(Charsets.UTF_8))
                    extraOk = extraOk && v.verify(Base64.getDecoder().decode(p[2]))
                }
            }
            val v = Signature.getInstance("SHA256withECDSA"); v.initVerify(kp.public); v.update(headHash!!.toByteArray(Charsets.UTF_8))
            return extraOk && v.verify(Base64.getDecoder().decode(headSig))
        }
        assertTrue(verify(text))
        val lines = text.trimEnd('\n').split('\n')
        assertEquals(slice.main.size + slice.extra.size, lines.size - 1)
        assertEquals(slice.main.size - 1, lines.count { it.startsWith("E\t") && it.endsWith("\t-") })
        // changing one odometer digit in an early line breaks the head signature
        val i = lines.indexOfFirst { it.startsWith("E\t") && it.split('\t')[1].split('|')[3] == "TRIP" }
        val f = lines[i].split('\t')[1].split('|').toMutableList(); f[8] = "99999.000"
        val tampered = lines.toMutableList().also { it[i] = "E\t" + f.joinToString("|") + "\t-" }.joinToString("\n")
        assertTrue(!verify(tampered))
        // deleting a middle line breaks the links
        val dropped = lines.toMutableList().also { it.removeAt(10) }.joinToString("\n")
        assertTrue(!verify(dropped))
        // link frames: every frame opens the phone page and still reassembles
        val links = ExportPack.frames(gz, "cd34", ExportPack.LINK_PREFIX)
        assertTrue(links.all { it.startsWith("https://tripsign.beyondspark.kr/#TSQ1:cd34:") })
        assertEquals(text, ExportPack.gunzip(ExportPack.join(links.reversed())!!))
        // TSQ2 (fountain, car app 0.5~): starting mid-stream with every other frame missed still rebuilds it
        val fountain = Fountain.Encoder(gz, "cd35", ExportPack.LINK_PREFIX)
        val fountainJs = (0 until fountain.k + 12).map { fountain.k + 777 + it * 2 }
        val dec = Fountain.Decoder()
        for (j in fountainJs) { dec.add(fountain.frame(j)); if (dec.result() != null) break }
        assertEquals(text, ExportPack.gunzip(dec.result()!!))
        assertTrue(fountain.frame(0).startsWith("https://tripsign.beyondspark.kr/#TSQ2:cd35:0:"))

        // size report + fixtures for the phone page test (tools/verify_test)
        val dir = System.getenv("TS_FIXTURE_DIR")
        if (dir != null) {
            File(dir).mkdirs()
            File(dir, "payload.txt").writeText(text)
            File(dir, "frames.txt").writeText(links.joinToString("\n"))
            File(dir, "stats.txt").writeText("entries=${lines.size - 1} text=${text.length} gzip=${gz.size} frames=${frames.size} fp=${ExportPack.fingerprint(pub)}\n")
            links.forEachIndexed { i, f -> png(f, File(dir, "f$i.png")) }
            File(dir, "frames2.txt").writeText(fountainJs.joinToString("\n") { fountain.frame(it) })
            fountainJs.forEachIndexed { n, j -> png(fountain.frame(j), File(dir, "q$n.png")) }
        }
    }

    private fun png(text: String, file: File) {
        val m = QrCodec.matrix(text)
        val scale = 6
        val q = 4
        val size = (m.size + q * 2) * scale
        val img = java.awt.image.BufferedImage(size, size, java.awt.image.BufferedImage.TYPE_INT_RGB)
        for (y in 0 until size) for (x in 0 until size) {
            val my = y / scale - q
            val mx = x / scale - q
            val dark = my in m.indices && mx in m.indices && m[my][mx]
            img.setRGB(x, y, if (dark) 0x000000 else 0xFFFFFF)
        }
        javax.imageio.ImageIO.write(img, "png", file)
    }

    /** Phone page sample pack ("예시 데이터로 보기"): DemoData October 2026, re-signed with a real EC key. */
    @Test
    fun demoPackFixture() {
        val dir = System.getenv("TS_FIXTURE_DIR") ?: return
        val kp = keys()
        val signer = EcSigner(kp)
        val demo = DemoData.build(zone)
        val out = mutableListOf<SealedEntry>()
        for (s in demo) out += Chain.seal(out.lastOrNull(), s.entry, s.createdAtMs, signer)
        val slice = ExportPack.select(out, YearMonth.of(2026, 10), zone)
        val pub = Base64.getEncoder().encodeToString(kp.public.encoded)
        val text = ExportPack.payloadText(linkedMapOf("from" to "2026-10", "to" to "2026-10", "fy" to "1", "tzo" to "540", "pub" to pub, "made" to "1793430000000"), slice)
        File(dir, "demo_payload.txt").writeText(text)
        val gz = ExportPack.gzip(text)
        val sb = StringBuilder("month entries=${slice.main.size + slice.extra.size} text=${text.length} gzip=${gz.size} frames=${ExportPack.frames(gz, "0000", ExportPack.LINK_PREFIX).size} fp=${ExportPack.fingerprint(pub)}\n")
        // size by range: fiscal year start (Jan 2026) .. each month
        for (m in 1..10) {
            val sl = ExportPack.select(out, YearMonth.of(2026, 1), YearMonth.of(2026, m), zone)
            val t = ExportPack.payloadText(linkedMapOf("from" to "2026-01", "to" to "2026-%02d".format(m), "fy" to "1", "tzo" to "540", "pub" to pub, "made" to "1793430000000"), sl)
            val g = ExportPack.gzip(t)
            sb.append("2026-01..2026-%02d entries=%d gzip=%d frames=%d\n".format(m, sl.main.size + sl.extra.size, g.size, ExportPack.frames(g, "0000", ExportPack.LINK_PREFIX).size))
            if (m == 10) File(dir, "demo_year_payload.txt").writeText(t)
        }
        File(dir, "demo_stats.txt").writeText(sb.toString())
    }

    @Test
    fun fingerprintIsStableAndShort() {
        val fp = ExportPack.fingerprint(Base64.getEncoder().encodeToString(ByteArray(91) { it.toByte() }))
        assertTrue(fp.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}")))
    }
}
