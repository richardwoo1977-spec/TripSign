// ver 1.3.1 | 2026-11-07 13:25
package io.tripsign.app.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Car -> phone transfer of one month of the ledger, over a loop of QR frames (no network).
 *
 * Payload (UTF-8 text, gzip):
 *   line 1  : "TS1" TAB key=value TAB key=value ...   (from, to, fy, tzo, pub, made, demo)
 *   line 2+ : "E" TAB canonical TAB signature
 *     canonical = Chain.canonical(...) exactly as hashed, except the last field (prevHash) is left
 *     empty when it equals the hash of the line before; the phone recomputes it. Hashes are never sent.
 *     Only the LAST "E" line carries its signature: its hash commits to every earlier line through
 *     prevHash, so one valid signature on the head authenticates the whole run (signatures were ~75%
 *     of the compressed size). Earlier lines carry "-".
 *   "X" lines: entries outside the contiguous run (latest VEHICLE record); own prevHash and signature.
 *
 * Frame (QR text): VERIFY_URL "#" "TSQ1:" sid ":" index ":" total ":" crc32(chunk, 8 hex) ":" chunk
 *   Every frame is a link to the phone page, so the phone's normal camera app opens the page from any
 *   frame. The part after "#" is a URL fragment: browsers never send it to the server.
 *   chunk = part of Base64(gzip(payload)). Frames repeat in a loop until the phone has all of them.
 */
object ExportPack {
    const val FORMAT = "TS1"
    const val FRAME = "TSQ1"
    const val CHUNK_CHARS = 600
    /** Phone verification page (hosted by the project owner). Every frame starts with it. */
    const val VERIFY_URL = "https://tripsign.beyondspark.kr/"
    const val LINK_PREFIX = VERIFY_URL + "#"

    data class Slice(val main: List<SealedEntry>, val extra: List<SealedEntry>)

    fun select(entries: List<SealedEntry>, m: YearMonth, zone: ZoneId): Slice = select(entries, m, m, zone)

    /**
     * The contiguous run of entries that covers every TRIP/GAP that started in [from]..[to] and every
     * PURPOSE/ASSIGN/AUTH/DENY that refers to them, plus the latest VEHICLE record if it lies outside.
     * The run starts right after the last TRIP/GAP before the range, so it also carries the records in
     * between (a vehicle change, late purposes of the month before): two exports of neighbouring months
     * always touch or overlap, and a hole between them on the phone really means missing records.
     */
    fun select(entries: List<SealedEntry>, from: YearMonth, to: YearMonth, zone: ZoneId): Slice {
        val ids = HashSet<String>()
        var minSeq = Long.MAX_VALUE
        var maxSeq = Long.MIN_VALUE
        for (s in entries) {
            val e = s.entry
            if (e.type != RecordType.TRIP && e.type != RecordType.GAP) continue
            val ym = YearMonth.from(Instant.ofEpochMilli(e.startMs).atZone(zone))
            if (ym.isBefore(from) || ym.isAfter(to)) continue
            ids += e.tripId
            if (s.seq < minSeq) minSeq = s.seq
            if (s.seq > maxSeq) maxSeq = s.seq
        }
        if (ids.isEmpty()) return Slice(emptyList(), latestVehicle(entries, emptyList()))
        val prevTrip = entries.lastOrNull { it.seq < minSeq && (it.entry.type == RecordType.TRIP || it.entry.type == RecordType.GAP) }
        minSeq = prevTrip?.let { it.seq + 1 } ?: (entries.firstOrNull()?.seq ?: minSeq)
        for (s in entries) {
            val t = s.entry.type
            if ((t == RecordType.PURPOSE || t == RecordType.ASSIGN || t == RecordType.AUTH || t == RecordType.DENY) && s.entry.tripId in ids && s.seq > maxSeq) maxSeq = s.seq
        }
        val main = entries.filter { it.seq in minSeq..maxSeq }
        return Slice(main, latestVehicle(entries, main))
    }

    private fun latestVehicle(entries: List<SealedEntry>, main: List<SealedEntry>): List<SealedEntry> {
        val v = entries.lastOrNull { it.entry.type == RecordType.VEHICLE } ?: return emptyList()
        return if (main.any { it.seq == v.seq }) emptyList() else listOf(v)
    }

    fun canonicalOf(s: SealedEntry): String = Chain.canonical(s.seq, s.createdAtMs, s.entry, s.prevHash)

    fun payloadText(header: Map<String, String>, slice: Slice): String {
        val sb = StringBuilder()
        sb.append(FORMAT)
        for ((k, v) in header) sb.append('\t').append(k).append('=').append(v.replace("\t", " ").replace("\n", " "))
        sb.append('\n')
        var prevHash: String? = null
        val last = slice.main.size - 1
        slice.main.forEachIndexed { i, s ->
            val c = canonicalOf(s)
            val line = if (prevHash != null && s.prevHash == prevHash) c.substring(0, c.length - s.prevHash.length) else c
            sb.append("E\t").append(line).append('\t').append(if (i == last) s.signature else "-").append('\n')
            prevHash = s.hash
        }
        for (s in slice.extra) sb.append("X\t").append(canonicalOf(s)).append('\t').append(s.signature).append('\n')
        return sb.toString()
    }

    fun gzip(text: String): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return bos.toByteArray()
    }

    fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { String(it.readBytes(), Charsets.UTF_8) }

    fun crcHex(text: String): String {
        val c = CRC32()
        c.update(text.toByteArray(Charsets.US_ASCII))
        return String.format("%08x", c.value)
    }

    fun frames(payload: ByteArray, sid: String, prefix: String = "", chunkChars: Int = CHUNK_CHARS): List<String> {
        val b64 = Base64.getEncoder().encodeToString(payload)
        val chunks = b64.chunked(chunkChars)
        return chunks.mapIndexed { i, ch -> "$prefix$FRAME:$sid:$i:${chunks.size}:${crcHex(ch)}:$ch" }
    }

    /** Reassembles frames (any order, duplicates allowed). Returns null until every frame is present and intact. */
    fun join(frames: Collection<String>): ByteArray? {
        var total = -1
        val parts = HashMap<Int, String>()
        var sid: String? = null
        for (raw in frames) {
            val f = raw.substring(raw.indexOf('#') + 1)   // frames may be links: drop "https://…#"
            val p = f.split(':')
            if (p.size != 6 || p[0] != FRAME) continue
            if (sid == null) sid = p[1] else if (sid != p[1]) continue
            val n = p[3].toIntOrNull() ?: continue
            val i = p[2].toIntOrNull() ?: continue
            if (crcHex(p[5]) != p[4]) continue
            total = n
            parts[i] = p[5]
        }
        if (total <= 0 || parts.size < total) return null
        val sb = StringBuilder()
        for (i in 0 until total) sb.append(parts[i] ?: return null)
        return Base64.getDecoder().decode(sb.toString())
    }

    /** Short key fingerprint shown on the car screen and on the phone page, e.g. "3FA2-91C0". */
    fun fingerprint(spkiBase64: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(spkiBase64))
        val hex = StringBuilder()
        for (i in 0 until 4) hex.append(String.format("%02X", d[i].toInt() and 0xFF))
        return hex.substring(0, 4) + "-" + hex.substring(4, 8)
    }

    /** Random 4-hex session id so frames of two different exports never mix on the phone. */
    fun newSid(): String = String.format("%04x", (Math.random() * 0x10000).toInt())
}
