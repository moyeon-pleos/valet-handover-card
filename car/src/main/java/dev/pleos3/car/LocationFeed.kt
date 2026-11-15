package dev.pleos3.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

interface LocationFeed {
    val fixes: SharedFlow<LocFix>
    fun start()
    fun stop()
}

class PleosLocationFeed(context: Context) : LocationFeed {
    @Volatile
    private var debugLocationActive = false

    private class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var location: FusedLocation? = null
        var listener: FusedLocationListener? = null
        var registrationAttempted = false
    }

    private val app = context.applicationContext
    private val mutableFixes = MutableSharedFlow<LocFix>(extraBufferCapacity = 64)
    override val fixes: SharedFlow<LocFix> = mutableFixes.asSharedFlow()

    @Volatile
    private var session: Session? = null

    @Synchronized
    override fun start() {
        if (session != null) return

        val current = Session()
        session = current
        current.scope.launch {
            if (session !== current) return@launch

            DebugBridge.install(app)
            current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                DebugBridge.events.collect { event ->
                    if (session !== current) return@collect
                    if (event["reset"] == "1") debugLocationActive = false
                    val raw = event["loc"] ?: return@collect
                    val fix = parseDebugLocation(raw)
                    if (fix == null) {
                        CarLog.i("디버그 위치 무시: $raw")
                    } else {
                        // 시험용 위치를 쓰기 시작하면 실제 위치는 받지 않는다 — 두 흐름은 시계가 달라
                        // 섞이면 속도 셈이 깨진다(2026-11-11 에뮬레이터에서 확인).
                        debugLocationActive = true
                        mutableFixes.emit(fix)
                    }
                }
            }

            try {
                val location = FusedLocation(app)
                current.location = location
                location.initialize()

                if (!location.isSupported()) {
                    CarLog.i("위치 기능을 지원하지 않습니다")
                    return@launch
                }

                val listener = object : FusedLocationListener {
                    override fun onChanged(location: LocationData?) {
                        val data = location ?: return
                        if (debugLocationActive) return
                        val fix = LocFix(
                            lat = data.latitude,
                            lon = data.longitude,
                            confidence = data.confidence,
                            timeMs = data.timestampMillis,
                        )
                        current.scope.launch {
                            if (session === current) mutableFixes.emit(fix)
                        }
                    }

                    override fun onError(error: Exception) {
                        if (session === current) {
                            CarLog.i("위치 수신 실패: ${error.message}")
                        }
                    }
                }
                current.listener = listener

                try {
                    current.registrationAttempted = true
                    location.registerFusedLocationCallback(listener)
                } catch (error: Exception) {
                    CarLog.i("위치 리스너 등록 실패: ${error.message}")
                }

                try {
                    location.getLastKnownLocation(listener)
                } catch (error: Exception) {
                    CarLog.i("마지막 위치 조회 실패: ${error.message}")
                }
            } catch (error: Exception) {
                CarLog.i("위치 초기화 실패: ${error.message}")
            }
        }
    }

    @Synchronized
    override fun stop() {
        val current = session ?: return
        session = null
        debugLocationActive = false

        current.scope.launch {
            try {
                val location = current.location
                val listener = current.listener

                if (
                    location != null && listener != null &&
                    current.registrationAttempted
                ) {
                    try {
                        location.unregisterFusedLocationCallback(listener)
                    } catch (error: Exception) {
                        CarLog.i("위치 리스너 해제 실패: ${error.message}")
                    }
                }

                try {
                    location?.release()
                } catch (error: Exception) {
                    CarLog.i("위치 해제 실패: ${error.message}")
                }
                current.location = null
            } finally {
                current.scope.cancel()
            }
        }
    }

    private fun parseDebugLocation(raw: String): LocFix? {
        val parts = raw.split(',').map(String::trim)
        if (parts.size != 3) return null

        val lat = parts[0].toDoubleOrNull() ?: return null
        val lon = parts[1].toDoubleOrNull() ?: return null
        val confidence = parts[2].toFloatOrNull() ?: return null

        if (
            !lat.isFinite() || !lon.isFinite() || !confidence.isFinite() ||
            lat !in -90.0..90.0 || lon !in -180.0..180.0 ||
            confidence < 0f
        ) {
            return null
        }

        return LocFix(
            lat = lat,
            lon = lon,
            confidence = confidence,
            timeMs = DebugBridge.nowMs(),
        )
    }
}
