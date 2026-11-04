package com.example.followingcar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Controlled UI fixtures only; no ViewModel, network, serial or motor operations. */
class ParkingPageTest {
    @get:Rule val compose = createComposeRule()

    private fun content(state: MutableState<ParkingPageState>, onRead: () -> Unit = {},
        onSave: () -> Unit = {}) {
        compose.setContent { MaterialTheme {
            ParkingPage(state.value, onRead,
                { state.value = state.value.copy(draftLeft = it) },
                { state.value = state.value.copy(draftRight = it) }, onSave)
        } }
    }

    private fun input(side: String) = compose.onNodeWithTag("parkingPage${side}Input")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test fun offlineDraftsAndActionsRemainAvailableWithoutReadingFirst() {
        val state = mutableStateOf(ParkingPageState())
        var reads = 0
        var saves = 0
        content(state, { reads++ }, { saves++ })
        compose.onNodeWithTag("parkingPageConnection").assertTextEquals("未连接")
        compose.onNodeWithTag("parkingPageLeftInput").assertIsEnabled().performTextReplacement("4.25")
        compose.onNodeWithTag("parkingPageRightInput").assertIsEnabled().performTextReplacement("5.5")
        compose.onNodeWithTag("parkingPageReadback").assertTextContains("左 — / 右 —", substring = true)
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, saves) }
        compose.onNodeWithTag("parkingPageSave").assertIsEnabled().performClick()
        compose.onNodeWithTag("parkingPageRefresh").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(1, saves) }
        assertEquals("4.25", input("Left"))
        assertEquals("5.5", input("Right"))
    }

    @Test fun currentReadbackDeterminesOffOnAndPerSideStateWithoutAModeSwitch() {
        val state = mutableStateOf(ParkingPageState(mode = true, valuesCurrent = true))
        content(state)
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("驻车状态待确认")
        compose.onNodeWithTag("parkingPageEnable").assertDoesNotExist()
        compose.onNodeWithTag("parkingPageDisable").assertDoesNotExist()
        compose.onNodeWithTag("parkingPageModeSource").assertDoesNotExist()
        compose.onNodeWithTag("parkingPageCurrentRule").assertTextContains("0 A 关闭，正值开启", substring = true)
        compose.runOnIdle { state.value = state.value.copy(leftCurrentAmps = 0.0, rightCurrentAmps = 0.0) }
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("驻车关闭（两侧 0 A）")
        compose.runOnIdle { state.value = state.value.copy(mode = false, leftCurrentAmps = 2.0, rightCurrentAmps = 3.0) }
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("驻车开启（两侧为正值）")
        compose.runOnIdle { state.value = state.value.copy(leftCurrentAmps = 0.0) }
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("分侧状态：左关闭 · 右开启")
        compose.runOnIdle { state.value = state.value.copy(leftCurrentAmps = 2.0, rightCurrentAmps = 0.0) }
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("分侧状态：左开启 · 右关闭")
        compose.runOnIdle { state.value = state.value.copy(rightCurrentAmps = null) }
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("分侧状态：左开启 · 右待确认")
    }

    @Test fun zeroShortcutOnlyFillsDraftsAndRequiresExplicitSaveBeforeStateChanges() {
        val state = mutableStateOf(ParkingPageState(leftCurrentAmps = 2.0, rightCurrentAmps = 3.0,
            draftLeft = "2", draftRight = "3", valuesCurrent = true))
        var saves = 0
        content(state, onSave = { saves++ })
        compose.onNodeWithTag("parkingPageSetZero").performClick()
        assertEquals("0", input("Left")); assertEquals("0", input("Right"))
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("驻车开启（两侧为正值）")
        compose.onNodeWithTag("parkingPageReadback").assertTextContains("左 2 / 右 3 A", substring = true)
        compose.runOnIdle { assertEquals(0, saves) }
        compose.onNodeWithTag("parkingPageSave").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, saves) }
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("驻车开启（两侧为正值）")
        compose.runOnIdle { state.value = state.value.copy(leftCurrentAmps = 0.0, rightCurrentAmps = 0.0) }
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("驻车关闭（两侧 0 A）")
    }

    @Test fun historicalValuesAreLabelledAndDraftChangesNeverPretendToBeReadback() {
        val state = mutableStateOf(ParkingPageState(leftCurrentAmps = 0.0, rightCurrentAmps = 0.0,
            draftLeft = "", draftRight = "", valuesCurrent = false))
        content(state)
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("上次读取：驻车关闭（两侧 0 A）")
        compose.onNodeWithTag("parkingPageReadback").assertTextContains("上次读取（非实时）", substring = true)
        input("Left").also { assertEquals("", it) }
        input("Right").also { assertEquals("", it) }
        compose.onNodeWithTag("parkingPageLeftInput").performTextReplacement("2")
        compose.onNodeWithTag("parkingPageRightInput").performTextReplacement("3")
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("上次读取：驻车关闭（两侧 0 A）")
        compose.onNodeWithTag("parkingPageReadback").assertTextContains("左 0 / 右 0 A", substring = true)
    }

    @Test fun independentStepsOnlyChangeDraftsAndNeverGuessReadbackOrApply() {
        val state = mutableStateOf(ParkingPageState(draftLeft = "4.25", draftRight = "5.5",
            leftCurrentAmps = 2.0, rightCurrentAmps = 3.0, maxCurrentAmps = 15.0))
        var saves = 0
        content(state, onSave = { saves++ })
        compose.onNodeWithTag("parkingPageLeftIncrease").performClick()
        compose.onNodeWithTag("parkingPageRightDecrease").performClick()
        assertEquals("5.25", input("Left"))
        assertEquals("4.5", input("Right"))
        compose.onNodeWithTag("parkingPageReadback").assertTextContains("左 2 / 右 3 A", substring = true)
        compose.runOnIdle { assertEquals(0, saves); state.value = state.value.copy(draftLeft = "", draftRight = "") }
        assertEquals("", input("Left"))
        compose.onNodeWithTag("parkingPageLeftIncrease").performClick()
        assertEquals("1", input("Left"))
        assertEquals("", input("Right"))
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun saveOnlyNeedsFiniteZeroToSixtyDraftsAndNeverSilentlyClampsTypedValues() {
        val state = mutableStateOf(ParkingPageState(draftLeft = "2", draftRight = "3", maxCurrentAmps = 5.0))
        var saves = 0
        content(state, onSave = { saves++ })
        listOf("0", "60", "6", "2.25", " 1.5 ").forEach { valid ->
            compose.onNodeWithTag("parkingPageLeftInput").performTextReplacement(valid)
            compose.onNodeWithTag("parkingPageSave").assertIsEnabled()
            assertEquals(valid, input("Left"))
        }
        listOf("", "NaN", "Infinity", "-0.01", "60.01", "invalid").forEach { invalid ->
            compose.onNodeWithTag("parkingPageLeftInput").performTextReplacement(invalid)
            compose.onNodeWithTag("parkingPageValidation").assertExists()
            compose.onNodeWithTag("parkingPageSave").assertIsNotEnabled().performClick()
            assertEquals(invalid, input("Left"))
        }
        compose.onNodeWithTag("parkingPageLeftInput").performTextReplacement("2")
        compose.onNodeWithTag("parkingPageRightInput").performTextReplacement("61")
        compose.onNodeWithTag("parkingPageSave").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun oneSaveSubmitsOnceAndBusyBlocksFurtherActionsButNotDraftEditing() {
        val state = mutableStateOf(ParkingPageState(draftLeft = "2", draftRight = "3"))
        var reads = 0
        var saves = 0
        content(state, { reads++ }, { saves++; state.value = state.value.copy(busy = true) })
        compose.onNodeWithTag("parkingPageSave").performClick()
        compose.onNodeWithTag("parkingPageSave").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("parkingPageRefresh").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("parkingPageLeftInput").assertIsEnabled().performTextReplacement("4")
        compose.onNodeWithTag("parkingPageRightIncrease").assertIsEnabled().performClick()
        assertEquals("4", input("Left"))
        assertEquals("4", input("Right"))
        compose.runOnIdle { assertEquals(1, saves); assertEquals(0, reads) }
        compose.runOnIdle { state.value = state.value.copy(busy = false, message = "已保存并回读") }
        compose.onNodeWithTag("parkingPageSave").assertIsEnabled()
        compose.runOnIdle { assertEquals(1, saves) }
    }

    @Test fun resultAndErrorAreVisibleWithoutInventingReadbackOrChangingDrafts() {
        val state = mutableStateOf(ParkingPageState(mode = null, draftLeft = "2", draftRight = "3",
            message = "左侧已回读 2 A", error = "右侧回执未确认，请刷新",
            feedbackText = "最近轮速：左 7 / 右 -3 RPM"))
        var saves = 0
        content(state, onSave = { saves++ })
        compose.onNodeWithTag("parkingPageMessage").assertTextEquals("左侧已回读 2 A")
        compose.onNodeWithTag("parkingPageError").assertTextEquals("右侧回执未确认，请刷新")
        compose.onNodeWithTag("parkingPageFeedback").assertTextEquals("最近轮速：左 7 / 右 -3 RPM")
        compose.onNodeWithTag("parkingPageMode").assertTextEquals("驻车状态待确认")
        assertEquals("2", input("Left"))
        assertEquals("3", input("Right"))
        compose.runOnIdle { assertEquals(0, saves) }
    }

    @Test fun fixedSmallViewportShowsModeCurrentsSaveAndResultsWithoutScrolling() {
        compose.setContent { MaterialTheme {
            Box(Modifier.requiredSize(360.dp, 580.dp).clipToBounds()) {
                ParkingPage(ParkingPageState(mode = null, draftLeft = "2", draftRight = "3", busy = true,
                    leftCurrentAmps = 2.0, rightCurrentAmps = 3.0, maxCurrentAmps = 15.0,
                    message = "左侧已回读确认 2 A；右侧结果待核对，输入草稿保留，不会自动重试设置",
                    error = "设置结果未全部确认，请读取核对各侧电流；不会自动重试或覆盖另一侧",
                    feedbackText = "轮速观测：左 7 / 右 -3 RPM；电流配置状态不代表物理停稳"), {}, {}, {}, {})
            }
        } }
        listOf("Refresh", "CurrentRule", "SetZero", "LeftInput", "RightInput", "Readback", "Save",
            "Message", "Error", "Feedback", "Progress").forEach { tag ->
            compose.onNodeWithTag("parkingPage$tag").assertIsDisplayed()
        }
    }
}
