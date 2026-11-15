package dev.pleos3.car

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.SystemClock
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 듣기가 실패한 까닭. */
enum class SpeechFailure { NO_SPEECH, ENGINE_ERROR, UNAVAILABLE }

interface SpeechListener {
    fun onPartial(text: String) {}
    fun onFinal(text: String)
    fun onFailed(reason: SpeechFailure)
}

/** 한 번 요청하면 한 문장을 듣는다. 완료 또는 실패는 주 스레드에서 한 번만 알린다. */
interface SpeechInput {
    fun listen(listener: SpeechListener)
    fun cancel()
}

class PleosSpeechInput(
    context: Context,
    private val noSpeechTimeoutMs: Long = 8_000L,
) : SpeechInput {
    private class Pending(
        val generation: Long,
        val listener: SpeechListener,
    ) {
        var requested = false
        var ready = false
        var partialSeen = false
        var lastPartial = ""
        var startJob: Job? = null
        var readyTimeout: Job? = null
        var noSpeechTimeout: Job? = null
        var finalTimeout: Job? = null

        fun cancelJobs() {
            startJob?.cancel()
            readyTimeout?.cancel()
            noSpeechTimeout?.cancel()
            finalTimeout?.cancel()
            startJob = null
            readyTimeout = null
            noSpeechTimeout = null
            finalTimeout = null
        }
    }

    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var engine: SpeechToText? = null
    private var engineReady = false
    private var initializing = false
    private var resultListener: ResultListener? = null
    private var pending: Pending? = null
    private var generation = 0L
    private var lastStoppedAt: Long? = null

    @Volatile
    private var stopped = false

    override fun listen(listener: SpeechListener) {
        scope.launch {
            if (stopped) return@launch

            cancelCurrent()
            val request = Pending(++generation, listener)
            pending = request

            val current = engine
            if (current != null && engineReady) {
                scheduleRequest(current, request)
            } else {
                initialize(request)
            }
        }
    }

    override fun cancel() {
        scope.launch {
            if (!stopped) cancelCurrent()
        }
    }

    private fun isCurrent(request: Pending): Boolean =
        !stopped &&
            pending === request &&
            generation == request.generation

    private fun initialize(request: Pending) {
        if (!isCurrent(request)) return

        try {
            val current = SpeechToText(app, Mode.ON_DEVICE)
            engine = current
            engineReady = false
            initializing = true
            attachListener(current, request)

            current.initialize(
                object : OnInitializeListener {
                    override fun onSuccess() {
                        scope.launch {
                            if (
                                !isCurrent(request) ||
                                engine !== current ||
                                !initializing
                            ) {
                                return@launch
                            }

                            try {
                                current.setLocale(Locale.KOREA)
                                initializing = false
                                engineReady = true
                                scheduleRequest(current, request)
                            } catch (error: Exception) {
                                logException("언어 설정", error)
                                finish(
                                    request,
                                    failure = SpeechFailure.ENGINE_ERROR,
                                    discardEngine = true,
                                )
                            }
                        }
                    }

                    override fun onFailure(error: ErrorType) {
                        scope.launch {
                            if (
                                !isCurrent(request) ||
                                engine !== current ||
                                !initializing
                            ) {
                                return@launch
                            }

                            finish(
                                request,
                                failure = SpeechFailure.UNAVAILABLE,
                                discardEngine = true,
                            )
                        }
                    }
                },
            )
        } catch (error: Exception) {
            logException("초기화", error)
            finish(
                request,
                failure = SpeechFailure.ENGINE_ERROR,
                discardEngine = true,
            )
        }
    }

    private fun scheduleRequest(current: SpeechToText, request: Pending) {
        request.startJob = scope.launch {
            val remaining = lastStoppedAt?.let {
                STOP_COOLDOWN_MS - (SystemClock.uptimeMillis() - it)
            } ?: 0L
            if (remaining > 0L) delay(remaining)

            if (
                !isCurrent(request) ||
                engine !== current ||
                !engineReady
            ) {
                return@launch
            }

            try {
                if (resultListener == null) attachListener(current, request)

                request.requested = true
                CarLog.i("STT: request ${request.generation}")
                request.readyTimeout = scope.launch {
                    delay(READY_TIMEOUT_MS)
                    if (isCurrent(request) && !request.ready) {
                        finish(
                            request,
                            failure = SpeechFailure.ENGINE_ERROR,
                            stopRequested = true,
                        )
                    }
                }
                current.request()
            } catch (error: Exception) {
                logException("요청", error)
                finish(
                    request,
                    failure = SpeechFailure.ENGINE_ERROR,
                    stopRequested = true,
                    discardEngine = true,
                )
            }
        }
    }

    private fun attachListener(current: SpeechToText, request: Pending) {
        // 요청마다 별도 리스너를 만들어 이미 전달 중인 옛 콜백도 세대로 거른다.
        val listener = object : ResultListener {
            override fun onUpdated(text: String, isFinal: Boolean) {
                dispatch(current, request) {
                    if (isFinal) {
                        finish(request, text = text)
                    } else {
                        acceptPartial(request, text)
                    }
                }
            }

            override fun onReady() {
                dispatch(current, request) {
                    if (!request.ready) {
                        request.ready = true
                        request.readyTimeout?.cancel()
                        request.readyTimeout = null
                        CarLog.i("STT: ready ${request.generation}")

                        if (!request.partialSeen) {
                            request.noSpeechTimeout = scope.launch {
                                delay(noSpeechTimeoutMs.coerceAtLeast(0L))
                                if (isCurrent(request) && !request.partialSeen) {
                                    finish(
                                        request,
                                        failure = SpeechFailure.NO_SPEECH,
                                        stopRequested = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            override fun onError(error: ErrorType) {
                dispatch(current, request) {
                    finish(
                        request,
                        failure = SpeechFailure.ENGINE_ERROR,
                        stopRequested = true,
                    )
                }
            }

            override fun onUpdatedEpdData(epdOn: Long, epdOff: Long) {}

            override fun onStartedRecognition() {}

            override fun onEndedRecognition() {}

            override fun onUpdatedRms(rms: Double) {}
        }

        resultListener = listener
        current.addListener(listener)
    }

    private fun dispatch(
        current: SpeechToText,
        request: Pending,
        action: () -> Unit,
    ) {
        scope.launch {
            if (
                isCurrent(request) &&
                engine === current &&
                request.requested
            ) {
                action()
            }
        }
    }

    private fun acceptPartial(request: Pending, text: String) {
        request.lastPartial = text.trim()
        if (!request.partialSeen) {
            request.partialSeen = true
            request.noSpeechTimeout?.cancel()
            request.noSpeechTimeout = null

            // 중간 글이 계속 바뀌어도 첫 중간 글부터 센 제한 시간은 늘리지 않는다.
            request.finalTimeout = scope.launch {
                delay(FINAL_TIMEOUT_MS)
                if (isCurrent(request)) {
                    finish(
                        request,
                        text = request.lastPartial,
                        stopRequested = true,
                    )
                }
            }
        }

        request.listener.onPartial(text)
    }

    private fun finish(
        request: Pending,
        text: String? = null,
        failure: SpeechFailure? = null,
        stopRequested: Boolean = false,
        discardEngine: Boolean = false,
    ) {
        if (!isCurrent(request)) return

        // SDK 중지·리스너 제거가 콜백을 일으켜도 두 번째 완료는 받지 않는다.
        pending = null
        request.cancelJobs()

        var cleanupSucceeded = detachListener()
        if (stopRequested && !stopEngine()) cleanupSucceeded = false
        if (discardEngine || !cleanupSucceeded) {
            if (!releaseEngine()) cleanupSucceeded = false
        }

        val trimmed = text.orEmpty().trim()
        val reason = when {
            // 유효한 최종 글은 뒷정리가 실패했더라도 버리지 않는다(엔진은 위에서 이미 버렸다).
            failure == null && trimmed.isNotEmpty() -> null
            !cleanupSucceeded -> SpeechFailure.ENGINE_ERROR
            failure != null -> failure
            else -> SpeechFailure.NO_SPEECH
        }

        if (reason != null) {
            CarLog.i("STT: failed $reason ${request.generation}")
            request.listener.onFailed(reason)
        } else {
            // 중지를 부르지 않고 끝났어도 곧바로 오는 다음 요청은 잠깐 쉬었다가 시작하게 한다.
            if (!stopRequested) lastStoppedAt = SystemClock.uptimeMillis()
            CarLog.i("STT: final «$trimmed» ${request.generation}")
            request.listener.onFinal(trimmed)
        }
    }

    private fun cancelCurrent() {
        val request = pending ?: return
        pending = null
        generation++
        request.cancelJobs()
        CarLog.i("STT: cancel ${request.generation}")

        val detached = detachListener()
        val engineStopped = stopEngine()
        // 초기화 중인 엔진은 버려 옛 초기화 결과가 다음 요청에 섞이지 않게 한다.
        if (initializing || !detached || !engineStopped) releaseEngine()
    }

    private fun detachListener(): Boolean {
        val listener = resultListener ?: return true
        resultListener = null
        val current = engine ?: return true
        return try {
            current.removeListener(listener)
            true
        } catch (error: Exception) {
            logException("리스너 제거", error)
            false
        }
    }

    private fun stopEngine(): Boolean {
        val current = engine ?: return true
        return try {
            current.stop()
            true
        } catch (error: Exception) {
            logException("중지", error)
            false
        } finally {
            lastStoppedAt = SystemClock.uptimeMillis()
        }
    }

    private fun releaseEngine(): Boolean {
        var succeeded = detachListener()
        val current = engine
        engine = null
        engineReady = false
        initializing = false

        if (current != null) {
            try {
                current.release()
            } catch (error: Exception) {
                logException("해제", error)
                succeeded = false
            }
        }
        return succeeded
    }

    private fun logException(operation: String, error: Exception) {
        CarLog.i("STT: failed ENGINE_ERROR ($operation: ${error.message})")
    }

    /** 앱이 끝날 때 요청을 취소하고 엔진을 해제한다. */
    @Synchronized
    fun stop() {
        if (stopped) return
        stopped = true

        scope.launch {
            try {
                cancelCurrent()
                releaseEngine()
            } finally {
                scope.cancel()
            }
        }
    }

    private companion object {
        const val STOP_COOLDOWN_MS = 300L
        const val READY_TIMEOUT_MS = 6_000L
        const val FINAL_TIMEOUT_MS = 30_000L
    }
}
