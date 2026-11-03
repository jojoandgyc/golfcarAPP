package com.example.followingcar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Local UI fixtures only. No ViewModel, SSH, serial, lifecycle or motor operations. */
class ParkingCurrentEditorTest {
    @get:Rule val compose = createComposeRule()
    private val left = MotorConfigurationField.LEFT_PARKING_CURRENT
    private val right = MotorConfigurationField.RIGHT_PARKING_CURRENT
    private val maximum = MotorConfigurationField.MAX_OUTPUT_CURRENT

    private fun snapshot() = MotorConfigurationSnapshot("parking-read-1", 1_700_000_000_000L,
        mapOf(left to 425L, right to 550L, maximum to 1500L,
            MotorConfigurationField.MAX_FORWARD_SPEED to 500L), writeAllowed = true,
        leftParkingCurrentRaw = 425L, rightParkingCurrentRaw = 550L)
    private fun ready() = MotorConfigurationUiState(snapshot = snapshot())
    private fun idle() = BoardUiState(connected = true)

    private fun content(board: () -> BoardUiState = { idle() },
        config: () -> MotorConfigurationUiState = { ready() },
        read: () -> Unit = {}, apply: (MotorConfigurationWritePlan) -> Unit = {}) {
        compose.setContent { MaterialTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ParkingCurrentEditor(board(), config(), read, apply)
            }
        } }
    }

    @Test fun unreadEditorNeverInventsValuesOrReadsAutomatically() {
        var reads = 0
        var writes = 0
        content(config = { MotorConfigurationUiState() }, read = { reads++ }, apply = { writes++ })
        compose.onNodeWithTag("parkingCurrentLeftInput").assertIsNotEnabled()
        compose.onNodeWithTag("parkingCurrentRightInput").assertIsNotEnabled()
        assertEquals("", input("Left"))
        assertEquals("", input("Right"))
        compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, writes) }
        compose.onNodeWithTag("parkingCurrentRead").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(0, writes) }
    }

    @Test fun independentOneAmpStepsShowLiveChangesAndSaveExactlyOnce() {
        val plans = mutableListOf<MotorConfigurationWritePlan>()
        val config = ready()
        content(config = { config }, apply = { plans += it })
        compose.onNodeWithTag("parkingCurrentLeftIncrease").performClick()
        assertEquals("5.25", input("Left"))
        assertEquals("5.5", input("Right"))
        compose.onNodeWithTag("parkingCurrentRightDecrease").performClick()
        assertEquals("4.5", input("Right"))
        compose.onNodeWithTag("parkingCurrentChange_${left.id}").assertTextEquals("左：4.25 A → 5.25 A")
        compose.onNodeWithTag("parkingCurrentChange_${right.id}").assertTextEquals("右：5.5 A → 4.5 A")
        compose.onNodeWithTag("parkingCurrentReview").assertDoesNotExist()
        compose.onNodeWithTag("parkingCurrentConfirm").assertDoesNotExist()
        compose.runOnIdle { assertTrue(plans.isEmpty()); assertTrue(config.draftValues.isEmpty()) }
        compose.onNodeWithTag("parkingCurrentSave").performScrollTo().performClick()
        compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, plans.size)
            assertEquals(config.snapshot!!.snapshotId, plans.single().snapshotId)
            assertEquals(MotorConfigurationWriteMode.PERSISTENT, plans.single().writeMode)
            assertEquals(mapOf(left to MotorConfigurationChange(left, 425, 525),
                right to MotorConfigurationChange(right, 550, 450)), plans.single().changes.associateBy { it.field })
        }
    }

    @Test fun activeManualFollowOrPendingControlDisablesReadingAndEditing() {
        var reads = 0
        var writes = 0
        val board = mutableStateOf(idle())
        content(board = { board.value }, read = { reads++ }, apply = { writes++ })
        compose.onNodeWithTag("parkingCurrentLeftIncrease").performClick()
        compose.onNodeWithTag("parkingCurrentSave").assertIsEnabled()
        listOf(idle().copy(driveEnabled = true), idle().copy(driveStarting = true),
            idle().copy(driveStopping = true), idle().copy(followActive = true),
            idle().copy(followStopping = true), idle().copy(followUncertain = true),
            idle().copy(connected = false), idle().copy(busy = true)).forEach { blocked ->
            compose.runOnIdle { board.value = blocked }
            compose.onNodeWithTag("parkingCurrentRead").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("parkingCurrentLeftInput").assertIsNotEnabled()
            compose.onNodeWithTag("parkingCurrentRightInput").assertIsNotEnabled()
            compose.onNodeWithTag("parkingCurrentLeftIncrease").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
        }
        compose.runOnIdle { board.value = idle().copy(driveEnabled = true) }
        compose.onNodeWithTag("parkingCurrentEditorStatus").assertTextContains("先用页底按钮停用手动控制", substring = true)
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, writes) }
    }

    @Test fun decimalAndOutputLimitValidationRejectBadValuesAndZeroStillRequiresExplicitSave() {
        var writes = 0
        content(apply = { writes++ })
        compose.onNodeWithTag("parkingCurrentLeftInput").performTextReplacement("0")
        compose.onNodeWithTag("parkingCurrentZeroHint").assertTextContains("不能开启", substring = true)
        compose.onNodeWithTag("parkingCurrentSave").assertIsEnabled()
        listOf("15.01", "60.01", "-1", "NaN", "1.001", "").forEach { invalid ->
            compose.onNodeWithTag("parkingCurrentLeftInput").performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithTag("parkingCurrentValidation").assertExists()
            compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
        }
        compose.onNodeWithTag("parkingCurrentLeftInput").performScrollTo().performTextReplacement("0.01")
        compose.onNodeWithTag("parkingCurrentSave").assertIsEnabled()
        compose.onNodeWithTag("parkingCurrentLeftDecrease").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun unrelatedMotorDraftsAndTemporaryModeNeverEnterParkingPlan() {
        val drafts = mapOf(maximum to "60", MotorConfigurationField.MAX_FORWARD_SPEED to "700")
        val config = ready().copy(draftValues = drafts, writeMode = MotorConfigurationWriteMode.TEMPORARY)
        val plans = mutableListOf<MotorConfigurationWritePlan>()
        content(config = { config }, apply = { plans += it })
        compose.onNodeWithTag("parkingCurrentLeftInput").performTextReplacement("16")
        compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled()
        compose.onNodeWithTag("parkingCurrentLeftInput").performTextReplacement("14")
        compose.onNodeWithTag("parkingCurrentSave").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf(MotorConfigurationChange(left, 425L, 1400L)), plans.single().changes)
            assertEquals(MotorConfigurationWriteMode.PERSISTENT, plans.single().writeMode)
            assertEquals(drafts, config.draftValues)
            assertEquals(MotorConfigurationWriteMode.TEMPORARY, config.writeMode)
        }
    }

    @Test fun liveChangesUpdateWithoutWritingAndConnectionOrControlLossDisablesSave() {
        var writes = 0
        val board = mutableStateOf(idle())
        content(board = { board.value }, apply = { writes++ })
        compose.onNodeWithTag("parkingCurrentLeftIncrease").performClick()
        compose.onNodeWithTag("parkingCurrentChange_${left.id}").assertTextEquals("左：4.25 A → 5.25 A")
        compose.onNodeWithTag("parkingCurrentLeftInput").performScrollTo().performTextReplacement("6.25")
        compose.onNodeWithTag("parkingCurrentChange_${left.id}").assertTextEquals("左：4.25 A → 6.25 A")
        compose.runOnIdle { board.value = idle().copy(connected = false) }
        compose.onNodeWithTag("parkingCurrentChanges").assertDoesNotExist()
        compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
        compose.runOnIdle { board.value = idle() }
        compose.onNodeWithTag("parkingCurrentSave").assertIsEnabled()
        compose.runOnIdle { assertEquals(0, writes) }
        compose.runOnIdle { board.value = idle().copy(driveEnabled = true) }
        compose.onNodeWithTag("parkingCurrentChanges").assertDoesNotExist()
        compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun missingOrInvalidatedPlanCannotSaveEvenWithAChangedInput() {
        var writes = 0
        val baseline = ready()
        val config = mutableStateOf(baseline)
        content(config = { config.value }, apply = { writes++ })
        compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("parkingCurrentLeftIncrease").performClick()
        compose.onNodeWithTag("parkingCurrentSave").assertIsEnabled()
        listOf(baseline.copy(requiresRead = true), baseline.copy(reading = true),
            baseline.copy(applying = true),
            baseline.copy(snapshot = snapshot().copy(writeAllowed = false)),
            baseline.copy(snapshot = snapshot().copy(values = snapshot().values - maximum))).forEach { invalid ->
            compose.runOnIdle { config.value = invalid }
            compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
            compose.onNodeWithTag("parkingCurrentChanges").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun freshReadResetsOnlyLocalDraftToItsActualIndependentValues() {
        var reads = 0
        val config = mutableStateOf(ready().copy(draftValues = mapOf(maximum to "20")))
        content(config = { config.value }, read = { reads++ })
        compose.onNodeWithTag("parkingCurrentLeftIncrease").performClick()
        compose.onNodeWithTag("parkingCurrentChanges").assertExists()
        compose.onNodeWithTag("parkingCurrentRead").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, reads)
            config.value = config.value.copy(snapshot = snapshot().copy(snapshotId = "parking-read-2",
                readAtMillis = 1_700_000_001_000L,
                values = snapshot().values + mapOf(left to 600L, right to 725L)))
        }
        assertEquals("6", input("Left"))
        assertEquals("7.25", input("Right"))
        compose.onNodeWithTag("parkingCurrentChanges").assertDoesNotExist()
        compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(mapOf(maximum to "20"), config.value.draftValues) }
    }

    @Test fun partialResultsArePerSideAndNeverRetryWithoutReading() {
        var reads = 0
        var writes = 0
        val config = ready().copy(requiresRead = true, error = "右侧回读未确认", writeResults = listOf(
            MotorConfigurationWriteResult(left, 600L, MotorConfigurationWriteOutcome.VERIFIED, 600L),
            MotorConfigurationWriteResult(right, 700L, MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN),
            MotorConfigurationWriteResult(maximum, 1500L, MotorConfigurationWriteOutcome.VERIFIED, 1500L)))
        content(config = { config }, read = { reads++ }, apply = { writes++ })
        compose.onNodeWithTag("parkingCurrentResult_${left.id}").assertTextContains("已保存并回读确认", substring = true)
        compose.onNodeWithTag("parkingCurrentResult_${right.id}").assertTextContains("结果未知，可能已写入", substring = true)
        compose.onNodeWithTag("parkingCurrentResult_${maximum.id}").assertDoesNotExist()
        compose.onNodeWithTag("parkingCurrentResultHint").assertTextEquals("未自动重试。请先重新读取核对实际值。")
        compose.onNodeWithTag("parkingCurrentSave").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, writes) }
        compose.onNodeWithTag("parkingCurrentRead").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(0, writes) }
    }

    @Test fun compactViewportShowsReadInputsLiveChangesAndSaveWithoutScrolling() {
        compose.setContent { MaterialTheme {
            Box(Modifier.requiredSize(360.dp, 440.dp).clipToBounds()) {
                ParkingCurrentEditor(idle(), ready(), {}, {}, Modifier.fillMaxSize())
            }
        } }
        compose.onNodeWithTag("parkingCurrentRead").assertIsDisplayed()
        compose.onNodeWithTag("parkingCurrentLeftInput").assertIsDisplayed()
        compose.onNodeWithTag("parkingCurrentRightInput").assertIsDisplayed()
        compose.onNodeWithTag("parkingCurrentLeftIncrease").performClick()
        compose.onNodeWithTag("parkingCurrentRightIncrease").performClick()
        compose.onNodeWithTag("parkingCurrentChange_${left.id}").assertIsDisplayed()
        compose.onNodeWithTag("parkingCurrentChange_${right.id}").assertIsDisplayed()
        compose.onNodeWithTag("parkingCurrentSave").assertIsDisplayed()
    }

    private fun input(side: String): String = compose.onNodeWithTag("parkingCurrent${side}Input")
        .fetchSemanticsNode().config[SemanticsProperties.EditableText].text
}
