package dev.pleos3.valet.core

import dev.pleos3.car.CarState
import dev.pleos3.car.Gear
import dev.pleos3.car.NaviPlace

class ValetSession(private val clock: () -> Long) {
    private var currentPhase = Phase.IDLE
    private var currentCard: HandoverCard? = null
    private var handoverSnapshot: Snapshot? = null
    private var selectedDestination: NaviPlace? = null
    private var ownerMemo: String? = null
    private var unconfirmedParkingSpot: String? = null
    private var missingDestinationAnnounced = false
    private var arrivalAnnounced = false
    private val telltaleEvents = mutableListOf<TelltaleEvent>()

    val phase: Phase
        get() = currentPhase

    val card: HandoverCard?
        get() = currentCard

    fun handOver(
        state: CarState,
        destination: NaviPlace?,
        memo: String?,
    ): List<Effect> {
        if (currentPhase != Phase.IDLE) return emptyList()

        handoverSnapshot = snapshot(state, clock())
        selectedDestination = destination
        ownerMemo = memo
        currentPhase = Phase.HANDED
        return listOf(Effect.Say("맡김을 시작했습니다"))
    }

    fun onCarState(
        prev: CarState,
        now: CarState,
    ): List<Effect> {
        return when (currentPhase) {
            Phase.IDLE, Phase.DONE -> emptyList()

            Phase.HANDED, Phase.GUIDE -> {
                if (now.gear != Gear.P) {
                    currentPhase = Phase.DRIVING
                    emptyList()
                } else if (
                    currentPhase == Phase.HANDED &&
                    now.driverSeated == true
                ) {
                    val destination = selectedDestination
                    if (destination != null) {
                        currentPhase = Phase.GUIDE
                        listOf(
                            Effect.ShowGuide,
                            Effect.Say(
                                "차주님이 정한 목적지 ‘${destination.name}’으로 안내할까요?",
                            ),
                        )
                    } else if (!missingDestinationAnnounced) {
                        missingDestinationAnnounced = true
                        listOf(Effect.Say("정해 둔 목적지가 없습니다"))
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            }

            Phase.DRIVING -> {
                val effects = mutableListOf<Effect>()
                val newlyLit = (now.telltales - prev.telltales).sorted()
                if (newlyLit.isNotEmpty()) {
                    val eventTime = clock()
                    for (name in newlyLit) {
                        telltaleEvents += TelltaleEvent(name, eventTime)
                        effects += Effect.Say(
                            "경고등이 켜졌습니다. ${Names.telltale(name)}",
                        )
                    }
                }

                if (shouldCreateDefault(prev, now)) {
                    effects += Effect.CardReady(
                        makeCard(CardKind.DEFAULT, now, null),
                    )
                } else if (prev.gear != Gear.P && now.gear == Gear.P) {
                    effects += Effect.ShowFinish
                    if (
                        !arrivalAnnounced &&
                        (now.passengerSeated == true || now.rearSeated == true)
                    ) {
                        arrivalAnnounced = true
                        effects += Effect.Say(
                            "도착했습니다. 차주님, 인계를 확인해 주세요",
                        )
                    }
                }
                effects
            }

            Phase.FINISHING -> {
                if (shouldCreateDefault(prev, now)) {
                    listOf(
                        Effect.CardReady(
                            makeCard(
                                CardKind.DEFAULT,
                                now,
                                unconfirmedParkingSpot,
                            ),
                        ),
                    )
                } else {
                    emptyList()
                }
            }
        }
    }

    fun chooseNavi(useCarNavi: Boolean): List<Effect> {
        if (currentPhase != Phase.GUIDE || !useCarNavi) {
            return emptyList()
        }
        val destination = selectedDestination ?: return emptyList()
        return listOf(
            Effect.RouteTo(destination),
            Effect.Say("차 내비로 안내를 시작합니다"),
        )
    }

    fun startFinish(now: CarState): List<Effect> {
        if (currentPhase != Phase.DRIVING || now.gear != Gear.P) {
            return emptyList()
        }

        currentPhase = Phase.FINISHING
        val checklist = FinishChecklist.lines(now).ifEmpty {
            listOf("전조등과 창문은 모두 괜찮습니다")
        }
        return checklist.map { Effect.Say(it) } +
            Effect.Say("주차 위치를 말씀해 주세요") +
            Effect.ListenForParkingSpot
    }

    fun parkingSpotSaid(
        text: String,
        refreshed: CarState,
    ): List<Effect> {
        if (currentPhase != Phase.FINISHING) return emptyList()

        val completed = makeCard(
            kind = CardKind.FULL,
            state = refreshed,
            parkingSpot = text,
            parkingSpotConfirmed = true,
        )
        return listOf(
            Effect.CardReady(completed),
            Effect.Say("인계 카드를 만들었습니다"),
        )
    }

    /**
     * 기사가 말했지만 아직 «이대로»를 누르지 않은 주차 위치를 기억한다.
     * 확인 없이 내리면 기본 카드에 «확인 전»으로 싣는다.
     */
    fun spotHeard(text: String?) {
        if (currentPhase != Phase.FINISHING) return
        unconfirmedParkingSpot = text?.takeIf { it.isNotBlank() }
    }

    fun reset() {
        currentPhase = Phase.IDLE
        currentCard = null
        handoverSnapshot = null
        selectedDestination = null
        ownerMemo = null
        unconfirmedParkingSpot = null
        missingDestinationAnnounced = false
        arrivalAnnounced = false
        telltaleEvents.clear()
    }

    private fun shouldCreateDefault(
        prev: CarState,
        now: CarState,
    ): Boolean =
        now.gear == Gear.P &&
            prev.driverDoorOpen == false &&
            now.driverDoorOpen == true

    private fun makeCard(
        kind: CardKind,
        state: CarState,
        parkingSpot: String?,
        parkingSpotConfirmed: Boolean = false,
    ): HandoverCard {
        val createdMs = clock()
        val completed = HandoverCard(
            createdMs = createdMs,
            kind = kind,
            destination = selectedDestination?.name,
            ownerMemo = ownerMemo,
            parkingSpot = parkingSpot,
            atHandover = checkNotNull(handoverSnapshot),
            atFinish = snapshot(state, createdMs),
            telltaleEvents = telltaleEvents.toList(),
            parkingSpotConfirmed = parkingSpotConfirmed,
        )
        currentCard = completed
        currentPhase = Phase.DONE
        unconfirmedParkingSpot = null
        return completed
    }

    private fun snapshot(state: CarState, timeMs: Long): Snapshot =
        Snapshot(
            timeMs = timeMs,
            fuelPercent = state.fuelPercent,
            odometerKm = state.odometerKm,
            telltales = state.telltales.toSet(),
            lowBeamOn = state.lowBeamOn,
            openWindows = state.windowOpen
                .filterValues { it }
                .keys
                .toSet(),
        )
}
