package com.example.followingcar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowPresentationTest {
    private fun running() = BoardUiState(connected = true, followActive = true,
        followStatus = FollowStatus("RUNNING", true, FollowCamera.CONFIG, behavior = "FOLLOW",
            targetDetected = true, targetTrackId = "7", targetAgeMs = 50,
            distanceMeters = 1.5, rangeAgeMs = 50, statusAgeMs = 20, commandStop = false))

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
        assertEquals("上次会话结果未确认", inactive.title)
        assertNoCurrentTelemetry(inactive)
    }

    @Test fun onlyTerminalAckConfirmsSessionEndAndSurvivesExpectedDisconnection() {
        val state = running()
        val exited = state.followStatus!!.copy(processExited = true)
        val pending = followPresentation(state.copy(followStatus = exited))
        assertEquals("等待会话结束确认", pending.title)
        val result = followPresentation(state.copy(followStatus = exited, followTerminal = true, connected = false))
        assertEquals("跟随程序已退出", result.title)
        assertTrue(result.subtitle.contains("不代表车轮已经停稳"))
        assertNoCurrentTelemetry(result)
    }

    @Test fun faultIsExplicitBeforeAndAfterExitWithoutCurrentMotionClaim() {
        val state = running()
        val fault = state.followStatus!!.copy(fault = "camera failed", runState = "FAULT")
        val active = followPresentation(state.copy(followStatus = fault))
        assertEquals("跟随出现异常", active.title)
        assertNoCurrentTelemetry(active)
        val pending = followPresentation(state.copy(followStatus = fault.copy(processExited = true)))
        assertEquals("故障，等待关闭结果", pending.title)
        val exited = followPresentation(state.copy(followStatus = fault.copy(processExited = true), followTerminal = true))
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

    @Test fun readyAndFinalIpcWaitAreNotTerminalEvenWhenProcessExited() {
        val state = running()
        val exited = state.followStatus!!.copy(runState = "STOPPED", processExited = true)
        assertEquals("已连接，等待开始", followPresentation(state.copy(followStatus = exited)).title)
        val pending = followPresentation(state.copy(followStatus = exited.copy(finalStatusPending = true)))
        assertEquals("正在确认退出结果", pending.title)
        assertNoCurrentTelemetry(pending)
    }

    @Test fun startupStagesAreOptionalAndNeverOverrideLifecycle() {
        val state = running()
        val startup = FollowStartup("camera", 1_200, 4_600, 15_000, 30_000, false)
        val starting = state.followStatus!!.copy(runState = "STARTING", statusAgeMs = null, startup = startup)
        assertTrue(followPresentation(state.copy(followStatus = starting)).subtitle.contains("打开相机"))
        assertEquals("正在启动跟随", followPresentation(state.copy(followStatus = starting.copy(startup = null))).title)
        assertTrue(followPresentation(state.copy(followStatus = starting.copy(startup = startup.copy(stage = "future_stage")))).subtitle.contains("启动中"))
        assertEquals("跟随出现异常", followPresentation(state.copy(followStatus = starting.copy(runState = "FAULT", fault = "startup deadline"))).title)
        assertEquals("正在停止跟随", followPresentation(state.copy(followStatus = starting.copy(runState = "STOPPING", startup = startup.copy(completed = true)))).title)
    }

    @Test fun phoneElapsedTimeAgesTelemetryWithoutComparingBoardClocks() {
        val state = running().copy(followLastReplyElapsedMs = 10_000)
        assertTrue(followPresentation(state, 10_550).rangeFresh)
        assertFalse(followPresentation(state, 10_551).rangeFresh)
        assertEquals("状态已过期", followPresentation(state, 11_981).title)
        assertEquals("状态已过期", followPresentation(state, 9_999).title)
        assertEquals("状态已过期", followPresentation(state).title)
    }

    @Test fun stopAndUnknownCommandDoNotClaimActiveFollowing() {
        val state = running()
        assertEquals("运行中，已发停止指令", followPresentation(state.copy(followStatus = state.followStatus!!.copy(commandStop = true))).title)
        assertEquals("运行中", followPresentation(state.copy(followStatus = state.followStatus!!.copy(commandStop = null))).title)
    }

    @Test fun releasedControlRetainsHistoricalFaultWithoutClaimingCurrentMonitoring() {
        val state = running().copy(followActive = false,
            followStatus = running().followStatus!!.copy(runState = "FAULT", fault = "close failed", processExited = true))
        val result = followPresentation(state)
        assertEquals("上次跟随异常", result.title)
        assertTrue(result.subtitle.contains("上次记录"))
        assertNoCurrentTelemetry(result)
    }

    @Test fun cancellationNeedsAConfirmedLocalOrProtocolOutcomeAndCannotHideFaults() {
        val localCancelled = BoardUiState(connected = true, followStartCancelled = true)
        assertEquals("启动已取消", followPresentation(localCancelled).title)
        val terminalCancelled = localCancelled.copy(followTerminal = true,
            followStatus = FollowStatus("STOPPED", false, FollowCamera.ASTRA_PRO, processExited = true))
        assertEquals("启动已取消", followPresentation(terminalCancelled).title)
        assertEquals("跟随异常结束", followPresentation(terminalCancelled.copy(
            followStatus = terminalCancelled.followStatus!!.copy(fault = "close failed"))).title)
        assertEquals("状态待确认", followPresentation(localCancelled.copy(followUncertain = true)).title)
        assertEquals("上次操作未完成", followPresentation(localCancelled.copy(followError = "close failed")).title)
    }
}

private fun assertNoCurrentTelemetry(result: FollowPresentation) {
    assertFalse(result.fresh)
    assertFalse(result.targetFresh)
    assertFalse(result.rangeFresh)
}
