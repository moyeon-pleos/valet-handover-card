package dev.pleos3.car

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import java.lang.reflect.InvocationTargetException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

interface NaviGate {
    val arrivals: SharedFlow<String>
    val remainMinutes: StateFlow<Int?>
    fun start()
    fun stop()
    fun routeTo(place: NaviPlace)
    fun homeBookmark(onResult: (NaviPlace?) -> Unit)
}

class PleosNaviGate(context: Context) : NaviGate {
    private class PendingBookmark(val callback: (NaviPlace?) -> Unit) {
        var timeout: Job? = null
    }

    private class Session {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var helper: NaviHelper? = null
        var listener: NaviHelperEventListener? = null
        var listenerAdded = false
        var ready = false
        var failed = false
        var sdkRemain: Int? = null
        var debugRemain: Int? = null
        var hasDebugRemain = false
        var bookmarkRequested = false
        val routes = mutableListOf<NaviPlace>()
        val bookmarks = mutableListOf<PendingBookmark>()
    }

    private val app = context.applicationContext
    private val mutableArrivals = MutableSharedFlow<String>(extraBufferCapacity = 64)
    private val mutableRemainMinutes = MutableStateFlow<Int?>(null)

    override val arrivals: SharedFlow<String> = mutableArrivals.asSharedFlow()
    override val remainMinutes: StateFlow<Int?> = mutableRemainMinutes.asStateFlow()

    @Volatile
    private var session: Session? = null

    @Synchronized
    override fun start() {
        if (session != null) return

        val current = Session()
        session = current

        current.scope.launch {
            if (session !== current) return@launch
            mutableRemainMinutes.value = null
            DebugBridge.install(app)

            current.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                DebugBridge.events.collect { event ->
                    if (session !== current) return@collect

                    if (event["reset"] == "1") {
                        current.hasDebugRemain = false
                        current.debugRemain = null
                    }

                    event["remain_min"]?.trim()?.toIntOrNull()
                        ?.takeIf { it >= 0 }
                        ?.let {
                            current.debugRemain = it
                            current.hasDebugRemain = true
                        }
                    publishRemain(current)

                    event["arrive"]?.let { mutableArrivals.emit(it) }
                }
            }

            try {
                val helper = NaviHelper(app)
                current.helper = helper
                val listener = createListener(current)
                current.listener = listener

                helper.initialize(
                    {
                        current.scope.launch {
                            if (session !== current) return@launch

                            try {
                                // 초기화에 성공한 뒤 리스너를 등록한다.
                                current.listenerAdded = true
                                helper.addListener(listener)
                                current.ready = true
                                current.failed = false

                                requestDestination(current)
                                val waiting = current.routes.toList()
                                current.routes.clear()
                                waiting.forEach { requestRoute(current, it) }
                                requestBookmarks(current)
                            } catch (error: Exception) {
                                fail(current, error)
                            }
                        }
                    },
                    { error ->
                        current.scope.launch {
                            if (session === current) fail(current, error)
                        }
                    },
                )
            } catch (error: Exception) {
                fail(current, error)
            }
        }
    }

    @Synchronized
    override fun stop() {
        val current = session ?: return
        session = null

        current.scope.launch {
            try {
                completeBookmarks(current, null)
                current.routes.clear()
                current.ready = false

                val helper = current.helper
                val listener = current.listener
                if (helper != null && listener != null && current.listenerAdded) {
                    try {
                        helper.removeListener(listener)
                    } catch (error: Exception) {
                        CarLog.i("내비 리스너 해제 실패: ${describe(error)}")
                    }
                }
                try {
                    helper?.release()
                } catch (error: Exception) {
                    CarLog.i("내비 해제 실패: ${describe(error)}")
                }
                current.helper = null
            } finally {
                current.scope.cancel()
            }
        }
    }

    override fun routeTo(place: NaviPlace) {
        if (
            !place.lat.isFinite() || !place.lon.isFinite() ||
            place.lat !in -90.0..90.0 || place.lon !in -180.0..180.0
        ) {
            CarLog.i("경로 요청 무시: 좌표가 올바르지 않습니다")
            return
        }

        start()
        val current = session ?: return
        current.scope.launch {
            if (session !== current) return@launch
            when {
                current.ready -> requestRoute(current, place)
                current.failed -> CarLog.i("경로 요청 실패: 내비가 준비되지 않았습니다")
                else -> current.routes.add(place)
            }
        }
    }

    override fun homeBookmark(onResult: (NaviPlace?) -> Unit) {
        start()
        val current = session
        if (current == null) {
            deliver(onResult, null)
            return
        }

        current.scope.launch {
            if (session !== current || current.failed) {
                deliver(onResult, null)
                return@launch
            }

            val pending = PendingBookmark(onResult)
            current.bookmarks.add(pending)
            pending.timeout = current.scope.launch {
                delay(5_000L)
                if (current.bookmarks.remove(pending)) {
                    CarLog.i("집 즐겨찾기 조회 실패: 응답이 없습니다")
                    deliver(pending.callback, null)
                    if (current.bookmarks.isEmpty()) {
                        current.bookmarkRequested = false
                    }
                }
            }
            requestBookmarks(current)
        }
    }

    private fun createListener(current: Session) =
        object : NaviHelperEventListener {
            override fun onDestinationArrived(info: DestinationArrivedInfo) {
                current.scope.launch {
                    if (session !== current) return@launch
                    current.sdkRemain = null
                    publishRemain(current)
                    mutableArrivals.emit(info.name)
                }
            }

            override fun onDestinationInfo(info: DestinationInfo) {
                current.scope.launch {
                    if (session !== current) return@launch

                    val absent = info.name.isBlank() &&
                        info.address.isBlank() &&
                        info.latitude == 0.0 &&
                        info.longitude == 0.0

                    current.sdkRemain = if (absent || info.remainTime < 0) {
                        null
                    } else {
                        info.remainTime / 60
                    }
                    publishRemain(current)
                }
            }

            override fun onDestinationChanged() {
                current.scope.launch {
                    if (session === current) requestDestination(current)
                }
            }

            override fun onRouteEnded() {
                clearDestination(current)
            }

            override fun onRouteCancelled() {
                clearDestination(current)
            }

            override fun onBookmarkInfo(bookmarks: List<BookmarkInfo>) {
                current.scope.launch {
                    if (session !== current) return@launch
                    val home = bookmarks.firstOrNull {
                        it.category == BookmarkType.HOME
                    }
                    val place = home?.let {
                        NaviPlace(
                            name = it.name,
                            address = it.address,
                            lat = it.latitude,
                            lon = it.longitude,
                        )
                    }
                    completeBookmarks(current, place)
                }
            }

            override fun onError(errorCode: NaviErrorCode) {
                current.scope.launch {
                    if (session === current) {
                        CarLog.i("내비 오류: $errorCode")
                        completeBookmarks(current, null)
                    }
                }
            }
        }

    private fun clearDestination(current: Session) {
        current.scope.launch {
            if (session !== current) return@launch
            current.sdkRemain = null
            publishRemain(current)
        }
    }

    private fun publishRemain(current: Session) {
        if (session !== current) return
        mutableRemainMinutes.value = if (current.hasDebugRemain) {
            current.debugRemain
        } else {
            current.sdkRemain
        }
    }

    private fun requestDestination(current: Session) {
        if (!current.ready) return
        try {
            current.helper?.getDestinationInfo()
        } catch (error: Exception) {
            current.sdkRemain = null
            publishRemain(current)
            CarLog.i("목적지 조회 실패: ${describe(error)}")
        }
    }

    private fun requestRoute(current: Session, place: NaviPlace) {
        try {
            val helper = current.helper
                ?: throw IllegalStateException("내비 연결이 없습니다")
            helper.requestRoute(createRouteInfo(place))
        } catch (error: Exception) {
            CarLog.i("경로 요청 실패: ${describe(error)}")
        }
    }

    /*
     * RouteInfo 생성자 자료가 중간에서 잘려 기본 인자 목록을 확인할 수 없다.
     * 공개된 속성 순서를 검증한 뒤 실제 Kotlin 기본 인자 생성자를 사용한다.
     * 선택 값은 SDK 기본값을 사용하고 좌표·이름·주소만 명시한다.
     */
    // 리플렉션으로 기본 인자 생성자를 부르면 poiId 가 null 이라 죽는다(2026-11-11 에뮬레이터 로그).
    // 앞선 시험 앱에서 동작이 확인된 이름 붙인 인자 방식으로 만든다. 장소 ID 가 없는 좌표는 빈 글자로 둔다.
    private fun createRouteInfo(place: NaviPlace): RouteInfo =
        RouteInfo(
            latitude = place.lat,
            longitude = place.lon,
            displayLongitude = null,
            displayLatitude = null,
            poiName = place.name,
            poiId = "",
            poiSubId = "0",
            address = place.address,
        )

    private fun requestBookmarks(current: Session) {
        if (
            !current.ready || current.bookmarkRequested ||
            current.bookmarks.isEmpty()
        ) {
            return
        }

        current.bookmarkRequested = true
        try {
            val helper = current.helper
                ?: throw IllegalStateException("내비 연결이 없습니다")
            helper.getBookmarkInfo()
        } catch (error: Exception) {
            CarLog.i("집 즐겨찾기 조회 실패: ${describe(error)}")
            completeBookmarks(current, null)
        }
    }

    private fun completeBookmarks(current: Session, place: NaviPlace?) {
        current.bookmarkRequested = false
        val waiting = current.bookmarks.toList()
        current.bookmarks.clear()
        for (pending in waiting) {
            pending.timeout?.cancel()
            deliver(pending.callback, place)
        }
    }

    private fun deliver(callback: (NaviPlace?) -> Unit, place: NaviPlace?) {
        try {
            callback(place)
        } catch (error: Exception) {
            CarLog.i("집 즐겨찾기 결과 처리 실패: ${describe(error)}")
        }
    }

    private fun fail(current: Session, error: Throwable) {
        if (session !== current) return
        current.ready = false
        current.failed = true
        current.sdkRemain = null
        current.routes.clear()
        publishRemain(current)
        completeBookmarks(current, null)
        CarLog.i("내비 초기화 실패: ${describe(error)}")
    }

    private fun describe(error: Throwable): String {
        val actual = if (error is InvocationTargetException) {
            error.targetException
        } else {
            error
        }
        return "${actual.javaClass.simpleName}: ${actual.message.orEmpty()}"
    }
}
