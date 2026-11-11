// ver 1.2.0 | 2026-11-06 16:48
package io.tripsign.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.tripsign.app.R
import io.tripsign.app.core.Entry
import io.tripsign.app.core.Purpose
import io.tripsign.app.data.tr
import io.tripsign.app.ui.MainActivity
import io.tripsign.app.ui.purposeLabel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Notifier {
    const val CHANNEL_STATUS = "tripsign_status"
    const val CHANNEL_ARRIVAL = "tripsign_arrival"
    const val CHANNEL_WARN = "tripsign_driver_check"
    const val ID_STATUS = 1001
    const val ID_WARN = 1002
    /** MainActivity extra: opened by the driver warning (the screen records WARN "screen" when it shows). */
    const val EXTRA_WARN = "driverWarn"

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, tr("기록 상태", "Recording status"), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ARRIVAL, tr("도착 후 목적 선택", "Purpose on arrival"), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_WARN, tr("운전자 확인", "Driver check"), NotificationManager.IMPORTANCE_HIGH)
        )
    }

    fun warnIntent(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_WARN, true)

    /**
     * 0.5.2: "누가 운전하나요?" — a heads-up (full screen where the car allows it) that opens the login. Returns true when
     * it was actually posted (notifications allowed), so the ledger only records a warning that could be seen.
     */
    fun driverWarning(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.areNotificationsEnabled()) return false
        val pi = PendingIntent.getActivity(context, 7, warnIntent(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(context, CHANNEL_WARN)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(tr("누가 운전하나요?", "Who is driving?"))
            .setContentText(tr("로그인하지 않고 출발하면 미인증 운행으로 기록됩니다", "Leaving without logging in is recorded as an unverified trip"))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .setContentIntent(pi)
        if (Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()) b.setFullScreenIntent(pi, true)
        nm.notify(ID_WARN, b.build())
        return true
    }

    fun cancelWarning(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ID_WARN)
    }

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun status(context: Context, text: String): Notification =
        Notification.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(tr("트립사인 기록 중", "TripSign is recording"))
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(openApp(context))
            .build()

    fun updateStatus(context: Context, text: String) {
        context.getSystemService(NotificationManager::class.java).notify(ID_STATUS, status(context, text))
    }

    fun arrivalId(tripId: String): Int = tripId.hashCode()

    /** Heads-up with three one-touch purpose buttons. */
    fun arrival(context: Context, trip: Entry) {
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val text = String.format(
            Locale.US,
            "%.1f km · %s–%s",
            trip.distanceKm,
            fmt.format(Date(trip.startMs)),
            fmt.format(Date(trip.endMs))
        )
        val builder = Notification.Builder(context, CHANNEL_ARRIVAL)
            .setSmallIcon(R.drawable.ic_stat_trip)
            .setContentTitle(tr("운행 종료 · 목적을 선택하세요", "Trip ended · choose a purpose"))
            .setContentText(text)
            .setCategory(Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            // swiped away = 건너뛰기: the purpose stays 미분류 and only the administrator may set it
            .setDeleteIntent(
                PendingIntent.getBroadcast(
                    context,
                    arrivalId(trip.tripId) * 4 + 3,
                    Intent(context, PurposeReceiver::class.java).setAction(PurposeReceiver.ACTION_SKIP).putExtra(PurposeReceiver.EXTRA_TRIP, trip.tripId),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .setTimeoutAfter(io.tripsign.app.core.Logbook.DRIVER_WINDOW_MS)
        Purpose.entries.forEachIndexed { index, purpose ->
            val intent = Intent(context, PurposeReceiver::class.java)
                .setAction(PurposeReceiver.ACTION)
                .putExtra(PurposeReceiver.EXTRA_TRIP, trip.tripId)
                .putExtra(PurposeReceiver.EXTRA_PURPOSE, purpose.name)
            val pi = PendingIntent.getBroadcast(
                context,
                arrivalId(trip.tripId) * 4 + index,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(Notification.Action.Builder(null, purposeLabel(purpose), pi).build())
        }
        context.getSystemService(NotificationManager::class.java).notify(arrivalId(trip.tripId), builder.build())
    }

    fun cancelArrival(context: Context, tripId: String) {
        context.getSystemService(NotificationManager::class.java).cancel(arrivalId(tripId))
    }
}
