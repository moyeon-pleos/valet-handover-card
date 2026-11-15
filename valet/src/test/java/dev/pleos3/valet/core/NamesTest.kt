package dev.pleos3.valet.core

import dev.pleos3.car.WindowPos
import org.junit.Assert.assertEquals
import org.junit.Test

class NamesTest {
    @Test
    fun `창문 네 곳의 한국어 이름을 반환한다`() {
        val expected = mapOf(
            WindowPos.FRONT_LEFT to "운전석 창",
            WindowPos.FRONT_RIGHT to "조수석 창",
            WindowPos.REAR_LEFT to "왼쪽 뒷창",
            WindowPos.REAR_RIGHT to "오른쪽 뒷창",
        )

        for ((position, name) in expected) {
            assertEquals(position.name, name, Names.window(position))
        }
    }

    @Test
    fun `표에 있는 경고등의 한국어 이름을 반환한다`() {
        val expected = mapOf(
            "TPMS" to "타이어 공기압",
            "ABS" to "ABS",
            "AIRBAG" to "에어백",
            "LOW_VOLT_BATTERY" to "배터리",
            "MASTER_WARNING" to "통합 경고",
            "SEAT_BELT" to "안전벨트",
            "DOOR_OPEN" to "문 열림",
        )

        for ((name, translated) in expected) {
            assertEquals(name, translated, Names.telltale(name))
        }
    }

    @Test
    fun `표에 없는 경고등 이름은 그대로 반환한다`() {
        for (name in listOf("ENGINE_CHECK", "새 경고등", "", "tpms")) {
            assertEquals(name, name, Names.telltale(name))
        }
    }
}
