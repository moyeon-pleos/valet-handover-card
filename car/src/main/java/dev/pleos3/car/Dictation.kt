package dev.pleos3.car

/**
 * 받아쓰기 한 건의 상태. T 는 «어디에 쓸 말인가»(메모·주차 위치·신고 …).
 * 쉼 → 듣는 중(중간 글) → 들음(확인 기다림) → 실음, 그리고 실패(못 들음·오류).
 */
sealed interface DictationState<out T> {
    /** 받아쓰는 대상. 쉼이면 null. */
    val target: T?

    data object Idle : DictationState<Nothing> {
        override val target: Nothing? = null
    }
    data class Listening<T>(override val target: T, val partial: String = "") : DictationState<T>
    data class Heard<T>(override val target: T, val text: String) : DictationState<T>
    data class Failed<T>(override val target: T, val reason: SpeechFailure) : DictationState<T>
}

/** 받아쓰기가 바깥에 시키는 일. 조종 코드가 차례로 실행한다. */
sealed interface DictationEffect<out T> {
    data object StartListening : DictationEffect<Nothing>
    data object StopListening : DictationEffect<Nothing>
    data class Say(val text: String) : DictationEffect<Nothing>
    data class Commit<T>(val target: T, val text: String) : DictationEffect<T>
}

/**
 * 말로 받은 글을 «확인한 뒤에만» 싣게 하는 순수 상태 기계(팀 결정 2026-11-11).
 * 실제 말(음성 인식), 시험용 입력(디버그 say), 예시 문장 단추가 모두 이 문으로 들어온다.
 */
class Dictation<T : Any> {
    var state: DictationState<T> = DictationState.Idle
        private set

    /** 확인을 기다리는 글. 들음 상태가 아니면 null. */
    val pendingText: String?
        get() = (state as? DictationState.Heard<T>)?.text

    /** 듣기를 시작한다(단추를 눌렀거나 앱이 물은 직후). */
    fun start(target: T): List<DictationEffect<T>> {
        val wasListening = state is DictationState.Listening
        state = DictationState.Listening(target)
        return if (wasListening) {
            listOf(DictationEffect.StopListening, DictationEffect.StartListening)
        } else {
            listOf(DictationEffect.StartListening)
        }
    }

    /** 말하는 도중의 중간 글. */
    fun onPartial(text: String): List<DictationEffect<T>> {
        val current = state as? DictationState.Listening<T> ?: return emptyList()
        state = current.copy(partial = text)
        return emptyList()
    }

    /** 음성 인식의 최종 글. */
    fun onFinal(text: String): List<DictationEffect<T>> {
        val current = state as? DictationState.Listening<T> ?: return emptyList()
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return onFailed(SpeechFailure.NO_SPEECH)

        state = DictationState.Heard(current.target, trimmed)
        return listOf(DictationEffect.Say(readBack(trimmed)))
    }

    /** 음성 인식이 못 들었거나 오류가 났다. */
    fun onFailed(reason: SpeechFailure): List<DictationEffect<T>> {
        val current = state as? DictationState.Listening<T> ?: return emptyList()
        state = DictationState.Failed(current.target, reason)
        return listOf(DictationEffect.Say(failureText(reason)))
    }

    /** 음성 인식을 거치지 않고 들어온 글(예시 문장 단추, 디버그 say). */
    fun heard(target: T, text: String): List<DictationEffect<T>> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        val wasListening = state is DictationState.Listening
        state = DictationState.Heard(target, trimmed)
        val say = DictationEffect.Say(readBack(trimmed))
        return if (wasListening) {
            listOf(DictationEffect.StopListening, say)
        } else {
            listOf(say)
        }
    }

    /** «이대로» — 들은 글을 싣는다. */
    fun confirm(): List<DictationEffect<T>> {
        val current = state as? DictationState.Heard<T> ?: return emptyList()
        state = DictationState.Idle
        return listOf(DictationEffect.Commit(current.target, current.text))
    }

    /** «다시 말하기». */
    fun retry(): List<DictationEffect<T>> {
        val target = state.target ?: return emptyList()
        return start(target)
    }

    /** 화면을 떠나는 등으로 받아쓰기를 버린다. */
    fun cancel(): List<DictationEffect<T>> {
        val wasListening = state is DictationState.Listening
        state = DictationState.Idle
        return if (wasListening) {
            listOf(DictationEffect.StopListening)
        } else {
            emptyList()
        }
    }

    companion object {
        const val NO_SPEECH_TEXT = "잘 듣지 못했습니다"
        const val UNAVAILABLE_TEXT = "음성 인식을 쓸 수 없습니다"

        /** 들은 글을 읽어 줄 문장. */
        fun readBack(text: String): String = "$text 라고 들었습니다"

        /** 듣지 못했을 때 알릴 문장. 화면과 목소리가 같은 글을 쓴다. */
        fun failureText(reason: SpeechFailure): String =
            if (reason == SpeechFailure.NO_SPEECH) NO_SPEECH_TEXT else UNAVAILABLE_TEXT
    }
}
