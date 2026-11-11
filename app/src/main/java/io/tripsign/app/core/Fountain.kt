// ver 1.0.0 | 2026-11-04 20:20
package io.tripsign.app.core

import java.util.Base64

/**
 * Fountain code for the QR stream (car -> phone), so the phone does not need any particular frame.
 *
 * The payload (gzip bytes) is cut into K blocks of BLOCK_BYTES (the last one zero-padded).
 * Frame j carries ONE block-sized piece:
 *   j <  K : block j itself
 *   j >= K : XOR of a pseudo-random subset of the K blocks, the subset fixed by (sid, j)
 * Any K independent pieces rebuild the payload (Gaussian elimination over GF(2)); with random subsets
 * K + 2 frames are almost always enough. So a missed frame never means "wait one more loop", and it
 * does not matter when the phone starts looking.
 *
 * Frame text: prefix "TSQ2:" sid ":" j ":" K ":" length ":" crc32(chunk, 8 hex) ":" chunk
 *   chunk = Base64 of the piece (BLOCK_BYTES = 450 -> exactly 600 chars, same QR size as TSQ1).
 * The subset generator (mulberry32) is mirrored bit for bit in verify/index.src.html.
 */
object Fountain {
    const val FRAME = "TSQ2"
    const val BLOCK_BYTES = 450

    fun blockCount(length: Int, blockBytes: Int = BLOCK_BYTES): Int = maxOf(1, (length + blockBytes - 1) / blockBytes)

    /** 32-bit words of the subset bitmask for frame j (bit i set = block i is in the XOR). */
    fun coefficients(sid: Int, j: Int, k: Int): IntArray {
        val words = IntArray((k + 31) / 32)
        if (j < k) {
            words[j ushr 5] = 1 shl (j and 31)
            return words
        }
        var a = (j * 0x9E3779B1.toInt()) xor (sid * 0x85EBCA6B.toInt())
        for (w in words.indices) {
            a += 0x6D2B79F5
            var t = (a xor (a ushr 15)) * (1 or a)
            t = (t + ((t xor (t ushr 7)) * (61 or t))) xor t
            words[w] = t xor (t ushr 14)
        }
        val rest = k and 31
        if (rest != 0) words[words.size - 1] = words[words.size - 1] and ((1 shl rest) - 1)
        if (words.all { it == 0 }) words[(j % k) ushr 5] = 1 shl ((j % k) and 31)
        return words
    }

    class Encoder(payload: ByteArray, val sid: String, private val prefix: String = "", private val blockBytes: Int = BLOCK_BYTES) {
        val length: Int = payload.size
        val k: Int = blockCount(length, blockBytes)
        private val sidInt: Int = sid.toInt(16)
        private val blocks: Array<ByteArray> = Array(k) { i ->
            val b = ByteArray(blockBytes)
            val from = i * blockBytes
            System.arraycopy(payload, from, b, 0, minOf(blockBytes, length - from).coerceAtLeast(0))
            b
        }

        fun piece(j: Int): ByteArray {
            val c = coefficients(sidInt, j, k)
            val out = ByteArray(blockBytes)
            for (i in 0 until k) {
                if ((c[i ushr 5] ushr (i and 31)) and 1 == 0) continue
                val b = blocks[i]
                for (x in 0 until blockBytes) out[x] = (out[x].toInt() xor b[x].toInt()).toByte()
            }
            return out
        }

        fun frame(j: Int): String {
            val chunk = Base64.getEncoder().encodeToString(piece(j))
            return "$prefix$FRAME:$sid:$j:$k:$length:${ExportPack.crcHex(chunk)}:$chunk"
        }
    }

    /**
     * Incremental Gauss-Jordan decoder. add() any frames in any order (duplicates and frames of
     * another sid are ignored); result() is non-null once rank == K.
     */
    class Decoder {
        var sid: String? = null; private set
        var k = 0; private set
        private var length = 0
        private var blockBytes = 0
        private val rowCoef = ArrayList<IntArray>()
        private val rowData = ArrayList<ByteArray>()
        private var pivotRow = IntArray(0)
        val rank: Int get() = rowCoef.size

        /** Returns true when the frame added new information. */
        fun add(raw: String): Boolean {
            val f = raw.substring(raw.indexOf('#') + 1)
            val p = f.split(':')
            if (p.size != 7 || p[0] != FRAME) return false
            val j = p[2].toIntOrNull() ?: return false
            val kk = p[3].toIntOrNull() ?: return false
            val len = p[4].toIntOrNull() ?: return false
            if (ExportPack.crcHex(p[6]) != p[5]) return false
            val data = try { Base64.getDecoder().decode(p[6]) } catch (e: IllegalArgumentException) { return false }
            if (sid == null) {
                if (kk <= 0 || len < 0 || data.isEmpty() || blockCount(len, data.size) != kk) return false
                sid = p[1]; k = kk; length = len; blockBytes = data.size; pivotRow = IntArray(kk) { -1 }
            } else if (p[1] != sid || kk != k || len != length || data.size != blockBytes) return false
            if (rank == k) return false
            val c = coefficients(p[1].toInt(16), j, k)
            // reduce by existing rows (each existing row is the only one holding its pivot bit)
            for (b in 0 until k) {
                val r = pivotRow[b]
                if (r >= 0 && bit(c, b)) { xorInto(c, rowCoef[r]); xorInto(data, rowData[r]) }
            }
            val piv = (0 until k).firstOrNull { bit(c, it) } ?: return false
            // clear the new pivot bit from every other row, keeping the matrix fully reduced
            for (r in rowCoef.indices) if (bit(rowCoef[r], piv)) { xorInto(rowCoef[r], c); xorInto(rowData[r], data) }
            pivotRow[piv] = rowCoef.size
            rowCoef += c; rowData += data
            return true
        }

        fun result(): ByteArray? {
            if (k == 0 || rank < k) return null
            val out = ByteArray(k * blockBytes)
            for (b in 0 until k) System.arraycopy(rowData[pivotRow[b]], 0, out, b * blockBytes, blockBytes)
            return out.copyOf(length)
        }

        private fun bit(c: IntArray, i: Int) = (c[i ushr 5] ushr (i and 31)) and 1 == 1
        private fun xorInto(a: IntArray, b: IntArray) { for (i in a.indices) a[i] = a[i] xor b[i] }
        private fun xorInto(a: ByteArray, b: ByteArray) { for (i in a.indices) a[i] = (a[i].toInt() xor b[i].toInt()).toByte() }
    }
}
