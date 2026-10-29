package com.example.followingcar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Uses fake state/callbacks only; never changes a phone/emulator wireless group. */
@RunWith(AndroidJUnit4::class)
class WifiDirectCardTest {
    @get:Rule val compose = createComposeRule()
    private fun ready() = WifiDirectState(supported = true, permissionGranted = true, wifiEnabled = true,
        groupInfoKnown = true, status = "就绪")

    private fun content(state: () -> WifiDirectState, canManage: () -> Boolean = { true },
        permission: () -> Unit = {}, create: () -> Unit = {}, remove: () -> Unit = {},
        discover: () -> Unit = {}, invite: (String) -> Unit = {}, useAddress: (String) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    WifiDirectCard(state(), canManage(), if (canManage()) null else "控制正在运行",
                        onPermission = permission, onWifiSettings = {}, onRefresh = {}, onCreate = create,
                        onRemove = remove, onDiscover = discover, onStopDiscovery = {},
                        onInvite = invite, onUseAddress = useAddress)
                }
            }
        }
    }

    @Test fun permissionAndGroupCreationAlwaysRequireSeparateExplicitActions() {
        val state = mutableStateOf(ready().copy(permissionGranted = false))
        var permissions = 0
        var creates = 0
        content({ state.value }, permission = { permissions++ }, create = { creates++ })
        compose.runOnIdle { assertEquals(0, permissions); assertEquals(0, creates) }
        compose.onNodeWithTag("wifiDirectCreate").assertDoesNotExist()
        compose.onNodeWithTag("wifiDirectPermission").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, permissions); state.value = ready() }
        compose.runOnIdle { assertEquals(0, creates) }
        compose.onNodeWithTag("wifiDirectCreate").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, creates) }
    }

    @Test fun controlLockDisablesPermissionAndNetworkChanges() {
        val state = mutableStateOf(ready().copy(permissionGranted = false))
        content({ state.value }, canManage = { false })
        compose.onNodeWithTag("wifiDirectPermission").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = ready() }
        compose.onNodeWithTag("wifiDirectCreate").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { state.value = ready().copy(groupExists = true, isGroupOwner = true) }
        compose.onNodeWithTag("wifiDirectRemove").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("wifiDirectDiscover").performScrollTo().assertIsNotEnabled()
    }

    @Test fun clientSelectionUsesActualClientAddressAndNeverPhoneGoAddress() {
        var selected: String? = null
        val group = ready().copy(groupExists = true, isGroupOwner = true, ownerAddress = "192.168.49.1",
            clients = listOf(WifiDirectPeer("Board", "board", "192.168.49.22", 0),
                WifiDirectPeer("Wrong GO", "wrong", "192.168.49.1", 0),
                WifiDirectPeer("Pending DHCP", "pending", null, 0)))
        content({ group }, useAddress = { selected = it })
        compose.runOnIdle { assertNull(selected) }
        compose.onNodeWithTag("wifiDirectUse_wrong").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("wifiDirectUse_pending").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("wifiDirectUse_board").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("192.168.49.22", selected) }
    }

    @Test fun passwordIsHiddenAndResetWhenGroupChanges() {
        val state = mutableStateOf(ready().copy(groupExists = true, isGroupOwner = true,
            networkName = "DIRECT-AB-One", passphrase = "example-secret"))
        content({ state.value })
        compose.onNodeWithTag("wifiDirectPassword").assertTextEquals("入组密码：••••••••")
        compose.onNodeWithTag("wifiDirectPasswordToggle").performScrollTo().performClick()
        compose.onNodeWithTag("wifiDirectPassword").assertTextEquals("入组密码：example-secret")
        compose.runOnIdle { state.value = state.value.copy(networkName = "DIRECT-CD-Two", passphrase = "new-secret") }
        compose.onNodeWithTag("wifiDirectPassword").assertTextEquals("入组密码：••••••••")
    }

    @Test fun existingClientGroupCannotInviteOrPretendPhoneIsGo() {
        content({ ready().copy(groupExists = true, isGroupOwner = false) })
        compose.onNodeWithTag("wifiDirectCreate").assertDoesNotExist()
        compose.onNodeWithTag("wifiDirectDiscover").assertDoesNotExist()
        compose.onNodeWithTag("wifiDirectOwnerAddress").assertDoesNotExist()
        compose.onNodeWithTag("wifiDirectRemove").performScrollTo().assertIsEnabled()
    }

    @Test fun timedOutWirelessActionRequiresRefreshBeforeAnotherMutation() {
        val state = mutableStateOf(ready().copy(confirmationRequired = true))
        content({ state.value })
        compose.onNodeWithTag("wifiDirectConfirmationRequired").assertExists()
        compose.onNodeWithTag("wifiDirectCreate").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("wifiDirectRefresh").performScrollTo().assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(groupExists = true, isGroupOwner = true) }
        compose.onNodeWithTag("wifiDirectRemove").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("wifiDirectDiscover").performScrollTo().assertIsNotEnabled()
    }
}
