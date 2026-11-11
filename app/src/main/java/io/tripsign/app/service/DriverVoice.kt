// ver 1.2.1 | 2026-11-07 22:44
package io.tripsign.app.service

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import io.tripsign.app.data.DiagLog
import java.util.Locale

/**
 * 0.5.2f (A+C): the car says "who is driving?" out loud. Pleos does not bring an app's notification up over the
 * navigation and stops an app from opening itself, so a spoken line is the one warning a driver cannot miss.
 *
 * Speech goes through Pleos Connect's Speech Service (Pleos TextToSpeech SDK, on-device engine). Where it is missing
 * or fails, nothing is said and the reason is logged; the login screen banner and the notification still stand.
 * [onSpoken] runs when the car really starts speaking, so the ledger only records a voice warning that was heard.
 */
class DriverVoice(context: Context) {

    private enum class State { STARTING, READY, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var state = State.STARTING
    private var pending: Pair<String, () -> Unit>? = null
    private var onStarted: (() -> Unit)? = null
    private var speaking = ""

    private val events = object : EventListener {
        override fun onReady() = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onStart() = onMain {
            // 0.5.2g: every spoken warning leaves a line, so a test does not depend on someone listening
            DiagLog.w("음성 시작: " + speaking.removePrefix(LEAD_IN).take(24))  // the lead-in is the same for all
            val f = onStarted
            onStarted = null
            f?.invoke()
        }
        override fun onDone() = Unit
        override fun onStop() = Unit
        override fun onError(errorType: ErrorType) = onMain {
            onStarted = null
            DiagLog.w("음성 경고 실패: $errorType")
        }
    }

    init {
        main.postDelayed({ if (state == State.STARTING) fail("응답 없음") }, START_TIMEOUT_MS)
        try {
            val t = TextToSpeech(context.applicationContext, Mode.ON_DEVICE)
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() = onMain { ready() }
                override fun onFailure(errorType: ErrorType) = onMain { fail(errorType.toString()) }
            })
        } catch (e: RuntimeException) {
            fail(e.javaClass.simpleName)
        } catch (e: LinkageError) {
            fail(e.javaClass.simpleName)
        }
    }

    /** Says [text] (replacing anything still being said). [onSpoken] runs once the car starts speaking. */
    fun say(text: String, onSpoken: () -> Unit) = onMain {
        when (state) {
            State.STARTING -> pending = text to onSpoken
            State.FAILED -> DiagLog.w("음성 경고 생략(음성 엔진 없음)")
            State.READY -> speakNow(text, onSpoken)
        }
    }

    fun release() = onMain {
        main.removeCallbacksAndMessages(null)
        pending = null
        onStarted = null
        tts?.let {
            if (state == State.READY) runCatching { it.removeEventListener(events) }
            runCatching { it.release() }
        }
        tts = null
        state = State.FAILED
    }

    private fun ready() {
        val t = tts
        if (state != State.STARTING || t == null) return
        t.addEventListener(events)
        t.setLocale(Locale.KOREA)
        state = State.READY
        DiagLog.w("음성 경고 준비됨")
        pending?.let { (text, done) -> pending = null; speakNow(text, done) }
    }

    private fun fail(reason: String) {
        if (state != State.STARTING) return
        state = State.FAILED
        DiagLog.w("음성 엔진을 쓸 수 없음: $reason")
        tts?.let { runCatching { it.release() } }
        tts = null
        pending = null
    }

    private fun speakNow(text: String, onSpoken: () -> Unit) {
        val t = tts ?: return
        try {
            t.stop()
            speaking = text
            onStarted = onSpoken
            t.speak(text, true)
        } catch (e: RuntimeException) {
            onStarted = null
            DiagLog.w("음성 경고 실패: ${e.javaClass.simpleName}")
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    companion object {
        private const val START_TIMEOUT_MS = 15_000L

        /**
         * 0.5.2h: every utterance comes out rough for its first ~2.5 s whatever the words are (tested by ear with the
         * debug speak hook: "트립사인입니다" said four times in a row was clean from the third; a leading "음" in a
         * separate utterance did not help; the engine does not understand SSML and reads the tags aloud).
         * So each warning opens with a lead-in that may sound rough, and the app name and the message follow it.
         * Right after boot the rough part ran ~0.3 s longer (into "트"), so the lead-in was lengthened and the app name
         * placed near its end, as one sentence ("…트립사인에서 안내 말씀 드리겠습니다.").
         */
        const val LEAD_IN = "안녕하세요. 사용자님의 차량 운행과 관련하여 트립사인에서 안내 말씀 드리겠습니다. "

        /** At start-up (ignition), before the drive. */
        const val TEXT_IGNITION = LEAD_IN + "운전자를 선택해 주세요. 로그인하지 않고 출발하면 미인증 운행으로 기록됩니다."

        /** 0.5.2g: at start-up when the drive before was a 미인증 운행 nobody has confirmed yet (the card is on screen). */
        const val TEXT_IGNITION_PENDING = LEAD_IN + "직전 운행이 미인증으로 남아 있습니다. 운전하신 분이면 화면에서 먼저 확인해 주세요. 그리고 운전자를 선택해 주세요."

        /** The drive started with nobody logged in. */
        const val TEXT_UNVERIFIED_START = LEAD_IN + "로그인하지 않고 출발했습니다. 이 운행은 미인증 운행으로 기록됩니다."
    }
}
