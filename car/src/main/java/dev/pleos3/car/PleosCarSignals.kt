package dev.pleos3.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Brake
import ai.pleos.playground.vehicle.api.Door
import ai.pleos.playground.vehicle.api.Fuel
import ai.pleos.playground.vehicle.api.Gear as SdkGear
import ai.pleos.playground.vehicle.api.Light
import ai.pleos.playground.vehicle.api.Odometer
import ai.pleos.playground.vehicle.api.Safety
import ai.pleos.playground.vehicle.api.Seat
import ai.pleos.playground.vehicle.api.Window
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.DoorLockStateListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.LightStateListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.VehicleListener
import ai.pleos.playground.vehicle.listener.WindowPositionListener
import android.content.Context
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PleosCarSignals(context: Context) : CarSignals {
    private class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var vehicle: Vehicle? = null
        val cleanups = mutableListOf<Pair<String, () -> Unit>>()
        val reads = mutableListOf<suspend () -> Unit>()
        val rearSeats = linkedMapOf(
            SeatValue.ROW_2_LEFT to null as Boolean?,
            SeatValue.ROW_2_CENTER to null as Boolean?,
            SeatValue.ROW_2_RIGHT to null as Boolean?,
        )
    }

    private data class Answer(val value: Any?, val error: Exception?)

    private val app = context.applicationContext
    private val stateLock = Any()
    private var base = CarState()
    private val mutableState = MutableStateFlow(DebugBridge.apply(base))

    override val state: StateFlow<CarState> = mutableState.asStateFlow()

    @Volatile
    private var session: Session? = null

    @Synchronized
    override fun start() {
        if (session != null) return

        val current = Session()
        session = current
        synchronized(stateLock) {
            base = CarState()
            mutableState.value = DebugBridge.apply(base)
        }

        current.scope.launch {
            if (session !== current) return@launch

            DebugBridge.install(app)
            current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                DebugBridge.events.collect {
                    synchronized(stateLock) {
                        if (session === current) {
                            mutableState.value = DebugBridge.apply(base)
                        }
                    }
                }
            }

            try {
                val vehicle = Vehicle(app)
                current.vehicle = vehicle
                vehicle.initialize()
                bindSignals(current, vehicle)
            } catch (error: Exception) {
                CarLog.i("차량 초기화 실패: ${describe(error)}")
            }

            poll(current)
        }
    }

    @Synchronized
    override fun stop() {
        val current = session ?: return
        session = null

        current.scope.launch {
            try {
                for ((name, cleanup) in current.cleanups.asReversed()) {
                    try {
                        cleanup()
                    } catch (error: Exception) {
                        CarLog.i("$name 해제 실패: ${describe(error)}")
                    }
                }
                current.cleanups.clear()

                try {
                    current.vehicle?.release()
                } catch (error: Exception) {
                    CarLog.i("차량 해제 실패: ${describe(error)}")
                }
                current.vehicle = null
            } finally {
                current.scope.cancel()
            }
        }
    }

    override suspend fun refresh(): CarState =
        withContext(Dispatchers.Main.immediate) {
            start()
            val current = session
            if (current != null) {
                current.scope.async { poll(current) }.await()
            }

            synchronized(stateLock) {
                mutableState.value = DebugBridge.apply(base)
                mutableState.value
            }
        }

    private suspend fun poll(current: Session) {
        if (session !== current) return
        coroutineScope {
            current.reads.toList().map { read ->
                async { read() }
            }.awaitAll()
        }
    }

    /*
     * 제공 자료에 Vehicle의 개별 접근자 이름이 빠져 있다.
     * 공개된 반환 인터페이스 형으로 실제 접근자를 찾으며 이름을 추측하지 않는다.
     */
    private fun <T : Any> api(vehicle: Vehicle, type: Class<T>): T {
        val methods = vehicle.javaClass.methods.filter {
            it.parameterCount == 0 && type.isAssignableFrom(it.returnType)
        }
        val method = methods.singleOrNull()
            ?: throw IllegalStateException("${type.simpleName} 접근자를 확인할 수 없습니다")
        return type.cast(method.invoke(vehicle))
    }

    /*
     * javap 자료에는 리스너의 Kotlin 널 허용 정보가 없다.
     * 제공된 콜백 이름만 처리하는 프록시로 박싱 값과 null을 그대로 받는다.
     */
    private fun <L : VehicleListener> makeListener(
        type: Class<L>,
        callback: String,
        onValue: (Any?) -> Unit,
        onFailure: (Exception) -> Unit,
    ): L {
        val proxy = Proxy.newProxyInstance(
            type.classLoader,
            arrayOf<Class<*>>(type),
        ) { instance, method, arguments ->
            when (method.name) {
                "equals" -> instance === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> type.simpleName
                "onFailed" -> {
                    val error = arguments?.firstOrNull() as? Exception
                        ?: IllegalStateException("차량 오류 정보가 없습니다")
                    onFailure(error)
                    null
                }
                callback -> {
                    onValue(arguments?.firstOrNull())
                    null
                }
                else -> null
            }
        }
        return type.cast(proxy)
    }

    private fun <L : VehicleListener> bind(
        current: Session,
        name: String,
        listenerType: Class<L>,
        callback: String,
        register: (L) -> Unit,
        unregister: (L) -> Unit,
        read: ((Any?) -> Unit, (Exception) -> Unit) -> Unit,
        update: (CarState, Any?) -> CarState,
    ) {
        val revision = AtomicLong()

        fun commit(value: Any?, expectedRevision: Long) {
            synchronized(stateLock) {
                if (session !== current || revision.get() != expectedRevision) return

                base = try {
                    update(base, value)
                } catch (error: Exception) {
                    CarLog.i("$name 값 해석 실패: ${describe(error)}")
                    update(base, null)
                }
                mutableState.value = DebugBridge.apply(base)
            }
        }

        fun failed(operation: String, error: Exception) {
            CarLog.i("$name $operation 실패: ${describe(error)}")
            val ticket = revision.incrementAndGet()
            commit(null, ticket)
        }

        current.reads += {
            val ticket = revision.incrementAndGet()
            val answer = withTimeoutOrNull(1_500L) {
                suspendCancellableCoroutine<Answer> { continuation ->
                    val delivered = AtomicBoolean()

                    fun finish(value: Any?, error: Exception?) {
                        if (
                            delivered.compareAndSet(false, true) &&
                            continuation.isActive
                        ) {
                            continuation.resume(Answer(value, error))
                        }
                    }

                    try {
                        read(
                            { value -> finish(value, null) },
                            { error -> finish(null, error) },
                        )
                    } catch (error: Exception) {
                        finish(null, error)
                    }
                }
            }

            when {
                answer == null -> {
                    // 응답이 늦을 뿐인 값을 비우면 기어가 «모름»이 되어 화면이 «운행 중»으로 뒤집힌다 → 기존 값을 둔다.
                    CarLog.i("$name 조회 실패: 1500밀리초 동안 응답이 없습니다(기존 값 유지)")
                }
                answer.error != null -> {
                    CarLog.i("$name 조회 실패: ${describe(answer.error)}")
                    commit(null, ticket)
                }
                else -> commit(answer.value, ticket)
            }
        }

        try {
            val listener = makeListener(
                listenerType,
                callback,
                onValue = { value ->
                    val ticket = revision.incrementAndGet()
                    commit(value, ticket)
                },
                onFailure = { error -> failed("수신", error) },
            )

            // 등록 도중 예외가 나도 부분 등록을 해제할 수 있게 먼저 보관한다.
            current.cleanups += name to { unregister(listener) }
            register(listener)
        } catch (error: Exception) {
            failed("등록", error)
        }
    }

    private fun bindSignals(current: Session, vehicle: Vehicle) {
        bind(
            current, "기어", GearSelectionListener::class.java, "onSelected",
            register = { api(vehicle, SdkGear::class.java).registerGearSelection(it) },
            unregister = { api(vehicle, SdkGear::class.java).unregisterGearSelection(it) },
            read = { ok, error ->
                api(vehicle, SdkGear::class.java)
                    .getCurrentGearSelectionState(ok, error)
            },
            update = { state, value ->
                state.copy(gear = toGear(value as? VehicleGear))
            },
        )

        val seatValues = listOf(
            SeatValue.ROW_1_LEFT,
            SeatValue.ROW_1_RIGHT,
            SeatValue.ROW_2_LEFT,
            SeatValue.ROW_2_CENTER,
            SeatValue.ROW_2_RIGHT,
        )
        for (seatValue in seatValues) {
            val area = SeatArea(seatValue)
            bind(
                current, "착석 ${seatValue.name}",
                SeatOccupancyListener::class.java, "onSeatOccupancyUpdated",
                register = {
                    api(vehicle, Seat::class.java).registerSeatOccupancy(area, it)
                },
                unregister = {
                    api(vehicle, Seat::class.java).unregisterSeatOccupancy(area, it)
                },
                read = { ok, error ->
                    api(vehicle, Seat::class.java)
                        .getCurrentSeatOccupancy(area, ok, error)
                },
                update = { state, value ->
                    val seated = value as? Boolean
                    when (seatValue) {
                        SeatValue.ROW_1_LEFT -> state.copy(driverSeated = seated)
                        SeatValue.ROW_1_RIGHT -> state.copy(passengerSeated = seated)
                        else -> {
                            current.rearSeats[seatValue] = seated
                            val seats = current.rearSeats.values
                            state.copy(
                                rearSeated = when {
                                    seats.any { it == true } -> true
                                    seats.all { it == false } -> false
                                    else -> null
                                },
                            )
                        }
                    }
                },
            )
        }

        val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)
        bind(
            current, "운전석 문", DoorPositionListener::class.java,
            "onPositionUpdated",
            register = {
                api(vehicle, Door::class.java).registerDoorPosition(driverDoor, it)
            },
            unregister = {
                api(vehicle, Door::class.java).unregisterDoorPosition(driverDoor, it)
            },
            read = { ok, error ->
                api(vehicle, Door::class.java)
                    .getCurrentDoorPosition(driverDoor, ok, error)
            },
            update = { state, value ->
                state.copy(driverDoorOpen = (value as? Number)?.toInt()?.let { it > 0 })
            },
        )

        bind(
            current, "운전석 문 잠김", DoorLockStateListener::class.java,
            "onDoorLockStateUpdated",
            register = {
                api(vehicle, Door::class.java).registerDoorLockState(driverDoor, it)
            },
            unregister = {
                api(vehicle, Door::class.java).unregisterDoorLockState(driverDoor, it)
            },
            read = { ok, error ->
                api(vehicle, Door::class.java)
                    .getDoorLockState(driverDoor, ok, error)
            },
            update = { state, value -> state.copy(doorsLocked = value as? Boolean) },
        )

        val windows = mapOf(
            WindowValue.ROW_1_LEFT to WindowPos.FRONT_LEFT,
            WindowValue.ROW_1_RIGHT to WindowPos.FRONT_RIGHT,
            WindowValue.ROW_2_LEFT to WindowPos.REAR_LEFT,
            WindowValue.ROW_2_RIGHT to WindowPos.REAR_RIGHT,
        )
        for ((windowValue, position) in windows) {
            val area = WindowArea(windowValue)
            bind(
                current, "창문 ${position.name}",
                WindowPositionListener::class.java, "onWindowPositionUpdated",
                register = {
                    api(vehicle, Window::class.java).registerWindowPosition(area, it)
                },
                unregister = {
                    api(vehicle, Window::class.java).unregisterWindowPosition(area, it)
                },
                read = { ok, error ->
                    api(vehicle, Window::class.java)
                        .getCurrentWindowPosition(area, ok, error)
                },
                update = { state, value ->
                    val open = (value as? Number)?.toInt()?.let { it > 0 }
                    val next = state.windowOpen.toMutableMap()
                    if (open == null) next.remove(position)
                    else next[position] = open
                    state.copy(windowOpen = next.toMap())
                },
            )
        }

        bind(
            current, "하향등", LightStateListener::class.java, "onLightStateUpdated",
            register = { api(vehicle, Light::class.java).registerLowBeamLightState(it) },
            unregister = {
                api(vehicle, Light::class.java).unregisterLowBeamLightState(it)
            },
            read = { ok, error ->
                api(vehicle, Light::class.java).getCurrentLowBeamLightState(ok, error)
            },
            update = { state, value ->
                state.copy(lowBeamOn = (value as? LightState)?.let { it == LightState.ON })
            },
        )

        bind(
            current, "연료", FuelPercentListener::class.java, "onFuelPercentUpdated",
            register = { api(vehicle, Fuel::class.java).registerFuelPercent(it) },
            unregister = { api(vehicle, Fuel::class.java).unregisterFuelPercent(it) },
            read = { ok, error ->
                api(vehicle, Fuel::class.java).getFuelPercent(ok, error)
            },
            update = { state, value ->
                state.copy(fuelPercent = (value as? Number)?.toInt())
            },
        )

        bind(
            current, "현재 연비", FuelEconomyListener::class.java,
            "onFuelEconomyUpdated",
            register = {
                api(vehicle, Fuel::class.java).registerCurrentFuelEconomy(it)
            },
            unregister = {
                api(vehicle, Fuel::class.java).unregisterCurrentFuelEconomy(it)
            },
            read = { ok, error ->
                api(vehicle, Fuel::class.java).getCurrentFuelEconomy(ok, error)
            },
            update = { state, value ->
                state.copy(fuelEconomyKmPerL = (value as? Number)?.toFloat())
            },
        )

        bind(
            current, "총 주행거리", DistanceDrivenListener::class.java,
            "onDistanceDrivenUpdated",
            register = { api(vehicle, Odometer::class.java).registerDistanceDriven(it) },
            unregister = {
                api(vehicle, Odometer::class.java).unregisterDistanceDriven(it)
            },
            read = { ok, error ->
                api(vehicle, Odometer::class.java).getCurrentDistanceDriven(ok, error)
            },
            update = { state, value ->
                state.copy(odometerKm = (value as? Number)?.toFloat())
            },
        )

        bind(
            current, "브레이크 페달", BrakePedalStatusListener::class.java,
            "onBrakePedalStatusUpdated",
            register = {
                api(vehicle, Brake::class.java).registerBrakePedalStatus(it)
            },
            unregister = {
                api(vehicle, Brake::class.java).unregisterBrakePedalStatus(it)
            },
            read = { ok, error ->
                api(vehicle, Brake::class.java).getCurrentBrakePedalStatus(ok, error)
            },
            update = { state, value ->
                state.copy(brakePressed = value as? Boolean)
            },
        )

        bind(
            current, "경고등", TelltaleInfoListener::class.java,
            "onTelltaleInfoUpdated",
            register = { api(vehicle, Safety::class.java).registerTelltales(it) },
            unregister = { api(vehicle, Safety::class.java).unregisterTelltales(it) },
            read = { ok, error ->
                api(vehicle, Safety::class.java).getTelltalesInfo(ok, error)
            },
            update = { state, value ->
                state.copy(telltales = activeTelltales(value as? Telltales))
            },
        )
    }

    private fun toGear(value: VehicleGear?): Gear = when (value) {
        VehicleGear.GEAR_PARK -> Gear.P
        VehicleGear.GEAR_REVERSE -> Gear.R
        VehicleGear.GEAR_NEUTRAL -> Gear.N
        VehicleGear.GEAR_DRIVE,
        VehicleGear.GEAR_1,
        VehicleGear.GEAR_2,
        VehicleGear.GEAR_3,
        VehicleGear.GEAR_4,
        VehicleGear.GEAR_5,
        VehicleGear.GEAR_6,
        VehicleGear.GEAR_7,
        VehicleGear.GEAR_8,
        VehicleGear.GEAR_9 -> Gear.D
        else -> Gear.UNKNOWN
    }

    private fun activeTelltales(value: Telltales?): Set<String> {
        if (value == null) return emptySet()

        return buildSet {
            for (data in value.telltales) {
                // TelltaleData 의 실제 접근자(getType·getState). 상태가 OFF 가 아니면(ON·BLINK) 켜진 것으로 본다.
                if (data.state != TelltaleState.OFF) {
                    add(data.type.name)
                }
            }
        }
    }

    private fun describe(error: Exception): String {
        val actual = if (error is InvocationTargetException) {
            error.targetException
        } else {
            error
        }
        return "${actual.javaClass.simpleName}: ${actual.message.orEmpty()}"
    }
}
