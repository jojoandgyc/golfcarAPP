package com.example.followingcar

import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun firstFaultSurvivesLaterInspectionFailureAndProcessRestart() {
        val directory = temporary.newFolder("diagnostics")
        val context = DiagnosticContext("follow-operation", "connection-1", "a".repeat(32))
        val engine = DiagnosticEngine(DiagnosticFileStore(directory))
        val first = engine.failure(context, "follow.ack_timeout", IOException("first timeout"))
        val second = engine.failure(context, "follow.inspect_failed", IOException("channel is not opened"))
        assertEquals(first, second)
        assertTrue(engine.flush())
        engine.close()
        val restarted = DiagnosticEngine(DiagnosticFileStore(directory))
        try {
            val snapshot = restarted.snapshot()
            assertEquals(listOf(first), snapshot.failures)
            assertTrue(snapshot.events.any { it.contains("follow.ack_timeout") })
            assertTrue(snapshot.events.any { it.contains("follow.inspect_failed") })
            assertEquals(first, restarted.failure(context, "follow.another_error", IOException("another")))
        } finally { restarted.close() }
    }

    @Test fun slowStorageAndFullQueueNeverWaitOnTheControlProducer() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val storage = MemoryStorage().apply {
            beforeAppend = {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        }
        val engine = DiagnosticEngine(storage, capacity = 4)
        try {
            engine.event("writer.blocked")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            repeat(200) { engine.event("manual.heartbeat_summary", fields = mapOf("seq" to it)) }
            engine.event("manual.stop_requested", critical = true)
            val elapsed = (System.nanoTime() - start) / 1_000_000
            assertTrue("Logging producer took $elapsed ms while disk was blocked", elapsed < 1_000)
            release.countDown()
            val snapshot = engine.snapshot()
            assertTrue(snapshot.status.droppedEvents > 0)
            assertTrue(storage.lines.any { it.contains("manual.stop_requested") })
            assertTrue(storage.lines.any { it.contains("diagnostic.events_dropped") })
        } finally { release.countDown(); engine.close() }
    }

    @Test fun storageFailureIsVisibleAndDoesNotEscapeToControlCaller() {
        val storage = MemoryStorage().apply { failWrites = true }
        val engine = DiagnosticEngine(storage)
        try {
            engine.event("manual.stop_requested", critical = true)
            assertTrue(engine.flush())
            assertFalse(engine.snapshot().status.available)
            storage.failWrites = false
            engine.event("connection.recovered")
            assertTrue(engine.snapshot().status.available)
            assertTrue(storage.lines.any { it.contains("connection.recovered") })
        } finally { engine.close() }
    }

    @Test fun failedSummaryWriteRetriesAfterDiskRecovers() {
        val storage = MemoryStorage().apply { failWrites = true }
        val engine = DiagnosticEngine(storage)
        try {
            val failure = engine.failure(DiagnosticContext("op"), "follow.timeout", IOException("timeout"))
            assertTrue(engine.flush())
            storage.failWrites = false
            engine.event("logging.retry")
            assertTrue(engine.flush())
            assertEquals(listOf(failure), storage.savedFailures)
        } finally { engine.close() }
    }

    @Test fun sevenDayAndByteLimitsDoNotRemoveSeparateFailureSummaries() {
        val directory = temporary.newFolder("retention")
        var clock = 1_800_000_000_000L
        val store = DiagnosticFileStore(directory, maxBytes = 1_200, segmentBytes = 200, now = { clock })
        val saved = DiagnosticFailure("time", "failure", "first", DiagnosticContext("op"))
        store.saveFailures(listOf(saved))
        repeat(40) { index ->
            clock += 1_000
            store.append(diagnosticJson(mapOf("event" to "entry-$index", "detail" to "x".repeat(90))))
        }
        assertTrue(File(directory, "events").listFiles()!!.sumOf { it.length() } <= 1_200)
        assertTrue(store.recent().any { it.contains("entry-39") })
        assertFalse(store.recent().any { it.contains("entry-0\"") })
        clock += 8L * 24 * 60 * 60 * 1000
        store.append("{\"event\":\"after-expiry\"}")
        assertEquals(listOf("{\"event\":\"after-expiry\"}"), store.recent())
        assertEquals(listOf(saved), store.loadFailures())
    }

    @Test fun onlyLastTenFirstFailuresAreRetainedAcrossRestart() {
        val directory = temporary.newFolder("failure-cap")
        val engine = DiagnosticEngine(DiagnosticFileStore(directory))
        repeat(14) { index -> engine.failure(DiagnosticContext("op-$index"), "failure", IOException("failure-$index")) }
        assertTrue(engine.flush())
        engine.close()
        val restarted = DiagnosticEngine(DiagnosticFileStore(directory))
        try {
            val failures = restarted.snapshot().failures
            assertEquals(10, failures.size)
            assertEquals("op-4", failures.first().context.operationId)
            assertEquals("op-13", failures.last().context.operationId)
        } finally { restarted.close() }
    }

    @Test fun secretsCommandsPayloadsAndExceptionCausesAreRemovedButRunIdRemains() {
        val redactor = DiagnosticRedactor()
        redactor.protect("secret-password-77")
        redactor.protect("export SECRET=private-command")
        val storage = MemoryStorage()
        val engine = DiagnosticEngine(storage, redactor)
        val runId = "0123456789abcdef0123456789abcdef"
        try {
            engine.event("follow.ready", DiagnosticContext("op", "conn", runId), fields = mapOf(
                "token" to "control-nonce", "password" to "secret-password-77",
                "command" to "export SECRET=private-command", "commandType" to "start",
                "payload" to "private-payload", "stdout" to "private-output",
                "state" to Any(), "message" to "token=plain-token password=plain-password"))
            engine.failure(DiagnosticContext("op", "conn", runId), "follow.error",
                IOException("failed secret-password-77", IllegalStateException("export SECRET=private-command")))
            engine.failure(DiagnosticContext("terminal"), "terminal.execute_failed",
                IOException("a completely unknown user command and output"))
            assertTrue(engine.flush())
            val all = storage.lines.joinToString("\n") + storage.savedFailures.joinToString()
            for (secret in listOf("control-nonce", "secret-password-77", "private-command", "private-payload",
                "private-output", "plain-token", "plain-password", "unknown user command")) {
                assertFalse("Leaked $secret", all.contains(secret))
            }
            assertTrue(all.contains(runId))
            assertTrue(all.contains("\"commandType\":\"start\""))
        } finally { engine.close() }
    }

    @Test fun jsonParserExceptionMessageCannotIncludeRawResponse() {
        val redactor = DiagnosticRedactor()
        val rawResponse = "Unterminated object at character 74 of {\"status\":{\"privateField\":\"sensitive-state\"}}"
        val filtered = redactor.exceptionMessage("org.json.JSONException", rawResponse)
        assertEquals("JSON 格式错误（日志不保存原始响应）", filtered)
        assertFalse(filtered.contains("sensitive-state"))
        assertEquals("channel is not opened", redactor.exceptionMessage("java.io.IOException", "channel is not opened"))
    }

    @Test fun zipContainsOnlyDiagnosticEventsFailuresAndVersionMetadata() {
        val directory = temporary.newFolder("zip-source")
        File(directory, "board_credentials.xml").writeText("must-not-be-exported")
        val engine = DiagnosticEngine(DiagnosticFileStore(directory))
        try {
            engine.event("follow.ready", DiagnosticContext("operation", "connection", "run-123"))
            engine.failure(DiagnosticContext("operation", "connection", "run-123"), "follow.timeout", IOException("ACK timeout"))
            val archive = engine.export(temporary.newFolder("exports"), mapOf("versionName" to "test-version"))
            ZipFile(archive).use { zip ->
                val names = zip.entries().asSequence().map { it.name }.toList()
                assertTrue(names.all { it == "metadata.json" || it == "first_failures.jsonl" || it.startsWith("events/events-") })
                assertNotNull(zip.getEntry("metadata.json"))
                assertNotNull(zip.getEntry("first_failures.jsonl"))
                val contents = zip.entries().asSequence().joinToString("\n") { zip.getInputStream(it).reader().readText() }
                assertTrue(contents.contains("test-version"))
                assertTrue(contents.contains("run-123"))
                assertFalse(contents.contains("must-not-be-exported"))
            }
        } finally { engine.close() }
    }

    private class MemoryStorage : DiagnosticStorage {
        val lines = CopyOnWriteArrayList<String>()
        @Volatile var failWrites = false
        @Volatile var savedFailures = emptyList<DiagnosticFailure>()
        var beforeAppend: (() -> Unit)? = null
        override fun loadFailures() = savedFailures
        override fun saveFailures(failures: List<DiagnosticFailure>) {
            if (failWrites) throw IOException("simulated full disk")
            savedFailures = failures
        }
        override fun append(line: String) {
            beforeAppend?.invoke()
            if (failWrites) throw IOException("simulated full disk")
            lines += line
        }
        override fun recent(): List<String> = lines.toList()
        override fun export(destination: File, metadata: Map<String, Any?>, failures: List<DiagnosticFailure>): File =
            throw UnsupportedOperationException()
    }
}
