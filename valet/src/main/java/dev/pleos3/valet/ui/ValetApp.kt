package dev.pleos3.valet.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pleos3.car.CarState
import dev.pleos3.car.Dictation
import dev.pleos3.car.DictationState
import dev.pleos3.car.WindowPos
import dev.pleos3.valet.GuideChoice
import dev.pleos3.valet.ValetDictationTarget
import dev.pleos3.valet.ValetExamples
import dev.pleos3.valet.ValetScreen
import dev.pleos3.valet.ValetUiState
import dev.pleos3.valet.core.CardKind
import dev.pleos3.valet.core.HandoverCard
import dev.pleos3.valet.core.Names
import dev.pleos3.valet.core.Snapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Background = Color(0xFFF4F6F5)
private val Navy = Color(0xFF1F3A5F)
private val Ink = Color(0xFF172B3A)
private val Muted = Color(0xFF52636E)
private val Bad = Color(0xFFAE3434)
private val Good = Color(0xFF23704B)

private val AppColors = lightColorScheme(
    primary = Navy,
    onPrimary = Color.White,
    secondary = Good,
    background = Background,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = Bad,
)

@Composable
fun ValetApp(
    state: ValetUiState,
    onTap: (String) -> Unit,
    onMemo: (String) -> Unit,
) {
    MaterialTheme(colorScheme = AppColors) {
        if (state.screen == ValetScreen.DRIVING) {
            DrivingContent()
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background),
            ) {
                val gap = if (maxHeight < 560.dp) 8.dp else 12.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(gap),
                ) {
                    AppHeader(
                        finishing = state.screen == ValetScreen.FINISHING,
                    )

                    when (state.screen) {
                        ValetScreen.IDLE -> IdleContent(state, onTap, onMemo)
                        ValetScreen.GUIDE -> GuideContent(state, onTap)
                        ValetScreen.HANDED,
                        ValetScreen.WAITING,
                        ValetScreen.ARRIVED,
                        -> WaitingContent(state, onTap)
                        ValetScreen.FINISHING -> FinishingContent(state, onTap)
                        ValetScreen.DONE -> {
                            state.card?.let { card ->
                                DoneContent(state, card, onTap)
                            }
                        }
                        ValetScreen.DRIVING -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun DrivingContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "운행 중",
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = Navy,
                )
                Text(
                    text = "소리로만 안내합니다",
                    fontSize = 28.sp,
                    color = Ink,
                )
            }
        }
    }
}

@Composable
private fun AppHeader(finishing: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (finishing) {
                "대리 인계 카드 · 인계 마치기"
            } else {
                "대리 인계 카드"
            },
            modifier = Modifier.weight(1f),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = Navy,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(16.dp))
        Surface(
            color = Good.copy(alpha = 0.1f),
            shape = RoundedCornerShape(24.dp),
        ) {
            Text(
                text = "P · 정차 중",
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 6.dp,
                ),
                fontSize = 22.sp,
                color = Good,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text = text,
        fontSize = 40.sp,
        lineHeight = 48.sp,
        fontWeight = FontWeight.Bold,
        color = Navy,
    )
}

@Composable
private fun ColumnScope.IdleContent(
    state: ValetUiState,
    onTap: (String) -> Unit,
    onMemo: (String) -> Unit,
) {
    PageTitle("대리를 맡기시나요?")
    BodyText(
        "목적지·주차 메모·차 상태를 같은 인계 카드에 남깁니다.",
        size = 22.sp,
        maxLines = 1,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WhiteCard(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight(),
        ) {
            Text(
                text = "목적지  ${state.destination?.name ?: "정해지지 않음"}",
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold,
                color = Navy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            BodyText(
                destinationAddress(state),
                size = 22.sp,
                maxLines = 1,
            )
            if (state.destinationIsExample) {
                BodyText("즐겨찾기 ‘집’이 없어 예시 목적지입니다", 22.sp, 1)
            }
            HorizontalDivider()
            BodyText("맡길 때 상태", 24.sp, 1, bold = true)
            CurrentStateSummary(state.car)
        }

        WhiteCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            BodyText("주차 메모", 26.sp, 1, bold = true)
            DictationContent(
                state = state.dictation,
                target = ValetDictationTarget.MEMO,
                onTap = onTap,
            ) {
                BodyText(
                    state.ownerMemo?.let { "«$it»" }
                        ?: "말로 주차 메모를 남겨 주세요",
                    size = 24.sp,
                    maxLines = 3,
                )
                Spacer(Modifier.weight(1f))
                ActionButton(
                    text = "메모 말하기",
                    onClick = { onTap("speak_memo") },
                    modifier = Modifier.fillMaxWidth(),
                )
                ActionButton(
                    text = "예시 메모 넣기",
                    onClick = { onMemo(ValetExamples.OWNER_MEMO) },
                    modifier = Modifier.fillMaxWidth(),
                    primary = false,
                )
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = "맡김은 차가 서 있을 때만 시작할 수 있습니다.",
            modifier = Modifier.weight(1f),
            fontSize = 22.sp,
            color = Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ActionButton(
            text = "맡김 시작",
            onClick = { onTap("handover") },
            modifier = Modifier.width(220.dp),
        )
    }
}

@Composable
private fun ColumnScope.DictationContent(
    state: DictationState<ValetDictationTarget>,
    target: ValetDictationTarget,
    onTap: (String) -> Unit,
    expand: Boolean = true,
    idleContent: @Composable ColumnScope.() -> Unit,
) {
    // 다른 대상의 받아쓰기는 이 칸에서 쉼으로 보인다.
    val current = if (state.target == target) state else DictationState.Idle

    when (current) {
        DictationState.Idle -> idleContent()
        is DictationState.Listening -> {
            BodyText("듣는 중…", 24.sp, 1, bold = true)
            BodyText(
                current.partial.ifBlank { "말씀하세요" },
                size = 24.sp,
                maxLines = 3,
            )
            DictationSpacer(expand)
        }
        is DictationState.Heard -> {
            BodyText("이렇게 들었습니다", 24.sp, 1, bold = true)
            BodyText(
                current.text,
                size = 24.sp,
                maxLines = 3,
            )
            DictationSpacer(expand)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ActionButton(
                    text = "이대로",
                    onClick = { onTap("confirm") },
                    modifier = Modifier.weight(1f),
                    fontSize = 22.sp,
                )
                ActionButton(
                    text = "다시 말하기",
                    onClick = { onTap("retry") },
                    modifier = Modifier.weight(1f),
                    primary = false,
                    fontSize = 22.sp,
                )
            }
        }
        is DictationState.Failed -> {
            BodyText(
                Dictation.failureText(current.reason),
                size = 24.sp,
                maxLines = 2,
            )
            DictationSpacer(expand)
            ActionButton(
                text = "다시 말하기",
                onClick = { onTap("retry") },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ColumnScope.DictationSpacer(expand: Boolean) {
    if (expand) {
        Spacer(Modifier.weight(1f))
    } else {
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ColumnScope.GuideContent(
    state: ValetUiState,
    onTap: (String) -> Unit,
) {
    PageTitle("차주님이 정한 목적지로\n안내할까요?")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WhiteCard(
            modifier = Modifier
                .weight(1.2f)
                .fillMaxHeight(),
        ) {
            BodyText(destinationLabel(state), 30.sp, 2, bold = true)
            BodyText(
                if (state.destinationIsExample) {
                    "예시 목적지"
                } else {
                    "차 내비 즐겨찾기 ‘집’"
                },
                22.sp,
                1,
            )
            HorizontalDivider()
            BodyText("차주 메모", 24.sp, 1, bold = true)
            BodyText(
                state.ownerMemo ?: "남긴 메모가 없습니다",
                26.sp,
                3,
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ActionButton(
                text = "차 내비로 안내",
                onClick = { onTap("navi_car") },
                modifier = Modifier.fillMaxWidth(),
            )
            ActionButton(
                text = "내 앱 쓸게요",
                onClick = { onTap("navi_own") },
                modifier = Modifier.fillMaxWidth(),
                primary = false,
            )
        }
    }

    Surface(
        color = Navy.copy(alpha = 0.07f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "달리는 동안 — 화면 없이 소리로만\n" +
                "경고등이 새로 켜진 시각은 인계 카드에 남습니다.",
            modifier = Modifier.padding(12.dp),
            fontSize = 22.sp,
            lineHeight = 28.sp,
            color = Navy,
        )
    }
}

@Composable
private fun ColumnScope.WaitingContent(
    state: ValetUiState,
    onTap: (String) -> Unit,
) {
    val arrived = state.screen == ValetScreen.ARRIVED
    val title = when (state.screen) {
        ValetScreen.HANDED -> "맡김을 시작했습니다"
        ValetScreen.ARRIVED -> "차가 도착했습니다"
        else -> "출발을 기다리고 있습니다"
    }
    val description = when (state.screen) {
        ValetScreen.HANDED ->
            "기사님이 운전석에 앉으면 목적지를 안내합니다."
        ValetScreen.ARRIVED ->
            "내리기 전에 전조등과 창문을 확인해 주세요."
        else -> if (state.guideChoice == GuideChoice.CAR) {
            "차 내비로 안내를 시작했습니다."
        } else {
            "기사님 앱으로 안내해 주세요."
        }
    }

    PageTitle(title)
    BodyText(description, 26.sp, 2)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WhiteCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            BodyText("목적지", 24.sp, 1, bold = true)
            BodyText(destinationLabel(state), 28.sp, 2)
            HorizontalDivider()
            BodyText("차주 메모", 24.sp, 1, bold = true)
            BodyText(state.ownerMemo ?: "남긴 메모가 없습니다", 24.sp, 3)
        }

        WhiteCard(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            BodyText("현재 차 상태", 26.sp, 1, bold = true)
            CurrentStateSummary(state.car)
        }
    }

    if (arrived) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "인계 마치기를 누른 뒤 주차 위치를 남겨 주세요.",
                modifier = Modifier.weight(1f),
                fontSize = 22.sp,
                color = Muted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ActionButton(
                text = "인계 마치기",
                onClick = { onTap("finish") },
                modifier = Modifier.width(240.dp),
            )
        }
    } else {
        BodyText("달리는 동안에는 소리로만 안내합니다.", 24.sp, 1)
    }
}

private data class CheckStatus(
    val label: String,
    val value: String,
    val color: Color,
)

@Composable
private fun ColumnScope.FinishingContent(
    state: ValetUiState,
    onTap: (String) -> Unit,
) {
    PageTitle("내리기 전에 확인해 주세요")

    val statuses = buildList {
        add(
            CheckStatus(
                "전조등",
                beamLabel(state.car.lowBeamOn),
                booleanColor(state.car.lowBeamOn),
            ),
        )
        for (position in WindowPos.entries) {
            val open = state.car.windowOpen[position]
            add(
                CheckStatus(
                    Names.window(position),
                    when (open) {
                        true -> "열려 있음"
                        false -> "닫힘"
                        null -> "확인 안 됨"
                    },
                    booleanColor(open),
                ),
            )
        }
        add(
            CheckStatus(
                "연료",
                fuelLabel(state.car.fuelPercent),
                if (state.car.fuelPercent == null) Muted else Navy,
            ),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (row in statuses.chunked(3)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (status in row) {
                    Surface(
                        color = status.color.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            BodyText(status.label, 22.sp, 1, bold = true)
                            Text(
                                text = status.value,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = status.color,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }

    BodyText("주차 위치를 말씀해 주세요", 28.sp, 1, bold = true)

    DictationContent(
        state = state.dictation,
        target = ValetDictationTarget.PARKING_SPOT,
        onTap = onTap,
        expand = false,
    ) {
        ActionButton(
            text = "주차 위치 말하기",
            onClick = { onTap("speak_spot") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val labels = listOf(
                "지하 2층 C구역\n17번 기둥",
                "지하 1층 A구역\n엘리베이터 앞",
                "지상 주차장\n정문 옆",
            )
            labels.forEachIndexed { index, label ->
                ActionButton(
                    text = label,
                    onClick = { onTap("spot${index + 1}") },
                    modifier = Modifier.weight(1f),
                    primary = index == 0,
                    fontSize = 22.sp,
                )
            }
        }
    }

    BodyText(
        "들은 글을 확인하고 «이대로»를 누르면 카드를 만듭니다.",
        22.sp,
        2,
    )
}

@Composable
private fun ColumnScope.DoneContent(
    state: ValetUiState,
    card: HandoverCard,
    onTap: (String) -> Unit,
) {
    PageTitle("차가 도착했습니다")
    Text(
        text = "카드 저장 완료(시연용, 서버 없음) · ${formatTime(card.createdMs)}",
        fontSize = 22.sp,
        color = Good,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        WhiteCard(
            modifier = Modifier
                .weight(0.9f)
                .fillMaxHeight(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BodyText("주차 위치", 24.sp, 1, bold = true)
                if (
                    card.parkingSpot != null &&
                    !card.parkingSpotConfirmed
                ) {
                    Surface(
                        color = Bad.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "기사 확인 전",
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = 6.dp,
                            ),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = Bad,
                        )
                    }
                }
            }
            BodyText(
                card.parkingSpot ?: "주차 위치 없이 만든 기본 카드",
                28.sp,
                2,
                bold = true,
            )
            if (card.kind == CardKind.DEFAULT) {
                BodyText("운전석 문이 열릴 때 상태를 기록했습니다", 22.sp, 2)
            }
            state.locationAtFinish?.let { fix ->
                BodyText("마지막 위치 수신 ${formatTime(fix.timeMs)}", 22.sp, 1)
            }
            BodyText(
                "차주 메모 · ${card.ownerMemo ?: "없음"}",
                22.sp,
                2,
            )
            Spacer(Modifier.weight(1f))
            TelltaleHistory(card)
        }

        WhiteCard(
            modifier = Modifier
                .weight(1.3f)
                .fillMaxHeight(),
        ) {
            BodyText("차 상태 — 맡길 때와 인계 때", 24.sp, 1, bold = true)
            ComparisonRow("", "맡길 때", "인계 때", heading = true)
            HorizontalDivider()
            ComparisonRow(
                "연료",
                fuelLabel(card.atHandover.fuelPercent),
                fuelLabel(card.atFinish.fuelPercent),
            )
            ComparisonRow(
                "주행거리",
                odometerLabel(card.atHandover.odometerKm),
                odometerLabel(card.atFinish.odometerKm),
            )
            ComparisonRow(
                "전조등",
                beamLabel(card.atHandover.lowBeamOn),
                beamLabel(card.atFinish.lowBeamOn),
            )
            ComparisonRow(
                "창문",
                snapshotWindows(card.atHandover),
                snapshotWindows(card.atFinish),
            )
            ComparisonRow(
                "경고등",
                telltalesLabel(card.atHandover.telltales),
                telltalesLabel(card.atFinish.telltales),
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "시동을 끈 뒤 상태와 차체 손상은 담지 않습니다.",
            modifier = Modifier.weight(1f),
            fontSize = 22.sp,
            color = Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        ActionButton(
            text = "새로 시작",
            onClick = { onTap("reset") },
            modifier = Modifier.width(220.dp),
        )
    }
}

@Composable
private fun TelltaleHistory(card: HandoverCard) {
    var index by remember(card) { mutableIntStateOf(0) }
    val events = card.telltaleEvents

    BodyText("운행 중 켜진 경고등", 22.sp, 1, bold = true)
    if (events.isEmpty()) {
        BodyText("기록 없음", 24.sp, 1)
    } else {
        val event = events[index.coerceIn(events.indices)]
        BodyText(
            "${formatTime(event.timeMs)} · ${Names.telltale(event.name)}",
            24.sp,
            2,
        )
        if (events.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButton(
                    text = "이전",
                    onClick = { index -= 1 },
                    modifier = Modifier.weight(1f),
                    primary = false,
                    enabled = index > 0,
                    fontSize = 22.sp,
                )
                Text(
                    text = "${index + 1}/${events.size}",
                    fontSize = 22.sp,
                    color = Muted,
                )
                ActionButton(
                    text = "다음",
                    onClick = { index += 1 },
                    modifier = Modifier.weight(1f),
                    primary = false,
                    enabled = index < events.lastIndex,
                    fontSize = 22.sp,
                )
            }
        }
    }
}

@Composable
private fun ComparisonRow(
    label: String,
    before: String,
    after: String,
    heading: Boolean = false,
) {
    val weight = if (heading) FontWeight.SemiBold else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(0.8f),
            fontSize = 22.sp,
            color = Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = before,
            modifier = Modifier.weight(1f),
            fontSize = 22.sp,
            fontWeight = weight,
            color = Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = after,
            modifier = Modifier.weight(1f),
            fontSize = 22.sp,
            fontWeight = weight,
            color = Navy,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CurrentStateSummary(state: CarState) {
    BodyText(
        "연료 ${fuelLabel(state.fuelPercent)} · " +
            "주행거리 ${odometerLabel(state.odometerKm)}",
        22.sp,
        2,
    )
    BodyText("경고등 ${telltalesLabel(state.telltales)}", 22.sp, 1)
    BodyText("전조등 ${beamLabel(state.lowBeamOn)}", 22.sp, 1)

    val open = WindowPos.entries.filter { state.windowOpen[it] == true }
    val windows = when {
        open.isNotEmpty() -> open.joinToString(" · ") { Names.window(it) } + " 열림"
        WindowPos.entries.all { state.windowOpen[it] == false } -> "창문 모두 닫힘"
        else -> "창문 확인 안 됨"
    }
    BodyText(windows, 22.sp, 1)
}

@Composable
private fun WhiteCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            content = content,
        )
    }
}

@Composable
private fun BodyText(
    text: String,
    size: TextUnit = 24.sp,
    maxLines: Int = 2,
    bold: Boolean = false,
) {
    Text(
        text = text,
        fontSize = size,
        lineHeight = size * 1.2f,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = Ink,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
    fontSize: TextUnit = 24.sp,
) {
    val buttonModifier = modifier.heightIn(min = 72.dp)
    val shape = RoundedCornerShape(12.dp)
    val padding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
    val content: @Composable () -> Unit = {
        Text(
            text = text,
            fontSize = fontSize,
            lineHeight = fontSize * 1.2f,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (primary) {
        Button(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = Navy,
                contentColor = Color.White,
            ),
        ) {
            content()
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = buttonModifier,
            enabled = enabled,
            shape = shape,
            contentPadding = padding,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = Navy,
            ),
        ) {
            content()
        }
    }
}

private fun destinationLabel(state: ValetUiState): String {
    val place = state.destination ?: return "정해 둔 목적지가 없습니다"
    return if (place.address.isBlank()) {
        place.name
    } else {
        "${place.name} · ${place.address}"
    }
}

private fun destinationAddress(state: ValetUiState): String =
    state.destination?.address?.takeIf { it.isNotBlank() }
        ?: "주소 정보 없음"

private fun booleanColor(value: Boolean?): Color = when (value) {
    true -> Bad
    false -> Good
    null -> Muted
}

private fun beamLabel(value: Boolean?): String = when (value) {
    true -> "켜져 있음"
    false -> "꺼짐"
    null -> "확인 안 됨"
}

private fun fuelLabel(value: Int?): String =
    value?.let { "$it%" } ?: "확인 안 됨"

private fun odometerLabel(value: Float?): String =
    value?.takeIf { it.isFinite() }?.let {
        String.format(Locale.KOREA, "%,.1f km", it)
    } ?: "확인 안 됨"

private fun telltalesLabel(names: Set<String>): String =
    names.sorted().joinToString(" · ") { Names.telltale(it) }
        .ifEmpty { "없음" }

private fun snapshotWindows(snapshot: Snapshot): String =
    if (snapshot.openWindows.isEmpty()) {
        // 스냅숏에는 미확인 창문과 명시적으로 닫힌 창문의 구분이 없다.
        "열림 기록 없음"
    } else {
        snapshot.openWindows
            .sortedBy { it.ordinal }
            .joinToString(" · ") { Names.window(it) + " 열림" }
    }

private fun formatTime(timeMs: Long): String =
    DateTimeFormatter
        .ofPattern("MM.dd HH:mm", Locale.KOREA)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(timeMs))
