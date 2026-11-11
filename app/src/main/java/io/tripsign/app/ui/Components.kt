// ver 1.5.0 | 2026-11-04 20:50
package io.tripsign.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import io.tripsign.app.data.tr
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-car sizing rules (design px on the 1706 x 1210 app canvas):
 * - any text >= 30 px, labels >= 32 px, button text >= 34 px
 * - every touch target >= 88 px high (header controls 96-100 px)
 */
object Sz {
    const val TEXT_MIN = 30
    const val LABEL = 32
    const val BUTTON = 36
    const val TOUCH = 88
    const val HEADER = 104
}

enum class Status { OK, WARN, BAD }

/** Drawn status mark (filled circle + check or exclamation). */
@Composable
fun StatusIcon(status: Status, sizePx: Int) {
    val fill = when (status) {
        Status.OK -> Palette.Ok
        Status.WARN -> Palette.Warn
        Status.BAD -> Palette.Bad
    }
    Canvas(Modifier.size(u(sizePx))) {
        val w = size.width
        drawCircle(fill, radius = w / 2f)
        val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (status == Status.OK) {
            val p = Path().apply {
                moveTo(w * 0.29f, w * 0.52f)
                lineTo(w * 0.44f, w * 0.67f)
                lineTo(w * 0.72f, w * 0.37f)
            }
            drawPath(p, Color.White, style = stroke)
        } else {
            drawLine(Color.White, Offset(w / 2f, w * 0.27f), Offset(w / 2f, w * 0.56f), strokeWidth = w * 0.12f, cap = StrokeCap.Round)
            drawCircle(Color.White, radius = w * 0.07f, center = Offset(w / 2f, w * 0.73f))
        }
    }
}

@Composable
fun Chevron(color: Color, sizePx: Int, back: Boolean = false) {
    Canvas(Modifier.size(u(sizePx))) {
        val w = size.width
        val p = Path().apply {
            if (back) {
                moveTo(w * 0.62f, w * 0.2f); lineTo(w * 0.32f, w * 0.5f); lineTo(w * 0.62f, w * 0.8f)
            } else {
                moveTo(w * 0.38f, w * 0.2f); lineTo(w * 0.68f, w * 0.5f); lineTo(w * 0.38f, w * 0.8f)
            }
        }
        drawPath(p, color, style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Drawn gear for the settings button: 8 trapezoid teeth with a round hole (even-odd fill).
 * Same geometry as work/gear/gear.py, which renders the reviewed preview.
 */
@Composable
fun GearIcon(color: Color, sizePx: Int) {
    Canvas(Modifier.size(u(sizePx))) {
        val w = size.width
        val teeth = 8
        val rOut = 0.47f * w
        val rRoot = 0.355f * w
        val halfTip = 9.0
        val halfRoot = 15.5
        val c = Offset(w / 2f, w / 2f)
        val path = Path().apply {
            fillType = PathFillType.EvenOdd
            var first = true
            for (i in 0 until teeth) {
                val a = i * 360.0 / teeth - 90.0
                val angles = doubleArrayOf(a - halfRoot, a - halfTip, a + halfTip, a + halfRoot)
                val radii = floatArrayOf(rRoot, rOut, rOut, rRoot)
                for (k in 0 until 4) {
                    val t = Math.toRadians(angles[k])
                    val r = radii[k]
                    val x = c.x + r * cos(t).toFloat()
                    val y = c.y + r * sin(t).toFloat()
                    if (first) { moveTo(x, y); first = false } else lineTo(x, y)
                }
            }
            close()
            addOval(Rect(center = c, radius = 0.135f * w))
        }
        drawPath(path, color)
    }
}

/** Font size (design px, before the text-size setting) at which [text] fits [availPx] real px. */
@Composable
private fun fitFs(text: String, maxFsPx: Int, minFsPx: Int, availPx: Float, weight: FontWeight, tnum: Boolean = false): Int {
    val measurer = rememberTextMeasurer()
    val base = fs(maxFsPx)
    val style = LocalTextStyle.current.copy(fontSize = base, fontWeight = weight, fontFeatureSettings = if (tnum) "tnum" else null)
    val measured = remember(text, base, weight, tnum) {
        measurer.measure(text = text, style = style, maxLines = 1, softWrap = false).size.width
    }
    val scale = if (measured <= 0 || measured <= availPx) 1f else availPx / measured
    return (maxFsPx * scale).toInt().coerceAtLeast(minFsPx)
}

/**
 * Single-line text that shrinks to fit [maxWidthPx] (design px) instead of wrapping or clipping.
 * Measured with the real font and the current text-size setting, so it holds for 1,927 km and
 * 1,040,112 km, in normal and large text alike.
 */
@Composable
fun FitText(
    text: String,
    maxFsPx: Int,
    minFsPx: Int,
    maxWidthPx: Int,
    weight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
    tnum: Boolean = false
) {
    val avail = with(LocalDensity.current) { u(maxWidthPx).toPx() }
    val px = fitFs(text, maxFsPx, minFsPx, avail, weight, tnum)
    Text(text, fontSize = fs(px), fontWeight = weight, color = color, maxLines = 1, softWrap = false, lineHeight = fs((px * 1.1f).toInt()), modifier = modifier, tnum = tnum)
}

/** Like [FitText] but takes whatever width the parent gives it (use with Modifier.weight). */
@Composable
fun FitTextFill(
    text: String,
    maxFsPx: Int,
    minFsPx: Int,
    weight: FontWeight,
    color: Color,
    modifier: Modifier = Modifier,
    tnum: Boolean = false,
    alignEnd: Boolean = false
) {
    BoxWithConstraints(modifier, contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        val avail = constraints.maxWidth.toFloat()
        val px = fitFs(text, maxFsPx, minFsPx, avail, weight, tnum)
        Text(text, fontSize = fs(px), fontWeight = weight, color = color, maxLines = 1, softWrap = false, lineHeight = fs((px * 1.1f).toInt()), tnum = tnum)
    }
}

@Composable
fun Card(widthPx: Int, heightPx: Int, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(u(32))
    Box(
        Modifier
            .width(u(widthPx))
            .height(u(heightPx))
            .shadow(u(4), shape, clip = false)
            .background(Palette.Card, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = u(32), vertical = u(30))
    ) { content() }
}

@Composable
fun CardLabel(text: String, openable: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = fs(Sz.LABEL), fontWeight = FontWeight.Bold, color = Palette.Sub)
        if (openable) {
            Spacer(Modifier.weight(1f))
            Text(tr("자세히", "Details"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Muted)
            Chevron(Palette.Muted, 32)
        }
    }
}

@Composable
fun EmptyNote(text: String? = null) {
    Column(Modifier.padding(top = u(36))) {
        Text(tr("기록 없음", "No records"), fontSize = fs(44), fontWeight = FontWeight.ExtraBold, color = Palette.Muted)
        Spacer(Modifier.height(u(10)))
        Text(text ?: tr("운행이 기록되면 표시됩니다", "Appears once trips are recorded"), fontSize = fs(Sz.TEXT_MIN), color = Palette.Sub)
    }
}

@Composable
fun Segmented(options: List<String>, selected: Int, itemWidthPx: Int = 112, padPx: Int = 18, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .background(Palette.Seg, RoundedCornerShape(u(24)))
            .padding(u(6))
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            // minimum width, not fixed: large text or English may need more
            Box(
                Modifier
                    .widthIn(min = u(itemWidthPx))
                    .height(u(Sz.TOUCH))
                    .then(if (on) Modifier.shadow(u(2), RoundedCornerShape(u(20))) else Modifier)
                    .background(if (on) Palette.SegOn else Color.Transparent, RoundedCornerShape(u(20)))
                    .clickable { onSelect(i) }
                    .padding(horizontal = u(padPx)),
                contentAlignment = Alignment.Center
            ) {
                Text(label, fontSize = fs(34), fontWeight = FontWeight.Bold, color = if (on) Palette.Blue else Palette.Sub, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
fun Chip(text: String, bg: Color, fg: Color, onClick: (() -> Unit)? = null) {
    Box(
        Modifier
            .heightIn(min = u(if (onClick != null) Sz.TOUCH else 60))
            .background(bg, RoundedCornerShape(u(30)))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = u(26), vertical = u(10)),
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = fs(Sz.TEXT_MIN), fontWeight = FontWeight.Bold, color = fg, maxLines = 1)
    }
}

@Composable
fun NavChip(text: String, back: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(u(96))
            .widthIn(min = u(170))
            .background(if (back) Palette.Seg else Palette.BlueSoft, RoundedCornerShape(u(30)))
            .clickable { onClick() }
            .padding(horizontal = u(24)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (back) Chevron(Palette.Sub, 36, back = true)
        Text(text, fontSize = fs(34), fontWeight = FontWeight.Bold, color = if (back) Palette.Sub else Palette.Blue, maxLines = 1)
        if (!back) Chevron(Palette.Blue, 36)
    }
}

@Composable
fun IconButton96(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(u(100))
            .background(Palette.Seg, RoundedCornerShape(u(28)))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun PrimaryButton(text: String, widthPx: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .width(u(widthPx))
            .height(u(112))
            .background(Palette.Primary, RoundedCornerShape(u(30)))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = fs(40), fontWeight = FontWeight.ExtraBold, color = Palette.OnPrimary)
    }
}

@Composable
fun SecondaryButton(text: String, widthPx: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .width(u(widthPx))
            .height(u(112))
            .background(Palette.Seg, RoundedCornerShape(u(30)))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text, fontSize = fs(38), fontWeight = FontWeight.Bold, color = Palette.Sub)
    }
}

/** Page position dots, same look as the onboarding dots (current page is a wide pill). */
@Composable
fun PageDots(current: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(u(12)), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until total) {
            Box(
                Modifier
                    .width(u(if (i == current) 44 else 16))
                    .height(u(16))
                    .background(if (i == current) Palette.Blue else Palette.Gray, RoundedCornerShape(u(8)))
            )
        }
    }
}

/** Dims everything behind a modal and swallows touches. */
@Composable
fun Scrim() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { }
    )
}
