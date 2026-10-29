package com.example.followingcar

import android.content.Context
import android.os.Process
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.ArrayDeque
import java.util.Properties
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

data class DiagnosticContext(
    val operationId: String,
    val connectionId: String? = null,
    val runId: String? = null,
) {
    companion object { val NONE = DiagnosticContext("none") }
}

data class DiagnosticFailure(
    val time: String,
    val event: String,
    val message: String,
    val context: DiagnosticContext,
)

data class DiagnosticStatus(val available: Boolean, val message: String, val droppedEvents: Long)
data class DiagnosticSnapshot(
    val events: List<String>,
    val failures: List<DiagnosticFailure>,
    val status: DiagnosticStatus,
)

/** No caller performs disk I/O or waits for the writer, including stop/heartbeat callbacks. */
object Diagnostics {
    private val redactor = DiagnosticRedactor()
    private val mutableStatus = MutableStateFlow(DiagnosticStatus(false, "日志正在初始化", 0))
    private val mutableFailures = MutableStateFlow<List<DiagnosticFailure>>(emptyList())
    val status: StateFlow<DiagnosticStatus> = mutableStatus.asStateFlow()
    val failures: StateFlow<List<DiagnosticFailure>> = mutableFailures.asStateFlow()
    @Volatile private var engine: DiagnosticEngine? = null
    private var exportDirectory: File? = null
    private var metadata: Map<String, Any?> = emptyMap()
    private var recoveryMetadata: () -> Map<String, Any?> = { emptyMap() }

    @Synchronized
    fun initialize(context: Context) {
        if (engine != null) return
        val app = context.applicationContext
        protectSecret(app.getSharedPreferences("board_credentials", 0).getString("password", null).orEmpty())
        val packageInfo = app.packageManager.getPackageInfo(app.packageName, 0)
        metadata = mapOf("package" to app.packageName, "versionName" to packageInfo.versionName,
            "versionCode" to packageInfo.longVersionCode, "schemaVersion" to 1,
            "androidSdk" to android.os.Build.VERSION.SDK_INT)
        exportDirectory = File(app.cacheDir, "diagnostic_exports")
        recoveryMetadata = {
            FollowRecoverySnapshot.restore(app.getSharedPreferences("follow_recovery", 0).all)
                ?.exportMetadata().orEmpty()
        }
        engine = DiagnosticEngine(DiagnosticFileStore(File(app.filesDir, "diagnostics")),
            redactor, Process.myPid(), onStatus = { mutableStatus.value = it },
            onFailures = { mutableFailures.value = it })
        event("app.process_started", fields = metadata, critical = true)
    }

    fun newContext(connectionId: String? = null) = DiagnosticContext(UUID.randomUUID().toString(), connectionId)
    fun protectSecret(value: String) { redactor.protect(value) }

    /** Use the same registered secrets for protocol error text shown in the UI. */
    fun safeText(value: String, limit: Int = 2_000): String = redactor.text(value, limit)

    fun event(
        event: String,
        context: DiagnosticContext = DiagnosticContext.NONE,
        source: String? = null,
        fields: Map<String, Any?> = emptyMap(),
        critical: Boolean = false,
    ) {
        engine?.event(event, context, source, fields, critical)
    }

    fun failure(
        context: DiagnosticContext,
        event: String,
        error: Throwable,
        fields: Map<String, Any?> = emptyMap(),
    ): DiagnosticFailure = engine?.failure(context, event, error, fields)
        ?: DiagnosticFailure(Instant.now().toString(), redactor.text(event),
            redactor.errorMessage(event, error), context)

    suspend fun snapshot(): DiagnosticSnapshot = withContext(Dispatchers.IO) {
        engine?.snapshot() ?: DiagnosticSnapshot(emptyList(), failures.value, status.value)
    }

    suspend fun export(): File = withContext(Dispatchers.IO) {
        val active = engine ?: throw IOException("诊断日志尚未初始化")
        active.export(requireNotNull(exportDirectory), metadata + recoveryMetadata())
    }
}

/** Small, independently testable whitelist/redaction boundary shared by disk and exports. */
internal class DiagnosticRedactor {
    private val secrets = LinkedHashSet<String>()
    private val credentials = Regex("(?i)(password|passwd|passphrase|token|authorization|credential|secret)([\\\"']?\\s*[:=]\\s*[\\\"']?)([^\\s,;\\\"'}]+)")
    private val bearer = Regex("(?i)\\bBearer\\s+[^\\s,;]+")
    private val urlCredentials = Regex("(://)[^/@\\s]+:[^/@\\s]+@")
    private val forbidden = setOf("password", "passwd", "passphrase", "token", "credential", "credentials",
        "authorization", "secret", "command", "stdout", "stderr", "payload", "raw", "body", "output", "state")

    @Synchronized fun protect(secret: String) {
        if (secret.isBlank()) return
        // Credentials and user commands are bounded by the UI. Keep recent values as well,
        // because an in-flight operation can fail after the user has edited a field.
        secrets.add(secret.take(16_384))
        while (secrets.size > 256) secrets.remove(secrets.first())
    }

    fun text(value: String, limit: Int = 2_000): String {
        val snapshot = synchronized(this) { secrets.toList() }
        var safe = value
        snapshot.sortedByDescending(String::length).forEach { secret -> safe = safe.replace(secret, "[REDACTED]") }
        safe = credentials.replace(safe) { "${it.groupValues[1]}${it.groupValues[2]}[REDACTED]" }
        safe = bearer.replace(safe, "Bearer [REDACTED]")
        safe = urlCredentials.replace(safe, "$1[REDACTED]@")
        return safe.take(limit)
    }

    fun errorMessage(event: String, error: Throwable): String = if (event.startsWith("terminal.") || event.startsWith("terminal_")) {
        "${error.javaClass.simpleName}：终端命令失败（日志不保存命令正文及输出）"
    } else exceptionMessage(error.javaClass.name, error.message)

    internal fun exceptionMessage(typeName: String, message: String?): String =
        if (typeName == "org.json.JSONException") {
            // Android's parser appends the original JSON, which may include a complete state or token.
            "JSON 格式错误（日志不保存原始响应）"
        } else text(message.orEmpty().ifBlank { typeName.substringAfterLast('.') }, 1_000)

    fun errorFields(event: String, error: Throwable): Map<String, Any?> {
        val terminal = event.startsWith("terminal.") || event.startsWith("terminal_")
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        val chain = generateSequence(error) { it.cause }.takeWhile { seen.add(it) }.take(8).toList()
        return mapOf("exceptionType" to error.javaClass.name,
            "exceptionMessage" to errorMessage(event, error),
            "exceptionStack" to text(chain.joinToString("\nCaused by: ") { cause ->
                cause.javaClass.name + (if (terminal) "" else ": " + exceptionMessage(cause.javaClass.name, cause.message)) + "\n" +
                    cause.stackTrace.take(24).joinToString("\n") { "  at $it" }
            }, 8_000))
    }

    fun fields(values: Map<String, Any?>): Map<String, Any?> = values.entries.take(32).associate { (key, value) ->
        val safeKey = key.take(80)
        val normalized = key.lowercase().replace(Regex("[^a-z]"), "")
        val secretKey = normalized in forbidden || normalized.contains("password") || normalized.endsWith("token") ||
            normalized.contains("credential") || normalized.contains("secret") || normalized.startsWith("raw")
        safeKey to when {
            secretKey -> "[REDACTED]"
            value == null || value is Boolean || value is Number -> value
            value is String -> text(value, if (key == "exceptionStack") 8_000 else 1_000)
            // Do not accidentally serialize BoardUiState, protocol JSON or arbitrary objects.
            else -> "[UNSUPPORTED]"
        }
    }
}

internal interface DiagnosticStorage {
    fun loadFailures(): List<DiagnosticFailure>
    fun saveFailures(failures: List<DiagnosticFailure>)
    fun append(line: String)
    fun recent(): List<String>
    fun export(destination: File, metadata: Map<String, Any?>, failures: List<DiagnosticFailure>): File
}

internal class DiagnosticEngine(
    private val storage: DiagnosticStorage,
    private val redactor: DiagnosticRedactor = DiagnosticRedactor(),
    private val pid: Int = 0,
    private val capacity: Int = 512,
    private val onStatus: (DiagnosticStatus) -> Unit = {},
    private val onFailures: (List<DiagnosticFailure>) -> Unit = {},
) {
    private data class Pending(val line: String, val critical: Boolean, val barrier: CountDownLatch? = null)
    private val lock = Object()
    private val queue = ArrayDeque<Pending>()
    private val firstFailures = LinkedHashMap<String, DiagnosticFailure>()
    private val memory = ArrayDeque<String>()
    private var failuresDirty = false
    private var retryFailureWrite = false
    private var closed = false
    private var dropped = 0L
    private var reportedDropped = 0L
    private val sequence = AtomicLong()
    private val instanceId = UUID.randomUUID().toString()
    @Volatile private var currentStatus = DiagnosticStatus(false, "日志正在初始化", 0)
    private val writer = Thread({ writeLoop() }, "diagnostic-writer").apply { isDaemon = true; start() }

    fun event(name: String, context: DiagnosticContext = DiagnosticContext.NONE, source: String? = null,
        fields: Map<String, Any?> = emptyMap(), critical: Boolean = false) {
        val record = linkedMapOf<String, Any?>("schemaVersion" to 1, "time" to Instant.now().toString(),
            "monotonicMs" to System.nanoTime() / 1_000_000, "instanceId" to instanceId,
            "eventId" to sequence.incrementAndGet(), "pid" to pid, "thread" to Thread.currentThread().name,
            "event" to redactor.text(name, 120), "operationId" to context.operationId,
            "connectionId" to context.connectionId, "runId" to context.runId,
            "source" to source?.let { redactor.text(it, 120) }, "fields" to redactor.fields(fields))
        enqueue(Pending(diagnosticJson(record), critical))
    }

    fun failure(context: DiagnosticContext, name: String, error: Throwable,
        fields: Map<String, Any?> = emptyMap()): DiagnosticFailure {
        val candidate = DiagnosticFailure(Instant.now().toString(), redactor.text(name, 120),
            redactor.errorMessage(name, error), context)
        val first: DiagnosticFailure
        synchronized(lock) {
            first = firstFailures[context.operationId] ?: candidate.also {
                firstFailures[context.operationId] = it
                trimFailures()
                failuresDirty = true
                onFailures(firstFailures.values.toList())
                lock.notifyAll()
            }
        }
        event(name, context, fields = fields + redactor.errorFields(name, error) +
            mapOf("firstFailure" to (first === candidate), "firstFailureTime" to first.time), critical = true)
        return first
    }

    private fun enqueue(pending: Pending) {
        synchronized(lock) {
            if (closed) return
            if (retryFailureWrite && pending.barrier == null) {
                failuresDirty = true
                retryFailureWrite = false
            }
            if (queue.size >= capacity) {
                val disposable = queue.firstOrNull { !it.critical && it.barrier == null }
                if (!pending.critical || disposable == null) {
                    // A flush barrier must always be able to observe completion; never evict it.
                    if (pending.barrier == null) { markDropped(); return }
                    val old = queue.firstOrNull { it.barrier == null }
                    if (old != null) queue.remove(old) else { pending.barrier.countDown(); return }
                } else queue.remove(disposable)
                markDropped()
            }
            queue.addLast(pending)
            lock.notifyAll()
        }
    }

    private fun markDropped() {
        dropped += 1
        currentStatus = currentStatus.copy(droppedEvents = dropped)
        onStatus(currentStatus)
    }

    private fun trimFailures() {
        while (firstFailures.size > 10) firstFailures.remove(firstFailures.keys.first())
    }

    private fun publishStatus(available: Boolean, message: String) {
        synchronized(lock) {
            currentStatus = DiagnosticStatus(available, message, dropped)
            onStatus(currentStatus)
        }
    }

    private fun writeLoop() {
        try {
            val loaded = storage.loadFailures()
            synchronized(lock) {
                val current = firstFailures.toMap()
                firstFailures.clear()
                loaded.forEach { firstFailures[it.context.operationId] = it }
                current.forEach { (key, value) -> firstFailures.putIfAbsent(key, value) }
                trimFailures()
                onFailures(firstFailures.values.toList())
            }
            publishStatus(true, "日志记录正常")
        } catch (failure: Exception) { publishStatus(false, "无法读取日志：${failure.javaClass.simpleName}") }
        while (true) {
            val pending: Pending?
            val summaries: List<DiagnosticFailure>?
            val newDrops: Long
            synchronized(lock) {
                while (queue.isEmpty() && !failuresDirty && !closed) lock.wait()
                if (closed && queue.isEmpty() && !failuresDirty) return
                pending = queue.pollFirst()
                summaries = if (failuresDirty) firstFailures.values.toList() else null
                failuresDirty = false
                newDrops = dropped - reportedDropped
                reportedDropped = dropped
            }
            try {
                if (summaries != null) storage.saveFailures(summaries)
                if (newDrops > 0) storage.append(diagnosticJson(mapOf("schemaVersion" to 1,
                    "time" to Instant.now().toString(), "event" to "diagnostic.events_dropped", "count" to newDrops)))
                if (pending != null && pending.barrier == null) {
                    synchronized(lock) {
                        memory.addLast(pending.line)
                        while (memory.size > 300) memory.removeFirst()
                    }
                    storage.append(pending.line)
                }
                if (summaries != null || newDrops > 0 || (pending != null && pending.barrier == null)) {
                    publishStatus(true, "日志记录正常")
                }
            } catch (failure: Exception) {
                if (summaries != null) synchronized(lock) { retryFailureWrite = true }
                // Never throw a storage failure into the control owner or recursively log it.
                publishStatus(false, "日志写入失败：${failure.javaClass.simpleName}；控制功能仍可运行")
            } finally { pending?.barrier?.countDown() }
        }
    }

    fun flush(timeoutMs: Long = 5_000): Boolean {
        val barrier = CountDownLatch(1)
        enqueue(Pending("", critical = true, barrier = barrier))
        return barrier.await(timeoutMs, TimeUnit.MILLISECONDS)
    }

    fun snapshot(): DiagnosticSnapshot {
        if (!flush()) return synchronized(lock) {
            DiagnosticSnapshot(memory.toList(), firstFailures.values.toList(),
                currentStatus.copy(available = false, message = "日志磁盘响应超时，显示内存中的最近事件"))
        }
        val events = try { storage.recent() } catch (failure: Exception) {
            publishStatus(false, "日志读取失败：${failure.javaClass.simpleName}")
            synchronized(lock) { memory.toList() }
        }
        return synchronized(lock) { DiagnosticSnapshot(events, firstFailures.values.toList(), currentStatus) }
    }

    fun export(directory: File, metadata: Map<String, Any?>): File {
        if (!flush()) throw IOException("日志写入响应超时，请稍后导出")
        if (!currentStatus.available) throw IOException(currentStatus.message)
        val failureSnapshot = synchronized(lock) { firstFailures.values.toList() }
        return storage.export(directory, redactor.fields(metadata) + mapOf("exportedAt" to Instant.now().toString(),
            "instanceId" to instanceId, "droppedEvents" to currentStatus.droppedEvents), failureSnapshot)
    }

    internal fun close() {
        synchronized(lock) { closed = true; lock.notifyAll() }
        writer.join(5_000)
    }
}

internal class DiagnosticFileStore(
    private val directory: File,
    private val maxBytes: Long = 20L * 1024 * 1024,
    private val maxAgeMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val segmentBytes: Long = 1024L * 1024,
    private val now: () -> Long = System::currentTimeMillis,
) : DiagnosticStorage {
    private val logs = File(directory, "events")
    private val failureFile = File(directory, "first_failures.properties")
    private var active: File? = null
    private val dateFormat = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC)

    private fun prepare() {
        if (!logs.isDirectory && !logs.mkdirs()) throw IOException("Cannot create diagnostic directory")
    }

    @Synchronized override fun loadFailures(): List<DiagnosticFailure> {
        prepare()
        prune()
        if (!failureFile.exists()) return emptyList()
        val values = Properties().apply { failureFile.inputStream().use(::load) }
        return (0 until (values.getProperty("count")?.toIntOrNull() ?: 0).coerceIn(0, 10)).map { index ->
            val prefix = "failure.$index."
            DiagnosticFailure(values.getProperty(prefix + "time", ""), values.getProperty(prefix + "event", ""),
                values.getProperty(prefix + "message", ""), DiagnosticContext(
                    values.getProperty(prefix + "operationId", "none"), values.getProperty(prefix + "connectionId"),
                    values.getProperty(prefix + "runId")))
        }
    }

    @Synchronized override fun saveFailures(failures: List<DiagnosticFailure>) {
        prepare()
        val values = Properties().apply {
            val recent = failures.takeLast(10)
            setProperty("count", recent.size.toString())
            recent.forEachIndexed { index, failure ->
                val prefix = "failure.$index."
                setProperty(prefix + "time", failure.time)
                setProperty(prefix + "event", failure.event)
                setProperty(prefix + "message", failure.message)
                setProperty(prefix + "operationId", failure.context.operationId)
                failure.context.connectionId?.let { setProperty(prefix + "connectionId", it) }
                failure.context.runId?.let { setProperty(prefix + "runId", it) }
            }
        }
        val temporary = File(directory, "first_failures.tmp")
        temporary.outputStream().buffered().use { values.store(it, "Redacted diagnostic first failures") }
        try {
            Files.move(temporary.toPath(), failureFile.toPath(), StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), failureFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun eventFiles(): List<File> = logs.listFiles()?.filter {
        it.isFile && it.name.matches(Regex("events-[0-9]{8}-[0-9a-f-]+\\.jsonl"))
    }?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name }).orEmpty()

    private fun prune() {
        val files = eventFiles()
        files.filter { now() - it.lastModified() > maxAgeMs }.forEach { if (!it.delete()) throw IOException("Cannot expire log") }
        val retained = eventFiles().toMutableList()
        var total = retained.sumOf { it.length() }
        for (file in retained) {
            if (total <= maxBytes) break
            val bytes = file.length()
            if (!file.delete()) throw IOException("Cannot rotate log")
            total -= bytes
        }
    }

    @Synchronized override fun append(line: String) {
        prepare()
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw IOException("Diagnostic record exceeds storage limit")
        val day = dateFormat.format(Instant.ofEpochMilli(now()))
        var file = active
        if (file == null || !file.exists() || !file.name.startsWith("events-$day-") ||
            file.length() + bytes.size > segmentBytes) {
            file = File(logs, "events-$day-${UUID.randomUUID()}.jsonl")
            active = file
        }
        file.appendBytes(bytes)
        file.setLastModified(now())
        prune()
    }

    @Synchronized override fun recent(): List<String> {
        prepare()
        prune()
        val recent = ArrayDeque<String>()
        // At most 20 MiB on disk, and only 300 events retained in the UI snapshot.
        for (file in eventFiles()) file.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                if (line.length <= 64 * 1024) {
                    recent.addLast(line)
                    while (recent.size > 300) recent.removeFirst()
                }
            }
        }
        return recent.toList()
    }

    @Synchronized override fun export(destination: File, metadata: Map<String, Any?>,
        failures: List<DiagnosticFailure>): File {
        prepare()
        prune()
        if (!destination.isDirectory && !destination.mkdirs()) throw IOException("Cannot create export directory")
        destination.listFiles()?.filter { it.isFile && it.name.startsWith("followingcar-diagnostics-") && it.extension == "zip" }
            ?.sortedByDescending { it.lastModified() }?.drop(1)?.forEach { it.delete() }
        val target = File(destination, "followingcar-diagnostics-${now()}-${UUID.randomUUID().toString().take(8)}.zip")
        try {
            ZipOutputStream(target.outputStream().buffered()).use { zip ->
                fun entry(name: String, text: String) {
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                }
                entry("metadata.json", diagnosticJson(metadata))
                entry("first_failures.jsonl", failures.joinToString("\n") { diagnosticJson(mapOf(
                    "time" to it.time, "event" to it.event, "message" to it.message,
                    "operationId" to it.context.operationId, "connectionId" to it.context.connectionId,
                    "runId" to it.context.runId)) })
                for (file in eventFiles()) {
                    zip.putNextEntry(ZipEntry("events/${file.name}"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } catch (failure: Exception) { target.delete(); throw failure }
        return target
    }
}

/** JSON for the bounded primitive event schema; never invokes arbitrary objects' toString(). */
internal fun diagnosticJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 32) append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }
    is Boolean -> value.toString()
    is Number -> if (value.toDouble().isFinite()) value.toString() else "null"
    is Map<*, *> -> value.entries.joinToString(",", "{", "}") { diagnosticJson(it.key as? String ?: "unknown") + ":" + diagnosticJson(it.value) }
    else -> "null"
}
