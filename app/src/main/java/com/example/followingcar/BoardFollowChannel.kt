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

/** One SSH-owned follow run. Only the owning loop sends requests and 500 ms heartbeats. */
class BoardFollowChannel private constructor(
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
    private var startSent = false
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

    /** EOF asks the supervisor to stop; a lost network also expires the board-side lease. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (blockingIo.get()) abortTransport()
        cleanup.execute {
            runCatching { channel.disconnect() }
            runCatching { input.close() }
        }
    }

    private suspend fun transact(command: String): FollowReply = withContext(Dispatchers.IO) {
        check(transaction.tryLock()) { "上一个跟随指令尚未收到回执" }
        try {
            check(isOpen) { "跟随通道已关闭，运行状态未确认" }
            val result = withTimeout(ACK_TIMEOUT_MS) {
                currentCoroutineContext().ensureActive()
                sequence += 1
                val sentSequence = sequence
                val previousToken = token
                val request = JSONObject().put("seq", sentSequence).put("token", previousToken)
                    .put("runId", ready.runId).put("cmd", command)
                blockingCall {
                    output.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                }
                val reply = readMessage()
                requireMessage(reply.opt("type") == "ack", "开发板返回了未知跟随响应")
                val replySequence = reply.opt("seq")
                requireMessage((replySequence is Int || replySequence is Long) &&
                    (replySequence as Number).toLong() == sentSequence, "跟随回执序号不匹配")
                requireMessage(reply.opt("runId") == ready.runId, "跟随回执不属于本次会话")
                val nextToken = readToken(reply)
                requireMessage(nextToken != previousToken, "跟随控制令牌未更新")
                val terminal = reply.opt("terminal")
                requireMessage(terminal is Boolean, "跟随回执缺少结束状态")
                val status = parseFollowStatus(reply.getJSONObject("status"))
                requireMessage(status.execute == ready.execute && status.camera == ready.camera,
                    "跟随回执模式或相机不匹配")
                if (terminal == true) {
                    requireMessage(status.processExited && status.runState in setOf("STOPPED", "FAULT"),
                        "跟随进程结束回执不一致")
                }
                token = nextToken
                FollowReply(status, terminal as Boolean)
            }
            if (result.terminal) close()
            result
        } catch (timeout: TimeoutCancellationException) {
            close()
            throw SocketTimeoutException("跟随回执超时；板端将请求停止，车辆状态未确认")
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
            if (closed.get()) throw IOException("跟随通道已关闭，运行状态未确认")
            while (input.available() > 0) {
                val value = input.read()
                if (value < 0) throw endedError()
                if (value == '\n'.code) {
                    val message = try {
                        JSONObject(bytes.toString(Charsets.UTF_8.name()))
                    } catch (failure: Exception) {
                        throw IOException("开发板跟随响应不是有效 JSON", failure)
                    }
                    if (message.opt("type") == "error") {
                        throw IOException(message.optString("error", "开发板跟随程序报错").take(600))
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
        val detail = stderr.text().trim().take(600)
        return IOException(if (detail.isNotBlank()) "跟随控制程序退出：$detail"
            else "跟随控制通道已结束，未确认车辆停止；请检查控制占用")
    }

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
        return value as String
    }

    private fun requireMessage(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
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
            abortTransport: () -> Unit,
        ): BoardFollowChannel = withContext(Dispatchers.IO) {
            options.validate(camera)
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
            val control = BoardFollowChannel(channel, channel.inputStream, channel.outputStream, stderr, abortTransport)
            try {
                withTimeout(START_TIMEOUT_MS) {
                    control.blockingCall { channel.connect(START_TIMEOUT_MS.toInt()) }
                    val reply = control.readMessage()
                    control.requireMessage(reply.opt("type") == "ready", "开发板跟随服务未就绪")
                    control.token = control.readToken(reply)
                    val runId = reply.opt("runId")
                    control.requireMessage(runId is String && runId.isNotBlank() && runId.length <= 128,
                        "跟随会话标识无效")
                    control.requireMessage(reply.opt("dryRun") == dryRun && reply.opt("execute") == execute &&
                        reply.opt("camera") == camera.argument, "开发板跟随模式或相机不匹配")
                    control.requireMessage(reply.optJSONObject("options")?.matches(options) == true,
                        "开发板跟随参数与 App 选择不一致，请更新车端桥接脚本")
                    control.requireMessage(reply.opt("heartbeatMs") == 500 && reply.opt("watchdogMs") == 2000,
                        "开发板跟随心跳参数不匹配")
                    control.ready = FollowReady(runId as String, dryRun, execute, camera)
                    control
                }
            } catch (timeout: TimeoutCancellationException) {
                control.close()
                throw SocketTimeoutException("跟随服务连接超时，请检查开发板脚本")
            } catch (failure: Exception) {
                control.close()
                throw failure
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
): String {
    options.validate(camera)
    if (options != FollowLaunchOptions()) {
        return "python3 -u $FOLLOW_PROJECT_ROOT/app_follow_bridge.py --camera ${camera.argument}" +
            followLaunchArguments(options) + " --check-config"
    }
    return "cd $FOLLOW_PROJECT_ROOT && exec ./run_request_0428_modular.sh --chain continuous " +
        "--config car_control_modular/config/reid_runtime.ini --camera ${camera.argument} " +
        "--settings continuous_follow/baseline.ini --no-gestures --avoidance off --no-reverse-follow --check-config"
}
