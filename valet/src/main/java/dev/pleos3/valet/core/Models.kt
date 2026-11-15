package dev.pleos3.valet.core

import dev.pleos3.car.NaviPlace
import dev.pleos3.car.WindowPos

enum class Phase {
    IDLE,
    HANDED,
    GUIDE,
    DRIVING,
    FINISHING,
    DONE,
}

enum class CardKind {
    FULL,
    DEFAULT,
}

data class Snapshot(
    val timeMs: Long,
    val fuelPercent: Int?,
    val odometerKm: Float?,
    val telltales: Set<String>,
    val lowBeamOn: Boolean?,
    val openWindows: Set<WindowPos>,
)

data class TelltaleEvent(
    val name: String,
    val timeMs: Long,
)

data class HandoverCard(
    val createdMs: Long,
    val kind: CardKind,
    val destination: String?,
    val ownerMemo: String?,
    val parkingSpot: String?,
    val atHandover: Snapshot,
    val atFinish: Snapshot,
    val telltaleEvents: List<TelltaleEvent>,
    /** 주차 위치를 기사가 «이대로»로 확인했는가. 확인 전 글이 실렸거나 주차 위치가 없으면 false. */
    val parkingSpotConfirmed: Boolean = false,
)

sealed interface Effect {
    data class Say(val text: String) : Effect
    data class RouteTo(val place: NaviPlace) : Effect
    data object ShowGuide : Effect
    data object ShowFinish : Effect
    data class CardReady(val card: HandoverCard) : Effect

    /** 앞의 말이 끝난 뒤 주차 위치를 한 번 듣는다. */
    data object ListenForParkingSpot : Effect
}
