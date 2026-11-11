// ver 1.2.0 | 2026-11-04 14:40
package io.tripsign.app.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.tripsign.app.data.Settings
import io.tripsign.app.data.tr

/**
 * Title -> 3 intro pages -> one permission page. Permissions are asked once, all together,
 * only after the user taps the button on the permission page.
 */
@Composable
fun OnboardingFlow(
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onFinish: () -> Unit,
    startPage: Int = 0
) {
    var page by remember(startPage) { mutableIntStateOf(startPage) }
    Box(Modifier.fillMaxSize().background(Palette.Bg)) {
        when (page) {
            0 -> TitlePage { page = 1 }
            1 -> IntroPage(
                1,
                tr("기록은 차가 합니다", "The car keeps the log"),
                tr("P에서 출발하면 기록이 시작되고, P로 세우면 끝납니다", "Leaving P starts a trip. Parking in P ends it."),
                { GearIllustration() }
            ) { page = 2 }
            2 -> IntroPage(
                2,
                tr("도착하면 한 번만 누르세요", "One tap when you arrive"),
                tr("업무 · 출퇴근 · 개인 중 하나를 고르면 끝입니다", "Pick Business, Commute or Personal. That's it."),
                { PurposeIllustration() }
            ) { page = 3 }
            3 -> IntroPage(
                3,
                tr("서명으로 검증되는 기록", "Records verified by signature"),
                tr("모든 기록은 서명되어 이어지고, 바뀌면 바로 드러납니다", "Every record is signed and chained. Any change shows."),
                { ChainIllustration() }
            ) { page = 4 }
            else -> PermissionPage(missingPermissions, onRequestPermissions, onFinish)
        }
    }
}

@Composable
private fun TitlePage(onStart: () -> Unit) {
    val context = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.align(Alignment.TopEnd).padding(top = u(40), end = u(60))) {
            Segmented(listOf("한국어", "English"), if (Settings.isEnglish) 1 else 0, 190) {
                Settings.setLanguage(context, if (it == 1) Settings.LANG_EN else Settings.LANG_KO)
            }
        }
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            AppLogo(200)
            Spacer(Modifier.height(u(44)))
            Text(tr("트립사인", "TripSign"), fontSize = fs(96), fontWeight = FontWeight.Black, color = Palette.Ink)
            Spacer(Modifier.height(u(16)))
            Text(
                tr("차량이 직접 기록하고 원터치로 전송되는 무결성 검증된 운행기록부", "A verified trip log the car records itself, sent with one tap"),
                fontSize = fs(36),
                color = Palette.Sub,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(u(80)))
            PrimaryButton(tr("시작하기", "Get started"), 520, onStart)
        }
    }
}

@Composable
private fun IntroPage(index: Int, title: String, body: String, illustration: @Composable () -> Unit, onNext: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = u(80)), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.height(u(520)).fillMaxWidth(), contentAlignment = Alignment.Center) { illustration() }
        Spacer(Modifier.height(u(30)))
        Text(title, fontSize = fs(64), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
        Spacer(Modifier.height(u(18)))
        Text(body, fontSize = fs(36), color = Palette.Sub, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Dots(index, 4)
            Spacer(Modifier.width(u(60)))
            PrimaryButton(tr("다음", "Next"), 360, onNext)
        }
    }
}

@Composable
private fun Dots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(u(12))) {
        for (i in 1..total) {
            Box(
                Modifier
                    .width(u(if (i == current) 44 else 16))
                    .height(u(16))
                    .background(if (i == current) Palette.Blue else Palette.Gray, RoundedCornerShape(u(8)))
            )
        }
    }
}

@Composable
private fun PermissionPage(missing: List<String>, onRequest: () -> Unit, onFinish: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(vertical = u(80)), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(tr("시작 전에 두 가지를 허용해 주세요", "Allow two things before you start"), fontSize = fs(60), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
        Spacer(Modifier.height(u(16)))
        Text(tr("허용 창이 차례로 뜹니다. 허용하지 않아도 기록은 계속됩니다.", "The prompts appear one after another. Recording works either way."), fontSize = fs(Sz.LABEL), color = Palette.Sub, maxLines = 1)
        Spacer(Modifier.height(u(56)))
        PermissionRow(
            "1",
            tr("속도", "Speed"),
            tr("이동 없는 기어 조작을 걸러내고, 주행거리를 교차 검증합니다", "Skips gear shifts without moving, cross-checks distance"),
            tr("시스템 창에는 '위치 정보'로 표시됩니다", "The system prompt calls this \"Location\""),
            missing.none { it.endsWith("CAR_SPEED") }
        )
        Spacer(Modifier.height(u(24)))
        PermissionRow(
            "2",
            tr("알림", "Notifications"),
            tr("도착하면 업무 · 출퇴근 · 개인 버튼을 알림으로 띄웁니다", "Shows Business · Commute · Personal buttons when you arrive"),
            null,
            missing.none { it.endsWith("POST_NOTIFICATIONS") }
        )
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
            if (missing.isEmpty()) {
                PrimaryButton(tr("시작", "Start"), 520, onFinish)
            } else {
                SecondaryButton(tr("나중에", "Later"), 300, onFinish)
                PrimaryButton(tr("허용하고 시작", "Allow and start"), 520, onRequest)
            }
        }
    }
}

@Composable
private fun PermissionRow(number: String, title: String, body: String, note: String?, granted: Boolean) {
    Row(
        Modifier
            .width(u(1400))
            .background(Palette.Card, RoundedCornerShape(u(32)))
            .padding(horizontal = u(40), vertical = u(32)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(u(72)).background(Palette.BlueSoft, RoundedCornerShape(u(36))),
            contentAlignment = Alignment.Center
        ) {
            Text(number, fontSize = fs(36), fontWeight = FontWeight.ExtraBold, color = Palette.Blue)
        }
        Spacer(Modifier.width(u(30)))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = fs(40), fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            Text(body, fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
            if (note != null) Text(note, fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = Palette.Warn, maxLines = 1)
        }
        if (granted) {
            Spacer(Modifier.width(u(20)))
            StatusIcon(Status.OK, 52)
        }
    }
}

@Composable
private fun GearIllustration() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(36))) {
        GearStep("P", tr("정차", "Parked"), false)
        Arrow()
        GearStep("D", tr("기록 시작", "Recording"), true)
        Arrow()
        GearStep("P", tr("도착", "Arrived"), false)
    }
}

@Composable
private fun GearStep(letter: String, caption: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(u(200)).background(if (active) Palette.Blue else Palette.Card, RoundedCornerShape(u(48))),
            contentAlignment = Alignment.Center
        ) {
            Text(letter, fontSize = fs(120), fontWeight = FontWeight.Black, color = if (active) Palette.OnPrimary else Palette.Ink)
        }
        Spacer(Modifier.height(u(20)))
        Text(caption, fontSize = fs(34), fontWeight = FontWeight.Bold, color = if (active) Palette.Blue else Palette.Sub)
    }
}

@Composable
private fun Arrow() {
    Chevron(Palette.Gray, 72)
}

@Composable
private fun PurposeIllustration() {
    Row(horizontalArrangement = Arrangement.spacedBy(u(28))) {
        listOf(tr("업무", "Business"), tr("출퇴근", "Commute"), tr("개인", "Personal")).forEachIndexed { i, label ->
            Box(
                Modifier
                    .width(u(300))
                    .height(u(180))
                    .background(if (i == 0) Palette.Blue else Palette.Card, RoundedCornerShape(u(36))),
                contentAlignment = Alignment.Center
            ) {
                FitText(label, 52, 34, 270, FontWeight.ExtraBold, if (i == 0) Palette.OnPrimary else Palette.Ink)
            }
        }
    }
}

@Composable
private fun ChainIllustration() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..3).forEach { n ->
            if (n > 1) Box(Modifier.width(u(70)).height(u(10)).background(Palette.Blue2, RoundedCornerShape(u(5))))
            Column(
                Modifier
                    .width(u(260))
                    .height(u(220))
                    .background(Palette.Card, RoundedCornerShape(u(36))),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                StatusIcon(Status.OK, 72)
                Spacer(Modifier.height(u(18)))
                Text(tr("기록 $n", "Record $n"), fontSize = fs(36), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, textAlign = TextAlign.Center)
                FitText(tr("서명 · 해시", "Signed · hashed"), Sz.TEXT_MIN, 24, 230, FontWeight.Normal, Palette.Sub)
            }
        }
    }
}
