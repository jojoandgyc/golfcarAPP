package com.example.followingcar

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.example.followingcar.ui.theme.FollowingCarTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Pure UI tests. No network, SSH, lifecycle control callbacks or device settings are used. */
class BoardNetworkStatusCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun uncertainLocalGroupAllowsRemoteImportWithoutClearingControlLock() {
        var imports = 0
        val state = BoardUiState(remoteMode = true, pairingUnconfirmed = true, followUncertain = true)
        compose.setContent { FollowingCarTheme { RemoteConnectionCard(state) { imports++ } } }
        compose.onNodeWithTag("importRemoteProfile").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, imports)
            assertTrue(state.followUncertain)
            assertFalse(state.canStartFollow)
        }
    }

    @Test fun unknownPresenceDoesNotBecomeOnlineFromCachedSshFlagOrInternet() {
        var refreshes = 0
        var reconnects = 0
        val health = BoardNetworkHealth(phone = PhoneNetworkState(observed = true, available = true,
            internetValidated = true, transportLabel = "Wi-Fi"))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = true, remoteMode = false,
                canReconnectRemote = true, refreshing = false, onRefresh = { refreshes++ },
                onReconnectRemote = { reconnects++ })
        } }
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals("小车状态待确认")
        compose.onNodeWithTag("networkLastSeen").assertTextContains("尚无在线确认", substring = true)
        compose.onNodeWithTag("networkRemoteReconnect").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, refreshes); assertEquals(0, reconnects) }
    }

    @Test fun verifiedRemotePresenceIsShownWhenLocalSshIsDisconnected() {
        var reconnects = 0
        val allowed = mutableStateOf(false)
        val health = BoardNetworkHealth(local = RouteHealth(status = Reachability.UNREACHABLE),
            remote = RouteHealth().online(9_000L))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = false, remoteMode = false,
                canReconnectRemote = allowed.value, refreshing = false, onRefresh = {},
                onReconnectRemote = { reconnects++ })
        } }
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals("小车在线 · 公网")
        compose.onNodeWithTag("networkLastSeen").assertTextContains("SSH 未连接", substring = true)
        compose.onNodeWithTag("networkRemoteRecoveryHint").assertTextContains("公网已确认可达", substring = true)
        compose.onNodeWithTag("networkRemoteReconnect").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, reconnects); allowed.value = true }
        compose.onNodeWithTag("networkRemoteReconnect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reconnects) }
    }

    @Test fun expiredEvidenceIsLabeledAndNeverPresentedAsCurrentOnline() {
        val now = mutableStateOf(10_000L)
        val health = BoardNetworkHealth(local = RouteHealth().online(1_000L))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, now.value, connected = true, remoteMode = false,
                canReconnectRemote = false, refreshing = false, onRefresh = {}, onReconnectRemote = {})
        } }
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals("小车在线 · 局域网")
        compose.runOnIdle { now.value = 35_000L }
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals("小车状态待确认")
        compose.onNodeWithTag("networkLocalStatusLastSeen").assertTextContains("上次确认已过期", substring = true)
        compose.onNodeWithTag("networkLastSeen").assertTextContains("34 秒前", substring = true)
    }

    @Test fun localOnlineDoesNotRequirePhoneInternetValidation() {
        val health = BoardNetworkHealth(phone = PhoneNetworkState(observed = true, available = false),
            local = RouteHealth().online(9_000L))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = false, remoteMode = false,
                canReconnectRemote = false, refreshing = false, onRefresh = {}, onReconnectRemote = {})
        } }
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals("小车在线 · 局域网")
        compose.onNodeWithTag("networkPhoneStatus").assertExists()
    }

    @Test fun checkingIsVisibleWhilePreviousFreshConfirmationRemainsValid() {
        val health = BoardNetworkHealth(local = RouteHealth().online(9_000L).checking(),
            remote = RouteHealth(status = Reachability.CHECKING))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = false, remoteMode = false,
                canReconnectRemote = false, refreshing = true, onRefresh = {}, onReconnectRemote = {})
        } }
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals("小车在线 · 局域网")
        compose.onNodeWithTag("networkLocalStatus").assertTextContains("正在检测 · 上次确认仍有效", substring = true)
        compose.onNodeWithTag("networkRemoteStatus").assertTextEquals("公网：正在检测")
    }

    @Test fun refreshingBlocksDuplicateRefreshButAllowsExplicitRemoteRecovery() {
        var refreshes = 0
        var reconnects = 0
        val refreshing = mutableStateOf(true)
        val canRefresh = mutableStateOf(true)
        val health = BoardNetworkHealth(local = RouteHealth(status = Reachability.CHECKING),
            remote = RouteHealth(status = Reachability.UNKNOWN))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = false, remoteMode = false,
                canReconnectRemote = true, refreshing = refreshing.value, onRefresh = { refreshes++ },
                onReconnectRemote = { reconnects++ }, canRefresh = canRefresh.value)
        } }
        compose.onNodeWithTag("networkRefresh").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, refreshes); assertEquals(0, reconnects) }
        compose.onNodeWithTag("networkRemoteReconnect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(0, refreshes); assertEquals(1, reconnects); refreshing.value = false; canRefresh.value = false }
        compose.onNodeWithTag("networkRefresh").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, refreshes); assertEquals(1, reconnects); canRefresh.value = true }
        compose.onNodeWithTag("networkRefresh").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, refreshes); assertEquals(1, reconnects) }
    }

    @Test fun missingRemoteProfileGivesInstructionsWithoutOpeningImport() {
        var reconnects = 0
        val health = BoardNetworkHealth(local = RouteHealth(status = Reachability.UNREACHABLE))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = false, remoteMode = false,
                canReconnectRemote = true, refreshing = false, onRefresh = {},
                onReconnectRemote = { reconnects++ })
        } }
        compose.onNodeWithTag("networkRemoteImportHint").assertTextContains("导入", substring = true)
        compose.onNodeWithTag("networkRemoteReconnect").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, reconnects) }
    }

    @Test fun failedRouteCheckingAgainKeepsRecoveryAvailableWithoutRevivingOldOnline() {
        var reconnects = 0
        val health = BoardNetworkHealth(local = RouteHealth().online(9_000L).unavailable(9_500L).checking(),
            remote = RouteHealth(status = Reachability.UNKNOWN))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = false, remoteMode = false,
                canReconnectRemote = true, refreshing = true, onRefresh = {},
                onReconnectRemote = { reconnects++ })
        } }
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals("小车状态待确认")
        compose.onNodeWithTag("networkLocalStatus").assertTextEquals("局域网：正在检测")
        compose.onNodeWithTag("networkRefresh").assertIsNotEnabled()
        compose.onNodeWithTag("networkRemoteReconnect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reconnects) }
    }

    @Test fun unknownLocalRouteCanOfferExplicitRemoteConnectionBeforeFirstProbe() {
        var reconnects = 0
        val health = BoardNetworkHealth(remote = RouteHealth(status = Reachability.UNKNOWN))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = false, remoteMode = false,
                canReconnectRemote = true, refreshing = false, onRefresh = {},
                onReconnectRemote = { reconnects++ })
        } }
        compose.onNodeWithTag("networkHealthSummary").assertTextEquals("小车状态待确认")
        compose.onNodeWithTag("networkRemoteReconnect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reconnects) }
    }

    @Test fun compactStatusHasNoRefreshAndRecoveryRespectsControlLock() {
        var refreshes = 0
        var reconnects = 0
        val allowed = mutableStateOf(false)
        val health = BoardNetworkHealth(local = RouteHealth(status = Reachability.UNREACHABLE),
            remote = RouteHealth().online(9_000L))
        compose.setContent { FollowingCarTheme {
            BoardNetworkStatusCard(health, 10_000L, connected = false, remoteMode = false,
                canReconnectRemote = allowed.value, refreshing = false, onRefresh = { refreshes++ },
                onReconnectRemote = { reconnects++ }, compact = true)
        } }
        compose.onNodeWithTag("networkHealthCompactSummary").assertTextEquals("小车在线 · 公网")
        compose.onNodeWithTag("networkHealthCompactDetail").assertTextContains("SSH 未连接", substring = true)
        compose.onNodeWithTag("networkRefresh").assertDoesNotExist()
        compose.onNodeWithTag("networkCompactRemoteReconnect").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, refreshes); assertEquals(0, reconnects); allowed.value = true }
        compose.onNodeWithTag("networkCompactRemoteReconnect").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, reconnects) }
    }
}
