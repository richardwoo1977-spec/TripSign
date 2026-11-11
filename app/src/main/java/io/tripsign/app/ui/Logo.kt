// ver 1.0.2 | 2026-11-04 13:35
package io.tripsign.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * App logo drawn in code (icon D1, chosen 11/4: logbook with a road + green seal).
 * Coordinates follow the 108-unit adaptive-icon canvas, cropped to 18..90.
 */
@Composable
fun AppLogo(sizePx: Int) {
    val navy = Color(0xFF173F7A)
    val seal = Color(0xFF2FBF8F)
    val faint = Color(0xFF9DB3D6)
    Canvas(Modifier.size(u(sizePx))) {
        val k = size.width / 72f
        fun p(x: Float, y: Float) = Offset((x - 18f) * k, (y - 18f) * k)
        drawRoundRect(navy, Offset.Zero, Size(size.width, size.height), CornerRadius(16f * k, 16f * k))
        drawRoundRect(Color.White, p(32f, 24f), Size(40f * k, 54f * k), CornerRadius(6f * k, 6f * k))
        drawLine(faint, p(38f, 34f), p(54f, 34f), strokeWidth = 3.5f * k, cap = StrokeCap.Round)
        val road = Path().apply {
            val a = p(37f, 72f); val b = p(45f, 40f); val c = p(49f, 40f); val d = p(57f, 72f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        drawPath(road, navy)
        drawLine(Color.White, p(47f, 45f), p(47f, 49f), strokeWidth = 2f * k, cap = StrokeCap.Round)
        drawLine(Color.White, p(47f, 54f), p(47f, 59f), strokeWidth = 2f * k, cap = StrokeCap.Round)
        drawLine(Color.White, p(47f, 64f), p(47f, 70f), strokeWidth = 2f * k, cap = StrokeCap.Round)
        drawCircle(navy, radius = 13.5f * k, center = p(68f, 70f))
        drawCircle(seal, radius = 12f * k, center = p(68f, 70f))
        val check = Path().apply {
            val a = p(62.5f, 70f); val b = p(66.5f, 74f); val c = p(73.5f, 66f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y)
        }
        drawPath(check, Color.White, style = Stroke(width = 3.5f * k, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}
