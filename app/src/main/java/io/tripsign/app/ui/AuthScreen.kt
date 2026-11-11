// ver 1.1.0 | 2026-11-07 13:25
package io.tripsign.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.tripsign.app.core.AuthProof
import io.tripsign.app.core.QrCodec
import io.tripsign.app.data.AuthClient
import io.tripsign.app.data.Live
import io.tripsign.app.data.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 0.5.2: phone check in front of the car. The car shows a QR that changes every 10 s; the phone opens
 * tripsign.beyondspark.kr/auth, proves an e-mail (and gives a phone number), then shows a 6-digit car code that is typed
 * here. The server's signed proof comes back and [onProof] writes it into the ledger. It shows who stood at the screen,
 * not who sat in the driver's seat — the record says exactly that.
 *
 * 0.5.2g: the same screen serves three jobs (who drove a 미인증 운행 / first check of a registered user at login /
 * sign-up). [notice] says on one line which job this is, so nobody mistakes a trip check for a login; [noticeWarn]
 * colours it as a warning (the 미인증 운행 check).
 */
@Composable
fun AuthScreen(
    carId: String,
    purpose: String,
    target: String,
    title: String,
    subtitle: String,
    onProof: (AuthProof) -> String?,
    onCancel: () -> Unit,
    notice: String? = null,
    noticeWarn: Boolean = false
) {
    // the service holds the repeated spoken warning while someone is doing a phone check
    DisposableEffect(Unit) {
        Live.update { it.copy(authOpen = true) }
        onDispose { Live.update { it.copy(authOpen = false) } }
    }
    val scope = rememberCoroutineScope()
    var attempt by remember { mutableIntStateOf(0) }
    var session by remember { mutableStateOf<AuthClient.Session?>(null) }
    var startError by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var digits by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf<String?>(null) }
    var matrix by remember { mutableStateOf<Array<BooleanArray>?>(null) }
    var shownStep by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(attempt) {
        session = null
        startError = null
        digits = ""
        codeError = null
        when (val r = withContext(Dispatchers.IO) { AuthClient.start(carId, purpose, target) }) {
            is AuthClient.Result.Ok -> session = r.value
            is AuthClient.Result.Fail -> startError = r.code
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500L)
        }
    }
    val s = session
    val expired = s != null && now - s.startedMs > s.ttlS * 1000L
    LaunchedEffect(s, now / 1000L) {
        val cur = s ?: return@LaunchedEffect
        val step = AuthProof.step(now, cur.stepS)
        if (step != shownStep) {
            shownStep = step
            val url = AuthProof.qrUrl(AuthClient.PAGE, cur.sid, step, cur.key)
            matrix = withContext(Dispatchers.Default) { QrCodec.matrix(url) }
        }
    }

    fun send(code: String) {
        val cur = session ?: return
        sending = true
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { AuthClient.claim(cur, code) }) {
                is AuthClient.Result.Ok -> {
                    val problem = onProof(r.value)
                    if (problem != null) { codeError = problem; digits = "" }
                }
                is AuthClient.Result.Fail -> {
                    digits = ""
                    codeError = when (r.code) {
                        "ERR_CAR_CODE" -> tr("번호가 맞지 않습니다", "Wrong code") + if (r.left >= 0) tr(" · 남은 횟수 ${r.left}번", " · ${r.left} left") else ""
                        "ERR_STATE" -> tr("폰에서 메일 인증을 먼저 끝내 주세요", "Finish the e-mail step on the phone first")
                        "ERR_CAR_OLD", "ERR_EXPIRED" -> tr("시간이 지났습니다 · 다시 시작하세요", "Timed out · start again")
                        "ERR_TRIES" -> tr("틀린 횟수가 많습니다 · 다시 시작하세요", "Too many tries · start again")
                        "ERR_NET" -> tr("인터넷 연결을 확인하세요", "Check the internet connection")
                        "ERR_PROOF" -> tr("확인서 서명이 맞지 않아 받지 않았습니다", "The proof's signature did not match")
                        else -> tr("다시 시도하세요", "Try again")
                    }
                }
            }
            sending = false
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            NavChip(tr("취소", "Cancel"), back = true, onClick = onCancel)
            Spacer(Modifier.width(u(24)))
            Text(title, fontSize = fs(56), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, softWrap = false)
            Spacer(Modifier.width(u(24)))
            FitTextFill(subtitle, 32, 22, FontWeight.SemiBold, Palette.Sub, Modifier.weight(1f))
        }
        if (notice != null) {
            Spacer(Modifier.height(u(10)))
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(u(92))
                    .background(if (noticeWarn) Palette.WarnSoft else Palette.Tile, RoundedCornerShape(u(24)))
                    .padding(horizontal = u(34)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FitTextFill(notice, 36, 24, FontWeight.ExtraBold, if (noticeWarn) Palette.Warn else Palette.Ink, Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(u(22)))
        val qrPx = if (notice != null) 560 else 620
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(u(24))) {
            Column(
                Modifier
                    .width(u(760))
                    .fillMaxHeight()
                    .background(Palette.Card, RoundedCornerShape(u(28)))
                    .padding(horizontal = u(50), vertical = u(40)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(tr("① 폰 카메라로 찍으세요", "① Scan with your phone"), fontSize = fs(40), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
                Spacer(Modifier.height(u(24)))
                val m = matrix
                Box(Modifier.size(u(qrPx)), contentAlignment = Alignment.Center) {
                    when {
                        startError != null -> Text(
                            when (startError) {
                                "ERR_NET" -> tr("인터넷에 연결되어 있지 않습니다\n본인확인은 인터넷이 필요합니다", "No internet connection\nThe check needs the internet")
                                "ERR_RATE" -> tr("요청이 너무 많습니다\n잠시 뒤 다시 하세요", "Too many requests\nTry again later")
                                else -> tr("서버에 연결하지 못했습니다\n잠시 뒤 다시 하세요", "Could not reach the server\nTry again later")
                            },
                            fontSize = fs(36), fontWeight = FontWeight.Bold, color = Palette.Bad, textAlign = TextAlign.Center, lineHeight = fs(54)
                        )
                        expired -> Text(tr("시간이 지났습니다", "Timed out"), fontSize = fs(40), fontWeight = FontWeight.Bold, color = Palette.Warn)
                        m == null -> Text(tr("서버에 연결하는 중", "Connecting"), fontSize = fs(36), color = Palette.Sub)
                        else -> AuthQr(m, qrPx)
                    }
                }
                Spacer(Modifier.height(u(20)))
                if (startError != null || expired) {
                    PrimaryButton(tr("다시 시작", "Start again"), 420) { attempt++ }
                } else {
                    // broken on purpose: two lines
                    Text(
                        tr("QR은 10초마다 바뀝니다\n사진으로 보낸 QR은 쓸 수 없습니다", "The QR changes every 10 s\nA photo of it will not work"),
                        fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, textAlign = TextAlign.Center, lineHeight = fs(45)
                    )
                    if (s != null) {
                        val left = ((s.ttlS * 1000L - (now - s.startedMs)) / 1000L).coerceAtLeast(0L)
                        Spacer(Modifier.height(u(10)))
                        Text(tr("남은 시간 ", "Time left ") + "%d:%02d".format(left / 60, left % 60), fontSize = fs(Sz.TEXT_MIN), color = Palette.Muted, tnum = true, maxLines = 1)
                    }
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Palette.Card, RoundedCornerShape(u(28)))
                    .padding(horizontal = u(60), vertical = u(40)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PinPad(
                    title = tr("② 폰에 뜬 차 확인번호 6자리", "② The 6-digit car code on the phone"),
                    digits = digits,
                    error = codeError != null,
                    message = when {
                        sending -> tr("확인하는 중", "Checking")
                        codeError != null -> codeError!!
                        else -> tr("폰에서 메일 인증을 마치면 번호가 뜹니다", "The code appears after the e-mail step")
                    },
                    messageColor = if (codeError != null) Palette.Bad else Palette.Sub,
                    onDigit = { c ->
                        if (!sending && session != null && !expired && digits.length < 6) {
                            codeError = null
                            digits += c
                            if (digits.length == 6) send(digits)
                        }
                    },
                    onDelete = { if (!sending && digits.isNotEmpty()) { codeError = null; digits = digits.dropLast(1) } },
                    length = 6
                )
            }
        }
    }
}

@Composable
private fun AuthQr(m: Array<BooleanArray>, sizePx: Int) {
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
