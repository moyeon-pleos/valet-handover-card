package dev.pleos3.valet

import dev.pleos3.car.CarLog
import dev.pleos3.car.CarSignals
import dev.pleos3.car.CarState
import dev.pleos3.car.DebugBridge
import dev.pleos3.car.Dictation
import dev.pleos3.car.DictationEffect
import dev.pleos3.car.DictationState
import dev.pleos3.car.Gear
import dev.pleos3.car.LocFix
import dev.pleos3.car.LocationFeed
import dev.pleos3.car.NaviGate
import dev.pleos3.car.NaviPlace
import dev.pleos3.car.SpeechFailure
import dev.pleos3.car.SpeechInput
import dev.pleos3.car.SpeechListener
import dev.pleos3.car.Voice
import dev.pleos3.valet.core.Effect
import dev.pleos3.valet.core.HandoverCard
import dev.pleos3.valet.core.Phase
import dev.pleos3.valet.core.ValetSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

enum class GuideChoice {
    CAR,
    OWN,
}

enum class ValetScreen {
    IDLE,
    HANDED,
    GUIDE,
    WAITING,
    DRIVING,
    ARRIVED,
    FINISHING,
    DONE,
}

enum class ValetDictationTarget {
    MEMO,
    PARKING_SPOT,
}

data class ValetUiState(
    val car: CarState = CarState(),
    val phase: Phase = Phase.IDLE,
    val destination: NaviPlace? = null,
    val destinationIsExample: Boolean = true,
    val ownerMemo: String? = null,
    val guideChoice: GuideChoice? = null,
    val handoverFuelPercent: Int? = null,
    val card: HandoverCard? = null,
    val locationAtFinish: LocFix? = null,
    val dictation: DictationState<ValetDictationTarget> = DictationState.Idle,
) {
    val screen: ValetScreen
        get() {
            if (car.gear != Gear.P) return ValetScreen.DRIVING
            return when (phase) {
                Phase.IDLE -> ValetScreen.IDLE
                Phase.HANDED -> ValetScreen.HANDED
                Phase.GUIDE -> if (guideChoice == null) {
                    ValetScreen.GUIDE
                } else {
                    ValetScreen.WAITING
                }
                Phase.DRIVING -> ValetScreen.ARRIVED
                Phase.FINISHING -> ValetScreen.FINISHING
                Phase.DONE -> ValetScreen.DONE
            }
        }
}

object ValetExamples {
    const val OWNER_MEMO = "지하 1층이 차면 지하 2층에 대 주세요"

    val parkingSpots: List<String> = listOf(
        "지하 2층 C구역 17번 기둥",
        "지하 1층 A구역 엘리베이터 앞",
        "지상 주차장 정문 옆",
    )

    fun home(): NaviPlace = NaviPlace(
        name = "집",
        address = "○○아파트",
        lat = 37.5,
        lon = 127.0,
    )
}

class ValetController(
    private val signals: CarSignals,
    private val voice: Voice,
    private val navi: NaviGate,
    private val speech: SpeechInput,
    private val location: LocationFeed,
) {
    private sealed interface Command {
        data object CarChanged : Command
        data class Tap(val action: String) : Command
        data class Spoken(val text: String) : Command
        data class ListenForParkingSpot(val requestId: Long) : Command
        data class SpeechPartial(val requestId: Long, val text: String) : Command
        data class SpeechFinal(val requestId: Long, val text: String) : Command
        data class SpeechFailed(
            val requestId: Long,
            val reason: SpeechFailure,
        ) : Command
        data class HomeResolved(
            val requestId: Int,
            val place: NaviPlace?,
        ) : Command
        data class LocationReceived(val fix: LocFix) : Command
    }

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate,
    )
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val session = ValetSession { DebugBridge.nowMs() }
    private val dictation = Dictation<ValetDictationTarget>()

    private var previousState = DebugBridge.apply(signals.state.value)
    private var destination: NaviPlace? = ValetExamples.home()
    private var destinationIsExample = true
    private var ownerMemo: String? = null
    private var guideChoice: GuideChoice? = null
    private var handoverFuelPercent: Int? = null
    private var latestLocation: LocFix? = null
    private var locationAtFinish: LocFix? = null
    private var homeRequestId = 0
    private var speechRequestId = 0L
    private var parkingListenRequestId = 0L
    private var started = false

    @Volatile
    private var stopped = false

    private var loggedPhase: Phase? = null
    private var loggedScreen: ValetScreen? = null

    private val mutableState = MutableStateFlow(
        ValetUiState(
            car = previousState,
            destination = destination,
        ),
    )
    val state: StateFlow<ValetUiState> = mutableState.asStateFlow()

    fun start() {
        if (started || stopped) return
        started = true

        // 입력을 한 줄로 처리해 상태 갱신과 카드 생성이 서로 끼어들지 않게 한다.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            publish()
            for (command in commands) {
                if (stopped) break

                when (command) {
                    Command.CarChanged -> acceptCarState(
                        DebugBridge.apply(signals.state.value),
                    )
                    is Command.Tap -> handleTap(command.action)
                    is Command.Spoken -> handleSpoken(command.text)
                    is Command.ListenForParkingSpot -> {
                        if (command.requestId == parkingListenRequestId) {
                            refreshState()
                            if (
                                command.requestId == parkingListenRequestId &&
                                canDictate(ValetDictationTarget.PARKING_SPOT) &&
                                dictation.state == DictationState.Idle
                            ) {
                                parkingListenRequestId++
                                executeDictation(
                                    dictation.start(
                                        ValetDictationTarget.PARKING_SPOT,
                                    ),
                                )
                            }
                        }
                    }
                    is Command.SpeechPartial -> {
                        if (acceptsSpeech(command.requestId)) {
                            CarLog.i("HEAR: partial «${command.text}»")
                            executeDictation(dictation.onPartial(command.text))
                        }
                    }
                    is Command.SpeechFinal -> {
                        if (acceptsSpeech(command.requestId)) {
                            CarLog.i("HEAR: final «${command.text.trim()}»")
                            executeDictation(dictation.onFinal(command.text))
                        }
                    }
                    is Command.SpeechFailed -> {
                        if (acceptsSpeech(command.requestId)) {
                            CarLog.i("HEAR: failed ${command.reason}")
                            executeDictation(dictation.onFailed(command.reason))
                        }
                    }
                    is Command.HomeResolved -> {
                        if (
                            command.requestId == homeRequestId &&
                            session.phase == Phase.IDLE
                        ) {
                            // 이름이 비어 온 즐겨찾기는 예시 목적지로 대신한다.
                            val named = command.place?.takeIf { it.name.isNotBlank() }
                            destination = named ?: ValetExamples.home()
                            destinationIsExample = named == null
                            publish()
                        }
                    }
                    is Command.LocationReceived -> {
                        if (
                            command.fix.lat.isFinite() &&
                            command.fix.lon.isFinite() &&
                            command.fix.lat in -90.0..90.0 &&
                            command.fix.lon in -180.0..180.0
                        ) {
                            val previous = latestLocation
                            if (
                                previous == null ||
                                command.fix.timeMs >= previous.timeMs
                            ) {
                                latestLocation = command.fix
                            }
                        }
                    }
                }
            }
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            signals.state.collect {
                // 대기 중이던 예전 값 대신 처리 시점의 최신 값을 읽는다.
                commands.trySend(Command.CarChanged)
            }
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            DebugBridge.events.collect { event ->
                // 한 방송에 차 값과 단추가 함께 있어도 차 값을 먼저 반영한다.
                commands.trySend(Command.CarChanged)
                event["tap"]?.let { action ->
                    commands.trySend(Command.Tap(action))
                }
                event["say"]?.let { text ->
                    commands.trySend(Command.Spoken(text))
                }
            }
        }

        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            location.fixes.collect { fix ->
                commands.trySend(Command.LocationReceived(fix))
            }
        }

        requestHome()
    }

    fun tap(action: String) {
        commands.trySend(Command.Tap(action))
    }

    fun submitText(text: String) {
        commands.trySend(Command.Spoken(text))
    }

    fun stop() {
        if (stopped) return
        stopped = true

        scope.launch {
            try {
                cancelDictation()
            } finally {
                commands.close()
                scope.cancel()
            }
        }
    }

    private fun requestHome() {
        val requestId = ++homeRequestId
        navi.homeBookmark { place ->
            commands.trySend(Command.HomeResolved(requestId, place))
        }
    }

    private suspend fun refreshState(): CarState {
        val refreshed = DebugBridge.apply(signals.refresh())
        acceptCarState(refreshed)
        return refreshed
    }

    private suspend fun acceptCarState(next: CarState) {
        val previous = previousState
        previousState = next
        if (previous.gear == Gear.P && next.gear != Gear.P) {
            cancelDictation()
        }
        execute(session.onCarState(previous, next))
    }

    private fun canDictate(target: ValetDictationTarget): Boolean =
        !stopped &&
            previousState.gear == Gear.P &&
            when (target) {
                ValetDictationTarget.MEMO -> session.phase == Phase.IDLE
                ValetDictationTarget.PARKING_SPOT ->
                    session.phase == Phase.FINISHING
            }

    private fun dictationTarget(): ValetDictationTarget? = dictation.state.target

    private fun acceptsSpeech(requestId: Long): Boolean {
        val current = dictation.state
        return current is DictationState.Listening &&
            requestId == speechRequestId &&
            canDictate(current.target)
    }

    private suspend fun handleTap(action: String) {
        val current = refreshState()

        // 초기화도 운행 화면에서는 실행하지 않는다.
        if (stopped || current.gear != Gear.P) return

        when (action) {
            "speak_memo" -> {
                if (session.phase != Phase.IDLE) return
                executeDictation(dictation.start(ValetDictationTarget.MEMO))
            }

            "speak_spot" -> {
                if (session.phase != Phase.FINISHING) return
                parkingListenRequestId++
                executeDictation(
                    dictation.start(ValetDictationTarget.PARKING_SPOT),
                )
            }

            "confirm" -> {
                val target = dictationTarget() ?: return
                if (!canDictate(target)) return
                // 방금 읽은 차 값을 넘겨, 싣는 도중에 다시 읽지 않게 한다.
                executeDictation(dictation.confirm(), fresh = current)
            }

            "retry" -> {
                val target = dictationTarget() ?: return
                if (!canDictate(target)) return
                val effects = dictation.retry()
                if (effects.isNotEmpty()) CarLog.i("CONFIRM: retry")
                executeDictation(effects)
            }

            "handover" -> {
                if (session.phase != Phase.IDLE) return
                cancelDictation()
                handoverFuelPercent = current.fuelPercent
                execute(
                    session.handOver(current, destination, ownerMemo),
                )
                // 맡김 순간 이미 착석해 있어도 안내를 시작할 수 있다.
                execute(session.onCarState(current, current))
            }

            "navi_car", "navi_own" -> {
                if (
                    session.phase != Phase.GUIDE ||
                    guideChoice != null
                ) {
                    return
                }
                val useCarNavi = action == "navi_car"
                guideChoice = if (useCarNavi) {
                    GuideChoice.CAR
                } else {
                    GuideChoice.OWN
                }
                execute(session.chooseNavi(useCarNavi))
            }

            "finish" -> execute(session.startFinish(current))

            "spot1", "spot2", "spot3" -> {
                if (session.phase != Phase.FINISHING) return
                val index = when (action) {
                    "spot1" -> 0
                    "spot2" -> 1
                    else -> 2
                }
                applyText(ValetExamples.parkingSpots[index], current)
            }

            "reset" -> {
                cancelDictation()
                session.reset()
                destination = ValetExamples.home()
                destinationIsExample = true
                ownerMemo = null
                guideChoice = null
                handoverFuelPercent = null
                latestLocation = null
                locationAtFinish = null
                publish()
                requestHome()
            }
        }
    }

    private suspend fun handleSpoken(text: String) {
        if (text.isBlank()) return

        // 그 사이 문이 열렸다면 기본 카드가 먼저 만들어지고 입력은 무시된다.
        val refreshed = refreshState()
        if (!stopped) applyText(text, refreshed)
    }

    private suspend fun applyText(text: String, refreshed: CarState) {
        if (text.isBlank() || refreshed.gear != Gear.P) return

        val target = when (session.phase) {
            Phase.IDLE -> ValetDictationTarget.MEMO
            Phase.FINISHING -> ValetDictationTarget.PARKING_SPOT
            else -> return
        }
        if (target == ValetDictationTarget.PARKING_SPOT) {
            parkingListenRequestId++
        }
        CarLog.i("HEAR: given «${text.trim()}»")
        executeDictation(dictation.heard(target, text))
    }

    private suspend fun cancelDictation() {
        // 이전 세션에서 말이 끝나기를 기다리던 자동 듣기도 무효화한다.
        parkingListenRequestId++
        val previous = dictation.state
        val effects = dictation.cancel()
        if (
            previous != DictationState.Idle &&
            previous !is DictationState.Listening
        ) {
            CarLog.i("HEAR: cancel")
        }
        executeDictation(effects)
    }

    private fun syncParkingSpot() {
        session.spotHeard(
            dictation.pendingText.takeIf {
                dictation.state.target == ValetDictationTarget.PARKING_SPOT
            },
        )
    }

    /** fresh 는 부르는 쪽이 방금 읽어 둔 차 값. 없으면 실을 때 새로 읽는다. */
    private suspend fun executeDictation(
        effects: List<DictationEffect<ValetDictationTarget>>,
        fresh: CarState? = null,
    ) {
        syncParkingSpot()

        for (effect in effects) {
            when (effect) {
                DictationEffect.StartListening -> {
                    val current = dictation.state
                    if (
                        current !is DictationState.Listening ||
                        !canDictate(current.target)
                    ) {
                        continue
                    }

                    val requestId = ++speechRequestId
                    CarLog.i("HEAR: start ${current.target.name}")
                    // 차가 읽어 주던 말이 받아 적히지 않게 먼저 끊는다.
                    voice.hush()
                    speech.listen(
                        object : SpeechListener {
                            override fun onPartial(text: String) {
                                commands.trySend(
                                    Command.SpeechPartial(requestId, text),
                                )
                            }

                            override fun onFinal(text: String) {
                                commands.trySend(
                                    Command.SpeechFinal(requestId, text),
                                )
                            }

                            override fun onFailed(reason: SpeechFailure) {
                                commands.trySend(
                                    Command.SpeechFailed(requestId, reason),
                                )
                            }
                        },
                    )
                }
                DictationEffect.StopListening -> {
                    // 채널에 이미 들어온 옛 요청의 콜백도 함께 무효화한다.
                    speechRequestId++
                    CarLog.i("HEAR: cancel")
                    speech.cancel()
                }
                is DictationEffect.Say -> voice.say(effect.text)
                is DictationEffect.Commit -> {
                    when (effect.target) {
                        ValetDictationTarget.MEMO -> {
                            if (canDictate(ValetDictationTarget.MEMO)) {
                                ownerMemo = effect.text
                                CarLog.i("CONFIRM: MEMO «${effect.text}»")
                            }
                        }
                        ValetDictationTarget.PARKING_SPOT -> {
                            // 다시 읽는 사이 문이 열리면 확인한 글이 빠진 기본 카드가 먼저 만들어진다.
                            val refreshed = fresh ?: refreshState()
                            if (canDictate(ValetDictationTarget.PARKING_SPOT)) {
                                CarLog.i(
                                    "CONFIRM: PARKING_SPOT «${effect.text}»",
                                )
                                execute(
                                    session.parkingSpotSaid(
                                        effect.text,
                                        refreshed,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
        }
        publish()
    }

    private suspend fun execute(effects: List<Effect>) {
        for (effect in effects) {
            when (effect) {
                is Effect.Say -> voice.say(effect.text)
                is Effect.RouteTo -> navi.routeTo(effect.place)
                Effect.ShowGuide -> Unit
                Effect.ShowFinish -> Unit
                Effect.ListenForParkingSpot -> {
                    val requestId = ++parkingListenRequestId
                    voice.whenQuiet {
                        if (!stopped) {
                            commands.trySend(
                                Command.ListenForParkingSpot(requestId),
                            )
                        }
                    }
                }
                is Effect.CardReady -> {
                    // 카드에는 세션이 기억한 확인 전 글이 이미 복사되어 있다.
                    cancelDictation()
                    locationAtFinish = latestLocation?.takeIf {
                        it.timeMs <= effect.card.createdMs
                    }
                    logCard(effect.card)
                }
            }
        }
        // 안내와 마무리 화면은 세션 단계와 현재 기어에서 결정한다.
        publish()
    }

    private fun publish() {
        val next = ValetUiState(
            car = previousState,
            phase = session.phase,
            destination = destination,
            destinationIsExample = destinationIsExample,
            ownerMemo = ownerMemo,
            guideChoice = guideChoice,
            handoverFuelPercent = handoverFuelPercent,
            card = session.card,
            locationAtFinish = locationAtFinish,
            dictation = dictation.state,
        )
        mutableState.value = next

        if (loggedPhase != next.phase) {
            loggedPhase = next.phase
            CarLog.i("PHASE: ${next.phase.name}")
        }
        if (loggedScreen != next.screen) {
            loggedScreen = next.screen
            CarLog.i("SCREEN: ${next.screen.name}")
        }
    }

    private fun logCard(card: HandoverCard) {
        val warnings = card.telltaleEvents.joinToString(",") {
            "${it.name}@${it.timeMs}"
        }.ifEmpty { "없음" }

        val parkingSpot = card.parkingSpot?.let {
            if (card.parkingSpotConfirmed) it else "$it(확인 전)"
        } ?: "미기록"

        CarLog.i(
            "CARD: ${card.kind.name}" +
                " | 생성=${card.createdMs}" +
                " | 목적지=${card.destination ?: "없음"}" +
                " | 메모=${card.ownerMemo ?: "없음"}" +
                " | 주차 위치=$parkingSpot" +
                " | 연료=${card.atHandover.fuelPercent}->${card.atFinish.fuelPercent}" +
                " | 주행거리=${card.atHandover.odometerKm}->${card.atFinish.odometerKm}" +
                " | 운행 경고=$warnings",
        )
    }
}
