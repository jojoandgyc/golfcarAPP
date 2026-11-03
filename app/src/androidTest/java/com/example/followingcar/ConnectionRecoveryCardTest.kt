package com.example.followingcar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.followingcar.ui.theme.FollowingCarTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fake state and callbacks only; no ViewModel, Bluetooth, Wi-Fi, SSH or board access. */
@RunWith(AndroidJUnit4::class)
class ConnectionRecoveryCardTest {
    @get:Rule val compose = createComposeRule()

    private fun readyWireless() = WifiDirectState(supported = true, permissionGranted = true,
        wifiEnabled = true, groupInfoKnown = true)

    private fun content(
        pairing: () -> PairingState = { PairingState() },
        wireless: () -> WifiDirectState = { readyWireless() },
        canManage: Boolean = true,
        canRefresh: Boolean = true,
        check: () -> Unit = {},
        cancel: () -> Unit = {},
        refresh: () -> Unit = {},
    ) {
        compose.setContent { FollowingCarTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ConnectionRecoveryCard(pairing(), wireless(), canManage, canRefresh, check, cancel, refresh)
            }
        } }
    }

    @Test fun confirmedIdleStateNeedsNoRecoveryCard() {
        content(pairing = { PairingState(stage = PairingStage.PAIRED, hotspotActive = true) })
        compose.onNodeWithTag("connectionRecoveryCard").assertDoesNotExist()
    }

    @Test fun ordinaryErrorWithoutPendingNetworkResultDoesNotOpenRecovery() {
        content(pairing = { PairingState(stage = PairingStage.ERROR, error = "检查失败") },
            wireless = { readyWireless().copy(error = "读取失败") })
        compose.onNodeWithTag("connectionRecoveryCard").assertDoesNotExist()
    }

    @Test fun unknownPairingKeepsRecoveryAvailableWithoutAnyConnectionMethodDependency() {
        var checks = 0
        var cancellations = 0
        var refreshes = 0
        content(pairing = { PairingState(stage = PairingStage.UNKNOWN, confirmationRequired = true) },
            check = { checks++ }, cancel = { cancellations++ }, refresh = { refreshes++ })
        compose.onNodeWithTag("connectionRecoveryTitle").assertTextEquals("本地网络待核对")
        compose.onNodeWithTag("connectionPairingRecoveryReason").assertTextEquals(
            "上次配网结果尚未确认；可先使用远程连接，本地连接需核对。")
        compose.runOnIdle { assertEquals(0, checks + cancellations + refreshes) }
        compose.onNodeWithTag("checkPairing").assertTextEquals("恢复并核对配网").performClick()
        compose.runOnIdle {
            assertEquals(1, checks)
            assertEquals(0, cancellations)
            assertEquals(0, refreshes)
        }
    }

    @Test fun recoveryLoadingDoesNotOfferOrAutomaticallyStartAnotherOperation() {
        var actions = 0
        content(pairing = { PairingState(recoveryLoading = true, confirmationRequired = true) },
            check = { actions++ }, cancel = { actions++ }, refresh = { actions++ })
        compose.onNodeWithTag("connectionRecoveryProgress").assertExists()
        compose.onNodeWithTag("connectionPairingRecoveryReason").assertTextEquals("正在读取上次配网记录，请稍候。")
        compose.onNodeWithTag("checkPairing").assertDoesNotExist()
        compose.onNodeWithTag("cancelPairing").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun busyPairingAlwaysAllowsCancelEvenWhenLoadingAndMutationIsBlocked() {
        var cancellations = 0
        var checks = 0
        content(pairing = { PairingState(stage = PairingStage.RECOVERING, busy = true,
            recoveryLoading = true, confirmationRequired = true) }, canManage = false,
            check = { checks++ }, cancel = { cancellations++ })
        compose.onNodeWithTag("connectionRecoveryTitle").assertTextEquals("配网进行中")
        compose.onNodeWithTag("cancelPairing").assertIsEnabled().performClick()
        compose.onNodeWithTag("checkPairing").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, cancellations); assertEquals(0, checks) }
    }

    @Test fun busyStageExplainsWhatTheAppIsWaitingFor() {
        val pairing = mutableStateOf(PairingState(busy = true, stage = PairingStage.PAIRING_BLUETOOTH))
        content(pairing = { pairing.value })
        compose.onNodeWithTag("connectionPairingRecoveryReason").assertTextEquals(
            "正在配对小车蓝牙，请确认手机系统的配对提示。")
        compose.runOnIdle { pairing.value = pairing.value.copy(stage = PairingStage.WAITING_NETWORK) }
        compose.onNodeWithTag("connectionPairingRecoveryReason").assertTextEquals("正在等待小车接入手机热点。")
    }

    @Test fun blockedRecoveryExplainsSshAndControlRequirementWithoutInvokingCallback() {
        var checks = 0
        content(pairing = { PairingState(confirmationRequired = true) }, canManage = false,
            check = { checks++ })
        compose.onNodeWithTag("checkPairing").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("connectionPairingRecoveryBlocked").assertTextEquals(
            "请先结束当前控制并断开 SSH，等待其他网络操作完成后再恢复配网。")
        compose.runOnIdle { assertEquals(0, checks) }
    }

    @Test fun wirelessConfirmationHasIndependentReadOnlyRefreshAction() {
        var checks = 0
        var refreshes = 0
        content(wireless = { readyWireless().copy(confirmationRequired = true) }, canManage = false,
            check = { checks++ }, refresh = { refreshes++ })
        compose.onNodeWithTag("connectionWirelessRecoveryReason").assertTextEquals(
            "上次 Wi-Fi Direct 操作结果尚未确认，请刷新无线状态后再使用本地连接。")
        compose.runOnIdle { assertEquals(0, refreshes) }
        compose.onNodeWithTag("connectionRefreshWireless").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(0, checks) }
    }

    @Test fun wirelessBusyCannotIssueConcurrentRefresh() {
        var refreshes = 0
        content(wireless = { readyWireless().copy(busy = true, confirmationRequired = true) },
            refresh = { refreshes++ })
        compose.onNodeWithTag("connectionRecoveryProgress").assertExists()
        compose.onNodeWithTag("connectionRefreshWireless").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("cancelPairing").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, refreshes) }
    }

    @Test fun wirelessReadGuardAndMissingPermissionAreExplained() {
        content(wireless = { readyWireless().copy(confirmationRequired = true, permissionGranted = false) },
            canRefresh = false)
        compose.onNodeWithTag("connectionRefreshWireless").assertIsNotEnabled()
        compose.onNodeWithTag("connectionWirelessRecoveryReason").assertTextEquals(
            "缺少附近 Wi-Fi 设备权限，暂时无法确认上次直连操作结果。")
        compose.onNodeWithTag("connectionWirelessRecoveryBlocked").assertExists()
    }

    @Test fun disabledRadioAndUnknownGroupHaveDifferentRecoveryReasons() {
        val wireless = mutableStateOf(readyWireless().copy(confirmationRequired = true, wifiEnabled = false))
        content(wireless = { wireless.value })
        compose.onNodeWithTag("connectionWirelessRecoveryReason").assertTextEquals(
            "手机 Wi-Fi 已关闭，上次直连操作结果尚未确认；开启后请刷新无线状态。")
        compose.runOnIdle { wireless.value = wireless.value.copy(wifiEnabled = true, groupInfoKnown = false) }
        compose.onNodeWithTag("connectionWirelessRecoveryReason").assertTextEquals(
            "系统尚未确认无线组状态，请刷新后再使用本地连接；也可先使用远程连接。")
    }

    @Test fun simultaneousRecoveryKeepsBothActionsAndHidesAllSensitiveStateText() {
        val marker = "sensitive-journal-address-credential"
        var checks = 0
        var refreshes = 0
        content(pairing = { PairingState(confirmationRequired = true, selectedAddress = marker,
            boardIp = marker, status = marker, error = marker, firstError = marker) },
            wireless = { readyWireless().copy(confirmationRequired = true, networkName = marker,
                passphrase = marker, ownerAddress = marker, status = marker, error = marker) },
            check = { checks++ }, refresh = { refreshes++ })
        compose.onNodeWithText(marker, substring = true).assertDoesNotExist()
        compose.onNodeWithTag("checkPairing").performScrollTo().performClick()
        compose.onNodeWithTag("connectionRefreshWireless").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, checks); assertEquals(1, refreshes) }
    }

    @Test fun confirmedResultRemovesCardWithoutExtraActions() {
        val pairing = mutableStateOf(PairingState(confirmationRequired = true))
        var checks = 0
        content(pairing = { pairing.value }, check = { checks++ })
        compose.onNodeWithTag("connectionRecoveryCard").assertExists()
        compose.runOnIdle { pairing.value = pairing.value.copy(confirmationRequired = false) }
        compose.onNodeWithTag("connectionRecoveryCard").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, checks) }
    }
}
