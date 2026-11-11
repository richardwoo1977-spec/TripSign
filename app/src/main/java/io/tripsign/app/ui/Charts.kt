// ver 1.3.0 | 2026-11-04 14:05
package io.tripsign.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.tripsign.app.core.Comparison
import io.tripsign.app.data.tr
import kotlin.math.abs
import kotlin.math.max

@Composable
fun Donut(parts: List<Pair<Float, Color>>, sizePx: Int, strokePx: Int, center: @Composable () -> Unit) {
    Box(Modifier.size(u(sizePx)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * strokePx / sizePx
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val total = parts.sumOf { it.first.toDouble() }.toFloat()
            if (total <= 0f) {
                drawArc(Palette.Line, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            } else {
                var start = -90f
                for ((value, color) in parts) {
                    val sweep = 360f * value / total
                    if (sweep > 0.5f) {
                        drawArc(color, start, max(0.5f, sweep - 2f), false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                    }
                    start += sweep
                }
            }
        }
        center()
    }
}

@Composable
fun SparkBars(values: List<Float>, widthPx: Int, heightPx: Int) {
    Canvas(Modifier.width(u(widthPx)).height(u(heightPx))) {
        if (values.isEmpty()) return@Canvas
        val maxV = max(values.maxOrNull() ?: 0f, 0.0001f)
        val gap = size.width * 0.27f / values.size
        val bw = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            // zero months get no bar (a sliver would read as "a little driving")
            if (v <= 0f) return@forEachIndexed
            val h = size.height * v / maxV
            val color = if (i == values.lastIndex) Palette.Blue else Palette.BarSoft
            drawRoundRect(
                color,
                topLeft = Offset(i * (bw + gap), size.height - h),
                size = Size(bw, h),
                cornerRadius = CornerRadius(bw * 0.25f, bw * 0.25f)
            )
        }
    }
}

/** One comparison column: change on top, before/now bars, a baseline, then the name and the km. */
@Composable
fun CompareGroup(c: Comparison, label: String, widthPx: Int, barHeightPx: Int) {
    Column(Modifier.width(u(widthPx)), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!c.hasBefore) {
            Box(Modifier.height(u(barHeightPx + 70)), contentAlignment = Alignment.Center) {
                Text(tr("비교 기록\n없음", "No\nhistory"), fontSize = fs(30), color = Palette.Muted, textAlign = TextAlign.Center, lineHeight = fs(40))
            }
        } else {
            val up = c.deltaPct >= 0.0
            FitText(
                (if (up) "▲" else "▼") + String.format(java.util.Locale.US, "%.0f%%", abs(c.deltaPct)),
                48,
                32,
                widthPx - 12,
                FontWeight.ExtraBold,
                if (up) Palette.Blue else Palette.Down
            )
            Spacer(Modifier.height(u(14)))
            val barsW = (widthPx * 0.82f).toInt()
            Canvas(Modifier.width(u(barsW)).height(u(barHeightPx))) {
                val mx = max(max(c.now, c.before), 0.0001).toFloat()
                val bw = size.width * 0.38f
                val hb = size.height * c.before.toFloat() / mx
                val hn = size.height * c.now.toFloat() / mx
                val r = CornerRadius(bw * 0.16f, bw * 0.16f)
                if (hb > 0f) drawRoundRect(Palette.Gray, Offset(size.width * 0.07f, size.height - hb), Size(bw, hb), r)
                if (hn > 0f) drawRoundRect(Palette.Blue, Offset(size.width * 0.55f, size.height - hn), Size(bw, hn), r)
            }
        }
        Box(Modifier.width(u(widthPx - 20)).height(u(2)).background(Palette.Line))
        Spacer(Modifier.height(u(16)))
        Text(label, fontSize = fs(36), fontWeight = FontWeight.ExtraBold, color = Palette.Ink, maxLines = 1)
        FitText(Fmt.km0(c.now) + " km", 30, 24, widthPx - 8, FontWeight.Normal, Palette.Sub)
    }
}

/**
 * Weekday x 2-hour heatmap (07-21 h) sized to fill its card. Cells are blue by distance; the
 * column of the busiest business slot ([peakBin]) is drawn in [Palette.Peak] instead, and its
 * hour label too, so the finding is visible without a sentence.
 */
@Composable
fun Heatmap(grid: Array<DoubleArray>, peakBin: Int?, cellWPx: Int, cellHPx: Int, gapPx: Int) {
    val days = weekdayLabels
    val maxV = grid.maxOf { row -> row.maxOrNull() ?: 0.0 }.coerceAtLeast(0.0001)
    val dayW = 76
    Column(verticalArrangement = Arrangement.spacedBy(u(gapPx))) {
        for (w in 0 until 7) {
            Row(Modifier.height(u(cellHPx)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(u(gapPx))) {
                Text(days[w], fontSize = fs(32), fontWeight = FontWeight.Bold, color = Palette.Sub, maxLines = 1, softWrap = false, modifier = Modifier.width(u(dayW)))
                for (b in 0 until 7) {
                    val v = grid[w][b] / maxV
                    val base = if (b == peakBin) Palette.Peak else Palette.Blue
                    Box(
                        Modifier
                            .width(u(cellWPx))
                            .height(u(cellHPx))
                            .background(
                                if (v > 0) base.copy(alpha = (0.16 + 0.84 * v).toFloat()) else Palette.Tile,
                                RoundedCornerShape(u(12))
                            )
                    )
                }
            }
        }
        Row(Modifier.height(u(48)), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(u(dayW + gapPx)))
            for (b in 0 until 7) {
                val peak = b == peakBin
                Box(Modifier.width(u(cellWPx + gapPx)), contentAlignment = Alignment.CenterStart) {
                    Text(
                        hourLabel(7 + b * 2),
                        fontSize = fs(30),
                        fontWeight = if (peak) FontWeight.ExtraBold else FontWeight.Normal,
                        color = if (peak) Palette.Peak else Palette.Sub,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}
