package com.example.followingcar

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.runner.RunWith

/** Page navigation and option selection do not connect to the board or start control. */
@RunWith(AndroidJUnit4::class)
class BoardPagesTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun tabsShowConnectionFollowJoystickMotorAndTerminalControls() {
        compose.onNodeWithTag("host").assertExists()
        compose.onNodeWithTag("pageFollow").performClick()
        compose.onNodeWithTag("followTargetDistance").assertExists()
        compose.onNodeWithTag("followConnect").assertIsDisplayed()
        compose.onNodeWithTag("boardHeader").assertDoesNotExist()
        compose.onNodeWithTag("connectionSummary").assertDoesNotExist()
        compose.onNodeWithTag("pageJoystick").performClick()
        compose.onNodeWithTag("driveStatus").assertExists()
        compose.onNodeWithTag("pageMotorConfiguration").performClick()
        compose.onNodeWithTag("motorConfigurationPage").assertIsDisplayed()
        compose.onNodeWithTag("motorConfigurationRead").assertIsNotEnabled()
        compose.onNodeWithTag("pageTerminal").performClick()
        compose.onNodeWithTag("command").assertExists()
        compose.onNodeWithTag("pageConnection").performClick()
        compose.onNodeWithTag("host").assertExists()
    }

    @Test
    fun motorPageIsInitiallyLockedAndNavigationDoesNotReadOrAlterFollowOptions() {
        val vm = compose.runOnUiThread { ViewModelProvider(compose.activity)[BoardViewModel::class.java] }
        val initial = vm.state.value
        assertFalse(initial.connected)
        assertNull(vm.motorConfiguration.value.snapshot)

        compose.onNodeWithTag("pageMotorConfiguration").performClick()
        compose.onNodeWithTag("pageMotorConfiguration").assertIsSelected()
        compose.onNodeWithTag("motorConfigurationConnection").assertTextEquals("未连接")
        compose.onNodeWithTag("boardHeader").assertDoesNotExist()
        compose.onNodeWithTag("connectionSummary").assertDoesNotExist()
        compose.onNodeWithTag("motorConfigurationRead").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationReview").assertIsDisplayed().assertIsNotEnabled()
        MotorConfigurationField.entries.forEach { field ->
            compose.onNodeWithTag("motorConfigurationField_${field.id}")
                .performScrollTo().assertIsNotEnabled()
        }
        compose.onNodeWithTag("motorConfigurationRead").assertIsDisplayed()
        compose.onNodeWithTag("motorConfigurationReview").assertIsDisplayed()
        compose.onNodeWithTag("motorConfigurationConfirmApply").assertDoesNotExist()

        compose.onNodeWithTag("pageFollow").performClick()
        compose.onNodeWithTag("followConnect").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("motorConfigurationPage").assertDoesNotExist()
        compose.runOnIdle {
            val current = vm.state.value
            assertEquals(initial.followCamera, current.followCamera)
            assertEquals(initial.followLaunchOptions, current.followLaunchOptions)
            assertEquals(initial.followExecute, current.followExecute)
            assertEquals(initial.followUncertain, current.followUncertain)
            assertFalse(current.connected)
            assertFalse(current.busy)
            assertFalse(current.followActive)
            assertFalse(current.manualActive)
            assertNull(vm.motorConfiguration.value.snapshot)
            assertTrue(vm.motorConfiguration.value.draftValues.isEmpty())
            assertFalse(vm.motorConfiguration.value.busy)
        }

        compose.onNodeWithTag("pageMotorConfiguration").performClick()
        compose.onNodeWithTag("motorConfigurationReview").assertIsNotEnabled()
        compose.onNodeWithTag("pageJoystick").performClick()
        compose.onNodeWithTag("enableDrive").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("motorConfigurationPage").assertDoesNotExist()
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

    @Test
    fun followUsesInlineOptionalConfigurationAndPinnedConnectionAction() {
        compose.onNodeWithTag("pageFollow").performClick()
        compose.onNodeWithTag("followShadow").assertIsSelected()
        compose.onNodeWithTag("followConnect").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("inspectFollowConfiguration").assertDoesNotExist()
        compose.onNodeWithTag("followConfigurationToggle").performScrollTo().performClick()
        compose.onNodeWithTag("inspectFollowConfiguration").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("followConnect").assertIsDisplayed().performClick()
        compose.onNodeWithTag("host").assertIsDisplayed()
        compose.onNodeWithTag("boardHeader").assertIsDisplayed()
    }
}
