// ver 1.4.0 | 2026-11-07 13:25
package io.tripsign.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import io.tripsign.app.core.AuthProof
import io.tripsign.app.core.Driver
import io.tripsign.app.core.LogRow
import io.tripsign.app.core.Logbook
import io.tripsign.app.data.Settings
import io.tripsign.app.data.TripRepository
import io.tripsign.app.data.tr
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Layout check only: example accounts on the login and user screens, so a screenshot never shows
 * a real person's name. Held in memory while a shot is open; nothing here is ever saved.
 */
object ShotAccounts {
    val active = mutableStateOf(false)
    private val LIST = listOf(Driver("영업팀", "김하늘"), Driver("영업팀", "이수"), Driver("총무팀", "박도윤"), Driver("", "정민"))
    private val AGO_MS = listOf(2L * 3_600_000L, 26L * 3_600_000L, 4L * 86_400_000L, 40L * 86_400_000L)
    fun drivers(): List<Driver> = if (active.value) LIST else Settings.drivers.value
    fun lastLogin(i: Int): Long = if (active.value) System.currentTimeMillis() - AGO_MS[i] else Settings.lastLogin.value.getOrElse(i) { 0L }
    fun order(): List<Int> = if (active.value) LIST.indices.toList() else Settings.loginOrder()
}

/**
 * Title shown every time the app starts (about 1.5 s, a tap skips it). Recording keeps running
 * behind it in the service.
 */
@Composable
fun SplashScreen(onDone: () -> Unit, hold: Boolean = false) {
    LaunchedEffect(hold) {
        if (hold) return@LaunchedEffect   // layout check: keep the title on screen for the screenshot
        delay(1500L)
        onDone()
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .clickable { onDone() }
            .padding(vertical = u(30)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))
        AppLogo(200)
        Spacer(Modifier.height(u(44)))
        Text(tr("트립사인", "TripSign"), fontSize = fs(96), fontWeight = FontWeight.Black, color = Palette.Ink, maxLines = 1)
        Spacer(Modifier.height(u(26)))
        // broken on purpose: two lines
        Text(
            tr("차량이 직접 기록하고 원터치로 전송되는\n무결성 검증된 운행기록부", "A verified trip log\nthe car records itself"),
            fontSize = fs(36), color = Palette.Sub, textAlign = TextAlign.Center, lineHeight = fs(54)
        )
        Spacer(Modifier.weight(1f))
        Text(tr("화면을 누르면 바로 넘어갑니다", "Tap to continue"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Muted, maxLines = 1)
    }
}

private enum class LoginStep { PICK, PIN, SET_PIN, SIGN_UP, VERIFY_OLD }

/**
 * "누가 운전하나요?": pick your own tile, then the last 4 digits of your phone. No account is
 * preselected, so the last driver is never carried over by accident. 가입 needs no approval.
 * [onDone] runs once someone is logged in.
 */
@Composable
fun LoginFlow(
    onDone: () -> Unit,
    onSettings: () -> Unit,
    startStep: String? = null,
    repo: TripRepository? = null,
    carId: String = "",
    warn: Boolean = false,
    pending: LogRow? = null,
    onPendingMine: () -> Unit = {},
    onPendingNotMe: () -> Unit = {}
) {
    var step by remember { mutableStateOf(LoginStep.PICK) }
    var who by remember { mutableIntStateOf(-1) }
    // 0.5.2a: a user registered before the phone check is logged in only AFTER the check. Their number is
    // checked first and kept here until then; 취소 goes back to "who is driving?" with nobody logged in.
    // (0.5.2 logged them in at the number, so 취소 on the QR screen left them logged in.)
    var pendingPin by remember { mutableStateOf("") }
    val needsVerify: (Int) -> Boolean = { i ->
        repo != null && carId.isNotEmpty() && i >= 0 && !Settings.isVerified(i) && !ShotAccounts.active.value
    }
    val afterLogin: () -> Unit = {
        if (needsVerify(who)) step = LoginStep.VERIFY_OLD else onDone()
    }
    LaunchedEffect(startStep) {
        when (startStep) {
            null -> step = LoginStep.PICK
            "pin" -> if (ShotAccounts.drivers().isNotEmpty()) { who = ShotAccounts.order().first(); step = LoginStep.PIN }
            "signup" -> step = LoginStep.SIGN_UP
        }
    }
    when (step) {
        LoginStep.PICK -> PickScreen(
            onPick = { i ->
                who = i
                // users added before 0.5 have no digits yet: set them once (twice to confirm)
                step = if (Settings.pins.value.getOrElse(i) { "" }.isEmpty()) LoginStep.SET_PIN else LoginStep.PIN
            },
            onSignUp = { step = LoginStep.SIGN_UP },
            onSettings = onSettings,
            warn = warn,
            pending = pending,
            onPendingMine = onPendingMine,
            onPendingNotMe = onPendingNotMe
        )
        LoginStep.PIN -> PinScreen(who, loginNow = !needsVerify(who), onPin = { pendingPin = it }, onBack = { step = LoginStep.PICK }, onDone = afterLogin)
        LoginStep.SET_PIN -> SetPinScreen(who, loginNow = !needsVerify(who), onPin = { pendingPin = it }, onBack = { step = LoginStep.PICK }, onDone = afterLogin)
        LoginStep.SIGN_UP -> SignUpScreen(onBack = { step = LoginStep.PICK }, onDone = onDone, repo = repo, carId = carId)
        LoginStep.VERIFY_OLD -> {
            val target = remember { "U" + System.currentTimeMillis() }
            val d = Settings.drivers.value.getOrNull(who)
            AuthScreen(
                carId = carId,
                purpose = AuthProof.ENROLL,
                target = target,
                title = tr("로그인 본인확인", "Login check"),
                subtitle = (d?.name ?: "") + tr(" · 한 번만 하면 됩니다", " · only once"),
                notice = tr("${d?.name ?: ""}님으로 로그인하려면 처음 한 번 폰으로 본인확인을 합니다", "To log in as ${d?.name ?: ""}, confirm with your phone once"),
                onProof = { proof ->
                    val other = Settings.indexOfIdentity(proof.personId)
                    val r = repo
                    if (other >= 0 && other != who) {
                        tr("이 이메일은 다른 사용자가 이미 쓰고 있습니다", "This e-mail belongs to another user")
                    } else if (r == null) {
                        null
                    } else {
                        r.appendEnrollProof(proof)
                        Settings.setIdentity(r.appContext, who, proof.personId)
                        // the check passed: now the login starts (with the number checked a moment ago)
                        Settings.login(r.appContext, who, pendingPin)
                        pendingPin = ""
                        onDone()
                        null
                    }
                },
                onCancel = { pendingPin = ""; step = LoginStep.PICK }
            )
        }
    }
}

/** "오늘 18:20" / "어제 17:42" / "11월 2일" / "아직 운행 없음" */
fun lastLoginLabel(ms: Long, now: Long = System.currentTimeMillis()): String {
    if (ms <= 0L) return tr("아직 운행 없음", "Not yet")
    val c = Calendar.getInstance().apply { timeInMillis = now }
    val t = Calendar.getInstance().apply { timeInMillis = ms }
    val sameDay = c.get(Calendar.YEAR) == t.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
    c.add(Calendar.DAY_OF_YEAR, -1)
    val yesterday = c.get(Calendar.YEAR) == t.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)
    val hm = SimpleDateFormat("HH:mm", Locale.US).format(Date(ms))
    return when {
        sameDay -> tr("오늘 $hm", "Today $hm")
        yesterday -> tr("어제 $hm", "Yesterday $hm")
        else -> if (Settings.isEnglish) SimpleDateFormat("MMM d", Locale.US).format(Date(ms)) else "${t.get(Calendar.MONTH) + 1}월 ${t.get(Calendar.DAY_OF_MONTH)}일"
    }
}

@Composable
private fun PickScreen(
    onPick: (Int) -> Unit,
    onSignUp: () -> Unit,
    onSettings: () -> Unit,
    warn: Boolean = false,
    pending: LogRow? = null,
    onPendingMine: () -> Unit = {},
    onPendingNotMe: () -> Unit = {}
) {
    val list = ShotAccounts.drivers()
    val order = ShotAccounts.order()
    val full = list.size >= Settings.MAX_DRIVERS
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("누가 운전하나요?", "Who is driving?"), fontSize = fs(56), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, softWrap = false)
            if (list.isNotEmpty()) {
                Spacer(Modifier.width(u(24)))
                FitTextFill(tr("자기 이름을 누르세요", "Tap your own name"), 32, 24, FontWeight.SemiBold, Palette.Sub, Modifier.weight(1f))
            } else Spacer(Modifier.weight(1f))
            IconButton96(onClick = onSettings) { GearIcon(Palette.Sub, 48) }
        }
        if (warn) {
            // 0.5.2: shown at ignition and after every drive; the ledger records that it was on screen
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(u(92))
                    .background(Palette.WarnSoft, RoundedCornerShape(u(24)))
                    .padding(horizontal = u(34)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FitTextFill(tr("로그인하지 않고 출발하면 미인증 운행으로 기록됩니다", "Leaving without logging in is recorded as an unverified trip"), 36, 24, FontWeight.ExtraBold, Palette.Warn, Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(u(16)))
        }
        if (pending != null && list.isNotEmpty()) {
            // 0.5.2g: the 미인증 운행 before this start, once (Logbook.pendingUnverified)
            PendingUnverifiedCard(pending, onPendingMine, onPendingNotMe)
            Spacer(Modifier.height(u(16)))
        }
        if (list.isEmpty()) {
            // first install: nobody has signed up yet
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(u(150)).background(Palette.BadgeBg, RoundedCornerShape(u(75))), contentAlignment = Alignment.Center) { PlusIcon(Palette.Sub, 80) }
                Spacer(Modifier.height(u(40)))
                Text(tr("아직 가입한 사람이 없습니다", "Nobody has signed up yet"), fontSize = fs(56), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
                Spacer(Modifier.height(u(20)))
                Text(
                    tr("처음 운전하는 사람이 가입하면\n바로 운행을 시작할 수 있습니다", "Whoever drives first signs up\nand starts right away"),
                    fontSize = fs(36), color = Palette.Sub, textAlign = TextAlign.Center, lineHeight = fs(54)
                )
                Spacer(Modifier.height(u(56)))
                PrimaryButton(tr("가입하기", "Sign up"), 520, onSignUp)
            }
        } else {
            Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.Center) {
                val cells: List<Int?> = order + (if (full) emptyList() else listOf(null))
                cells.chunked(4).forEachIndexed { r, line ->
                    if (r > 0) Spacer(Modifier.height(u(24)))
                    Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
                        line.forEach { i ->
                            if (i == null) SignUpTile(Modifier.weight(1f), onSignUp)
                            else UserTile(list[i], ShotAccounts.lastLogin(i), Modifier.weight(1f)) { onPick(i) }
                        }
                        repeat(4 - line.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            FitTextFill(
                if (full) tr("8명이 다 찼습니다 · 새로 가입하려면 설정 → 사용자에서 한 명을 정리하세요", "8 users is the limit · remove one in Settings → Users to add someone")
                else tr("운행이 끝나면 자동으로 로그아웃됩니다 · 다음 운행 때 다시 고릅니다", "Logged out when the trip ends · choose again next time"),
                Sz.TEXT_MIN, 22, FontWeight.Normal, Palette.Muted, Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun UserTile(d: Driver, last: Long, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(u(250))
            .background(Palette.Card, RoundedCornerShape(u(28)))
            .clickable { onClick() }
            .padding(horizontal = u(34), vertical = u(30)),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FitTextFill(d.dept.ifEmpty { "–" }, 32, 22, FontWeight.Normal, Palette.Sub, Modifier.weight(1f))
            Spacer(Modifier.width(u(12)))
            Text(lastLoginLabel(last), fontSize = fs(28), color = Palette.Muted, maxLines = 1, softWrap = false, tnum = true)
        }
        FitTextFill(d.name, 60, 34, FontWeight.ExtraBold, Palette.Ink, Modifier.fillMaxWidth())
    }
}

@Composable
private fun SignUpTile(modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .height(u(250))
            .dashedBorder(Palette.ChipLine, 3, 28)
            .clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        PlusIcon(Palette.Blue, 56)
        Spacer(Modifier.height(u(14)))
        Text(tr("가입하기", "Sign up"), fontSize = fs(40), fontWeight = FontWeight.ExtraBold, color = Palette.Blue, maxLines = 1)
    }
}

/** Dashed rounded outline (the "add" tile is not a filled card). Sizes in design px. */
@Composable
private fun Modifier.dashedBorder(color: Color, widthPx: Int, radiusPx: Int): Modifier {
    val density = LocalDensity.current
    val w = with(density) { u(widthPx).toPx() }
    val r = with(density) { u(radiusPx).toPx() }
    return this.drawBehind {
        drawRoundRect(
            color = color,
            topLeft = Offset(w / 2f, w / 2f),
            size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(w * 4f, w * 3f)))
        )
    }
}

@Composable
fun PlusIcon(color: Color, sizePx: Int) {
    Canvas(Modifier.size(u(sizePx))) {
        val s = size.width
        val w = s * 0.11f
        drawLine(color, Offset(s / 2f, s * 0.15f), Offset(s / 2f, s * 0.85f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(s * 0.15f, s / 2f), Offset(s * 0.85f, s / 2f), strokeWidth = w, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

/** Left: who was picked (big). Right: the keypad card given in [content]. */
@Composable
private fun PinLayout(d: Driver?, backLabel: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            NavChip(backLabel, back = true, onClick = onBack)
        }
        Spacer(Modifier.height(u(22)))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(u(24))) {
            Column(
                Modifier
                    .width(u(640))
                    .fillMaxHeight()
                    .background(Palette.Card, RoundedCornerShape(u(28)))
                    .padding(horizontal = u(50), vertical = u(56))
            ) {
                if (d != null) {
                    FitTextFill(d.dept.ifEmpty { "–" }, 38, 26, FontWeight.Normal, Palette.Sub, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(u(14)))
                    FitTextFill(d.name, 96, 50, FontWeight.ExtraBold, Palette.Ink, Modifier.fillMaxWidth())
                }
                Spacer(Modifier.weight(1f))
                // broken on purpose: two lines
                Text(tr("내 이름이 아니면\n왼쪽 위 ‘다른 사람 선택’", "Not you?\nTap ‘Someone else’ at the top"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Muted, lineHeight = fs(45))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Palette.Card, RoundedCornerShape(u(28)))
                    .padding(horizontal = u(60), vertical = u(50)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) { content() }
        }
    }
}

/** [length] boxes (4, or 6 for the car code) + message + keypad. [error] paints the boxes red. */
@Composable
fun ColumnScope.PinPad(title: String, digits: String, error: Boolean, message: String, messageColor: Color, onDigit: (Char) -> Unit, onDelete: () -> Unit, length: Int = 4) {
    FitText(title, 48, 32, 900, FontWeight.ExtraBold, Palette.Ink)
    Spacer(Modifier.height(u(36)))
    Row(horizontalArrangement = Arrangement.spacedBy(u(if (length > 4) 16 else 22))) {
        for (i in 0 until length) {
            val filled = i < digits.length
            val line = when {
                error -> Palette.Bad
                i == digits.length -> Palette.Primary
                else -> Palette.Gray
            }
            Box(
                Modifier
                    .width(u(if (length > 4) 92 else 120))
                    .height(u(136))
                    .border(u(4), line, RoundedCornerShape(u(24))),
                contentAlignment = Alignment.Center
            ) {
                if (filled) Box(Modifier.size(u(30)).background(if (error) Palette.Bad else Palette.Ink, RoundedCornerShape(u(15))))
            }
        }
    }
    Spacer(Modifier.height(u(26)))
    FitText(message, 34, 24, 900, if (error) FontWeight.ExtraBold else FontWeight.Normal, messageColor)
    Spacer(Modifier.weight(1f))
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', ' ', '0', '<')
    Column(Modifier.width(u(660)), verticalArrangement = Arrangement.spacedBy(u(18))) {
        keys.chunked(3).forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(u(18))) {
                line.forEach { k ->
                    if (k == ' ') Spacer(Modifier.weight(1f).height(u(118)))
                    else Box(
                        Modifier
                            .weight(1f)
                            .height(u(118))
                            .background(Palette.Seg, RoundedCornerShape(u(26)))
                            .clickable { if (k == '<') onDelete() else onDigit(k) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (k == '<') DeleteKeyIcon(Palette.Ink)
                        else Text(k.toString(), fontSize = fs(54), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1, tnum = true)
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteKeyIcon(color: Color) {
    Canvas(Modifier.width(u(60)).height(u(44))) {
        val w = size.width
        val h = size.height
        val sw = w * 0.066f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.30f, h * 0.05f); lineTo(w * 0.93f, h * 0.05f); lineTo(w * 0.93f, h * 0.95f); lineTo(w * 0.30f, h * 0.95f); lineTo(w * 0.03f, h * 0.5f); close()
        }
        drawPath(path, color, style = Stroke(width = sw, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        drawLine(color, Offset(w * 0.47f, h * 0.32f), Offset(w * 0.73f, h * 0.68f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(color, Offset(w * 0.73f, h * 0.32f), Offset(w * 0.47f, h * 0.68f), strokeWidth = sw, cap = androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@Composable
private fun PinScreen(who: Int, loginNow: Boolean, onPin: (String) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val d = ShotAccounts.drivers().getOrNull(who)
    var digits by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(error) {
        if (error) { delay(700L); digits = ""; error = false }
    }
    PinLayout(d, tr("다른 사람 선택", "Someone else"), onBack) {
        PinPad(
            title = tr("내 번호 4자리", "Your 4-digit number"),
            digits = digits,
            error = error,
            message = when {
                error -> tr("번호가 맞지 않습니다 · 다시 입력하세요", "Wrong digits · try again")
                loginNow -> tr("맞으면 바로 운행을 시작합니다", "The drive starts when they match")
                else -> tr("맞으면 폰 본인확인으로 넘어갑니다", "Then a one-time phone check")
            },
            messageColor = if (error) Palette.Bad else Palette.Sub,
            onDigit = { c ->
                if (!error && digits.length < 4) {
                    digits += c
                    if (digits.length == 4 && !ShotAccounts.active.value) {
                        when {
                            loginNow -> if (Settings.login(context, who, digits)) onDone() else error = true
                            // not logged in yet: the phone check comes first
                            Settings.verifyPin(who, digits) -> { onPin(digits); onDone() }
                            else -> error = true
                        }
                    }
                }
            },
            onDelete = { if (!error && digits.isNotEmpty()) digits = digits.dropLast(1) }
        )
    }
}

/**
 * Asks for [who]'s CURRENT 4 digits (뒷자리 바꾸기 / 지우기 by the owner). Wrong digits paint the boxes
 * red and clear; [onOk] runs when they match. Nobody is logged in by this.
 */
@Composable
fun ColumnScope.VerifyPinPad(who: Int, title: String, onOk: () -> Unit) {
    var digits by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(error) {
        if (error) { delay(700L); digits = ""; error = false }
    }
    PinPad(
        title = title,
        digits = digits,
        error = error,
        message = if (error) tr("번호가 맞지 않습니다 · 다시 입력하세요", "Wrong digits · try again") else tr("본인만 할 수 있습니다", "Only the owner can do this"),
        messageColor = if (error) Palette.Bad else Palette.Sub,
        onDigit = { c ->
            if (!error && digits.length < 4) {
                digits += c
                if (digits.length == 4) { if (Settings.verifyPin(who, digits)) onOk() else error = true }
            }
        },
        onDelete = { if (!error && digits.isNotEmpty()) digits = digits.dropLast(1) }
    )
}

/**
 * Sets the 4 digits twice (first time for users added before 0.5, or 설정 → 뒷자리 바꾸기).
 * [login] = log the user in when done.
 */
@Composable
fun ColumnScope.SetPinPad(who: Int, login: Boolean, onSet: (String) -> Unit = {}, onDone: () -> Unit) {
    val context = LocalContext.current
    var first by remember { mutableStateOf<String?>(null) }
    var digits by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var sameAsNow by remember { mutableStateOf(false) }   // the error is "that is the current number"
    LaunchedEffect(error) {
        if (error) { delay(900L); digits = ""; first = null; error = false }
    }
    PinPad(
        title = if (first == null) tr("내 번호 4자리를 정하세요", "Choose your 4-digit number") else tr("같은 번호 한 번 더", "The same number again"),
        digits = digits,
        error = error,
        message = when {
            error && sameAsNow -> tr("지금과 같은 번호입니다 · 바뀐 번호를 입력하세요", "That is the current number · enter the new one")
            error -> tr("처음 입력한 번호와 다릅니다 · 처음부터 다시", "Did not match · start again")
            first == null -> tr("휴대폰 번호와 다르게 정하세요 · 다른 사람이 모르는 번호", "Not your phone digits · a number others do not know")
            else -> tr("확인을 위해 한 번 더 입력합니다", "Once more to confirm")
        },
        messageColor = if (error) Palette.Bad else Palette.Sub,
        onDigit = { c ->
            if (!error && digits.length < 4) {
                digits += c
                if (digits.length == 4) {
                    val f = first
                    // changing to the number already saved changes nothing: say so instead of "done"
                    if (f == null && Settings.verifyPin(who, digits)) { sameAsNow = true; error = true }
                    else if (f == null) { first = digits; digits = "" }
                    else if (f == digits) {
                        Settings.setPin(context, who, digits)
                        if (login) Settings.login(context, who, digits)
                        onSet(digits)
                        onDone()
                    } else { sameAsNow = false; error = true }
                }
            }
        },
        onDelete = { if (!error && digits.isNotEmpty()) digits = digits.dropLast(1) }
    )
}

@Composable
private fun SetPinScreen(who: Int, loginNow: Boolean, onPin: (String) -> Unit, onBack: () -> Unit, onDone: () -> Unit) {
    PinLayout(Settings.drivers.value.getOrNull(who), tr("다른 사람 선택", "Someone else"), onBack) {
        SetPinPad(who, login = loginNow, onDone = onDone, onSet = onPin)
    }
}

@Composable
private fun SignUpScreen(onBack: () -> Unit, onDone: () -> Unit, repo: TripRepository? = null, carId: String = "") {
    val context = LocalContext.current
    var checking by remember { mutableStateOf(false) }
    var dept by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    val d = Driver(Logbook.clean(dept, Logbook.DEPT_MAX), Logbook.clean(name, Logbook.NAME_MAX))
    val duplicate = d.name.isNotBlank() && Settings.exists(d)
    val full = Settings.drivers.value.size >= Settings.MAX_DRIVERS
    val pinOk = Settings.isPin(pin)
    val match = pinOk && pin2 == pin
    val ready = d.name.isNotBlank() && !duplicate && !full && match
    // 0.5.2: 가입 needs a phone check in front of the car (layout checks and tests without a ledger skip it)
    if (checking && repo != null && carId.isNotEmpty()) {
        val target = remember { "U" + System.currentTimeMillis() }
        AuthScreen(
            carId = carId,
            purpose = AuthProof.ENROLL,
            target = target,
            title = tr("가입 본인확인", "Sign-up check"),
            subtitle = d.name,
            notice = tr("${d.name}님 가입을 마치려면 폰으로 본인확인을 합니다", "To finish signing up ${d.name}, confirm with your phone"),
            onProof = { proof ->
                if (Settings.indexOfIdentity(proof.personId) >= 0) {
                    tr("이 이메일로 이미 가입한 사람이 있습니다", "Someone already signed up with this e-mail")
                } else if (!Settings.signUp(context, d, pin, proof.personId)) {
                    tr("가입하지 못했습니다 · 이름과 번호를 확인하세요", "Could not sign up · check the name and number")
                } else {
                    repo.appendEnrollProof(proof)
                    onDone()
                    null
                }
            },
            onCancel = { checking = false }
        )
        return
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            NavChip(tr("취소", "Cancel"), back = true, onClick = onBack)
            Spacer(Modifier.width(u(24)))
            Text(tr("가입하기", "Sign up"), fontSize = fs(56), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
        }
        Spacer(Modifier.height(u(22)))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(u(24))) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(Palette.Card, RoundedCornerShape(u(28)))
                    .padding(horizontal = u(56), vertical = u(40)),
                verticalArrangement = Arrangement.spacedBy(u(24))
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
                    Column(Modifier.weight(1f)) { Field(tr("부서", "Department"), dept, tr("예: 영업팀", "e.g. Sales")) { dept = Logbook.clean(it, Logbook.DEPT_MAX) } }
                    Column(Modifier.weight(1f)) { Field(tr("성명", "Name"), name, tr("예: 홍길동", "e.g. Kim Ha-neul")) { name = Logbook.clean(it, Logbook.NAME_MAX) } }
                }
                // both digit fields on one row, so the on-screen keyboard never covers the second one
                Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
                    Column(Modifier.weight(1f)) { Field(tr("내 번호 4자리", "Your 4-digit number"), pin, tr("숫자 4자리", "4 digits"), digits = true) { pin = it.filter { c -> c in '0'..'9' }.take(4) } }
                    Column(Modifier.weight(1f)) { Field(tr("같은 번호 한 번 더", "The same number again"), pin2, tr("숫자 4자리", "4 digits"), digits = true) { pin2 = it.filter { c -> c in '0'..'9' }.take(4) } }
                }
                Column {
                    FitTextFill(tr("휴대폰 번호와 다르게 정하세요 · 로그인할 때 본인 확인용으로만 씁니다", "Not your phone digits · only used to check it is you at login"), 28, 20, FontWeight.Normal, Palette.Muted, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(u(12)))
                    val msg = when {
                        pin2.length < 4 || !pinOk -> null
                        match -> tr("두 번 입력한 번호가 같습니다", "Both entries match") to Palette.Ok
                        else -> tr("처음 입력한 번호와 다릅니다 · 다시 입력하세요", "Does not match the first entry") to Palette.Bad
                    }
                    if (msg != null) FitTextFill(msg.first, 30, 22, FontWeight.ExtraBold, msg.second, Modifier.fillMaxWidth())
                }
            }
            Column(
                Modifier
                    .width(u(560))
                    .fillMaxHeight()
                    .background(Palette.Card, RoundedCornerShape(u(28)))
                    .padding(u(50))
            ) {
                Text(tr("운행기록부 ④사용자", "Logbook ④ user"), fontSize = fs(34), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
                Spacer(Modifier.height(u(16)))
                // broken on purpose: two lines
                Text(tr("부서와 성명이\n운행기록부에 그대로 적힙니다", "Department and name go\ninto the logbook as written"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, lineHeight = fs(45))
                Spacer(Modifier.height(u(40)))
                Box(Modifier.fillMaxWidth().height(u(2)).background(Palette.Line))
                Spacer(Modifier.height(u(34)))
                // preview of the logbook ④ cell; empty fields show their label in grey, not a dash
                FitTextFill(d.dept.ifEmpty { tr("부서", "Department") }, 30, 22, FontWeight.Normal, if (d.dept.isEmpty()) Palette.Muted else Palette.Sub, Modifier.fillMaxWidth())
                Spacer(Modifier.height(u(6)))
                FitTextFill(d.name.ifEmpty { tr("성명", "Name") }, 56, 34, FontWeight.ExtraBold, if (d.name.isEmpty()) Palette.Muted else Palette.Ink, Modifier.fillMaxWidth())
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(u(104))
                        .alpha(if (ready) 1f else 0.45f)
                        .background(Palette.Primary, RoundedCornerShape(u(30)))
                        .clickable {
                            if (!ready) return@clickable
                            if (repo != null && carId.isNotEmpty()) checking = true
                            else if (Settings.signUp(context, d, pin)) onDone()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(tr("폰으로 본인확인", "Verify with phone"), fontSize = fs(38), fontWeight = FontWeight.ExtraBold, color = Palette.OnPrimary, maxLines = 1)
                }
                Spacer(Modifier.height(u(16)))
                FitTextFill(
                    when {
                        full -> tr("8명이 다 찼습니다 · 설정 → 사용자에서 정리", "8 users is the limit")
                        duplicate -> tr("같은 부서에 같은 이름이 이미 있습니다", "This name is already signed up")
                        ready -> tr("폰으로 확인하면 가입되고 바로 시작합니다", "Signs up and starts once your phone confirms")
                        else -> tr("성명과 번호 두 칸을 채우면 눌립니다", "Fill in the name and both digit fields")
                    },
                    28, 20, FontWeight.Normal, if (duplicate || full) Palette.Bad else Palette.Muted, Modifier.fillMaxWidth(), alignEnd = false
                )
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, hint: String, digits: Boolean = false, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Text(label, fontSize = fs(32), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
    Spacer(Modifier.height(u(12)))
    Box(
        Modifier
            .fillMaxWidth()
            .height(u(112))
            .border(u(3), Palette.Gray, RoundedCornerShape(u(24)))
            .padding(horizontal = u(34)),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = fs(44), fontWeight = FontWeight.Bold, color = Palette.Ink),
            cursorBrush = SolidColor(Palette.Blue),
            visualTransformation = if (digits) PasswordVisualTransformation('•') else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Text, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, fontSize = fs(44), fontWeight = FontWeight.Medium, color = Palette.Muted, maxLines = 1, softWrap = false)
                    inner()
                }
            }
        )
    }
}
