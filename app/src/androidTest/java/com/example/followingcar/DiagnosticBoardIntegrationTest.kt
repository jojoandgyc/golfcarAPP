package com.example.followingcar

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.zip.ZipFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: real SSH and video shutdown, exclusively dry-run follow. No motor/camera worker. */
@RunWith(AndroidJUnit4::class)
class DiagnosticBoardIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun dryRunStopSourcesCorrelateWithBoardAndExport() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Opt in with useSavedBoardCredentials", args.getString("useSavedBoardCredentials") == "true")
        val vm = compose.runOnUiThread { ViewModelProvider(compose.activity)[BoardViewModel::class.java] }
        val initial = vm.state.value
        assumeTrue("Saved board credentials required", initial.password.isNotEmpty())
        val reader = BoardSshClient()
        val runIds = mutableListOf<String>()
        try {
            reader.connect(BoardConnection(initial.host, initial.port.toInt(), initial.username, initial.password))
            assertTrue("Board must be idle before simulated integration", reader.readFollowAvailability().controlAvailable)
            compose.runOnUiThread { vm.connect() }
            compose.waitUntil(40_000) { !vm.state.value.busy }
            assertTrue(vm.state.value.error, vm.state.value.connected)
            if (vm.state.value.followUncertain) {
                compose.runOnUiThread { vm.refreshFollowAvailability() }
                compose.waitUntil(25_000) { !vm.state.value.busy }
                assertFalse(vm.state.value.followError, vm.state.value.followUncertain)
            }
            compose.runOnUiThread { vm.setFollowExecute(false); vm.inspectFollowConfiguration() }
            compose.waitUntil(35_000) { !vm.state.value.followConfigurationLoading }
            assertTrue(vm.state.value.followConfigurationError, vm.state.value.canStartFollow)

            // Invoke the same owner callbacks used by user, page and activity lifecycle events.
            for (source in listOf(ControlStopSource.USER_STOP, ControlStopSource.PAGE_CHANGED,
                ControlStopSource.APP_PAUSED, ControlStopSource.WINDOW_FOCUS_LOST)) {
                compose.runOnUiThread { vm.startFollow(dryRun = true) }
                compose.waitUntil(65_000) { vm.state.value.followStatus?.runState == "RUNNING" || !vm.state.value.followActive }
                assertTrue(vm.state.value.followError, vm.state.value.followActive)
                assertEquals(false, vm.state.value.followStatus?.execute)
                compose.runOnUiThread {
                    if (source == ControlStopSource.USER_STOP) vm.stopFollow("诊断模拟停止", source)
                    else vm.onControlSurfaceHidden("诊断模拟生命周期", source)
                    // A second request must be recorded without replacing the original source.
                    vm.stopFollow("诊断模拟重复停止", ControlStopSource.USER_STOP)
                }
                compose.waitUntil(15_000) { !vm.state.value.followActive }
                assertFalse(vm.state.value.followError, vm.state.value.followUncertain)
                val snapshot = Diagnostics.snapshot()
                val events = snapshot.events.map(::JSONObject)
                val start = events.last { it.optString("event") == "follow.start_requested" }
                val op = start.getString("operationId")
                val owned = events.filter { it.optString("operationId") == op }
                val ready = owned.last { it.optString("event") == "follow.ready" }
                val runId = ready.getString("runId")
                assertTrue(runId.matches(Regex("[a-f0-9]{32}")))
                runIds += runId
                val expected = listOf("follow.stop_requested", "follow.stop_sending", "follow.terminal_received", "follow.finished")
                var previousIndex = -1
                for (name in expected) {
                    val index = owned.indexOfFirst { it.optString("event") == name }
                    assertTrue("Missing or unordered $name for $source", index > previousIndex)
                    assertEquals(runId, owned[index].getString("runId"))
                    previousIndex = index
                }
                val stops = owned.filter { it.optString("event") == "follow.stop_requested" }
                assertEquals(source.name, stops.first().getString("source"))
                assertTrue(stops.size >= 2)
                assertTrue(stops.all { it.getJSONObject("fields").getString("firstSource") == source.name })
                assertEquals(source.name, owned.first { it.optString("event") == "follow.stop_sending" }.getString("source"))
                delay(350) // Allow bridge's bounded post-terminal diagnostic flush to finish.
                val board = reader.execute("cat /home/topeet/Desktop/rk_car_runtime_module/app_diagnostic_logs/*_${runId}_*.jsonl")
                assertEquals(board.stderr, 0, board.exitCode)
                val boardEvents = board.stdout.lineSequence().filter { it.isNotBlank() }.map(::JSONObject).toList()
                assertTrue(boardEvents.all { it.getString("runId") == runId })
                assertTrue(boardEvents.any { it.getString("event") == "worker_stop_sent" })
                assertTrue(boardEvents.any { it.getString("event") == "terminal_sent" })
                val finished = boardEvents.last { it.getString("event") == "session_finished" }
                assertEquals(0, finished.getInt("exitCode"))
                assertEquals("app_stop_requested", finished.getJSONObject("firstStop").getString("source"))
            }
            val archive = Diagnostics.export()
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue(names.all { it in setOf("metadata.json", "first_failures.jsonl") || it.startsWith("events/") })
                val metadata = JSONObject(zip.getInputStream(zip.getEntry("metadata.json")).bufferedReader().readText())
                val target = InstrumentationRegistry.getInstrumentation().targetContext
                assertEquals(target.packageManager.getPackageInfo(target.packageName, 0).versionName,
                    metadata.getString("versionName"))
                val text = names.joinToString("\n") { zip.getInputStream(zip.getEntry(it)).bufferedReader().readText() }
                assertFalse("Saved credential must not be exported", text.contains(initial.password))
                assertTrue(runIds.all(text::contains))
            }
        } finally {
            compose.runOnUiThread { vm.stopFollow("诊断模拟清理") }
            compose.waitUntil(20_000) { !vm.state.value.followActive }
            compose.runOnUiThread { vm.disconnect() }
            compose.waitUntil(65_000) { !vm.state.value.connected && !vm.state.value.busy }
            reader.disconnect("diagnostic_integration_complete")
        }
    }
}
