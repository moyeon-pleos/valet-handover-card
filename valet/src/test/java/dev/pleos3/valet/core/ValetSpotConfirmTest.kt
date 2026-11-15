package dev.pleos3.valet.core

import dev.pleos3.car.CarState
import dev.pleos3.car.Gear
import dev.pleos3.car.NaviPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 주차 위치를 말로 받을 때의 규칙(팀 결정 2026-11-11).
 * 들은 글은 기사가 «이대로»를 눌러야 확인된 것이고, 확인하지 않고 내리면 «확인 전» 표시와 함께 카드에 실린다.
 */
class ValetSpotConfirmTest {
    private var now = 0L
    private val session = ValetSession { now }
    private var state = CarState(
        gear = Gear.P,
        driverSeated = false,
        driverDoorOpen = false,
        fuelPercent = 70,
        odometerKm = 1000f,
        lowBeamOn = false,
    )
    private val home = NaviPlace("집", "예시 아파트", 37.5, 127.0)

    private fun update(next: CarState): List<Effect> {
        val effects = session.onCarState(state, next)
        state = next
        return effects
    }

    /** 맡김 → 착석 → 운행 → P 까지 간다(인계 마치기 직전). */
    private fun driveAndPark() {
        session.handOver(state, home, "주차 메모")
        update(state.copy(driverSeated = true))
        update(state.copy(gear = Gear.D))
        update(state.copy(gear = Gear.P))
    }

    private fun toFinishing(): List<Effect> {
        driveAndPark()
        return session.startFinish(state)
    }

    private fun openDoor(): HandoverCard {
        update(state.copy(driverDoorOpen = true))
        return requireNotNull(session.card)
    }

    @Test
    fun `인계 마치기는 주차 위치를 물은 뒤 듣기를 시킨다`() {
        val effects = toFinishing()
        assertEquals(Effect.ListenForParkingSpot, effects.last())
        assertEquals(Effect.Say("주차 위치를 말씀해 주세요"), effects[effects.size - 2])
        assertEquals(1, effects.count { it == Effect.ListenForParkingSpot })
    }

    @Test
    fun `이대로를 누른 주차 위치는 확인된 글로 전체 카드에 실린다`() {
        toFinishing()
        session.parkingSpotSaid("지하 이층 씨 구역 17번 기둥", state)

        val card = requireNotNull(session.card)
        assertEquals(CardKind.FULL, card.kind)
        assertEquals("지하 이층 씨 구역 17번 기둥", card.parkingSpot)
        assertTrue(card.parkingSpotConfirmed)
    }

    @Test
    fun `들은 글을 확인하지 않고 내리면 기본 카드에 확인 전으로 실린다`() {
        toFinishing()
        session.spotHeard("지하 이층 씨 구역 17번기동")

        val card = openDoor()
        assertEquals(CardKind.DEFAULT, card.kind)
        assertEquals("지하 이층 씨 구역 17번기동", card.parkingSpot)
        assertFalse(card.parkingSpotConfirmed)
        assertEquals(Phase.DONE, session.phase)
    }

    @Test
    fun `다시 말하기로 글을 버린 뒤 내리면 주차 위치가 없다`() {
        toFinishing()
        session.spotHeard("틀리게 들은 글")
        session.spotHeard(null)

        val card = openDoor()
        assertNull(card.parkingSpot)
        assertFalse(card.parkingSpotConfirmed)
    }

    @Test
    fun `아무것도 듣지 못한 채 내리면 주차 위치가 없다`() {
        toFinishing()

        val card = openDoor()
        assertEquals(CardKind.DEFAULT, card.kind)
        assertNull(card.parkingSpot)
        assertFalse(card.parkingSpotConfirmed)
    }

    @Test
    fun `빈 글은 들은 것으로 치지 않는다`() {
        toFinishing()
        session.spotHeard("   ")

        assertNull(openDoor().parkingSpot)
    }

    @Test
    fun `인계 마치기 전에 들어온 글은 기억하지 않는다`() {
        driveAndPark()
        session.spotHeard("운행 중에 들어온 글")

        val card = openDoor()
        assertEquals(CardKind.DEFAULT, card.kind)
        assertNull(card.parkingSpot)
    }

    @Test
    fun `들은 글이 있어도 이대로를 누른 글이 확인된 글로 실린다`() {
        toFinishing()
        session.spotHeard("처음 들은 글")
        session.parkingSpotSaid("다시 말해 확인한 글", state)

        val card = requireNotNull(session.card)
        assertEquals(CardKind.FULL, card.kind)
        assertEquals("다시 말해 확인한 글", card.parkingSpot)
        assertTrue(card.parkingSpotConfirmed)
    }

    @Test
    fun `초기화하면 확인 전 글을 지운다`() {
        toFinishing()
        session.spotHeard("앞 운행에서 들은 글")
        session.reset()
        state = state.copy(gear = Gear.P, driverSeated = false, driverDoorOpen = false)

        toFinishing()
        assertNull(openDoor().parkingSpot)
    }
}
