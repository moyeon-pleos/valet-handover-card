package dev.pleos3.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

interface CarSignals {
    val state: StateFlow<CarState>
    fun start()
    fun stop()
    suspend fun refresh(): CarState
}

class FakeCarSignals(initial: CarState = CarState()) : CarSignals {
    private val mutableState = MutableStateFlow(initial)
    override val state: StateFlow<CarState> = mutableState.asStateFlow()

    fun update(transform: (CarState) -> CarState) {
        mutableState.update(transform)
    }

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun refresh(): CarState = state.value
}
