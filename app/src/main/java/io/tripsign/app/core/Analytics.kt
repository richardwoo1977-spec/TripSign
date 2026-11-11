// ver 1.1.0 | 2026-11-04 12:10
package io.tripsign.app.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class ViewKind { TRIP, GAP }

/** A trip or gap as the user sees it, with its current purpose and purpose history applied. */
data class TripView(
    val id: String,
    val kind: ViewKind,
    val startMs: Long,
    val endMs: Long,
    val distanceKm: Double,
    val source: DistanceSource,
    val startOdoKm: Double?,
    val endOdoKm: Double?,
    val purpose: Purpose?,
    val classifiedAtMs: Long?,
    val purposeChanges: Int
)

enum class Period { DAY, WEEK, MONTH, YEAR }

data class Range(val fromMs: Long, val toMs: Long)

data class PeriodStats(
    val totalKm: Double,
    val businessKm: Double,
    val commuteKm: Double,
    val personalKm: Double,
    val unclassifiedKm: Double,
    val gapKm: Double,
    val trips: Int
) {
    /** 업무 사용 비율 = (업무 + 출퇴근) / 총 주행거리(미기록 구간 포함). 미분류·미기록은 업무로 보지 않는다. */
    val businessRatio: Double get() = if (totalKm <= 0.0) 0.0 else (businessKm + commuteKm) / totalKm
    val isEmpty: Boolean get() = trips == 0 && gapKm <= 0.0
}

data class Trust(
    val score: Int,
    val grade: String,
    val signatureOk: Boolean,
    val gapKm: Double,
    val immediateRate: Double,
    val purposeChanges: Int,
    val classifiedRate: Double,
    val gapShare: Double = 0.0,
    val lateTrips: Int = 0,
    val unclassifiedTrips: Int = 0,
    val trips: Int = 0,
    val gapPenalty: Int = 0,
    val latePenalty: Int = 0,
    val unclassifiedPenalty: Int = 0,
    val changePenalty: Int = 0
) {
    /** Only what lowered the grade, biggest first. Empty list = no deductions. */
    val issues: List<TrustIssue>
        get() {
            if (!signatureOk) return listOf(TrustIssue(TrustIssueKind.SIGNATURE, 100, "기록 검증 실패", "저장된 기록이 서명과 맞지 않습니다"))
            val list = mutableListOf<TrustIssue>()
            if (gapPenalty > 0) list += TrustIssue(TrustIssueKind.GAP, gapPenalty, "미기록 구간 ${fmt1(gapKm)} km", "총 거리의 ${pct(gapShare)}를 앱이 기록하지 못했습니다")
            if (latePenalty > 0) list += TrustIssue(TrustIssueKind.LATE, latePenalty, "늦은 분류 ${lateTrips}건", "도착 후 10분이 지나 목적을 골랐습니다")
            if (unclassifiedPenalty > 0) list += TrustIssue(TrustIssueKind.UNCLASSIFIED, unclassifiedPenalty, "미분류 ${unclassifiedTrips}건", "목적을 고르지 않은 운행이 있습니다")
            if (changePenalty > 0) list += TrustIssue(TrustIssueKind.CHANGE, changePenalty, "목적 변경 ${purposeChanges}건", "고른 목적을 나중에 바꿨습니다")
            return list.sortedByDescending { it.penalty }
        }

    private fun fmt1(v: Double) = String.format(java.util.Locale.KOREA, "%,.1f", v)
    private fun pct(v: Double) = String.format(java.util.Locale.KOREA, "%.0f%%", v * 100.0)
}

enum class TrustIssueKind { SIGNATURE, GAP, LATE, UNCLASSIFIED, CHANGE }

data class TrustIssue(val kind: TrustIssueKind, val penalty: Int, val title: String, val detail: String)

data class Bucket(val label: String, val km: Double, val businessKm: Double)

data class Comparison(val label: String, val now: Double, val before: Double, val hasBefore: Boolean) {
    val deltaPct: Double get() = if (before <= 0.0) 0.0 else (now - before) / before * 100.0
}

object Analytics {

    /** Classification within this time after arrival counts as "즉시 분류". */
    const val IMMEDIATE_MS: Long = 10 * 60_000L

    fun buildViews(entries: List<SealedEntry>): List<TripView> {
        val purposeHistory = HashMap<String, MutableList<SealedEntry>>()
        for (s in entries) {
            if (s.entry.type == RecordType.PURPOSE) {
                purposeHistory.getOrPut(s.entry.tripId) { mutableListOf() }.add(s)
            }
        }
        val views = mutableListOf<TripView>()
        for (s in entries) {
            val e = s.entry
            if (e.type != RecordType.TRIP && e.type != RecordType.GAP) continue
            val history = purposeHistory[e.tripId].orEmpty()
            val last = history.lastOrNull()
            views += TripView(
                id = e.tripId,
                kind = if (e.type == RecordType.TRIP) ViewKind.TRIP else ViewKind.GAP,
                startMs = e.startMs,
                endMs = e.endMs,
                distanceKm = e.distanceKm,
                source = e.distanceSource,
                startOdoKm = e.startOdoKm,
                endOdoKm = e.endOdoKm,
                purpose = last?.entry?.purpose,
                classifiedAtMs = history.firstOrNull()?.createdAtMs,
                purposeChanges = if (history.size > 1) history.size - 1 else 0
            )
        }
        return views.sortedBy { it.startMs }
    }

    fun range(period: Period, anchorMs: Long, zone: ZoneId): Range {
        val day = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate()
        val from: LocalDate
        val to: LocalDate
        when (period) {
            Period.DAY -> {
                from = day; to = day.plusDays(1)
            }
            Period.WEEK -> {
                from = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)); to = from.plusWeeks(1)
            }
            Period.MONTH -> {
                from = day.withDayOfMonth(1); to = from.plusMonths(1)
            }
            Period.YEAR -> {
                from = day.withDayOfYear(1); to = from.plusYears(1)
            }
        }
        return Range(from.atStartOfDay(zone).toInstant().toEpochMilli(), to.atStartOfDay(zone).toInstant().toEpochMilli())
    }

    fun previous(period: Period, r: Range, zone: ZoneId): Range {
        val from = Instant.ofEpochMilli(r.fromMs).atZone(zone).toLocalDate()
        val prevFrom = when (period) {
            Period.DAY -> from.minusDays(1)
            Period.WEEK -> from.minusWeeks(1)
            Period.MONTH -> from.minusMonths(1)
            Period.YEAR -> from.minusYears(1)
        }
        return Range(prevFrom.atStartOfDay(zone).toInstant().toEpochMilli(), r.fromMs)
    }

    fun sameRangeLastYear(r: Range, zone: ZoneId): Range {
        val from = Instant.ofEpochMilli(r.fromMs).atZone(zone).minusYears(1)
        val to = Instant.ofEpochMilli(r.toMs).atZone(zone).minusYears(1)
        return Range(from.toInstant().toEpochMilli(), to.toInstant().toEpochMilli())
    }

    fun stats(views: List<TripView>, r: Range): PeriodStats {
        var total = 0.0
        var biz = 0.0
        var com = 0.0
        var per = 0.0
        var unc = 0.0
        var gap = 0.0
        var trips = 0
        for (v in views) {
            if (v.startMs < r.fromMs || v.startMs >= r.toMs) continue
            total += v.distanceKm
            if (v.kind == ViewKind.GAP) {
                gap += v.distanceKm
                continue
            }
            trips++
            when (v.purpose) {
                Purpose.BUSINESS -> biz += v.distanceKm
                Purpose.COMMUTE -> com += v.distanceKm
                Purpose.PERSONAL -> per += v.distanceKm
                null -> unc += v.distanceKm
            }
        }
        return PeriodStats(total, biz, com, per, unc, gap, trips)
    }

    fun comparisons(views: List<TripView>, anchorMs: Long, zone: ZoneId): List<Comparison> {
        fun cmp(label: String, now: Range, before: Range): Comparison {
            val a = stats(views, now)
            val b = stats(views, before)
            return Comparison(label, a.totalKm, b.totalKm, !b.isEmpty)
        }
        val day = range(Period.DAY, anchorMs, zone)
        val week = range(Period.WEEK, anchorMs, zone)
        val month = range(Period.MONTH, anchorMs, zone)
        return listOf(
            cmp("전일", day, previous(Period.DAY, day, zone)),
            cmp("전주", week, previous(Period.WEEK, week, zone)),
            cmp("전월", month, previous(Period.MONTH, month, zone)),
            cmp("전년", month, sameRangeLastYear(month, zone))
        )
    }

    /** 7 weekdays (Mon..Sun) x 7 two-hour bins (07-09 .. 19-21), distance in km. */
    fun heatmap(views: List<TripView>, r: Range, zone: ZoneId): Array<DoubleArray> {
        val grid = Array(7) { DoubleArray(7) }
        for (v in views) {
            if (v.kind != ViewKind.TRIP || v.startMs < r.fromMs || v.startMs >= r.toMs) continue
            val t = Instant.ofEpochMilli(v.startMs).atZone(zone)
            val w = t.dayOfWeek.value - 1
            val bin = (t.hour - 7) / 2
            if (t.hour in 7..20 && bin in 0..6) grid[w][bin] += v.distanceKm
        }
        return grid
    }

    /** Totals per month for the 12 months ending with the anchor month: (month number, business+commute km, other km). */
    fun monthlyBars(views: List<TripView>, anchorMs: Long, zone: ZoneId): List<Triple<Int, Double, Double>> {
        val anchorMonth = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate().withDayOfMonth(1)
        val out = mutableListOf<Triple<Int, Double, Double>>()
        for (i in 11 downTo 0) {
            val m = anchorMonth.minusMonths(i.toLong())
            val r = Range(
                m.atStartOfDay(zone).toInstant().toEpochMilli(),
                m.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
            )
            val s = stats(views, r)
            out += Triple(m.monthValue, s.businessKm + s.commuteKm, s.totalKm - s.businessKm - s.commuteKm)
        }
        return out
    }

    fun trust(views: List<TripView>, r: Range, signatureOk: Boolean): Trust {
        val list = views.filter { it.startMs >= r.fromMs && it.startMs < r.toMs }
        val trips = list.filter { it.kind == ViewKind.TRIP }
        val tripKm = trips.sumOf { it.distanceKm }
        val gapKm = list.filter { it.kind == ViewKind.GAP }.sumOf { it.distanceKm }
        val classified = trips.filter { it.purpose != null }
        val immediate = classified.count { c -> c.classifiedAtMs != null && c.classifiedAtMs - c.endMs <= IMMEDIATE_MS }
        val changes = trips.sumOf { it.purposeChanges }
        val gapShare = if (tripKm + gapKm <= 0.0) 0.0 else gapKm / (tripKm + gapKm)
        val immediateRate = if (classified.isEmpty()) 1.0 else immediate.toDouble() / classified.size
        val classifiedRate = if (trips.isEmpty()) 1.0 else classified.size.toDouble() / trips.size
        val gapPenalty = Math.round(50.0 * gapShare).toInt()
        val latePenalty = Math.round(20.0 * (1.0 - immediateRate)).toInt()
        val unclassifiedPenalty = Math.round(15.0 * (1.0 - classifiedRate)).toInt()
        val changePenalty = minOf(10, changes * 2)
        val s = (100 - gapPenalty - latePenalty - unclassifiedPenalty - changePenalty).coerceIn(0, 100)
        val grade = when {
            !signatureOk -> "D"
            s >= 95 -> "AAA"
            s >= 90 -> "AA"
            s >= 80 -> "A"
            s >= 70 -> "BBB"
            s >= 60 -> "BB"
            else -> "B"
        }
        return Trust(
            score = if (signatureOk) s else 0,
            grade = grade,
            signatureOk = signatureOk,
            gapKm = gapKm,
            immediateRate = immediateRate,
            purposeChanges = changes,
            classifiedRate = classifiedRate,
            gapShare = gapShare,
            lateTrips = classified.size - immediate,
            unclassifiedTrips = trips.size - classified.size,
            trips = trips.size,
            gapPenalty = gapPenalty,
            latePenalty = latePenalty,
            unclassifiedPenalty = unclassifiedPenalty,
            changePenalty = changePenalty
        )
    }

    /** Distance per sub-period for the detail chart: hours of a day, days of a week/month, months of a year. */
    fun buckets(views: List<TripView>, period: Period, anchorMs: Long, zone: ZoneId): List<Bucket> {
        val r = range(period, anchorMs, zone)
        val from = Instant.ofEpochMilli(r.fromMs).atZone(zone)
        val out = mutableListOf<Bucket>()
        fun add(label: String, a: Long, b: Long) {
            val s = stats(views, Range(a, b))
            out += Bucket(label, s.totalKm, s.businessKm + s.commuteKm)
        }
        when (period) {
            Period.DAY -> for (h in 0 until 24 step 2) {
                add("${h}시", from.plusHours(h.toLong()).toInstant().toEpochMilli(), from.plusHours(h + 2L).toInstant().toEpochMilli())
            }
            Period.WEEK -> {
                val names = listOf("월", "화", "수", "목", "금", "토", "일")
                for (d in 0 until 7) add(names[d], from.plusDays(d.toLong()).toInstant().toEpochMilli(), from.plusDays(d + 1L).toInstant().toEpochMilli())
            }
            Period.MONTH -> {
                val days = from.toLocalDate().lengthOfMonth()
                for (d in 0 until days) add("${d + 1}", from.plusDays(d.toLong()).toInstant().toEpochMilli(), from.plusDays(d + 1L).toInstant().toEpochMilli())
            }
            Period.YEAR -> for (m in 0 until 12) {
                add("${m + 1}월", from.plusMonths(m.toLong()).toInstant().toEpochMilli(), from.plusMonths(m + 1L).toInstant().toEpochMilli())
            }
        }
        return out
    }

    /** Business-use ratio per month for the 12 months ending with the anchor month. */
    fun monthlyRatio(views: List<TripView>, anchorMs: Long, zone: ZoneId): List<Pair<Int, Double?>> {
        val anchorMonth = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate().withDayOfMonth(1)
        return (11 downTo 0).map { i ->
            val m = anchorMonth.minusMonths(i.toLong())
            val s = stats(views, Range(m.atStartOfDay(zone).toInstant().toEpochMilli(), m.plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()))
            m.monthValue to (if (s.isEmpty) null else s.businessRatio)
        }
    }

    /** 7 weekdays x 12 two-hour bins (00-02 .. 22-24). */
    fun heatmapFullDay(views: List<TripView>, r: Range, zone: ZoneId): Array<DoubleArray> {
        val grid = Array(7) { DoubleArray(12) }
        for (v in views) {
            if (v.kind != ViewKind.TRIP || v.startMs < r.fromMs || v.startMs >= r.toMs) continue
            val t = Instant.ofEpochMilli(v.startMs).atZone(zone)
            grid[t.dayOfWeek.value - 1][t.hour / 2] += v.distanceKm
        }
        return grid
    }
}
