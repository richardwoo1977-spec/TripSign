// ver 1.0.0 | 2026-11-04 20:25
package io.tripsign.app.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.random.Random

class FountainTest {
    private fun payload(n: Int, seed: Int) = Random(seed).nextBytes(n)

    /** Feeds frames j = start, start+1, ... where each is "seen" with probability p; returns frames shown. */
    private fun receive(enc: Fountain.Encoder, p: Double, start: Int, rnd: Random, limit: Int = 200_000): Pair<Fountain.Decoder, Int> {
        val dec = Fountain.Decoder()
        var shown = 0
        var j = start
        while (dec.result() == null && shown < limit) {
            if (rnd.nextDouble() < p) dec.add(enc.frame(j))
            j++; shown++
        }
        return dec to shown
    }

    @Test fun rebuildsAnySizeFromAnyStart() {
        val rnd = Random(11)
        for (n in listOf(1, 449, 450, 451, 900, 6_300, 57_000, 60_001)) {
            val data = payload(n, n)
            val enc = Fountain.Encoder(data, "1a2b", ExportPack.LINK_PREFIX)
            for (start in listOf(0, 1, enc.k / 2, enc.k - 1, enc.k, 3 * enc.k + 7, 100_000)) {
                for (p in listOf(1.0, 0.6, 0.3)) {
                    val (dec, _) = receive(enc, p, start, rnd)
                    assertArrayEquals("n=$n start=$start p=$p", data, dec.result())
                }
            }
        }
    }

    @Test fun needsOnlyAboutKFrames() {
        // repair-only frames (start past the systematic part): count frames actually received
        val rnd = Random(5)
        val data = payload(57_000, 3)
        val enc = Fountain.Encoder(data, "beef")
        val extras = ArrayList<Int>()
        repeat(60) { t ->
            val dec = Fountain.Decoder()
            var got = 0
            var j = enc.k + rnd.nextInt(1_000_000)
            while (dec.result() == null) { dec.add(enc.frame(j)); got++; j += 1 + rnd.nextInt(3) }
            extras += got - enc.k
            assertArrayEquals(data, dec.result())
        }
        extras.sort()
        println("k=${enc.k} extra frames beyond k: median=${extras[extras.size / 2]} max=${extras.last()}")
        assertTrue("median overhead ${extras[extras.size / 2]}", extras[extras.size / 2] <= 3)
        assertTrue("max overhead ${extras.last()}", extras.last() <= 12)
    }

    @Test fun ignoresBrokenForeignAndDuplicateFrames() {
        val data = payload(5_000, 9)
        val enc = Fountain.Encoder(data, "00ff", ExportPack.LINK_PREFIX)
        val other = Fountain.Encoder(payload(5_000, 10), "0100", ExportPack.LINK_PREFIX)
        val dec = Fountain.Decoder()
        assertTrue(dec.add(enc.frame(3)))
        assertFalse("duplicate", dec.add(enc.frame(3)))
        assertFalse("other export", dec.add(other.frame(4)))
        val f = enc.frame(5)
        val bad = f.substring(0, f.length - 3) + (if (f.last() == 'A') "BBB" else "AAA")
        assertFalse("crc", dec.add(bad))
        assertFalse("old format", dec.add("https://tripsign.beyondspark.kr/#TSQ1:00ff:0:3:00000000:AAAA"))
        assertEquals(1, dec.rank)
        var j = 6
        while (dec.result() == null) dec.add(enc.frame(j++))
        assertArrayEquals(data, dec.result())
        assertNull(Fountain.Decoder().result())
    }

    @Test fun framesFitTheSameQrAsBefore() {
        val enc = Fountain.Encoder(payload(57_000, 1), "abcd", ExportPack.LINK_PREFIX)
        val maxLen = (0 until 400).maxOf { enc.frame(enc.k + it * 997).length }
        println("TSQ2 frame length max=$maxLen k=${enc.k}")
        assertTrue(maxLen <= 31 + 50 + 600)
        // every frame still renders as a QR code
        QrCodec.matrix(enc.frame(enc.k + 12345))
    }

    /** Fixture for the phone page test: same coefficient bits and frames on both sides. */
    @Test fun writesPhoneFixture() {
        val dir = System.getenv("TS_FIXTURE_DIR") ?: return
        val data = payload(20_000, 77)
        val enc = Fountain.Encoder(data, "c0de", ExportPack.LINK_PREFIX)
        val js = StringBuilder()
        for (j in listOf(0, enc.k - 1, enc.k, enc.k + 1, 999, 123_456, 2_000_000_000)) {
            js.append(j).append(' ').append(Fountain.coefficients(0xc0de, j, enc.k).joinToString(",")).append('\n')
        }
        File(dir, "fountain_coef.txt").writeText(js.toString())
        File(dir, "fountain_payload.b64").writeText(java.util.Base64.getEncoder().encodeToString(data))
        File(dir, "fountain_frames.txt").writeText((enc.k + 500 until enc.k + 500 + enc.k + 30).joinToString("\n") { enc.frame(it) })
        // a real fiscal-year sample pack (written by ExportPackTest.demoPackFixture on an earlier run)
        val year = File(dir, "demo_year_payload.txt")
        if (year.exists()) {
            val y = Fountain.Encoder(ExportPack.gzip(year.readText()), "7e57", ExportPack.LINK_PREFIX)
            val r = Random(42)
            val shown = ArrayList<String>()
            var j = 3 * y.k + 11
            while (shown.size < y.k + 40) { if (r.nextDouble() < 0.4) shown += y.frame(j); j++ }   // 40% read, from mid-stream
            File(dir, "fountain_year_frames.txt").writeText(shown.joinToString("\n"))
        }
    }
}
