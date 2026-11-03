package com.example.followingcar

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.Session
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class DriveReady(
    val port: String, val dryRun: Boolean, val watchdogMs: Int, val maxRpm: Int,
    val parkingSupported: Boolean = false, val parking: ParkingSettings? = null,
    val infraredSupported: Boolean = false,
    val networkGraceMs: Int? = null,
    val safetyVersion: Int = 0,
    val parkingCurrentReadOnly: Boolean = false,
    val feedback: DriveFeedback? = null,
)
data class DriveAck(
    val left: Int, val right: Int, val stopped: Boolean,
    val parking: ParkingSettings? = null,
    val interlock: DriveInterlock? = null,
    val recoveryRequired: Boolean = false,
    val targetStopped: Boolean = left == 0 && right == 0,
    val feedback: DriveFeedback? = null,
    val feedbackReceivedNanos: Long = 0L,
    val feedbackAgeAtReceiptMs: Long = Long.MAX_VALUE,
)

/**
 * One request and ACK at a time, with a new board-issued token for every request.
 * Callers send their latest target every 100 ms; this class never queues motion targets.
 */
class BoardDriveChannel private constructor(
    private val channel: ChannelExec,
    private val input: InputStream,
    private val output: OutputStream,
    private val stderr: BoundedOutputStream,
    private val abortTransport: (reason: String, diagnostic: DiagnosticContext) -> Unit,
    diagnosticContext: DiagnosticContext,
) {
    @Volatile var diagnosticContext: DiagnosticContext = diagnosticContext
        private set
    private val transaction = DriveTransactionGate()
    private val closed = AtomicBoolean(false)
    private val blockingIo = AtomicBoolean(false)
    private var token = ""
    private var sequence = 0L
    private var confirmedParking: ParkingSettings? = null
    @Volatile private var phase = "created"
    @Volatile private var currentCommand = "open"
    @Volatile private var lastAckSequence = 0L
    @Volatile private var lastAckNanos = 0L
    @Volatile private var lastAckRttMs: Long? = null
    @Volatile private var sendGapMs: Long? = null
    private var lastSendNanos = 0L
    private var summaryAt = System.nanoTime()
    private var summaryCount = 0
    private var summaryRttTotal = 0L
    private var summaryRttMax = 0L
    lateinit var ready: DriveReady
        private set

    val isOpen: Boolean
        get() = !closed.get() && channel.isConnected && !channel.isClosed

    val hasPendingTransaction: Boolean get() = transaction.isPending()

    /** Network grace never delays an intentional stop. Idle channels may still send close/estop. */
    fun interruptForActiveStop(source: ControlStopSource): Boolean {
        if (!::ready.isInitialized || ready.networkGraceMs == null) return false
        val pending = transaction.requestStop(source.name)
        Diagnostics.event("drive.active_stop_gate", diagnosticContext, source = source.name,
            fields = diagnosticFields() + mapOf("interruptedTransaction" to pending), critical = true)
        if (pending) close("active_stop_${source.name.lowercase()}")
        return pending
    }

    suspend fun exchange(left: Int, right: Int, emergency: Boolean = false): DriveAck {
        require(left in -ready.maxRpm..ready.maxRpm && right in -ready.maxRpm..ready.maxRpm) {
            "摇杆速度超过允许范围"
        }
        val command = when {
            emergency -> "estop"
            left == 0 && right == 0 -> "stop"
            else -> "drive"
        }
        val result = transact(command, if (emergency) 0 else left, if (emergency) 0 else right)
        if (emergency) close("emergency_ack")
        return result
    }

    suspend fun stopAndClose(): DriveAck {
        try {
            check(isOpen) { "摇杆通道已关闭，未收到本次停车回执" }
            return transact("close", 0, 0)
        } finally {
            close("stop_and_close")
        }
    }

    /** Serialized by the same caller as the heartbeat; the board also requires a stopped target. */
    suspend fun configureParking(settings: ParkingSettings): DriveAck {
        check(ready.parkingSupported) { "开发板脚本不支持驻车设置，请更新 app_joystick_bridge.py" }
        check(ready.parkingCurrentReadOnly && confirmedParking != null &&
            settings.copy(enabled = confirmedParking!!.enabled) == confirmedParking) {
            "手动控制期间驻车电流只读，请停用手动控制后到电机配置页修改"
        }
        check(!settings.enabled || settings.canEnable) { "驻车电流配置不具备开启条件，请检查电机配置" }
        return transact("parking", 0, 0, settings)
    }

    /** Nonblocking for UI/lifecycle callers. EOF or the board watchdog stops the motors. */
    fun close(reason: String = "requested", invalidateTransport: Boolean = false) {
        if (!closed.compareAndSet(false, true)) return
        val abort = invalidateTransport || blockingIo.get()
        Diagnostics.event("drive.channel_close", diagnosticContext, source = reason,
            fields = diagnosticFields() + mapOf("closeScope" to if (abort) "transport" else "channel"),
            critical = true)
        // Explicitly interrupt a network write if one is in flight. A partially sent control
        // command must not be retried on this transport.
        if (abort) abortTransport("drive_$reason", diagnosticContext)
        cleanup.execute {
            runCatching { channel.disconnect() }
            runCatching { input.close() }
        }
    }

    private suspend fun transact(
        command: String, left: Int, right: Int, parking: ParkingSettings? = null,
    ): DriveAck =
        withContext(Dispatchers.IO) {
            check(ready.safetyVersion == REQUIRED_DRIVE_SAFETY_VERSION && ready.parkingCurrentReadOnly) {
                "开发板安全控制协议尚未确认，拒绝发送控制指令"
            }
            // Refuse concurrency instead of keeping stale movement requests in a queue.
            transaction.begin(terminal = command == "estop" || command == "close")
            val started = System.nanoTime()
            val transition = command != currentCommand
            val important = parking != null || command == "estop" || command == "close" || transition
            val report = important || elapsedMillis(summaryAt) >= 1_000L
            currentCommand = command
            phase = "check_open"
            try {
                check(isOpen) { "摇杆控制通道已关闭，请重新启用" }
                val closing = command == "estop" || command == "close"
                withTimeout(if (parking != null || closing) PARKING_ACK_TIMEOUT_MS else driveAckTimeoutMillis(ready.networkGraceMs)) {
                    sequence += 1
                    val sentSequence = sequence
                    val previousToken = token
                    val request = JSONObject().put("seq", sentSequence).put("token", previousToken)
                        .put("cmd", command).put("left", left).put("right", right)
                    if (parking != null) {
                        request.put("parkingEnabled", parking.enabled)
                    }
                    phase = "write"
                    val sendingNanos = System.nanoTime()
                    sendGapMs = if (lastSendNanos == 0L) null else
                        ((sendingNanos - lastSendNanos) / 1_000_000L).coerceAtLeast(0L)
                    lastSendNanos = sendingNanos
                    if (report) Diagnostics.event("drive.request_send", diagnosticContext,
                        fields = mapOf("commandType" to command, "seq" to sentSequence,
                            "sendGapMs" to sendGapMs, "lastAckRttMs" to lastAckRttMs,
                            "leftRpm" to left, "rightRpm" to right,
                            "parkingEnabled" to parking?.enabled, "parkingCurrentReadOnly" to true),
                        critical = important)
                    blockingCall {
                        output.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    phase = "ack_wait"
                    val reply = readMessage()
                    phase = "ack_validate"
                    requireMessage(reply.getString("type") == "ack", "开发板返回了未知控制响应")
                    requireMessage(reply.getLong("seq") == sentSequence, "开发板回执序号不匹配")
                    val nextToken = readToken(reply)
                    requireMessage(nextToken != previousToken, "开发板控制令牌未更新")
                    val rawLeft = reply.opt("left")
                    val rawRight = reply.opt("right")
                    val rawStopped = reply.opt("stopped")
                    requireMessage(rawLeft is Number && rawRight is Number && rawStopped is Boolean &&
                        rawLeft.toDouble() == rawLeft.toInt().toDouble() &&
                        rawRight.toDouble() == rawRight.toInt().toDouble(), "开发板速度或停车回执格式无效")
                    val actualLeft = (rawLeft as Number).toInt()
                    val actualRight = (rawRight as Number).toInt()
                    val measured = readFeedback(reply, ready.dryRun, actualLeft, actualRight)
                    val stopped = measured.stopped
                    requireMessage(!(closing || parking != null) || stopped,
                        "开发板终止或驻车操作缺少实测停车确认")
                    val rawRecovery = reply.opt("recoveryRequired")
                    requireMessage(!reply.has("recoveryRequired") || rawRecovery is Boolean, "开发板网络恢复回执格式无效")
                    val recoveryRequired = try {
                        validatedDriveRecovery(ready.networkGraceMs != null, rawRecovery as? Boolean,
                            actualLeft, actualRight, measured.targetStopped)
                    } catch (failure: IllegalArgumentException) {
                        requireMessage(false, failure.message ?: "开发板网络恢复回执无效")
                        false
                    }
                    val interlock = readInterlock(reply, command, left, right, actualLeft, actualRight,
                        measured.targetStopped, recoveryRequired)
                    val actualParking = if (ready.parkingSupported) readParking(reply) else null
                    if (ready.parkingSupported) {
                        val expectedParking = if (closing) confirmedParking?.copy(enabled = false)
                            else parking ?: confirmedParking
                        requireMessage(actualParking == expectedParking, "开发板驻车回执与设置不一致")
                    }
                    token = nextToken
                    confirmedParking = actualParking
                    lastAckSequence = sentSequence
                    lastAckNanos = System.nanoTime()
                    val rtt = elapsedMillis(started)
                    if (recoveryRequired || rtt >= 900L) Diagnostics.event("drive.network_recovery", diagnosticContext,
                        fields = mapOf("seq" to sentSequence, "elapsedMs" to rtt,
                            "recoveryRequired" to recoveryRequired, "stopped" to stopped,
                            "networkGraceMs" to ready.networkGraceMs), critical = true)
                    lastAckRttMs = rtt
                    summaryCount += 1
                    summaryRttTotal += rtt
                    summaryRttMax = maxOf(summaryRttMax, rtt)
                    if (report) {
                        Diagnostics.event("drive.ack_summary", diagnosticContext, fields = mapOf(
                            "commandType" to command, "seq" to sentSequence, "elapsedMs" to rtt,
                            "sendGapMs" to sendGapMs, "lastAckRttMs" to lastAckRttMs,
                            "sampleCount" to summaryCount, "maxRttMs" to summaryRttMax,
                            "meanRttMs" to summaryRttTotal / summaryCount,
                            "leftRpm" to actualLeft, "rightRpm" to actualRight, "targetStopped" to measured.targetStopped,
                            "stopped" to stopped, "feedbackLeftRpm" to measured.feedback.leftRpm,
                            "feedbackRightRpm" to measured.feedback.rightRpm,
                            "feedbackLeftError" to measured.feedback.leftError,
                            "feedbackRightError" to measured.feedback.rightError,
                            "feedbackAgeMs" to measured.feedback.ageMs, "feedbackSamples" to measured.feedback.samples,
                            "feedbackSource" to measured.feedback.source),
                            critical = important)
                        summaryAt = System.nanoTime()
                        summaryCount = 0
                        summaryRttTotal = 0
                        summaryRttMax = 0
                    }
                    phase = "idle"
                    DriveAck(actualLeft, actualRight, stopped, actualParking, interlock, recoveryRequired,
                        measured.targetStopped, measured.feedback, lastAckNanos, measured.feedback.ageMs + rtt)
                }
            } catch (timeout: TimeoutCancellationException) {
                transaction.interruptedStopSource()?.let { throw activeStopCancellation(it) }
                val failure = SocketTimeoutException("控制回执等待超时，连接已关闭；请先重新连接再启用手动控制")
                Diagnostics.failure(diagnosticContext, "drive.ack_timeout", failure,
                    diagnosticFields() + mapOf("elapsedMs" to elapsedMillis(started),
                        "leftRpm" to left, "rightRpm" to right))
                // A completed write does not prove that a blackholed SSH connection is alive.
                close("ack_timeout", invalidateTransport = true)
                throw failure
            } catch (failure: Exception) {
                transaction.interruptedStopSource()?.let { throw activeStopCancellation(it) }
                val safeFailure = protocolDiagnosticFailure(failure)
                if (failure is CancellationException) {
                    Diagnostics.event("drive.transaction_cancelled", diagnosticContext,
                        fields = diagnosticFields(), critical = true)
                } else {
                    Diagnostics.failure(diagnosticContext, "drive.transaction_failed", safeFailure,
                        diagnosticFields() + mapOf("elapsedMs" to elapsedMillis(started),
                            "leftRpm" to left, "rightRpm" to right,
                            "originalExceptionClass" to failure.javaClass.name))
                }
                close(if (failure is CancellationException) "transaction_cancelled" else "transaction_failed")
                throw safeFailure
            } finally {
                transaction.finish()
            }
        }

    private fun activeStopCancellation(source: String): CancellationException {
        Diagnostics.event("drive.transaction_cancelled", diagnosticContext, source = source,
            fields = diagnosticFields() + mapOf("intentionalStop" to true), critical = true)
        close("active_stop_cancelled")
        return DriveStopRequestedException(source)
    }

    private suspend fun readMessage(): JSONObject {
        val bytes = ByteArrayOutputStream()
        while (true) {
            currentCoroutineContext().ensureActive()
            if (closed.get()) throw IOException("摇杆控制通道已关闭")
            while (input.available() > 0) {
                val byte = input.read()
                if (byte < 0) throw endedError()
                if (byte == '\n'.code) {
                    val message = try {
                        JSONObject(bytes.toString(Charsets.UTF_8.name()))
                    } catch (failure: Exception) {
                        Diagnostics.event("drive.protocol_error", diagnosticContext,
                            fields = diagnosticFields() + mapOf("protocolStage" to "invalid_json"),
                            critical = true)
                        throw IOException("开发板控制响应不是有效 JSON")
                    }
                    if (message.optString("type") == "error") {
                        throw IOException(message.optString("error", "开发板控制程序报错").take(500))
                    }
                    return message
                }
                if (bytes.size() >= MAX_LINE_BYTES) throw IOException("开发板控制响应过长")
                bytes.write(byte)
            }
            if (channel.isClosed || channel.isEOF || !channel.isConnected) throw endedError()
            delay(10)
        }
    }

    private fun endedError(): IOException {
        Diagnostics.event("drive.eof", diagnosticContext, fields = diagnosticFields(), critical = true)
        val detail = stderr.text().trim().take(500)
        return IOException(
            if (detail.isNotBlank()) "摇杆控制程序退出：$detail"
            else "摇杆控制通道已结束，可能触发通信超时停车，请重新启用"
        )
    }

    /** JSch writes are blocking; a timeout/cancellation must close the real socket too. */
    private suspend fun blockingCall(action: () -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation { cause ->
                Diagnostics.event("drive.blocking_io_cancelled", diagnosticContext,
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
                    continuation.resumeWithException(IOException("摇杆控制通道已关闭"))
                }
            } catch (failure: Exception) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            } finally {
                blockingIo.set(false)
            }
        }
    }

    private fun readToken(message: JSONObject): String {
        val value = message.getString("token")
        requireMessage(value.isNotBlank() && value.length <= 256, "开发板控制令牌无效")
        return value.also(Diagnostics::protectSecret)
    }

    private fun diagnosticFields(): Map<String, Any?> = mapOf(
        "phase" to phase, "commandType" to currentCommand, "seq" to sequence,
        "lastAckSeq" to lastAckSequence,
        "lastAckRttMs" to lastAckRttMs, "sendGapMs" to sendGapMs,
        "lastAckAgeMs" to if (lastAckNanos == 0L) null else elapsedMillis(lastAckNanos),
        "channelConnected" to channel.isConnected, "channelClosed" to channel.isClosed,
        "channelEof" to channel.isEOF, "blockingIo" to blockingIo.get(),
        "transactionPending" to hasPendingTransaction,
        "activeStopSource" to transaction.requestedStopSource(),
        "ackTimeoutMs" to if (::ready.isInitialized) driveAckTimeoutMillis(ready.networkGraceMs) else null,
    )

    private fun requireMessage(condition: Boolean, message: String) {
        if (!condition) {
            Diagnostics.event("drive.protocol_error", diagnosticContext,
                fields = diagnosticFields() + mapOf("protocolStage" to "validation", "reason" to message),
                critical = true)
            throw IOException(message)
        }
    }

    private fun readParking(message: JSONObject): ParkingSettings {
        return readSafety { validatedParkingSettings(listOf("parkingEnabled", "parkingLeftCurrentAmps",
            "parkingRightCurrentAmps", "parkingMaxCurrentAmps").associateWith(message::opt)) }
    }

    private fun readFeedback(message: JSONObject, dryRun: Boolean, left: Int, right: Int): ValidatedDriveFeedback =
        readSafety { validatedDriveFeedback(listOf("targetStopped", "stopped", "feedbackLeftRpm", "feedbackRightRpm",
            "feedbackLeftError", "feedbackRightError", "feedbackAgeMs", "feedbackSamples", "feedbackSource")
            .associateWith(message::opt), dryRun, left, right) }

    private fun <T> readSafety(block: () -> T): T = try {
        block()
    } catch (failure: IllegalArgumentException) {
        requireMessage(false, failure.message ?: "开发板安全反馈格式无效")
        throw IOException("开发板安全反馈格式无效")
    }

    private fun readInterlock(reply: JSONObject, command: String, requestedLeft: Int, requestedRight: Int,
        actualLeft: Int, actualRight: Int, stopped: Boolean, recoveryRequired: Boolean): DriveInterlock? {
        var blocked: Boolean? = null
        var directions: List<String>? = null
        if (ready.infraredSupported || reply.has("infraredBlocked") || reply.has("infraredDirections")) {
            val rawBlocked = reply.opt("infraredBlocked")
            val rawDirections = reply.opt("infraredDirections")
            requireMessage(rawBlocked is Boolean && rawDirections is JSONArray, "开发板红外保护回执格式无效")
            blocked = rawBlocked as Boolean
            val array = rawDirections as JSONArray
            requireMessage(array.length() <= 3, "开发板红外保护方向过多")
            directions = (0 until array.length()).map { index ->
                val direction = array.opt(index)
                requireMessage(direction is String, "开发板红外保护方向格式无效")
                direction as String
            }
        }
        return try {
            // A negotiated network stop may suppress movement independently of infrared.
            val suppressed = recoveryRequired && blocked != true
            validatedDriveInterlock(ready.infraredSupported, command,
                if (suppressed) 0 else requestedLeft, if (suppressed) 0 else requestedRight,
                actualLeft, actualRight, stopped, blocked, directions)
        } catch (failure: IllegalArgumentException) {
            requireMessage(false, failure.message ?: "开发板红外保护回执无效")
            null // requireMessage always throws above.
        }
    }

    internal companion object {
        // Parking mode changes and terminal cleanup wait for measured zero-speed feedback.
        private const val PARKING_ACK_TIMEOUT_MS = 2_500L
        private const val START_TIMEOUT_MS = 5_000L
        private const val MAX_LINE_BYTES = 4096
        private val cleanup = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "drive-channel-cleanup").apply { isDaemon = true }
        }

        suspend fun open(
            session: Session, dryRun: Boolean, diagnostic: DiagnosticContext,
            abortTransport: (reason: String, diagnostic: DiagnosticContext) -> Unit,
        ): BoardDriveChannel =
            withContext(Dispatchers.IO) {
                val started = System.nanoTime()
                var opened: BoardDriveChannel? = null
                Diagnostics.event("drive.channel_open_requested", diagnostic,
                    fields = mapOf("dryRun" to dryRun, "timeoutMs" to START_TIMEOUT_MS), critical = true)
                try {
                    val channel = (session.openChannel("exec") as ChannelExec).apply {
                        setCommand(driveBridgeCommand(dryRun))
                        setPty(false)
                        setAgentForwarding(false)
                        setInputStream(null)
                    }
                    val stderr = BoundedOutputStream(2048)
                    channel.setErrStream(stderr, true)
                    val control = BoardDriveChannel(channel, channel.inputStream, channel.outputStream,
                        stderr, abortTransport, diagnostic).also { opened = it }
                    withTimeout(START_TIMEOUT_MS) {
                        control.phase = "channel_open"
                        control.blockingCall { channel.connect(START_TIMEOUT_MS.toInt()) }
                        control.phase = "ready_wait"
                        Diagnostics.event("drive.channel_opened", diagnostic,
                            fields = mapOf("elapsedMs" to elapsedMillis(started)))
                        val reply = control.readMessage()
                        control.phase = "ready_validate"
                        control.requireMessage(reply.getString("type") == "ready", "开发板未就绪")
                        control.readSafety { validateDriveSafetyVersion(reply.opt("safetyVersion"),
                            reply.opt("parkingCurrentReadOnly")) }
                        control.token = control.readToken(reply)
                        control.requireMessage(reply.opt("parkingSupported") == true, "开发板安全驻车能力未确认")
                        val parkingSupported = true
                        val parking = control.readParking(reply)
                        control.requireMessage(!parking.enabled, "开发板初始驻车模式无效")
                        control.requireMessage(listOf("left", "right").all { key ->
                            val value = reply.opt(key)
                            value is Number && value.toDouble() == 0.0
                        }, "开发板初始目标未归零")
                        val initialFeedback = control.readFeedback(reply, dryRun, 0, 0)
                        control.requireMessage(initialFeedback.stopped, "开发板初始实测停车未确认")
                        val rawInfraredSupported = reply.opt("infraredSupported")
                        control.requireMessage(!reply.has("infraredSupported") || rawInfraredSupported is Boolean,
                            "开发板红外保护能力字段无效")
                        val rawRunId = reply.opt("runId")
                        val rawGrace = reply.opt("networkGraceMs")
                        control.requireMessage(!reply.has("networkGraceMs") ||
                            rawGrace is Number && rawGrace.toDouble() == 5_000.0,
                            "开发板网络恢复参数不匹配")
                        control.requireMessage(!reply.has("runId") ||
                            rawRunId is String && rawRunId.matches(Regex("[0-9a-f]{32}")),
                            "开发板控制会话编号无效")
                        val ready = DriveReady(
                            port = reply.getString("port"), dryRun = reply.getBoolean("dryRun"),
                            watchdogMs = reply.getInt("watchdogMs"), maxRpm = reply.getInt("maxRpm"),
                            parkingSupported = parkingSupported, parking = parking,
                            infraredSupported = rawInfraredSupported == true,
                            networkGraceMs = (rawGrace as? Number)?.toInt(),
                            safetyVersion = REQUIRED_DRIVE_SAFETY_VERSION, parkingCurrentReadOnly = true,
                            feedback = initialFeedback.feedback,
                        )
                        control.requireMessage(ready.dryRun == dryRun, "开发板控制模式不匹配")
                        control.requireMessage(ready.maxRpm in 1..100 && ready.watchdogMs == 600, "开发板限速或超时参数不匹配")
                        control.ready = ready
                        control.confirmedParking = parking
                        if (rawRunId is String) control.diagnosticContext = diagnostic.copy(runId = rawRunId)
                        control.phase = "idle"
                        Diagnostics.event("drive.ready", control.diagnosticContext, fields = mapOf(
                            "dryRun" to ready.dryRun, "port" to ready.port,
                            "safetyVersion" to ready.safetyVersion,
                            "parkingCurrentReadOnly" to ready.parkingCurrentReadOnly,
                            "parkingLeftCurrentAmps" to ready.parking?.leftCurrentAmps,
                            "parkingRightCurrentAmps" to ready.parking?.rightCurrentAmps,
                            "parkingMaxCurrentAmps" to ready.parking?.maxCurrentAmps,
                            "parkingCanEnable" to (ready.parking?.canEnable == true),
                            "feedbackSource" to initialFeedback.feedback.source,
                            "feedbackSamples" to initialFeedback.feedback.samples,
                            "feedbackLeftRpm" to initialFeedback.feedback.leftRpm,
                            "feedbackRightRpm" to initialFeedback.feedback.rightRpm,
                            "parkingSupported" to ready.parkingSupported,
                            "infraredSupported" to ready.infraredSupported,
                            "networkGraceMs" to ready.networkGraceMs,
                            "ackTimeoutMs" to driveAckTimeoutMillis(ready.networkGraceMs),
                            "elapsedMs" to elapsedMillis(started)), critical = true)
                        control
                    }
                } catch (timeout: TimeoutCancellationException) {
                    val failure = SocketTimeoutException("启动摇杆控制超时，请检查串口、电机电源和控制脚本")
                    Diagnostics.failure(opened?.diagnosticContext ?: diagnostic, "drive.open_timeout", failure,
                        (opened?.diagnosticFields() ?: mapOf("phase" to "channel_create")) +
                            mapOf("elapsedMs" to elapsedMillis(started), "timeoutMs" to START_TIMEOUT_MS))
                    opened?.close("ready_timeout", invalidateTransport = true)
                    throw failure
                } catch (failure: Exception) {
                    val safeFailure = protocolDiagnosticFailure(failure)
                    val fields = (opened?.diagnosticFields() ?: mapOf("phase" to "channel_create")) +
                        mapOf("elapsedMs" to elapsedMillis(started), "originalExceptionClass" to failure.javaClass.name)
                    if (failure is CancellationException) {
                        Diagnostics.event("drive.open_cancelled", opened?.diagnosticContext ?: diagnostic, fields = fields, critical = true)
                    } else {
                        Diagnostics.failure(opened?.diagnosticContext ?: diagnostic, "drive.open_failed", safeFailure, fields)
                    }
                    opened?.close(if (failure is CancellationException) "open_cancelled" else "open_failed")
                    throw safeFailure
                }
            }
    }
}
