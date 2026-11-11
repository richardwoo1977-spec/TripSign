// ver 1.3.0 | 2026-11-04 20:50
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import io.tripsign.app.core.Chain
import io.tripsign.app.core.ExportPack
import io.tripsign.app.core.FiscalYear
import io.tripsign.app.core.Fountain
import io.tripsign.app.core.QrCodec
import io.tripsign.app.core.RecordType
import io.tripsign.app.core.SealedEntry
import io.tripsign.app.core.Signer
import io.tripsign.app.data.DiagLog
import io.tripsign.app.data.Settings
import io.tripsign.app.data.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.Base64

/**
 * Sample data never uses the car's real key: it is signed by a throwaway key that lives only in memory,
 * so the real key only ever signs real recorded trips. The phone shows "예시 데이터" for these packs.
 */
private object DemoKeys : Signer {
    val pair: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
    val publicBase64: String get() = Base64.getEncoder().encodeToString(pair.public.encoded)
    override fun sign(data: ByteArray): String {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(pair.private)
        s.update(data)
        return Base64.getEncoder().encodeToString(s.sign())
    }
}

/** enc == null: nothing to send in this range. */
private class Prepared(val enc: Fountain.Encoder?, val entries: Int, val trips: Int, val bytes: Int, val fingerprint: String)

/**
 * What to send. The phone merges what it already has, so sending a longer range again never duplicates rows.
 * FISCAL_YEAR (default) = start of the fiscal year .. this month: always complete, so nobody has to remember
 * what they sent before. SHOWN_MONTH = only the month on the logbook screen (quick re-send of one month).
 */
enum class SendRange { SHOWN_MONTH, FISCAL_YEAR }

/**
 * Car -> phone: every QR frame is a link to the verification page, so the phone's normal camera app
 * opens the page from whichever frame it sees first, and the page then keeps reading.
 * Frames are a fountain code (core/Fountain.kt): the stream never repeats and any K frames are enough,
 * so a missed frame costs one more frame, not one more loop. Frames start as soon as they are ready.
 */
@Composable
fun ExportScreen(
    entries: List<SealedEntry>,
    publicKeyBase64: () -> String,
    month: YearMonth,
    zone: ZoneId,
    demo: Boolean,
    unclassified: Int,
    onBack: () -> Unit
) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    var range by remember { mutableStateOf(SendRange.FISCAL_YEAR) }
    var slow by remember { mutableStateOf(false) }
    val fy = FiscalYear.of(month, Settings.fyStart.intValue)
    // fiscal year: from its first month up to today (or its last month when it is a past year), not just the shown month
    val nowMonth = if (demo) month else YearMonth.now(zone)
    val from = if (range == SendRange.SHOWN_MONTH) month else fy.first
    val to = if (range == SendRange.SHOWN_MONTH) month else (if (fy.last.isBefore(nowMonth)) fy.last else maxOf(month, nowMonth))
    var prepared by remember(month, demo, range) { mutableStateOf<Prepared?>(null) }
    var failed by remember(month, demo, range) { mutableStateOf(false) }
    var index by remember(month, demo, range) { mutableIntStateOf(0) }
    var matrix by remember(month, demo, range) { mutableStateOf<Array<BooleanArray>?>(null) }

    LaunchedEffect(month, demo, range, entries.size, to) {
        prepared = null
        failed = false
        try {
            prepared = withContext(Dispatchers.Default) {
                val source = if (demo) {
                    // re-sign the sample ledger with the throwaway key (in memory only)
                    val out = ArrayList<SealedEntry>(entries.size)
                    for (s in entries) out += Chain.seal(out.lastOrNull(), s.entry, s.createdAtMs, DemoKeys)
                    out
                } else entries
                val pub = if (demo) DemoKeys.publicBase64 else publicKeyBase64()
                val slice = ExportPack.select(source, from, to, zone)
                val header = linkedMapOf(
                    "from" to from.toString(),
                    "to" to to.toString(),
                    // last record number in the whole car ledger at this moment: lets the phone tell
                    // "everything up to now" from "a part of it"
                    "head" to (source.lastOrNull()?.seq ?: 0L).toString(),
                    // when this ledger (and its key) began: an app reinstalled in July cannot vouch for June
                    "born" to (source.firstOrNull()?.createdAtMs ?: 0L).toString(),
                    "fy" to Settings.fyStart.intValue.toString(),
                    "tzo" to (zone.rules.getOffset(Instant.now()).totalSeconds / 60).toString(),
                    "pub" to pub,
                    "made" to System.currentTimeMillis().toString()
                )
                if (demo) header["demo"] = "1"
                val gz = ExportPack.gzip(ExportPack.payloadText(header, slice))
                val trips = slice.main.count { it.entry.type == RecordType.TRIP || it.entry.type == RecordType.GAP }
                val enc = if (trips == 0) null else Fountain.Encoder(gz, ExportPack.newSid(), ExportPack.LINK_PREFIX)
                Prepared(enc, slice.main.size + slice.extra.size, trips, gz.size, ExportPack.fingerprint(pub))
            }
            DiagLog.w("QR 전송 준비: $from~$to ${prepared?.entries}건 ${prepared?.bytes}바이트 ${prepared?.enc?.k ?: 0}장 필요 demo=$demo")
        } catch (e: Exception) {
            DiagLog.w("QR 전송 준비 실패: $e")
            failed = true
        }
    }

    val p = prepared
    LaunchedEffect(p, slow) {
        val enc = p?.enc ?: return@LaunchedEffect
        val step = if (slow) 400L else 200L
        var j = index
        while (true) {
            val started = System.currentTimeMillis()
            // frame j: blocks first (j < K), then an endless run of mixed frames
            matrix = withContext(Dispatchers.Default) { QrCodec.matrix(enc.frame(j)) }
            index = j
            j = if (j == Int.MAX_VALUE) enc.k else j + 1
            delay((step - (System.currentTimeMillis() - started)).coerceAtLeast(20L))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            NavChip(tr("운행기록부", "Logbook"), back = true, onClick = onBack)
            Spacer(Modifier.width(u(24)))
            Text(tr("QR로 보내기", "Send by QR"), fontSize = fs(56), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(u(20)))
            FitTextFill(rangeLabel(from, to), 40, 26, FontWeight.Bold, Palette.Sub, Modifier.weight(1f), tnum = true)
            if (demo) Chip(tr("예시 데이터", "Sample data"), Palette.WarnSoft, Palette.Warn)
        }
        Spacer(Modifier.height(u(22)))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(u(24))) {
            // QR always dark-on-white, also in dark mode (phone cameras read that best)
            Box(Modifier.size(u(1000)).background(Color.White, RoundedCornerShape(u(28))), contentAlignment = Alignment.Center) {
                when {
                    failed -> Text(tr("준비 실패", "Could not prepare"), fontSize = fs(40), fontWeight = FontWeight.ExtraBold, color = Color(0xFFB42318))
                    p == null -> Text(tr("준비 중…", "Preparing…"), fontSize = fs(40), fontWeight = FontWeight.Bold, color = Color(0xFF556275))
                    // nothing to send: say so instead of showing a QR that carries no trips
                    p.enc == null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(tr("보낼 운행 기록이 없습니다", "No trips to send"), fontSize = fs(44), fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B1B33), maxLines = 1)
                        Spacer(Modifier.height(u(12)))
                        Text(rangeLabel(from, to), fontSize = fs(34), fontWeight = FontWeight.Bold, color = Color(0xFF556275), maxLines = 1, tnum = true)
                    }
                    else -> matrix?.let { QrCanvas(it, 960) }
                }
            }
            Column(Modifier.weight(1f).fillMaxSize()) {
                // only when there is something to send (no "point the camera" over an empty box)
                if (p?.enc != null) {
                    FitTextFill(tr("폰 카메라로 비추세요", "Point the phone camera here"), 40, 26, FontWeight.ExtraBold, Palette.Ink)
                    Spacer(Modifier.height(u(8)))
                    FitTextFill(tr("열리는 페이지가 끝까지 받습니다", "The page that opens reads the rest"), Sz.TEXT_MIN, 22, FontWeight.Normal, Palette.Sub)
                    Spacer(Modifier.height(u(8)))
                    // some phone cameras do not open links from QR codes (e.g. older Galaxy)
                    FitTextFill(tr("카메라로 안 열리면 폰 브라우저에서 tripsign.beyondspark.kr", "No link? Open tripsign.beyondspark.kr on the phone"), Sz.TEXT_MIN, 22, FontWeight.Bold, Palette.Sub)
                }
                Spacer(Modifier.height(u(28)))
                Text(tr("보낼 기간", "Range"), fontSize = fs(Sz.LABEL), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
                Spacer(Modifier.height(u(8)))
                // fiscal year first: it is the choice that can never leave a hole
                Segmented(
                    listOf(tr("사업연도 전체", "Fiscal year"), if (Settings.isEnglish) monthLabel(month.monthValue) + " only" else "${month.monthValue}월만"),
                    if (range == SendRange.FISCAL_YEAR) 0 else 1, 150
                ) { range = if (it == 0) SendRange.FISCAL_YEAR else SendRange.SHOWN_MONTH }
                Spacer(Modifier.height(u(20)))
                Text(tr("속도", "Speed"), fontSize = fs(Sz.LABEL), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
                Spacer(Modifier.height(u(8)))
                Segmented(listOf(tr("보통", "Normal"), tr("느리게", "Slow")), if (slow) 1 else 0, 150) { slow = it == 1 }
                Spacer(Modifier.height(u(20)))
                val enc = p?.enc
                if (p != null && enc != null) {
                    // a phone that reads most frames needs about K of them; 15% margin for missed ones
                    val sec = (enc.k * 1.15 * (if (slow) 0.4 else 0.2) + 0.5).toInt().coerceAtLeast(1)
                    FitTextFill(
                        tr("아무 장이나 ${enc.k}장 받으면 끝 · 약 ${sec}초", "Any ${enc.k} frames will do · about ${sec}s"),
                        34, 24, FontWeight.ExtraBold, Palette.Ink, tnum = true
                    )
                    FitTextFill(
                        tr("기록 ${Fmt.count(p.entries)}건 · ${Fmt.count(index + 1)}번째 장", "${Fmt.count(p.entries)} records · frame ${Fmt.count(index + 1)}"),
                        Sz.TEXT_MIN, 22, FontWeight.Normal, Palette.Sub, tnum = true
                    )
                }
                Spacer(Modifier.weight(1f))
                if (p?.enc != null) {
                    Text(tr("키 지문", "Key fingerprint"), fontSize = fs(Sz.LABEL), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
                    FitTextFill(p.fingerprint, 64, 40, FontWeight.ExtraBold, Palette.Ink, tnum = true)
                    FitTextFill(tr("폰 화면의 키 지문과 같은지 확인", "Must match the phone page"), Sz.TEXT_MIN, 22, FontWeight.Normal, Palette.Sub)
                }
                if (unclassified > 0 && range == SendRange.SHOWN_MONTH) {
                    Spacer(Modifier.height(u(14)))
                    FitTextFill(tr("미분류 ${unclassified}건은 미분류로 보내집니다", "$unclassified unclassified trips are sent as is"), Sz.TEXT_MIN, 22, FontWeight.Bold, Palette.Warn)
                }
            }
        }
    }
}

private fun rangeLabel(from: YearMonth, to: YearMonth): String =
    if (Settings.isEnglish) {
        if (from == to) "${monthLabel(to.monthValue)} ${to.year}" else "${monthLabel(from.monthValue)} ${from.year} – ${monthLabel(to.monthValue)} ${to.year}"
    } else {
        if (from == to) "${to.year}년 ${to.monthValue}월"
        else if (from.year == to.year) "${to.year}년 ${from.monthValue}월~${to.monthValue}월"
        else "${from.year}년 ${from.monthValue}월~${to.year}년 ${to.monthValue}월"
    }

/** Draws a QR matrix with a 4-module white margin, square modules snapped to whole pixels. */
@Composable
private fun QrCanvas(m: Array<BooleanArray>, sizePx: Int) {
    Canvas(Modifier.size(u(sizePx))) {
        val n = m.size
        val cells = n + 8
        val cell = kotlin.math.floor(size.minDimension / cells)
        val origin = (size.minDimension - cell * cells) / 2f + cell * 4
        drawRect(Color.White)
        for (y in 0 until n) {
            val row = m[y]
            for (x in 0 until n) {
                if (row[x]) drawRect(Color.Black, topLeft = Offset(origin + x * cell, origin + y * cell), size = Size(cell, cell))
            }
        }
    }
}
