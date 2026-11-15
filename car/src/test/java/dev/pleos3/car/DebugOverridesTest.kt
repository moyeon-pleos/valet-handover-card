package dev.pleos3.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugOverridesTest {
    @Test
    fun `기어 네 값을 덮어쓴다`() {
        val expected = mapOf(
            "P" to Gear.P,
            "R" to Gear.R,
            "N" to Gear.N,
            "D" to Gear.D,
        )

        for ((raw, gear) in expected) {
            val result = DebugOverrides.apply(
                CarState(gear = Gear.UNKNOWN),
                mapOf("gear" to raw),
            )
            assertEquals(gear, result.gear)
        }
    }

    @Test
    fun `운전석 조수석 뒷좌석 착석을 각각 덮어쓴다`() {
        val base = CarState(
            driverSeated = false,
            passengerSeated = true,
            rearSeated = false,
        )

        val result = DebugOverrides.apply(
            base,
            mapOf(
                "seat_driver" to "1",
                "seat_passenger" to "0",
                "seat_rear" to "1",
            ),
        )

        assertEquals(
            base.copy(
                driverSeated = true,
                passengerSeated = false,
                rearSeated = true,
            ),
            result,
        )
    }

    @Test
    fun `창문 네 개를 알맞은 위치에 덮어쓴다`() {
        val result = DebugOverrides.apply(
            CarState(),
            mapOf(
                "win_fl" to "1",
                "win_fr" to "0",
                "win_rl" to "0",
                "win_rr" to "1",
            ),
        )

        assertEquals(
            mapOf(
                WindowPos.FRONT_LEFT to true,
                WindowPos.FRONT_RIGHT to false,
                WindowPos.REAR_LEFT to false,
                WindowPos.REAR_RIGHT to true,
            ),
            result.windowOpen,
        )
    }

    @Test
    fun `지정하지 않은 창문 값과 원본 지도는 유지한다`() {
        val windows = linkedMapOf(
            WindowPos.FRONT_LEFT to false,
            WindowPos.FRONT_RIGHT to true,
        )
        val base = CarState(windowOpen = windows)

        val result = DebugOverrides.apply(base, mapOf("win_fl" to "1"))

        assertTrue(result.windowOpen.getValue(WindowPos.FRONT_LEFT))
        assertTrue(result.windowOpen.getValue(WindowPos.FRONT_RIGHT))
        assertFalse(windows.getValue(WindowPos.FRONT_LEFT))
        assertEquals(windows, base.windowOpen)
    }

    @Test
    fun `연료 연비 주행거리를 덮어쓴다`() {
        val result = DebugOverrides.apply(
            CarState(),
            mapOf(
                "fuel" to "42",
                "economy" to "12.5",
                "odo" to "12345.75",
            ),
        )

        assertEquals(
            CarState(
                fuelPercent = 42,
                fuelEconomyKmPerL = 12.5f,
                odometerKm = 12345.75f,
            ),
            result,
        )
    }

    @Test
    fun `경고등을 기존 집합에 더하고 뺀다`() {
        val original = linkedSetOf("ABS", "TPMS")
        val base = CarState(telltales = original)

        val result = DebugOverrides.apply(
            base,
            mapOf(
                "telltales" to " ENGINE_OIL_PRESSURE,TPMS,TPMS ",
                "telltales_off" to "ABS,TPMS",
            ),
        )

        assertEquals(setOf("ENGINE_OIL_PRESSURE"), result.telltales)
        assertEquals(setOf("ABS", "TPMS"), original)
        assertEquals(setOf("ABS", "TPMS"), base.telltales)
    }

    @Test
    fun `빈 경고등 목록은 기존 집합을 지우지 않는다`() {
        val base = CarState(telltales = setOf("ABS"))

        val result = DebugOverrides.apply(
            base,
            mapOf("telltales" to " , ", "telltales_off" to ""),
        )

        assertEquals(base, result)
    }

    @Test
    fun `문 잠김 하향등 브레이크를 덮어쓴다`() {
        val result = DebugOverrides.apply(
            CarState(),
            mapOf(
                "door_driver" to "1",
                "lock" to "0",
                "lowbeam" to "1",
                "brake" to "0",
            ),
        )

        assertEquals(
            CarState(
                driverDoorOpen = true,
                doorsLocked = false,
                lowBeamOn = true,
                brakePressed = false,
            ),
            result,
        )
    }

    @Test
    fun `모르는 키와 사건 키는 차 상태를 바꾸지 않는다`() {
        val base = CarState(
            gear = Gear.D,
            fuelPercent = 70,
            telltales = setOf("TPMS"),
        )

        val result = DebugOverrides.apply(
            base,
            mapOf(
                "unknown_key" to "1",
                "say" to "도착했어요",
                "arrive" to "집",
                "now" to "2026-11-19T18:40:00",
                "reset" to "1",
                "telltale_on" to "ABS",
            ),
        )

        assertEquals(base, result)
    }

    @Test
    fun `잘못된 숫자와 유한하지 않은 값은 무시한다`() {
        val base = CarState(
            fuelPercent = 70,
            fuelEconomyKmPerL = 10f,
            odometerKm = 100f,
        )

        val invalidOverrides = listOf(
            mapOf("fuel" to "문자", "economy" to "문자", "odo" to "문자"),
            mapOf("fuel" to "42.5", "economy" to "NaN", "odo" to "Infinity"),
            mapOf("fuel" to "999999999999999", "economy" to "1e100", "odo" to "-Infinity"),
            mapOf("fuel" to "-1", "economy" to "-0.1", "odo" to "-1"),
            mapOf("fuel" to "101", "economy" to "", "odo" to ""),
        )

        for (overrides in invalidOverrides) {
            assertEquals(base, DebugOverrides.apply(base, overrides))
        }
    }

    @Test
    fun `잘못된 기어와 참거짓 값은 무시한다`() {
        val base = CarState(
            gear = Gear.N,
            driverSeated = true,
            passengerSeated = false,
            driverDoorOpen = false,
            windowOpen = mapOf(WindowPos.FRONT_LEFT to true),
            brakePressed = true,
        )

        val result = DebugOverrides.apply(
            base,
            mapOf(
                "gear" to "X",
                "seat_driver" to "true",
                "seat_passenger" to "2",
                "door_driver" to "열림",
                "win_fl" to "-1",
                "brake" to "",
            ),
        )

        assertEquals(base, result)
    }

    @Test
    fun `연료와 거리의 경계값 영을 허용한다`() {
        val result = DebugOverrides.apply(
            CarState(
                fuelPercent = 50,
                fuelEconomyKmPerL = 10f,
                odometerKm = 100f,
            ),
            mapOf("fuel" to "0", "economy" to "0", "odo" to "0"),
        )

        assertEquals(
            CarState(
                fuelPercent = 0,
                fuelEconomyKmPerL = 0f,
                odometerKm = 0f,
            ),
            result,
        )
    }

    @Test
    fun `같은 입력에는 같은 결과를 내며 덮어쓰기 표를 바꾸지 않는다`() {
        val base = CarState(telltales = setOf("ABS"))
        val overrides = linkedMapOf(
            "gear" to "P",
            "fuel" to "25",
            "telltales" to "TPMS",
        )
        val originalOverrides = overrides.toMap()

        val first = DebugOverrides.apply(base, overrides)
        val second = DebugOverrides.apply(base, overrides)

        assertEquals(first, second)
        assertEquals(originalOverrides, overrides)
        assertEquals(CarState(telltales = setOf("ABS")), base)
    }
}
