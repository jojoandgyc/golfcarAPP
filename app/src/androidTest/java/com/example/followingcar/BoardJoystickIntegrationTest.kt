package com.example.followingcar

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in phone/SSH/UI tests. Every control session is explicitly started with dryRun=true.
 * NEVER click the production enableDrive button from this test: that button opens real hardware.
 */
@RunWith(AndroidJUnit4::class)
class BoardJoystickIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun joystickUsesDryRunAndReleaseAndEmergencyStopAreAcknowledged() {
        val password = passwordOrSkip()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BoardViewModel::class.java] }
        try {
            connectThroughUi(viewModel, password)
            enableDryRun(viewModel)

            compose.onNodeWithTag("joystick").performScrollTo().performTouchInput {
                down(center)
                moveTo(Offset(center.x, height * 0.2f))
            }
            // The protocol tests verify exact nonzero ACKs. Here keep the gesture held across
            // several real heartbeat intervals before checking the UI-to-controller target.
            compose.waitUntil(5_000) {
                val state = viewModel.state.value
                state.driveLeft > 0 && state.driveRight < 0 && !state.driveConfirmedStopped
            }
            SystemClock.sleep(350)
            compose.runOnIdle {
                val state = viewModel.state.value
                assertTrue(state.driveError, state.driveEnabled)
                assertTrue(state.driveLeft <= state.speedLimit)
                assertEquals(-state.driveLeft, state.driveRight)
            }
            compose.onNodeWithTag("joystick").performTouchInput { up() }
            awaitNeutralAck(viewModel)

            compose.onNodeWithTag("joystick").performTouchInput {
                down(center)
                moveTo(Offset(width * 0.8f, center.y))
            }
            compose.waitUntil(5_000) {
                val state = viewModel.state.value
                state.driveLeft > 0 && state.driveRight > 0 && !state.driveConfirmedStopped
            }
            SystemClock.sleep(350)
            compose.runOnIdle { assertEquals(viewModel.state.value.driveLeft, viewModel.state.value.driveRight) }
            compose.onNodeWithTag("joystick").performTouchInput { up() }
            awaitNeutralAck(viewModel)

            compose.onNodeWithTag("emergencyStop").performScrollTo().performClick()
            awaitDisabled(viewModel)
            compose.runOnIdle {
                assertTrue(viewModel.state.value.driveConfirmedStopped)
                assertEquals(0, viewModel.state.value.driveLeft)
                assertEquals(0, viewModel.state.value.driveRight)
            }
        } finally {
            cleanUp(viewModel)
        }
    }

    @Test
    fun leavingTheForegroundDisablesDryRunAndReturningDoesNotResumeMotion() {
        val password = passwordOrSkip()
        val viewModel = compose.runOnUiThread { ViewModelProvider(compose.activity)[BoardViewModel::class.java] }
        try {
            connectThroughUi(viewModel, password)
            enableDryRun(viewModel)
            // This lifecycle test has no held test pointer; the first test covers real gestures.
            // The same ViewModel target is still sent only to the verified dry-run bridge.
            compose.runOnUiThread { viewModel.moveJoystick(0f, 0.6f) }
            compose.waitUntil(5_000) {
                val state = viewModel.state.value
                state.driveLeft > 0 && state.driveRight < 0 && !state.driveConfirmedStopped
            }
            SystemClock.sleep(350)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            awaitDisabled(viewModel)
            assertTrue(viewModel.state.value.driveConfirmedStopped)
            assertEquals(0, viewModel.state.value.driveLeft)
            assertEquals(0, viewModel.state.value.driveRight)

            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitForIdle()
            compose.runOnIdle {
                assertFalse(viewModel.state.value.driveEnabled)
                assertFalse(viewModel.state.value.driveStarting)
                assertEquals(0, viewModel.state.value.driveLeft)
                assertEquals(0, viewModel.state.value.driveRight)
            }
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            cleanUp(viewModel)
        }
    }

    private fun passwordOrSkip(): String {
        val password = InstrumentationRegistry.getArguments().getString("boardPassword").orEmpty()
        assumeTrue("Provide boardPassword to opt in to real-board dry-run tests", password.isNotEmpty())
        return password
    }

    private fun connectThroughUi(viewModel: BoardViewModel, password: String) {
        val arguments = InstrumentationRegistry.getArguments()
        compose.onNodeWithTag("host").performScrollTo()
            .performTextReplacement(arguments.getString("boardHost") ?: "172.16.16.82")
        compose.onNodeWithTag("username").performScrollTo()
            .performTextReplacement(arguments.getString("boardUsername") ?: "topeet")
        compose.onNodeWithTag("password").performScrollTo().performTextReplacement(password)
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        compose.waitUntil(25_000) {
            val state = viewModel.state.value
            (state.connected && !state.busy) || state.status == "连接失败"
        }
        assertTrue(viewModel.state.value.error, viewModel.state.value.connected)
    }

    private fun enableDryRun(viewModel: BoardViewModel) {
        compose.runOnUiThread { viewModel.enableDrive(dryRun = true) }
        compose.waitUntil(8_000) {
            viewModel.state.value.driveEnabled || viewModel.state.value.driveError != null
        }
        assertTrue(viewModel.state.value.driveError, viewModel.state.value.driveEnabled)
        // This must pass before generating ANY nonzero target, including touch input.
        assertEquals("dry-run", viewModel.state.value.drivePort)
        assertTrue(viewModel.state.value.driveStatus.contains("模拟控制"))
    }

    private fun awaitNeutralAck(viewModel: BoardViewModel) {
        compose.waitUntil(5_000) {
            val state = viewModel.state.value
            state.driveEnabled && state.driveLeft == 0 && state.driveRight == 0 && state.driveConfirmedStopped
        }
    }

    private fun awaitDisabled(viewModel: BoardViewModel) {
        compose.waitUntil(8_000) {
            val state = viewModel.state.value
            !state.driveStarting && !state.driveEnabled && !state.driveStopping
        }
    }

    private fun cleanUp(viewModel: BoardViewModel) {
        compose.runOnUiThread {
            viewModel.disableDrive("模拟测试结束")
            viewModel.disconnect()
        }
        compose.waitUntil(8_000) {
            val state = viewModel.state.value
            !state.connected && !state.driveStarting && !state.driveEnabled && !state.driveStopping
        }
    }
}
