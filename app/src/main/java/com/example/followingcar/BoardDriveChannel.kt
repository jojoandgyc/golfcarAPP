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

data class DriveReady(
    val port: String, val dryRun: Boolean, val watchdogMs: Int, val maxRpm: Int,
    val parkingSupported: Boolean = false, val parking: ParkingSettings? = null,
)
data class DriveAck(
    val left: Int, val right: Int, val stopped: Boolean,
    val parking: ParkingSettings? = null,
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
    private val abortTransport: () -> Unit,
) {
    private val transaction = Mutex()
    private val closed = AtomicBoolean(false)
    private val blockingIo = AtomicBoolean(false)
    private var token = ""
    private var sequence = 0L
    private var confirmedParking: ParkingSettings? = null
    lateinit var ready: DriveReady
        private set

    val isOpen: Boolean
        get() = !closed.get() && channel.isConnected && !channel.isClosed

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
        if (emergency) close()
        return result
    }

    suspend fun stopAndClose() {
        try {
            check(isOpen) { "摇杆通道已关闭，未收到本次停车回执" }
            transact("close", 0, 0)
        } finally {
            close()
        }
    }

    /** Serialized by the same caller as the heartbeat; the board also requires a stopped target. */
    suspend fun configureParking(settings: ParkingSettings): DriveAck {
        check(ready.parkingSupported) { "开发板脚本不支持驻车设置，请更新 app_joystick_bridge.py" }
        return transact("parking", 0, 0, settings)
    }

    /** Nonblocking for UI/lifecycle callers. EOF or the board watchdog stops the motors. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Explicitly interrupt a network write if one is in flight. A partially sent control
        // command must not be retried on this transport.
        if (blockingIo.get()) abortTransport()
        cleanup.execute {
            runCatching { channel.disconnect() }
            runCatching { input.close() }
        }
    }

    private suspend fun transact(
        command: String, left: Int, right: Int, parking: ParkingSettings? = null,
    ): DriveAck =
        withContext(Dispatchers.IO) {
            // Refuse concurrency instead of keeping stale movement requests in a queue.
            check(transaction.tryLock()) { "上一个摇杆指令尚未收到回执" }
            try {
                check(isOpen) { "摇杆控制通道已关闭，请重新启用" }
                val closing = command == "estop" || command == "close"
                withTimeout(if (parking != null || closing) PARKING_ACK_TIMEOUT_MS else ACK_TIMEOUT_MS) {
                    sequence += 1
                    val sentSequence = sequence
                    val previousToken = token
                    val request = JSONObject().put("seq", sentSequence).put("token", previousToken)
                        .put("cmd", command).put("left", left).put("right", right)
                    if (parking != null) {
                        request.put("parkingEnabled", parking.enabled)
                            .put("parkingCurrent", parking.currentAmps)
                    }
                    blockingCall {
                        output.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    val reply = readMessage()
                    requireMessage(reply.getString("type") == "ack", "开发板返回了未知控制响应")
                    requireMessage(reply.getLong("seq") == sentSequence, "开发板回执序号不匹配")
                    val nextToken = readToken(reply)
                    requireMessage(nextToken != previousToken, "开发板控制令牌未更新")
                    val actualLeft = reply.getInt("left")
                    val actualRight = reply.getInt("right")
                    val stopped = reply.getBoolean("stopped")
                    requireMessage(actualLeft == left && actualRight == right, "开发板回执速度与指令不一致")
                    requireMessage(stopped == (actualLeft == 0 && actualRight == 0), "开发板停车回执不一致")
                    val actualParking = if (ready.parkingSupported) readParking(reply) else null
                    if (ready.parkingSupported) {
                        val expectedParking = if (closing) confirmedParking?.copy(enabled = false)
                            else parking ?: confirmedParking
                        requireMessage(actualParking == expectedParking, "开发板驻车回执与设置不一致")
                    }
                    token = nextToken
                    confirmedParking = actualParking
                    DriveAck(actualLeft, actualRight, stopped, actualParking)
                }
            } catch (timeout: TimeoutCancellationException) {
                close()
                throw SocketTimeoutException("摇杆控制回执超时，已关闭控制通道；请重新启用摇杆")
            } catch (failure: Exception) {
                close()
                throw failure
            } finally {
                transaction.unlock()
            }
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
                        throw IOException("开发板控制响应不是有效 JSON", failure)
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
        val detail = stderr.text().trim().take(500)
        return IOException(
            if (detail.isNotBlank()) "摇杆控制程序退出：$detail"
            else "摇杆控制通道已结束，可能触发通信超时停车，请重新启用"
        )
    }

    /** JSch writes are blocking; a timeout/cancellation must close the real socket too. */
    private suspend fun blockingCall(action: () -> Unit) {
        suspendCancellableCoroutine<Unit> { continuation ->
            continuation.invokeOnCancellation {
                abortTransport()
                close()
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
        return value
    }

    private fun requireMessage(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }

    private fun readParking(message: JSONObject): ParkingSettings {
        val enabled = message.opt("parkingEnabled")
        val current = message.opt("parkingCurrent")
        requireMessage(enabled is Boolean && current is Number &&
            current.toDouble() in 0.0..60.0 && current.toDouble() == current.toInt().toDouble(),
            "开发板驻车参数无效")
        val amps = (current as Number).toInt()
        requireMessage(enabled == false || amps > 0, "开发板驻车开关与电流不一致")
        return ParkingSettings(enabled as Boolean, amps)
    }

    internal companion object {
        private const val ACK_TIMEOUT_MS = 900L
        // Configuration and terminal cleanup include register writes/readback after stopping.
        private const val PARKING_ACK_TIMEOUT_MS = 2_500L
        private const val START_TIMEOUT_MS = 5_000L
        private const val MAX_LINE_BYTES = 4096
        private val cleanup = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "drive-channel-cleanup").apply { isDaemon = true }
        }

        suspend fun open(session: Session, dryRun: Boolean, abortTransport: () -> Unit): BoardDriveChannel =
            withContext(Dispatchers.IO) {
                val channel = (session.openChannel("exec") as ChannelExec).apply {
                    setCommand("python3 -u /home/topeet/lianzhan/scripts/app_joystick_bridge.py" + if (dryRun) " --dry-run" else "")
                    setPty(false)
                    setAgentForwarding(false)
                    setInputStream(null)
                }
                val stderr = BoundedOutputStream(2048)
                channel.setErrStream(stderr, true)
                val control = BoardDriveChannel(channel, channel.inputStream, channel.outputStream, stderr, abortTransport)
                try {
                    withTimeout(START_TIMEOUT_MS) {
                        control.blockingCall { channel.connect(START_TIMEOUT_MS.toInt()) }
                        val reply = control.readMessage()
                        control.requireMessage(reply.getString("type") == "ready", "开发板未就绪")
                        control.token = control.readToken(reply)
                        val parkingSupported = reply.optBoolean("parkingSupported", false)
                        val parking = if (parkingSupported) control.readParking(reply) else null
                        val ready = DriveReady(
                            port = reply.getString("port"), dryRun = reply.getBoolean("dryRun"),
                            watchdogMs = reply.getInt("watchdogMs"), maxRpm = reply.getInt("maxRpm"),
                            parkingSupported = parkingSupported, parking = parking,
                        )
                        control.requireMessage(ready.dryRun == dryRun, "开发板控制模式不匹配")
                        control.requireMessage(ready.maxRpm in 1..100 && ready.watchdogMs == 600, "开发板限速或超时参数不匹配")
                        control.ready = ready
                        control.confirmedParking = parking
                        control
                    }
                } catch (timeout: TimeoutCancellationException) {
                    control.close()
                    throw SocketTimeoutException("启动摇杆控制超时，请检查串口、电机电源和控制脚本")
                } catch (failure: Exception) {
                    control.close()
                    throw failure
                }
            }
    }
}
