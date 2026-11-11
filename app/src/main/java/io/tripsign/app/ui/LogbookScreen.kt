// ver 1.6.3 | 2026-11-06 20:18
package io.tripsign.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import io.tripsign.app.core.Driver
import io.tripsign.app.core.FiscalYear
import io.tripsign.app.core.LogRow
import io.tripsign.app.core.LogTotals
import io.tripsign.app.core.Purpose
import io.tripsign.app.core.ViewKind
import io.tripsign.app.data.Settings
import io.tripsign.app.data.tr
import java.time.YearMonth
import java.util.Locale

/** Table filter from the alert chips above the table. */
enum class RowFilter { ALL, UNCLASSIFIED, GAP, UNVERIFIED }

/**
 * Column widths (design px) of the logbook table: ③ ④ ⑤ ⑥ ⑦ ⑧ ⑨, then 구분 takes the rest.
 * Budget (inner width 1562): 190+190+170+170+178+178+178 + 7 gaps x 12 = 1338, so 구분 gets 224,
 * enough for "분류하기" at large text (41 px x 4 + 36 padding = 200). ⑤⑥ hold "999,999" (7 x 19 + 22 = 155).
 * ⑦⑧⑨ are wider so their header labels keep the same size as the others (seen shrunk on the emulator, 11/5).
 */
private val COLS = intArrayOf(190, 190, 170, 170, 178, 178, 178)
private const val GAP = 12

/**
 * Home: the 국세청 업무용승용차 운행기록부 itself (v0.4 refocus).
 * Numbered badges ①~⑬ are the form's own field numbers, so the screen maps 1:1 to the paper form.
 * Integer rule: ⑤⑥ floored odometer readings, ⑦ = ⑥ − ⑤; no decimals anywhere.
 */
@Composable
fun LogbookScreen(
    month: YearMonth,
    monthRows: List<LogRow>,
    monthTotals: LogTotals,
    fyTotals: LogTotals,
    fy: FiscalYear,
    canPrev: Boolean,
    canNext: Boolean,
    filter: RowFilter,
    demo: Boolean,
    onMonth: (Int) -> Unit,
    onFilter: (RowFilter) -> Unit,
    onPurpose: (LogRow) -> Unit,
    onDriver: (LogRow) -> Unit,
    onSearch: () -> Unit,
    onExport: () -> Unit,
    onSettings: () -> Unit,
    driver: Driver? = null,
    onWho: () -> Unit = {}
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        // header
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("운행기록부", "Logbook"), fontSize = fs(56), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(u(26)))
            FitTextFill(fyLabel(fy), 32, 24, FontWeight.SemiBold, Palette.Sub, Modifier.weight(1f))
            if (demo) {
                Spacer(Modifier.width(u(16)))
                Chip(tr("예시 데이터", "Sample data"), Palette.WarnSoft, Palette.Warn)
            }
            Spacer(Modifier.width(u(16)))
            WhoChip(driver, onWho)
            Spacer(Modifier.width(u(16)))
            Box(Modifier.semantics { contentDescription = "설정" }) {
                IconButton96(onClick = onSettings) { GearIcon(Palette.Sub, 48) }
            }
        }
        Spacer(Modifier.height(u(22)))
        // ⑪ ⑫ ⑬ for the fiscal year that holds the shown month
        Row(horizontalArrangement = Arrangement.spacedBy(u(20))) {
            Tile(11, tr("총 주행거리", "Total distance"), Fmt.n(fyTotals.km), "km", Modifier.weight(1f))
            Tile(12, tr("업무용 사용거리", "Business use"), Fmt.n(fyTotals.workKm), "km", Modifier.weight(1f))
            Tile(13, tr("업무사용비율", "Business ratio"), fyTotals.ratioPct?.let { String.format(Locale.US, "%.1f", it) } ?: "–", "%", Modifier.weight(1f))
        }
        Spacer(Modifier.height(u(22)))
        // table
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Palette.Card, RoundedCornerShape(u(28)))
                .padding(start = u(28), end = u(28), top = u(14))
        ) {
            Row(Modifier.fillMaxWidth().height(u(96)), verticalAlignment = Alignment.CenterVertically) {
                MonthSelector(month, canPrev, canNext, onMonth)
                Spacer(Modifier.weight(1f))
                if (filter != RowFilter.ALL) {
                    AlertChip(tr("전체 보기", "Show all"), warn = false) { onFilter(RowFilter.ALL) }
                } else {
                    if (monthTotals.gapCount > 0) {
                        AlertChip(tr("미기록 ${Fmt.n(monthTotals.gapKm)} km", "Unrecorded ${Fmt.n(monthTotals.gapKm)} km"), warn = true) { onFilter(RowFilter.GAP) }
                    }
                    if (monthTotals.unclassifiedCount > 0) {
                        if (monthTotals.gapCount > 0) Spacer(Modifier.width(u(16)))
                        AlertChip(tr("미분류 ${monthTotals.unclassifiedCount}건", "${monthTotals.unclassifiedCount} unclassified"), warn = true) { onFilter(RowFilter.UNCLASSIFIED) }
                    }
                    if (monthTotals.unverifiedCount > 0) {
                        if (monthTotals.gapCount > 0 || monthTotals.unclassifiedCount > 0) Spacer(Modifier.width(u(16)))
                        AlertChip(tr("미인증 ${monthTotals.unverifiedCount}건", "${monthTotals.unverifiedCount} unverified"), warn = true) { onFilter(RowFilter.UNVERIFIED) }
                    }
                }
                // search and send live with the month they act on (moved down from the header)
                Spacer(Modifier.width(u(16)))
                HeaderButton(tr("검색", "Search"), filled = false, onClick = onSearch)
                Spacer(Modifier.width(u(16)))
                HeaderButton(tr("QR로 보내기", "Send by QR"), filled = true, onClick = onExport)
            }
            HeaderRow()
            val shown = when (filter) {
                RowFilter.ALL -> monthRows
                RowFilter.UNCLASSIFIED -> monthRows.filter { it.unclassified }
                RowFilter.GAP -> monthRows.filter { it.kind == ViewKind.GAP }
                RowFilter.UNVERIFIED -> monthRows.filter { it.unverified }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    EmptyNote(tr("이 달에는 기록이 없습니다", "No records this month"))
                }
            } else {
                // newest first, like a car screen list; the form order is kept in the Excel export
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(shown.asReversed(), key = { it.id }) { r ->
                        LogRowView(r, onPurpose = { onPurpose(r) }, onDriver = { onDriver(r) })
                    }
                }
            }
            SumRow(monthTotals)
        }
    }
}

/** Short on purpose: the header has to hold it next to the buttons even at large text (about 16 characters). */
fun fyLabel(fy: FiscalYear): String {
    val f = fy.first
    val l = fy.last
    return if (Settings.isEnglish) {
        "FY${fy.startYear} · ${monthLabel(f.monthValue)}–${monthLabel(l.monthValue)}${if (l.year != f.year) " ${l.year}" else ""}"
    } else {
        "${fy.startYear} 사업연도 · ${f.monthValue}월~${if (l.year != f.year) "${l.year}년 " else ""}${l.monthValue}월"
    }
}

/** Who is logged in for this drive, or an amber "nobody" chip. Fixed width: the header never reflows. */
@Composable
private fun WhoChip(driver: Driver?, onClick: () -> Unit) {
    if (driver != null) {
        Row(
            Modifier
                .width(u(380))
                .height(u(96))
                .border(u(3), Palette.ChipLine, RoundedCornerShape(u(30)))
                .clickable { onClick() }
                .padding(horizontal = u(26)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FitTextFill(driver.name, 36, 24, FontWeight.ExtraBold, Palette.Ink, Modifier.weight(1f))
            Spacer(Modifier.width(u(10)))
            Box(Modifier.width(u(2)).height(u(44)).background(Palette.Line))
            Spacer(Modifier.width(u(14)))
            Text(tr("바꾸기", "Change"), fontSize = fs(32), fontWeight = FontWeight.ExtraBold, color = Palette.Blue, maxLines = 1, softWrap = false)
        }
    } else {
        Box(
            Modifier
                .width(u(380))
                .height(u(96))
                .background(Palette.WarnSoft, RoundedCornerShape(u(30)))
                .clickable { onClick() }
                .padding(horizontal = u(26)),
            contentAlignment = Alignment.Center
        ) {
            FitTextFill(tr("로그인 안 됨 · 로그인하기", "Nobody logged in · log in"), 34, 22, FontWeight.ExtraBold, Palette.Warn)
        }
    }
}

@Composable
private fun HeaderButton(text: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .height(u(96))
            .widthIn(min = u(140))
            .background(if (filled) Palette.Primary else Palette.Seg, RoundedCornerShape(u(30)))
            .clickable { onClick() }
            .padding(horizontal = u(36)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = fs(38), fontWeight = FontWeight.ExtraBold, color = if (filled) Palette.OnPrimary else Palette.Ink, maxLines = 1, softWrap = false)
    }
}

/** Form field number, drawn as a neutral grey disc (not a button). */
@Composable
fun FormBadge(n: Int, sizePx: Int = 50) {
    Box(
        Modifier.size(u(sizePx)).background(Palette.BadgeBg, RoundedCornerShape(u(sizePx / 2))),
        contentAlignment = Alignment.Center
    ) {
        Text(n.toString(), fontSize = fs(if (sizePx >= 50) 30 else 26), fontWeight = FontWeight.ExtraBold, color = Palette.Sub, maxLines = 1, softWrap = false, tnum = true)
    }
}

@Composable
private fun Tile(n: Int, label: String, value: String, unit: String, modifier: Modifier) {
    Column(
        modifier
            .height(u(168))
            .background(Palette.Card, RoundedCornerShape(u(28)))
            // 16 (not 22): the 74 px number needs ~89 px of line box under the 44 px label row
            .padding(horizontal = u(30), vertical = u(16)),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FormBadge(n)
            Spacer(Modifier.width(u(10)))
            FitTextFill(label, 30, 22, FontWeight.Bold, Palette.Sub, Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            FitTextFill(value, 74, 40, FontWeight.ExtraBold, Palette.Ink, Modifier.weight(1f, fill = false), tnum = true)
            Spacer(Modifier.width(u(10)))
            Text(unit, fontSize = fs(34), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = u(8)))
        }
    }
}

@Composable
private fun MonthSelector(month: YearMonth, canPrev: Boolean, canNext: Boolean, onMonth: (Int) -> Unit) {
    Row(
        Modifier.height(u(Sz.TOUCH)).background(Palette.Seg, RoundedCornerShape(u(24))),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(u(Sz.TOUCH)).semantics { contentDescription = "이전 달" }.then(if (canPrev) Modifier.clickable { onMonth(-1) } else Modifier),
            contentAlignment = Alignment.Center
        ) { Chevron(if (canPrev) Palette.Sub else Palette.Gray, 34, back = true) }
        Text(
            if (Settings.isEnglish) "${monthLabel(month.monthValue)} ${month.year}" else "${month.year}년 ${month.monthValue}월",
            fontSize = fs(34), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, softWrap = false, tnum = true,
            modifier = Modifier.padding(horizontal = u(10))
        )
        Box(
            Modifier.size(u(Sz.TOUCH)).semantics { contentDescription = "다음 달" }.then(if (canNext) Modifier.clickable { onMonth(1) } else Modifier),
            contentAlignment = Alignment.Center
        ) { Chevron(if (canNext) Palette.Sub else Palette.Gray, 34) }
    }
}

@Composable
private fun AlertChip(text: String, warn: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(u(Sz.TOUCH))
            .background(if (warn) Palette.WarnSoft else Palette.Seg, RoundedCornerShape(u(24)))
            .clickable { onClick() }
            .padding(start = u(28), end = u(18)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, fontSize = fs(32), fontWeight = FontWeight.ExtraBold, color = if (warn) Palette.Warn else Palette.Ink, maxLines = 1, softWrap = false)
        Chevron(if (warn) Palette.Warn else Palette.Sub, 34)
    }
}

@Composable
private fun Cell(index: Int, content: @Composable () -> Unit) {
    Box(Modifier.width(u(COLS[index])), contentAlignment = Alignment.CenterStart) { content() }
}

@Composable
private fun HeaderLabel(n: Int?, text: String, end: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(end = u(if (end) 22 else 0)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (end) Arrangement.End else Arrangement.Start
    ) {
        if (n != null) {
            FormBadge(n, 44)
            Spacer(Modifier.width(u(6)))
        }
        // one size for every header label (25 px = 24.6 dp, the secondary-text minimum); shrinks only at large text
        FitTextFill(text, 25, 20, FontWeight.Bold, Palette.Muted, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun HeaderRow() {
    Row(
        Modifier.fillMaxWidth().height(u(72)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(GAP))
    ) {
        Cell(0) { HeaderLabel(3, tr("사용일자", "Date"), false) }
        Cell(1) { HeaderLabel(4, tr("사용자", "User"), false) }
        Cell(2) { HeaderLabel(5, tr("주행 전", "Start"), true) }
        Cell(3) { HeaderLabel(6, tr("주행 후", "End"), true) }
        Cell(4) { HeaderLabel(7, tr("주행거리", "Distance"), true) }
        Cell(5) { HeaderLabel(8, tr("출·퇴근", "Commute"), true) }
        Cell(6) { HeaderLabel(9, tr("일반 업무", "Business"), true) }
        Box(Modifier.weight(1f)) { HeaderLabel(null, tr("구분", "Purpose"), false) }
    }
    Box(Modifier.fillMaxWidth().height(u(2)).background(Palette.Line))
}

@Composable
private fun NumCell(index: Int, text: String, size: Int, weight: FontWeight, color: androidx.compose.ui.graphics.Color) {
    Cell(index) {
        FitTextFill(text, size, 22, weight, color, Modifier.fillMaxWidth().padding(end = u(22)), tnum = true, alignEnd = true)
    }
}

@Composable
private fun LogRowView(r: LogRow, onPurpose: () -> Unit, onDriver: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().height(u(106)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(u(GAP))
        ) {
            Cell(0) {
                Column {
                    FitTextFill(Fmt.day(r.startMs), 34, 24, FontWeight.Bold, Palette.Ink, tnum = true)
                    FitTextFill(Fmt.time(r.startMs) + "–" + Fmt.time(r.endMs), 30, 20, FontWeight.Normal, Palette.Muted, tnum = true)
                }
            }
            Cell(1) {
                val d = r.driver
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = u(Sz.TOUCH))
                        .then(if (r.kind == ViewKind.TRIP) Modifier.clickable { onDriver() } else Modifier),
                    verticalArrangement = Arrangement.Center
                ) {
                    if (d != null) {
                        FitTextFill(d.name, 34, 22, FontWeight.Bold, Palette.Ink)
                        // a 미인증 운행 keeps its mark after a later phone check
                        if (r.unverified) FitTextFill(tr("미인증 · 사후 확인", "Unverified · checked later"), 26, 18, FontWeight.Bold, Palette.Bad)
                        else FitTextFill(d.dept.ifEmpty { "–" }, 30, 20, FontWeight.Normal, Palette.Muted)
                    } else if (r.kind == ViewKind.TRIP) {
                        // 0.5.2a: 미인증 운행 has one place to act — the 구분 button opens what to do
                        if (r.unverified) FitTextFill(tr("미인증 운행", "Unverified"), 32, 22, FontWeight.ExtraBold, Palette.Bad)
                        else FitTextFill(tr("폰으로 확인", "Check by phone"), 30, 20, FontWeight.Bold, Palette.Blue)
                    } else {
                        FitTextFill("–", 34, 22, FontWeight.Bold, Palette.Muted)
                    }
                }
            }
            NumCell(2, r.odoBefore?.let { Fmt.n(it) } ?: "–", 34, FontWeight.Medium, Palette.Sub)
            NumCell(3, r.odoAfter?.let { Fmt.n(it) } ?: "–", 34, FontWeight.Medium, Palette.Sub)
            NumCell(4, Fmt.n(r.km), 38, FontWeight.ExtraBold, Palette.Ink)
            NumCell(5, r.commuteKm?.let { Fmt.n(it) } ?: "–", 34, FontWeight.Bold, Palette.Ink)
            NumCell(6, r.businessKm?.let { Fmt.n(it) } ?: "–", 34, FontWeight.Bold, Palette.Ink)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                PurposeChip(r, onPurpose)
            }
        }
        Box(Modifier.fillMaxWidth().height(u(2)).background(Palette.Line))
    }
}

@Composable
private fun PurposeChip(r: LogRow, onClick: () -> Unit) {
    if (r.kind == ViewKind.GAP) {
        // 0.5.2e: same footprint and centring as the buttons in this column (nothing to press, so no outline)
        Box(Modifier.widthIn(min = u(150)).height(u(80)).padding(horizontal = u(18)), contentAlignment = Alignment.Center) {
            Text(tr("미기록", "Unrecorded"), fontSize = fs(34), fontWeight = FontWeight.ExtraBold, color = Palette.Warn, maxLines = 1, softWrap = false)
        }
        return
    }
    val p = r.purpose
    val shape = RoundedCornerShape(u(22))
    if (r.unverified) {
        // 0.5.2a: a button like the others (same size, centred). It opens what can be done: a phone check, and after
        // that the administrator's purpose. Until then the km never counts as 출퇴근/업무.
        Box(
            Modifier
                .widthIn(min = u(150))
                .height(u(80))
                .border(u(3), Palette.ChipLine, shape)
                .clickable { onClick() }
                .padding(horizontal = u(18)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (r.settledLater && p != null) purposeLabel(p) else tr("미인증", "Unverified"),
                fontSize = fs(34), fontWeight = FontWeight.ExtraBold,
                color = if (r.settledLater) Palette.Warn else Palette.Bad,
                maxLines = 1, softWrap = false
            )
        }
        return
    }
    Box(
        Modifier
            .widthIn(min = u(150))
            .height(u(80))
            .then(
                if (p == null) Modifier.background(Palette.Primary, shape)
                else Modifier.border(u(3), Palette.ChipLine, shape)
            )
            .clickable { onClick() }
            .padding(horizontal = u(18)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            when {
                p != null -> purposeLabel(p)
                r.purposeSkipped -> tr("미분류", "Unclassified")
                else -> tr("분류하기", "Classify")
            },
            fontSize = fs(34), fontWeight = FontWeight.ExtraBold,
            // an administrator's change shows in amber (the history is in the Excel and on the phone page)
            color = if (p == null) Palette.OnPrimary else if (r.adminEdited) Palette.Warn else Palette.Ink,
            maxLines = 1, softWrap = false
        )
    }
}

@Composable
private fun SumRow(t: LogTotals) {
    Box(Modifier.fillMaxWidth().height(u(3)).background(Palette.Gray))
    Row(
        Modifier.fillMaxWidth().height(u(100)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(u(GAP))
    ) {
        Cell(0) { Text(tr("합계", "Total"), fontSize = fs(36), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, softWrap = false) }
        Cell(1) { }
        Cell(2) { }
        Cell(3) { }
        NumCell(4, Fmt.n(t.km), 36, FontWeight.ExtraBold, Palette.Ink)
        NumCell(5, Fmt.n(t.commuteKm), 36, FontWeight.ExtraBold, Palette.Ink)
        NumCell(6, Fmt.n(t.businessKm), 36, FontWeight.ExtraBold, Palette.Ink)
        // ⑦ also holds personal, unclassified and unrecorded km; say so, or ⑦ ≠ ⑧ + ⑨ looks like an error
        val rest = buildList {
            if (t.personalKm > 0) add(tr("개인 ", "Personal ") + Fmt.n(t.personalKm))
            if (t.unclassifiedKm > 0) add(tr("미분류 ", "Unclassified ") + Fmt.n(t.unclassifiedKm))
            if (t.gapKm > 0) add(tr("미기록 ", "Unrecorded ") + Fmt.n(t.gapKm))
            if (t.unverifiedKm > 0) add(tr("미인증 ", "Unverified ") + Fmt.n(t.unverifiedKm))
        }
        // one item per line, broken on purpose (one line of three items did not fit in English)
        Column(Modifier.weight(1f).padding(start = u(6)), verticalArrangement = Arrangement.Center) {
            val size = if (rest.size >= 4) 20 else if (rest.size >= 3) 24 else 28
            rest.forEach { FitTextFill(it, size, 18, FontWeight.Bold, Palette.Sub, Modifier.fillMaxWidth(), tnum = true) }
        }
    }
}

/** 0.5.2a: Korean "(으)로" picked from the last syllable: 업무로 · 개인으로 · 출퇴근으로 · 홍길동으로. */
internal fun josaRo(word: String): String {
    val c = word.lastOrNull() ?: return word
    if (c !in '\uAC00'..'\uD7A3') return word + "(으)로"
    val jong = (c.code - 0xAC00) % 28
    return word + if (jong == 0 || jong == 8) "로" else "으로"
}

/**
 * 0.5.2a: what a 미인증 운행 needs, in one place. Before a phone check: [폰으로 확인] (only parked). After it:
 * the administrator may set the purpose (안 B); the 미인증 mark stays on the row and on the phone page.
 */
@Composable
fun UnverifiedSheet(r: LogRow, canPhone: Boolean, onPhone: () -> Unit, onAdmin: () -> Unit, onClose: () -> Unit) {
    PickerCard(
        title = Fmt.day(r.startMs) + "  " + Fmt.time(r.startMs) + "–" + Fmt.time(r.endMs) + "  ·  " + Fmt.n(r.km) + " km",
        onClose = onClose
    ) {
        Text(tr("미인증 운행", "Unverified trip"), fontSize = fs(46), fontWeight = FontWeight.ExtraBold, color = Palette.Bad, maxLines = 1)
        Spacer(Modifier.height(u(18)))
        val d = r.driver
        // broken on purpose: one line each
        val lines = if (!r.authConfirmed || d == null) listOf(
            tr("로그인하지 않고 출발한 운행입니다", "It left with nobody logged in"),
            tr("운전한 사람이 폰으로 확인하면 사용자가 기록됩니다", "The driver confirms by phone to be recorded")
        ) else listOf(
            tr("사후 확인: ", "Checked later: ") + d.name,
            tr("관리자가 구분을 정하면 업무 거리에 들어갑니다", "Counts as work once the administrator sets it"),
            tr("미인증 표시는 그대로 남습니다", "The unverified mark stays")
        )
        lines.forEach { FitTextFill(it, 34, 24, FontWeight.Medium, Palette.Sub, Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(u(30)))
        if (!r.authConfirmed || d == null) {
            if (canPhone) PrimaryButton(tr("폰으로 확인", "Check by phone"), 1012) { onPhone() }
            else FitTextFill(tr("차가 P에 서 있을 때 할 수 있습니다", "Only while parked (P)"), 32, 24, FontWeight.Bold, Palette.Warn, Modifier.fillMaxWidth())
        } else {
            PrimaryButton(if (r.purpose == null) tr("구분 정하기 · 관리자", "Set purpose · administrator") else tr("구분 바꾸기 · 관리자", "Change purpose · administrator"), 1012) { onAdmin() }
        }
    }
}

/** Purpose choice for one row (same three buttons as the arrival card). */
@Composable
fun PurposePicker(r: LogRow, onPick: (Purpose) -> Unit, onClose: () -> Unit, adminNeeded: Boolean = false) {
    var chosen by remember { mutableStateOf<Purpose?>(null) }
    val admin = Settings.adminDriver()
    val c = chosen
    if (adminNeeded && c != null && admin != null) {
        // 0.5.2: after the driver's window only the administrator changes a purpose; their name is signed with it
        Scrim()
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .width(u(1000))
                    .height(u(1080))
                    .background(Palette.Card, RoundedCornerShape(u(36)))
                    .padding(horizontal = u(60), vertical = u(44)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FitTextFill(tr("관리자 ${admin.name} · ${josaRo(purposeLabel(c))} 바꾸기", "Admin ${admin.name} · change to ${purposeLabel(c)}"), 36, 26, FontWeight.Bold, Palette.Sub, Modifier.weight(1f))
                    Box(
                        Modifier
                            .height(u(Sz.TOUCH))
                            .widthIn(min = u(160))
                            .background(Palette.Seg, RoundedCornerShape(u(24)))
                            .clickable { chosen = null }
                            .padding(horizontal = u(30)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(tr("뒤로", "Back"), fontSize = fs(32), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1)
                    }
                }
                Spacer(Modifier.height(u(20)))
                VerifyPinPad(Settings.admin.intValue, tr("관리자 번호 4자리", "Administrator's 4-digit number")) { onPick(c) }
            }
        }
        return
    }
    PickerCard(
        title = Fmt.day(r.startMs) + "  " + Fmt.time(r.startMs) + "–" + Fmt.time(r.endMs) + "  ·  " + Fmt.n(r.km) + " km",
        onClose = onClose
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(u(20))) {
            Purpose.entries.forEach { p ->
                val on = r.purpose == p
                Box(
                    Modifier
                        .width(u(324))
                        .height(u(130))
                        .background(if (on) Palette.Primary else Palette.Tile, RoundedCornerShape(u(30)))
                        .clickable { if (adminNeeded) { if (admin != null && !on) chosen = p } else onPick(p) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(purposeLabel(p), fontSize = fs(46), fontWeight = FontWeight.ExtraBold, color = if (on) Palette.OnPrimary else Palette.Ink, maxLines = 1, softWrap = false)
                }
            }
        }
        if (adminNeeded) {
            Spacer(Modifier.height(u(16)))
            Text(
                if (admin != null) when {
                    // 0.5.2a: say why the driver cannot choose (a skipped trip is not "time passed")
                    r.unverified -> tr("사후 확인된 미인증 운행입니다 · 관리자 번호로 정합니다", "Unverified, checked later · the administrator sets it")
                    r.purposeSkipped -> tr("건너뛴 운행입니다 · 관리자 번호로 바꿉니다", "Skipped at arrival · the administrator changes it")
                    r.adminEdited -> tr("관리자가 고친 운행입니다 · 관리자 번호로 바꿉니다", "Changed by the administrator · the administrator changes it")
                    else -> tr("운전자가 고를 수 있는 시간이 지났습니다 · 관리자 번호로 바꿉니다", "The driver's time has passed · the administrator changes it")
                }
                else tr("관리자가 없습니다 · 사용자를 먼저 등록하세요", "No administrator yet · register a user first"),
                fontSize = fs(Sz.TEXT_MIN), color = Palette.Warn, maxLines = 1
            )
        } else if (r.purpose != null) {
            Spacer(Modifier.height(u(16)))
            Text(tr("바꾸면 변경 이력이 서명되어 남습니다", "A change is signed and kept as history"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
        }
    }
}

/**
 * User (form ④) choice for one row, from the users registered in settings. With [verify], the chosen
 * person's own 4 digits are asked before the trip is put in their name (nobody can move a trip onto
 * or off someone else without them). A user without digits yet cannot be chosen until they set them.
 */
@Composable
fun DriverPicker(r: LogRow, drivers: List<Driver>, verify: Boolean, onPick: (Driver) -> Unit, onOpenSettings: () -> Unit, onClose: () -> Unit) {
    var asking by remember { mutableIntStateOf(-1) }
    val chosen = drivers.getOrNull(asking)
    if (verify && chosen != null) {
        AssignPinCard(r, chosen, asking, onOk = { onPick(chosen) }, onBack = { asking = -1 })
        return
    }
    PickerCard(title = tr("사용자 지정", "Assign user") + "  ·  " + Fmt.day(r.startMs) + " " + Fmt.time(r.startMs), onClose = onClose) {
        if (drivers.isEmpty()) {
            Text(tr("설정에서 사용자를 먼저 등록하세요", "Register users in Settings first"), fontSize = fs(36), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1)
            Spacer(Modifier.height(u(20)))
            PrimaryButton(tr("설정 열기", "Open Settings"), 420, onOpenSettings)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(u(14))) {
                drivers.withIndex().toList().chunked(3).forEach { line ->
                    Row(horizontalArrangement = Arrangement.spacedBy(u(20))) {
                        line.forEach { (i, d) ->
                            val on = r.driver == d
                            val ready = !verify || Settings.hasPin(i)
                            Column(
                                Modifier
                                    .width(u(324))
                                    .height(u(130))
                                    .background(if (on) Palette.Primary else Palette.Tile, RoundedCornerShape(u(30)))
                                    .clickable(enabled = ready && !on) { if (verify) asking = i else onPick(d) }
                                    .padding(horizontal = u(20)),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                FitTextFill(d.name, 42, 26, FontWeight.ExtraBold, if (on) Palette.OnPrimary else if (ready) Palette.Ink else Palette.Muted)
                                FitTextFill(if (ready) d.dept.ifEmpty { "–" } else tr("번호 미설정", "No number yet"), 30, 20, FontWeight.Bold, if (on) Palette.OnPrimary else if (ready) Palette.Sub else Palette.Muted)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(u(16)))
            if (verify) Text(tr("고른 사람이 자기 번호 4자리로 확인합니다", "The chosen person confirms with their 4-digit number"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
            Text(tr("바꾸면 변경 이력이 서명되어 남습니다", "A change is signed and kept as history"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
        }
    }
}

/** The chosen person's 4 digits before a trip is put in their name. Wrong digits clear; 뒤로 picks again. */
@Composable
private fun AssignPinCard(r: LogRow, d: Driver, index: Int, onOk: () -> Unit, onBack: () -> Unit) {
    Scrim()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(u(1000))
                .height(u(1080))
                .background(Palette.Card, RoundedCornerShape(u(36)))
                .padding(horizontal = u(60), vertical = u(44)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FitTextFill(tr("${d.name}에게 지정", "Assign to ${d.name}") + "  ·  " + Fmt.day(r.startMs) + " " + Fmt.time(r.startMs), 36, 26, FontWeight.Bold, Palette.Sub, Modifier.weight(1f))
                Box(
                    Modifier
                        .height(u(Sz.TOUCH))
                        .widthIn(min = u(160))
                        .background(Palette.Seg, RoundedCornerShape(u(24)))
                        .clickable { onBack() }
                        .padding(horizontal = u(30)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr("뒤로", "Back"), fontSize = fs(32), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1)
                }
            }
            Spacer(Modifier.height(u(20)))
            VerifyPinPad(index, tr("${d.name}의 번호 4자리", "${d.name}'s 4-digit number"), onOk)
        }
    }
}

@Composable
private fun PickerCard(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Scrim()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(u(1100))
                .background(Palette.Card, RoundedCornerShape(u(36)))
                .padding(horizontal = u(44), vertical = u(36))
        ) {
            FitText(title, 36, 26, 1012, FontWeight.Bold, Palette.Sub, tnum = true)
            Spacer(Modifier.height(u(24)))
            content()
            Spacer(Modifier.height(u(20)))
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .height(u(Sz.TOUCH))
                    .widthIn(min = u(200))
                    .clickable { onClose() }
                    .padding(horizontal = u(40)),
                contentAlignment = Alignment.Center
            ) {
                Text(tr("닫기", "Close"), fontSize = fs(34), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
            }
        }
    }
}
