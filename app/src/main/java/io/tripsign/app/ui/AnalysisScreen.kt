// ver 2.1.0 | 2026-11-04 15:25
package io.tripsign.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.tripsign.app.core.Analytics
import io.tripsign.app.core.Period
import io.tripsign.app.core.Purpose
import io.tripsign.app.core.Trust
import io.tripsign.app.core.TrustIssue
import io.tripsign.app.core.TrustIssueKind
import io.tripsign.app.core.TripView
import io.tripsign.app.core.ViewKind
import io.tripsign.app.data.Settings
import io.tripsign.app.data.tr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

enum class DetailKind { RATIO, TOTAL, COMPARE, PATTERN, TRUST }

fun periodTitle(period: Period, anchorMs: Long, zone: ZoneId): String {
    val d = Instant.ofEpochMilli(anchorMs).atZone(zone).toLocalDate()
    return when (period) {
        Period.DAY -> if (Settings.isEnglish) "${monthLabel(d.monthValue)} ${d.dayOfMonth}" else "${d.monthValue}월 ${d.dayOfMonth}일"
        Period.WEEK -> tr("이번 주", "This week")
        Period.MONTH -> if (Settings.isEnglish) listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")[d.monthValue - 1] else "${d.monthValue}월"
        Period.YEAR -> if (Settings.isEnglish) "${d.year}" else "${d.year}년"
    }
}

fun prevLabel(period: Period): String = when (period) {
    Period.DAY -> tr("전일 대비", "vs previous day")
    Period.WEEK -> tr("전주 대비", "vs previous week")
    Period.MONTH -> tr("전월 대비", "vs previous month")
    Period.YEAR -> tr("전년 대비", "vs previous year")
}

val periodOptions: List<String> get() = listOf(tr("일", "D"), tr("주", "W"), tr("월", "M"), tr("년", "Y"))

fun gradeColor(t: Trust): Color = when {
    !t.signatureOk -> Palette.Bad
    t.score >= 80 -> Palette.Ok
    else -> Palette.Warn
}

fun issueTitle(i: TrustIssue, t: Trust): String = when (i.kind) {
    TrustIssueKind.SIGNATURE -> tr("기록 검증 실패", "Verification failed")
    TrustIssueKind.GAP -> tr("미기록 구간 ${Fmt.km1(t.gapKm)} km", "Unrecorded ${Fmt.km1(t.gapKm)} km")
    TrustIssueKind.LATE -> tr("늦은 분류 ${Fmt.count(t.lateTrips)}건", "Late labels: ${Fmt.count(t.lateTrips)}")
    TrustIssueKind.UNCLASSIFIED -> tr("미분류 ${Fmt.count(t.unclassifiedTrips)}건", "Unlabeled: ${Fmt.count(t.unclassifiedTrips)}")
    TrustIssueKind.CHANGE -> tr("목적 변경 ${t.purposeChanges}건", "Purpose changed: ${t.purposeChanges}")
}

/** Index (0..6) of the 2-hour slot from 07 h with the most business km, or null if none. */
fun peakBusinessBin(views: List<TripView>, fromMs: Long, toMs: Long, zone: ZoneId): Int? {
    val bins = DoubleArray(7)
    for (v in views) {
        if (v.kind != ViewKind.TRIP || v.purpose != Purpose.BUSINESS || v.startMs < fromMs || v.startMs >= toMs) continue
        val h = Instant.ofEpochMilli(v.startMs).atZone(zone).hour
        if (h in 7..20) bins[(h - 7) / 2] += v.distanceKm
    }
    val best = bins.indices.maxByOrNull { bins[it] } ?: return null
    return if (bins[best] <= 0.0) null else best
}

/*
 * Home layout (design px, content area 1618 x 1026 under the header):
 *   pager 978 high + 8 gap + page dots 40 (dots like onboarding; swipe to change page).
 *   Page 1: business use (797 x 978) | distance (797 x 477) over record trust (797 x 477)
 *   Page 2: period comparison (797 x 978) | weekly pattern (797 x 978)
 * Inside every card the label sits at the top and the content is pinned to the bottom edge.
 * Heights are budgeted for large text (x1.2); see the notes at each card.
 */
private const val PAGE_H = 978
private const val HALF_H = 477
private const val COL_W = 797

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnalysisScreen(
    views: List<TripView>,
    signatureOk: Boolean,
    demo: Boolean,
    period: Period,
    anchorMs: Long,
    zone: ZoneId,
    needsPermission: Boolean,
    parked: Boolean,
    homePage: Int,
    onHomePage: (Int) -> Unit,
    onPeriod: (Period) -> Unit,
    onToggleDemo: () -> Unit,
    onOpenTrips: () -> Unit,
    onOpenDetail: (DetailKind) -> Unit,
    onOpenSettings: () -> Unit
) {
    val range = Analytics.range(period, anchorMs, zone)
    val stats = Analytics.stats(views, range)
    val prev = Analytics.stats(views, Analytics.previous(period, range, zone))
    val trust = Analytics.trust(views, range, signatureOk)
    val comparisons = Analytics.comparisons(views, anchorMs, zone)
    val bars = Analytics.monthlyBars(views, anchorMs, zone)
    val heat = Analytics.heatmap(views, range, zone)
    val peak = peakBusinessBin(views, range.fromMs, range.toMs, zone)

    val pager = rememberPagerState(initialPage = homePage.coerceIn(0, 1)) { 2 }
    val scope = rememberCoroutineScope()
    LaunchedEffect(pager.currentPage) { onHomePage(pager.currentPage) }
    LaunchedEffect(homePage) { if (homePage != pager.currentPage) pager.scrollToPage(homePage.coerceIn(0, 1)) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            // the title gives way (shrinks) before any control is pushed off screen
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                FitTextFill(
                    if (Settings.isEnglish) periodTitle(period, anchorMs, zone) + " trips" else periodTitle(period, anchorMs, zone) + " 운행 분석",
                    56,
                    40,
                    FontWeight.ExtraBold,
                    Palette.Ink,
                    Modifier.weight(1f, fill = false)
                )
                Spacer(Modifier.width(u(20)))
                if (demo) {
                    Chip(tr("예시 데이터  ✕", "Sample data  ✕"), Palette.WarnSoft, Palette.Warn, onToggleDemo)
                } else {
                    Chip(tr("예시 보기", "Sample"), Palette.Seg, Palette.Sub, onToggleDemo)
                }
            }
            Spacer(Modifier.width(u(16)))
            NavChip(tr("운행 기록", "Trips"), back = false, onClick = onOpenTrips)
            Spacer(Modifier.width(u(16)))
            Segmented(periodOptions, period.ordinal, 104) { onPeriod(Period.entries[it]) }
            Spacer(Modifier.width(u(16)))
            // A missing permission shows as a badge on settings, where the request button lives.
            Box {
                IconButton96(onOpenSettings) { GearIcon(Palette.Sub, 48) }
                if (needsPermission) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = u(6), end = u(6))
                            .size(u(26))
                            .background(Palette.Warn, RoundedCornerShape(u(13)))
                    )
                }
            }
        }
        Spacer(Modifier.height(u(20)))

        HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(u(PAGE_H)), pageSpacing = u(44)) { page ->
            if (page == 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
                    RatioCard(stats) { onOpenDetail(DetailKind.RATIO) }
                    Column(verticalArrangement = Arrangement.spacedBy(u(24))) {
                        TotalCard(stats, prev, period, bars) { onOpenDetail(DetailKind.TOTAL) }
                        Card(COL_W, HALF_H, onClick = { onOpenDetail(DetailKind.TRUST) }) {
                            Column {
                                CardLabel(tr("기록 신뢰 등급", "Record trust"), openable = true)
                                TrustSummary(trust, empty = stats.isEmpty, parked = parked)
                            }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
                    CompareCard(comparisons) { onOpenDetail(DetailKind.COMPARE) }
                    PatternCard(heat, peak, empty = stats.trips == 0) { onOpenDetail(DetailKind.PATTERN) }
                }
            }
        }
        Spacer(Modifier.height(u(8)))
        Box(Modifier.fillMaxWidth().height(u(40)), contentAlignment = Alignment.Center) {
            PageDots(pager.currentPage, 2)
        }
    }
}

/* Business use, hero card. Inner 733 x 878. Large: label 52 + donut 540 + legend ~125 = 717. */
@Composable
private fun RatioCard(stats: io.tripsign.app.core.PeriodStats, onOpen: () -> Unit) {
    Card(COL_W, PAGE_H, onClick = onOpen) {
        Column(Modifier.fillMaxSize()) {
            CardLabel(tr("업무 사용 비율", "Business use"), openable = true)
            if (stats.isEmpty) {
                Spacer(Modifier.weight(1f))
                EmptyNote()
            } else {
                val other = stats.personalKm + stats.unclassifiedKm + stats.gapKm
                Spacer(Modifier.weight(1f))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Donut(
                        listOf(
                            stats.businessKm.toFloat() to Palette.Blue,
                            stats.commuteKm.toFloat() to Palette.Blue2,
                            other.toFloat() to Palette.Gray
                        ),
                        540,
                        64
                    ) {
                        // hole is 412 px across: the number, and the unit written out under it
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FitText(Fmt.pct1(stats.businessRatio), 130, 70, 330, FontWeight.ExtraBold, Palette.Ink)
                            Text(tr("(단위 : %)", "(unit : %)"), fontSize = fs(32), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                val t = stats.totalKm
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Legend(Palette.Blue, tr("업무", "Business"), stats.businessKm / t)
                    Legend(Palette.Blue2, tr("출퇴근", "Commute"), stats.commuteKm / t)
                    Legend(Palette.Gray, tr("개인·기타", "Other"), other / t)
                }
            }
        }
    }
}

/*
 * Distance. Inner 733 x 417. The change chip sits right of the number so the bars get the room:
 * normal 44 + 121 + 16 + bars 150 + 36 = 367, large 52 + 145 + 16 + 150 + 43 = 406.
 */
@Composable
private fun TotalCard(stats: io.tripsign.app.core.PeriodStats, prev: io.tripsign.app.core.PeriodStats, period: Period, bars: List<Triple<Int, Double, Double>>, onOpen: () -> Unit) {
    Card(COL_W, HALF_H, onClick = onOpen) {
        Column(Modifier.fillMaxSize()) {
            CardLabel(tr("총 주행거리", "Distance"), openable = true)
            Spacer(Modifier.weight(1f))
            if (stats.isEmpty) {
                EmptyNote()
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    // number + km take what the change column leaves; 100,211 / 1,040,112 km shrink to fit
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                        FitTextFill(Fmt.km0(stats.totalKm), 110, 56, FontWeight.ExtraBold, Palette.Ink, Modifier.weight(1f, fill = false))
                        Text(" km", fontSize = fs(42), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = u(14)))
                    }
                    Spacer(Modifier.width(u(16)))
                    Column(Modifier.padding(bottom = u(12)), horizontalAlignment = Alignment.End) {
                        if (prev.isEmpty) {
                            Text(tr("이전 기간 기록 없음", "No earlier period"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1, softWrap = false)
                        } else {
                            val d = (stats.totalKm - prev.totalKm) / prev.totalKm * 100.0
                            val up = d >= 0
                            Chip(
                                (if (up) "▲ " else "▼ ") + String.format(Locale.US, "%.1f%%", kotlin.math.abs(d)),
                                if (up) Palette.BlueSoft else Palette.WarnSoft,
                                if (up) Palette.Blue else Palette.Down
                            )
                            Spacer(Modifier.height(u(6)))
                            Text(prevLabel(period), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Spacer(Modifier.height(u(16)))
                SparkBars(bars.map { (it.second + it.third).toFloat() }, 733, 150)
                Row(Modifier.fillMaxWidth()) {
                    Text(monthLabel(bars.first().first), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub)
                    Spacer(Modifier.weight(1f))
                    Text(monthLabel(bars.last().first), fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Blue)
                }
            }
        }
    }
}

/* Period comparison. Inner 733 x 878. Large: 52 + 48 + 67 + 14 + 540 + 18 + 50 + 43 = 832. */
@Composable
private fun CompareCard(comparisons: List<io.tripsign.app.core.Comparison>, onOpen: () -> Unit) {
    Card(COL_W, PAGE_H, onClick = onOpen) {
        Column(Modifier.fillMaxSize()) {
            CardLabel(tr("기간별 주행거리 비교", "Period comparison"), openable = true)
            val nothing = comparisons.none { it.hasBefore } && comparisons.all { it.now <= 0.0 }
            if (nothing) {
                Spacer(Modifier.weight(1f))
                EmptyNote(tr("두 기간 이상 기록되면 비교가 표시됩니다", "Appears once two periods are recorded"))
            } else {
                Row(Modifier.fillMaxWidth().padding(top = u(8)), horizontalArrangement = Arrangement.End) {
                    LegendSquare(Palette.Gray, tr("이전", "Before"))
                    Spacer(Modifier.width(u(26)))
                    LegendSquare(Palette.Blue, tr("이번", "Now"))
                }
                Spacer(Modifier.weight(1f))
                Row { comparisons.forEachIndexed { i, c -> CompareGroup(c, comparisonLabels[i], 183, 540) } }
            }
        }
    }
}

/* Weekly pattern. Inner 733 x 878. Large: 52 + heatmap 7x76 + 6x10 + 58 = 650 + 24 + legend 52 = 778. */
@Composable
private fun PatternCard(heat: Array<DoubleArray>, peak: Int?, empty: Boolean, onOpen: () -> Unit) {
    Card(COL_W, PAGE_H, onClick = onOpen) {
        Column(Modifier.fillMaxSize()) {
            CardLabel(tr("요일·시간대 주행 패턴", "Weekly pattern"), openable = true)
            Spacer(Modifier.weight(1f))
            if (empty) {
                EmptyNote()
            } else {
                Heatmap(heat, peak, 82, 76, 10)
                Spacer(Modifier.height(u(24)))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LegendSquare(Palette.Blue, tr("진할수록 많이 달림", "Darker = more km"))
                    if (peak != null) {
                        Spacer(Modifier.width(u(30)))
                        LegendSquare(Palette.Peak, tr("업무 최다 시간대", "Top business slot"))
                    }
                }
            }
        }
    }
}

/**
 * The grade is the message: big letter, score beside it. Deductions sit small at the bottom,
 * one line at a time. They rotate only while parked (gear P); while driving the line stays on
 * the largest deduction so nothing on screen moves on its own.
 */
@Composable
fun TrustSummary(trust: Trust, empty: Boolean, parked: Boolean) {
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(u(6)))
        Row(verticalAlignment = Alignment.Bottom) {
            if (empty && trust.signatureOk) {
                Text(tr("기록 전", "No trips yet"), fontSize = fs(64), fontWeight = FontWeight.ExtraBold, color = Palette.Muted, maxLines = 1)
            } else {
                FitText(trust.grade, 170, 100, 360, FontWeight.Black, gradeColor(trust))
                Spacer(Modifier.width(u(22)))
                Row(Modifier.padding(bottom = u(26)), verticalAlignment = Alignment.Bottom) {
                    Text("${trust.score}", fontSize = fs(60), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, lineHeight = fs(64), maxLines = 1, softWrap = false)
                    Text(" / 100", fontSize = fs(34), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = u(8)))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        val issues = if (empty) emptyList() else trust.issues
        if (issues.isEmpty()) {
            IssueLine(Status.OK, if (empty) tr("서명 검증 준비 완료", "Signing ready") else tr("감점 없음 · 서명 검증 통과", "No deductions · signatures OK"), null, 0, 0)
        } else {
            var index by remember(issues.size) { mutableIntStateOf(0) }
            LaunchedEffect(parked, issues.size) {
                index = 0
                if (parked && issues.size > 1) {
                    while (true) {
                        delay(4_000L)
                        index = (index + 1) % issues.size
                    }
                }
            }
            Crossfade(targetState = index.coerceIn(0, issues.lastIndex), animationSpec = tween(450), label = "trustIssue") { i ->
                val issue = issues[i]
                IssueLine(
                    if (issue.kind == TrustIssueKind.SIGNATURE) Status.BAD else Status.WARN,
                    issueTitle(issue, trust),
                    if (issue.kind == TrustIssueKind.SIGNATURE) null else "−${issue.penalty}" + tr("점", ""),
                    i,
                    issues.size
                )
            }
        }
    }
}

/** Compact deduction line with page dots when there is more than one. */
@Composable
fun IssueLine(status: Status, title: String, trailing: String?, index: Int, count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(u(72))
            .background(
                when (status) {
                    Status.OK -> Palette.OkSoft
                    Status.WARN -> Palette.WarnSoft
                    Status.BAD -> Palette.BadSoft
                },
                RoundedCornerShape(u(16))
            )
            .padding(horizontal = u(16)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusIcon(status, 32)
        Spacer(Modifier.width(u(12)))
        Text(title, fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Spacer(Modifier.width(u(10)))
            Text(trailing, fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.ExtraBold, color = Palette.Warn, maxLines = 1)
        }
        if (count > 1) {
            Spacer(Modifier.width(u(14)))
            Column(verticalArrangement = Arrangement.spacedBy(u(6))) {
                for (k in 0 until count.coerceAtMost(5)) {
                    Box(
                        Modifier
                            .size(u(8))
                            .background(if (k == index) Palette.Warn else Palette.Gray, RoundedCornerShape(u(4)))
                    )
                }
            }
        }
    }
}

/** Legend entry: name on one line, share below it in large type. */
@Composable
fun Legend(color: Color, label: String, share: Double) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(u(20)).background(color, RoundedCornerShape(u(10))))
            Spacer(Modifier.width(u(12)))
            Text(label, fontSize = fs(32), color = Palette.Sub, maxLines = 1, softWrap = false)
        }
        Text(
            Fmt.pct0(if (share.isNaN()) 0.0 else share),
            fontSize = fs(54),
            fontWeight = FontWeight.ExtraBold,
            color = Palette.Ink,
            lineHeight = fs(60),
            maxLines = 1,
            modifier = Modifier.padding(start = u(32))
        )
    }
}

@Composable
fun LegendSquare(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(u(22)).background(color, RoundedCornerShape(u(6))))
        Spacer(Modifier.width(u(10)))
        Text(label, fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub)
    }
}
