package com.example.followingcar

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fake callbacks and telemetry only; no SSH, camera, motor or real ViewModel is used. */
@RunWith(AndroidJUnit4::class)
class FollowControlsTest {
    @get:Rule val compose = createComposeRule()
    private fun ready() = BoardUiState(connected = true)
    private fun status() = FollowStatus(
        runState = "RUNNING", execute = false, camera = FollowCamera.CONFIG,
        behavior = "FOLLOW", reason = "fake_sample", targetTrackId = "7", targetAgeMs = 50,
        distanceMeters = 1.8, rangeAgeMs = 70, commandLeftRpm = 12.0, commandRightRpm = 12.0,
        feedbackLeftRpm = null, feedbackRightRpm = null, feedbackAgeMs = null,
        stopRequested = false, stopAcknowledged = null, physicalStopped = null,
        statusAgeMs = 20, fault = null,
    )

    @Test fun noConfigurationIsRequiredAndRealModeSelectionNeverStartsAutomatically() {
        val state = mutableStateOf(ready())
        var starts = 0
        var refreshes = 0
        var selectedMode: Boolean? = null
        compose.setContent {
            TestPage(state.value,
                onExecuteChange = { selectedMode = it; state.value = state.value.copy(followExecute = it) },
                onStart = { starts++ }, onRefreshConfiguration = { refreshes++ })
        }
        compose.runOnIdle { assertNull(state.value.followConfiguration); assertEquals(0, starts); assertEquals(0, refreshes) }
        compose.onNodeWithTag("followShadow").assertIsSelected()
        compose.onNodeWithTag("followExecute").assertIsNotSelected()
        compose.onNodeWithTag("startFollow").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("inspectFollowConfiguration").assertDoesNotExist()
        compose.onNodeWithTag("followExecute").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, selectedMode); assertEquals(0, starts); assertEquals(0, refreshes) }
        compose.onNodeWithTag("followExecute").assertIsSelected()
        compose.onNodeWithTag("startFollow").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, starts); assertEquals(0, refreshes) }
    }

    @Test fun oneDistanceUpdatesForwardAndReverseWhileOptionsNeverStartControl() {
        val state = mutableStateOf(ready().copy(followCamera = FollowCamera.ASTRA_PRO))
        var starts = 0
        compose.setContent {
            TestPage(state.value, onStart = { starts++ },
                onOptionsChange = { state.value = state.value.copy(followLaunchOptions = it) })
        }
        compose.onNodeWithTag("followTargetDistance").assertTextEquals("1.5 m")
        compose.onNodeWithTag("followTargetDistanceIncrease").performScrollTo().performClick()
        compose.onNodeWithTag("followGestures").performScrollTo().performClick()
        compose.onNodeWithTag("followGesture_Closed_Fist").performScrollTo().performClick()
        compose.onNodeWithTag("followAvoidance").performScrollTo().performClick()
        compose.onNodeWithTag("followReverse").performScrollTo().performClick()
        compose.onNodeWithTag("followTargetDistanceDecrease").performScrollTo().performClick()
        compose.onNodeWithTag("followTargetDistance").assertTextEquals("1.5 m")
        compose.onNodeWithTag("followReverseDistanceDecrease").assertDoesNotExist()
        compose.runOnIdle {
            val options = state.value.followLaunchOptions
            assertEquals(1.5, options.targetDistanceMeters!!, 0.0001)
            assertEquals(1.5, options.reverseDistanceMeters!!, 0.0001)
            assertEquals(true, options.gesturesEnabled)
            assertEquals(FollowResumeGesture.CLOSED_FIST, options.resumeGesture)
            assertEquals(true, options.avoidanceEnabled)
            assertEquals(true, options.reverseEnabled)
            assertEquals(0, starts)
        }
    }

    @Test fun enablingReverseUsesTheCurrentlyDisplayedDistance() {
        val state = mutableStateOf(ready()) // Stored reverse is 1.4 m; displayed target is 1.5 m.
        compose.setContent {
            TestPage(state.value, onOptionsChange = { state.value = state.value.copy(followLaunchOptions = it) })
        }
        compose.onNodeWithTag("followReverse").performScrollTo().performClick()
        compose.onNodeWithTag("followTargetDistance").performScrollTo().assertTextEquals("1.5 m")
        compose.runOnIdle {
            assertEquals(1.5, state.value.followLaunchOptions.targetDistanceMeters!!, 0.0001)
            assertEquals(1.5, state.value.followLaunchOptions.reverseDistanceMeters!!, 0.0001)
        }
    }

    @Test fun disconnectedSettingsRemainEditableAndAvoidanceCanBeSelectedBeforeAstra() {
        val state = mutableStateOf(BoardUiState())
        compose.setContent {
            TestPage(state.value, onOptionsChange = { state.value = state.value.copy(followLaunchOptions = it) })
        }
        compose.onNodeWithTag("followCamera_config").assertIsSelected()
        compose.onNodeWithTag("followAvoidance").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, state.value.followLaunchOptions.avoidanceEnabled) }
        compose.onNodeWithTag("followConnect").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("startFollow").assertDoesNotExist()
    }

    @Test fun manualOwnershipAndBusyOperationsStillDisableStartAndSettings() {
        val state = mutableStateOf(ready())
        compose.setContent { TestPage(state.value) }
        compose.onNodeWithTag("startFollow").assertIsEnabled()
        for (blocked in listOf(ready().copy(driveStarting = true), ready().copy(driveEnabled = true),
            ready().copy(driveStopping = true), ready().copy(busy = true), ready().copy(videoServiceBusy = true),
            ready().copy(followConfigurationLoading = true))) {
            compose.runOnIdle { state.value = blocked }
            compose.onNodeWithTag("startFollow").assertIsDisplayed().assertIsNotEnabled()
            compose.onNodeWithTag("followExecute").assertIsNotEnabled()
        }
        compose.runOnIdle { state.value = ready().copy(followConfigurationError = "可选读取失败") }
        compose.onNodeWithTag("startFollow").assertIsEnabled()
        compose.onNodeWithTag("followExecute").assertIsEnabled()
    }

    @Test fun uncertainExitHasOnlyAnExplicitInspectionActionAndKeepsSettingsLocked() {
        var inspections = 0
        var starts = 0
        compose.setContent {
            TestPage(ready().copy(followUncertain = true), onStart = { starts++ }, onInspect = { inspections++ })
        }
        compose.onNodeWithTag("startFollow").assertDoesNotExist()
        compose.onNodeWithTag("followUncertainHint").assertIsDisplayed()
        compose.onNodeWithTag("followSettingsToggle").performScrollTo().performClick()
        compose.onNodeWithTag("followExecute").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, inspections) }
        compose.onNodeWithTag("inspectFollowAvailability").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(1, inspections) }
    }

    @Test fun disconnectedRecoveryOpensConnectionPageWithoutStartingOrInspecting() {
        var inspections = 0
        var starts = 0
        var connections = 0
        compose.setContent {
            TestPage(BoardUiState(followUncertain = true), onStart = { starts++ },
                onInspect = { inspections++ }, onOpenConnection = { connections++ })
        }
        compose.onNodeWithTag("startFollow").assertDoesNotExist()
        compose.onNodeWithTag("inspectFollowAvailability").assertDoesNotExist()
        compose.onNodeWithTag("followUncertainHint").assertIsDisplayed()
        compose.onNodeWithTag("followConnect").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, inspections); assertEquals(1, connections) }
    }

    @Test fun staleDistanceAndMissingFeedbackStayUnknownInExpandedDetails() {
        compose.setContent { TestPage(ready().copy(followActive = true, followStatus = status().copy(rangeAgeMs = 900))) }
        openDetails()
        compose.onNodeWithTag("followDistance").assertTextEquals("距离：未知或已过期")
        compose.onNodeWithTag("followFeedback").assertTextEquals("反馈轮速：未知或已过期")
        compose.onNodeWithTag("followStopAck").assertTextEquals("停车通信回执：未知")
        compose.onNodeWithTag("followPhysicalStopped").assertTextEquals("机械停稳：未知")
        compose.onNodeWithTag("stopFollow").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun processExitAndStopAckNeverClaimMechanicalStop() {
        val finished = status().copy(runState = "STOPPED", processExited = true,
            stopRequested = true, stopAcknowledged = true, physicalStopped = null)
        compose.setContent { TestPage(ready().copy(followStatus = finished, followTerminal = true)) }
        openDetails()
        compose.onNodeWithTag("followRunState").assertTextEquals("软件状态：已结束")
        compose.onNodeWithTag("followProcessExited").assertTextEquals("跟随进程：已退出")
        compose.onNodeWithTag("followStopAck").assertTextEquals("停车通信回执：已确认")
        compose.onNodeWithTag("followPhysicalStopped").assertTextEquals("机械停稳：未知")
        compose.onNodeWithTag("followCommand").assertTextEquals("指令轮速：未知或已过期")
    }

    @Test fun staleSnapshotNeverDisplaysCurrentRunningBehavior() {
        compose.setContent { TestPage(ready().copy(followActive = true, followStatus = status().copy(statusAgeMs = 3_000))) }
        openDetails()
        compose.onNodeWithTag("followRunState").assertTextEquals("软件状态：已过期或未知（上次运行中）")
        compose.onNodeWithTag("followBehavior").assertTextEquals("控制行为与原因：未知或已过期")
        compose.onNodeWithTag("followDistance").assertTextEquals("距离：未知或已过期")
    }

    @Test fun startupProgressAndFinalIpcWaitDoNotReleaseStartOrClaimSuccess() {
        val state = mutableStateOf(ready().copy(followActive = true, followStatus = status().copy(
            runState = "STARTING", statusAgeMs = null,
            startup = FollowStartup("camera", 1_200, 4_600, 15_000, 30_000, false))))
        compose.setContent { TestPage(state.value) }
        compose.onNodeWithTag("followStatusTitle").assertTextEquals("正在启动跟随")
        compose.onNodeWithTag("followStartupProgress").assertTextEquals("启动已用 4.6 s · 总期限 30 s")
        compose.onNodeWithTag("followProgressMessage").assertDoesNotExist()
        compose.onNodeWithTag("stopFollow").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("startFollow").assertDoesNotExist()
        saveUiSnapshot("follow-handoff-startup.png")
        compose.runOnIdle { state.value = state.value.copy(followStopping = true,
            followStatus = state.value.followStatus!!.copy(runState = "STOPPED", processExited = true,
                finalStatusPending = true, stopRequested = true)) }
        compose.onNodeWithTag("followStatusTitle").assertTextEquals("正在确认退出结果")
        compose.onNodeWithTag("startFollow").assertDoesNotExist()
        saveUiSnapshot("follow-handoff-final-pending.png")
        openDetails()
        compose.onNodeWithTag("followFinalStatusPending").performScrollTo().assertTextEquals("最终关闭状态：正在等待")
        compose.onNodeWithTag("followTerminal").performScrollTo().assertTextEquals("会话终态：未收到")
    }

    @Test fun faultWhileWorkerLivesAndFaultAfterTerminalStayDistinct() {
        val state = mutableStateOf(ready().copy(followActive = true, followStatus = status().copy(
            runState = "FAULT", fault = "close timeout", processExited = false)))
        compose.setContent { TestPage(state.value) }
        compose.onNodeWithTag("followStatusTitle").assertTextEquals("跟随出现异常")
        compose.onNodeWithTag("startFollow").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(followActive = false, followTerminal = true,
            followStatus = state.value.followStatus!!.copy(processExited = true)) }
        compose.onNodeWithTag("followStatusTitle").assertTextEquals("跟随异常结束")
        openDetails()
        compose.onNodeWithTag("followRunState").assertTextEquals("软件状态：故障退出")
        compose.onNodeWithTag("followPhysicalStopped").performScrollTo().assertTextEquals("机械停稳：未知")
    }

    @Test fun configurationOnlyLoadsAfterExplicitExpansionAndRead() {
        val state = mutableStateOf(ready())
        var inspections = 0
        var starts = 0
        compose.setContent {
            TestPage(state.value, onStart = { starts++ }, onRefreshConfiguration = { inspections++ },
                onCameraChange = { state.value = state.value.copy(followCamera = it) })
        }
        compose.onNodeWithTag("inspectFollowConfiguration").assertDoesNotExist()
        compose.onNodeWithTag("followCamera_gemini335").performScrollTo().performClick()
        compose.onNodeWithTag("followCamera_gemini335").assertIsSelected()
        compose.onNodeWithTag("followConfigurationToggle").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, inspections); assertEquals(0, starts) }
        compose.onNodeWithTag("inspectFollowConfiguration").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, inspections); assertEquals(0, starts) }
        compose.onNodeWithTag("startFollow").assertIsDisplayed().assertIsEnabled()
    }

    @Test fun startAndStopFooterRemainVisibleWhileBodyScrolls() {
        val state = mutableStateOf(ready())
        var stops = 0
        compose.setContent { TestPage(state.value, onStop = { stops++ }) }
        compose.onNodeWithTag("startFollow").assertIsDisplayed()
        val startBottom = compose.onNodeWithTag("startFollow").fetchSemanticsNode().boundsInRoot.bottom
        compose.onNodeWithTag("followConfigurationToggle").performScrollTo().performClick()
        compose.onNodeWithTag("inspectFollowConfiguration").performScrollTo()
        compose.onNodeWithTag("startFollow").assertIsDisplayed()
        assertEquals(startBottom, compose.onNodeWithTag("startFollow").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        compose.runOnIdle { state.value = ready().copy(followActive = true, followStatus = status()) }
        compose.onNodeWithTag("stopFollow").assertIsDisplayed()
        val stopBottom = compose.onNodeWithTag("stopFollow").fetchSemanticsNode().boundsInRoot.bottom
        openDetails()
        compose.onNodeWithTag("followPhysicalStopped").performScrollTo()
        compose.onNodeWithTag("stopFollow").assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(stopBottom, compose.onNodeWithTag("stopFollow").fetchSemanticsNode().boundsInRoot.bottom, 1f)
        compose.runOnIdle { assertEquals(1, stops); state.value = state.value.copy(followStopping = true) }
        compose.onNodeWithTag("stopFollow").assertIsDisplayed().assertIsNotEnabled()
    }

    private fun openDetails() { compose.onNodeWithTag("followDetailsToggle").performScrollTo().performClick() }

    /** Optional visual evidence, separate from semantic assertions and test pass/fail. */
    private fun saveUiSnapshot(name: String) {
        runCatching {
            compose.waitForIdle()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
            try {
                val directory = instrumentation.targetContext.externalCacheDir ?: return
                File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Composable
    private fun TestPage(
        state: BoardUiState,
        onExecuteChange: (Boolean) -> Unit = {}, onStart: () -> Unit = {}, onStop: () -> Unit = {},
        onInspect: () -> Unit = {}, onOptionsChange: (FollowLaunchOptions) -> Unit = {},
        onCameraChange: (FollowCamera) -> Unit = {}, onRefreshConfiguration: () -> Unit = {},
        onOpenConnection: () -> Unit = {},
    ) {
        MaterialTheme {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                FollowPage(state, onExecuteChange, onStart, onStop, onInspect, onOptionsChange,
                    onCameraChange, onRefreshConfiguration, onOpenConnection, Modifier.fillMaxSize())
            }
        }
    }
}
