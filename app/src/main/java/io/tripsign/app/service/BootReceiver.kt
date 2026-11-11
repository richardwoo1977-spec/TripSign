// ver 1.1.0 | 2026-11-06 16:48
package io.tripsign.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.tripsign.app.data.DiagLog
import io.tripsign.app.data.Settings

/**
 * Starts recording when the head unit boots, so trips are logged without opening the app.
 * 0.5.2: also right after an app update (MY_PACKAGE_REPLACED) — an update stops the app, and without this
 * nothing would record until the next boot or until someone opened the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DiagLog.init(context)
        Settings.load(context)
        DiagLog.w("시작 신호 수신: ${intent.action}")
        TripService.start(context)
    }
}
