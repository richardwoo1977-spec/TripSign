// ver 1.1.0 | 2026-11-04 20:50
package io.tripsign.app.ui

import android.util.Log
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit

/**
 * Layout check (debug builds only). Every text in the app goes through [Text] below, which reports
 * to logcat tag "TripSignLayout" when a text is cut off (CLIP) or wraps to a second line that was
 * not written into it with "\n" (WRAP). tools/layout_check.sh collects these per screen.
 */
object LayoutCheck {
    @Volatile var enabled = false
    @Volatile var tag = "-"
    private val seen = HashSet<String>()

    fun report(kind: String, text: String) {
        if (!enabled) return
        val key = "$tag|$kind|$text"
        synchronized(seen) { if (!seen.add(key)) return }
        Log.w("TripSignLayout", "$tag | $kind | ${text.replace('\n', '/')}")
    }

    fun mark(newTag: String) {
        tag = newTag
        if (enabled) Log.w("TripSignLayout", "$newTag | SHOT | -")
    }
}

/**
 * Same parameters as the material3 Text calls used in this app, plus the layout check.
 * [tnum] = tabular figures: Asta Sans digits are proportional by default (1111111 is narrower than
 * 0000000), so every number column turns this on to keep digits aligned.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    tnum: Boolean = false
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = fontSize,
        fontWeight = fontWeight,
        textAlign = textAlign,
        lineHeight = lineHeight,
        softWrap = softWrap,
        maxLines = maxLines,
        style = if (tnum) LocalTextStyle.current.copy(fontFeatureSettings = "tnum") else LocalTextStyle.current,
        onTextLayout = { r ->
            if (LayoutCheck.enabled) {
                if (r.hasVisualOverflow) LayoutCheck.report("CLIP", text)
                else if (r.lineCount > 1 && !text.contains('\n')) LayoutCheck.report("WRAP", text)
            }
        }
    )
}
