// ver 1.4.1 | 2026-11-04 20:50
package io.tripsign.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import io.tripsign.app.core.TripSearch
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.tripsign.app.core.Analytics
import io.tripsign.app.core.ChainCheck
import io.tripsign.app.core.DistanceSource
import io.tripsign.app.core.Gear
import io.tripsign.app.core.Period
import io.tripsign.app.core.Purpose
import io.tripsign.app.core.TripView
import io.tripsign.app.core.ViewKind
import io.tripsign.app.data.LiveState
import io.tripsign.app.data.Settings
import io.tripsign.app.data.tr
import java.time.ZoneId

@Composable
fun TripsScreen(
    views: List<TripView>,
    check: ChainCheck,
    live: LiveState,
    demo: Boolean,
    anchorMs: Long,
    zone: ZoneId,
    filter: TripFilter,
    parked: Boolean,
    onFilter: (TripFilter) -> Unit,
    onBack: () -> Unit,
    onClassify: (String, Purpose) -> Unit
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            NavChip(tr("운행기록부", "Logbook"), back = true, onClick = onBack)
            Spacer(Modifier.width(u(24)))
            Text(tr("운행 기록", "Trips"), fontSize = fs(52), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, softWrap = false)
            if (demo) {
                Spacer(Modifier.width(u(20)))
                Chip(tr("예시 데이터", "Sample data"), Palette.WarnSoft, Palette.Warn)
            }
            Spacer(Modifier.weight(1f))
            val ok = check.ok
            Chip(
                if (ok) tr("서명 검증 통과 · ${Fmt.count(check.count)}건 · ${check.headHash.take(8)}", "Signatures OK · ${Fmt.count(check.count)} · ${check.headHash.take(8)}")
                else tr("검증 실패 #${check.firstBadSeq}", "Verification failed #${check.firstBadSeq}"),
                if (ok) Palette.OkSoft else Palette.BadSoft,
                if (ok) Palette.Ok else Palette.Bad
            )
        }
        Spacer(Modifier.height(u(18)))
        LiveStrip(live, demo)
        Spacer(Modifier.height(u(18)))
        // Filters (88 px buttons) and the search box. The search box sits on the same line only
        // where it still gets ~400 px (Korean, normal text); otherwise it takes its own line.
        val inline = !Settings.isEnglish && Settings.textScale == 1f
        Row(Modifier.fillMaxWidth().height(u(100)), verticalAlignment = Alignment.CenterVertically) {
            Segmented(
                listOf(tr("전체", "All"), tr("오늘", "Today"), tr("이번 주", "Week"), tr("이번 달", "Month")),
                filter.period.ordinal,
                96,
                14
            ) { onFilter(filter.copy(period = FilterPeriod.entries[it])) }
            Spacer(Modifier.width(u(24)))
            Segmented(
                listOf(tr("전체", "All"), purposeLabel(Purpose.BUSINESS), purposeLabel(Purpose.COMMUTE), purposeLabel(Purpose.PERSONAL), tr("미분류", "Unlabeled")),
                filter.purpose.ordinal,
                96,
                14
            ) { onFilter(filter.copy(purpose = FilterPurpose.entries[it])) }
            if (inline) {
                Spacer(Modifier.width(u(24)))
                SearchBox(filter, parked, onFilter, Modifier.weight(1f))
            }
        }
        if (!inline) {
            Spacer(Modifier.height(u(8)))
            Row(Modifier.fillMaxWidth().height(u(100)), verticalAlignment = Alignment.CenterVertically) {
                SearchBox(filter, parked, onFilter, Modifier.weight(1f))
            }
        }
        val rows = applyFilter(views, filter, anchorMs, zone).asReversed()
        val trips = rows.filter { it.kind == ViewKind.TRIP }
        Spacer(Modifier.height(u(12)))
        Row(Modifier.fillMaxWidth().height(u(44)), verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("운행 ${Fmt.count(trips.size)}건 · ${Fmt.km1(trips.sumOf { it.distanceKm })} km", Fmt.count(trips.size) + plural(trips.size, " trip", " trips") + " · ${Fmt.km1(trips.sumOf { it.distanceKm })} km"),
                fontSize = fs(Sz.TEXT_MIN),
                fontWeight = FontWeight.Bold,
                color = Palette.Sub,
                maxLines = 1
            )
            if (filter != TripFilter()) {
                Spacer(Modifier.width(u(20)))
                Text(tr("필터 적용 중", "Filtered"), fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Blue, maxLines = 1)
            }
            if (filter.search.isNotBlank()) {
                val q = TripSearch.parse(filter.search, anchorMs, zone)
                Spacer(Modifier.width(u(20)))
                FitTextFill(
                    if (q.unknown.isNotEmpty()) tr("알 수 없는 조건: ", "Not understood: ") + q.unknown.joinToString(" ")
                    else tr("검색: ", "Search: ") + TripSearch.describe(q, Settings.isEnglish),
                    Sz.TEXT_MIN,
                    24,
                    FontWeight.Bold,
                    if (q.unknown.isNotEmpty()) Palette.Warn else Palette.Blue,
                    Modifier.weight(1f, fill = false)
                )
            }
        }
        Spacer(Modifier.height(u(12)))
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (views.isEmpty()) {
                        Text(tr("아직 기록된 운행이 없습니다", "No trips recorded yet"), fontSize = fs(44), fontWeight = FontWeight.ExtraBold, color = Palette.Muted)
                        Spacer(Modifier.height(u(14)))
                        Text(
                            tr("D로 출발했다가 P로 정차하면 자동으로 기록됩니다", "Drive off in D and park in P. The trip is logged automatically."),
                            fontSize = fs(34),
                            color = Palette.Sub,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(tr("조건에 맞는 운행이 없습니다", "No trips match"), fontSize = fs(44), fontWeight = FontWeight.ExtraBold, color = Palette.Muted)
                    }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(u(14))) {
                items(rows, key = { it.id }) { v -> TripRow(v, demo, onClassify) }
            }
        }
    }
}

@Composable
private fun LinkDot(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(u(20)).background(if (on) Palette.Ok else Palette.Bad, RoundedCornerShape(u(10))))
        Spacer(Modifier.width(u(10)))
        Text(label, fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
    }
}

@Composable
private fun LiveStrip(live: LiveState, demo: Boolean) {
    val gear = when (live.gear) {
        Gear.PARK -> "P"
        Gear.REVERSE -> "R"
        Gear.NEUTRAL -> "N"
        Gear.DRIVE -> "D"
        Gear.UNKNOWN -> "?"
    }
    val state = when {
        demo -> tr("예시 데이터 보는 중 · 실제 기록은 계속됨", "Viewing sample data · recording continues")
        !live.serviceRunning -> tr("기록 서비스 시작 중", "Starting recorder")
        live.driving -> tr("주행 기록 중 · ", "Recording · ") + Fmt.km1(live.tripKm) + " km"
        else -> tr("대기 중", "Waiting")
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(u(Sz.TOUCH))
            .background(Palette.Card, RoundedCornerShape(u(24)))
            .padding(horizontal = u(28)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tr("기어 ", "Gear ") + gear, fontSize = fs(34), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
        Spacer(Modifier.width(u(28)))
        Text(tr("계기판 ", "Odometer ") + (live.odometerKm?.let { Fmt.km1(it) + " km" } ?: tr("확인 중", "reading")), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
        Spacer(Modifier.width(u(28)))
        Text(state, fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Blue, maxLines = 1)
        Spacer(Modifier.weight(1f))
        LinkDot(tr("차량", "Car"), live.carConnected)
        Spacer(Modifier.width(u(24)))
        LinkDot(tr("주행거리", "Distance"), live.odometerConnected)
    }
}

@Composable
private fun TripRow(v: TripView, demo: Boolean, onClassify: (String, Purpose) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(u(140))
            .background(Palette.Card, RoundedCornerShape(u(24)))
            .padding(horizontal = u(28)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(u(330))) {
            Text(Fmt.day(v.startMs), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
            Text(
                if (v.kind == ViewKind.GAP) tr("앱이 기록하지 않은 사이", "Not recorded by the app") else Fmt.time(v.startMs) + " – " + Fmt.time(v.endMs),
                fontSize = fs(34),
                fontWeight = FontWeight.Bold,
                color = Palette.Ink,
                maxLines = 1
            )
        }
        Column(Modifier.width(u(470))) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(Fmt.km1(v.distanceKm), fontSize = fs(52), fontWeight = FontWeight.ExtraBold, color = if (v.kind == ViewKind.GAP) Palette.Down else Palette.Ink)
                Text(" km", fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Sub, modifier = Modifier.padding(bottom = u(8)))
                Spacer(Modifier.width(u(14)))
                val tag = when {
                    v.kind == ViewKind.GAP -> tr("미기록 구간", "Unrecorded")
                    v.source == DistanceSource.ODOMETER -> tr("계기판", "Odometer")
                    else -> tr("속도 추정", "From speed")
                }
                Text(tag, fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = if (v.kind == ViewKind.GAP) Palette.Down else Palette.Sub, maxLines = 1, modifier = Modifier.padding(bottom = u(10)))
            }
            val odo = if (v.startOdoKm != null && v.endOdoKm != null) Fmt.km1(v.startOdoKm) + " → " + Fmt.km1(v.endOdoKm) + " km" else tr("계기판 값 없음", "No odometer value")
            FitText(odo, Sz.TEXT_MIN, 24, 460, FontWeight.Normal, Palette.Sub)
        }
        Spacer(Modifier.weight(1f))
        if (v.kind == ViewKind.TRIP) {
            if (v.purposeChanges > 0) {
                Text(if (Settings.isEnglish) "Changed ${v.purposeChanges}×" else "변경 ${v.purposeChanges}회", fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Down, maxLines = 1)
                Spacer(Modifier.width(u(18)))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(u(14))) {
                Purpose.entries.forEach { p ->
                    val selected = v.purpose == p
                    Box(
                        Modifier
                            .width(u(168))
                            .height(u(96))
                            .background(if (selected) Palette.Blue else Palette.Tile, RoundedCornerShape(u(22)))
                            .clickable(enabled = !demo && !selected) { onClassify(v.id, p) },
                        contentAlignment = Alignment.Center
                    ) {
                        FitText(purposeLabel(p), Sz.BUTTON, 28, 150, FontWeight.ExtraBold, if (selected) Palette.OnPrimary else Palette.Ink)
                    }
                }
            }
        } else {
            Text(tr("업무로 집계되지 않습니다", "Never counted as business"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Down, maxLines = 1)
        }
    }
}

enum class FilterPeriod { ALL, TODAY, WEEK, MONTH }
enum class FilterPurpose { ALL, BUSINESS, COMMUTE, PERSONAL, UNLABELED }
/** [search] is the applied search text (date / time / distance, see core TripSearch). */
data class TripFilter(val period: FilterPeriod = FilterPeriod.ALL, val purpose: FilterPurpose = FilterPurpose.ALL, val search: String = "")

/** Unrecorded gaps only show under "all purposes"; they are never a purpose of their own. */
fun applyFilter(views: List<TripView>, f: TripFilter, anchorMs: Long, zone: ZoneId): List<TripView> {
    val range = when (f.period) {
        FilterPeriod.ALL -> null
        FilterPeriod.TODAY -> Analytics.range(Period.DAY, anchorMs, zone)
        FilterPeriod.WEEK -> Analytics.range(Period.WEEK, anchorMs, zone)
        FilterPeriod.MONTH -> Analytics.range(Period.MONTH, anchorMs, zone)
    }
    return views.filter { v ->
        val inRange = range == null || (v.startMs >= range.fromMs && v.startMs < range.toMs)
        val purposeOk = when (f.purpose) {
            FilterPurpose.ALL -> true
            FilterPurpose.BUSINESS -> v.kind == ViewKind.TRIP && v.purpose == Purpose.BUSINESS
            FilterPurpose.COMMUTE -> v.kind == ViewKind.TRIP && v.purpose == Purpose.COMMUTE
            FilterPurpose.PERSONAL -> v.kind == ViewKind.TRIP && v.purpose == Purpose.PERSONAL
            FilterPurpose.UNLABELED -> v.kind == ViewKind.TRIP && v.purpose == null
        }
        inRange && purposeOk
    }.let { list ->
        if (f.search.isBlank()) list else {
            val q = TripSearch.parse(f.search, anchorMs, zone)
            list.filter { TripSearch.matches(it, q, zone) }
        }
    }
}

/**
 * Search box + button. Typing is only allowed while parked (gear P): the head unit blocks the
 * keyboard while driving anyway, and the box says so instead of silently not opening.
 */
@Composable
private fun SearchBox(filter: TripFilter, parked: Boolean, onFilter: (TripFilter) -> Unit, modifier: Modifier) {
    var text by remember(filter.search) { mutableStateOf(filter.search) }
    val focus = LocalFocusManager.current
    fun apply() {
        focus.clearFocus()
        onFilter(filter.copy(search = text.trim()))
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(u(Sz.TOUCH))
                .background(if (parked) Palette.Card else Palette.Seg, RoundedCornerShape(u(22)))
                .border(u(2), Palette.Line, RoundedCornerShape(u(22)))
                .padding(start = u(24)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                enabled = parked,
                singleLine = true,
                textStyle = TextStyle(fontSize = fs(32), fontWeight = FontWeight.Bold, color = Palette.Ink),
                cursorBrush = SolidColor(Palette.Blue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { apply() }),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) {
                            Text(
                                if (parked) tr("예: 10/30 · 18:20 · 20km", "e.g. 10/30 · 18:20 · 20km") else tr("정차(P) 중에만 입력", "Type only in P"),
                                fontSize = fs(Sz.TEXT_MIN),
                                color = Palette.Muted,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                        inner()
                    }
                }
            )
            if (text.isNotEmpty() || filter.search.isNotEmpty()) {
                // clear: a full 88 px square, not a small x
                Box(
                    Modifier
                        .size(u(Sz.TOUCH))
                        .clickable {
                            text = ""
                            focus.clearFocus()
                            onFilter(filter.copy(search = ""))
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("✕", fontSize = fs(34), fontWeight = FontWeight.Bold, color = Palette.Sub)
                }
            }
        }
        Spacer(Modifier.width(u(12)))
        Box(
            Modifier
                .height(u(Sz.TOUCH))
                .widthIn(min = u(140))
                .background(Palette.Primary, RoundedCornerShape(u(22)))
                .clickable { apply() }
                .padding(horizontal = u(26)),
            contentAlignment = Alignment.Center
        ) {
            Text(tr("검색", "Search"), fontSize = fs(Sz.BUTTON), fontWeight = FontWeight.ExtraBold, color = Palette.OnPrimary, maxLines = 1, softWrap = false)
        }
    }
}
