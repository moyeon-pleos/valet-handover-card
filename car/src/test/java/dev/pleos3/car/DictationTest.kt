package dev.pleos3.car

import dev.pleos3.car.DictationEffect.Commit
import dev.pleos3.car.DictationEffect.Say
import dev.pleos3.car.DictationEffect.StartListening
import dev.pleos3.car.DictationEffect.StopListening
import dev.pleos3.car.DictationState.Failed
import dev.pleos3.car.DictationState.Heard
import dev.pleos3.car.DictationState.Idle
import dev.pleos3.car.DictationState.Listening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 받아쓰기 규칙 — 듣기는 요청했을 때만, 들은 글은 확인한 뒤에만 싣는다. */
class DictationTest {
    private enum class Target { MEMO, SPOT }

    private fun listening(target: Target = Target.MEMO): Dictation<Target> =
        Dictation<Target>().also { it.start(target) }

    private fun heard(text: String = "지하 이층 씨 구역"): Dictation<Target> =
        listening().also { it.onFinal(text) }

    @Test
    fun `처음에는 쉬고 있고 확인을 기다리는 글이 없다`() {
        val d = Dictation<Target>()
        assertEquals(Idle, d.state)
        assertNull(d.pendingText)
    }

    @Test
    fun `듣기를 시작하면 듣는 중이 되고 마이크를 연다`() {
        val d = Dictation<Target>()
        assertEquals(listOf(StartListening), d.start(Target.MEMO))
        assertEquals(Listening(Target.MEMO, ""), d.state)
    }

    @Test
    fun `중간 글은 듣는 중에만 화면 글을 바꾼다`() {
        val d = listening()
        assertEquals(emptyList<DictationEffect<Target>>(), d.onPartial("지하"))
        assertEquals(Listening(Target.MEMO, "지하"), d.state)

        val idle = Dictation<Target>()
        assertEquals(emptyList<DictationEffect<Target>>(), idle.onPartial("지하"))
        assertEquals(Idle, idle.state)
    }

    @Test
    fun `최종 글이 오면 들음이 되고 읽어 준다 — 아직 싣지 않는다`() {
        val d = listening(Target.SPOT)
        val effects = d.onFinal("  지하 이층 씨 구역 ")
        assertEquals(listOf(Say(Dictation.readBack("지하 이층 씨 구역"))), effects)
        assertEquals(Heard(Target.SPOT, "지하 이층 씨 구역"), d.state)
        assertEquals("지하 이층 씨 구역", d.pendingText)
    }

    @Test
    fun `이대로를 누르면 그때 싣고 쉼으로 돌아간다`() {
        val d = heard("지하 이층")
        assertEquals(listOf(Commit(Target.MEMO, "지하 이층")), d.confirm())
        assertEquals(Idle, d.state)
        assertNull(d.pendingText)
    }

    @Test
    fun `들은 글이 없으면 이대로는 아무 일도 하지 않는다`() {
        assertEquals(emptyList<DictationEffect<Target>>(), Dictation<Target>().confirm())
        val d = listening()
        assertEquals(emptyList<DictationEffect<Target>>(), d.confirm())
        assertEquals(Listening(Target.MEMO, ""), d.state)
    }

    @Test
    fun `다시 말하기는 들은 글을 버리고 다시 듣는다`() {
        val d = heard()
        assertEquals(listOf(StartListening), d.retry())
        assertEquals(Listening(Target.MEMO, ""), d.state)
        assertNull(d.pendingText)
    }

    @Test
    fun `빈 최종 글은 못 들은 것으로 친다`() {
        val d = listening()
        assertEquals(listOf(Say(Dictation.NO_SPEECH_TEXT)), d.onFinal("   "))
        assertEquals(Failed(Target.MEMO, SpeechFailure.NO_SPEECH), d.state)
    }

    @Test
    fun `못 들으면 그렇게 말하고 다시 말하기로 다시 듣는다`() {
        val d = listening()
        assertEquals(listOf(Say(Dictation.NO_SPEECH_TEXT)), d.onFailed(SpeechFailure.NO_SPEECH))
        assertEquals(Failed(Target.MEMO, SpeechFailure.NO_SPEECH), d.state)
        assertEquals(listOf(StartListening), d.retry())
        assertEquals(Listening(Target.MEMO, ""), d.state)
    }

    @Test
    fun `음성 인식을 못 쓰면 다른 말로 알린다`() {
        val engine = listening()
        assertEquals(listOf(Say(Dictation.UNAVAILABLE_TEXT)), engine.onFailed(SpeechFailure.ENGINE_ERROR))
        val missing = listening()
        assertEquals(listOf(Say(Dictation.UNAVAILABLE_TEXT)), missing.onFailed(SpeechFailure.UNAVAILABLE))
        assertEquals(Failed(Target.MEMO, SpeechFailure.UNAVAILABLE), missing.state)
    }

    @Test
    fun `듣는 중이 아닐 때 온 최종 글과 실패는 버린다`() {
        val d = Dictation<Target>()
        assertEquals(emptyList<DictationEffect<Target>>(), d.onFinal("옆 사람의 말"))
        assertEquals(emptyList<DictationEffect<Target>>(), d.onFailed(SpeechFailure.NO_SPEECH))
        assertEquals(Idle, d.state)

        val waiting = heard("지하 이층")
        assertEquals(emptyList<DictationEffect<Target>>(), waiting.onFinal("늦게 온 글"))
        assertEquals(Heard(Target.MEMO, "지하 이층"), waiting.state)
    }

    @Test
    fun `예시 문장과 시험용 입력도 확인을 거친다`() {
        val d = Dictation<Target>()
        assertEquals(listOf(Say(Dictation.readBack("지상 주차장 정문 옆"))), d.heard(Target.SPOT, " 지상 주차장 정문 옆 "))
        assertEquals(Heard(Target.SPOT, "지상 주차장 정문 옆"), d.state)
        assertEquals(listOf(Commit(Target.SPOT, "지상 주차장 정문 옆")), d.confirm())
    }

    @Test
    fun `듣는 중에 예시 문장이 오면 마이크를 닫고 그 글을 쓴다`() {
        val d = listening(Target.SPOT)
        assertEquals(
            listOf(StopListening, Say(Dictation.readBack("지하 일층"))),
            d.heard(Target.SPOT, "지하 일층"),
        )
        assertEquals(Heard(Target.SPOT, "지하 일층"), d.state)
    }

    @Test
    fun `빈 예시 글은 무시한다`() {
        val d = heard("지하 이층")
        assertEquals(emptyList<DictationEffect<Target>>(), d.heard(Target.MEMO, "  "))
        assertEquals(Heard(Target.MEMO, "지하 이층"), d.state)
    }

    @Test
    fun `듣는 중에 다시 시작하면 앞의 듣기를 닫고 새로 연다`() {
        val d = listening(Target.MEMO)
        d.onPartial("지하")
        assertEquals(listOf(StopListening, StartListening), d.start(Target.SPOT))
        assertEquals(Listening(Target.SPOT, ""), d.state)
    }

    @Test
    fun `버리면 쉼으로 돌아가고 듣는 중이었으면 마이크를 닫는다`() {
        val busy = listening()
        assertEquals(listOf(StopListening), busy.cancel())
        assertEquals(Idle, busy.state)

        val waiting = heard()
        assertEquals(emptyList<DictationEffect<Target>>(), waiting.cancel())
        assertEquals(Idle, waiting.state)
        assertNull(waiting.pendingText)

        assertEquals(emptyList<DictationEffect<Target>>(), Dictation<Target>().cancel())
    }
}
