// ver 1.3.1 | 2026-11-04 15:30
package io.tripsign.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.tripsign.app.core.Analytics
import io.tripsign.app.core.ChainCheck
import io.tripsign.app.core.Period
import io.tripsign.app.core.Purpose
import io.tripsign.app.core.TripView
import io.tripsign.app.core.ViewKind
import io.tripsign.app.data.Settings
import io.tripsign.app.data.tr
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

fun detailTitle(kind: DetailKind): String = when (kind) {
    DetailKind.RATIO -> tr("업무 사용 비율", "Business use")
    DetailKind.TOTAL -> tr("총 주행거리", "Distance")
    DetailKind.COMPARE -> tr("기간별 주행거리 비교", "Period comparison")
    DetailKind.PATTERN -> tr("요일·시간대 주행 패턴", "Weekly pattern")
    DetailKind.TRUST -> tr("기록 신뢰 등급", "Record trust")
}

@Composable
fun DetailScreen(
    kind: DetailKind,
    views: List<TripView>,
    check: ChainCheck,
    signatureOk: Boolean,
    demo: Boolean,
    period: Period,
    anchorMs: Long,
    zone: ZoneId,
    onPeriod: (Period) -> Unit,
    onBack: () -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            NavChip(tr("분석", "Overview"), back = true, onClick = onBack)
            Spacer(Modifier.width(u(24)))
            // the title shrinks before the period, the sample chip or the segmented control move
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                FitTextFill(detailTitle(kind), 52, 36, FontWeight.ExtraBold, Palette.Ink, Modifier.weight(1f, fill = false))
                if (kind != DetailKind.COMPARE) {
                    Spacer(Modifier.width(u(18)))
                    Text(periodTitle(period, anchorMs, zone), fontSize = fs(36), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1, softWrap = false)
                }
                if (demo) {
                    Spacer(Modifier.width(u(18)))
                    Chip(tr("예시 데이터", "Sample data"), Palette.WarnSoft, Palette.Warn)
                }
            }
            Spacer(Modifier.width(u(16)))
            if (kind != DetailKind.COMPARE) {
                Segmented(periodOptions, period.ordinal, 104) { onPeriod(Period.entries[it]) }
            }
        }
        Spacer(Modifier.height(u(24)))
        val range = Analytics.range(period, anchorMs, zone)
        when (kind) {
            DetailKind.RATIO -> RatioDetail(views, range, anchorMs, zone)
            DetailKind.TOTAL -> TotalDetail(views, period, range, anchorMs, zone)
            DetailKind.COMPARE -> CompareDetail(views, anchorMs, zone)
            DetailKind.PATTERN -> PatternDetail(views, range, zone)
            DetailKind.TRUST -> TrustDetail(views, range, check, signatureOk)
        }
    }
}

@Composable
private fun RatioDetail(views: List<TripView>, range: io.tripsign.app.core.Range, anchorMs: Long, zone: ZoneId) {
    val s = Analytics.stats(views, range)
    val trips = views.filter { it.kind == ViewKind.TRIP && it.startMs >= range.fromMs && it.startMs < range.toMs }
    Column(verticalArrangement = Arrangement.spacedBy(u(24))) {
        Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
            Card(560, 600) {
                Column(Modifier.fillMaxWidth()) {
                    CardLabel(tr("업무 + 출퇴근", "Business + commute"))
                    if (s.isEmpty) {
                        EmptyNote()
                    } else {
                        Spacer(Modifier.height(u(30)))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Donut(
                                listOf(
                                    s.businessKm.toFloat() to Palette.Blue,
                                    s.commuteKm.toFloat() to Palette.Blue2,
                                    (s.personalKm + s.unclassifiedKm + s.gapKm).toFloat() to Palette.Gray
                                ),
                                400,
                                52
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    FitText(Fmt.pct1(s.businessRatio), 96, 56, 260, FontWeight.ExtraBold, Palette.Ink)
                                    Text("%", fontSize = fs(36), fontWeight = FontWeight.Bold, color = Palette.Sub)
                                }
                            }
                        }
                    }
                }
            }
            Card(1010, 600) {
                Column {
                    CardLabel(tr("목적별 거리", "Distance by purpose"))
                    Spacer(Modifier.height(u(12)))
                    TableHeader(listOf(tr("목적", "Purpose") to 280, tr("거리", "Distance") to 260, tr("운행", "Trips") to 160, tr("비율", "Share") to 200))
                    fun count(p: Purpose?): String {
                        val n = trips.count { it.purpose == p }
                        return if (Settings.isEnglish) Fmt.count(n) else Fmt.count(n) + "회"
                    }
                    val t = if (s.totalKm <= 0.0) 1.0 else s.totalKm
                    TableRow(listOf(purposeLabel(Purpose.BUSINESS) to 280, Fmt.km1(s.businessKm) + " km" to 260, count(Purpose.BUSINESS) to 160, Fmt.pct0(s.businessKm / t) to 200), Palette.Blue)
                    TableRow(listOf(purposeLabel(Purpose.COMMUTE) to 280, Fmt.km1(s.commuteKm) + " km" to 260, count(Purpose.COMMUTE) to 160, Fmt.pct0(s.commuteKm / t) to 200), Palette.Blue2)
                    TableRow(listOf(purposeLabel(Purpose.PERSONAL) to 280, Fmt.km1(s.personalKm) + " km" to 260, count(Purpose.PERSONAL) to 160, Fmt.pct0(s.personalKm / t) to 200), Palette.Gray)
                    TableRow(listOf(tr("미분류", "Unlabeled") to 280, Fmt.km1(s.unclassifiedKm) + " km" to 260, count(null) to 160, Fmt.pct0(s.unclassifiedKm / t) to 200), Palette.Warn)
                    TableRow(listOf(tr("미기록 구간", "Unrecorded") to 280, Fmt.km1(s.gapKm) + " km" to 260, "–" to 160, Fmt.pct0(s.gapKm / t) to 200), Palette.Down)
                    Spacer(Modifier.height(u(12)))
                    Text(tr("미분류·미기록 구간은 업무 거리에 넣지 않습니다", "Unlabeled or unrecorded km is never business"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
                }
            }
        }
        Card(1594, 394) {
            Column {
                CardLabel(tr("최근 12개월 업무 사용 비율", "Business use, last 12 months"))
                Spacer(Modifier.height(u(14)))
                val months = Analytics.monthlyRatio(views, anchorMs, zone)
                Row(Modifier.fillMaxWidth().height(u(270)), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
                    months.forEachIndexed { i, (m, r) ->
                        val last = i == months.lastIndex
                        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(u(110))) {
                            Text(r?.let { Fmt.pct0(it) } ?: "–", fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = if (last) Palette.Blue else Palette.Sub, maxLines = 1)
                            Spacer(Modifier.height(u(8)))
                            if (r == null || r <= 0.0) {
                                Box(Modifier.width(u(56)).height(u(3)).background(Palette.Line))
                            } else {
                                Box(
                                    Modifier
                                        .width(u(56))
                                        .height(u((r * 160).coerceAtLeast(8.0)))
                                        .background(if (last) Palette.Blue else Palette.BarSoft, RoundedCornerShape(u(10)))
                                )
                            }
                            Spacer(Modifier.height(u(10)))
                            Text(monthLabel(m), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

private fun bucketLabel(period: Period, i: Int): String = when (period) {
    Period.DAY -> hourLabel(i * 2)
    Period.WEEK -> weekdayLabels[i]
    Period.MONTH -> "${i + 1}"
    Period.YEAR -> monthLabel(i + 1)
}

@Composable
private fun TotalDetail(views: List<TripView>, period: Period, range: io.tripsign.app.core.Range, anchorMs: Long, zone: ZoneId) {
    val s = Analytics.stats(views, range)
    val trips = views.filter { it.kind == ViewKind.TRIP && it.startMs >= range.fromMs && it.startMs < range.toMs }
    val longest = trips.maxByOrNull { it.distanceKm }
    Column(verticalArrangement = Arrangement.spacedBy(u(24))) {
        Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
            Kpi(tr("총 주행거리", "Total"), if (s.isEmpty) null else Fmt.km0(s.totalKm), "km")
            Kpi(tr("운행 횟수", "Trips"), if (s.trips == 0) null else Fmt.count(s.trips), tr("회", ""))
            Kpi(tr("평균 거리", "Average"), if (s.trips == 0) null else Fmt.km1(trips.sumOf { it.distanceKm } / s.trips), "km")
            Kpi(tr("가장 긴 운행", "Longest"), longest?.let { Fmt.km1(it.distanceKm) }, "km")
        }
        Card(1594, 774) {
            Column {
                CardLabel(
                    when (period) {
                        Period.DAY -> tr("2시간 단위 주행거리", "Distance per 2 hours")
                        Period.WEEK -> tr("요일별 주행거리", "Distance per weekday")
                        Period.MONTH -> tr("일별 주행거리", "Distance per day")
                        Period.YEAR -> tr("월별 주행거리", "Distance per month")
                    }
                )
                Row(Modifier.fillMaxWidth().padding(top = u(8)), horizontalArrangement = Arrangement.End) {
                    LegendSquare(Palette.Blue, tr("업무·출퇴근", "Business·commute"))
                    Spacer(Modifier.width(u(26)))
                    LegendSquare(Palette.Gray, tr("개인·기타", "Other"))
                }
                Spacer(Modifier.height(u(16)))
                val buckets = Analytics.buckets(views, period, anchorMs, zone)
                if (buckets.all { it.km <= 0.0 }) {
                    EmptyNote()
                } else {
                    val maxKm = max(buckets.maxOf { it.km }, 0.0001)
                    Canvas(Modifier.fillMaxWidth().height(u(500))) {
                        val n = buckets.size
                        val gap = size.width * 0.3f / n
                        val bw = (size.width - gap * (n - 1)) / n
                        drawLine(Palette.Line, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
                        buckets.forEachIndexed { i, b ->
                            val x = i * (bw + gap)
                            val hAll = (size.height * b.km / maxKm).toFloat()
                            val hBiz = (size.height * b.businessKm / maxKm).toFloat()
                            val r = CornerRadius(bw * 0.2f, bw * 0.2f)
                            if (hAll > 0f) drawRoundRect(Palette.Gray, Offset(x, size.height - hAll), Size(bw, hAll), r)
                            if (hBiz > 0f) drawRoundRect(Palette.Blue, Offset(x, size.height - hBiz), Size(bw, hBiz), r)
                        }
                    }
                    Spacer(Modifier.height(u(12)))
                    Row(Modifier.fillMaxWidth()) {
                        val step = if (buckets.size > 14) 5 else 1
                        buckets.forEachIndexed { i, _ ->
                            Text(
                                if (i % step == 0) bucketLabel(period, i) else "",
                                fontSize = fs(Sz.TEXT_MIN),
                                color = Palette.Sub,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }
    }
}

/** KPI tile. A missing value shows a single dash and no unit. */
@Composable
private fun Kpi(label: String, value: String?, unit: String) {
    Card(380, 200) {
        Column {
            Text(label, fontSize = fs(Sz.LABEL), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
            Row(verticalAlignment = Alignment.Bottom) {
                // unit is ~60 px; the number gets the rest of the 316 px and shrinks past 99,999
                FitText(value ?: "–", 72, 40, if (value != null && unit.isNotEmpty()) 250 else 316, FontWeight.ExtraBold, if (value == null) Palette.Muted else Palette.Ink)
                if (value != null && unit.isNotEmpty()) {
                    Text(" $unit", fontSize = fs(34), fontWeight = FontWeight.Bold, color = Palette.Sub, modifier = Modifier.padding(bottom = u(10)))
                }
            }
        }
    }
}

@Composable
private fun CompareDetail(views: List<TripView>, anchorMs: Long, zone: ZoneId) {
    val day = Analytics.range(Period.DAY, anchorMs, zone)
    val week = Analytics.range(Period.WEEK, anchorMs, zone)
    val month = Analytics.range(Period.MONTH, anchorMs, zone)
    val rows = listOf(
        Triple(tr("전일", "Day"), day, Analytics.previous(Period.DAY, day, zone)),
        Triple(tr("전주", "Week"), week, Analytics.previous(Period.WEEK, week, zone)),
        Triple(tr("전월", "Month"), month, Analytics.previous(Period.MONTH, month, zone)),
        Triple(tr("전년 동월", "Year"), month, Analytics.sameRangeLastYear(month, zone))
    )
    Card(1594, 1000) {
        Column {
            CardLabel(tr("이번 기간과 이전 기간", "This period vs the one before"))
            Spacer(Modifier.height(u(18)))
            TableHeader(
                listOf(
                    tr("비교", "Compare") to 220,
                    tr("총 거리 (이번 / 이전)", "Total (now / before)") to 520,
                    tr("증감", "Change") to 240,
                    tr("업무·출퇴근 (이번 / 이전)", "Business·commute (now / before)") to 470
                )
            )
            for ((label, now, before) in rows) {
                val a = Analytics.stats(views, now)
                val b = Analytics.stats(views, before)
                val noBefore = b.isEmpty || b.totalKm <= 0.0
                val delta = if (noBefore) tr("기록 없음", "No data") else {
                    val d = (a.totalKm - b.totalKm) / b.totalKm * 100.0
                    (if (d >= 0) "▲ " else "▼ ") + String.format(Locale.US, "%.1f%%", abs(d))
                }
                Row(
                    Modifier.fillMaxWidth().height(u(150)),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.width(u(40)))
                    FitText(label, 40, 28, 210, FontWeight.ExtraBold, Palette.Ink, Modifier.width(u(220)))
                    FitText(Fmt.km1(a.totalKm) + "  /  " + (if (noBefore) "–" else Fmt.km1(b.totalKm)) + " km", 36, 26, 500, FontWeight.Bold, Palette.Ink, Modifier.width(u(520)))
                    FitText(
                        delta,
                        36,
                        26,
                        230,
                        FontWeight.ExtraBold,
                        when {
                            delta.startsWith("▲") -> Palette.Blue
                            delta.startsWith("▼") -> Palette.Down
                            else -> Palette.Muted
                        },
                        Modifier.width(u(240))
                    )
                    FitText(
                        Fmt.km1(a.businessKm + a.commuteKm) + "  /  " + (if (noBefore) "–" else Fmt.km1(b.businessKm + b.commuteKm)) + " km",
                        34,
                        24,
                        460,
                        FontWeight.Normal,
                        Palette.Sub,
                        Modifier.width(u(470))
                    )
                }
                Box(Modifier.fillMaxWidth().height(u(2)).background(Palette.Line))
            }
            Spacer(Modifier.height(u(18)))
            Text(
                tr("기준: 전일·전주·전월은 바로 앞 기간, 전년 동월은 작년 같은 달", "Day · Week · Month: the period just before   ·   Year: same month last year"),
                fontSize = fs(Sz.TEXT_MIN),
                color = Palette.Sub,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PatternDetail(views: List<TripView>, range: io.tripsign.app.core.Range, zone: ZoneId) {
    val grid = Analytics.heatmapFullDay(views, range, zone)
    val maxV = grid.maxOf { row -> row.maxOrNull() ?: 0.0 }
    val days = weekdayLabels
    val empty = maxV <= 0.0
    Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
        Card(1080, 1000) {
            Column {
                CardLabel(tr("요일 × 시간대 (2시간 단위, 거리 기준)", "Weekday × time (2-hour blocks, by distance)"))
                Spacer(Modifier.height(u(28)))
                if (empty) {
                    EmptyNote()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(u(10))) {
                        for (w in 0 until 7) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(8))) {
                                Text(days[w], fontSize = fs(Sz.LABEL), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1, modifier = Modifier.width(u(70)))
                                for (b in 0 until 12) {
                                    val v = grid[w][b] / maxV
                                    Box(
                                        Modifier
                                            .width(u(68))
                                            .height(u(96))
                                            .background(
                                                if (v > 0) Palette.Blue.copy(alpha = (0.12 + 0.88 * v).toFloat()) else Palette.Tile,
                                                RoundedCornerShape(u(10))
                                            )
                                    )
                                }
                            }
                        }
                        Row {
                            Spacer(Modifier.width(u(78)))
                            // one label per two cells (0h, 4h, ... 20h), each owning both cells' width
                            for (b in 0 until 12 step 2) {
                                Text(
                                    hourLabel(b * 2),
                                    fontSize = fs(Sz.TEXT_MIN),
                                    color = Palette.Sub,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.width(u(152))
                                )
                            }
                        }
                    }
                }
            }
        }
        Card(490, 1000) {
            Column {
                CardLabel(tr("요일별 거리", "By weekday"))
                Spacer(Modifier.height(u(20)))
                val totals = grid.map { it.sum() }
                val mx = max(totals.maxOrNull() ?: 0.0, 0.0001)
                totals.forEachIndexed { i, t ->
                    Row(Modifier.height(u(64)), verticalAlignment = Alignment.CenterVertically) {
                        Text(days[i], fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1, modifier = Modifier.width(u(70)))
                        Box(Modifier.width(u(220)).height(u(30)).background(Palette.Tile, RoundedCornerShape(u(8)))) {
                            if (t > 0.0) {
                                Box(
                                    Modifier
                                        .width(u((220 * t / mx).coerceAtLeast(10.0)))
                                        .height(u(30))
                                        .background(Palette.Blue, RoundedCornerShape(u(8)))
                                )
                            }
                        }
                        Spacer(Modifier.width(u(14)))
                        Text(Fmt.km0(t), fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = if (t > 0.0) Palette.Ink else Palette.Muted, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(u(30)))
                CardLabel(tr("많이 달린 시간대", "Busiest times"))
                Spacer(Modifier.height(u(14)))
                val cells = mutableListOf<Triple<Int, Int, Double>>()
                for (w in 0 until 7) for (b in 0 until 12) if (grid[w][b] > 0) cells += Triple(w, b, grid[w][b])
                if (cells.isEmpty()) {
                    Text(tr("기록 없음", "No records"), fontSize = fs(36), fontWeight = FontWeight.ExtraBold, color = Palette.Muted)
                } else {
                    // slot and distance on two deliberate lines: one line no longer fits at large text
                    cells.sortedByDescending { it.third }.take(3).forEachIndexed { rank, (w, b, km) ->
                        val slot = if (Settings.isEnglish) "${days[w]} ${b * 2}–${b * 2 + 2}h" else "${days[w]}요일 ${b * 2}~${b * 2 + 2}시"
                        Column(Modifier.padding(vertical = u(6))) {
                            Text("${rank + 1}.  $slot", fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1)
                            Text(Fmt.km0(km) + " km", fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1, modifier = Modifier.padding(start = u(40)))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrustDetail(views: List<TripView>, range: io.tripsign.app.core.Range, check: ChainCheck, signatureOk: Boolean) {
    val t = Analytics.trust(views, range, signatureOk)
    val empty = Analytics.stats(views, range).isEmpty
    fun pts(p: Int) = if (p == 0) tr("0점", "0") else "−$p" + tr("점", "")
    Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
        Card(560, 1000) {
            Column {
                CardLabel(tr("등급", "Grade"))
                Spacer(Modifier.height(u(20)))
                if (empty && signatureOk) {
                    Text(tr("기록 전", "No trips"), fontSize = fs(96), fontWeight = FontWeight.ExtraBold, color = Palette.Muted, maxLines = 1)
                } else {
                    FitText(t.grade, 180, 110, 490, FontWeight.Black, gradeColor(t))
                    Text("${t.score} / 100", fontSize = fs(48), fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
                }
                Spacer(Modifier.height(u(36)))
                GradeScale()
                Spacer(Modifier.weight(1f))
                Text(tr("기록 ${Fmt.count(check.count)}건", Fmt.count(check.count) + plural(check.count, " record", " records")), fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
                Text(tr("마지막 해시 ", "Last hash ") + check.headHash.take(12), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
            }
        }
        Card(1010, 1000) {
            Column(verticalArrangement = Arrangement.spacedBy(u(16))) {
                CardLabel(tr("점수가 정해진 이유", "How the score is set"))
                Spacer(Modifier.height(u(4)))
                DetailIssue(
                    signatureOk,
                    tr("서명 검증", "Signatures"),
                    if (signatureOk) tr("모든 기록의 서명·해시 연결 일치", "All signatures and hash links match") else tr("검증 실패: ", "Failed: ") + check.reason,
                    if (signatureOk) tr("통과", "OK") else tr("D 등급", "Grade D")
                )
                DetailIssue(
                    t.gapPenalty == 0,
                    tr("미기록 구간", "Unrecorded"),
                    tr("기록 못 한 거리 ${Fmt.km1(t.gapKm)} km · 전체의 ${Fmt.pct0(t.gapShare)}", "${Fmt.km1(t.gapKm)} km not recorded · ${Fmt.pct0(t.gapShare)} of total"),
                    pts(t.gapPenalty)
                )
                DetailIssue(
                    t.latePenalty == 0,
                    tr("늦은 분류", "Late labels"),
                    tr("도착 10분 뒤에 목적을 고른 운행 ${Fmt.count(t.lateTrips)}건", Fmt.count(t.lateTrips) + plural(t.lateTrips, " trip", " trips") + " labeled 10+ min late"),
                    pts(t.latePenalty)
                )
                DetailIssue(
                    t.unclassifiedPenalty == 0,
                    tr("미분류", "Unlabeled"),
                    tr("목적을 고르지 않은 운행 ${Fmt.count(t.unclassifiedTrips)}건", Fmt.count(t.unclassifiedTrips) + plural(t.unclassifiedTrips, " trip", " trips") + " without a purpose"),
                    pts(t.unclassifiedPenalty)
                )
                DetailIssue(
                    t.changePenalty == 0,
                    tr("목적 변경", "Purpose changes"),
                    tr("고른 목적을 나중에 바꾼 횟수 ${Fmt.count(t.purposeChanges)}건", Fmt.count(t.purposeChanges) + plural(t.purposeChanges, " purpose", " purposes") + " changed later"),
                    pts(t.changePenalty)
                )
                Spacer(Modifier.height(u(4)))
                // formula as a 2 x 2 grid: one sentence no longer fits at large text
                Column {
                    Text(tr("100점에서 감점", "Deducted from 100"), fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
                    Row {
                        Text(tr("미기록 비율 × 50", "Unrecorded share × 50"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1, modifier = Modifier.width(u(470)))
                        Text(tr("늦은 분류 비율 × 20", "Late-label share × 20"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
                    }
                    Row {
                        Text(tr("미분류 비율 × 15", "Unlabeled share × 15"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1, modifier = Modifier.width(u(470)))
                        Text(tr("목적 변경 1건당 2점 (최대 10)", "2 per change (max 10)"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailIssue(ok: Boolean, title: String, detail: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(u(120))
            .background(if (ok) Palette.Tile else Palette.WarnSoft, RoundedCornerShape(u(22)))
            .padding(horizontal = u(24)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusIcon(if (ok) Status.OK else Status.WARN, 44)
        Spacer(Modifier.width(u(20)))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = fs(36), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
            Text(detail, fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
        }
        Spacer(Modifier.width(u(12)))
        Text(value, fontSize = fs(36), fontWeight = FontWeight.ExtraBold, color = if (ok) Palette.Ok else Palette.Warn, maxLines = 1)
    }
}

/** Same colours as the grade itself: A grades green, B grades amber, D (signature failed) red. */
@Composable
private fun GradeScale() {
    val grades = listOf("AAA" to "95+", "AA" to "90+", "A" to "80+", "BBB" to "70+", "BB" to "60+", "B" to "~59")
    Column(verticalArrangement = Arrangement.spacedBy(u(8))) {
        grades.forEach { (g, r) ->
            GradeScaleRow(g, r + tr("점", ""), if (g.startsWith("A")) Palette.Ok else Palette.Warn)
        }
        GradeScaleRow("D", tr("서명 검증 실패", "Signature failed"), Palette.Bad)
    }
}

@Composable
private fun GradeScaleRow(grade: String, range: String, color: Color) {
    Row(Modifier.height(u(46)), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(u(18))
                .height(u(18))
                .background(color, RoundedCornerShape(u(9)))
        )
        Spacer(Modifier.width(u(14)))
        Text(grade, fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, modifier = Modifier.width(u(110)))
        Text(range, fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
    }
}

@Composable
private fun TableHeader(cols: List<Pair<String, Int>>) {
    Row(Modifier.fillMaxWidth().height(u(58)), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(u(40)))
        cols.forEach { (text, w) ->
            FitText(text, Sz.TEXT_MIN, 24, w - 10, FontWeight.Bold, Palette.Muted, Modifier.width(u(w)))
        }
    }
    Box(Modifier.fillMaxWidth().height(u(2)).background(Palette.Line))
}

@Composable
private fun TableRow(cols: List<Pair<String, Int>>, dot: Color) {
    Row(Modifier.fillMaxWidth().height(u(72)), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(u(22)).height(u(22)).background(dot, RoundedCornerShape(u(11))))
        Spacer(Modifier.width(u(18)))
        cols.forEachIndexed { i, (text, w) ->
            FitText(text, Sz.LABEL, 24, w - 10, if (i == 0) FontWeight.Bold else FontWeight.ExtraBold, Palette.Ink, Modifier.width(u(w)))
        }
    }
}
