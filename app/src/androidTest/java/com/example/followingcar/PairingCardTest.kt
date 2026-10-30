package com.example.followingcar

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.mutableStateOf
import com.example.followingcar.ui.theme.FollowingCarTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PairingCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bluetoothCheckDoesNotTriggerProvisioning() {
        var provisions = 0
        var checked: String? = null
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(supported = true), true, false, { provisions++ }, {}, {}, {},
                onCheckBluetooth = { checked = it })
        } }
        compose.onNodeWithTag("checkBluetooth").performClick()
        assertEquals(DEFAULT_PAIRING_BOARD_ADDRESS, checked)
        assertEquals(0, provisions)
    }

    @Test fun bluetoothCheckCannotRunDuringUnknownProvisioning() {
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(confirmationRequired = true), true, false, {}, {}, {}, {},
                onCheckBluetooth = {})
        } }
        compose.onNodeWithTag("checkBluetooth").assertDoesNotExist()
    }

    @Test fun singleButtonUsesConfiguredBoardWithoutShowingCredentials() {
        var selected: String? = null
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(supported = true), true, false,
                { selected = it }, {}, {}, {})
        } }
        compose.onNodeWithTag("startPairing").performClick()
        assertEquals(DEFAULT_PAIRING_BOARD_ADDRESS, selected)
        compose.onNodeWithTag("pairingBoardAddress").assertDoesNotExist()
    }

    @Test fun unknownResultOffersQueryInsteadOfStartingOrClosingHotspot() {
        var checks = 0
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(hotspotActive = true, confirmationRequired = true), true, false,
                {}, {}, { checks++ }, {})
        } }
        compose.onNodeWithTag("checkPairing").performClick()
        assertEquals(1, checks)
        compose.onNodeWithTag("startPairing").assertDoesNotExist()
        compose.onNodeWithTag("stopPairingHotspot").assertDoesNotExist()
    }

    @Test fun connectedSshPreventsHotspotTeardown() {
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(hotspotActive = true, boardIp = "192.168.45.20"), false, true,
                {}, {}, {}, {})
        } }
        compose.onNodeWithTag("stopPairingHotspot").assertIsNotEnabled()
    }

    @Test fun pendingOperationShowsCancelAndCannotStartAnother() {
        var cancelled = false
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(busy = true), true, false, {}, { cancelled = true }, {}, {})
        } }
        compose.onNodeWithTag("cancelPairing").assertIsEnabled().performClick()
        assertEquals(true, cancelled)
        compose.onNodeWithTag("startPairing").assertDoesNotExist()
    }

    @Test fun retainedHotspotRequiresFreshBoardCheckBeforeReconnect() {
        var checks = 0
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(hotspotActive = true, boardIp = "192.168.45.20",
                status = "开发板已接入手机热点"), true, false, {}, {}, { checks++ }, {})
        } }
        compose.onNodeWithTag("pairingStatus").assertTextEquals("配对热点已保留，尚未建立 SSH 连接")
        compose.onNodeWithTag("pairingNetwork").assertTextEquals("上次确认的小车地址 192.168.45.20")
        compose.onNodeWithTag("reconnectPairedBoard").performClick()
        assertEquals(1, checks)
    }

    @Test fun recoveryLoadingShowsProgressWithoutAnyNewNetworkAction() {
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(recoveryLoading = true), true, false, {}, {}, {}, {},
                onCheckBluetooth = {})
        } }
        compose.onNodeWithTag("pairingProgress").assertExists()
        listOf("startPairing", "checkBluetooth", "pairingAdvanced", "checkPairing",
            "stopPairingHotspot", "cancelPairing").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    @Test fun loadingRecoveryAlsoHidesActionsForAnExistingInMemoryHotspot() {
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(recoveryLoading = true, hotspotActive = true,
                boardIp = "192.168.45.20"), true, false, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("stopPairingHotspot").assertDoesNotExist()
        compose.onNodeWithTag("reconnectPairedBoard").assertDoesNotExist()
        compose.onNodeWithTag("startPairing").assertDoesNotExist()
    }

    @Test fun latestRecoveryErrorDoesNotReplaceTheFirstFailure() {
        val state = mutableStateOf(PairingState(confirmationRequired = true,
            error = "读取小车响应超时", firstError = "读取小车响应超时"))
        compose.setContent { FollowingCarTheme {
            PairingCard(state.value, true, false, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("pairingError").assertTextEquals("读取小车响应超时")
        compose.onNodeWithTag("pairingFirstError").assertDoesNotExist()

        compose.runOnIdle { state.value = state.value.copy(error = "正在等待小车重新上线") }
        compose.onNodeWithTag("pairingError").assertTextEquals("正在等待小车重新上线")
        compose.onNodeWithTag("pairingFirstError").assertTextEquals("首次异常：读取小车响应超时")
    }

    @Test fun automaticRecoveryOffersCancelWithoutConcurrentQueryOrProvision() {
        var cancellations = 0
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(stage = PairingStage.RECOVERING, busy = true,
                confirmationRequired = true, hotspotActive = true), false, false,
                {}, { cancellations += 1 }, {}, {}, onCheckBluetooth = {})
        } }
        compose.onNodeWithTag("pairingProgress").assertExists()
        compose.onNodeWithTag("cancelPairing").assertIsEnabled().performClick()
        assertEquals(1, cancellations)
        listOf("checkPairing", "startPairing", "stopPairingHotspot", "checkBluetooth", "pairingAdvanced").forEach {
            compose.onNodeWithTag(it).assertDoesNotExist()
        }
    }

    @Test fun unknownResultAfterHotspotLossCannotChangeThePendingBoard() {
        val state = mutableStateOf(PairingState(supported = true))
        var checks = 0
        var provisions = 0
        compose.setContent { FollowingCarTheme {
            PairingCard(state.value, true, false, { provisions += 1 }, {}, { checks += 1 }, {},
                onCheckBluetooth = {})
        } }
        compose.onNodeWithTag("pairingAdvanced").performClick()
        compose.onNodeWithTag("pairingBoardAddress").assertExists()

        compose.runOnIdle {
            state.value = state.value.copy(stage = PairingStage.UNKNOWN, confirmationRequired = true,
                hotspotActive = false)
        }
        compose.onNodeWithTag("pairingAdvanced").assertDoesNotExist()
        compose.onNodeWithTag("pairingBoardAddress").assertDoesNotExist()
        compose.onNodeWithTag("startPairing").assertDoesNotExist()
        compose.onNodeWithTag("checkBluetooth").assertDoesNotExist()
        compose.onNodeWithTag("checkPairing").assertIsEnabled().performClick()
        assertEquals(1, checks)
        assertEquals(0, provisions)
    }

    @Test fun anUnknownRequestCannotBeCheckedDuringCompetingControl() {
        compose.setContent { FollowingCarTheme {
            PairingCard(PairingState(confirmationRequired = true), false, false, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("checkPairing").assertIsNotEnabled()
        compose.onNodeWithTag("pairingAdvanced").assertDoesNotExist()
        compose.onNodeWithTag("startPairing").assertDoesNotExist()
    }

    @Test fun restoredSelectedBoardIsUsedAfterTheOldRequestIsConfirmed() {
        val state = mutableStateOf(PairingState(recoveryLoading = true))
        var selected: String? = null
        compose.setContent { FollowingCarTheme {
            PairingCard(state.value, true, false, { selected = it }, {}, {}, {})
        } }
        compose.runOnIdle {
            state.value = state.value.copy(recoveryLoading = false, selectedAddress = "12:34:56:78:90:AB")
        }
        compose.onNodeWithTag("startPairing").performClick()
        assertEquals("12:34:56:78:90:AB", selected)
    }
}
