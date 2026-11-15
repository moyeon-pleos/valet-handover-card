package dev.pleos3.valet.core

import dev.pleos3.car.CarState
import dev.pleos3.car.Gear
import dev.pleos3.car.NaviPlace
import dev.pleos3.car.WindowPos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ValetSessionTest {
    @Test
    fun `처음에는 대기 단계이고 카드가 없다`() {
        val h = Harness()

        assertEquals(Phase.IDLE, h.session.phase)
        assertNull(h.session.card)
    }

    @Test
    fun `맡기면 맡김 단계가 되고 시작 문장을 말한다`() {
        val h = Harness()

        val effects = h.handOver()

        assertEquals(Phase.HANDED, h.session.phase)
        assertNull(h.session.card)
        assertEquals(
            listOf(Effect.Say("맡김을 시작했습니다")),
            effects,
        )
    }

    @Test
    fun `맡김 뒤 운전석 착석과 P가 갖춰지면 목적지 안내를 묻는다`() {
        val h = Harness()
        h.handOver()

        val effects = h.update(h.state.copy(driverSeated = true))

        assertEquals(Phase.GUIDE, h.session.phase)
        assertEquals(2, effects.size)
        assertEquals(1, effects.count { it == Effect.ShowGuide })
        assertEquals(
            listOf("차주님이 정한 목적지 ‘집’으로 안내할까요?"),
            effects.filterIsInstance<Effect.Say>().map { it.text },
        )
    }

    @Test
    fun `운전석 착석이 거짓이거나 미상이면 안내를 묻지 않는다`() {
        for (seated in listOf<Boolean?>(false, null)) {
            val h = Harness()
            h.handOver()

            val effects = h.update(h.state.copy(driverSeated = seated))

            assertEquals(emptyList<Effect>(), effects)
            assertEquals(Phase.HANDED, h.session.phase)
        }
    }

    @Test
    fun `안내 단계에서 같은 상태를 받아도 안내를 다시 묻지 않는다`() {
        val h = Harness()
        h.enter(Phase.GUIDE)

        assertEquals(emptyList<Effect>(), h.update(h.state))
        assertEquals(Phase.GUIDE, h.session.phase)
    }

    @Test
    fun `목적지가 없으면 안내 단계로 가지 않고 한 번만 알린다`() {
        val h = Harness()
        h.handOver(destination = null)

        assertEquals(
            emptyList<Effect>(),
            h.update(h.state.copy(driverSeated = false)),
        )

        val first = h.update(h.state.copy(driverSeated = true))

        assertEquals(
            listOf(Effect.Say("정해 둔 목적지가 없습니다")),
            first,
        )
        assertEquals(Phase.HANDED, h.session.phase)
        assertEquals(emptyList<Effect>(), h.update(h.state))

        h.update(h.state.copy(driverSeated = false))
        assertEquals(
            emptyList<Effect>(),
            h.update(h.state.copy(driverSeated = true)),
        )
        assertEquals(Phase.HANDED, h.session.phase)
    }

    @Test
    fun `차 내비 선택은 목적지 설정과 안내 시작 음성을 낸다`() {
        val h = Harness()
        h.enter(Phase.GUIDE)

        val effects = h.session.chooseNavi(true)

        assertEquals(Phase.GUIDE, h.session.phase)
        assertEquals(2, effects.size)
        assertEquals(1, effects.count { it == Effect.RouteTo(h.home) })
        assertEquals(
            listOf("차 내비로 안내를 시작합니다"),
            effects.filterIsInstance<Effect.Say>().map { it.text },
        )
    }

    @Test
    fun `기사 앱 선택은 효과 없이 안내 단계를 유지한다`() {
        val h = Harness()
        h.enter(Phase.GUIDE)

        assertEquals(emptyList<Effect>(), h.session.chooseNavi(false))
        assertEquals(Phase.GUIDE, h.session.phase)
    }

    @Test
    fun `맡김과 안내 단계에서 P가 아닌 모든 기어는 운행으로 넘어간다`() {
        for (phase in listOf(Phase.HANDED, Phase.GUIDE)) {
            for (gear in Gear.entries.filter { it != Gear.P }) {
                val h = Harness()
                h.enter(phase)

                val effects = h.update(
                    h.state.copy(gear = gear, driverSeated = true),
                )

                assertEquals("$phase / $gear", Phase.DRIVING, h.session.phase)
                assertFalse(effects.contains(Effect.ShowGuide))
                assertFalse(effects.contains(Effect.ShowFinish))
                assertNull(h.session.card)
            }
        }
    }

    @Test
    fun `운행 중 새 경고등만 말하고 그때의 시각을 기록한다`() {
        val h = Harness(
            initial = baseState().copy(telltales = setOf("ABS")),
        )
        h.enter(Phase.DRIVING)

        h.now = 100L
        val first = h.update(
            h.state.copy(telltales = setOf("ABS", "TPMS", "AIRBAG")),
        )

        val expectedSpeech = setOf(
            "경고등이 켜졌습니다. 타이어 공기압",
            "경고등이 켜졌습니다. 에어백",
        )
        assertEquals(
            expectedSpeech,
            first.filterIsInstance<Effect.Say>().map { it.text }.toSet(),
        )
        assertEquals(2, first.size)

        h.now = 200L
        assertEquals(emptyList<Effect>(), h.update(h.state))
        assertEquals(
            emptyList<Effect>(),
            h.update(h.state.copy(telltales = setOf("TPMS", "AIRBAG"))),
        )

        h.now = 300L
        val unknown = h.update(
            h.state.copy(
                telltales = h.state.telltales + "ENGINE_CHECK",
            ),
        )
        assertEquals(
            listOf(Effect.Say("경고등이 켜졌습니다. ENGINE_CHECK")),
            unknown,
        )

        h.now = 500L
        val card = h.finishFull()
        val expectedEvents = setOf(
            TelltaleEvent("TPMS", 100L),
            TelltaleEvent("AIRBAG", 100L),
            TelltaleEvent("ENGINE_CHECK", 300L),
        )

        assertEquals(expectedEvents, card.telltaleEvents.toSet())
        assertEquals(expectedEvents.size, card.telltaleEvents.size)
    }

    @Test
    fun `운행 전과 마무리 중의 경고등 변화는 운행 경고 기록에 넣지 않는다`() {
        val h = Harness()
        h.handOver()

        assertEquals(
            emptyList<Effect>(),
            h.update(h.state.copy(telltales = setOf("TPMS"))),
        )
        h.update(h.state.copy(driverSeated = true))

        assertEquals(
            emptyList<Effect>(),
            h.update(h.state.copy(telltales = setOf("TPMS", "ABS"))),
        )
        h.update(h.state.copy(gear = Gear.D))
        assertEquals(emptyList<Effect>(), h.update(h.state))

        h.update(h.state.copy(gear = Gear.P))
        h.session.startFinish(h.state)

        assertEquals(
            emptyList<Effect>(),
            h.update(
                h.state.copy(
                    telltales = setOf("TPMS", "ABS", "MASTER_WARNING"),
                ),
            ),
        )
        h.session.parkingSpotSaid("지하 1층", h.state)

        assertEquals(
            emptyList<TelltaleEvent>(),
            requireNotNull(h.session.card).telltaleEvents,
        )
    }

    @Test
    fun `운행에서 P로 돌아오면 마무리 화면 효과를 내고 운행 단계를 유지한다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)

        val effects = h.update(h.state.copy(gear = Gear.P))

        assertEquals(listOf(Effect.ShowFinish), effects)
        assertEquals(Phase.DRIVING, h.session.phase)
        assertNull(h.session.card)
    }

    @Test
    fun `P가 아닌 기어 사이의 변경은 마무리 화면을 띄우지 않는다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)

        for (gear in Gear.entries.filter { it != Gear.P }) {
            val effects = h.update(h.state.copy(gear = gear))

            assertEquals(emptyList<Effect>(), effects)
            assertEquals(Phase.DRIVING, h.session.phase)
        }
    }

    @Test
    fun `조수석이나 뒷좌석에 동승자가 있으면 도착을 한 번 알린다`() {
        val occupants = listOf(
            true to false,
            false to true,
            true to true,
            true to null,
            null to true,
        )
        val arrival = Effect.Say(
            "도착했습니다. 차주님, 인계를 확인해 주세요",
        )

        for ((passenger, rear) in occupants) {
            val h = Harness(
                initial = baseState().copy(
                    passengerSeated = passenger,
                    rearSeated = rear,
                ),
            )
            h.enter(Phase.DRIVING)

            val effects = h.update(h.state.copy(gear = Gear.P))

            assertEquals(2, effects.size)
            assertEquals(1, effects.count { it == Effect.ShowFinish })
            assertEquals(1, effects.count { it == arrival })
            assertEquals(Phase.DRIVING, h.session.phase)

            assertFalse(h.update(h.state).contains(arrival))
            h.update(h.state.copy(gear = Gear.D))
            assertFalse(
                h.update(h.state.copy(gear = Gear.P)).contains(arrival),
            )
        }
    }

    @Test
    fun `동승자가 없거나 착석 여부가 미상이면 도착 음성을 내지 않는다`() {
        for (passenger in listOf<Boolean?>(false, null)) {
            for (rear in listOf<Boolean?>(false, null)) {
                val h = Harness(
                    initial = baseState().copy(
                        passengerSeated = passenger,
                        rearSeated = rear,
                    ),
                )
                h.enter(Phase.DRIVING)

                assertEquals(
                    listOf(Effect.ShowFinish),
                    h.update(h.state.copy(gear = Gear.P)),
                )
            }
        }
    }

    @Test
    fun `인계 마치기는 점검 목록 순서대로 말한 뒤 주차 위치를 묻는다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)
        h.update(
            h.state.copy(
                gear = Gear.P,
                lowBeamOn = true,
                windowOpen = mapOf(
                    WindowPos.FRONT_LEFT to true,
                    WindowPos.REAR_RIGHT to true,
                ),
            ),
        )

        val checklist = FinishChecklist.lines(h.state)
        assertEquals(3, checklist.size)

        val expected: List<Effect> =
            checklist.map { Effect.Say(it) } +
                Effect.Say("주차 위치를 말씀해 주세요") +
                Effect.ListenForParkingSpot

        assertEquals(expected, h.session.startFinish(h.state))
        assertEquals(Phase.FINISHING, h.session.phase)
        assertNull(h.session.card)
    }

    @Test
    fun `전조등만 켜졌을 때 전조등 점검 뒤 주차 위치를 묻는다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)
        h.update(h.state.copy(gear = Gear.P, lowBeamOn = true))

        assertEquals(
            listOf(
                Effect.Say("전조등이 켜져 있습니다"),
                Effect.Say("주차 위치를 말씀해 주세요"),
                Effect.ListenForParkingSpot,
            ),
            h.session.startFinish(h.state),
        )
    }

    @Test
    fun `창문만 열렸을 때 창문 점검 뒤 주차 위치를 묻는다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)
        h.update(
            h.state.copy(
                gear = Gear.P,
                windowOpen = mapOf(WindowPos.REAR_RIGHT to true),
            ),
        )

        assertEquals(
            listOf(
                Effect.Say("오른쪽 뒷창이 열려 있습니다"),
                Effect.Say("주차 위치를 말씀해 주세요"),
                Effect.ListenForParkingSpot,
            ),
            h.session.startFinish(h.state),
        )
    }

    @Test
    fun `점검할 항목이 없으면 괜찮다고 말한 뒤 주차 위치를 묻는다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)
        h.update(h.state.copy(gear = Gear.P))

        assertEquals(
            listOf(
                Effect.Say("전조등과 창문은 모두 괜찮습니다"),
                Effect.Say("주차 위치를 말씀해 주세요"),
                Effect.ListenForParkingSpot,
            ),
            h.session.startFinish(h.state),
        )
        assertEquals(Phase.FINISHING, h.session.phase)
    }

    @Test
    fun `운행 단계여도 P가 아니면 인계 마치기를 시작하지 않는다`() {
        for (gear in Gear.entries.filter { it != Gear.P }) {
            val h = Harness()
            h.enter(Phase.DRIVING)
            h.update(h.state.copy(gear = gear))

            assertEquals(
                emptyList<Effect>(),
                h.session.startFinish(h.state),
            )
            assertEquals(Phase.DRIVING, h.session.phase)
            assertNull(h.session.card)
        }
    }

    @Test
    fun `주차 위치를 받으면 다시 읽은 값으로 전체 카드를 만든다`() {
        val initial = baseState().copy(
            fuelPercent = 81,
            odometerKm = 12000.5f,
            telltales = setOf("SEAT_BELT"),
            lowBeamOn = true,
            windowOpen = mapOf(
                WindowPos.FRONT_LEFT to true,
                WindowPos.FRONT_RIGHT to false,
            ),
        )
        val h = Harness(initial)
        h.now = 10L
        h.handOver(memo = "지하 주차장으로 부탁드립니다")
        h.update(h.state.copy(driverSeated = true))
        h.update(h.state.copy(gear = Gear.D))
        h.update(
            h.state.copy(
                gear = Gear.P,
                fuelPercent = 70,
                odometerKm = 12020f,
                lowBeamOn = true,
                windowOpen = mapOf(WindowPos.REAR_RIGHT to true),
            ),
        )
        h.now = 20L
        h.session.startFinish(h.state)

        val refreshed = h.state.copy(
            fuelPercent = 68,
            odometerKm = 12021.25f,
            telltales = setOf("DOOR_OPEN"),
            lowBeamOn = false,
            windowOpen = mapOf(
                WindowPos.FRONT_LEFT to false,
                WindowPos.REAR_LEFT to true,
                WindowPos.REAR_RIGHT to false,
            ),
        )
        h.now = 30L
        val effects = h.session.parkingSpotSaid(
            "지하 2층 C구역 17번 기둥",
            refreshed,
        )

        val card = requireNotNull(h.session.card)
        assertEquals(Phase.DONE, h.session.phase)
        assertEquals(30L, card.createdMs)
        assertEquals(CardKind.FULL, card.kind)
        assertEquals("집", card.destination)
        assertEquals("지하 주차장으로 부탁드립니다", card.ownerMemo)
        assertEquals("지하 2층 C구역 17번 기둥", card.parkingSpot)
        assertEquals(
            Snapshot(
                timeMs = 10L,
                fuelPercent = 81,
                odometerKm = 12000.5f,
                telltales = setOf("SEAT_BELT"),
                lowBeamOn = true,
                openWindows = setOf(WindowPos.FRONT_LEFT),
            ),
            card.atHandover,
        )
        assertEquals(
            Snapshot(
                timeMs = 30L,
                fuelPercent = 68,
                odometerKm = 12021.25f,
                telltales = setOf("DOOR_OPEN"),
                lowBeamOn = false,
                openWindows = setOf(WindowPos.REAR_LEFT),
            ),
            card.atFinish,
        )
        assertEquals(
            emptyList<TelltaleEvent>(),
            card.telltaleEvents,
        )
        assertEquals(2, effects.size)
        assertEquals(
            listOf(Effect.CardReady(card)),
            effects.filterIsInstance<Effect.CardReady>(),
        )
        assertEquals(
            listOf(Effect.Say("인계 카드를 만들었습니다")),
            effects.filterIsInstance<Effect.Say>(),
        )
    }

    @Test
    fun `없는 목적지와 메모 및 알 수 없는 차 값은 카드에도 그대로 남긴다`() {
        val initial = CarState(gear = Gear.P, driverDoorOpen = false)
        val h = Harness(initial)
        h.now = 7L
        h.handOver(destination = null, memo = null)
        h.update(h.state.copy(gear = Gear.D))

        h.now = 9L
        val card = h.finishFull()

        assertNull(card.destination)
        assertNull(card.ownerMemo)
        assertEquals(
            Snapshot(7L, null, null, emptySet(), null, emptySet()),
            card.atHandover,
        )
        assertEquals(
            Snapshot(9L, null, null, emptySet(), null, emptySet()),
            card.atFinish,
        )
    }

    @Test
    fun `운행 또는 마무리 단계의 P에서 문을 열면 기본 카드를 만든다`() {
        for (phase in listOf(Phase.DRIVING, Phase.FINISHING)) {
            val h = Harness()
            h.enter(phase)
            h.update(h.state.copy(gear = Gear.P))
            h.now = 55L
            val finishState = h.state.copy(
                driverDoorOpen = true,
                fuelPercent = 42,
                odometerKm = 1234.5f,
                lowBeamOn = true,
                telltales = setOf("DOOR_OPEN"),
                windowOpen = mapOf(
                    WindowPos.FRONT_RIGHT to true,
                    WindowPos.REAR_LEFT to false,
                ),
            )

            val effects = h.update(finishState)

            val card = requireNotNull(h.session.card)
            assertEquals(Phase.DONE, h.session.phase)
            assertEquals(CardKind.DEFAULT, card.kind)
            assertEquals(55L, card.createdMs)
            assertEquals("집", card.destination)
            assertEquals("주차 메모", card.ownerMemo)
            assertNull(card.parkingSpot)
            assertEquals(
                Snapshot(0L, 70, 1000f, emptySet(), false, emptySet()),
                card.atHandover,
            )
            assertEquals(
                Snapshot(
                    55L,
                    42,
                    1234.5f,
                    setOf("DOOR_OPEN"),
                    true,
                    setOf(WindowPos.FRONT_RIGHT),
                ),
                card.atFinish,
            )
            assertEquals(
                listOf(Effect.CardReady(card)),
                effects.filterIsInstance<Effect.CardReady>(),
            )
        }
    }

    @Test
    fun `P 복귀와 문 열림이 동시에 와도 기본 카드를 만든다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)

        val effects = h.update(
            h.state.copy(gear = Gear.P, driverDoorOpen = true),
        )

        val card = requireNotNull(h.session.card)
        assertEquals(CardKind.DEFAULT, card.kind)
        assertNull(card.parkingSpot)
        assertEquals(Phase.DONE, h.session.phase)
        assertEquals(
            listOf(Effect.CardReady(card)),
            effects.filterIsInstance<Effect.CardReady>(),
        )
    }

    @Test
    fun `기본 카드에도 운행 중 경고등과 발생 시각을 남긴다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)
        h.now = 12L
        h.update(h.state.copy(telltales = setOf("TPMS")))
        h.update(h.state.copy(gear = Gear.P))
        h.now = 25L

        h.update(h.state.copy(driverDoorOpen = true))

        val card = requireNotNull(h.session.card)
        assertEquals(CardKind.DEFAULT, card.kind)
        assertEquals(
            listOf(TelltaleEvent("TPMS", 12L)),
            card.telltaleEvents,
        )
        assertEquals(25L, card.createdMs)
    }

    @Test
    fun `P가 아니면 문이 열려도 기본 카드를 만들지 않는다`() {
        for (phase in listOf(Phase.DRIVING, Phase.FINISHING)) {
            for (gear in Gear.entries.filter { it != Gear.P }) {
                val h = Harness()
                h.enter(phase)
                h.update(h.state.copy(gear = gear))

                val effects = h.update(h.state.copy(driverDoorOpen = true))

                assertNull(h.session.card)
                assertTrue(effects.filterIsInstance<Effect.CardReady>().isEmpty())
            }
        }
    }

    @Test
    fun `문 열림은 명시적인 닫힘에서 열림으로 바뀔 때만 기본 카드를 만든다`() {
        for (phase in listOf(Phase.DRIVING, Phase.FINISHING)) {
            val h = Harness()
            h.enter(phase)
            h.update(h.state.copy(gear = Gear.P))
            h.update(h.state.copy(driverDoorOpen = null))

            val unknownToOpen = h.update(
                h.state.copy(driverDoorOpen = true),
            )
            val openToOpen = h.update(h.state)
            val openToClosed = h.update(
                h.state.copy(driverDoorOpen = false),
            )

            for (effects in listOf(unknownToOpen, openToOpen, openToClosed)) {
                assertTrue(effects.filterIsInstance<Effect.CardReady>().isEmpty())
            }
            assertNull(h.session.card)
            assertEquals(phase, h.session.phase)
        }
    }

    @Test
    fun `운행 전 단계에서는 문이 열려도 기본 카드를 만들지 않는다`() {
        for (phase in listOf(Phase.IDLE, Phase.HANDED, Phase.GUIDE)) {
            val h = Harness()
            h.enter(phase)

            val effects = h.update(
                h.state.copy(gear = Gear.P, driverDoorOpen = true),
            )

            assertNull(h.session.card)
            assertTrue(effects.filterIsInstance<Effect.CardReady>().isEmpty())
            assertEquals(phase, h.session.phase)
        }
    }

    @Test
    fun `대기 외 단계의 맡김 호출은 효과와 상태 변화가 없다`() {
        for (phase in Phase.entries.filter { it != Phase.IDLE }) {
            val h = Harness()
            h.enter(phase)
            val previousCard = h.session.card

            val effects = h.session.handOver(
                h.state.copy(fuelPercent = 1),
                NaviPlace("다른 목적지", "다른 주소", 35.0, 129.0),
                "다른 메모",
            )

            assertEquals(emptyList<Effect>(), effects)
            assertEquals(phase, h.session.phase)
            assertEquals(previousCard, h.session.card)

            if (phase != Phase.DONE) {
                val card = when (phase) {
                    Phase.HANDED, Phase.GUIDE -> {
                        h.update(h.state.copy(gear = Gear.D))
                        h.finishFull()
                    }
                    Phase.DRIVING -> h.finishFull()
                    Phase.FINISHING -> {
                        h.session.parkingSpotSaid("지하 1층", h.state)
                        requireNotNull(h.session.card)
                    }
                    else -> error("시험 준비 단계 오류")
                }
                assertEquals("집", card.destination)
                assertEquals("주차 메모", card.ownerMemo)
                assertEquals(70, card.atHandover.fuelPercent)
            }
        }
    }

    @Test
    fun `안내 외 단계의 내비 선택은 효과와 상태 변화가 없다`() {
        for (phase in Phase.entries.filter { it != Phase.GUIDE }) {
            for (useCarNavi in listOf(false, true)) {
                val h = Harness()
                h.enter(phase)
                val previousCard = h.session.card

                assertEquals(
                    emptyList<Effect>(),
                    h.session.chooseNavi(useCarNavi),
                )
                assertEquals(phase, h.session.phase)
                assertEquals(previousCard, h.session.card)
            }
        }
    }

    @Test
    fun `운행 외 단계의 인계 마치기는 효과와 상태 변화가 없다`() {
        for (phase in Phase.entries.filter { it != Phase.DRIVING }) {
            val h = Harness()
            h.enter(phase)
            val previousCard = h.session.card

            assertEquals(
                emptyList<Effect>(),
                h.session.startFinish(h.state.copy(gear = Gear.P)),
            )
            assertEquals(phase, h.session.phase)
            assertEquals(previousCard, h.session.card)
        }
    }

    @Test
    fun `마무리 외 단계의 주차 위치 입력은 효과와 상태 변화가 없다`() {
        for (phase in Phase.entries.filter { it != Phase.FINISHING }) {
            val h = Harness()
            h.enter(phase)
            val previousCard = h.session.card

            assertEquals(
                emptyList<Effect>(),
                h.session.parkingSpotSaid(
                    "무시할 위치",
                    h.state.copy(fuelPercent = 1),
                ),
            )
            assertEquals(phase, h.session.phase)
            assertEquals(previousCard, h.session.card)
        }
    }

    @Test
    fun `대기 단계의 차 상태 변경은 운행이나 카드 생성을 시작하지 않는다`() {
        val h = Harness()
        val effects = h.update(
            h.state.copy(
                gear = Gear.D,
                driverSeated = true,
                driverDoorOpen = true,
                telltales = setOf("TPMS"),
            ),
        )

        assertEquals(emptyList<Effect>(), effects)
        assertEquals(Phase.IDLE, h.session.phase)
        assertNull(h.session.card)
    }

    @Test
    fun `완료 후 상태 변경과 재입력은 기존 카드를 바꾸지 않는다`() {
        val h = Harness()
        h.enter(Phase.DONE)
        val original = requireNotNull(h.session.card)

        assertEquals(
            emptyList<Effect>(),
            h.update(
                h.state.copy(
                    gear = Gear.D,
                    telltales = setOf("TPMS"),
                    fuelPercent = 1,
                ),
            ),
        )
        assertEquals(
            emptyList<Effect>(),
            h.update(
                h.state.copy(gear = Gear.P, driverDoorOpen = true),
            ),
        )
        assertEquals(
            emptyList<Effect>(),
            h.session.parkingSpotSaid("다른 위치", h.state),
        )
        assertEquals(Phase.DONE, h.session.phase)
        assertEquals(original, h.session.card)
    }

    @Test
    fun `기본 카드가 만들어진 뒤 주차 위치를 받아도 전체 카드로 바꾸지 않는다`() {
        val h = Harness()
        h.enter(Phase.FINISHING)
        h.update(h.state.copy(driverDoorOpen = true))
        val original = requireNotNull(h.session.card)

        assertEquals(
            emptyList<Effect>(),
            h.session.parkingSpotSaid("늦게 받은 위치", h.state),
        )
        assertEquals(CardKind.DEFAULT, original.kind)
        assertEquals(original, h.session.card)
        assertEquals(Phase.DONE, h.session.phase)
    }

    @Test
    fun `어느 단계에서 초기화해도 대기 단계와 빈 카드로 돌아간다`() {
        for (phase in Phase.entries) {
            val h = Harness()
            h.enter(phase)

            h.session.reset()

            assertEquals(Phase.IDLE, h.session.phase)
            assertNull(h.session.card)

            h.session.reset()
            assertEquals(Phase.IDLE, h.session.phase)
            assertNull(h.session.card)
            assertEquals(
                listOf(Effect.Say("맡김을 시작했습니다")),
                h.session.handOver(h.initial, h.home, "새 메모"),
            )
            assertEquals(Phase.HANDED, h.session.phase)
        }
    }

    @Test
    fun `초기화하면 이전 카드와 경고 기록 및 맡김 정보를 지운다`() {
        val h = Harness()
        h.enter(Phase.DRIVING)
        h.now = 10L
        h.update(h.state.copy(telltales = setOf("TPMS")))
        val oldCard = h.finishFull()
        assertEquals(
            listOf(TelltaleEvent("TPMS", 10L)),
            oldCard.telltaleEvents,
        )

        h.session.reset()
        h.state = h.initial.copy(
            fuelPercent = 30,
            odometerKm = 2000f,
        )
        h.now = 100L
        h.handOver(destination = null, memo = null)
        h.update(h.state.copy(gear = Gear.D))
        h.now = 200L

        val newCard = h.finishFull("새 주차 위치")

        assertNull(newCard.destination)
        assertNull(newCard.ownerMemo)
        assertEquals("새 주차 위치", newCard.parkingSpot)
        assertEquals(100L, newCard.atHandover.timeMs)
        assertEquals(30, newCard.atHandover.fuelPercent)
        assertEquals(2000f, newCard.atHandover.odometerKm)
        assertEquals(200L, newCard.createdMs)
        assertEquals(emptyList<TelltaleEvent>(), newCard.telltaleEvents)
    }

    @Test
    fun `초기화하면 목적지 없음 안내를 새 세션에서 다시 할 수 있다`() {
        val h = Harness()

        repeat(2) {
            h.handOver(destination = null)
            assertEquals(
                listOf(Effect.Say("정해 둔 목적지가 없습니다")),
                h.update(h.state.copy(driverSeated = true)),
            )
            assertEquals(emptyList<Effect>(), h.update(h.state))
            h.session.reset()
            h.state = h.initial
        }
    }

    @Test
    fun `초기화하면 새 운행에서 도착 알림을 다시 할 수 있다`() {
        val h = Harness(
            initial = baseState().copy(passengerSeated = true),
        )
        val arrival = Effect.Say(
            "도착했습니다. 차주님, 인계를 확인해 주세요",
        )

        repeat(2) {
            h.enter(Phase.DRIVING)
            val effects = h.update(h.state.copy(gear = Gear.P))
            assertEquals(1, effects.count { effect -> effect == arrival })

            h.session.reset()
            h.state = h.initial
        }
    }

    private class Harness(
        val initial: CarState = baseState(),
    ) {
        var now = 0L
        var state = initial
        val session = ValetSession { now }
        val home = NaviPlace(
            name = "집",
            address = "예시 아파트",
            lat = 37.5,
            lon = 127.0,
        )

        fun handOver(
            destination: NaviPlace? = home,
            memo: String? = "주차 메모",
        ): List<Effect> = session.handOver(state, destination, memo)

        fun update(next: CarState): List<Effect> {
            val effects = session.onCarState(state, next)
            state = next
            return effects
        }

        fun enter(target: Phase) {
            if (target == Phase.IDLE) return

            handOver()
            if (target == Phase.HANDED) return

            update(state.copy(driverSeated = true))
            if (target == Phase.GUIDE) return

            update(state.copy(gear = Gear.D))
            if (target == Phase.DRIVING) return

            update(state.copy(gear = Gear.P))
            session.startFinish(state)
            if (target == Phase.FINISHING) return

            session.parkingSpotSaid("지하 1층", state)
        }

        fun finishFull(spot: String = "지하 1층"): HandoverCard {
            update(state.copy(gear = Gear.P))
            session.startFinish(state)
            session.parkingSpotSaid(spot, state)
            return requireNotNull(session.card)
        }
    }

    companion object {
        private fun baseState(): CarState = CarState(
            gear = Gear.P,
            driverSeated = false,
            passengerSeated = false,
            rearSeated = false,
            driverDoorOpen = false,
            fuelPercent = 70,
            odometerKm = 1000f,
            lowBeamOn = false,
        )
    }
}
