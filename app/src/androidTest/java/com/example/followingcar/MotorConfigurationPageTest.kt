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
    private fun snapshot() = MotorConfigurationSnapshot("a".repeat(64), 1_700_000_000_000,
        MotorConfigurationField.entries.associateWith { it.choices.keys.firstOrNull() ?: it.minRaw } +
        mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to 2000L,
            MotorConfigurationField.MAX_FORWARD_SPEED to 500L,
            MotorConfigurationField.MAX_REVERSE_SPEED to 400L,
            MotorConfigurationField.CLOSED_LOOP_ACCELERATION to 100L,
            MotorConfigurationField.CLOSED_LOOP_DECELERATION to 200L,
            MotorConfigurationField.LEFT_PARKING_CURRENT to 500L,
            MotorConfigurationField.RIGHT_PARKING_CURRENT to 600L),
        deviceInfo = listOf(MotorConfigurationDetail("控制器", "双轴控制器")),
        leftMotorInfo = listOf(MotorConfigurationDetail("转速", "0 RPM")),
        rightMotorInfo = listOf(MotorConfigurationDetail("转速", "未知")),
        writeAllowed = true, leftParkingCurrentRaw = 500, rightParkingCurrentRaw = 600,
        stopAllowed = true)

    private fun content(state: () -> MotorConfigurationUiState, connected: () -> Boolean = { true },
        allowed: () -> Boolean = { true }, read: () -> Unit = {},
        edit: (MotorConfigurationField, String) -> Unit = { _, _ -> },
        apply: (MotorConfigurationWritePlan) -> Unit = {},
        mode: ((MotorConfigurationWriteMode) -> Unit)? = null,
        connection: ((MotorSerialSettings) -> Unit)? = null,
        action: ((MotorActionPlan) -> Unit)? = null,
        unconfirmed: () -> Boolean = { false },
        cancellable: () -> Boolean = { false },
        cancel: (() -> Unit)? = null) {
        compose.setContent {
            MaterialTheme {
                MotorConfigurationPage(state(), connected(), allowed(), "控制运行或退出尚未确认",
                    onRead = read, onEdit = edit, onDiscard = {}, onApply = apply, modifier = Modifier.fillMaxSize(),
                    onWriteModeChange = mode, onConnectionChange = connection, onAction = action,
                    actionUnconfirmed = unconfirmed(), actionCancellationAllowed = cancellable(), onCancelAction = cancel)
            }
        }
    }

    private fun tab(name: String) = compose.onNodeWithTag("motorConfigurationTab_$name").performScrollTo().performClick()

    @Test fun unreadConfigurationNeverWritesAndReadRequiresExplicitClick() {
        var reads = 0
        var writes = 0
        content({ MotorConfigurationUiState() }, read = { reads++ }, apply = { writes++ })
        compose.runOnIdle { assertEquals(0, reads); assertEquals(0, writes) }
        tab("MOTOR")
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
        tab("MOTOR")
        compose.onNodeWithTag("motorConfigurationField_max_output_current").performScrollTo().performTextReplacement("12.5")
        compose.onNodeWithTag("motorConfigurationReview").assertIsEnabled().performClick()
        compose.onNodeWithTag("motorConfigurationPreview").assertExists()
        compose.runOnIdle { assertNull(written) }
        captureIfRequested("preview.png")
        compose.onNodeWithTag("motorConfigurationConfirmApply").performClick()
        compose.runOnIdle {
            assertEquals("a".repeat(64), written?.snapshotId)
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
        tab("MOTOR")
        compose.onNodeWithTag("motorConfigurationValidation_max_output_current").performScrollTo().assertExists()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
        compose.runOnIdle {
            state.value = MotorConfigurationUiState(snapshot().copy(writeAllowed = false,
                writeBlockedReason = "读取时观测到电机仍在运动"), mapOf(MotorConfigurationField.MAX_FORWARD_SPEED to "700"))
        }
        compose.onNodeWithTag("motorConfigurationWriteBlocked").assertExists()
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
        compose.onNodeWithText("${MotorConfigurationField.MAX_OUTPUT_CURRENT.label}：回读一致").performScrollTo().assertExists()
        compose.onNodeWithText("${MotorConfigurationField.MAX_FORWARD_SPEED.label}：写入结果未知，可能已更改").performScrollTo().assertExists()
        compose.onNodeWithText("${MotorConfigurationField.MAX_REVERSE_SPEED.label}：尚未尝试写入").performScrollTo().assertExists()
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

    @Test fun categoriesAndRegisterSearchExposeSignedPhaseAndPidFieldsWithoutIo() {
        var calls = 0
        content({ MotorConfigurationUiState(snapshot()) }, read = { calls++ }, edit = { _, _ -> calls++ },
            apply = { calls++ }, action = { calls++ })
        compose.onNodeWithTag("motorConfigurationField_max_output_current").assertDoesNotExist()
        tab("MOTOR")
        compose.onNodeWithTag("motorConfigurationSearch").performScrollTo().performTextReplacement("0x006E")
        compose.onNodeWithTag("motorConfigurationField_right_foc_compensation_angle").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("motorConfigurationField_max_output_current").assertDoesNotExist()
        compose.onNodeWithTag("motorConfigurationSearch").performScrollTo().performTextReplacement("0x0069")
        compose.onNodeWithTag("motorConfigurationField_right_phase_1_2").performScrollTo().assertIsEnabled()
        tab("PID")
        compose.onNodeWithTag("motorConfigurationSearch").performScrollTo().performTextReplacement("0x0104")
        compose.onNodeWithTag("motorConfigurationField_pid_hall_parking_speed_p").performScrollTo().assertIsEnabled()
        compose.runOnIdle { assertEquals(0, calls) }
    }

    @Test fun enumSelectionOnlyChangesDraftAndChangingWriteModeInvalidatesPreview() {
        val state = mutableStateOf(MotorConfigurationUiState(snapshot()))
        var writes = 0
        content({ state.value }, edit = { field, value -> state.value = state.value.copy(draftValues = mapOf(field to value)) },
            mode = { state.value = state.value.copy(writeMode = it) }, apply = { writes++ })
        tab("PROTECTION")
        compose.onNodeWithTag("motorConfigurationSearch").performScrollTo().performTextReplacement("电压保护")
        compose.onNodeWithTag("motorConfigurationField_voltage_protection_enabled").performScrollTo().performClick()
        compose.onNodeWithTag("motorConfigurationField_voltage_protection_enabled_option_1").performClick()
        compose.runOnIdle { assertEquals("1", state.value.draftValues[MotorConfigurationField.VOLTAGE_PROTECTION_ENABLED]); assertEquals(0, writes) }
        compose.onNodeWithTag("motorConfigurationReview").performClick()
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertIsEnabled()
        compose.onNodeWithTag("motorConfigurationWriteMode_TEMPORARY").performScrollTo().performClick()
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertDoesNotExist()
        compose.onNodeWithTag("motorConfigurationReview").performClick()
        compose.onNodeWithText("确认临时写入").assertExists()
        compose.runOnIdle { assertEquals(MotorConfigurationWriteMode.TEMPORARY, state.value.writeMode); assertEquals(0, writes) }
    }

    @Test fun actionNeedsItsOwnPreviewAndSingleExplicitConfirmation() {
        var requested: MotorActionPlan? = null
        content({ MotorConfigurationUiState(snapshot()) }, action = { requested = it })
        tab("OPERATIONS")
        compose.onNodeWithTag("motorActionReview").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("motorActionPreview").performScrollTo().assertExists()
        compose.runOnIdle { assertNull(requested) }
        compose.onNodeWithTag("motorActionConfirm").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(MotorAction.RIGHT_STOP, requested?.action); assertEquals(0L, requested?.value) }
        compose.onNodeWithTag("motorActionConfirm").assertDoesNotExist()
    }

    @Test fun controlLockBusyAndDisconnectInvalidateActionPreview() {
        val state = mutableStateOf(MotorConfigurationUiState(snapshot()))
        val allowed = mutableStateOf(true)
        val connected = mutableStateOf(true)
        var actions = 0
        content({ state.value }, connected = { connected.value }, allowed = { allowed.value }, action = { actions++ })
        tab("OPERATIONS")
        compose.onNodeWithTag("motorActionReview").performScrollTo().performClick()
        compose.runOnIdle { allowed.value = false }
        compose.onNodeWithTag("motorActionConfirm").assertDoesNotExist()
        compose.onNodeWithTag("motorActionReview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { allowed.value = true }
        compose.onNodeWithTag("motorActionReview").performScrollTo().performClick()
        compose.runOnIdle { state.value = state.value.copy(actioning = true) }
        compose.onNodeWithTag("motorActionConfirm").assertDoesNotExist()
        compose.onNodeWithTag("motorConfigurationRead").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(actioning = false) }
        compose.onNodeWithTag("motorActionReview").performScrollTo().performClick()
        compose.runOnIdle { connected.value = false }
        compose.onNodeWithTag("motorActionConfirm").assertDoesNotExist()
        compose.onNodeWithTag("motorActionReview").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun stopAndLearningCancelRemainAvailableUnderStopOnlyGate() {
        val state = MotorConfigurationUiState(snapshot().copy(writeAllowed = false, stopAllowed = true,
            writeBlockedReason = "当前只允许停止或取消"))
        var requested: MotorActionPlan? = null
        content({ state }, action = { requested = it })
        tab("OPERATIONS")
        compose.onNodeWithTag("motorActionReview").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("motorActionSelector").performScrollTo().performClick()
        compose.onNodeWithTag("motorActionSelector_option_phase_learning").performScrollTo().performClick()
        compose.onNodeWithTag("motorActionDuration").assertDoesNotExist()
        compose.onNodeWithTag("motorActionReview").performScrollTo().performClick()
        compose.onNodeWithTag("motorActionConfirm").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(MotorAction.PHASE_LEARNING, requested?.action)
            assertEquals(0L, requested?.value)
            assertEquals(0, requested?.durationMs)
        }
        compose.onNodeWithTag("motorActionValue").performScrollTo().performClick()
        compose.onNodeWithTag("motorActionValue_option_1").performClick()
        compose.onNodeWithTag("motorActionReview").performScrollTo().assertIsNotEnabled()
    }

    @Test fun serialConnectionSettingsRequireExplicitApplyAndNeverReadOrWrite() {
        var calls = 0
        var changed: MotorSerialSettings? = null
        content({ MotorConfigurationUiState(snapshot()) }, read = { calls++ }, apply = { calls++ }, action = { calls++ },
            connection = { changed = it })
        tab("COMMUNICATION")
        compose.onNodeWithTag("motorConnectionToggle").performScrollTo().performClick()
        compose.onNodeWithTag("motorConnectionSlave").performScrollTo().performTextReplacement("2")
        compose.runOnIdle { assertNull(changed); assertEquals(0, calls) }
        compose.onNodeWithTag("motorConnectionApply").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(MotorSerialSettings(slave = 2), changed); assertEquals(0, calls) }
    }

    @Test fun unconfirmedActionInvalidatesConfirmationAndOnlyAllowsRecoveryRead() {
        val state = MotorConfigurationUiState(snapshot(), mapOf(MotorConfigurationField.MAX_OUTPUT_CURRENT to "12.5"))
        val unconfirmed = mutableStateOf(false)
        var reads = 0
        var writes = 0
        var actions = 0
        content({ state }, read = { reads++ }, apply = { writes++ }, action = { actions++ },
            unconfirmed = { unconfirmed.value })
        tab("MOTOR")
        compose.onNodeWithTag("motorConfigurationReview").performClick()
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertIsEnabled()
        compose.runOnIdle { unconfirmed.value = true }
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertDoesNotExist()
        compose.onNodeWithTag("motorActionRecoveryRequired").assertExists()
        compose.onNodeWithTag("motorConfigurationField_max_output_current").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationRead").assertIsEnabled().performClick()
        tab("OPERATIONS")
        compose.onNodeWithTag("motorActionReview").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("motorActionSelector").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, reads); assertEquals(0, writes); assertEquals(0, actions) }
    }

    @Test fun runningActionCancelOnlyInvokesCancellationAndNeverCancelsConfigurationWrite() {
        val state = mutableStateOf(MotorConfigurationUiState(snapshot(), actioning = true))
        var reads = 0
        var writes = 0
        var actions = 0
        var cancelled = 0
        content({ state.value }, read = { reads++ }, apply = { writes++ }, action = { actions++ },
            unconfirmed = { true }, cancellable = { true }, cancel = { cancelled++ })
        compose.onNodeWithTag("motorActionCancelRunning").assertIsEnabled().performClick()
        compose.onNodeWithTag("motorConfigurationRead").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, cancelled); assertEquals(0, reads); assertEquals(0, writes); assertEquals(0, actions)
            state.value = state.value.copy(actioning = false, applying = true)
        }
        compose.onNodeWithTag("motorActionCancelRunning").assertDoesNotExist()
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
