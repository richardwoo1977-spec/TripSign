// ver 1.0.0 | 2026-11-07 21:40
package io.tripsign.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.tripsign.app.data.DiagLog

/**
 * Debug builds only (src/debug): says a test sentence with the running service's voice, so wordings can be compared
 * by ear and by recording without a new build each time.
 *
 *   adb shell am broadcast --user 10 -n io.tripsign.app/.service.DebugSpeakReceiver --es text "'문장'"
 *
 * Does nothing when the service is not running.
 */
class DebugSpeakReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra("text")?.trim().orEmpty().take(200)
        if (text.isEmpty()) return
        val hook = TripService.speakHook
        if (hook == null) {
            DiagLog.w("음성 시험 실패: 서비스가 실행 중이 아님")
            return
        }
        hook(text)
    }
}
