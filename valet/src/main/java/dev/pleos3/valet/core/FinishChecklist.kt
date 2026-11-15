package dev.pleos3.valet.core

import dev.pleos3.car.CarState
import dev.pleos3.car.WindowPos

object FinishChecklist {
    fun lines(state: CarState): List<String> = buildList {
        if (state.lowBeamOn == true) {
            add("전조등이 켜져 있습니다")
        }
        for (position in WindowPos.entries) {
            if (state.windowOpen[position] == true) {
                add("${Names.window(position)}이 열려 있습니다")
            }
        }
    }
}
