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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
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
    val unifiedParkingSupported: Boolean = false,
    val parkingSemantics: String? = null,
    val stopConfirmationWarningSupported: Boolean = false,
    val feedbackNoiseMaxRpm: Int = 2,
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
    val feedbackRecheck: Boolean = false,
    val standstillPending: Boolean = false,
    val parkingResult: ParkingReply? = null,
    val parkingMode: Boolean? = null,
    val parkingModeSource: String? = null,
    val stopCommandConfirmed: Boolean = false,
    val communicationDelayed: Boolean = false,
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
    private var recoveryReportState = 0
    private var recoveryReportNanos = 0L
    private var ackDelayHandler: (suspend (Long) -> Unit)? = null
    lateinit var ready: DriveReady
        private set

    val isOpen: Boolean
        get() = !closed.get() && channel.isConnected && !channel.isClosed

    val hasPendingTransaction: Boolean get() = transaction.isPending()

    internal fun configureAckDelayHandler(handler: suspend (Long) -> Unit) {
        ackDelayHandler = handler
    }

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
        check(ready.parkingSemantics != PARKING_CURRENT_SEMANTICS) {
            "驻车开关已合并到电流设置：0 A 关闭，正电流开启，请在驻车页保存电流"
        }
        check(ready.parkingSupported) { "开发板脚本不支持驻车设置，请更新 app_joystick_bridge.py" }
        check(ready.parkingCurrentReadOnly && confirmedParking != null &&
            settings.copy(enabled = confirmedParking!!.enabled) == confirmedParking) {
            "手动控制期间驻车电流只读，请停用手动控制后到电机配置页修改"
        }
        check(!settings.enabled || settings.canEnable) { "驻车电流配置不具备开启条件，请检查电机配置" }
        return transact("parking", 0, 0, settings)
    }

    /** Uses the existing owner and request sequence; never opens a competing motor bridge. */
    internal suspend fun parkingSettings(request: ParkingRequest): DriveAck {
        check(ready.unifiedParkingSupported && ready.parkingSemantics == PARKING_CURRENT_SEMANTICS) {
            "开发板尚未支持新版驻车电流接口，请更新桥接程序"
        }
        return transact("parking_settings", 0, 0, parkingRequest = request)
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
        parkingRequest: ParkingRequest? = null,
    ): DriveAck =
        withContext(Dispatchers.IO) {
            check(ready.safetyVersion == REQUIRED_DRIVE_SAFETY_VERSION && ready.parkingCurrentReadOnly) {
                "开发板安全控制协议尚未确认，拒绝发送控制指令"
            }
            // Refuse concurrency instead of keeping stale movement requests in a queue.
            transaction.begin(terminal = command == "estop" || command == "close")
            val started = System.nanoTime()
            val transition = command != currentCommand
            val important = parking != null || parkingRequest != null || command == "estop" || command == "close" || transition
            val report = important || elapsedMillis(summaryAt) >= 1_000L
            currentCommand = command
            phase = "check_open"
            var parkingReplyBeforeFailure: ParkingReply? = null
            var communicationDelayed = false
            try {
                check(isOpen) { "摇杆控制通道已关闭，请重新启用" }
                val closing = command == "estop" || command == "close"
                withTimeout(when {
                    parkingRequest != null -> UNIFIED_PARKING_ACK_TIMEOUT_MS
                    parking != null || closing -> PARKING_ACK_TIMEOUT_MS
                    else -> driveAckTimeoutMillis(ready.networkGraceMs)
                }) {
                    sequence += 1
                    val sentSequence = sequence
                    val previousToken = token
                    val request = JSONObject().put("seq", sentSequence).put("token", previousToken)
                        .put("cmd", command).put("left", left).put("right", right)
                    if (parking != null) {
                        request.put("parkingEnabled", parking.enabled)
                    }
                    parkingRequest?.addTo(request)?.put("schemaVersion", 2)
                        ?.put("parkingSemantics", PARKING_CURRENT_SEMANTICS)
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
                    withTimeout(DRIVE_WRITE_TIMEOUT_MS) {
                        blockingCall {
                            output.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                            output.flush()
                        }
                    }
                    phase = "ack_wait"
                    // Keep one reader, including its partial JSON line, alive across the
                    // warning boundary. Never resend a request whose board token is unknown.
                    val reply = if (!closing && parking == null && parkingRequest == null && ready.networkGraceMs != null) {
                        coroutineScope {
                            val warning = launch {
                                delay(DRIVE_ACK_WARNING_MS)
                                if (phase == "ack_wait" && isOpen) {
                                    communicationDelayed = true
                                    val elapsed = elapsedMillis(started)
                                    Diagnostics.event("drive.ack_delayed", diagnosticContext,
                                        fields = diagnosticFields() + mapOf("elapsedMs" to elapsed,
                                            "severity" to "warning", "sessionRetained" to true), critical = true)
                                    ackDelayHandler?.invoke(elapsed)
                                }
                            }
                            try { readMessage() } finally { warning.cancel() }
                        }
                    } else readMessage()
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
                    val parkingResult = if (parkingRequest != null) {
                        parseParkingReply(reply.getJSONObject("parkingResult"), sentSequence.toString(), parkingRequest, ready.dryRun)
                    } else null
                    parkingReplyBeforeFailure = parkingResult
                    val measured = readFeedback(reply, ready.dryRun, actualLeft, actualRight)
                    val stopped = measured.stopped
                    val rawStopCommand = reply.opt("stopCommandConfirmed")
                    requireMessage(!reply.has("stopCommandConfirmed") || rawStopCommand is Boolean,
                        "开发板停止指令确认字段无效")
                    val stopCommandConfirmed = rawStopCommand == true
                    requireMessage(!stopCommandConfirmed || measured.targetStopped,
                        "停止指令确认与目标不一致")
                    requireMessage(!closing || stopped || ready.stopConfirmationWarningSupported && stopCommandConfirmed,
                        "开发板终止操作未确认停止指令")
                    requireMessage(parking == null || stopped, "开发板驻车操作缺少实测停车确认")
                    val rawRecovery = reply.opt("recoveryRequired")
                    requireMessage(!reply.has("recoveryRequired") || rawRecovery is Boolean, "开发板网络恢复回执格式无效")
                    val recoveryRequired = try {
                        validatedDriveRecovery(ready.networkGraceMs != null, rawRecovery as? Boolean,
                            actualLeft, actualRight, measured.targetStopped)
                    } catch (failure: IllegalArgumentException) {
                        requireMessage(false, failure.message ?: "开发板网络恢复回执无效")
                        false
                    }
                    val rawFeedbackRecheck = reply.opt("feedbackRecheck")
                    requireMessage(!reply.has("feedbackRecheck") || rawFeedbackRecheck is Boolean,
                        "开发板轮速复核回执格式无效")
                    val feedbackRecheck = rawFeedbackRecheck == true
                    requireMessage(!feedbackRecheck || recoveryRequired && measured.targetStopped,
                        "轮速复核时必须保持零目标并重新确认输入")
                    val rawStandstillPending = reply.opt("standstillPending")
                    requireMessage(!reply.has("standstillPending") || rawStandstillPending is Boolean,
                        "开发板停车等待回执格式无效")
                    val standstillPending = rawStandstillPending == true
                    requireMessage(!standstillPending || feedbackRecheck && recoveryRequired && measured.targetStopped,
                        "停车等待时必须保持零目标与输入恢复锁")
                    val refusedMovingRead = parkingRequest?.op == "read" && parkingResult?.ok == false &&
                        parkingResult.errorCode == "motion_targets_active" && !parkingResult.writeMayHaveOccurred
                    requireMessage(parkingRequest == null || ready.unifiedParkingSupported &&
                        (measured.targetStopped || refusedMovingRead), "统一驻车操作必须保持零转速目标")
                    val interlock = readInterlock(reply, command,
                        if (refusedMovingRead) actualLeft else left, if (refusedMovingRead) actualRight else right,
                        actualLeft, actualRight, measured.targetStopped, recoveryRequired)
                    val actualParking = if (ready.parkingSupported) readParking(reply) else null
                    val modeSource = if (ready.unifiedParkingSupported) reply.opt("parkingModeSource") else null
                    val rawMode = if (ready.unifiedParkingSupported) reply.opt("parkingMode") else null
                    if (ready.parkingSemantics == PARKING_CURRENT_SEMANTICS) {
                        requireMessage(reply.opt("parkingSemantics") == PARKING_CURRENT_SEMANTICS &&
                            reply.has("parkingMode") && (rawMode == JSONObject.NULL || rawMode is Boolean),
                            "开发板驻车电流协议字段无效")
                        readSafety { validateParkingCurrentReadback(rawMode as? Boolean, modeSource as? String,
                            requireNotNull(actualParking)) }
                        requireMessage(parkingResult == null || !parkingResult.ok || parkingResult.mode == rawMode as? Boolean,
                            "驻车操作结果与电流状态不一致")
                    } else if (ready.unifiedParkingSupported) {
                        requireMessage(reply.has("parkingMode") && (rawMode == JSONObject.NULL || rawMode is Boolean) &&
                            modeSource in setOf("unknown", "manual_session"), "开发板驻车模式来源字段无效")
                        requireMessage((rawMode == JSONObject.NULL) == (modeSource == "unknown"), "开发板驻车模式来源不一致")
                        requireMessage(rawMode !is Boolean || rawMode == actualParking?.enabled,
                            "开发板驻车模式记录不一致")
                        requireMessage(parkingResult == null || !parkingResult.ok || parkingResult.mode == rawMode as? Boolean,
                            "驻车操作结果与模式记录不一致")
                    }
                    if (ready.parkingSupported) {
                        val expectedParking = if (closing) confirmedParking?.copy(enabled = false)
                            else parking ?: confirmedParking
                        requireMessage(ready.unifiedParkingSupported || !standstillPending || actualParking?.enabled == false,
                            "停车等待时电子驻车必须保持关闭")
                        val safeParkingRelease = standstillPending &&
                            actualParking == expectedParking?.copy(enabled = false)
                        if (parkingResult != null) {
                            requireMessage(actualParking != null && expectedParking != null &&
                                actualParking.maxCurrentAmps == expectedParking.maxCurrentAmps,
                                "驻车操作不能更改最大输出电流")
                            val actual = requireNotNull(actualParking)
                            val previous = requireNotNull(expectedParking)
                            requireMessage(parkingResult.leftRaw == null || parkingResult.leftRaw / 100.0 == actual.leftCurrentAmps,
                                "左驻车电流回读不一致")
                            requireMessage(parkingResult.rightRaw == null || parkingResult.rightRaw / 100.0 == actual.rightCurrentAmps,
                                "右驻车电流回读不一致")
                            requireMessage(parkingResult.maxRaw == null || parkingResult.maxRaw / 100.0 == actual.maxCurrentAmps,
                                "最大输出电流回读不一致")
                            requireMessage(parkingRequest?.op == "currents" ||
                                actual.copy(enabled = previous.enabled) == previous,
                                "读取操作不能修改驻车电流")
                            requireMessage(parkingRequest?.op != "read" || actual == previous || safeParkingRelease,
                                "读取操作不能修改驻车模式")
                        } else requireMessage(actualParking == expectedParking || safeParkingRelease,
                                "开发板驻车回执与设置不一致")
                    }
                    token = nextToken
                    confirmedParking = actualParking
                    lastAckSequence = sentSequence
                    lastAckNanos = System.nanoTime()
                    val rtt = elapsedMillis(started)
                    val recoveryState = (if (recoveryRequired) 1 else 0) or (if (feedbackRecheck) 2 else 0) or
                        (if (standstillPending) 4 else 0) or (if (stopped) 8 else 0)
                    val recoveryActive = (recoveryState and 7) != 0
                    val recoveryChanged = (recoveryActive || (recoveryReportState and 7) != 0) &&
                        recoveryState != recoveryReportState
                    if (recoveryChanged || (recoveryActive || rtt >= 900L) &&
                        elapsedMillis(recoveryReportNanos) >= 1_000L) {
                        val event = when {
                            standstillPending || (recoveryReportState and 4) != 0 -> "drive.standstill_wait"
                            feedbackRecheck || (recoveryReportState and 2) != 0 -> "drive.feedback_recheck"
                            else -> "drive.network_recovery"
                        }
                        Diagnostics.event(event, diagnosticContext,
                            fields = mapOf("seq" to sentSequence, "elapsedMs" to rtt,
                                "recoveryRequired" to recoveryRequired, "feedbackRecheck" to feedbackRecheck,
                                "standstillPending" to standstillPending,
                                "severity" to if (standstillPending) "warning" else "info",
                                "stopped" to stopped, "feedbackLeftRpm" to measured.feedback.leftRpm,
                                "feedbackRightRpm" to measured.feedback.rightRpm,
                                "networkGraceMs" to ready.networkGraceMs), critical = recoveryChanged)
                        recoveryReportNanos = System.nanoTime()
                    }
                    recoveryReportState = recoveryState
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
                            "feedbackRecheck" to feedbackRecheck,
                            "standstillPending" to standstillPending,
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
                    if (communicationDelayed) Diagnostics.event("drive.ack_delay_recovered", diagnosticContext,
                        fields = diagnosticFields() + mapOf("elapsedMs" to elapsedMillis(started),
                            "neutralRequired" to true), critical = true)
                    DriveAck(actualLeft, actualRight, stopped, actualParking, interlock, recoveryRequired,
                        measured.targetStopped, measured.feedback, lastAckNanos, measured.feedback.ageMs + rtt,
                        feedbackRecheck, standstillPending, parkingResult, rawMode as? Boolean, modeSource as? String,
                        stopCommandConfirmed, communicationDelayed)
                }
            } catch (timeout: TimeoutCancellationException) {
                transaction.interruptedStopSource()?.let { throw activeStopCancellation(it) }
                val retainTransport = phase == "ack_wait" && !blockingIo.get()
                val failure = DriveAckTimeoutException(if (retainTransport)
                    "控制回执持续未恢复，已结束本次控制通道；SSH 保留，确认控制空闲后可重新启用"
                    else "控制写入超时，连接已关闭，请重新连接", retainTransport)
                Diagnostics.failure(diagnosticContext, if (retainTransport) "drive.ack_timeout" else "drive.write_timeout", failure,
                    diagnosticFields() + mapOf("elapsedMs" to elapsedMillis(started),
                        "leftRpm" to left, "rightRpm" to right,
                        "transportRetained" to retainTransport))
                // Only an uncancellable/partial write needs to tear down the socket.
                // A read deadline ends this token stream, while SSH can be probed separately.
                close(if (retainTransport) "ack_timeout" else "write_timeout", invalidateTransport = !retainTransport)
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
                val result = parkingReplyBeforeFailure
                if (result != null && !result.ok && failure !is CancellationException)
                    throw ParkingResultException(result, safeFailure)
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
        "writeTimeoutMs" to DRIVE_WRITE_TIMEOUT_MS,
        "ackTimeoutMs" to if (currentCommand == "parking_settings") UNIFIED_PARKING_ACK_TIMEOUT_MS
            else if (currentCommand in setOf("parking", "close", "estop")) PARKING_ACK_TIMEOUT_MS
            else if (::ready.isInitialized) driveAckTimeoutMillis(ready.networkGraceMs) else null,
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
            "parkingRightCurrentAmps", "parkingMaxCurrentAmps").associateWith(message::opt),
            commandModeOnly = ::ready.isInitialized && ready.unifiedParkingSupported) }
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
        private const val UNIFIED_PARKING_ACK_TIMEOUT_MS = 4_000L
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
                        val rawStopWarning = reply.opt("stopConfirmationWarningSupported")
                        control.requireMessage(!reply.has("stopConfirmationWarningSupported") || rawStopWarning is Boolean,
                            "开发板停车告警能力字段无效")
                        val stopWarningSupported = rawStopWarning == true
                        control.requireMessage(!reply.has("stopCommandConfirmed") ||
                            reply.opt("stopCommandConfirmed") is Boolean, "开发板初始停止指令确认字段无效")
                        control.requireMessage(!stopWarningSupported || reply.opt("stopCommandConfirmed") == true,
                            "开发板初始停止指令未确认")
                        control.requireMessage(initialFeedback.stopped || stopWarningSupported &&
                            reply.opt("stopCommandConfirmed") == true, "开发板初始停止指令未确认")
                        val rawNoiseMax = reply.opt("feedbackNoiseMaxRpm")
                        control.requireMessage(!reply.has("feedbackNoiseMaxRpm") || rawNoiseMax is Number &&
                            rawNoiseMax.toDouble() == rawNoiseMax.toInt().toDouble() && rawNoiseMax.toInt() in 1..80,
                            "开发板轮速告警范围无效")
                        val rawInfraredSupported = reply.opt("infraredSupported")
                        control.requireMessage(!reply.has("infraredSupported") || rawInfraredSupported is Boolean,
                            "开发板红外保护能力字段无效")
                        val rawUnifiedParking = reply.opt("unifiedParkingSupported")
                        control.requireMessage(!reply.has("unifiedParkingSupported") || rawUnifiedParking is Boolean,
                            "开发板统一驻车能力字段无效")
                        val rawParkingSemantics = reply.opt("parkingSemantics")
                        control.requireMessage(!reply.has("parkingSemantics") ||
                            rawParkingSemantics == PARKING_CURRENT_SEMANTICS && rawUnifiedParking == true,
                            "开发板驻车电流协议未匹配")
                        if (rawParkingSemantics == PARKING_CURRENT_SEMANTICS) {
                            val rawMode = reply.opt("parkingMode")
                            control.requireMessage(reply.has("parkingMode") && (rawMode == JSONObject.NULL || rawMode is Boolean),
                                "开发板初始驻车电流状态无效")
                            control.readSafety { validateParkingCurrentReadback(rawMode as? Boolean,
                                reply.opt("parkingModeSource") as? String, parking) }
                        }
                        val rawRunId = reply.opt("runId")
                        val rawGrace = reply.opt("networkGraceMs")
                        control.requireMessage(!reply.has("networkGraceMs") ||
                            rawGrace is Number && rawGrace.toDouble() in setOf(5_000.0, 15_000.0),
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
                            unifiedParkingSupported = rawUnifiedParking == true,
                            parkingSemantics = rawParkingSemantics as? String,
                            stopConfirmationWarningSupported = stopWarningSupported,
                            feedbackNoiseMaxRpm = (rawNoiseMax as? Number)?.toInt() ?: 2,
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
