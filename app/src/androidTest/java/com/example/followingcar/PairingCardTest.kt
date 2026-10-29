package com.example.followingcar

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
}
