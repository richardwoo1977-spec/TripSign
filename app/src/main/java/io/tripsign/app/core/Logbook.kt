// ver 1.2.0 | 2026-11-07 13:25
package io.tripsign.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.roundToLong

/** Form ④ 사용자. */
data class Driver(val dept: String, val name: String) {
    fun encode(): String = Logbook.clean(dept, Logbook.DEPT_MAX) + Logbook.SEP + Logbook.clean(name, Logbook.NAME_MAX)

    companion object {
        fun decode(note: String): Driver? {
            val i = note.indexOf(Logbook.SEP)
            if (i < 0) return null
            return Driver(note.substring(0, i), note.substring(i + 1))
        }
    }
}

/** Form ① 차종, ② 자동차등록번호. */
data class Vehicle(val model: String, val plate: String) {
    fun encode(): String = Logbook.clean(model, Logbook.MODEL_MAX) + Logbook.SEP + Logbook.clean(plate, Logbook.PLATE_MAX)

    companion object {
        fun decode(note: String): Vehicle? {
            val i = note.indexOf(Logbook.SEP)
            if (i < 0) return null
            return Vehicle(note.substring(0, i), note.substring(i + 1))
        }
    }
}

/**
 * One line of the 국세청 업무용승용차 운행기록부 (③~⑩).
 * Integer rule (meter style, decided 11/4): ⑤⑥ are the odometer readings floored to whole km, ⑦ = ⑥ − ⑤,
 * so every row and every total is a sum of what is printed. No decimals are shown or exported;
 * the measured value stays in the signed ledger only.
 */
data class LogRow(
    val id: String,
    val kind: ViewKind,
    val startMs: Long,
    val endMs: Long,
    val driver: Driver?,
    val odoBefore: Long?,      // ⑤ (null when the odometer was not available)
    val odoAfter: Long?,       // ⑥
    val km: Long,              // ⑦
    val commuteKm: Long?,      // ⑧ (null = not commute)
    val businessKm: Long?,     // ⑨ (null = not business)
    val purpose: Purpose?,
    val measuredKm: Double,
    val purposeChanges: Int,
    val classifiedLate: Boolean,
    /** 미인증 운행: the driver warning was on screen and the car left with nobody logged in (never business km). */
    val unverified: Boolean = false,
    /** The current purpose was set by the administrator after the driver's window closed. */
    val adminEdited: Boolean = false,
    /** The driver skipped the purpose at arrival: it stays 미분류 until the administrator sets it. */
    val purposeSkipped: Boolean = false,
    /** The current user was confirmed by a server-signed phone check in front of the car. */
    val authConfirmed: Boolean = false,
    /** When the trip's first purpose was chosen (0 = never), for the driver's window. */
    val firstPurposeAtMs: Long = 0L,
    /**
     * 0.5.2a (안 B): a 미인증 운행 whose user was confirmed by a phone check and whose purpose the administrator set
     * AFTER that check. Only then its km counts as 출퇴근/업무. The 미인증 mark stays either way.
     */
    val settledLater: Boolean = false,
    /** 0.5.2g: when someone last pressed "내가 아니다" on this 미인증 운행 at a car start (0 = never). */
    val deniedAtMs: Long = 0L,
    /** 0.5.2g: how many times "내가 아니다" was pressed for this trip. */
    val denyCount: Int = 0
) {
    val unclassified: Boolean get() = kind == ViewKind.TRIP && purpose == null && !unverified
    /** Whether the purpose counts in ⑧⑨ and the totals. */
    val counts: Boolean get() = !unverified || settledLater
    /** 사후 확인을 마쳐 관리자가 구분을 정할 수 있는 미인증 운행. */
    val adminMayClassifyUnverified: Boolean get() = unverified && authConfirmed
    val noOdometer: Boolean get() = odoBefore == null || odoAfter == null
}

data class LogTotals(
    val km: Long,            // ⑦ sum, and ⑪ for a fiscal year
    val commuteKm: Long,     // ⑧
    val businessKm: Long,    // ⑨
    val personalKm: Long,
    val unclassifiedKm: Long,
    val gapKm: Long,
    val unclassifiedCount: Int,
    val gapCount: Int,
    val rows: Int,
    val unverifiedKm: Long = 0L,
    val unverifiedCount: Int = 0
) {
    /** ⑫ 업무용 사용거리 = ⑧ + ⑨ */
    val workKm: Long get() = commuteKm + businessKm

    /** ⑬ 업무사용비율 (⑫/⑪) in percent, one decimal, half up. Null when nothing was driven. */
    val ratioPct: Double? get() = if (km <= 0L) null else Logbook.round1(workKm * 100.0 / km)
}

data class FiscalYear(val startYear: Int, val startMonth: Int) {
    val first: YearMonth get() = YearMonth.of(startYear, startMonth)
    val last: YearMonth get() = first.plusMonths(11)

    fun contains(m: YearMonth): Boolean = !m.isBefore(first) && !m.isAfter(last)

    fun fromMs(zone: ZoneId): Long = first.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    fun toMs(zone: ZoneId): Long = last.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    companion object {
        /** The fiscal year (named by its start year) that holds [m] when years start in [startMonth]. */
        fun of(m: YearMonth, startMonth: Int): FiscalYear {
            val sm = startMonth.coerceIn(1, 12)
            val y = if (m.monthValue >= sm) m.year else m.year - 1
            return FiscalYear(y, sm)
        }
    }
}

object Logbook {
    const val SEP = '\u001F'
    const val NAME_MAX = 5
    const val DEPT_MAX = 8
    const val MODEL_MAX = 20
    const val PLATE_MAX = 12

    /** Classified more than 24 h after arrival (shown as a fact on the phone page, never as a grade). */
    const val LATE_MS = 24 * 3_600_000L

    /** TRIP.note of a 미인증 운행. */
    const val NOTE_UNVERIFIED = "unverified"
    /** PURPOSE.note of a skipped purpose (purpose = null). */
    const val NOTE_SKIP = "skip"
    /** PURPOSE.note prefix of an administrator's change: "admin" SEP 부서 SEP 성명. */
    const val NOTE_ADMIN = "admin"
    /** DENY.note: "내가 아니다" pressed on the 미인증 운행 card at the next car start. */
    const val NOTE_NOT_ME = "notme"

    /** The driver may choose the purpose for this long after arrival (then only the administrator). */
    const val DRIVER_WINDOW_MS = 10 * 60_000L

    fun adminNote(admin: Driver): String = NOTE_ADMIN + SEP + admin.encode()

    fun isAdminNote(note: String): Boolean = note.startsWith(NOTE_ADMIN + SEP)

    /** The administrator named in an admin PURPOSE note. */
    fun adminOf(note: String): Driver? = if (isAdminNote(note)) Driver.decode(note.substring(NOTE_ADMIN.length + 1)) else null

    /**
     * The driver's own purpose choice is open only for the newest trip, within [DRIVER_WINDOW_MS] of arrival, when it
     * was not skipped and no newer drive has started. Everything else needs the administrator.
     */
    fun driverMayClassify(row: LogRow, newestTripId: String?, nowMs: Long, parkedIdle: Boolean): Boolean =
        parkedIdle && row.kind == ViewKind.TRIP && row.id == newestTripId && !row.purposeSkipped && !row.unverified &&
            !row.adminEdited && nowMs - row.endMs in 0..DRIVER_WINDOW_MS

    /**
     * 0.5.2g: the 미인증 운행 to put in front of whoever starts the car. Only the newest record, only when it is a
     * 미인증 운행 nobody has confirmed yet, only when it ended between the previous car start and the latest one
     * (so the card is shown at the first start after it, once), and not after "내가 아니다" was pressed at this start.
     * A newer drive (or 미기록 구간) makes it the logbook's business only.
     */
    fun pendingUnverified(rows: List<LogRow>, prevIgnitionMs: Long, ignitionMs: Long): LogRow? {
        val r = rows.lastOrNull() ?: return null
        if (r.kind != ViewKind.TRIP || !r.unverified || r.driver != null || r.authConfirmed) return null
        if (ignitionMs <= 0L || r.endMs <= prevIgnitionMs || r.endMs > ignitionMs) return null
        if (r.deniedAtMs >= ignitionMs) return null
        return r
    }

    /** Removes characters that would break the ledger line or the export line, then cuts to [max] characters. */
    fun clean(text: String, max: Int): String {
        val sb = StringBuilder()
        for (ch in text.trim()) {
            if (ch == '|' || ch == '\t' || ch == '\n' || ch == '\r' || ch == SEP) continue
            if (ch.isISOControl()) continue
            sb.append(ch)
        }
        val cps = sb.toString().codePoints().toArray()
        return if (cps.size <= max) sb.toString() else String(cps, 0, max)
    }

    fun round1(v: Double): Double = (v * 10.0 + 1e-9).roundToLong() / 10.0

    /** Floor that ignores binary noise: 47525.9999999 reads as 47526. */
    fun meter(km: Double): Long = floor(km + 1e-6).toLong()

    fun latestVehicle(entries: List<SealedEntry>): Vehicle? =
        entries.lastOrNull { it.entry.type == RecordType.VEHICLE }?.let { Vehicle.decode(it.entry.note) }

    fun rows(entries: List<SealedEntry>): List<LogRow> {
        val purposes = HashMap<String, MutableList<SealedEntry>>()
        val drivers = HashMap<String, Driver>()
        val authSeq = HashMap<String, Long>()
        val assignSeq = HashMap<String, Long>()
        val denied = HashMap<String, MutableList<Long>>()
        for (s in entries) {
            when (s.entry.type) {
                RecordType.PURPOSE -> purposes.getOrPut(s.entry.tripId) { mutableListOf() }.add(s)
                RecordType.ASSIGN -> Driver.decode(s.entry.note)?.let { drivers[s.entry.tripId] = it; assignSeq[s.entry.tripId] = s.seq }
                RecordType.AUTH -> authSeq[s.entry.tripId] = s.seq
                RecordType.DENY -> denied.getOrPut(s.entry.tripId) { mutableListOf() }.add(s.entry.startMs)
                else -> Unit
            }
        }
        val out = mutableListOf<LogRow>()
        for (s in entries) {
            val e = s.entry
            if (e.type != RecordType.TRIP && e.type != RecordType.GAP) continue
            val kind = if (e.type == RecordType.TRIP) ViewKind.TRIP else ViewKind.GAP
            val before = e.startOdoKm?.let { meter(it) }
            val after = e.endOdoKm?.let { meter(it) }
            val km = if (before != null && after != null) (after - before).coerceAtLeast(0L) else (e.distanceKm + 1e-9).roundToLong()
            val history = if (kind == ViewKind.TRIP) purposes[e.tripId].orEmpty() else emptyList()
            val last = history.lastOrNull()?.entry
            val purpose = last?.purpose
            val chosen = history.filter { it.entry.purpose != null }
            val unverified = kind == ViewKind.TRIP && e.note == NOTE_UNVERIFIED
            // a phone check confirms the user only when it came right before the ASSIGN that set the current user
            val a = assignSeq[e.tripId]
            val auth = authSeq[e.tripId]
            val authConfirmed = a != null && auth != null && auth == a - 1
            val lastSeq = history.lastOrNull()?.seq
            val settled = unverified && authConfirmed && purpose != null && last != null && isAdminNote(last.note) &&
                lastSeq != null && auth != null && lastSeq > auth
            out += LogRow(
                id = e.tripId,
                kind = kind,
                startMs = e.startMs,
                endMs = e.endMs,
                driver = drivers[e.tripId],
                odoBefore = before,
                odoAfter = after,
                km = km,
                commuteKm = if (purpose == Purpose.COMMUTE && (!unverified || settled)) km else null,
                businessKm = if (purpose == Purpose.BUSINESS && (!unverified || settled)) km else null,
                purpose = purpose,
                measuredKm = e.distanceKm,
                purposeChanges = if (chosen.size > 1) chosen.size - 1 else 0,
                classifiedLate = chosen.firstOrNull()?.let { it.createdAtMs - e.endMs > LATE_MS } ?: false,
                unverified = unverified,
                adminEdited = last != null && isAdminNote(last.note),
                purposeSkipped = history.any { it.entry.note == NOTE_SKIP },
                authConfirmed = authConfirmed,
                firstPurposeAtMs = chosen.firstOrNull()?.createdAtMs ?: 0L,
                settledLater = settled,
                deniedAtMs = denied[e.tripId]?.maxOrNull() ?: 0L,
                denyCount = denied[e.tripId]?.size ?: 0
            )
        }
        return out.sortedBy { it.startMs }
    }

    fun month(rows: List<LogRow>, m: YearMonth, zone: ZoneId): List<LogRow> =
        rows.filter { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) == m }

    fun fiscalYear(rows: List<LogRow>, fy: FiscalYear, zone: ZoneId): List<LogRow> {
        val from = fy.fromMs(zone)
        val to = fy.toMs(zone)
        return rows.filter { it.startMs in from until to }
    }

    fun totals(rows: List<LogRow>): LogTotals {
        var km = 0L
        var commute = 0L
        var business = 0L
        var personal = 0L
        var unc = 0L
        var gap = 0L
        var uncN = 0
        var gapN = 0
        var unv = 0L
        var unvN = 0
        for (r in rows) {
            km += r.km
            when {
                r.kind == ViewKind.GAP -> { gap += r.km; gapN++ }
                r.unverified && !r.settledLater -> { unv += r.km; unvN++ }
                r.purpose == Purpose.COMMUTE -> commute += r.km
                r.purpose == Purpose.BUSINESS -> business += r.km
                r.purpose == Purpose.PERSONAL -> personal += r.km
                else -> { unc += r.km; uncN++ }
            }
        }
        return LogTotals(km, commute, business, personal, unc, gap, uncN, gapN, rows.size, unv, unvN)
    }

    /** Months that have at least one row, newest first. */
    fun months(rows: List<LogRow>, zone: ZoneId): List<YearMonth> =
        rows.map { YearMonth.from(Instant.ofEpochMilli(it.startMs).atZone(zone)) }.distinct().sortedDescending()

    fun dateOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
}
