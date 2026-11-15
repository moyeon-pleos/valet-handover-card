package dev.pleos3.valet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

// Pleos 화면(2560×1440)은 밀도가 낮아(1dp ≈ 1px) 글과 단추가 작게 나온다.
// 차 안에서 읽기 좋게 앱 화면 전체를 같은 비율로 키운다.
private const val CAR_SCALE = 1.6f

@Composable
fun CarScale(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(base.density * CAR_SCALE, base.fontScale),
        content = content,
    )
}
