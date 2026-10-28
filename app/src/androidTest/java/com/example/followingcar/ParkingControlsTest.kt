package com.example.followingcar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline UI checks: callbacks only, with no SSH or motor access. */
@RunWith(AndroidJUnit4::class)
class ParkingControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun ready(settings: ParkingSettings = ParkingSettings()) = BoardUiState(
        connected = true, driveEnabled = true, driveConfirmedStopped = true,
        parkingSupported = true, parkingSettings = settings
    )

    @Test
    fun unknownOrUnsupportedStateCannotChangeParking() {
        val state = mutableStateOf(BoardUiState())
        compose.setContent { MaterialTheme { ParkingControls(state.value, {}, {}) } }
        assertControlsDisabled()
        compose.onNodeWithTag("parkingCurrent").assertTextEquals("驻车电流：— A")

        compose.runOnIdle { state.value = ready().copy(parkingSupported = false) }
        assertControlsDisabled()
    }

    @Test
    fun changesRequestOneAmpStepsAndWaitForConfirmedState() {
        var requestedEnabled: Boolean? = null
        var requestedCurrent: Int? = null
        val state = mutableStateOf(ready())
        compose.setContent {
            MaterialTheme {
                ParkingControls(state.value, { requestedEnabled = it }, { requestedCurrent = it })
            }
        }
        compose.onNodeWithTag("parkingSwitch").performClick()
        compose.runOnIdle { assertEquals(true, requestedEnabled) }
        compose.onNodeWithTag("parkingSwitch").assertIsOff()
        compose.onNodeWithTag("parkingIncrease").performClick()
        compose.runOnIdle { assertEquals(6, requestedCurrent) }
        compose.onNodeWithTag("parkingCurrent").assertTextEquals("驻车电流：5 A")
        compose.onNodeWithTag("parkingDecrease").performClick()
        compose.runOnIdle { assertEquals(4, requestedCurrent) }

        compose.runOnIdle { state.value = ready(ParkingSettings(enabled = true, currentAmps = 6)) }
        compose.onNodeWithTag("parkingSwitch").assertIsOn()
        compose.onNodeWithTag("parkingCurrent").assertTextEquals("驻车电流：6 A")
    }

    @Test
    fun movementUnconfirmedStopAndPendingConfigurationDisableChanges() {
        val state = mutableStateOf(ready())
        compose.setContent { MaterialTheme { ParkingControls(state.value, {}, {}) } }
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled()
        compose.runOnIdle { state.value = ready().copy(driveLeft = 10, driveRight = -10) }
        assertControlsDisabled()
        compose.runOnIdle { state.value = ready().copy(driveConfirmedStopped = false) }
        assertControlsDisabled()
        compose.runOnIdle { state.value = ready().copy(parkingUpdating = true) }
        assertControlsDisabled()
        compose.onNodeWithTag("parkingCurrent").assertTextEquals("驻车电流：5 A")
        compose.runOnIdle { state.value = ready().copy(driveStopping = true) }
        assertControlsDisabled()
        compose.runOnIdle { state.value = ready().copy(busy = true) }
        assertControlsDisabled()
    }

    @Test
    fun zeroCurrentDisablesParkingAndLimitsRespectZeroAndSixtyAmps() {
        val state = mutableStateOf(ready(ParkingSettings(currentAmps = 0)))
        compose.setContent { MaterialTheme { ParkingControls(state.value, {}, {}) } }
        compose.onNodeWithTag("parkingSwitch").assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("parkingDecrease").assertIsNotEnabled()
        compose.onNodeWithTag("parkingIncrease").assertIsEnabled()
        compose.runOnIdle { state.value = ready(ParkingSettings(currentAmps = 60)) }
        compose.onNodeWithTag("parkingIncrease").assertIsNotEnabled()
        compose.onNodeWithTag("parkingDecrease").assertIsEnabled()
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled()
    }

    private fun assertControlsDisabled() {
        listOf("parkingSwitch", "parkingDecrease", "parkingIncrease").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }
}
