// ver 1.3.2 | 2026-11-07 21:40
package io.tripsign.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import io.tripsign.app.car.CarSignals
import io.tripsign.app.car.OdometerSource
import io.tripsign.app.core.Driver
import io.tripsign.app.core.EngineConfig
import io.tripsign.app.core.EngineOutput
import io.tripsign.app.core.Gear
import io.tripsign.app.core.Logbook
import io.tripsign.app.core.TripEngine
import io.tripsign.app.data.DiagLog
import io.tripsign.app.data.Live
import io.tripsign.app.data.Settings
import io.tripsign.app.data.TripRepository
import io.tripsign.app.data.tr
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Foreground service that records trips with no driver input.
 * All engine calls run on the main thread; SDK callbacks are posted here first.
 *
 * 0.5.2 — who drives:
 * - The user is fixed when the drive STARTS (whoever was logged in at that moment), not when it ends.
 * - Ignition (boot, ignition signal, or waking from sleep) ends any login and puts "누가 운전하나요?" on screen.
 *   So does the end of every drive. A drive that starts with nobody logged in after such a warning was on screen
 *   is a 미인증 운행. A drive with no warning shown (the app could not show it) is only 미지정.
 * - A drive still open when the head unit goes off (switched off outside P) is saved every few seconds and
 *   closed at the last moment it was seen when the service comes back.
 */
class TripService : Service() {

    companion object {
        /** 0.5.2h: set while the service runs; used only by the debug-build DebugSpeakReceiver. */
        @Volatile var speakHook: ((String) -> Unit)? = null
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, TripService::class.java))
            } catch (e: Exception) {
                DiagLog.w("서비스 시작 실패: $e")
            }
        }

        /** A gap in the 1 s tick longer than this (deep sleep / suspend-to-RAM) counts as the car being switched off. */
        const val WAKE_GAP_MS = 90_000L
        /** Two warnings closer than this are one. */
        const val WARN_DEBOUNCE_MS = 30_000L
        /** 0.5.2g: the start warning is said again at these times while nobody has logged in (the last one at 1 min). */
        val VOICE_REPEAT_MS = longArrayOf(20_000L, 60_000L)
        private const val MARKER_EVERY_MS = 5_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var repo: TripRepository
    private lateinit var engine: TripEngine
    private var car: CarSignals? = null
    private var odometer: OdometerSource? = null
    private var voice: DriverVoice? = null   // 0.5.2f: spoken warning
    private var lastStatus = ""

    // who drives the open trip (fixed at its start) and whether it is a 미인증 운행
    private var activeDriver: Driver? = null
    private var activeUnverified = false
    /** A warning was on screen since the last ignition / drive end, so a drive with nobody logged in is 미인증. */
    private var warnedSinceLastDrive = false
    private var lastWarnMs = 0L
    // 0.5.2g: the spoken warning at a car start is said again at 20 s and 60 s while nobody has logged in.
    // A new start, a drive starting or a new trip end make the old repeats stale (generation number).
    private var voiceGen = 0
    private var voiceWarnedThisStart = false

    private var lastTickWall = 0L
    private var lastTickElapsed = 0L
    private var lastMarkerMs = 0L
    private var ignition: Int? = null
    /** A drive restored from the marker waits for the first odometer reading (or this time) before it is closed. */
    private var restoreEndMs: Long? = null
    private var restoreDeadline = 0L

    private val markerFile by lazy { File(filesDir, "open_trip.json") }

    private val ticker = object : Runnable {
        override fun run() {
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            if (lastTickElapsed > 0L && elapsed - lastTickElapsed > WAKE_GAP_MS) {
                // the head unit slept (car off) and woke up: close an open drive where it was last seen
                onIgnitionStart("wake", lastTickWall)
            }
            lastTickWall = wall
            lastTickElapsed = elapsed
            restoreEndMs?.let { end -> if (elapsed > restoreDeadline) finishRestored(end) }
            handle(engine.tick(wall))
            saveMarker(wall)
            publish()
            main.postDelayed(this, 1_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        DiagLog.init(this)
        Settings.load(this)
        Notifier.createChannels(this)
        goForeground(tr("대기 중", "Waiting"))
        repo = TripRepository.get(this)
        voice = DriverVoice(this)
        // debug builds only: lets a test say any sentence with the same engine and settings (see DebugSpeakReceiver)
        speakHook = { text -> DiagLog.w("음성 시험: " + text.take(24)); voice?.say(text) {} }
        engine = TripEngine(EngineConfig(), repo.lastOdometerKm())
        DiagLog.w("서비스 시작, 기준 계기판=${repo.lastOdometerKm()}")
        Live.update { it.copy(serviceRunning = true) }
        restoreMarker()

        // a new boot is an ignition; a process restart on the same boot is not
        val boot = Settings.bootCount(this)
        if (boot >= 0 && boot != Settings.lastIgnitionBoot(this)) {
            Settings.setIgnitionBoot(this, boot)
            main.post { onIgnitionStart("boot", null) }
        }

        car = CarSignals(
            context = this,
            onGear = { gear, t -> main.post { onGear(gear, t) } },
            onSpeed = { mps, t -> main.post { onSpeed(mps, t) } },
            onConnected = { ok -> main.post { Live.update { s -> s.copy(carConnected = ok) } } },
            log = DiagLog::w,
            onIgnition = { v -> main.post { onIgnitionState(v) } }
        ).also { it.start() }

        odometer = OdometerSource(
            context = this,
            onKm = { km -> main.post { onOdometer(km) } },
            onConnected = { ok -> main.post { Live.update { s -> s.copy(odometerConnected = ok) } } },
            log = DiagLog::w
        ).also { it.start() }

        main.post(ticker)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every startForegroundService() must be answered with startForeground(), even when already running.
        goForeground(lastStatus.ifEmpty { tr("대기 중", "Waiting") })
        return START_STICKY
    }

    private fun goForeground(text: String) {
        val notification = Notifier.status(this, text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.ID_STATUS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.ID_STATUS, notification)
        }
    }

    override fun onDestroy() {
        voiceGen++
        voice?.release()
        voice = null
        speakHook = null
        main.removeCallbacks(ticker)
        car?.stop()
        odometer?.stop()
        Live.update { it.copy(serviceRunning = false, carConnected = false, odometerConnected = false) }
        DiagLog.w("서비스 종료")
        super.onDestroy()
    }

    // ---------------------------------------------------------------- ignition

    private fun onIgnitionState(v: Int) {
        val prev = ignition
        ignition = v
        val on = v >= 4   // ON(4) or START(5)
        Live.update { it.copy(ignitionOn = on) }
        if (prev != null && prev < 4 && on) onIgnitionStart("ignition", null)
        if (prev != null && prev >= 4 && !on && engine.isDriving && engine.currentGear != Gear.PARK) {
            // switched off while not in P: close the drive now instead of waiting for a P that never comes
            DiagLog.w("P 아닌 채 시동 꺼짐: 운행 닫음")
            handle(engine.forceFinish(System.currentTimeMillis()))
        }
    }

    /**
     * The car was (re)started. [closeAt]: when waking from sleep, the last moment the open drive was seen.
     * Ends the login and puts the driver warning up.
     */
    private fun onIgnitionStart(why: String, closeAt: Long?) {
        DiagLog.w("시동 감지($why): 로그아웃 후 운전자 확인")
        if (closeAt != null && engine.isDriving) handle(engine.forceFinish(closeAt))
        Settings.logout(this)
        Settings.markIgnition(this, System.currentTimeMillis())
        warnedSinceLastDrive = false
        warnFromMs = System.currentTimeMillis()
        voiceWarnedThisStart = false
        requestDriverWarning(why)
    }

    /**
     * "누가 운전하나요?": first the app itself on screen (works when the car allows the start), then a heads-up
     * (full screen where allowed). The screen records WARN "screen" when it really shows; a posted heads-up is WARN "notice".
     */
    private fun requestDriverWarning(why: String) {
        // 0.5.2h: before onboarding, or with nobody signed up, there is no driver to choose: no screen, notice or voice
        // (found after an app-data reset: the first start said "운전자를 선택해 주세요" three times over the onboarding)
        if (!Settings.onboarded.value || Settings.drivers.value.isEmpty()) {
            DiagLog.w("운전자 확인 생략($why): 온보딩 전이거나 가입자 없음")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastWarnMs < WARN_DEBOUNCE_MS) return
        lastWarnMs = now
        Live.update { it.copy(warnSeq = it.warnSeq + 1) }
        try {
            startActivity(Notifier.warnIntent(this))
        } catch (e: Exception) {
            DiagLog.w("경고 화면 열기 실패: ${e.javaClass.simpleName}")
        }
        if (Notifier.driverWarning(this)) {
            repo.appendWarn("notice", now)
            warnedSinceLastDrive = true
        }
        // 0.5.2f (A+C): at start-up the car also says it, recorded only once the car really speaks. Not after a drive
        // (the driver has just parked; the screen and notification are enough for the next one).
        if (why != "trip end") {
            val gen = ++voiceGen
            sayStartWarning()
            for (delayMs in VOICE_REPEAT_MS) {
                main.postDelayed({
                    if (gen != voiceGen) return@postDelayed
                    val st = Live.state.value
                    if (Settings.currentDriver.intValue >= 0 || engine.isDriving || st.authOpen ||
                        engine.currentGear == Gear.DRIVE || engine.currentGear == Gear.REVERSE
                    ) return@postDelayed
                    DiagLog.w("음성 경고 반복(${delayMs / 1000}초)")
                    sayStartWarning()
                }, delayMs)
            }
        }
        DiagLog.w("운전자 확인 요청($why)")
    }

    /**
     * The spoken warning at a car start. When the drive before was a 미인증 운행 nobody has confirmed, it says so first
     * (the login screen shows that trip's card). The ledger gets one WARN "voice" per start: the first time the car
     * really speaks.
     */
    private fun sayStartWarning() {
        val pending = Logbook.pendingUnverified(Logbook.rows(repo.entries.value), Settings.prevIgnitionAt.longValue, Settings.ignitionAt.longValue)
        val text = if (pending != null) DriverVoice.TEXT_IGNITION_PENDING else DriverVoice.TEXT_IGNITION
        voice?.say(text) {
            if (!voiceWarnedThisStart) {
                voiceWarnedThisStart = true
                repo.appendWarn("voice")
            }
            warnedSinceLastDrive = true
        }
    }

    // ---------------------------------------------------------------- signals

    private fun onGear(gear: Gear, t: Long) {
        Live.update { it.copy(gear = gear) }
        handle(engine.onGear(gear, t))
        if (gear == Gear.PARK) odometer?.readNow()
    }

    private fun onSpeed(mps: Double, t: Long) {
        Live.update { it.copy(speedKmh = mps * 3.6) }
        handle(engine.onSpeed(mps, t))
    }

    private fun onOdometer(km: Double) {
        Live.update { it.copy(odometerKm = km) }
        handle(engine.onOdometer(km, System.currentTimeMillis()))
        restoreEndMs?.let { finishRestored(it) }
    }

    private fun handle(outputs: List<EngineOutput>) {
        for (o in outputs) {
            when (o) {
                is EngineOutput.TripStarted -> {
                    activeDriver = Settings.current()
                    // the screen may have shown the warning while the service was busy: the ledger has the WARN
                    val warned = warnedSinceLastDrive || WarnBridge.shownSince(warnFromMs)
                    // 0.5.2f (A+C): nobody logged in at the start = 미인증, whether or not a warning reached anyone.
                    // (Before, only after a warning: switching notifications off avoided the mark.)
                    activeUnverified = activeDriver == null
                    voiceGen++   // a drive started: no more "who is driving?" repeats
                    if (activeUnverified) voice?.say(DriverVoice.TEXT_UNVERIFIED_START) {}
                    DiagLog.w("운행 시작 ${o.tripId} 계기판=${o.startOdoKm} 사용자=${activeDriver?.name ?: "-"} 미인증=$activeUnverified 경고=$warned")
                    Live.update { it.copy(lastEvent = if (activeUnverified) "미인증 운행 시작" else "운행 시작") }
                    Notifier.cancelWarning(this)
                    saveMarker(System.currentTimeMillis(), force = true)
                }
                is EngineOutput.TripFinished -> {
                    val entry = if (activeUnverified) o.entry.copy(note = Logbook.NOTE_UNVERIFIED) else o.entry
                    val sealed = repo.appendTrip(entry, activeDriver)
                    activeDriver = null
                    activeUnverified = false
                    clearMarker()
                    warnFromMs = System.currentTimeMillis()
                    warnedSinceLastDrive = false
                    Notifier.arrival(this, sealed.entry)
                    Live.update { it.copy(lastEvent = String.format(Locale.KOREA, "운행 종료 %.1f km", o.entry.distanceKm)) }
                    // the next drive starts with "누가 운전하나요?" again
                    lastWarnMs = 0L
                    voiceGen++
                    requestDriverWarning("trip end")
                }
                is EngineOutput.NoMoveIgnored -> {
                    DiagLog.w("이동 없음: 기록 제외 ${o.startMs}~${o.endMs}")
                    Live.update { it.copy(lastEvent = "이동 없는 기어 조작 제외") }
                    activeDriver = null
                    activeUnverified = false
                    clearMarker()
                }
                is EngineOutput.GapDetected -> {
                    repo.append(o.entry)
                    Live.update { it.copy(lastEvent = String.format(Locale.KOREA, "미기록 구간 %.1f km", o.entry.distanceKm)) }
                }
                is EngineOutput.AnomalyDetected -> {
                    repo.append(o.entry)
                    Live.update { it.copy(lastEvent = "계기판 이상값 기록") }
                }
            }
        }
    }

    /** Warnings shown from this moment on count for the next drive (set at ignition and at every drive end). */
    private var warnFromMs = 0L

    // ---------------------------------------------------------------- open-drive marker

    private fun saveMarker(now: Long, force: Boolean = false) {
        if (!engine.isDriving) return
        if (!force && now - lastMarkerMs < MARKER_EVERY_MS) return
        lastMarkerMs = now
        try {
            val j = JSONObject()
                .put("id", engine.activeTripId)
                .put("startMs", engine.activeStartMs)
                .put("startOdoKm", engine.activeStartOdoKm ?: JSONObject.NULL)
                .put("speedKm", engine.activeSpeedKm)
                .put("maxMps", engine.activeMaxMps)
                .put("lastSeenMs", now)
                .put("driver", activeDriver?.encode() ?: JSONObject.NULL)
                .put("unverified", activeUnverified)
            val tmp = File(filesDir, "open_trip.tmp")
            tmp.writeText(j.toString(), Charsets.UTF_8)
            tmp.renameTo(markerFile)
        } catch (e: Exception) {
            DiagLog.w("운행 표시 저장 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun clearMarker() {
        try {
            if (markerFile.exists()) markerFile.writeText("", Charsets.UTF_8)
        } catch (e: Exception) {
            DiagLog.w("운행 표시 지우기 실패: ${e.javaClass.simpleName}")
        }
    }

    /** A drive that was open when the head unit went off: put it back, close it at its last moment once the odometer is read. */
    private fun restoreMarker() {
        try {
            if (!markerFile.exists()) return
            val text = markerFile.readText(Charsets.UTF_8)
            if (text.isBlank()) return
            val j = JSONObject(text)
            val id = j.getString("id")
            if (repo.entries.value.any { it.entry.tripId == id }) { clearMarker(); return }
            engine.restore(
                id = id,
                startMs = j.getLong("startMs"),
                startOdoKm = if (j.isNull("startOdoKm")) null else j.getDouble("startOdoKm"),
                speedKm = j.optDouble("speedKm", 0.0),
                maxMps = j.optDouble("maxMps", 0.0)
            )
            activeDriver = if (j.isNull("driver")) null else Driver.decode(j.getString("driver"))
            activeUnverified = j.optBoolean("unverified", false)
            restoreEndMs = j.getLong("lastSeenMs")
            restoreDeadline = SystemClock.elapsedRealtime() + 60_000L
            DiagLog.w("꺼지기 전 열린 운행 복원 $id")
        } catch (e: Exception) {
            DiagLog.w("운행 표시 읽기 실패: ${e.javaClass.simpleName}")
            clearMarker()
        }
    }

    private fun finishRestored(endMs: Long) {
        restoreEndMs = null
        if (engine.isDriving) handle(engine.forceFinish(endMs))
    }

    // ---------------------------------------------------------------- status

    private fun publish() {
        Live.update { it.copy(driving = engine.isDriving, tripKm = engine.activeSpeedKm) }
        val text = if (engine.isDriving) {
            tr("주행 기록 중", "Recording") + String.format(Locale.US, " · %.1f km", engine.activeSpeedKm)
        } else {
            tr("대기 중 · 계기판 ", "Waiting · odometer ") + (engine.lastOdoKm?.let { String.format(Locale.US, "%,.1f km", it) } ?: tr("확인 중", "reading"))
        }
        if (text != lastStatus) {
            lastStatus = text
            Notifier.updateStatus(this, text)
        }
    }
}

/**
 * The screen and the service share one process. When the warning is really on screen the screen writes WARN "screen"
 * and notes the time here, so a drive that starts right after it counts as 미인증 even if the heads-up was blocked.
 */
object WarnBridge {
    @Volatile
    private var shownAtMs = 0L

    fun markShown(nowMs: Long = System.currentTimeMillis()) {
        shownAtMs = nowMs
    }

    fun shownSince(ms: Long): Boolean = shownAtMs > 0L && shownAtMs >= ms
}
