package com.example.followingcar

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fake snapshots and callbacks only: this test never opens SSH or a serial device. */
@RunWith(AndroidJUnit4::class)
class MotorConfigurationPageTest {
    @get:Rule val compose = createComposeRule()
    private fun snapshot() = MotorConfigurationSnapshot("read-a", 1_700_000_000_000,
        mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to 2000L,
            MotorConfigurationField.MAX_FORWARD_SPEED to 500L,
            MotorConfigurationField.MAX_REVERSE_SPEED to 400L,
            MotorConfigurationField.CLOSED_LOOP_ACCELERATION to 100L,
            MotorConfigurationField.CLOSED_LOOP_DECELERATION to 200L),
        deviceInfo = listOf(MotorConfigurationDetail("控制器", "双轴控制器")),
        leftMotorInfo = listOf(MotorConfigurationDetail("转速", "0 RPM")),
        rightMotorInfo = listOf(MotorConfigurationDetail("转速", "未知")),
        writeAllowed = true, leftParkingCurrentRaw = 500, rightParkingCurrentRaw = 600)

    private fun content(state: () -> MotorConfigurationUiState, connected: () -> Boolean = { true },
        allowed: () -> Boolean = { true }, read: () -> Unit = {},
        edit: (MotorConfigurationField, String) -> Unit = { _, _ -> },
        apply: (MotorConfigurationWritePlan) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                MotorConfigurationPage(state(), connected(), allowed(), "控制运行或退出尚未确认",
                    onRead = read, onEdit = edit, onDiscard = {}, onApply = apply, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @Test fun unreadConfigurationNeverWritesAndReadRequiresExplicitClick() {
        var reads = 0
        var writes = 0
        content({ MotorConfigurationUiState() }, read = { reads++ }, apply = { writes++ })
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, writes) }
        compose.onNodeWithTag("motorConfigurationField_max_output_current").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationRead").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(0, writes) }
    }

    @Test fun changedValuesNeedInlinePreviewThenExplicitConfirmation() {
        val state = mutableStateOf(MotorConfigurationUiState(snapshot()))
        var written: MotorConfigurationWritePlan? = null
        content({ state.value }, edit = { field, value ->
            state.value = state.value.copy(draftValues = state.value.draftValues + (field to value))
        }, apply = { written = it })
        captureIfRequested("ready.png")
        compose.onNodeWithTag("motorConfigurationField_max_output_current").performScrollTo().performTextReplacement("12.5")
        compose.onNodeWithTag("motorConfigurationReview").assertIsEnabled().performClick()
        compose.onNodeWithTag("motorConfigurationPreview").assertExists()
        compose.runOnIdle { assertNull(written) }
        captureIfRequested("preview.png")
        compose.onNodeWithTag("motorConfigurationConfirmApply").performClick()
        compose.runOnIdle {
            assertEquals("read-a", written?.snapshotId)
            assertEquals(listOf(MotorConfigurationChange(MotorConfigurationField.MAX_OUTPUT_CURRENT, 2000, 1250)), written?.changes)
        }
    }

    @Test fun controlLockInvalidatesOpenPreviewAndDisablesSerialAccess() {
        val state = MotorConfigurationUiState(snapshot(), mapOf(MotorConfigurationField.MAX_FORWARD_SPEED to "700"))
        val allowed = mutableStateOf(true)
        var writes = 0
        content({ state }, allowed = { allowed.value }, apply = { writes++ })
        compose.onNodeWithTag("motorConfigurationReview").performClick()
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertIsEnabled()
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertDoesNotExist()
        compose.onNodeWithTag("motorConfigurationRead").assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test fun invalidRangeAndBoardGateBothPreventApplyingChanges() {
        val state = mutableStateOf(MotorConfigurationUiState(snapshot(),
            mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to "60.01")))
        content({ state.value })
        compose.onNodeWithTag("motorConfigurationValidation_max_output_current").performScrollTo().assertExists()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = MotorConfigurationUiState(snapshot().copy(writeAllowed = false,
                writeBlockedReason = "读取时观测到电机仍在运动"), mapOf(MotorConfigurationField.MAX_FORWARD_SPEED to "700"))
        }
        compose.onNodeWithTag("motorConfigurationWriteBlocked").performScrollTo().assertExists()
        compose.onNodeWithTag("motorConfigurationRead").assertIsEnabled()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
    }

    @Test fun partialAndUnknownWriteResultsRemainVisibleWithoutSnapshotAndRequireRead() {
        val state = MotorConfigurationUiState(requiresRead = true, writeResults = listOf(
            MotorConfigurationWriteResult(MotorConfigurationField.MAX_OUTPUT_CURRENT, 2000,
                MotorConfigurationWriteOutcome.VERIFIED, 2000),
            MotorConfigurationWriteResult(MotorConfigurationField.MAX_FORWARD_SPEED, 700,
                MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN),
            MotorConfigurationWriteResult(MotorConfigurationField.MAX_REVERSE_SPEED, 400,
                MotorConfigurationWriteOutcome.NOT_ATTEMPTED)))
        content({ state })
        compose.onNodeWithText("最大输出电流：回读一致").performScrollTo().assertExists()
        compose.onNodeWithText("正转速度上限：写入结果未知，可能已更改").performScrollTo().assertExists()
        compose.onNodeWithText("反转速度上限：尚未尝试写入").performScrollTo().assertExists()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationRead").assertIsEnabled()
    }

    @Test fun newSnapshotOrDisconnectInvalidatesPriorPreview() {
        val state = mutableStateOf(MotorConfigurationUiState(snapshot(),
            mapOf(MotorConfigurationField.MAX_FORWARD_SPEED to "700")))
        val connected = mutableStateOf(true)
        content({ state.value }, connected = { connected.value })
        compose.onNodeWithTag("motorConfigurationReview").performClick()
        compose.runOnIdle { state.value = state.value.copy(snapshot = snapshot().copy(snapshotId = "read-b")) }
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertDoesNotExist()
        compose.onNodeWithTag("motorConfigurationReview").performClick()
        compose.runOnIdle { connected.value = false }
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertDoesNotExist()
        compose.onNodeWithTag("motorConfigurationRead").assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
    }

    /** Opt-in visual artifacts show only this test's fixed fake controller values. */
    private fun captureIfRequested(filename: String) {
        if (InstrumentationRegistry.getArguments().getString("captureMotorConfiguration") != "true") return
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        // IME closing is a system animation, outside Compose's test clock.
        Thread.sleep(300L)
        val directory = File(requireNotNull(instrumentation.targetContext.getExternalFilesDir(null)), "motor-configuration")
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create visual-review directory" }
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Unable to capture visual-review screenshot" }
        try {
            File(directory, filename).outputStream().use { output ->
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Unable to save visual-review PNG" }
            }
        } finally {
            screenshot.recycle()
        }
    }
}
