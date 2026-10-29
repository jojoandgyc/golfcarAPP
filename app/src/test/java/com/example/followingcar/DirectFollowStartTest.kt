package com.example.followingcar

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectFollowStartTest {
    @Test fun connectedIdleUserCanStartEitherModeWithoutFetchingConfiguration() {
        val ready = BoardUiState(connected = true)
        assertNull(ready.followConfiguration)
        assertFalse(ready.followExecute)
        assertTrue(ready.canStartFollow)
        assertTrue(ready.copy(followExecute = true).canStartFollow)
        assertFalse("Selecting real execution does not start a session", ready.copy(followExecute = true).followActive)
    }

    @Test fun optionalConfigurationReadFailureDoesNotBecomeAStartPrerequisite() {
        val ready = BoardUiState(connected = true, followExecute = true,
            followConfigurationError = "上次可选配置查询失败")
        assertNull(ready.followConfiguration)
        assertTrue(ready.canStartFollow)
    }

    @Test fun directStartRetainsConnectionBusyAndControlOwnershipGuards() {
        val ready = BoardUiState(connected = true, followExecute = true)
        val blocked = listOf(
            ready.copy(connected = false), ready.copy(busy = true),
            ready.copy(driveStarting = true), ready.copy(driveEnabled = true), ready.copy(driveStopping = true),
            ready.copy(followActive = true), ready.copy(followStopping = true), ready.copy(followUncertain = true),
            ready.copy(videoServiceBusy = true), ready.copy(followConfigurationLoading = true),
        )
        blocked.forEachIndexed { index, state -> assertFalse("Guard $index must block a new control session", state.canStartFollow) }
    }

    @Test fun directStartStillValidatesSelectedFollowParameters() {
        val ready = BoardUiState(connected = true, followExecute = true, followCamera = FollowCamera.ASTRA_PRO,
            followLaunchOptions = FollowLaunchOptions(targetDistanceMeters = 1.5, gesturesEnabled = true,
                resumeGesture = FollowResumeGesture.THUMB_UP, avoidanceEnabled = true,
                reverseEnabled = true, reverseDistanceMeters = 1.4))
        assertTrue(ready.canStartFollow)
        assertFalse(ready.copy(followCamera = FollowCamera.CONFIG).canStartFollow)
        assertFalse(ready.copy(followLaunchOptions = ready.followLaunchOptions.copy(targetDistanceMeters = 0.4)).canStartFollow)
        assertFalse(ready.copy(followLaunchOptions = ready.followLaunchOptions.copy(reverseDistanceMeters = Double.NaN)).canStartFollow)
    }

    @Test fun recoveredIdleSessionCanBeStartedExplicitlyWithoutAutomaticRestart() {
        val recovered = BoardUiState(connected = true, followUncertain = false)
        assertNull(recovered.followConfiguration)
        assertTrue(recovered.canStartFollow)
        assertFalse(recovered.followActive)
        assertFalse(recovered.followExecute)
        assertFalse(recovered.copy(followUncertain = true).canStartFollow)
    }
}
