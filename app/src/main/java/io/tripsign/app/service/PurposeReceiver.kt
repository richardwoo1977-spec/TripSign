// ver 1.1.0 | 2026-11-06 16:48
package io.tripsign.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.tripsign.app.core.Purpose
import io.tripsign.app.data.DiagLog
import io.tripsign.app.data.Live
import io.tripsign.app.data.Settings
import io.tripsign.app.data.TripRepository

/**
 * One-touch purpose from the arrival notification buttons, and 건너뛰기 when it is swiped away.
 * 0.5.2: a button works only inside the driver's window, parked with no drive open; after that the purpose
 * stays 미분류 and only the administrator may set it (in the app).
 */
class PurposeReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION = "io.tripsign.app.SET_PURPOSE"
        const val ACTION_SKIP = "io.tripsign.app.SKIP_PURPOSE"
        const val EXTRA_TRIP = "tripId"
        const val EXTRA_PURPOSE = "purpose"
    }

    override fun onReceive(context: Context, intent: Intent) {
        DiagLog.init(context)
        Settings.load(context)
        val tripId = intent.getStringExtra(EXTRA_TRIP) ?: return
        val repo = TripRepository.get(context)
        when (intent.action) {
            ACTION -> {
                val purpose = runCatching { Purpose.valueOf(intent.getStringExtra(EXTRA_PURPOSE) ?: "") }.getOrNull() ?: return
                val ok = repo.classifyByDriver(tripId, purpose, System.currentTimeMillis(), Live.state.value.parkedIdle)
                DiagLog.w("알림 버튼 분류 $tripId -> ${purpose.name} ok=$ok")
                Notifier.cancelArrival(context, tripId)
            }
            ACTION_SKIP -> {
                val ok = repo.skipPurpose(tripId)
                DiagLog.w("알림 지움 = 건너뛰기 $tripId ok=$ok")
            }
        }
    }
}
