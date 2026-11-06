package com.example.followingcar

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only check. Credentials are supplied explicitly or reused from this phone. */
@RunWith(AndroidJUnit4::class)
class BoardScreenIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun phoneCanConnectAndExecuteACommandFromTheScreen() {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("boardPassword").orEmpty()
        val useSavedCredentials = arguments.getString("useSavedBoardCredentials") == "true"
        assumeTrue("Opt in with boardPassword or useSavedBoardCredentials", password.isNotEmpty() || useSavedCredentials)
        val model = compose.runOnUiThread { (compose.activity.application as FollowingCarApplication).boardViewModel }
        compose.waitUntil(15_000) { !model.state.value.remoteProfileLoading }
        assumeTrue("Start with an idle disconnected shared owner", model.state.value.canRecoverConnection)
        val lease = SharedOwnerSshTestLease(model)
        try {
            compose.onNodeWithTag("pageConnection").performClick()
            compose.onNodeWithTag("connectionLanMode").performScrollTo().performClick()
            if (compose.onAllNodesWithTag("host").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag("connectionLoginSettings").performScrollTo().performClick()
            }
            compose.onNodeWithTag("host").performScrollTo()
                .performTextReplacement(arguments.getString("boardHost") ?: "172.16.16.82")
            compose.onNodeWithTag("username").performScrollTo()
                .performTextReplacement(arguments.getString("boardUsername") ?: "topeet")
            if (password.isNotEmpty()) {
                compose.onNodeWithTag("password").performScrollTo().performTextReplacement(password)
            }
            compose.onNodeWithTag("connect").performScrollTo().performClick()
            compose.waitUntil(25_000) {
                val state = model.state.value
                (state.connected && !state.busy) || state.status == "连接失败"
            }
            lease.rememberEstablishedConnection()
            compose.onNodeWithTag("connectionStatus").assertTextEquals("SSH 已连接")

            compose.onNodeWithTag("pageTerminal").performClick()

            compose.onNodeWithTag("command").performScrollTo()
                .performTextReplacement("printf 'PHONE_APP_OK\\n'; whoami")
            compose.onNodeWithTag("execute").performScrollTo().performClick()
            compose.waitUntil(20_000) { textOf("stdout").contains("PHONE_APP_OK") }
            compose.onNodeWithTag("stdout").performScrollTo().assertTextContains("PHONE_APP_OK", substring = true)
            compose.onNodeWithTag("stdout").assertTextContains(arguments.getString("boardUsername") ?: "topeet", substring = true)
            compose.onNodeWithTag("disconnect").performClick()
            compose.waitUntil(5_000) { textOf("connectionStatus") == "已断开" }
        } finally {
            runBlocking { lease.closeIfStillOwnedAndIdle() }
        }
    }

    private fun textOf(tag: String): String = runCatching {
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
    }.getOrDefault("")
}
