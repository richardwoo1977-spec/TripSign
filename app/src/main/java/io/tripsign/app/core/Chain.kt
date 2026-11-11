// ver 1.0.0 | 2026-11-04 14:00
package io.tripsign.app.core

import java.security.MessageDigest
import java.util.Locale

interface Signer {
    /** Returns a Base64 signature of [data]. */
    fun sign(data: ByteArray): String
}

interface Verifier {
    fun verify(data: ByteArray, signature: String): Boolean
}

data class ChainCheck(
    val ok: Boolean,
    val count: Int,
    val headHash: String,
    val firstBadSeq: Long? = null,
    val reason: String = ""
)

object Chain {
    const val GENESIS: String = "0000000000000000000000000000000000000000000000000000000000000000"

    private fun num(v: Double?): String = if (v == null) "-" else String.format(Locale.ROOT, "%.3f", v)

    /** Fixed field order; any change to a stored value changes the hash. */
    fun canonical(seq: Long, createdAtMs: Long, e: Entry, prevHash: String): String =
        listOf(
            "v1",
            seq.toString(),
            createdAtMs.toString(),
            e.type.name,
            e.tripId,
            e.startMs.toString(),
            e.endMs.toString(),
            num(e.startOdoKm),
            num(e.endOdoKm),
            num(e.speedKm),
            num(e.distanceKm),
            e.distanceSource.name,
            num(e.maxSpeedKmh),
            e.purpose?.name ?: "-",
            e.note.replace("|", "/"),
            prevHash
        ).joinToString("|")

    fun sha256Hex(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    fun seal(prev: SealedEntry?, entry: Entry, createdAtMs: Long, signer: Signer): SealedEntry {
        val seq = (prev?.seq ?: 0L) + 1L
        val prevHash = prev?.hash ?: GENESIS
        val hash = sha256Hex(canonical(seq, createdAtMs, entry, prevHash))
        val signature = signer.sign(hash.toByteArray(Charsets.UTF_8))
        return SealedEntry(seq, createdAtMs, entry, prevHash, hash, signature)
    }

    fun verify(entries: List<SealedEntry>, verifier: Verifier): ChainCheck {
        var prevHash = GENESIS
        var expectedSeq = 1L
        for (s in entries) {
            if (s.seq != expectedSeq) {
                return ChainCheck(false, entries.size, prevHash, s.seq, "순번 불연속")
            }
            if (s.prevHash != prevHash) {
                return ChainCheck(false, entries.size, prevHash, s.seq, "이전 해시 불일치")
            }
            val hash = sha256Hex(canonical(s.seq, s.createdAtMs, s.entry, s.prevHash))
            if (hash != s.hash) {
                return ChainCheck(false, entries.size, prevHash, s.seq, "내용 변경 감지")
            }
            if (!verifier.verify(s.hash.toByteArray(Charsets.UTF_8), s.signature)) {
                return ChainCheck(false, entries.size, prevHash, s.seq, "서명 불일치")
            }
            prevHash = s.hash
            expectedSeq++
        }
        return ChainCheck(true, entries.size, prevHash)
    }
}
