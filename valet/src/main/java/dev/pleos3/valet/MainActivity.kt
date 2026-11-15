package dev.pleos3.valet

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.pleos3.car.DebugBridge
import dev.pleos3.car.PleosCarSignals
import dev.pleos3.car.PleosLocationFeed
import dev.pleos3.car.PleosNaviGate
import dev.pleos3.car.PleosSpeechInput
import dev.pleos3.car.PleosVoice
import dev.pleos3.valet.ui.CarScale
import dev.pleos3.valet.ui.ValetApp

class MainActivity : ComponentActivity() {
    private lateinit var signals: PleosCarSignals
    private lateinit var voice: PleosVoice
    private lateinit var navi: PleosNaviGate
    private lateinit var speech: PleosSpeechInput
    private lateinit var location: PleosLocationFeed
    private lateinit var controller: ValetController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        DebugBridge.install(applicationContext)
        signals = PleosCarSignals(applicationContext)
        voice = PleosVoice(applicationContext)
        navi = PleosNaviGate(applicationContext)
        speech = PleosSpeechInput(applicationContext)
        location = PleosLocationFeed(applicationContext)

        controller = ValetController(
            signals = signals,
            voice = voice,
            navi = navi,
            speech = speech,
            location = location,
        )

        // SDK가 첫 값을 내기 전에 화면과 디버그 입력의 구독을 준비한다.
        controller.start()
        signals.start()
        navi.start()
        location.start()

        setContent {
            CarScale {
                val state by controller.state.collectAsState()
                ValetApp(
                    state = state,
                    onTap = controller::tap,
                    onMemo = controller::submitText,
                )
            }
        }
    }

    override fun onDestroy() {
        if (::controller.isInitialized) controller.stop()
        if (::speech.isInitialized) speech.stop()
        if (::location.isInitialized) location.stop()
        if (::navi.isInitialized) navi.stop()
        if (::signals.isInitialized) signals.stop()
        if (::voice.isInitialized) voice.stop()
        super.onDestroy()
    }
}
