package com.example.followingcar

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import android.util.JsonReader
import android.util.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.StringReader
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One SSH-owned follow run. Only the owning loop sends requests and 500 ms heartbeats. */
class BoardFollowChannel private constructor(
    private val channel: ChannelExec,
    private val input: InputStream,
    private val output: OutputStream,
    private val stderr: BoundedOutputStream,
    private val abortTransport: (reason: String, diagnostic: DiagnosticContext) -> Unit,
    diagnostic: DiagnosticContext,
) {
    var diagnosticContext: DiagnosticContext = diagnostic
        private set
    private val transaction = Mutex()
    private val closed = AtomicBoolean(false)
    private val blockingIo = AtomicBoolean(false)
    private var token = ""
    private var sequence = 0L
    private var startSent = false
    @Volatile private var phase = "created"
    @Volatile private var currentCommand = "open"
    @Volatile private var lastAckSequence = 0L
    @Volatile private var lastAckNanos = 0L
    /** Only validated replies advance freshness; error snapshots are diagnostic evidence. */
    @Volatile var lastReplyReceivedNanos = 0L
        private set
    @Volatile var lastStatus: FollowStatus? = null
        private set
    @Volatile var terminalReply: FollowReply? = null
        private set
    lateinit var ready: FollowReady
        private set

    val isOpen: Boolean
        get() = !closed.get() && channel.isConnected && !channel.isClosed

    suspend fun start(): FollowReply {
        check(!startSent) { "本次跟随会话已发送启动请求" }
        startSent = true
        return transact("start")
    }

    suspend fun heartbeat(): FollowReply = transact("heartbeat")

    /** Acknowledges the request promptly; heartbeat continues until the owned process exits. */
    suspend fun requestStop(): FollowReply = transact("stop")

    /** Cancel a ready-but-not-started session without creating a worker. */
    suspend fun requestClose(): FollowReply = transact("close")

    /** EOF asks the supervisor to stop; a lost network also expires the board-side lease. */
    fun close(reason: String = "requested", invalidateTransport: Boolean = false) {
        if (!closed.compareAndSet(false, true)) return
        val abort = invalidateTransport || blockingIo.get()
        Diagnostics.event("follow.channel_close", diagnosticContext, source = reason,
            fields = diagnosticFields() + mapOf("closeScope" to if (abort) "transport" else "channel"),
            critical = true)
        if (abort) abortTransport("follow_$reason", diagnosticContext)
        cleanup.execute {
            runCatching { channel.disconnect() }
            runCatching { input.close() }
        }
    }

    private suspend fun transact(command: String): FollowReply = withContext(Dispatchers.IO) {
        check(transaction.tryLock()) { "上一个跟随指令尚未收到回执" }
        val started = System.nanoTime()
        currentCommand = command
        phase = "check_open"
        try {
            // An already validated terminal reply wins over late EOF and duplicate stop intents.
            terminalReply?.let { return@withContext it }
            check(isOpen) { "跟随通道已关闭，运行状态未确认" }
            val result = withTimeout(ACK_TIMEOUT_MS) {
                currentCoroutineContext().ensureActive()
                sequence += 1
                val sentSequence = sequence
                val previousToken = token
                val request = JSONObject().put("seq", sentSequence).put("token", previousToken)
                    .put("runId", ready.runId).put("cmd", command)
                phase = "write"
                Diagnostics.event("follow.request_send", diagnosticContext,
                    fields = mapOf("commandType" to command, "seq" to sentSequence, "timeoutMs" to ACK_TIMEOUT_MS),
                    critical = command != "heartbeat")
                blockingCall {
                    output.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                phase = "ack_wait"
                val reply = readMessage()
                phase = "ack_validate"
                requireMessage(reply.opt("type") == "ack", "开发板返回了未知跟随响应")
                val replySequence = reply.opt("seq")
                requireMessage((replySequence is Int || replySequence is Long) &&
                    (replySequence as Number).toLong() == sentSequence, "跟随回执序号不匹配")
                requireMessage(reply.opt("runId") == ready.runId, "跟随回执不属于本次会话")
                val nextToken = readToken(reply)
                requireMessage(nextToken != previousToken, "跟随控制令牌未更新")
                val terminal = reply.opt("terminal")
                requireMessage(terminal is Boolean, "跟随回执缺少结束状态")
                val status = preserveFault(parseFollowStatus(reply.getJSONObject("status")))
                requireMessage(status.execute == ready.execute && status.camera == ready.camera,
                    "跟随回执模式或相机不匹配")
                if (terminal == true) {
                    requireMessage(status.processExited && !status.finalStatusPending &&
                        status.runState in setOf("STOPPED", "FAULT"),
                        "跟随进程结束回执不一致")
                }
                token = nextToken
                lastAckSequence = sentSequence
                lastAckNanos = System.nanoTime()
                lastReplyReceivedNanos = lastAckNanos
                lastStatus = status
                Diagnostics.event("follow.ack", diagnosticContext, fields = mapOf(
                    "commandType" to command, "seq" to sentSequence, "elapsedMs" to elapsedMillis(started),
                    "terminal" to terminal, "runState" to status.runState,
                    "processExited" to status.processExited, "stopRequested" to status.stopRequested,
                    "stopAcknowledged" to status.stopAcknowledged, "statusAgeMs" to status.statusAgeMs) +
                    statusDiagnosticFields(status),
                    critical = terminal == true || command != "heartbeat" || status.fault != null)
                status.fault?.let { fault ->
                    Diagnostics.failure(diagnosticContext, "follow.board_fault", IOException(fault),
                        diagnosticFields())
                }
                phase = "idle"
                FollowReply(status, terminal as Boolean).also {
                    if (it.terminal) terminalReply = it
                }
            }
            if (result.terminal) close("terminal_ack")
            result
        } catch (timeout: TimeoutCancellationException) {
            terminalReply?.let {
                close("terminal_ack")
                return@withContext it
            }
            val failure = SocketTimeoutException("跟随回执超时；板端将请求停止，车辆状态未确认")
            Diagnostics.failure(diagnosticContext, "follow.ack_timeout", failure,
                diagnosticFields() + mapOf("elapsedMs" to elapsedMillis(started), "timeoutMs" to ACK_TIMEOUT_MS))
            // A completed write does not prove that the SSH transport is still alive. Do not
            // let JSch's cached connection flag advertise a timed-out control lease as usable.
            close("ack_timeout", invalidateTransport = true)
            throw failure
        } catch (failure: Exception) {
            terminalReply?.let {
                close("terminal_ack")
                if (failure is CancellationException) throw failure
                return@withContext it
            }
            val safeFailure = protocolDiagnosticFailure(failure)
            if (failure is CancellationException) {
                Diagnostics.event("follow.transaction_cancelled", diagnosticContext,
                    fields = diagnosticFields(), critical = true)
            } else {
                Diagnostics.failure(diagnosticContext, "follow.transaction_failed", safeFailure,
                    diagnosticFields() + mapOf("elapsedMs" to elapsedMillis(started),
                        "originalExceptionClass" to failure.javaClass.name))
            }
            close(if (failure is CancellationException) "transaction_cancelled" else "transaction_failed")
            throw safeFailure
        } finally {
            transaction.unlock()
        }
    }

    private suspend fun readMessage(): JSONObject {
        val bytes = ByteArrayOutputStream()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw IOException("跟随通道已关闭，运行状态未确认")
            while (input.available() > 0) {
                val value = input.read()
                if (value < 0) throw endedError()
                if (value == '\n'.code) {
                    val message = try {
                        parseFollowWireMessage(bytes.toByteArray())
                    } catch (failure: Exception) {
                        // A parser's exception can echo the raw protocol, including its token.
                        Diagnostics.event("follow.protocol_error", diagnosticContext,
                            fields = diagnosticFields() + mapOf("protocolStage" to "invalid_json"),
                            critical = true)
                        throw IOException("开发板跟随响应不是有效 JSON")
                    }
                    if (message.opt("type") == "error") {
                        throw sessionError(message)
                    }
                    return message
                }
                if (bytes.size() >= MAX_LINE_BYTES) throw IOException("开发板跟随响应过长")
                bytes.write(value)
            }
            if (channel.isClosed || channel.isEOF || !channel.isConnected) throw endedError()
            delay(10)
        }
    }

    private fun endedError(): IOException {
        Diagnostics.event("follow.eof", diagnosticContext, fields = diagnosticFields(), critical = true)
        val detail = Diagnostics.safeText(stderr.text().trim(), 600)
        return IOException(if (detail.isNotBlank()) "跟随控制程序退出：$detail"
            else "跟随控制通道已结束，未确认车辆停止；请检查控制占用")
    }

    private fun preserveFault(status: FollowStatus): FollowStatus = status.copy(
        fault = lastStatus?.fault ?: status.fault?.let { Diagnostics.safeText(it) },
    )

    private fun sessionError(message: JSONObject): FollowSessionException {
        val reported = message.optJSONObject("status")?.let { json ->
            runCatching { parseFollowStatus(json) }.getOrNull()?.takeIf { status ->
                !::ready.isInitialized || status.execute == ready.execute && status.camera == ready.camera &&
                    (!message.has("runId") || message.opt("runId") == ready.runId)
            }?.let(::preserveFault)
        }
        reported?.let { lastStatus = it }
        val detail = (message.opt("error") as? String)?.takeIf(String::isNotBlank)
            ?: "开发板跟随会话失败"
        val failure = FollowSessionException(Diagnostics.safeText(detail, 600), reported)
        Diagnostics.failure(diagnosticContext, "follow.session_error", failure,
            diagnosticFields() + statusDiagnosticFields(reported) + mapOf(
                "errorStatusPresent" to (reported != null), "terminal" to false))
        return failure
    }

    private suspend fun blockingCall(action: () -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { cause ->
                Diagnostics.event("follow.blocking_io_cancelled", diagnosticContext,
                    fields = diagnosticFields() + mapOf("causeClass" to cause?.javaClass?.name,
                        "closeScope" to "transport"), critical = true)
                close("blocking_io_cancelled", invalidateTransport = true)
            }
            blockingIo.set(true)
            try {
                if (continuation.isActive && !closed.get()) {
                    action()
                    continuation.resume(Unit)
                } else if (continuation.isActive) {
                    continuation.resumeWithException(IOException("跟随控制通道已关闭"))
                }
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally {
                blockingIo.set(false)
            }
        }
    }

    private fun readToken(message: JSONObject): String {
        val value = message.opt("token")
        requireMessage(value is String && value.matches(Regex("[0-9a-f]{32}")), "跟随控制令牌无效")
        return (value as String).also(Diagnostics::protectSecret)
    }

    private fun diagnosticFields(): Map<String, Any?> = mapOf(
        "phase" to phase, "commandType" to currentCommand, "seq" to sequence,
        "lastAckSeq" to lastAckSequence,
        "lastAckAgeMs" to if (lastAckNanos == 0L) null else elapsedMillis(lastAckNanos),
        "channelConnected" to channel.isConnected, "channelClosed" to channel.isClosed,
        "channelEof" to channel.isEOF, "blockingIo" to blockingIo.get(),
    )

    private fun requireMessage(condition: Boolean, message: String) {
        if (!condition) {
            Diagnostics.event("follow.protocol_error", diagnosticContext,
                fields = diagnosticFields() + mapOf("protocolStage" to "validation", "reason" to message),
                critical = true)
            throw IOException(message)
        }
    }

    internal companion object {
        private const val ACK_TIMEOUT_MS = 1_200L
        private const val START_TIMEOUT_MS = 5_000L
        private const val MAX_LINE_BYTES = 16 * 1024
        private val cleanup = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "follow-channel-cleanup").apply { isDaemon = true }
        }

        suspend fun open(
            session: Session, camera: FollowCamera, execute: Boolean, dryRun: Boolean,
            options: FollowLaunchOptions,
            diagnostic: DiagnosticContext,
            abortTransport: (reason: String, diagnostic: DiagnosticContext) -> Unit,
        ): BoardFollowChannel = withContext(Dispatchers.IO) {
            options.validate(camera)
            require(!dryRun || !execute) { "纯模拟会话不能启用实车执行" }
            val started = System.nanoTime()
            var opened: BoardFollowChannel? = null
            Diagnostics.event("follow.channel_open_requested", diagnostic,
                fields = mapOf("camera" to camera.argument, "execute" to execute,
                    "dryRun" to dryRun, "timeoutMs" to START_TIMEOUT_MS), critical = true)
            try {
                val channel = (session.openChannel("exec") as ChannelExec).apply {
                    setCommand("python3 -u $FOLLOW_PROJECT_ROOT/app_follow_bridge.py --camera ${camera.argument}" +
                        followLaunchArguments(options) +
                        (if (execute) " --execute" else "") + (if (dryRun) " --dry-run" else ""))
                    setPty(false)
                    setAgentForwarding(false)
                    setInputStream(null)
                }
                val stderr = BoundedOutputStream(4096)
                channel.setErrStream(stderr, true)
                val control = BoardFollowChannel(channel, channel.inputStream, channel.outputStream,
                    stderr, abortTransport, diagnostic).also { opened = it }
                withTimeout(START_TIMEOUT_MS) {
                    control.phase = "channel_open"
                    control.blockingCall { channel.connect(START_TIMEOUT_MS.toInt()) }
                    control.phase = "ready_wait"
                    Diagnostics.event("follow.channel_opened", diagnostic,
                        fields = mapOf("elapsedMs" to elapsedMillis(started)))
                    val reply = control.readMessage()
                    control.phase = "ready_validate"
                    control.requireMessage(reply.opt("type") == "ready", "开发板跟随服务未就绪")
                    control.token = control.readToken(reply)
                    val runId = reply.opt("runId")
                    control.requireMessage(runId is String && runId.isNotBlank() && runId.length <= 128,
                        "跟随会话标识无效")
                    control.diagnosticContext = diagnostic.copy(runId = runId as String)
                    control.requireMessage(reply.opt("dryRun") == dryRun && reply.opt("execute") == execute &&
                        reply.opt("camera") == camera.argument, "开发板跟随模式或相机不匹配")
                    control.requireMessage(reply.optJSONObject("options")?.matches(options) == true,
                        "开发板跟随参数与 App 选择不一致，请更新车端桥接脚本")
                    control.requireMessage(reply.opt("heartbeatMs") == 500 && reply.opt("watchdogMs") == 2000,
                        "开发板跟随心跳参数不匹配")
                    control.ready = FollowReady(runId, dryRun, execute, camera)
                    reply.optJSONObject("status")?.let { json ->
                        val status = parseFollowStatus(json)
                        control.requireMessage(status.execute == execute && status.camera == camera,
                            "跟随初始状态的模式或相机不匹配")
                        control.lastStatus = control.preserveFault(status)
                    }
                    control.lastReplyReceivedNanos = System.nanoTime()
                    control.phase = "idle"
                    Diagnostics.event("follow.ready", control.diagnosticContext,
                        fields = mapOf("camera" to camera.argument, "execute" to execute, "dryRun" to dryRun,
                            "elapsedMs" to elapsedMillis(started)), critical = true)
                    control
                }
            } catch (timeout: TimeoutCancellationException) {
                val failure = SocketTimeoutException("跟随服务连接超时，请检查开发板脚本")
                Diagnostics.failure(opened?.diagnosticContext ?: diagnostic, "follow.open_timeout", failure,
                    (opened?.diagnosticFields() ?: mapOf("phase" to "channel_create")) +
                        mapOf("elapsedMs" to elapsedMillis(started), "timeoutMs" to START_TIMEOUT_MS))
                opened?.close("ready_timeout", invalidateTransport = true)
                throw failure
            } catch (failure: Exception) {
                val safeFailure = protocolDiagnosticFailure(failure)
                val fields = (opened?.diagnosticFields() ?: mapOf("phase" to "channel_create")) +
                    mapOf("elapsedMs" to elapsedMillis(started), "originalExceptionClass" to failure.javaClass.name)
                if (failure is CancellationException) {
                    Diagnostics.event("follow.open_cancelled", opened?.diagnosticContext ?: diagnostic,
                        fields = fields, critical = true)
                } else {
                    Diagnostics.failure(opened?.diagnosticContext ?: diagnostic, "follow.open_failed", safeFailure, fields)
                }
                opened?.close(if (failure is CancellationException) "open_cancelled" else "open_failed")
                throw safeFailure
            }
        }
    }
}

internal const val FOLLOW_PROJECT_ROOT = "/home/topeet/Desktop/rk_car_runtime_module"

/** All values come from fixed enums and checked finite numbers; no arbitrary shell text. */
internal fun followLaunchArguments(options: FollowLaunchOptions): String = buildString {
    options.targetDistanceMeters?.let { append(" --target-distance ").append(it) }
    append(if (options.gesturesEnabled) " --gestures" else " --no-gestures")
    append(" --gesture-resume ").append(options.resumeGesture.argument)
    append(" --avoidance ").append(if (options.avoidanceEnabled) "on" else "off")
    append(if (options.reverseEnabled) " --reverse-follow" else " --no-reverse-follow")
    options.reverseDistanceMeters?.let { append(" --reverse-distance ").append(it) }
}

private fun JSONObject.matches(options: FollowLaunchOptions): Boolean =
    numberMatches("targetDistanceM", options.targetDistanceMeters) &&
        opt("gesturesEnabled") == options.gesturesEnabled &&
        opt("resumeGesture") == options.resumeGesture.argument &&
        opt("avoidance") == (if (options.avoidanceEnabled) "on" else "off") &&
        opt("reverseEnabled") == options.reverseEnabled &&
        numberMatches("reverseDistanceM", options.reverseDistanceMeters)

private fun JSONObject.numberMatches(key: String, expected: Double?): Boolean {
    val actual = opt(key)
    return if (expected == null) actual == null || actual == JSONObject.NULL
        else actual is Number && actual.toDouble() == expected
}

internal fun followConfigurationCommand(
    camera: FollowCamera, options: FollowLaunchOptions = FollowLaunchOptions(),
    execute: Boolean = false,
): String {
    options.validate(camera)
    return "python3 -u $FOLLOW_PROJECT_ROOT/app_follow_bridge.py --camera ${camera.argument}" +
        followLaunchArguments(options) + (if (execute) " --execute" else "") + " --check-config"
}

/** An error response ends the protocol; its optional snapshot never proves a terminal ACK. */
class FollowSessionException(message: String, val status: FollowStatus?) : IOException(message)

/** Explicit fields only: never serialize the entire status or response into diagnostics. */
private fun statusDiagnosticFields(status: FollowStatus?): Map<String, Any?> = mapOf(
    "runState" to status?.runState,
    "processExited" to status?.processExited,
    "stopRequested" to status?.stopRequested,
    "stopAcknowledged" to status?.stopAcknowledged,
    "statusAgeMs" to status?.statusAgeMs,
    "boardFault" to status?.fault,
    "finalStatusPending" to status?.finalStatusPending,
    "exitCode" to status?.exitCode,
    "logDir" to status?.logDir,
    "startupStage" to status?.startup?.stage,
    "startupStageElapsedMs" to status?.startup?.stageElapsedMs,
    "startupTotalElapsedMs" to status?.startup?.totalElapsedMs,
    "startupStageTimeoutMs" to status?.startup?.stageTimeoutMs,
    "startupTotalTimeoutMs" to status?.startup?.totalTimeoutMs,
    "startupCompleted" to status?.startup?.completed,
)

/** Android JSONObject alone also accepts trailing text and duplicate keys; neither is a frame. */
internal fun parseFollowWireMessage(bytes: ByteArray): JSONObject {
    val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    JsonReader(StringReader(text)).use { reader ->
        reader.isLenient = false
        require(reader.peek() == JsonToken.BEGIN_OBJECT)
        validateFollowJsonValue(reader, 0)
        require(reader.peek() == JsonToken.END_DOCUMENT)
    }
    return JSONObject(text)
}

private fun validateFollowJsonValue(reader: JsonReader, depth: Int) {
    require(depth <= 32)
    when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val keys = HashSet<String>()
            reader.beginObject()
            while (reader.hasNext()) {
                require(keys.add(reader.nextName()))
                validateFollowJsonValue(reader, depth + 1)
            }
            reader.endObject()
        }
        JsonToken.BEGIN_ARRAY -> {
            reader.beginArray()
            while (reader.hasNext()) validateFollowJsonValue(reader, depth + 1)
            reader.endArray()
        }
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> reader.nextNull()
        else -> throw IOException("开发板跟随响应不是有效 JSON")
    }
}
