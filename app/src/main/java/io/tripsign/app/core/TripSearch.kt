// ver 1.0.0 | 2026-11-04 15:10
package io.tripsign.app.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/**
 * Trip search by date, time and distance (운행 기록 검색). Space-separated terms are AND-ed.
 *
 * Date      10/30 · 2026-10-30 · 2026.10.30 · 10.30 (two-digit day) · 10월 30일 · 30일 · 10월
 *           (month/day without a year = the most recent such day on or before today)
 * Time      18:20 (trips running at that minute) · 18시 · 오후 6시 · 18시 20분 · 오전 9:30
 * Distance  21.9 · 21.9km (rounded as shown) · 20km (19.5 up to 20.5) · 10~30km · 10-30
 *           · 30km 이상 · 30km 이하 · >=30 · <=30
 * A bare number is a distance. Anything not understood is returned in [SearchQuery.unknown].
 */
sealed class SearchTerm {
    data class OnDate(val date: LocalDate) : SearchTerm()
    data class InMonth(val month: YearMonth) : SearchTerm()
    data class AtTime(val minuteOfDay: Int) : SearchTerm()
    data class InHour(val hour: Int) : SearchTerm()
    /** [minKm, maxKm) unless an end is inclusive. */
    data class Distance(val minKm: Double, val maxKm: Double, val maxInclusive: Boolean = false) : SearchTerm()
}

data class SearchQuery(val terms: List<SearchTerm>, val unknown: List<String>) {
    val isEmpty: Boolean get() = terms.isEmpty() && unknown.isEmpty()
}

object TripSearch {

    private val ymd = Regex("""^(\d{4})[-./](\d{1,2})[-./](\d{1,2})$""")
    private val mdSlash = Regex("""^(\d{1,2})/(\d{1,2})$""")
    private val mdDot = Regex("""^(\d{1,2})\.(\d{2})$""")
    private val mdKo = Regex("""^(\d{1,2})월(\d{1,2})일$""")
    private val dKo = Regex("""^(\d{1,2})일$""")
    private val mKo = Regex("""^(\d{1,2})월$""")
    private val hm = Regex("""^(\d{1,2}):(\d{2})$""")
    private val hKo = Regex("""^(\d{1,2})시$""")
    private val hmKo = Regex("""^(\d{1,2})시(\d{1,2})분$""")
    private val range = Regex("""^(\d+(?:\.\d+)?)(?:km)?[~-](\d+(?:\.\d+)?)(?:km)?$""")
    private val dist = Regex("""^(>=|<=|>|<)?(\d+(?:\.\d+)?)(?:km)?$""")

    fun parse(input: String, todayMs: Long, zone: ZoneId): SearchQuery {
        val today = Instant.ofEpochMilli(todayMs).atZone(zone).toLocalDate()
        val text = normalize(input)
        val terms = mutableListOf<SearchTerm>()
        val unknown = mutableListOf<String>()
        for (token in text.split(' ').filter { it.isNotBlank() }) {
            val t = term(token, today)
            if (t == null) unknown += token else terms += t
        }
        return SearchQuery(terms, unknown)
    }

    /** Joins the spaced forms ("10월 30일", "오후 6 시", "30 km 이상", "10 ~ 30") into single tokens. */
    internal fun normalize(input: String): String {
        var s = input.trim().lowercase()
            .replace('～', '~').replace('〜', '~').replace('–', '-').replace(',', ' ')
        s = s.replace(Regex("""(\d)\s*([~-])\s*(\d)"""), "$1$2$3")
        s = s.replace(Regex("""(\d)\s+km"""), "$1km")
        s = s.replace(Regex("""(\d)\s*(월|일|시|분)"""), "$1$2")
        s = s.replace(Regex("""(\d+월)\s+(\d+일)"""), "$1$2")
        s = s.replace(Regex("""(\d+시)\s+(\d+분)"""), "$1$2")
        s = s.replace(Regex("""(\S+?)(km)?\s*이상"""), ">=$1")
        s = s.replace(Regex("""(\S+?)(km)?\s*이하"""), "<=$1")
        // 오전 / 오후 + time
        s = s.replace(Regex("""(오전|오후)\s*(\d{1,2})(:\d{2}|시(?:\d{1,2}분)?)?""")) { m ->
            val pm = m.groupValues[1] == "오후"
            var h = m.groupValues[2].toInt() % 12
            if (pm) h += 12
            val rest = m.groupValues[3]
            when {
                rest.startsWith(":") -> "$h$rest"
                rest.isEmpty() -> "${h}시"
                else -> "$h$rest"
            }
        }
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /** Most recent [month]/[day] on or before [today] (no year given). */
    private fun recentDate(month: Int, day: Int, today: LocalDate): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        for (back in 0..1) {
            val y = today.year - back
            val ym = YearMonth.of(y, month)
            if (day > ym.lengthOfMonth()) continue
            val d = LocalDate.of(y, month, day)
            if (!d.isAfter(today)) return d
        }
        return null
    }

    private fun term(token: String, today: LocalDate): SearchTerm? {
        ymd.matchEntire(token)?.let { m ->
            val (y, mo, d) = m.destructured
            return runCatching { SearchTerm.OnDate(LocalDate.of(y.toInt(), mo.toInt(), d.toInt())) }.getOrNull()
        }
        (mdSlash.matchEntire(token) ?: mdDot.matchEntire(token) ?: mdKo.matchEntire(token))?.let { m ->
            val (mo, d) = m.destructured
            val date = recentDate(mo.toInt(), d.toInt(), today)
            if (date != null) return SearchTerm.OnDate(date)
            if (token.contains('/') || token.contains('월')) return null
            // "21.95"-style decimals that are not a valid date fall through to distance
        }
        dKo.matchEntire(token)?.let { m ->
            val d = m.groupValues[1].toInt()
            val thisMonth = YearMonth.from(today)
            val ym = if (d <= today.dayOfMonth) thisMonth else thisMonth.minusMonths(1)
            return if (d in 1..ym.lengthOfMonth()) SearchTerm.OnDate(ym.atDay(d)) else null
        }
        mKo.matchEntire(token)?.let { m ->
            val mo = m.groupValues[1].toInt()
            if (mo !in 1..12) return null
            val ym = YearMonth.of(today.year, mo).let { if (it.isAfter(YearMonth.from(today))) it.minusYears(1) else it }
            return SearchTerm.InMonth(ym)
        }
        (hm.matchEntire(token) ?: hmKo.matchEntire(token))?.let { m ->
            val (h, mi) = m.destructured
            if (h.toInt() !in 0..23 || mi.toInt() !in 0..59) return null
            return SearchTerm.AtTime(h.toInt() * 60 + mi.toInt())
        }
        hKo.matchEntire(token)?.let { m ->
            val h = m.groupValues[1].toInt()
            return if (h in 0..23) SearchTerm.InHour(h) else null
        }
        range.matchEntire(token)?.let { m ->
            val a = m.groupValues[1].toDouble()
            val b = m.groupValues[2].toDouble()
            return SearchTerm.Distance(minOf(a, b), maxOf(a, b), maxInclusive = true)
        }
        dist.matchEntire(token)?.let { m ->
            val op = m.groupValues[1]
            val raw = m.groupValues[2]
            val v = raw.toDouble()
            return when (op) {
                ">=" -> SearchTerm.Distance(v, Double.MAX_VALUE)
                ">" -> SearchTerm.Distance(Math.nextUp(v), Double.MAX_VALUE)
                "<=" -> SearchTerm.Distance(0.0, v, maxInclusive = true)
                "<" -> SearchTerm.Distance(0.0, v)
                else -> {
                    // match what the list shows: 21.9 = 21.85 up to 21.95, 20 = 19.5 up to 20.5
                    val decimals = raw.substringAfter('.', "").length
                    var half = 0.5
                    repeat(decimals) { half /= 10.0 }
                    SearchTerm.Distance(v - half, v + half)
                }
            }
        }
        return null
    }

    fun matches(v: TripView, q: SearchQuery, zone: ZoneId): Boolean {
        if (q.terms.isEmpty()) return q.unknown.isEmpty()
        val start = Instant.ofEpochMilli(v.startMs).atZone(zone)
        val end = Instant.ofEpochMilli(v.endMs).atZone(zone)
        val s = start.hour * 60 + start.minute
        var e = end.hour * 60 + end.minute
        if (end.toLocalDate().isAfter(start.toLocalDate())) e += 1440
        return q.terms.all { t ->
            when (t) {
                is SearchTerm.OnDate -> start.toLocalDate() == t.date
                is SearchTerm.InMonth -> YearMonth.from(start) == t.month
                is SearchTerm.AtTime -> (t.minuteOfDay in s..e) || (t.minuteOfDay + 1440 in s..e)
                is SearchTerm.InHour -> s < (t.hour + 1) * 60 && e >= t.hour * 60
                is SearchTerm.Distance -> {
                    val d = v.distanceKm
                    d >= t.minKm && (if (t.maxInclusive) d <= t.maxKm + 1e-9 else d < t.maxKm)
                }
            }
        }
    }

    /** Short readable form of a parsed query, for the "검색: ..." line. */
    fun describe(q: SearchQuery, english: Boolean): String = q.terms.joinToString("  ·  ") { t ->
        when (t) {
            is SearchTerm.OnDate -> if (english) "${t.date.monthValue}/${t.date.dayOfMonth}/${t.date.year}" else "${t.date.year}. ${t.date.monthValue}. ${t.date.dayOfMonth}."
            is SearchTerm.InMonth -> if (english) "${t.month.monthValue}/${t.month.year}" else "${t.month.year}년 ${t.month.monthValue}월"
            is SearchTerm.AtTime -> String.format("%02d:%02d", t.minuteOfDay / 60, t.minuteOfDay % 60)
            is SearchTerm.InHour -> if (english) "${t.hour}:00–${t.hour}:59" else "${t.hour}시대"
            is SearchTerm.Distance -> when {
                t.maxKm == Double.MAX_VALUE -> if (english) "≥ ${trim(t.minKm)} km" else "${trim(t.minKm)} km 이상"
                t.minKm == 0.0 && t.maxInclusive -> if (english) "≤ ${trim(t.maxKm)} km" else "${trim(t.maxKm)} km 이하"
                t.minKm == 0.0 -> if (english) "< ${trim(t.maxKm)} km" else "${trim(t.maxKm)} km 미만"
                t.maxInclusive -> "${trim(t.minKm)}–${trim(t.maxKm)} km"
                else -> "≈ ${trim((t.minKm + t.maxKm) / 2)} km"
            }
        }
    }

    private fun trim(v: Double): String {
        val r = Math.round(v * 100.0) / 100.0
        return if (abs(r - Math.round(r)) < 1e-9) Math.round(r).toString() else r.toString()
    }
}
