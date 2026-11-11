// ver 1.5.0 | 2026-11-06 16:48
package io.tripsign.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import io.tripsign.app.data.DiagLog
import io.tripsign.app.data.Settings
import io.tripsign.app.data.TripRepository
import io.tripsign.app.service.Notifier
import io.tripsign.app.service.TripService

class MainActivity : ComponentActivity() {

    private val missing = mutableStateOf<List<String>>(emptyList())
    private val requesting = mutableStateOf(false)
    private val demoScale = mutableStateOf(1.0)
    private val shot = mutableStateOf<String?>(null)
    /** 0.5.2: how many times the driver warning opened this screen (the screen shows "누가 운전하나요?" and records it). */
    private val warnOpens = mutableStateOf(0)

    private fun readWarn(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifier.EXTRA_WARN, false) == true) {
            warnOpens.value = warnOpens.value + 1
            intent.removeExtra(Notifier.EXTRA_WARN)
        }
    }

    /**
     * Layout check, debug builds only (tools/layout_check.sh): `--es shot home1 --es size large
     * --es lang en` opens one screen in that text size and language without saving the choice.
     */
    private fun readShot(intent: Intent?) {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || intent == null || !intent.hasExtra("shot")) return
        val id = intent.getStringExtra("shot")
        val size = intent.getStringExtra("size")
        val lang = intent.getStringExtra("lang")
        val theme = intent.getStringExtra("theme")
        LayoutCheck.enabled = true
        LayoutCheck.mark("${lang ?: "-"}_${size ?: "-"}_${theme ?: "light"}_x${demoScale.value.toInt()}_$id")
        Settings.applyForTest(lang, size, theme ?: Settings.THEME_LIGHT)
        shot.value = id
    }

    /**
     * Test hook, debug builds only: launching with `--ef demoScale 52` multiplies the sample
     * distances so 100,000+ km layouts can be checked on the head unit. Ignored in release builds.
     */
    private fun readDemoScale(intent: Intent?) {
        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable || intent == null || !intent.hasExtra("demoScale")) return
        val k = intent.getFloatExtra("demoScale", 1f).toDouble().coerceIn(1.0, 1000.0)
        demoScale.value = k
        DiagLog.w("시험용 예시 배율 = $k")
    }

    private val runtimePermissions: List<String>
        get() {
            val list = mutableListOf("android.car.permission.CAR_SPEED")
            if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
            return list
        }

    private fun refreshMissing(): List<String> {
        val m = runtimePermissions.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        missing.value = m
        return m
    }

    /** One batch request, only when the user asks for it (onboarding button, header chip or settings). */
    private fun requestMissing() {
        val m = refreshMissing()
        if (m.isEmpty()) {
            finishOnboarding()
            return
        }
        DiagLog.w("권한 요청: $m")
        requesting.value = true
        requestPermissions(m.toTypedArray(), 77)
        // If no system dialog took focus, never leave the dimmed screen stuck.
        window.decorView.postDelayed({
            if (requesting.value && hasWindowFocus()) {
                DiagLog.w("권한 창이 뜨지 않음: 어둡게 한 화면 해제")
                requesting.value = false
            }
        }, 3_000L)
    }

    private fun finishOnboarding() {
        Settings.setOnboarded(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DiagLog.init(this)
        Settings.load(this)
        val dm = resources.displayMetrics
        DiagLog.w(
            "화면 생성 · 표시 ${dm.widthPixels}x${dm.heightPixels} density=${dm.density} dpi=${dm.densityDpi} " +
                "xdpi=${dm.xdpi} ydpi=${dm.ydpi} 언어=${Settings.language.value} 모드=${Settings.theme.value}"
        )
        refreshMissing()
        readDemoScale(intent)
        readShot(intent)
        readWarn(intent)
        val repo = TripRepository.get(this)
        setContent {
            AppRoot(
                repo = repo,
                missingPermissions = missing.value,
                requestingPermissions = requesting.value,
                demoScale = demoScale.value,
                shot = shot.value,
                warnOpens = warnOpens.value,
                onRequestPermissions = { requestMissing() },
                onFinishOnboarding = { finishOnboarding() }
            )
        }
        // Gear and odometer work without runtime permissions, so recording starts right away.
        TripService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readDemoScale(intent)
        readShot(intent)
        readWarn(intent)
    }

    override fun onResume() {
        super.onResume()
        DiagLog.w("화면 표시, 미허용 권한=${refreshMissing()}")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissions.forEachIndexed { i, p ->
            DiagLog.w("권한 $p = ${if (grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED) "허용" else "거부"}")
        }
        requesting.value = false
        refreshMissing()
        finishOnboarding()
        TripService.start(this)
    }
}
