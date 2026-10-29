package com.example.followingcar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowPresentationTest {
    private fun running() = BoardUiState(connected = true, followActive = true,
        followStatus = FollowStatus("RUNNING", true, FollowCamera.CONFIG, behavior = "FOLLOW",
            targetDetected = true, targetTrackId = "7", targetAgeMs = 50,
            distanceMeters = 1.5, rangeAgeMs = 50, statusAgeMs = 20))

    @Test fun pendingRecoveryOverridesEvenFreshRunningTelemetry() {
        listOf(true, false).forEach { connected ->
            val result = followPresentation(running().copy(connected = connected, followUncertain = true))
            assertEquals("状态待确认", result.title)
            assertNoCurrentTelemetry(result)
        }
    }

    @Test fun disconnectedAndInactiveSnapshotsNeverShowCurrentFollowing() {
        val disconnected = followPresentation(running().copy(connected = false))
        assertEquals("连接中断", disconnected.title)
        assertNoCurrentTelemetry(disconnected)
        val inactive = followPresentation(running().copy(followActive = false))
        assertEquals("准备开始跟随", inactive.title)
        assertNoCurrentTelemetry(inactive)
    }

    @Test fun processExitOverridesContradictoryRunningAndFreshTarget() {
        val state = running()
        val result = followPresentation(state.copy(followStatus = state.followStatus!!.copy(processExited = true)))
        assertEquals("本次跟随已结束", result.title)
        assertTrue(result.subtitle.contains("不代表车轮已经停稳"))
        assertNoCurrentTelemetry(result)
    }

    @Test fun faultIsExplicitBeforeAndAfterExitWithoutCurrentMotionClaim() {
        val state = running()
        val fault = state.followStatus!!.copy(fault = "camera failed", runState = "FAULT")
        val active = followPresentation(state.copy(followStatus = fault))
        assertEquals("跟随出现异常", active.title)
        assertNoCurrentTelemetry(active)
        val exited = followPresentation(state.copy(followStatus = fault.copy(processExited = true)))
        assertEquals("跟随异常结束", exited.title)
        assertNoCurrentTelemetry(exited)
    }

    @Test fun stoppingAndStartupAreDistinctFromFollowing() {
        val state = running()
        listOf(state.copy(followStopping = true),
            state.copy(followStatus = state.followStatus!!.copy(stopRequested = true)),
            state.copy(followStatus = state.followStatus!!.copy(runState = "STOPPING"))).forEach {
            assertEquals("正在停止跟随", followPresentation(it).title)
            assertNoCurrentTelemetry(followPresentation(it))
        }
        listOf(state.copy(followStatus = null),
            state.copy(followStatus = state.followStatus!!.copy(runState = "STARTING"))).forEach {
            assertEquals("正在启动跟随", followPresentation(it).title)
            assertNoCurrentTelemetry(followPresentation(it))
        }
    }

    @Test fun staleAndUnknownSnapshotAgesCannotBePresentedAsLive() {
        val state = running()
        listOf<Long?>(null, -1, 2_001).forEach { age ->
            val result = followPresentation(state.copy(followStatus = state.followStatus!!.copy(statusAgeMs = age)))
            assertEquals("状态已过期", result.title)
            assertNoCurrentTelemetry(result)
        }
        assertTrue(followPresentation(state.copy(followStatus = state.followStatus!!.copy(statusAgeMs = 2_000))).fresh)
    }

    @Test fun followRequiresKnownFreshTargetAndExplicitMode() {
        val state = running()
        assertEquals("正在跟随", followPresentation(state).title)
        assertEquals("影子跟随中", followPresentation(state.copy(
            followStatus = state.followStatus!!.copy(execute = false))).title)
        listOf(state.followStatus!!.copy(targetDetected = false),
            state.followStatus!!.copy(targetDetected = null),
            state.followStatus!!.copy(targetAgeMs = 601)).forEach { status ->
            val result = followPresentation(state.copy(followStatus = status))
            assertEquals("运行中", result.title)
            assertTrue(result.fresh)
            assertFalse(result.rangeFresh)
        }
    }

    @Test fun unknownBehaviorNeverInventsGestureOrMovementState() {
        val state = running()
        listOf<String?>(null, "", "WAIT_GESTURE", "NEW_RUNTIME_BEHAVIOR").forEach { behavior ->
            val result = followPresentation(state.copy(followStatus = state.followStatus!!.copy(behavior = behavior)))
            assertEquals("运行中", result.title)
            assertTrue(result.fresh)
        }
    }

    @Test fun rangeFreshnessRequiresValidAgeDistanceAndFreshDetectedTarget() {
        val state = running()
        val status = state.followStatus!!
        val boundary = followPresentation(state.copy(followStatus = status.copy(targetAgeMs = 600, rangeAgeMs = 600)))
        assertTrue(boundary.targetFresh)
        assertTrue(boundary.rangeFresh)
        listOf(status.copy(rangeAgeMs = 601), status.copy(rangeAgeMs = null),
            status.copy(distanceMeters = null), status.copy(distanceMeters = Double.NaN),
            status.copy(distanceMeters = -1.0), status.copy(targetAgeMs = null),
            status.copy(targetDetected = false)).forEach {
            assertFalse(followPresentation(state.copy(followStatus = it)).rangeFresh)
        }
    }
}

private fun assertNoCurrentTelemetry(result: FollowPresentation) {
    assertFalse(result.fresh)
    assertFalse(result.targetFresh)
    assertFalse(result.rangeFresh)
}
