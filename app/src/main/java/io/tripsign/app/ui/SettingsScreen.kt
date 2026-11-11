// ver 1.7.1 | 2026-11-06 17:41
package io.tripsign.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.tripsign.app.core.ChainCheck
import io.tripsign.app.core.Driver
import io.tripsign.app.core.ExportPack
import io.tripsign.app.core.Logbook
import io.tripsign.app.core.Vehicle
import io.tripsign.app.data.DiagLog
import io.tripsign.app.data.Settings
import io.tripsign.app.data.TripRepository
import io.tripsign.app.data.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val VEHICLE_SDK_VERSION = "2.3.2"

/**
 * Settings, one section at a time (left menu + one panel) so nothing scrolls or gets cut.
 * Height budget per panel (content area 962 design px at most): vehicle ~490, users ~600,
 * display ~300, integrity ~440, permissions ~310, about ~400.
 * The light/dark choice is gone: the app follows the car's day/night like the rest of the screen.
 */
@Composable
fun SettingsScreen(
    repo: TripRepository,
    check: ChainCheck,
    vehicle: Vehicle?,
    parked: Boolean,
    demo: Boolean,
    initialTab: Int,
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
    onReplayOnboarding: () -> Unit,
    onToggleDemo: () -> Unit,
    onBack: () -> Unit
) {
    var tab by remember(initialTab) { mutableStateOf(initialTab.coerceIn(0, 5)) }
    val tabs = listOf(
        tr("운행기록부 정보", "Logbook details"),
        tr("사용자", "Users"),
        tr("화면", "Display"),
        tr("기록 무결성", "Integrity"),
        tr("권한", "Permissions"),
        tr("앱 정보", "About")
    )
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .padding(horizontal = u(44), vertical = u(30))
    ) {
        Row(Modifier.fillMaxWidth().height(u(Sz.HEADER)), verticalAlignment = Alignment.CenterVertically) {
            NavChip(tr("운행기록부", "Logbook"), back = true, onClick = onBack)
            Spacer(Modifier.width(u(24)))
            Text(tr("설정", "Settings"), fontSize = fs(56), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
            if (!parked) {
                Spacer(Modifier.width(u(24)))
                Chip(tr("입력은 정차(P) 중에만", "Typing only in P"), Palette.WarnSoft, Palette.Warn)
            }
        }
        Spacer(Modifier.height(u(24)))
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(u(24))) {
            Column(Modifier.width(u(360)), verticalArrangement = Arrangement.spacedBy(u(10))) {
                tabs.forEachIndexed { i, label ->
                    val on = i == tab
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(u(104))
                            .background(if (on) Palette.Card else Palette.Bg, RoundedCornerShape(u(24)))
                            .clickable { tab = i }
                            .padding(horizontal = u(28)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        FitTextFill(label, 36, 24, if (on) FontWeight.ExtraBold else FontWeight.Bold, if (on) Palette.Ink else Palette.Sub)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when (tab) {
                    0 -> VehicleCard(repo, vehicle, parked)
                    1 -> DriversCard(parked)
                    2 -> DisplayCard()
                    3 -> IntegrityCard(repo, check)
                    4 -> PermissionsCard(missingPermissions, onRequestPermissions)
                    else -> AboutCard(demo, onToggleDemo, onReplayOnboarding)
                }
            }
        }
    }
}

/** A settings section: full panel width, height of its content. */
@Composable
private fun Panel(content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(Palette.Card, RoundedCornerShape(u(32)))
            .padding(horizontal = u(36), vertical = u(32))
    ) { content() }
}

/** Form ① 차종, ② 자동차등록번호 (signed record) and the 사업연도 start month (setting). */
@Composable
private fun VehicleCard(repo: TripRepository, vehicle: Vehicle?, parked: Boolean) {
    var model by remember(vehicle) { mutableStateOf(vehicle?.model ?: "") }
    var plate by remember(vehicle) { mutableStateOf(vehicle?.plate ?: "") }
    var saved by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    Panel {
        Column {
            CardLabel(tr("운행기록부 정보", "Logbook details"))
            Spacer(Modifier.height(u(18)))
            Input(1, tr("차종", "Model"), model, Logbook.MODEL_MAX, parked, tr("예: 그랜저 HEV", "e.g. Grandeur HEV")) { model = it; saved = null }
            Spacer(Modifier.height(u(14)))
            Input(2, tr("자동차등록번호", "Plate"), plate, Logbook.PLATE_MAX, parked, tr("예: 123가4567", "e.g. 123가4567")) { plate = it; saved = null }
            Spacer(Modifier.height(u(18)))
            Row(Modifier.fillMaxWidth().height(u(Sz.TOUCH)), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("사업연도 시작 월", "Fiscal year starts"), fontSize = fs(34), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1)
                Spacer(Modifier.weight(1f))
                MonthStepper(Settings.fyStart.intValue) { Settings.setFyStart(context, it) }
            }
            Spacer(Modifier.height(u(22)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val changed = model.trim() != (vehicle?.model ?: "") || plate.trim() != (vehicle?.plate ?: "")
                if (parked && changed && model.isNotBlank() && plate.isNotBlank()) {
                    PrimaryButton(tr("저장", "Save"), 260) {
                        repo.setVehicle(Vehicle(model, plate))
                        saved = tr("서명 기록으로 저장했습니다", "Saved as a signed record")
                        DiagLog.w("차량 정보 저장")
                    }
                } else {
                    SecondaryButton(tr("저장", "Save"), 260) { }
                }
                Spacer(Modifier.width(u(20)))
                FitTextFill(
                    saved ?: tr("엑셀 ①② 칸에 들어갑니다", "Goes into ① and ② of the Excel"),
                    Sz.TEXT_MIN, 22, FontWeight.Normal, if (saved != null) Palette.Ok else Palette.Sub, Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Form ④ users (가입 happens on the login screen). Past trips keep the name they were signed with.
 * 뒷자리 바꾸기: the owner's current digits first, then the new ones twice (nobody can take over
 * someone else's account). 지우기: the owner with their digits at any time; anyone else only after
 * 30 days without a login (someone who left can be cleared without a master key).
 */
@Composable
private fun DriversCard(parked: Boolean) {
    val context = LocalContext.current
    val list = ShotAccounts.drivers()
    var removing by remember { mutableStateOf(-1) }
    var changing by remember { mutableStateOf(-1) }
    var changeVerified by remember { mutableStateOf(false) }
    var handing by remember { mutableStateOf(-1) }   // 0.5.2: hand the administrator role to this user
    Panel {
        Column {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(tr("사용자", "Users"), fontSize = fs(44), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
                Spacer(Modifier.width(u(20)))
                Text("${list.size} / ${Settings.MAX_DRIVERS}" + tr("명", ""), fontSize = fs(34), color = Palette.Sub, maxLines = 1, tnum = true, modifier = Modifier.padding(bottom = u(4)))
            }
            Spacer(Modifier.height(u(10)))
            FitTextFill(
                if (list.isEmpty()) tr("가입은 로그인 화면의 ‘가입하기’에서 합니다", "People sign up on the login screen")
                else tr("한 명을 지우면 로그인 화면에 ‘가입하기’가 다시 생깁니다", "Removing someone frees a place on the login screen"),
                Sz.TEXT_MIN, 22, FontWeight.Normal, Palette.Muted, Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(u(14)))
            list.forEachIndexed { i, d ->
                Row(Modifier.fillMaxWidth().height(u(98)), verticalAlignment = Alignment.CenterVertically) {
                    FitTextFill(d.name, 42, 28, FontWeight.ExtraBold, Palette.Ink, Modifier.width(u(200)))
                    Spacer(Modifier.width(u(24)))
                    FitTextFill(d.dept.ifEmpty { "–" }, 32, 22, FontWeight.Normal, Palette.Sub, Modifier.width(u(170)))
                    Spacer(Modifier.width(u(16)))
                    // 0.5.2: administrator and phone check status
                    Column(Modifier.width(u(150))) {
                        if (i == Settings.admin.intValue) FitTextFill(tr("관리자", "Admin"), 28, 20, FontWeight.ExtraBold, Palette.Blue)
                        FitTextFill(
                            if (ShotAccounts.active.value || Settings.isVerified(i)) tr("본인확인 됨", "Verified") else tr("본인확인 전", "Not verified"),
                            26, 18, FontWeight.Bold, if (ShotAccounts.active.value || Settings.isVerified(i)) Palette.Ok else Palette.Warn
                        )
                    }
                    Spacer(Modifier.width(u(16)))
                    val last = ShotAccounts.lastLogin(i)
                    FitTextFill(if (last > 0L) tr("최근 ", "Last ") + lastLoginLabel(last) else lastLoginLabel(0L), 30, 22, FontWeight.Normal, Palette.Muted, Modifier.weight(1f), tnum = true)
                    Spacer(Modifier.width(u(24)))
                    if (i != Settings.admin.intValue && Settings.admin.intValue >= 0) {
                        OutlineButton(tr("관리자로", "Make admin"), 170, parked) { handing = i }
                        Spacer(Modifier.width(u(16)))
                    } else Spacer(Modifier.width(u(186)))
                    OutlineButton(tr("번호 바꾸기", "Change number"), 210, parked) {
                        changing = i
                        // users from before 0.5 have no digits yet: nothing to check
                        changeVerified = Settings.pins.value.getOrElse(i) { "" }.isEmpty()
                    }
                    Spacer(Modifier.width(u(24)))
                    OutlineButton(tr("지우기", "Remove"), 140, parked) { removing = i }
                }
                Box(Modifier.fillMaxWidth().height(u(2)).background(Palette.Line))
            }
        }
    }
    val r = list.getOrNull(removing)
    if (r != null && !Settings.removableByAnyone(removing)) {
        // still in use: only the owner (their digits) may remove it
        Dialog(onDismissRequest = { removing = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .width(u(1000))
                    .height(u(1080))
                    .background(Palette.Card, RoundedCornerShape(u(32)))
                    .padding(horizontal = u(60), vertical = u(44)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FitTextFill(tr("${r.name} 지우기", "Remove ${r.name}"), 36, 26, FontWeight.Bold, Palette.Sub, Modifier.weight(1f))
                    OutlineButton(tr("닫기", "Close"), 140, true) { removing = -1 }
                }
                Spacer(Modifier.height(u(8)))
                FitTextFill(tr("30일 넘게 로그인이 없으면 누구나 지울 수 있습니다", "Anyone can remove it after 30 days without a login"), Sz.TEXT_MIN, 22, FontWeight.Normal, Palette.Muted, Modifier.fillMaxWidth())
                Spacer(Modifier.height(u(12)))
                VerifyPinPad(removing, tr("${r.name}의 번호 4자리", "${r.name}'s 4-digit number")) {
                    Settings.removeDriver(context, removing)
                    removing = -1
                }
            }
        }
    } else if (r != null) {
        Dialog(onDismissRequest = { removing = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .width(u(900))
                    .background(Palette.Card, RoundedCornerShape(u(32)))
                    .padding(horizontal = u(60), vertical = u(56))
            ) {
                FitText(tr("${r.name} (${r.dept.ifEmpty { "–" }})을 지울까요?", "Remove ${r.name} (${r.dept.ifEmpty { "–" }})?"), 48, 32, 780, FontWeight.ExtraBold, Palette.Ink)
                Spacer(Modifier.height(u(16)))
                FitText(tr("30일 넘게 로그인이 없어 누구나 지울 수 있는 계정입니다", "No login for over 30 days, so anyone can remove it"), Sz.TEXT_MIN, 22, 780, FontWeight.Bold, Palette.Warn)
                Spacer(Modifier.height(u(16)))
                // broken on purpose: two lines
                Text(
                    tr("지나간 운행 기록에 적힌 이름은 그대로 남습니다\n지운 뒤에는 이 사람으로 로그인할 수 없습니다", "Past trips keep this name\nThis person can no longer log in"),
                    fontSize = fs(34), color = Palette.Sub, lineHeight = fs(52)
                )
                Spacer(Modifier.height(u(48)))
                Row(horizontalArrangement = Arrangement.spacedBy(u(24))) {
                    Box(Modifier.weight(1f).height(u(104)).background(Palette.Seg, RoundedCornerShape(u(30))).clickable { removing = -1 }, contentAlignment = Alignment.Center) {
                        Text(tr("취소", "Cancel"), fontSize = fs(38), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
                    }
                    Box(Modifier.weight(1f).height(u(104)).background(Palette.Bad, RoundedCornerShape(u(30))).clickable { Settings.removeDriver(context, removing); removing = -1 }, contentAlignment = Alignment.Center) {
                        Text(tr("지우기", "Remove"), fontSize = fs(38), fontWeight = FontWeight.ExtraBold, color = Palette.OnPrimary, maxLines = 1)
                    }
                }
            }
        }
    }
    val h = list.getOrNull(handing)
    val adminNow = Settings.adminDriver()
    if (h != null && adminNow != null) {
        // the current administrator's number hands the role over (nobody can make themselves administrator)
        Dialog(onDismissRequest = { handing = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .width(u(1000))
                    .height(u(1080))
                    .background(Palette.Card, RoundedCornerShape(u(32)))
                    .padding(horizontal = u(60), vertical = u(44)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FitTextFill(tr("관리자를 ${josaRo(h.name)}", "Make ${h.name} administrator"), 36, 26, FontWeight.Bold, Palette.Sub, Modifier.weight(1f))
                    OutlineButton(tr("닫기", "Close"), 140, true) { handing = -1 }
                }
                Spacer(Modifier.height(u(20)))
                VerifyPinPad(Settings.admin.intValue, tr("지금 관리자 ${adminNow.name}의 번호 4자리", "${adminNow.name}'s 4-digit number (current admin)")) {
                    Settings.setAdmin(context, handing)
                    handing = -1
                }
            }
        }
    }
    val c = list.getOrNull(changing)
    if (c != null) {
        Dialog(onDismissRequest = { changing = -1 }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier
                    .width(u(1000))
                    .height(u(1080))
                    .background(Palette.Card, RoundedCornerShape(u(32)))
                    .padding(horizontal = u(60), vertical = u(44)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FitTextFill(c.name + "  " + c.dept, 36, 26, FontWeight.Bold, Palette.Sub, Modifier.weight(1f))
                    OutlineButton(tr("닫기", "Close"), 140, true) { changing = -1 }
                }
                Spacer(Modifier.height(u(20)))
                if (!changeVerified) VerifyPinPad(changing, tr("지금 쓰는 번호 4자리", "Current 4-digit number")) { changeVerified = true }
                else SetPinPad(changing, login = false) { changing = -1 }
            }
        }
    }
}

@Composable
private fun OutlineButton(text: String, widthPx: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .width(u(widthPx))
            .height(u(80))
            .border(u(3), if (enabled) Palette.ChipLine else Palette.Gray, RoundedCornerShape(u(22)))
            .clickable { if (enabled) onClick() },
        contentAlignment = Alignment.Center
    ) {
        FitText(text, 32, 22, widthPx - 24, FontWeight.ExtraBold, if (enabled) Palette.Ink else Palette.Muted)
    }
}

@Composable
private fun Input(n: Int, label: String, value: String, max: Int, parked: Boolean, hint: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        FormBadge(n)
        Spacer(Modifier.width(u(10)))
        FitTextFill(label, 32, 22, FontWeight.Bold, Palette.Ink, Modifier.width(u(230)))
        Spacer(Modifier.width(u(12)))
        Box(Modifier.weight(1f)) { InputBox(value, max, parked, hint, onChange) }
    }
}

@Composable
private fun InputBox(value: String, max: Int, parked: Boolean, hint: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(u(Sz.TOUCH))
            .background(if (parked) Palette.Card else Palette.Seg, RoundedCornerShape(u(22)))
            .border(u(2), Palette.ChipLine, RoundedCornerShape(u(22)))
            .padding(horizontal = u(20)),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = { onChange(Logbook.clean(it, max)) },
            enabled = parked,
            singleLine = true,
            textStyle = TextStyle(fontSize = fs(32), fontWeight = FontWeight.Bold, color = Palette.Ink),
            cursorBrush = SolidColor(Palette.Blue),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(hint, fontSize = fs(Sz.TEXT_MIN), color = Palette.Muted, maxLines = 1, softWrap = false)
                    inner()
                }
            }
        )
    }
}

@Composable
private fun MonthStepper(month: Int, onChange: (Int) -> Unit) {
    Row(Modifier.height(u(Sz.TOUCH)).background(Palette.Seg, RoundedCornerShape(u(24))), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(u(Sz.TOUCH)).clickable { onChange(if (month == 1) 12 else month - 1) }, contentAlignment = Alignment.Center) { Chevron(Palette.Sub, 34, back = true) }
        Text(monthLabel(month), fontSize = fs(34), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1, softWrap = false, tnum = true, modifier = Modifier.widthIn(min = u(90)).padding(horizontal = u(6)))
        Box(Modifier.size(u(Sz.TOUCH)).clickable { onChange(if (month == 12) 1 else month + 1) }, contentAlignment = Alignment.Center) { Chevron(Palette.Sub, 34) }
    }
}

@Composable
private fun DisplayCard() {
    val context = LocalContext.current
    Panel {
        Column {
            CardLabel(tr("화면", "Display"))
            Spacer(Modifier.height(u(18)))
            SettingRow(tr("언어", "Language")) {
                val opts = listOf(Settings.LANG_SYSTEM, Settings.LANG_KO, Settings.LANG_EN)
                Segmented(listOf(tr("시스템", "System"), "한국어", "English"), opts.indexOf(Settings.language.value).coerceAtLeast(0), 150) { Settings.setLanguage(context, opts[it]) }
            }
            Spacer(Modifier.height(u(14)))
            Text(tr("바꾸면 바로 적용됩니다. 주간·야간은 차량 설정을 따릅니다.", "Changes apply right away. Day/night follows the car."), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1)
        }
    }
}

@Composable
private fun IntegrityCard(repo: TripRepository, check: ChainCheck) {
    var last by remember { mutableStateOf<Pair<ChainCheck, Long>?>(null) }
    val shown = last?.first ?: check
    // same 8-character fingerprint as the QR screen and the phone page
    val fingerprint = remember { runCatching { ExportPack.fingerprint(repo.keys.publicKeyBase64()) }.getOrElse { tr("확인 불가", "Unavailable") } }
    Panel {
        Column {
            CardLabel(tr("기록 무결성 (실제 기록)", "Record integrity (real log)"))
            Spacer(Modifier.height(u(12)))
            Row(Modifier.height(u(68)), verticalAlignment = Alignment.CenterVertically) {
                StatusIcon(if (shown.ok) Status.OK else Status.BAD, 40)
                Spacer(Modifier.width(u(16)))
                Text(
                    if (shown.ok) tr("서명·해시 연결 정상", "Signatures and hash links OK") else tr("검증 실패 #${shown.firstBadSeq}", "Failed at #${shown.firstBadSeq}"),
                    fontSize = fs(34), fontWeight = FontWeight.ExtraBold, color = if (shown.ok) Palette.Ok else Palette.Bad, maxLines = 1
                )
            }
            InfoRow(tr("기록 수", "Records"), if (Settings.isEnglish) Fmt.count(shown.count) else Fmt.count(shown.count) + "건")
            InfoRow(tr("마지막 해시", "Last hash"), shown.headHash.take(16) + "…")
            InfoRow(tr("키 지문", "Key fingerprint"), fingerprint)
            Spacer(Modifier.height(u(14)))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SecondaryButton(tr("다시 검증", "Verify again"), 330) {
                    val c = repo.reverify()
                    DiagLog.w("설정에서 재검증: ok=${c.ok} count=${c.count}")
                    last = c to System.currentTimeMillis()
                }
                Spacer(Modifier.width(u(20)))
                last?.let {
                    Text(tr("확인 ", "Checked ") + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it.second)), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub)
                }
            }
        }
    }
}

@Composable
private fun PermissionsCard(missingPermissions: List<String>, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    Panel {
        Column {
            Row(Modifier.fillMaxWidth().height(u(if (missingPermissions.isEmpty()) 52 else Sz.TOUCH)), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("권한", "Permissions"), fontSize = fs(Sz.LABEL), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
                Spacer(Modifier.weight(1f))
                // for a permission the system no longer asks about ("don't ask again")
                if (missingPermissions.isNotEmpty()) {
                    Chip(tr("시스템 설정 열기", "Open system settings"), Palette.Seg, Palette.Sub) {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.onFailure { DiagLog.w("앱 설정 열기 실패: $it") }
                    }
                }
            }
            Spacer(Modifier.height(u(18)))
            PermissionStatus(
                tr("속도", "Speed"),
                listOf(tr("이동 없는 기어 조작을 걸러냄", "Skips gear shifts without moving"), tr("시스템 창에는 '위치 정보'로 표시", "System prompt calls it \"Location\"")),
                missingPermissions.none { it.endsWith("CAR_SPEED") },
                onRequestPermissions
            )
            Spacer(Modifier.height(u(12)))
            PermissionStatus(
                tr("알림", "Notifications"),
                listOf(tr("도착하면 목적 버튼을 띄움", "Purpose buttons on arrival")),
                missingPermissions.none { it.endsWith("POST_NOTIFICATIONS") },
                onRequestPermissions
            )
        }
    }
}

@Composable
private fun AboutCard(demo: Boolean, onToggleDemo: () -> Unit, onReplayOnboarding: () -> Unit) {
    val context = LocalContext.current
    Panel {
        Column {
            CardLabel(tr("앱 정보", "About"))
            Spacer(Modifier.height(u(8)))
            val info = remember {
                runCatching {
                    val pi = context.packageManager.getPackageInfo(context.packageName, 0)
                    val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
                    "${pi.versionName} ($code)"
                }.getOrElse { "–" }
            }
            InfoRow(tr("버전", "Version"), info)
            InfoRow(tr("차량 SDK", "Vehicle SDK"), "Pleos Vehicle SDK $VEHICLE_SDK_VERSION")
            SettingRow(tr("예시 데이터", "Sample data")) {
                Segmented(listOf(tr("끄기", "Off"), tr("보기", "Show")), if (demo) 1 else 0, 150) { if ((it == 1) != demo) onToggleDemo() }
            }
            Spacer(Modifier.height(u(10)))
            SecondaryButton(tr("소개 화면 다시 보기", "Show intro again"), 440, onReplayOnboarding)
        }
    }
}

@Composable
private fun SettingRow(label: String, control: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().height(u(104)), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = fs(36), fontWeight = FontWeight.Bold, color = Palette.Ink, maxLines = 1)
        Spacer(Modifier.weight(1f))
        control()
    }
}

/** Reason lines are written to fit one line each (~17 Korean / ~32 Latin characters at 30 px). */
@Composable
private fun PermissionStatus(label: String, reasons: List<String>, granted: Boolean, onRequest: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = u(112))
            .background(Palette.Tile, RoundedCornerShape(u(22)))
            .padding(horizontal = u(22), vertical = u(10)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusIcon(if (granted) Status.OK else Status.WARN, 40)
        Spacer(Modifier.width(u(16)))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = fs(34), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
            reasons.forEach { Text(it, fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub, maxLines = 1) }
        }
        Spacer(Modifier.width(u(12)))
        if (granted) {
            Text(tr("허용됨", "Allowed"), fontSize = fs(Sz.LABEL), fontWeight = FontWeight.ExtraBold, color = Palette.Ok, maxLines = 1)
        } else {
            Chip(tr("허용하기", "Allow"), Palette.Primary, Palette.OnPrimary, onRequest)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(u(64)), verticalAlignment = Alignment.CenterVertically) {
        FitText(label, Sz.LABEL, 26, 280, FontWeight.Normal, Palette.Sub, Modifier.width(u(300)))
        FitText(value, Sz.LABEL, 24, 840, FontWeight.Bold, Palette.Ink, tnum = true)
    }
}
