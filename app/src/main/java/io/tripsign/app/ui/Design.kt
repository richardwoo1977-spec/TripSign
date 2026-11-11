// ver 1.4.0 | 2026-11-04 20:50
package io.tripsign.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * The approved screen design is drawn on a 1706 x 1210 px canvas (Pleos Connect app area).
 * LocalUnit = real pixels per design pixel, so the layout matches the design on any density.
 */
val LocalUnit = staticCompositionLocalOf { 1f }

const val DESIGN_W = 1706f
const val DESIGN_H = 1210f

@Composable
fun u(designPx: Number): Dp {
    val unit = LocalUnit.current
    return with(LocalDensity.current) { (designPx.toFloat() * unit).toDp() }
}

/**
 * Text size in design pixels. Ignores the system font scale (the glance layout is fixed) but
 * follows the app's own text-size setting: Large = x1.2, and every screen is budgeted for it.
 */
@Composable
fun fs(designPx: Number): TextUnit {
    val unit = LocalUnit.current
    val scale = io.tripsign.app.data.Settings.textScale
    return with(LocalDensity.current) { (designPx.toFloat() * unit * scale).toSp() }
}

/**
 * Reviewed palette (v0.2): deep navy text, one calm primary blue for data and actions,
 * teal-green only for "verified", amber only for "needs attention". Muted text keeps >= 4.5:1 on its card.
 * Every colour has a dark-mode pair; reading a colour inside a composable follows Settings.theme.
 */
object Palette {
    private val d: Boolean get() = io.tripsign.app.data.Settings.isDark
    private fun c(light: Long, dark: Long) = Color(if (d) dark else light)

    val Bg get() = c(0xFFF3F5F8, 0xFF0F1521)
    val Card get() = c(0xFFFFFFFF, 0xFF1A2332)
    val Blue get() = c(0xFF1F5FD6, 0xFF5B8DEF)
    /**
     * Filled button with white text. Dark #356CDD: white text 4.84:1 and the fill against the card 3.26:1
     * (v5 review). Palette.Blue in dark mode is a text/data colour and fails white text (about 3.3:1).
     */
    val Primary get() = c(0xFF1F5FD6, 0xFF356CDD)
    /** Outline of a chosen purpose chip: 4.29:1 (light) / 4.52:1 (dark) against the card, above the 3:1 needed for a control edge. */
    val ChipLine get() = c(0xFF6E7B8F, 0xFF7D8AA0)
    /** Form-number badge (①~⑬): neutral grey so it never looks like a button. */
    val BadgeBg get() = c(0xFFEEF1F5, 0xFF2A3444)
    val Blue2 get() = c(0xFF8DB0EE, 0xFF35548F)
    val BlueSoft get() = c(0xFFE9F0FD, 0xFF1E2D4A)
    val BarSoft get() = c(0xFFD5DFF2, 0xFF2A3A57)
    val Gray get() = c(0xFFCBD3DE, 0xFF3A4556)
    val Line get() = c(0xFFE3E8EF, 0xFF2A3444)
    val Ink get() = c(0xFF0B1B33, 0xFFE8EDF5)
    val Sub get() = c(0xFF556275, 0xFFA9B4C4)
    val Muted get() = c(0xFF6B7789, 0xFF8F9BAD)
    val Ok get() = c(0xFF12996F, 0xFF34C08F)
    val OkSoft get() = c(0xFFE3F5EE, 0xFF173A30)
    val Down get() = c(0xFFB45309, 0xFFF0A04B)
    val Warn get() = c(0xFFB45309, 0xFFF0A04B)
    val WarnSoft get() = c(0xFFFDF1E3, 0xFF3B2A17)
    val Seg get() = c(0xFFE6EAF0, 0xFF222C3B)
    val SegOn get() = c(0xFFFFFFFF, 0xFF33415A)
    val Tile get() = c(0xFFF5F7FA, 0xFF212B3A)
    val Bad get() = c(0xFFB42318, 0xFFF2645A)
    val BadSoft get() = c(0xFFFDECEA, 0xFF3D1D1D)
    /** Only for "the busiest business time slot" on the heatmap; used nowhere else. */
    val Peak get() = c(0xFF7A5AF8, 0xFFA48BFF)
    val OnPrimary get() = Color(0xFFFFFFFF)
    val Scrim get() = c(0x8C000000, 0xB3000000)
}
