package com.example.followingcar

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real SSH/video shutdown, exclusively dryRun=true and execute=false. */
@RunWith(AndroidJUnit4::class)
class DirectFollowStartIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dryRunStartsWithoutConfigurationFetchAndStillReleasesVideoFirst() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val explicitPassword = arguments.getString("boardPassword").orEmpty()
        assumeTrue("Opt in with useSavedBoardCredentials or an explicit boardPassword",
            arguments.getString("useSavedBoardCredentials") == "true" || explicitPassword.isNotEmpty())
        val vm = compose.runOnUiThread { ViewModelProvider(compose.activity)[BoardViewModel::class.java] }
        if (explicitPassword.isNotEmpty()) compose.runOnUiThread {
            arguments.getString("boardHost")?.let(vm::updateHost)
            arguments.getString("boardUser")?.let(vm::updateUsername)
            vm.updatePassword(explicitPassword)
        }
        val initial = vm.state.value
        assumeTrue("Board credentials required", initial.password.isNotEmpty())
        assumeTrue("Do not interrupt an existing App control operation",
            !initial.manualActive && !initial.followActive && !initial.busy && !initial.videoServiceBusy)
        val reader = BoardSshClient()
        var ownsAppConnection = false
        var testFailure: Throwable? = null
        try {
            reader.connect(BoardConnection(initial.host, initial.port.toInt(), initial.username, initial.password))
            // This is a read-only ownership probe, never a stop request for another session.
            assumeTrue("Skip while the board is occupied", reader.readFollowAvailability().controlAvailable)
            val marker = Diagnostics.newContext()
            Diagnostics.event("test.direct_follow_begin", marker, critical = true)

            ownsAppConnection = true
            if (initial.connected) {
                compose.runOnUiThread { vm.disconnect() }
                compose.waitUntil(135_000) { !vm.state.value.connected && !vm.state.value.busy }
            }
            compose.runOnUiThread { vm.connect() }
            compose.waitUntil(45_000) { !vm.state.value.busy }
            assertTrue(vm.state.value.error, vm.state.value.connected)
            assertFalse("Reconnect must resolve an old pending lock before a user start", vm.state.value.followUncertain)
            assertNull("No configuration fetch is needed", vm.state.value.followConfiguration)
            assertFalse(vm.state.value.followConfigurationLoading)
            assertFalse("A new page defaults to shadow mode", vm.state.value.followExecute)
            assertTrue(vm.state.value.followError, vm.state.value.canStartFollow)
            assertFalse("Connection and recovery never start follow automatically", vm.state.value.followActive)

            // Never set followExecute=true and never invoke a production real-motor button.
            compose.runOnUiThread { vm.startFollow(dryRun = true) }
            compose.waitUntil(65_000) {
                val current = vm.state.value
                if (current.followStatus != null) assertEquals(VideoServiceState.STOPPED, current.videoServiceState)
                current.followStatus?.runState == "RUNNING" || !current.followActive
            }
            assertTrue(vm.state.value.followError, vm.state.value.followActive)
            assertEquals("RUNNING", vm.state.value.followStatus?.runState)
            assertEquals(false, vm.state.value.followStatus?.execute)
            assertEquals(VideoServiceState.STOPPED, vm.state.value.videoServiceState)
            assertNull(vm.state.value.followConfiguration)

            compose.runOnUiThread { vm.stopFollow("直接启动模拟测试结束") }
            compose.waitUntil(15_000) { !vm.state.value.followActive }
            assertFalse(vm.state.value.followError, vm.state.value.followUncertain)
            assertEquals(true, vm.state.value.followStatus?.processExited)
            assertEquals(VideoServiceState.STOPPED, vm.state.value.videoServiceState)

            val records = Diagnostics.snapshot().events.map(::JSONObject)
            val boundary = records.indexOfLast {
                it.optString("event") == "test.direct_follow_begin" && it.optString("operationId") == marker.operationId
            }
            assertTrue("Test boundary must remain in the event window", boundary >= 0)
            val duringTest = records.drop(boundary + 1)
            assertFalse("Direct start must not insert a configuration request",
                duringTest.any { it.optString("event") == "follow.configuration_requested" })
            assertFalse("Direct start must not silently execute check-config",
                duringTest.any { it.optString("event") == "ssh.exec_started" &&
                    it.optJSONObject("fields")?.optString("kind") == "follow_config" })
            val start = duringTest.single { it.optString("event") == "follow.start_requested" }
            assertTrue(start.getJSONObject("fields").getBoolean("dryRun"))
            assertFalse(start.getJSONObject("fields").getBoolean("execute"))
            assertTrue(duringTest.any { it.optString("event") == "follow.terminal_received" &&
                it.optString("operationId") == start.getString("operationId") })
        } catch (failure: Throwable) {
            testFailure = failure
            throw failure
        } finally {
            val cleanupFailure = runCatching {
                try {
                    if (ownsAppConnection) {
                        try {
                            compose.runOnUiThread { vm.stopFollow("直接启动模拟测试清理") }
                            compose.waitUntil(65_000) { !vm.state.value.followActive }
                        } finally {
                            compose.runOnUiThread { vm.disconnect() }
                            compose.waitUntil(135_000) { !vm.state.value.connected && !vm.state.value.busy }
                        }
                    }
                } finally { reader.disconnect("direct_follow_test_complete") }
            }.exceptionOrNull()
            if (cleanupFailure != null) {
                if (testFailure != null) testFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
            }
        }
    }
}
