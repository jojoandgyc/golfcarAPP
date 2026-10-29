package com.example.followingcar

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
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
        compose.onNodeWithTag("drivingCameraPanel").assertIsDisplayed()
        compose.onNodeWithTag("videoServiceToggle").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("joystick").assertIsDisplayed()
        compose.onNodeWithTag("controlModeButtons").performClick()
        compose.onNodeWithTag("directionPad").assertIsDisplayed()
        listOf("directionFORWARD", "directionLEFT", "directionRIGHT", "directionREVERSE", "directionStop")
            .forEach { tag ->
                compose.onNodeWithTag(tag).assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            }
        compose.onNodeWithTag("drivingCameraPanel").assertIsDisplayed()
        compose.onNodeWithTag("enableDrive").assertIsDisplayed()
        compose.onNodeWithTag("joystick").assertDoesNotExist()
        compose.onNodeWithTag("controlModeJoystick").performClick()
        compose.onNodeWithTag("joystick").assertIsDisplayed()
        compose.onNodeWithTag("directionPad").assertDoesNotExist()
    }

    @Test
    fun drivingVideoAndControlsFitWithoutPageScrolling() {
        compose.onNodeWithTag("boardHeader").assertIsDisplayed()
        compose.onNodeWithTag("connectionSummary").assertIsDisplayed()
        compose.onNodeWithTag("pageJoystick").performClick()
        assertTrue(compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("boardHeader").assertDoesNotExist()
        compose.onNodeWithTag("connectionSummary").assertDoesNotExist()
        compose.onNodeWithTag("manualCameraTab").assertDoesNotExist()
        compose.onNodeWithTag("manualDriveTab").assertIsSelected()
        compose.onNodeWithTag("drivingCameraPanel").assertIsDisplayed()
        compose.onNodeWithTag("videoServiceToggle").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("joystick").assertIsDisplayed()
        compose.onNodeWithTag("enableDrive").assertIsDisplayed()

        compose.onNodeWithTag("manualParkingTab").performClick()
        assertTrue(compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("parkingSwitch").assertIsDisplayed()
        compose.onNodeWithTag("parkingCurrent").assertIsDisplayed()
        compose.onNodeWithTag("drivingCameraPanel").assertDoesNotExist()
        compose.onNodeWithTag("enableDrive").assertIsDisplayed()

        compose.onNodeWithTag("manualDriveTab").performClick()
        compose.onNodeWithTag("drivingCameraPanel").assertIsDisplayed()
        compose.onNodeWithTag("joystick").assertIsDisplayed()
        compose.onNodeWithTag("enableDrive").assertIsDisplayed()

        compose.onNodeWithTag("pageConnection").performClick()
        compose.onNodeWithTag("boardHeader").assertIsDisplayed()
        compose.onNodeWithTag("connectionSummary").assertIsDisplayed()
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
