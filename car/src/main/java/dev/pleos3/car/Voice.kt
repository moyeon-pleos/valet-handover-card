package dev.pleos3.car

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

interface Voice {
    fun say(text: String)

    /** 지금까지 시킨 말이 모두 끝난 뒤 action 을 한 번 부른다. */
    fun whenQuiet(action: () -> Unit) = action()

    /** 아직 말하는 중이면 그 말을 끊는다(마이크를 열기 직전에 부른다). */
    fun hush() {}
}

class RecordingVoice : Voice {
    private val lock = Any()
    private val recorded = mutableListOf<String>()

    val said: List<String>
        get() = synchronized(lock) { recorded.toList() }

    override fun say(text: String) {
        synchronized(lock) {
            recorded.add(text)
        }
    }
}

class PleosVoice(context: Context) : Voice {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pending = ArrayDeque<String>()
    private val quietActions = mutableListOf<() -> Unit>()

    private var engine: TextToSpeech? = null
    private var eventListener: EventListener? = null
    private var initializing = false
    private var ready = false
    private var speakingCount = 0
    private var estimatedEndMs = 0L
    private var quietJob: Job? = null
    private var quietGeneration = 0L

    @Volatile
    private var stopped = false

    init {
        scope.launch { initialize() }
    }

    override fun say(text: String) {
        // 초기화 실패와 해제 여부에 관계없이 요청 문장을 먼저 기록한다.
        CarLog.i("VOICE: $text")

        scope.launch {
            if (stopped) return@launch

            val now = SystemClock.uptimeMillis()
            estimatedEndMs = maxOf(now, estimatedEndMs) + estimateMs(text)
            speakingCount++
            pending.addLast(text)
            scheduleQuietFallback()

            if (ready) drain()
            else initialize()
        }
    }

    override fun whenQuiet(action: () -> Unit) {
        scope.launch {
            if (stopped) return@launch

            if (speakingCount == 0 && pending.isEmpty()) {
                action()
            } else {
                quietActions += action
                scheduleQuietFallback()
            }
        }
    }

    override fun hush() {
        scope.launch {
            if (stopped) return@launch

            // 말하는 중으로 보일 때만 끊는다.
            if (
                pending.isEmpty() &&
                SystemClock.uptimeMillis() >= estimatedEndMs
            ) {
                return@launch
            }

            CarLog.i("VOICE: hush")
            pending.clear()
            try {
                engine?.stop()
            } catch (error: Exception) {
                CarLog.i("음성 중지 실패: ${error.message}")
            }
            speakingCount = 0
            estimatedEndMs = 0L
            // 말이 끝나기를 기다리던 일은 끊은 지금 부른다.
            completeQuietActions("끊음")
        }
    }

    /** 글 한 덩이를 읽는 데 걸릴 시간의 어림. */
    private fun estimateMs(text: String): Long =
        600L + 170L * text.count { !it.isWhitespace() }

    private fun initialize() {
        if (stopped || ready || initializing) return
        initializing = true

        try {
            val current = TextToSpeech(app, Mode.ON_DEVICE)
            engine = current
            current.initialize(
                object : OnInitializeListener {
                    override fun onSuccess() {
                        scope.launch {
                            if (stopped || engine !== current || !initializing) {
                                return@launch
                            }

                            try {
                                current.setLocale(Locale.KOREA)
                                current.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(
                                            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
                                        )
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build(),
                                )
                                val listener = createEventListener(current)
                                eventListener = listener
                                current.addEventListener(listener)
                                initializing = false
                                ready = true
                                drain()
                            } catch (error: Exception) {
                                fail(current, "음성 설정 실패: ${error.message}")
                            }
                        }
                    }

                    override fun onFailure(error: ErrorType) {
                        scope.launch {
                            fail(current, "음성 초기화 실패: $error")
                        }
                    }
                },
            )
        } catch (error: Exception) {
            val current = engine
            if (current != null) {
                fail(current, "음성 초기화 예외: ${error.message}")
            } else {
                initializing = false
                CarLog.i("음성 초기화 예외: ${error.message}")
            }
        }
    }

    private fun createEventListener(current: TextToSpeech): EventListener =
        object : EventListener {
            // 에뮬레이터에서 끝 신호가 오는지 보려고 신호를 로그에 남긴다(2026-11-11 — 주차 위치 질문 뒤 «어림»으로만 풀렸다).
            override fun onStart() = CarLog.i("TTS: start")

            override fun onReady() = Unit

            override fun onDone() {
                CarLog.i("TTS: done")
                scope.launch {
                    if (stopped || engine !== current) return@launch

                    speakingCount = (speakingCount - 1).coerceAtLeast(0)
                    if (speakingCount == 0 && pending.isEmpty()) {
                        estimatedEndMs = 0L
                        completeQuietActions("신호")
                    }
                }
            }

            override fun onError(error: ErrorType) = CarLog.i("TTS: error $error")

            override fun onStop() = CarLog.i("TTS: stop")

            override fun onUpdatedRms(rms: Double) = Unit
        }

    private fun scheduleQuietFallback() {
        quietJob?.cancel()
        quietJob = null
        val generation = ++quietGeneration
        if (stopped || quietActions.isEmpty()) return

        val remainingMs =
            (estimatedEndMs + 1_500L - SystemClock.uptimeMillis())
                .coerceAtLeast(0L)

        quietJob = scope.launch {
            delay(remainingMs)
            if (
                stopped ||
                generation != quietGeneration ||
                quietActions.isEmpty()
            ) {
                return@launch
            }

            quietJob = null
            speakingCount = 0
            estimatedEndMs = 0L
            completeQuietActions("어림")
        }
    }

    private fun completeQuietActions(source: String) {
        quietGeneration++
        quietJob?.cancel()
        quietJob = null
        if (stopped || quietActions.isEmpty()) return

        // 먼저 목록을 비워 신호와 어림이 겹쳐도 같은 동작을 다시 부르지 않는다.
        val actions = quietActions.toList()
        quietActions.clear()
        CarLog.i("VOICE: quiet ($source)")
        for (action in actions) {
            if (stopped) break
            try {
                action()
            } catch (error: Exception) {
                CarLog.i("음성 완료 동작 실패: ${error.message}")
            }
        }
    }

    private fun fail(current: TextToSpeech, message: String) {
        if (stopped || engine !== current) return

        CarLog.i(message)
        engine = null
        initializing = false
        ready = false
        detachEventListener(current)
        try {
            current.release()
        } catch (error: Exception) {
            CarLog.i("음성 엔진 해제 실패: ${error.message}")
        }
        // 대기 문장과 어림 시각은 유지한다. 다음 요청에서 초기화를 다시 시도한다.
    }

    /** 걸어 둔 이벤트 리스너를 엔진에서 뗀다. 실패는 기록만 한다. */
    private fun detachEventListener(current: TextToSpeech?) {
        val listener = eventListener ?: return
        eventListener = null
        if (current == null) return

        try {
            current.removeEventListener(listener)
        } catch (error: Exception) {
            CarLog.i("음성 리스너 해제 실패: ${error.message}")
        }
    }

    private fun drain() {
        val current = engine ?: return
        if (!ready || stopped) return

        var drainedMs = 0L
        while (pending.isNotEmpty()) {
            val text = pending.removeFirst()
            drainedMs += estimateMs(text)
            try {
                // 앞 문장을 중단하지 않고 요청 순서대로 SDK에 넣는다.
                current.speak(text, false)
            } catch (error: Exception) {
                CarLog.i("음성 말하기 실패: ${error.message}")
            }
        }
        if (drainedMs == 0L) return

        // 초기화를 기다리느라 늦게 나간 말은 say() 때가 아니라 지금부터 다시 센다.
        val restartedEndMs = SystemClock.uptimeMillis() + drainedMs
        if (restartedEndMs > estimatedEndMs) {
            estimatedEndMs = restartedEndMs
            if (quietActions.isNotEmpty()) scheduleQuietFallback()
        }
    }

    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true

        scope.launch {
            try {
                quietGeneration++
                quietJob?.cancel()
                quietJob = null
                quietActions.clear()
                speakingCount = 0
                estimatedEndMs = 0L
                pending.clear()
                initializing = false
                ready = false
                val current = engine
                engine = null

                detachEventListener(current)
                try {
                    current?.stop()
                } catch (error: Exception) {
                    CarLog.i("음성 중지 실패: ${error.message}")
                }
                try {
                    current?.release()
                } catch (error: Exception) {
                    CarLog.i("음성 해제 실패: ${error.message}")
                }
            } finally {
                scope.cancel()
            }
        }
    }
}
