// ver 1.0.0 | 2026-11-07 13:25
package io.tripsign.app.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import io.tripsign.app.core.Driver
import io.tripsign.app.core.LogRow
import io.tripsign.app.data.tr

/*
 * 0.5.2g: the 미인증 운행 check, made unmistakable.
 * - At the next car start the login screen shows the 미인증 운행 that is still open: "내가 운전했다" leads to the phone
 *   check, "내가 아니다" is written into the ledger (DENY) and closes the card.
 * - After the phone check a dialog says plainly what changed (the user was confirmed; the 미인증 mark stays) with the
 *   trip's facts, and 확인 goes back to where it was. Nobody is logged in by it: confirming a past drive is not
 *   saying "I drive next".
 */

/** "11/7 (토) 12:13 – 12:14 · 1분" */
fun tripWhen(r: LogRow): String = Fmt.day(r.startMs) + " " + Fmt.time(r.startMs) + " – " + Fmt.time(r.endMs) + " · " + Fmt.duration(r.endMs - r.startMs)

/** "9 → 11 km · 주행 2 km" (or only the distance when the odometer was not read) */
fun tripOdometer(r: LogRow): String =
    if (r.odoBefore != null && r.odoAfter != null) {
        Fmt.n(r.odoBefore) + " → " + Fmt.n(r.odoAfter) + " km" + tr(" · 주행 ", " · ") + Fmt.n(r.km) + " km"
    } else {
        tr("계기판 값 없음 · 주행 ", "No odometer · ") + Fmt.n(r.km) + " km"
    }

private fun driverLabel(d: Driver): String = if (d.dept.isBlank()) d.name else d.dept + " " + d.name

/** On the login screen ("누가 운전하나요?") at the first car start after a 미인증 운행. */
@Composable
fun PendingUnverifiedCard(r: LogRow, onMine: () -> Unit, onNotMe: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(u(176))
            .background(Palette.Card, RoundedCornerShape(u(28)))
            .padding(horizontal = u(34), vertical = u(24)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            FitTextFill(tr("직전 운행이 미인증으로 남아 있습니다", "The last trip is still unverified"), 38, 26, FontWeight.ExtraBold, Palette.Warn, Modifier.fillMaxWidth())
            Spacer(Modifier.height(u(10)))
            FitTextFill(tripWhen(r) + " · " + tripOdometer(r), 32, 22, FontWeight.SemiBold, Palette.Sub, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.width(u(24)))
        Box(
            Modifier
                .width(u(300))
                .height(u(112))
                .background(Palette.Primary, RoundedCornerShape(u(30)))
                .clickable { onMine() },
            contentAlignment = Alignment.Center
        ) {
            Text(tr("내가 운전했다", "I drove it"), fontSize = fs(36), fontWeight = FontWeight.ExtraBold, color = Palette.OnPrimary, maxLines = 1)
        }
        Spacer(Modifier.width(u(16)))
        Box(
            Modifier
                .width(u(260))
                .height(u(112))
                .background(Palette.Seg, RoundedCornerShape(u(30)))
                .clickable { onNotMe() },
            contentAlignment = Alignment.Center
        ) {
            Text(tr("내가 아니다", "Not me"), fontSize = fs(36), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1)
        }
    }
}

/** After a phone check of a 미인증 운행: what was confirmed, and what did not change. */
@Composable
fun ConfirmedTripDialog(r: LogRow, d: Driver, onOk: () -> Unit) {
    Scrim()
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .width(u(1200))
                .shadow(u(16), RoundedCornerShape(u(36)))
                .background(Palette.Card, RoundedCornerShape(u(36)))
                .padding(horizontal = u(50), vertical = u(40))
        ) {
            FitTextFill(tr("미인증 운행의 운전자를 확인했습니다", "The driver of the unverified trip is confirmed"), 46, 30, FontWeight.ExtraBold, Palette.Ink, Modifier.fillMaxWidth())
            Spacer(Modifier.height(u(30)))
            FactRow(tr("운전자", "Driver"), driverLabel(d))
            FactRow(tr("운행", "Trip"), tripWhen(r))
            FactRow(tr("계기판", "Odometer"), tripOdometer(r))
            Spacer(Modifier.height(u(24)))
            // two lines on purpose, each fitted to one line (a long name shrinks the line instead of wrapping it)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.Tile, RoundedCornerShape(u(22)))
                    .padding(horizontal = u(30), vertical = u(20))
            ) {
                FitTextFill(
                    tr("미인증 표시는 남고, '사후 확인: ${d.name}'이 함께 기록됩니다", "The unverified mark stays; 'confirmed later: ${d.name}' is added"),
                    32, 22, FontWeight.SemiBold, Palette.Sub, Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(u(10)))
                FitTextFill(
                    tr("업무 거리 반영은 관리자가 구분을 정한 뒤입니다", "It counts as business km once the admin sets the purpose"),
                    32, 22, FontWeight.SemiBold, Palette.Sub, Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(u(30)))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                PrimaryButton(tr("확인", "OK"), 420, onOk)
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().height(u(72)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
        Text(label, fontSize = fs(34), color = Palette.Sub, maxLines = 1, modifier = Modifier.width(u(200)))
        FitTextFill(value, 40, 26, FontWeight.Bold, Palette.Ink, Modifier.weight(1f))
    }
}
