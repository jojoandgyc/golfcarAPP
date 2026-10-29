package com.example.followingcar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure UI callbacks and sample status only: never connects, opens hardware, or starts following. */
@RunWith(AndroidJUnit4::class)
class FollowControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun ready() = BoardUiState(connected = true, followConfiguration = FollowConfiguration(
        cameraProfile = FollowCamera.CONFIG, targetDistanceMeters = 1.2,
        maxRpm = 200.0, forwardMaxRpm = 200.0, parkingCurrentAmps = 10.0,
        motorPort = "/dev/fake", depthBackend = "fake", models = listOf("fake.rknn"),
        cameraDetails = emptyMap(), rawJson = "{\"hardware_opened\":false}",
    ))

    private fun status() = FollowStatus(
        runState = "RUNNING", execute = false, camera = FollowCamera.CONFIG,
        behavior = "FOLLOW", reason = "fake_sample", targetTrackId = "7", targetAgeMs = 50,
        distanceMeters = 1.8, rangeAgeMs = 70, commandLeftRpm = 12.0, commandRightRpm = 12.0,
        feedbackLeftRpm = null, feedbackRightRpm = null, feedbackAgeMs = null,
        stopRequested = false, stopAcknowledged = null, physicalStopped = null,
        statusAgeMs = 20, fault = null,
    )

    @Test
    fun compositionDoesNotStartAndRealExecutionRequiresExplicitSwitchAndStart() {
        val state = mutableStateOf(ready())
        var starts = 0
        var execute: Boolean? = null
        compose.setContent {
            TestSurface {
                FollowControlCard(state.value, { execute = it }, { starts++ }, {}, {})
            }
        }
        compose.runOnIdle { assertEquals(0, starts); assertEquals(null, execute) }
        compose.onNodeWithTag("followExecute").assertIsOff()
        compose.onNodeWithTag("startFollow").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("followExecute").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, execute); assertEquals(0, starts) }
        compose.runOnIdle { state.value = ready().copy(followExecute = true) }
        compose.onNodeWithTag("startFollow").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun followOptionsCanBeSelectedWithoutStartingAControlSession() {
        val state = mutableStateOf(ready().copy(followCamera = FollowCamera.ASTRA_PRO))
        var starts = 0
        compose.setContent {
            TestSurface {
                FollowControlCard(state.value, {}, { starts++ }, {}, {},
                    { options -> state.value = state.value.copy(followLaunchOptions = options) })
            }
        }
        compose.onNodeWithTag("followTargetDistanceIncrease").performScrollTo().performClick()
        compose.onNodeWithTag("followGestures").performScrollTo().performClick()
        compose.onNodeWithTag("followGesture_Closed_Fist").performScrollTo().performClick()
        compose.onNodeWithTag("followAvoidance").performScrollTo().performClick()
        compose.onNodeWithTag("followReverse").performScrollTo().performClick()
        compose.onNodeWithTag("followReverseDistanceDecrease").performScrollTo().performClick()
        compose.runOnIdle {
            val options = state.value.followLaunchOptions
            assertEquals(1.6, options.targetDistanceMeters!!, 0.0001)
            assertEquals(true, options.gesturesEnabled)
            assertEquals(FollowResumeGesture.CLOSED_FIST, options.resumeGesture)
            assertEquals(true, options.avoidanceEnabled)
            assertEquals(true, options.reverseEnabled)
            assertEquals(1.3, options.reverseDistanceMeters!!, 0.0001)
            assertEquals(0, starts)
        }
    }

    @Test
    fun avoidanceSwitchIsClickableBeforeAstraCameraIsSelected() {
        val state = mutableStateOf(ready().copy(followCamera = FollowCamera.CONFIG))
        compose.setContent {
            TestSurface {
                FollowControlCard(state.value, {}, {}, {}, {},
                    { options -> state.value = state.value.copy(followLaunchOptions = options) })
            }
        }
        compose.onNodeWithTag("followAvoidance").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(true, state.value.followLaunchOptions.avoidanceEnabled) }
    }

    @Test
    fun startupRequiresConfigurationAndManualControlMustBeReleased() {
        val state = mutableStateOf(BoardUiState(connected = true))
        compose.setContent { TestSurface { FollowControlCard(state.value, {}, {}, {}, {}) } }
        compose.onNodeWithTag("startFollow").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready().copy(driveStarting = true) }
        compose.onNodeWithTag("startFollow").assertIsNotEnabled()
        compose.onNodeWithTag("followExecute").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready().copy(driveEnabled = true) }
        compose.onNodeWithTag("startFollow").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready().copy(driveStopping = true) }
        compose.onNodeWithTag("startFollow").assertIsNotEnabled()
        compose.runOnIdle { state.value = ready() }
        compose.onNodeWithTag("startFollow").assertIsEnabled()
    }

    @Test
    fun uncertainExitStaysLockedAndOnlyExplicitInspectionRuns() {
        var inspections = 0
        var starts = 0
        val state = ready().copy(followUncertain = true)
        compose.setContent {
            TestSurface { FollowControlCard(state, {}, { starts++ }, {}, { inspections++ }) }
        }
        compose.onNodeWithTag("startFollow").assertIsNotEnabled()
        compose.onNodeWithTag("followExecute").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, inspections) }
        compose.onNodeWithTag("inspectFollowAvailability").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(0, starts); assertEquals(1, inspections) }
    }

    @Test
    fun disconnectedRecoveryExplainsReconnectAndDoesNotStartOrInspectFromComposition() {
        var inspections = 0
        var starts = 0
        val state = ready().copy(connected = false, followUncertain = true)
        compose.setContent {
            TestSurface { FollowControlCard(state, {}, { starts++ }, {}, { inspections++ }) }
        }
        compose.onNodeWithTag("startFollow").assertIsNotEnabled()
        compose.onNodeWithTag("inspectFollowAvailability").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("followRecoveryHint").assertTextEquals(
            "请到连接页重新连接，连接成功后会自动检查控制占用，不会自动恢复跟随。")
        compose.runOnIdle { assertEquals(0, starts); assertEquals(0, inspections) }
    }

    @Test
    fun staleDistanceAndMissingFeedbackAreUnknownInsteadOfZero() {
        val state = ready().copy(followActive = true, followStatus = status().copy(rangeAgeMs = 900))
        compose.setContent { TestSurface { FollowControlCard(state, {}, {}, {}, {}) } }
        compose.onNodeWithTag("followDistance").assertTextEquals("距离：未知或已过期")
        compose.onNodeWithTag("followFeedback").assertTextEquals("反馈轮速：未知或已过期")
        compose.onNodeWithTag("followStopAck").assertTextEquals("停车通信回执：未知")
        compose.onNodeWithTag("followPhysicalStopped").assertTextEquals("机械停稳：未知")
        compose.onNodeWithTag("followExecute").assertIsNotEnabled()
        compose.onNodeWithTag("stopFollow").assertIsEnabled()
    }

    @Test
    fun processExitAndStopAckDoNotClaimMechanicalStop() {
        val finished = status().copy(runState = "STOPPED", processExited = true,
            stopRequested = true, stopAcknowledged = true, physicalStopped = null)
        compose.setContent { TestSurface { FollowControlCard(ready().copy(followStatus = finished), {}, {}, {}, {}) } }
        compose.onNodeWithTag("followProcessExited").assertTextEquals("跟随进程：已退出")
        compose.onNodeWithTag("followStopAck").assertTextEquals("停车通信回执：已确认")
        compose.onNodeWithTag("followPhysicalStopped").assertTextEquals("机械停稳：未知")
        compose.onNodeWithTag("followCommand").assertTextEquals("指令轮速：未知或已过期")
    }

    @Test
    fun staleSnapshotDoesNotKeepDisplayingRunningOrCurrentBehavior() {
        val stale = status().copy(statusAgeMs = 3_000)
        compose.setContent { TestSurface { FollowControlCard(ready().copy(followActive = true, followStatus = stale), {}, {}, {}, {}) } }
        compose.onNodeWithTag("followRunState").assertTextEquals("软件状态：已过期或未知（上次运行中）")
        compose.onNodeWithTag("followBehavior").assertTextEquals("控制行为与原因：未知或已过期")
        compose.onNodeWithTag("followDistance").assertTextEquals("距离：未知或已过期")
    }

    @Test
    fun configurationInspectionAndCameraSelectionDoNotStartAnything() {
        var selected: FollowCamera? = null
        var inspections = 0
        compose.setContent {
            TestSurface { FollowConfigurationCard(ready(), { selected = it }, { inspections++ }) }
        }
        compose.runOnIdle { assertEquals(null, selected); assertEquals(0, inspections) }
        compose.onNodeWithTag("followCamera_gemini335").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(FollowCamera.GEMINI335, selected); assertEquals(0, inspections) }
        compose.onNodeWithTag("inspectFollowConfiguration").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, inspections) }
    }

    @Composable
    private fun TestSurface(content: @Composable () -> Unit) {
        MaterialTheme { Column(Modifier.verticalScroll(rememberScrollState())) { content() } }
    }
}
