package dev.pleos3.valet.core

import dev.pleos3.car.WindowPos

object Names {
    fun window(pos: WindowPos): String = when (pos) {
        WindowPos.FRONT_LEFT -> "운전석 창"
        WindowPos.FRONT_RIGHT -> "조수석 창"
        WindowPos.REAR_LEFT -> "왼쪽 뒷창"
        WindowPos.REAR_RIGHT -> "오른쪽 뒷창"
    }

    fun telltale(name: String): String = when (name) {
        "TPMS" -> "타이어 공기압"
        "ABS" -> "ABS"
        "AIRBAG" -> "에어백"
        "LOW_VOLT_BATTERY" -> "배터리"
        "MASTER_WARNING" -> "통합 경고"
        "SEAT_BELT" -> "안전벨트"
        "DOOR_OPEN" -> "문 열림"
        else -> name
    }
}
