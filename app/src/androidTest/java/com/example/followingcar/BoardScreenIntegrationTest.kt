package com.example.followingcar

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in, read-only end-to-end test against the user's board. Password is a runner argument. */
@RunWith(AndroidJUnit4::class)
class BoardScreenIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun phoneCanConnectAndExecuteACommandFromTheScreen() {
        val arguments = InstrumentationRegistry.getArguments()
        val password = arguments.getString("boardPassword").orEmpty()
        assumeTrue("Provide boardPassword to run against a real board", password.isNotEmpty())
        compose.onNodeWithTag("host").performTextReplacement(arguments.getString("boardHost") ?: "172.16.16.82")
        compose.onNodeWithTag("username").performTextReplacement(arguments.getString("boardUsername") ?: "topeet")
        compose.onNodeWithTag("password").performScrollTo().performTextReplacement(password)
        compose.onNodeWithTag("connect").performScrollTo().performClick()
        compose.waitUntil(25_000) { textOf("connectionStatus") in listOf("已连接", "连接失败") }
        compose.onNodeWithTag("connectionStatus").assertTextEquals("已连接")

        compose.onNodeWithTag("pageTerminal").performClick()

        compose.onNodeWithTag("command").performScrollTo()
            .performTextReplacement("printf 'PHONE_APP_OK\\n'; whoami")
        compose.onNodeWithTag("execute").performScrollTo().performClick()
        compose.waitUntil(20_000) { textOf("stdout").contains("PHONE_APP_OK") }
        compose.onNodeWithTag("stdout").performScrollTo().assertTextContains("PHONE_APP_OK", substring = true)
        compose.onNodeWithTag("stdout").assertTextContains(arguments.getString("boardUsername") ?: "topeet", substring = true)
        compose.onNodeWithTag("disconnect").performClick()
        compose.waitUntil(5_000) { textOf("connectionStatus") == "已断开" }
    }

    private fun textOf(tag: String): String = runCatching {
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
    }.getOrDefault("")
}
