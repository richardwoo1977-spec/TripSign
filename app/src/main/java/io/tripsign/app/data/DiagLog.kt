// ver 1.0.0 | 2026-11-04 15:10
package io.tripsign.app.data

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Diagnostic log for emulator tests: filesDir/tripsign_log.txt (read with run-as). */
object DiagLog {
    private const val TAG = "TripSign"
    private const val MAX_BYTES = 1_000_000L
    private var file: File? = null
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    fun init(context: Context) {
        if (file == null) file = File(context.filesDir, "tripsign_log.txt")
    }

    @Synchronized
    fun w(message: String) {
        Log.i(TAG, message)
        val f = file ?: return
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                val old = File(f.parentFile, "tripsign_log.old.txt")
                old.delete()
                f.renameTo(old)
            }
            f.appendText(fmt.format(Date()) + "  " + message + "\n")
        } catch (e: Exception) {
            Log.w(TAG, "log write failed", e)
        }
    }
}
