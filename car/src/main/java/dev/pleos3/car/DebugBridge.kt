package dev.pleos3.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

object DebugBridge {
    const val ACTION = "dev.pleos3.DEBUG"

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableEvents =
        MutableSharedFlow<Map<String, String>>(extraBufferCapacity = 64)

    val events: SharedFlow<Map<String, String>> = mutableEvents.asSharedFlow()

    private val overrides = linkedMapOf<String, String>()
    private var installedReceiver: BroadcastReceiver? = null
    private var clockBaseMs: Long? = null
    private var clockBaseElapsedMs = 0L

    fun install(context: Context) {
        val app = context.applicationContext
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            return
        }

        synchronized(lock) {
            if (installedReceiver != null) return

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action != ACTION) return

                    val values = linkedMapOf<String, String>()
                    try {
                        val extras = intent.extras
                        if (extras != null) {
                            for (key in extras.keySet()) {
                                try {
                                    @Suppress("DEPRECATION")
                                    val value = extras.get(key)
                                    if (value is String) values[key] = value
                                } catch (error: Exception) {
                                    CarLog.i("디버그 값 읽기 실패: $key, ${error.message}")
                                }
                            }
                        }
                    } catch (error: Exception) {
                        CarLog.i("디버그 방송 읽기 실패: ${error.message}")
                        return
                    }

                    accept(values.toMap())
                }
            }

            try {
                ContextCompat.registerReceiver(
                    app,
                    receiver,
                    IntentFilter(ACTION),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                installedReceiver = receiver
            } catch (error: Exception) {
                CarLog.i("디버그 수신기 등록 실패: ${error.message}")
            }
        }
    }

    private fun accept(values: Map<String, String>) {
        synchronized(lock) {
            if (values["reset"] == "1") {
                overrides.clear()
                clockBaseMs = null
                clockBaseElapsedMs = 0L
            }

            values["now"]?.let { value ->
                try {
                    val parsed = LocalDateTime.parse(value)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli()
                    clockBaseMs = parsed
                    clockBaseElapsedMs = SystemClock.elapsedRealtime()
                } catch (error: Exception) {
                    CarLog.i("디버그 시각 무시: $value")
                }
            }

            for ((key, value) in values) {
                DebugOverrides.normalizeScalar(key, value)?.let {
                    overrides[key] = it
                }
            }

            val enabled = DebugOverrides.names(overrides["telltales"]).toMutableSet()
            val disabled =
                DebugOverrides.names(overrides["telltales_off"]).toMutableSet()

            val turnOn = DebugOverrides.names(values["telltales"]) +
                DebugOverrides.names(values["telltale_on"])
            val turnOff = DebugOverrides.names(values["telltales_off"]) +
                DebugOverrides.names(values["telltale_off"])

            for (name in turnOn) {
                disabled.remove(name)
                enabled.add(name)
            }
            for (name in turnOff) {
                enabled.remove(name)
                disabled.add(name)
            }

            if (enabled.isEmpty()) overrides.remove("telltales")
            else overrides["telltales"] = enabled.sorted().joinToString(",")

            if (disabled.isEmpty()) overrides.remove("telltales_off")
            else overrides["telltales_off"] = disabled.sorted().joinToString(",")
        }

        // 버퍼가 가득 차면 중단했다가 이어서 내보내며 사건을 버리지 않는다.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutableEvents.emit(values)
        }
    }

    fun nowMs(): Long = synchronized(lock) {
        val base = clockBaseMs
        if (base == null) {
            System.currentTimeMillis()
        } else {
            base + (SystemClock.elapsedRealtime() - clockBaseElapsedMs)
        }
    }

    fun apply(base: CarState): CarState = synchronized(lock) {
        DebugOverrides.apply(base, overrides)
    }
}

object DebugOverrides {
    private val booleanKeys = setOf(
        "seat_driver",
        "seat_passenger",
        "seat_rear",
        "door_driver",
        "lock",
        "win_fl",
        "win_fr",
        "win_rl",
        "win_rr",
        "lowbeam",
        "brake",
    )

    private val windowKeys = mapOf(
        "win_fl" to WindowPos.FRONT_LEFT,
        "win_fr" to WindowPos.FRONT_RIGHT,
        "win_rl" to WindowPos.REAR_LEFT,
        "win_rr" to WindowPos.REAR_RIGHT,
    )

    private val namePattern = Regex("[A-Z][A-Z0-9_]*")

    internal fun names(value: String?): Set<String> =
        value.orEmpty()
            .split(',')
            .map(String::trim)
            .filter { namePattern.matches(it) }
            .toSet()

    internal fun normalizeScalar(key: String, value: String): String? {
        val text = value.trim()
        return when {
            key in booleanKeys -> text.takeIf { it == "0" || it == "1" }
            key == "gear" -> text.takeIf { it in setOf("P", "R", "N", "D") }
            key == "fuel" -> text.toIntOrNull()
                ?.takeIf { it in 0..100 }
                ?.toString()
            key == "economy" || key == "odo" -> text.toFloatOrNull()
                ?.takeIf { it.isFinite() && it >= 0f }
                ?.toString()
            else -> null
        }
    }

    private fun boolean(value: String?): Boolean? = when (value?.trim()) {
        "0" -> false
        "1" -> true
        else -> null
    }

    fun apply(base: CarState, overrides: Map<String, String>): CarState {
        var result = base

        overrides["gear"]?.let { raw ->
            val gear = when (raw.trim()) {
                "P" -> Gear.P
                "R" -> Gear.R
                "N" -> Gear.N
                "D" -> Gear.D
                else -> null
            }
            if (gear != null) result = result.copy(gear = gear)
        }

        boolean(overrides["seat_driver"])?.let {
            result = result.copy(driverSeated = it)
        }
        boolean(overrides["seat_passenger"])?.let {
            result = result.copy(passengerSeated = it)
        }
        boolean(overrides["seat_rear"])?.let {
            result = result.copy(rearSeated = it)
        }
        boolean(overrides["door_driver"])?.let {
            result = result.copy(driverDoorOpen = it)
        }
        boolean(overrides["lock"])?.let {
            result = result.copy(doorsLocked = it)
        }
        boolean(overrides["lowbeam"])?.let {
            result = result.copy(lowBeamOn = it)
        }
        boolean(overrides["brake"])?.let {
            result = result.copy(brakePressed = it)
        }

        val windows = result.windowOpen.toMutableMap()
        for ((key, position) in windowKeys) {
            boolean(overrides[key])?.let { windows[position] = it }
        }
        result = result.copy(windowOpen = windows.toMap())

        overrides["fuel"]?.let { raw ->
            normalizeScalar("fuel", raw)?.toIntOrNull()?.let {
                result = result.copy(fuelPercent = it)
            }
        }
        overrides["economy"]?.let { raw ->
            normalizeScalar("economy", raw)?.toFloatOrNull()?.let {
                result = result.copy(fuelEconomyKmPerL = it)
            }
        }
        overrides["odo"]?.let { raw ->
            normalizeScalar("odo", raw)?.toFloatOrNull()?.let {
                result = result.copy(odometerKm = it)
            }
        }

        result = result.copy(
            telltales = (
                result.telltales + names(overrides["telltales"])
            ) - names(overrides["telltales_off"]),
        )
        return result
    }
}
