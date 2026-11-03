package com.example.followingcar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Offline UI checks: callbacks only, with no SSH, register writes or motor access. */
@RunWith(AndroidJUnit4::class)
class ParkingControlsTest {
    @get:Rule val compose = createComposeRule()

    private fun currents(enabled: Boolean = false) = ParkingSettings(enabled = enabled,
        leftCurrentAmps = 4.25, rightCurrentAmps = 5.5, maxCurrentAmps = 20.0)

    private fun ready(settings: ParkingSettings = currents()) = BoardUiState(
        connected = true, driveEnabled = true, driveConfirmedStopped = true,
        driveFeedbackVerified = true, driveFeedbackLeftRpm = 0, driveFeedbackRightRpm = 0,
        driveLastFeedbackNanos = TEST_NOW_NANOS, driveFeedbackAgeAtReceiptMs = 0L,
        parkingSupported = true, parkingSettings = settings,
    )

    @Test fun unknownOrUnsupportedStateCannotChangeParkingAndDoesNotInventCurrent() {
        var changes = 0
        val state = mutableStateOf(BoardUiState())
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) { changes++ } } }
        assertControlsDisabled()
        compose.onNodeWithTag("parkingSwitch").performClick()
        compose.onNodeWithTag("parkingLeftCurrent").assertTextEquals("左：— A")
        compose.onNodeWithTag("parkingRightCurrent").assertTextEquals("右：— A")
        compose.onNodeWithTag("parkingMaxCurrent").assertTextEquals("最大输出电流：— A")
        compose.onNodeWithTag("parkingFeedback").assertTextEquals("最近轮速反馈：尚无可信读数")
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("未连接小车")
        compose.onNodeWithTag("parkingTargetSpeeds").assertTextEquals("手动控制未启用，无实时转速目标")

        compose.runOnIdle { state.value = ready().copy(parkingSupported = false) }
        assertControlsDisabled()
        compose.onNodeWithTag("parkingSwitch").performClick()
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun toggleWaitsForAcknowledgementAndPreservesDistinctReadOnlyCurrents() {
        val requests = mutableListOf<Boolean>()
        val initial = currents()
        val state = mutableStateOf(ready(initial))
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) { requests += it } } }
        assertNoCurrentEditors()
        compose.onNodeWithTag("parkingLeftCurrent").assertTextEquals("左：4.25 A")
        compose.onNodeWithTag("parkingRightCurrent").assertTextEquals("右：5.5 A")
        compose.onNodeWithTag("parkingMaxCurrent").assertTextEquals("最大输出电流：20 A")
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled().performClick()
        compose.onNodeWithTag("parkingSwitch").assertIsOff()
        compose.runOnIdle {
            assertEquals(listOf(true), requests)
            assertEquals(initial, state.value.parkingSettings)
            state.value = ready(initial.copy(enabled = true))
        }
        compose.onNodeWithTag("parkingSwitch").assertIsOn().performClick()
        compose.onNodeWithTag("parkingSwitch").assertIsOn()
        compose.runOnIdle {
            assertEquals(listOf(true, false), requests)
            assertEquals(initial.copy(enabled = true), state.value.parkingSettings)
        }
    }

    @Test fun motionUnverifiedFeedbackPendingAndControlLocksDisableChanges() {
        var changes = 0
        val state = mutableStateOf(ready())
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) { changes++ } } }
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled()
        val blockedStates = listOf(
            ready().copy(driveLeft = 10, driveRight = -10),
            ready().copy(driveConfirmedStopped = false),
            ready().copy(driveFeedbackVerified = false),
            ready().copy(driveFeedbackLeftRpm = null),
            ready().copy(driveFeedbackRightRpm = -2),
            ready().copy(parkingUpdating = true),
            ready().copy(driveStopping = true),
            ready().copy(busy = true),
            ready().copy(followUncertain = true),
        )
        for (blocked in blockedStates) {
            compose.runOnIdle { state.value = blocked }
            assertControlsDisabled()
            compose.onNodeWithTag("parkingSwitch").performClick()
        }
        compose.runOnIdle { assertEquals(0, changes) }
    }

    @Test fun zeroOrInvalidLimitsPreventEnablingAndPointToExplicitMotorConfiguration() {
        var changes = 0
        val state = mutableStateOf(ready(ParkingSettings()))
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) { changes++ } } }
        val invalid = listOf(
            ParkingSettings(),
            currents().copy(leftCurrentAmps = 0.0),
            currents().copy(rightCurrentAmps = 0.0),
            currents().copy(maxCurrentAmps = 0.0),
            currents().copy(leftCurrentAmps = 20.01),
            currents().copy(rightCurrentAmps = 60.01, maxCurrentAmps = 70.0),
        )
        for (settings in invalid) {
            compose.runOnIdle { state.value = ready(settings) }
            compose.onNodeWithTag("parkingSwitch").assertIsOff().assertIsNotEnabled().performClick()
            compose.onNodeWithTag("parkingHint").assertTextContains("到电机页读取并调整", substring = true)
            assertNoCurrentEditors()
        }
        compose.runOnIdle {
            assertEquals(0, changes)
            state.value = ready(ParkingSettings(leftCurrentAmps = 0.01,
                rightCurrentAmps = 60.0, maxCurrentAmps = 60.0))
        }
        compose.onNodeWithTag("parkingLeftCurrent").assertTextEquals("左：0.01 A")
        compose.onNodeWithTag("parkingRightCurrent").assertTextEquals("右：60 A")
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled()
    }

    @Test fun alreadyEnabledParkingCanBeDisabledEvenIfCurrentsNoLongerPermitEnabling() {
        val requests = mutableListOf<Boolean>()
        val initial = currents(enabled = true).copy(leftCurrentAmps = 0.0)
        compose.setContent { MaterialTheme { ParkingControls(ready(initial), TEST_NOW_NANOS) { requests += it } } }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("已开启，可关闭")
        compose.onNodeWithTag("parkingHint").assertTextContains("可关闭驻车；再次开启前需核对电流", substring = true)
        compose.onNodeWithTag("parkingSwitch").assertIsOn().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(false), requests) }
        assertNoCurrentEditors()
    }

    @Test fun zeroTargetDoesNotClaimPhysicalStopWithoutVerifiedRepeatedZeroFeedback() {
        val state = mutableStateOf(ready().copy(driveConfirmedStopped = false,
            driveFeedbackVerified = false, driveFeedbackLeftRpm = null, driveFeedbackRightRpm = null))
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) {} } }
        compose.onNodeWithTag("parkingTargetSpeeds").assertTextEquals("目标已回零 · 等待新鲜停车反馈")
        compose.onNodeWithTag("parkingFeedback").assertTextEquals("最近轮速反馈：尚无可信读数")
        assertControlsDisabled()
        compose.runOnIdle {
            state.value = ready().copy(driveConfirmedStopped = false, driveFeedbackLeftRpm = 12)
        }
        compose.onNodeWithTag("parkingFeedback")
            .assertTextEquals("最近轮速反馈：左 12 / 右 0 RPM · 尚未停稳")
        assertControlsDisabled()
        compose.runOnIdle { state.value = ready().copy(driveConfirmedStopped = false) }
        compose.onNodeWithTag("parkingFeedback").assertTextEquals("最近轮速反馈已回零，正在复核停车")
        assertControlsDisabled()
        compose.runOnIdle { state.value = ready() }
        compose.onNodeWithTag("parkingFeedback").assertTextEquals("最近轮速反馈：左 0 / 右 0 RPM · 已确认停车")
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled()
    }

    @Test fun expiredFeedbackAndAckTransitAgeDisableSwitchWithoutClaimingFreshStop() {
        var changes = 0
        val now = mutableStateOf(TEST_NOW_NANOS)
        val state = mutableStateOf(ready())
        compose.setContent { MaterialTheme { ParkingControls(state.value, now.value) { changes++ } } }
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled()
        compose.runOnIdle { now.value += 501_000_000L }
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("parkingFeedback").assertTextContains("已过期", substring = true)
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("轮速反馈已过期")
        compose.onNodeWithTag("parkingTargetSpeeds").assertTextEquals("目标已回零 · 等待新鲜停车反馈")
        compose.runOnIdle {
            now.value = TEST_NOW_NANOS + 60_000_000L
            state.value = ready().copy(driveFeedbackAgeAtReceiptMs = 450L)
        }
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, changes)
            state.value = ready().copy(driveLastFeedbackNanos = now.value)
        }
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled()
    }

    @Test fun feedbackRecheckShowsActualSmallSpeedAndKeepsSwitchLockedUntilRecoveryClears() {
        val state = mutableStateOf(ready().copy(driveFeedbackRecheck = true,
            driveConfirmedStopped = false, driveFeedbackLeftRpm = -1))
        var requests = 0
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) { requests++ } } }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("轮速反馈复核中，保持停车")
        compose.onNodeWithTag("parkingFeedback").assertTextContains("左 -1 / 右 0 RPM · 复核中", substring = true)
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled().performClick()
        compose.runOnIdle { state.value = ready().copy(driveFeedbackRecheck = true, driveRecoveryNeedsRelease = true) }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("反馈已恢复，请松手后再操作")
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, requests); state.value = ready() }
        compose.onNodeWithTag("parkingSwitch").assertIsEnabled()
    }

    @Test fun standstillWaitingShowsPausedParkingAndMeasuredMotionWithoutCallingItAControlFailure() {
        val state = mutableStateOf(ready().copy(driveStandstillPending = true, driveFeedbackRecheck = true,
            driveConfirmedStopped = false, driveFeedbackLeftRpm = 7, driveFeedbackRightRpm = -3))
        var requests = 0
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) { requests++ } } }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("正在减速，等待停车确认")
        compose.onNodeWithTag("parkingSwitch").assertIsOff().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("parkingFeedback").assertTextContains("左 7 / 右 -3 RPM", substring = true)
        compose.onNodeWithTag("parkingHint").assertTextContains("电子驻车已暂停", substring = true)
        compose.runOnIdle { state.value = ready().copy(driveStandstillPending = true, driveFeedbackRecheck = true) }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("车轮已停稳，请松手后再操作")
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, requests); state.value = ready() }
        compose.onNodeWithTag("parkingSwitch").assertIsOff().assertIsEnabled()
    }

    @Test fun pendingZeroFeedbackThatExpiresDoesNotClaimCurrentStandstill() {
        val state = ready().copy(driveStandstillPending = true, driveFeedbackRecheck = true)
        var requests = 0
        compose.setContent { MaterialTheme {
            ParkingControls(state, TEST_NOW_NANOS + 501_000_000L) { requests++ }
        } }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("停车等待中，轮速反馈待更新")
        compose.onNodeWithTag("parkingFeedback").assertTextContains("已过期", substring = true)
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun zeroCurrentReasonNamesAffectedSideBesideSwitchAndShowsRealValues() {
        var requests = 0
        val state = mutableStateOf(ready(ParkingSettings(maxCurrentAmps = 15.0)))
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) { requests++ } } }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("左右驻车电流为 0，无法开启")
        compose.onNodeWithTag("parkingHint").assertTextContains("左 0 A、右 0 A、上限 15 A", substring = true)
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled().performClick()
        compose.runOnIdle {
            state.value = ready(ParkingSettings(leftCurrentAmps = 0.0, rightCurrentAmps = 4.25, maxCurrentAmps = 15.0))
        }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("左侧驻车电流为 0，无法开启")
        compose.onNodeWithTag("parkingHint").assertTextContains("左 0 A、右 4.25 A、上限 15 A", substring = true)
        compose.runOnIdle {
            state.value = ready(ParkingSettings(leftCurrentAmps = 4.25, rightCurrentAmps = 0.0, maxCurrentAmps = 15.0))
        }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("右侧驻车电流为 0，无法开启")
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, requests) }
    }

    @Test fun historicalCurrentSnapshotNeverRestoresSwitchStateOrAuthorizesControl() {
        var requests = 0
        val history = currents(enabled = true)
        val state = mutableStateOf(ready().copy(driveEnabled = false, parkingSettings = null,
            lastReadParkingSettings = history, driveFeedbackVerified = false))
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) { requests++ } } }
        compose.onNodeWithTag("parkingCurrentSource").assertTextEquals("上次读取（非实时）")
        compose.onNodeWithTag("parkingLeftCurrent").assertTextEquals("左：4.25 A")
        compose.onNodeWithTag("parkingRightCurrent").assertTextEquals("右：5.5 A")
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("未启用手动控制")
        compose.onNodeWithTag("parkingTargetSpeeds").assertTextEquals("手动控制未启用，无实时转速目标")
        compose.onNodeWithTag("parkingFeedback").assertTextEquals("最近轮速反馈：左 0 / 右 0 RPM · 未验证或已过期")
        compose.onNodeWithTag("parkingSwitch").assertIsOff().assertIsNotEnabled().performClick()
        compose.runOnIdle { state.value = state.value.copy(driveEnabled = true) }
        compose.onNodeWithTag("parkingAvailability").assertTextEquals("尚未读取驻车配置")
        compose.onNodeWithTag("parkingSwitch").assertIsOff().assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, requests)
            state.value = ready(currents().copy(leftCurrentAmps = 3.5)).copy(lastReadParkingSettings = history)
        }
        compose.onNodeWithTag("parkingCurrentSource").assertTextEquals("已读取的驻车电流上限")
        compose.onNodeWithTag("parkingLeftCurrent").assertTextEquals("左：3.5 A")
        compose.onNodeWithTag("parkingSwitch").assertIsOff().assertIsEnabled()
    }

    @Test fun availabilityPrioritizesConnectionLifecycleAndPendingOperationBeforeCurrentIssue() {
        val zero = ready(ParkingSettings(maxCurrentAmps = 15.0))
        val state = mutableStateOf(zero)
        compose.setContent { MaterialTheme { ParkingControls(state.value, TEST_NOW_NANOS) {} } }
        val cases = listOf(
            zero.copy(connected = false, driveStarting = true) to "未连接小车",
            zero.copy(driveStarting = true) to "手动控制正在启动",
            zero.copy(driveStopping = true) to "手动控制正在停用",
            zero.copy(driveEnabled = false) to "未启用手动控制",
            zero.copy(parkingUpdating = true) to "正在切换驻车",
            zero.copy(busy = true) to "其他操作正在进行",
        )
        for ((value, label) in cases) {
            compose.runOnIdle { state.value = value }
            compose.onNodeWithTag("parkingAvailability").assertTextEquals(label)
            compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled()
        }
    }

    private fun assertNoCurrentEditors() {
        compose.onNodeWithTag("parkingDecrease").assertDoesNotExist()
        compose.onNodeWithTag("parkingIncrease").assertDoesNotExist()
    }

    private fun assertControlsDisabled() {
        compose.onNodeWithTag("parkingSwitch").assertIsNotEnabled()
        assertNoCurrentEditors()
    }

    private companion object {
        const val TEST_NOW_NANOS = 10_000_000_000L
    }
}
