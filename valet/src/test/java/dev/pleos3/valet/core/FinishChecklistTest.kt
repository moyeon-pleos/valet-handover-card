package dev.pleos3.valet.core

import dev.pleos3.car.CarState
import dev.pleos3.car.WindowPos
import org.junit.Assert.assertEquals
import org.junit.Test

class FinishChecklistTest {
    @Test
    fun `전조등이 켜졌으면 전조등 문장을 반환한다`() {
        assertEquals(
            listOf("전조등이 켜져 있습니다"),
            FinishChecklist.lines(CarState(lowBeamOn = true)),
        )
    }

    @Test
    fun `각 열린 창문에 맞는 문장을 반환한다`() {
        val expected = mapOf(
            WindowPos.FRONT_LEFT to "운전석 창이 열려 있습니다",
            WindowPos.FRONT_RIGHT to "조수석 창이 열려 있습니다",
            WindowPos.REAR_LEFT to "왼쪽 뒷창이 열려 있습니다",
            WindowPos.REAR_RIGHT to "오른쪽 뒷창이 열려 있습니다",
        )

        for ((position, line) in expected) {
            assertEquals(
                position.name,
                listOf(line),
                FinishChecklist.lines(
                    CarState(windowOpen = mapOf(position to true)),
                ),
            )
        }
    }

    @Test
    fun `전조등과 열린 창문을 빠짐없이 한 번씩 포함한다`() {
        val state = CarState(
            lowBeamOn = true,
            windowOpen = linkedMapOf(
                WindowPos.REAR_RIGHT to true,
                WindowPos.FRONT_RIGHT to true,
                WindowPos.REAR_LEFT to true,
                WindowPos.FRONT_LEFT to true,
            ),
        )
        val expected = setOf(
            "전조등이 켜져 있습니다",
            "운전석 창이 열려 있습니다",
            "조수석 창이 열려 있습니다",
            "왼쪽 뒷창이 열려 있습니다",
            "오른쪽 뒷창이 열려 있습니다",
        )

        val actual = FinishChecklist.lines(state)

        assertEquals(expected, actual.toSet())
        assertEquals(expected.size, actual.size)
    }

    @Test
    fun `닫힌 창문과 지도에 없는 창문은 포함하지 않는다`() {
        val state = CarState(
            lowBeamOn = false,
            windowOpen = mapOf(
                WindowPos.FRONT_LEFT to false,
                WindowPos.FRONT_RIGHT to true,
                WindowPos.REAR_RIGHT to false,
            ),
        )

        assertEquals(
            listOf("조수석 창이 열려 있습니다"),
            FinishChecklist.lines(state),
        )
    }

    @Test
    fun `전조등과 창문이 모두 꺼지거나 닫혔으면 빈 목록이다`() {
        val state = CarState(
            lowBeamOn = false,
            windowOpen = WindowPos.entries.associateWith { false },
        )

        assertEquals(emptyList<String>(), FinishChecklist.lines(state))
    }

    @Test
    fun `알 수 없는 전조등과 없는 창문 정보는 경고하지 않는다`() {
        assertEquals(
            emptyList<String>(),
            FinishChecklist.lines(CarState()),
        )
    }
}
