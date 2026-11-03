package com.example.followingcar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.example.followingcar.ui.theme.FollowingCarTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI-only fixtures: no ViewModel, SSH, wireless controller or system settings. */
class ConnectionSetupCardTest {
    @get:Rule val compose = createComposeRule()

    @Test fun choosingMethodsOnlyChangesSelectionAndNeverConnectsOrImports() {
        val actions = Actions()
        val method = mutableStateOf(ConnectionMethod.LAN)
        compose.setContent { FollowingCarTheme {
            Setup(savedState(), method.value, actions,
                onMethod = { actions.methods += it; method.value = it })
        } }
        compose.onNodeWithTag("connectionStatus").assertTextEquals("SSH 未连接")
        compose.onNodeWithTag("connectionLanMode").assertIsSelected().performClick()
        compose.onNodeWithTag("connectionDirectMode").assertIsEnabled().performClick()
        compose.onNodeWithTag("connectionDirectMode").assertIsSelected()
        compose.onNodeWithTag("connectionRemoteMode").assertIsEnabled().performClick()
        compose.onNodeWithTag("connectionRemoteMode").assertIsSelected()
        compose.onNodeWithTag("connectionTarget").assertTextEquals("relay.example.com:2443")
        compose.onNodeWithTag("connectionStatus").assertTextEquals("SSH 未连接")
        compose.runOnIdle {
            assertEquals(listOf(ConnectionMethod.WIFI_DIRECT, ConnectionMethod.REMOTE), actions.methods)
            assertEquals(0, actions.connections + actions.imports + actions.disconnects + actions.cancels)
        }
    }

    @Test fun localRecoveryUncertaintyDoesNotHideRemoteConnectOrChangeControlLocks() {
        val actions = Actions()
        val state = savedState().copy(remoteMode = true, pairingUnconfirmed = true, followUncertain = true)
        compose.setContent { FollowingCarTheme { Setup(state, ConnectionMethod.REMOTE, actions) } }
        compose.onNodeWithTag("connectionLanMode").assertIsEnabled()
        compose.onNodeWithTag("connectionDirectMode").assertIsEnabled()
        compose.onNodeWithTag("connect").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, actions.connections)
            assertTrue(state.followUncertain)
            assertTrue(state.pairingUnconfirmed)
            assertFalse(state.canStartFollow)
        }
    }

    @Test fun missingOrInvalidRemoteProfileUsesImportAsPrimaryAction() {
        val actions = Actions()
        val state = mutableStateOf(savedState().copy(remoteProfile = null, pairingUnconfirmed = true))
        compose.setContent { FollowingCarTheme {
            Setup(state.value, ConnectionMethod.REMOTE, actions,
                connectionReason = "请先导入远程连接配置。")
        } }
        compose.onNodeWithTag("connect").assertDoesNotExist()
        compose.onNodeWithTag("importRemoteProfile").assertIsEnabled().performClick()
        compose.onNodeWithTag("connectionBlocked").assertTextEquals("请先导入远程连接配置。")
        compose.runOnIdle { state.value = savedState().copy(remoteProfileError = "配置已过期，请重新导入。") }
        compose.onNodeWithTag("remoteProfileError").assertTextEquals("配置已过期，请重新导入。")
        compose.onNodeWithTag("importRemoteProfile").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, actions.imports); assertEquals(0, actions.connections) }
    }

    @Test fun busyCanCancelAndConnectedCanExplicitlyDisconnect() {
        val actions = Actions()
        val state = mutableStateOf(savedState().copy(busy = true))
        compose.setContent { FollowingCarTheme { Setup(state.value, ConnectionMethod.LAN, actions) } }
        compose.onNodeWithTag("connectionStatus").assertTextEquals("SSH 正在连接或操作")
        compose.onNodeWithTag("cancel").assertIsEnabled().performClick()
        compose.onNodeWithTag("connect").assertDoesNotExist()
        compose.onNodeWithTag("connectionRemoteMode").assertIsNotEnabled().performClick()
        compose.runOnIdle { state.value = savedState().copy(connected = true) }
        compose.onNodeWithTag("connectionStatus").assertTextEquals("SSH 已连接")
        compose.onNodeWithTag("cancel").assertDoesNotExist()
        compose.onNodeWithTag("disconnect").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, actions.cancels)
            assertEquals(1, actions.disconnects)
            assertTrue(actions.methods.isEmpty())
            assertEquals(0, actions.connections)
        }
    }

    @Test fun blockedConnectionShowsReasonWhileMethodSelectionRemainsAvailable() {
        val actions = Actions()
        compose.setContent { FollowingCarTheme {
            Setup(savedState(), ConnectionMethod.WIFI_DIRECT, actions,
                connectionReason = "请先允许附近 Wi-Fi 设备权限，再刷新直连状态。")
        } }
        compose.onNodeWithTag("connect").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("connectionBlocked")
            .assertTextEquals("请先允许附近 Wi-Fi 设备权限，再刷新直连状态。")
        compose.onNodeWithTag("connectionLanMode").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(0, actions.connections)
            assertEquals(listOf(ConnectionMethod.LAN), actions.methods)
        }
    }

    @Test fun activeMutationBlocksSelectionImportAndConnectWithVisibleReason() {
        val actions = Actions()
        val method = mutableStateOf(ConnectionMethod.LAN)
        val reason = "无线组操作正在进行，请等待结果确认。"
        compose.setContent { FollowingCarTheme {
            Setup(savedState().copy(remoteProfile = null), method.value, actions,
                selectionReason = reason, connectionReason = reason)
        } }
        compose.onNodeWithTag("connectionSelectionBlocked").assertTextEquals(reason)
        compose.onNodeWithTag("connect").assertIsNotEnabled().performClick()
        listOf("connectionLanMode", "connectionDirectMode", "connectionRemoteMode").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled().performClick()
        }
        compose.runOnIdle { method.value = ConnectionMethod.REMOTE }
        compose.onNodeWithTag("importRemoteProfile").assertIsNotEnabled().performClick()
        compose.runOnIdle {
            assertTrue(actions.methods.isEmpty())
            assertEquals(0, actions.connections + actions.imports)
        }
    }

    @Test fun savedCredentialsAreCollapsedAndPasswordIsMaskedWhenExpanded() {
        val actions = Actions()
        compose.setContent { FollowingCarTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Setup(savedState(), ConnectionMethod.LAN, actions)
            }
        } }
        compose.onNodeWithTag("password").assertDoesNotExist()
        compose.onNodeWithTag("connectionLoginSummary").assertTextEquals("账号 topeet · 密码已保存")
        compose.onNodeWithTag("connectionLoginSettings").performClick()
        compose.onNodeWithTag("password").performScrollTo().assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertEquals("•".repeat(savedState().password.length), passwordRenderedText())
        compose.onNodeWithTag("connectionPasswordToggle").performClick()
        // KeyboardType.Password correctly retains password semantics even when explicitly shown.
        // Inspect the actual text layout rather than mistaking that semantic flag for masking.
        assertEquals(savedState().password, passwordRenderedText())
        compose.onNodeWithTag("connectionLoginSettings").performScrollTo().performClick().performClick()
        compose.onNodeWithTag("password").performScrollTo().assert(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        assertEquals("•".repeat(savedState().password.length), passwordRenderedText())
        compose.runOnIdle { assertEquals(0, actions.connections + actions.imports) }
    }

    private fun passwordRenderedText(): String {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("password").performSemanticsAction(SemanticsActions.GetTextLayoutResult) {
            assertTrue("Password field must expose its rendered text layout", it(layouts))
        }
        assertTrue("Password field must have a text layout", layouts.isNotEmpty())
        return layouts.first().layoutInput.text.text
    }

    @Test fun missingLoginFieldsExpandAutomaticallyButPrimaryActionStaysAboveThem() {
        val actions = Actions()
        compose.setContent { FollowingCarTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Setup(savedState().copy(host = "", username = "", password = ""), ConnectionMethod.LAN, actions)
            }
        } }
        compose.onNodeWithTag("host").assertExists()
        compose.onNodeWithTag("username").assertExists()
        compose.onNodeWithTag("password").assertExists()
        compose.onNodeWithTag("connect").assertIsDisplayed()
        val button = compose.onNodeWithTag("connect").fetchSemanticsNode().boundsInRoot
        val host = compose.onNodeWithTag("host").fetchSemanticsNode().boundsInRoot
        assertTrue("Primary action must precede login fields", button.bottom <= host.top)
    }

    @Test fun validRemoteProfileReplacementIsInSettingsAndDoesNotConnect() {
        val actions = Actions()
        compose.setContent { FollowingCarTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Setup(savedState(), ConnectionMethod.REMOTE, actions)
            }
        } }
        compose.onNodeWithTag("connect").assertIsDisplayed()
        compose.onNodeWithTag("importRemoteProfile").assertDoesNotExist()
        compose.onNodeWithTag("connectionLoginSettings").performClick()
        compose.onNodeWithTag("host").assertDoesNotExist()
        compose.onNodeWithTag("port").assertDoesNotExist()
        compose.onNodeWithTag("remoteServerHost").assertTextEquals("服务器 relay.example.com:2443")
        compose.onNodeWithTag("importRemoteProfile").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, actions.imports); assertEquals(0, actions.connections) }
    }

    @Test fun smallViewportShowsEveryPrimaryActionWithoutScrolling() {
        val actions = Actions()
        val method = mutableStateOf(ConnectionMethod.LAN)
        val state = mutableStateOf(savedState())
        compose.setContent { FollowingCarTheme {
            Box(Modifier.requiredSize(360.dp, 420.dp).clipToBounds().testTag("connectionViewport")) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Setup(state.value, method.value, actions)
                }
            }
        } }
        for (choice in ConnectionMethod.entries) {
            compose.runOnIdle { method.value = choice }
            compose.onNodeWithTag("connectionStatus").assertIsDisplayed().assertTextEquals("SSH 未连接")
            compose.onNodeWithTag("connect").assertIsDisplayed()
        }
        compose.runOnIdle { state.value = savedState().copy(remoteProfile = null) }
        compose.onNodeWithTag("importRemoteProfile").assertIsDisplayed()
        compose.runOnIdle { state.value = savedState().copy(busy = true) }
        compose.onNodeWithTag("cancel").assertIsDisplayed()
        compose.runOnIdle { state.value = savedState().copy(connected = true) }
        compose.onNodeWithTag("disconnect").assertIsDisplayed()
        compose.runOnIdle {
            method.value = ConnectionMethod.LAN
            state.value = savedState().copy(host = "", username = "", password = "")
        }
        compose.onNodeWithTag("connect").assertIsDisplayed()
    }

    @Composable
    private fun Setup(
        state: BoardUiState,
        method: ConnectionMethod,
        actions: Actions,
        selectionReason: String? = state.connectionSelectionBlockReason(),
        connectionReason: String? = null,
        onMethod: (ConnectionMethod) -> Unit = { actions.methods += it },
    ) {
        ConnectionSetupCard(state = state, method = method,
            selectionBlockedReason = selectionReason, connectionBlockedReason = connectionReason,
            onMethodChange = onMethod, onConnect = { actions.connections++ },
            onDisconnect = { actions.disconnects++ }, onCancel = { actions.cancels++ },
            onImport = { actions.imports++ }, onHostChange = {}, onPortChange = {},
            onUsernameChange = {}, onPasswordChange = {})
    }

    private class Actions {
        val methods = mutableListOf<ConnectionMethod>()
        var connections = 0
        var disconnects = 0
        var cancels = 0
        var imports = 0
    }

    private fun savedState() = BoardUiState(password = "test-only-password", remoteProfile = RemoteProfile(
        host = "relay.example.com", clientCertificateSha256 = "00".repeat(32),
        certificateExpiresAtEpochMillis = 4_102_444_800_000L))
}
