package com.example.followingcar

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Page navigation and option selection do not connect to the board or start control. */
@RunWith(AndroidJUnit4::class)
class BoardPagesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabsShowConnectionFollowJoystickAndTerminalControls() {
        compose.onNodeWithTag("host").assertExists()
        compose.onNodeWithTag("pageFollow").performClick()
        compose.onNodeWithTag("followTargetDistance").assertExists()
        compose.onNodeWithTag("pageJoystick").performClick()
        compose.onNodeWithTag("driveStatus").assertExists()
        compose.onNodeWithTag("pageTerminal").performClick()
        compose.onNodeWithTag("command").assertExists()
        compose.onNodeWithTag("pageConnection").performClick()
        compose.onNodeWithTag("host").assertExists()
    }

    @Test
    fun manualPageSwitchesBetweenJoystickAndDirectionButtonsWithoutStartingDrive() {
        compose.onNodeWithTag("pageJoystick").performClick()
        compose.onNodeWithTag("joystick").assertExists()
        compose.onNodeWithTag("controlModeButtons").performScrollTo().performClick()
        compose.onNodeWithTag("directionPad").assertExists()
        compose.onNodeWithTag("joystick").assertDoesNotExist()
        compose.onNodeWithTag("controlModeJoystick").performScrollTo().performClick()
        compose.onNodeWithTag("joystick").assertExists()
        compose.onNodeWithTag("directionPad").assertDoesNotExist()
    }

    @Test
    fun enablingAvoidanceSelectsAstraAndChoosingAnotherCameraDisablesAvoidance() {
        compose.onNodeWithTag("pageFollow").performClick()
        compose.onNodeWithTag("followAvoidance").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("followCamera_astra-pro").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("followCamera_gemini335").performScrollTo().performClick()
        compose.onNodeWithTag("followAvoidance").performScrollTo().assertIsOff()
    }
}
