// ver 1.4.0 | 2026-11-07 13:25
package io.tripsign.app.ui

import io.tripsign.app.core.Purpose
import io.tripsign.app.data.Settings
import io.tripsign.app.data.tr
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Fmt {
    private val loc: Locale get() = if (Settings.isEnglish) Locale.US else Locale.KOREA
    fun km0(v: Double): String = String.format(loc, "%,.0f", v)
    fun km1(v: Double): String = String.format(loc, "%,.1f", v)
    fun count(n: Int): String = String.format(loc, "%,d", n)
    /** Whole km (integer rule): 47,526 */
    fun n(v: Long): String = String.format(loc, "%,d", v)
    fun pct0(v: Double): String = String.format(loc, "%.0f%%", v * 100.0)
    fun pct1(v: Double): String = String.format(loc, "%.1f", v * 100.0)
    fun time(ms: Long): String = SimpleDateFormat("HH:mm", loc).format(Date(ms))
    fun day(ms: Long): String = SimpleDateFormat(if (Settings.isEnglish) "EEE, MMM d" else "M/d (E)", loc).format(Date(ms))
    /** 0.5.2g: a drive's length, whole minutes (at least 1): "1분", "1시간 5분" / "1 min", "1 h 5 min". */
    fun duration(ms: Long): String {
        val min = ((ms + 30_000L) / 60_000L).coerceAtLeast(1L)
        val h = min / 60
        val m = min % 60
        return when {
            h == 0L -> tr("${m}분", "$m min")
            m == 0L -> tr("${h}시간", "$h h")
            else -> tr("${h}시간 ${m}분", "$h h $m min")
        }
    }
}

fun purposeLabel(p: Purpose): String = when (p) {
    Purpose.BUSINESS -> tr("업무", "Business")
    Purpose.COMMUTE -> tr("출퇴근", "Commute")
    Purpose.PERSONAL -> tr("개인", "Personal")
}

val weekdayLabels: List<String>
    get() = if (Settings.isEnglish) listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun") else listOf("월", "화", "수", "목", "금", "토", "일")

fun monthLabel(m: Int): String = if (Settings.isEnglish) listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")[m - 1] else "${m}월"

fun hourLabel(h: Int): String = if (Settings.isEnglish) "${h}h" else "${h}시"

/** Labels for Analytics.comparisons(): day, week, month, same month last year. */
val comparisonLabels: List<String>
    get() = listOf(tr("전일", "Day"), tr("전주", "Week"), tr("전월", "Month"), tr("전년", "Year"))

/** English singular/plural; Korean never changes. */
fun plural(n: Int, one: String, many: String): String = if (n == 1) one else many
