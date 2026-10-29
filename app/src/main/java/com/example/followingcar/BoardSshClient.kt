package com.example.followingcar

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class BoardConnection(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val tls: RemoteTlsConfig? = null,
) {
    // Avoid accidentally including credentials in logs or exception messages.
    override fun toString(): String = "BoardConnection(host=$host, port=$port, username=$username)"
}

data class CommandResult(
    val command: String,
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val truncated: Boolean = false,
)

/** A single authenticated connection to the user's pinned development board. */
class BoardSshClient {
    private val operations = Mutex()
    private val stateLock = Any()
    private var generation = 0L
    private var connection: Connection? = null

    val diagnosticConnectionId: String?
        get() = synchronized(stateLock) { connection?.diagnostic?.connectionId }

    val isConnected: Boolean
        get() = synchronized(stateLock) {
            connection?.let { it.ready && it.session.isConnected && !it.socket.isClosed } == true
        }

    suspend fun connect(
        config: BoardConnection,
        diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
    ) = withContext(Dispatchers.IO) {
        operations.withLock {
            Diagnostics.protectSecret(config.password)
            require(config.host.isNotBlank()) { "请输入开发板地址" }
            require(config.port in 1..65535) { "端口必须在 1 到 65535 之间" }
            require(config.username.isNotBlank()) { "请输入用户名" }
            require(config.password.isNotEmpty()) { "请输入密码" }
            currentCoroutineContext().ensureActive()

            val (attempt, previous) = synchronized(stateLock) {
                generation += 1
                val old = connection
                connection = null
                generation to old
            }
            previous?.let {
                Diagnostics.event("ssh.transport_close", it.diagnostic, source = "replacement", critical = true)
                it.close()
            }

            val context = diagnostic.copy(connectionId = UUID.randomUUID().toString())
            val started = System.nanoTime()
            Diagnostics.event("ssh.connect_started", context,
                fields = mapOf("host" to config.host, "port" to config.port,
                    "generation" to attempt, "timeoutMs" to CONNECT_TIMEOUT_MS,
                    "transport" to if (config.tls == null) "local" else "remote_mtls"), critical = true)

            val socket = Socket()
            val jsch = JSch().apply {
                setKnownHosts(PINNED_HOST_KEY.byteInputStream(Charsets.US_ASCII))
            }
            val session = jsch.getSession(config.username.trim(), config.host.trim(), config.port).apply {
                setPassword(config.password)
                setHostKeyAlias(HOST_KEY_ALIAS)
                setConfig("StrictHostKeyChecking", "yes")
                setConfig("PreferredAuthentications", "password")
                // These algorithms are provided by Android's JCA without extra providers.
                setConfig("server_host_key", "ecdsa-sha2-nistp256")
                setConfig("kex", "ecdh-sha2-nistp256")
                setServerAliveInterval(5_000)
                setServerAliveCountMax(2)
                setDaemonThread(true)
                setSocketFactory(object : SocketFactory {
                    override fun createSocket(host: String, port: Int): Socket {
                        return openBoardSocket(socket, host, port, CONNECT_TIMEOUT_MS, config.tls)
                    }

                    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
                })
            }
            val candidate = Connection(session, socket, context)
            try {
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (generation != attempt) throw CancellationException("连接已取消")
                    connection = candidate
                }
                // Cancellation closes the socket even while JSch is blocked in connect/authentication.
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { closeConnection(candidate, "connect_cancelled", context) }
                    try {
                        if (continuation.isActive) {
                            session.connect(CONNECT_TIMEOUT_MS)
                            continuation.resume(Unit)
                        }
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (connection !== candidate || !session.isConnected) {
                        throw CancellationException("连接已取消")
                    }
                    candidate.ready = true
                }
                Diagnostics.event("ssh.connected", context,
                    fields = mapOf("elapsedMs" to elapsedMillis(started), "generation" to attempt), critical = true)
            } catch (error: Exception) {
                if (error is CancellationException) {
                    Diagnostics.event("ssh.connect_cancelled", context, critical = true)
                } else {
                    Diagnostics.failure(context, "ssh.connect_failed", error,
                        mapOf("phase" to "connect_and_authenticate", "elapsedMs" to elapsedMillis(started)))
                }
                closeConnection(candidate, "connect_failed", context)
                throw error
            }
        }
    }

    suspend fun execute(
        command: String, timeoutMillis: Long = 15_000L, kind: String = "terminal",
        diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
        input: ByteArray? = null,
    ): CommandResult =
        withContext(Dispatchers.IO) {
            operations.withLock {
                require(command.isNotBlank()) { "请输入要执行的命令" }
                require(timeoutMillis in 1..300_000) { "命令超时必须在 1 毫秒到 5 分钟之间" }
                require(input == null || input.size <= 16 * 1024) { "请求内容过长" }
                val active = synchronized(stateLock) {
                    connection?.takeIf { it.ready && it.session.isConnected && !it.socket.isClosed }
                } ?: throw IOException("尚未连接开发板，请先连接")
                val stdout = BoundedOutputStream(OUTPUT_LIMIT_PER_STREAM)
                val stderr = BoundedOutputStream(OUTPUT_LIMIT_PER_STREAM)
                var channel: ChannelExec? = null
                val started = System.nanoTime()
                val context = diagnostic.copy(connectionId = active.diagnostic.connectionId)
                val category = safeDiagnosticCommandKind(kind)
                var phase = "channel_open"
                Diagnostics.event("ssh.exec_started", context, fields = mapOf(
                    "kind" to category, "commandLength" to command.length, "timeoutMs" to timeoutMillis), critical = true)
                try {
                    currentCoroutineContext().ensureActive()
                    channel = (active.session.openChannel("exec") as ChannelExec).apply {
                        setCommand(command)
                        // Structured configuration requests go through stdin, never shell interpolation.
                        // No input body is included in diagnostics or the terminal result.
                        setInputStream(input?.inputStream())
                        setPty(false)
                        setAgentForwarding(false)
                        // JSch drains both streams on its reader thread. The bounded sinks keep
                        // accepting (and discarding) excess bytes, so verbose commands cannot stall.
                        setOutputStream(stdout, true)
                        setErrStream(stderr, true)
                    }
                    val execChannel = channel
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { closeConnection(active, "exec_open_cancelled", context) }
                        try {
                            if (continuation.isActive) {
                                execChannel.connect(minOf(timeoutMillis, CHANNEL_TIMEOUT_MS).toInt())
                                continuation.resume(Unit)
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
                    phase = "command_wait"
                    Diagnostics.event("ssh.channel_opened", context,
                        fields = mapOf("kind" to category, "elapsedMs" to elapsedMillis(started)))
                    while (!execChannel.isClosed) {
                        currentCoroutineContext().ensureActive()
                        if (!active.session.isConnected || active.socket.isClosed) {
                            throw IOException("与开发板的连接已断开")
                        }
                        if ((System.nanoTime() - started) / 1_000_000 >= timeoutMillis) {
                            throw SocketTimeoutException(
                                "命令执行超时，连接已断开；远端进程可能仍在运行。",
                            )
                        }
                        delay(25)
                    }
                    currentCoroutineContext().ensureActive()
                    if (execChannel.exitStatus < 0) {
                        throw IOException("命令通道已关闭，但未收到退出状态，请重新连接")
                    }
                    Diagnostics.event("ssh.exec_completed", context, fields = mapOf(
                        "kind" to category, "exitCode" to execChannel.exitStatus,
                        "elapsedMs" to elapsedMillis(started), "stdoutBytes" to stdout.retainedBytes,
                        "stderrBytes" to stderr.retainedBytes,
                        "truncated" to (stdout.truncated || stderr.truncated)), critical = true)
                    CommandResult(
                        command = command,
                        stdout = stdout.text(),
                        stderr = stderr.text(),
                        exitCode = execChannel.exitStatus,
                        truncated = stdout.truncated || stderr.truncated,
                    )
                } catch (error: Exception) {
                    val fields = mapOf("kind" to category, "phase" to phase,
                        "elapsedMs" to elapsedMillis(started), "sessionConnected" to active.session.isConnected,
                        "channelConnected" to channel?.isConnected, "socketClosed" to active.socket.isClosed,
                        "originalExceptionClass" to error.javaClass.name)
                    if (error is CancellationException) {
                        Diagnostics.event("ssh.exec_cancelled", context, fields = fields, critical = true)
                    } else {
                        Diagnostics.failure(context, "ssh.exec_failed",
                            if (category == "terminal") terminalDiagnosticFailure(error) else error, fields)
                    }
                    closeConnection(active, "exec_failed", context)
                    throw error
                } finally {
                    val completedChannel = channel
                    cleanup.execute {
                        runCatching { completedChannel?.disconnect() }
                        Diagnostics.event("ssh.channel_closed", context,
                            fields = mapOf("kind" to category, "phase" to phase), critical = true)
                    }
                }
            }
        }

    /** Opens the board's persistent control bridge on the already authenticated SSH session. */
    suspend fun openDriveChannel(
        dryRun: Boolean = false,
        diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
    ): BoardDriveChannel =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val active = synchronized(stateLock) {
                    connection?.takeIf { it.ready && it.session.isConnected && !it.socket.isClosed }
                } ?: throw IOException("尚未连接开发板，请先连接")
                val context = diagnostic.copy(connectionId = active.diagnostic.connectionId)
                BoardDriveChannel.open(active.session, dryRun, context) { reason, failureContext ->
                    // A timed-out write cannot be safely retried. Closing the socket also
                    // unblocks JSch without waiting for its channel/write monitor.
                    invalidateTransport(active, reason, failureContext)
                }
            }
        }

    /** Uses the active joystick session so parking settings share its serial ownership and ACKs. */
    suspend fun configureParking(
        channel: BoardDriveChannel, settings: ParkingSettings,
        diagnostic: DiagnosticContext = channel.diagnosticContext,
    ): DriveAck {
        check(isConnected) { "尚未连接开发板，请先连接" }
        Diagnostics.event("parking.request", diagnostic,
            fields = mapOf("enabled" to settings.enabled, "currentAmps" to settings.currentAmps), critical = true)
        return channel.configureParking(settings)
    }

    /** Read-only: inspect both streaming services and live RGB/depth health without starting them. */
    suspend fun readVideoServiceState(
        diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
    ): VideoServiceState =
        parseVideoServiceState(execute(videoServiceCommand(), timeoutMillis = 15_000L,
            kind = "video_inspect", diagnostic = diagnostic))

    /** Uses the board's videoctl.sh; success includes readiness or confirmed camera release. */
    suspend fun setVideoServiceEnabled(
        enabled: Boolean, diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
    ): VideoServiceState =
        requireVideoServiceState(
            execute(videoServiceCommand(enabled), timeoutMillis = if (enabled) 60_000L else 45_000L,
                kind = if (enabled) "video_start" else "video_stop", diagnostic = diagnostic),
            enabled,
        )

    /**
     * Static configuration validation only: this command never opens the camera or motors.
     * Camera fields remain the board's original values. The configuration page adds their
     * Chinese notes according to the active capture backend, without treating legacy fields
     * as settings used by the current continuous-follow runtime.
     */
    suspend fun readFollowConfiguration(
        camera: FollowCamera, options: FollowLaunchOptions = FollowLaunchOptions(),
        diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
        execute: Boolean = false,
    ): FollowConfiguration {
        val result = execute(followConfigurationCommand(camera, options, execute), timeoutMillis = 30_000L,
            kind = "follow_config", diagnostic = diagnostic)
        requireFollowResult(result, "跟随配置检查失败")
        return parseFollowConfiguration(result.stdout, camera, expectedExecute = execute)
    }

    /** Checks ownership after a lost session without opening devices or restarting a run. */
    suspend fun readFollowAvailability(
        diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
    ): FollowAvailability {
        val result = execute("python3 -u $FOLLOW_PROJECT_ROOT/app_follow_bridge.py --inspect",
            kind = "follow_availability", diagnostic = diagnostic)
        requireFollowResult(result, "无法检查跟随控制占用")
        return parseFollowAvailability(result.stdout)
    }

    suspend fun openFollowChannel(
        camera: FollowCamera, execute: Boolean, dryRun: Boolean = false,
        options: FollowLaunchOptions = FollowLaunchOptions(),
        diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
    ): BoardFollowChannel = withContext(Dispatchers.IO) {
        operations.withLock {
            val active = synchronized(stateLock) {
                connection?.takeIf { it.ready && it.session.isConnected && !it.socket.isClosed }
            } ?: throw IOException("尚未连接开发板，请先连接")
            val context = diagnostic.copy(connectionId = active.diagnostic.connectionId)
            BoardFollowChannel.open(active.session, camera, execute, dryRun, options, context) { reason, failureContext ->
                invalidateTransport(active, reason, failureContext)
            }
        }
    }

    private fun requireFollowResult(result: CommandResult, message: String) {
        if (result.exitCode != 0) {
            val detail = result.stderr.ifBlank { result.stdout }.trim().take(600)
            throw IOException("$message（退出码 ${result.exitCode}）：$detail")
        }
        if (result.truncated) throw IOException("$message：返回内容不完整，请检查开发板输出")
    }

    /** Safe to call from another thread to interrupt connection or command execution. */
    fun disconnect(
        reason: String = "requested",
        diagnostic: DiagnosticContext = Diagnostics.newContext(diagnosticConnectionId),
    ) {
        val old = synchronized(stateLock) {
            generation += 1
            connection.also { connection = null }
        }
        old?.let {
            Diagnostics.event("ssh.transport_close", diagnostic.copy(connectionId = it.diagnostic.connectionId),
                source = reason, critical = true)
            it.close()
        }
    }

    private fun closeConnection(target: Connection, reason: String, diagnostic: DiagnosticContext) {
        synchronized(stateLock) {
            target.ready = false
            if (connection === target) connection = null
        }
        Diagnostics.event("ssh.transport_close", diagnostic.copy(connectionId = target.diagnostic.connectionId),
            source = reason, critical = true)
        target.close()
    }

    /** Socket close is immediate; never wait for JSch channel/write monitors on this path. */
    private fun invalidateTransport(target: Connection, reason: String, diagnostic: DiagnosticContext) {
        val detached = synchronized(stateLock) {
            target.ready = false
            if (connection === target) {
                connection = null
                true
            } else false
        }
        Diagnostics.event("ssh.transport_abort", diagnostic.copy(connectionId = target.diagnostic.connectionId),
            source = reason, fields = mapOf("closeScope" to "transport", "currentConnectionDetached" to detached),
            critical = true)
        // Always close only the captured transport. A newer connection may already exist.
        target.close()
    }

    private class Connection(val session: Session, val socket: Socket, val diagnostic: DiagnosticContext) {
        var ready = false // Accessed only under stateLock.
        private val closed = AtomicBoolean(false)

        fun close() {
            if (!closed.compareAndSet(false, true)) return
            // Closing our socket first also interrupts a connection not yet registered by JSch.
            runCatching { socket.close() }
            // JSch disconnect may acquire channel/write monitors; do not wait on a UI,
            // coroutine cancellation, or control deadline thread for that bookkeeping.
            cleanup.execute { runCatching { session.disconnect() } }
        }
    }

    private companion object {
        val cleanup = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ssh-connection-cleanup").apply { isDaemon = true }
        }
        const val CONNECT_TIMEOUT_MS = 10_000
        const val CHANNEL_TIMEOUT_MS = 5_000L
        const val OUTPUT_LIMIT_PER_STREAM = 32 * 1024
        const val HOST_KEY_ALIAS = "followingcar-board"

        // Public key read from this board over the authenticated connection on 2026-10-28.
        // Changing the board or reinstalling its SSH host keys requires updating this pin.
        const val PINNED_HOST_KEY = "$HOST_KEY_ALIAS ecdsa-sha2-nistp256 " +
            "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBCjVdRUTKIyiWjltab5Ufcfk34JvDFCf9j/U+v9CYSh7zqZbWW1KkEfRveCQoVAFA8ayab6rbgnH3/uf09iZLbE=\n"
    }
}

/** Always consumes incoming output, but retains only a bounded UTF-8 prefix for the UI. */
internal class BoundedOutputStream(private val limit: Int) : OutputStream() {
    private val bytes = ByteArrayOutputStream()

    @Volatile
    var truncated: Boolean = false
        private set

    val retainedBytes: Int
        @Synchronized get() = bytes.size()

    init {
        require(limit >= 0)
    }

    @Synchronized
    override fun write(value: Int) {
        if (bytes.size() < limit) bytes.write(value) else truncated = true
    }

    @Synchronized
    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= buffer.size - length)
        val accepted = minOf(length, limit - bytes.size())
        if (accepted > 0) bytes.write(buffer, offset, accepted)
        if (accepted < length) truncated = true
    }

    @Synchronized
    fun text(): String = bytes.toString(Charsets.UTF_8.name())
}

/** Never let caller-provided command labels or terminal exceptions leak command contents. */
internal fun safeDiagnosticCommandKind(kind: String): String = when (kind) {
    "board_status", "memory", "disk", "network", "video_inspect", "video_start", "video_stop",
    "follow_config", "follow_availability", "motor_config_read", "motor_config_write" -> kind
    else -> "terminal"
}

internal fun terminalDiagnosticFailure(error: Throwable): Throwable =
    IOException("终端操作异常（${error.javaClass.name}）；命令正文和输出不写入日志").apply {
        stackTrace = error.stackTrace
    }

/** Android JSON exception messages may contain the rejected field or complete input. */
internal fun protocolDiagnosticFailure(error: Exception): Exception =
    if (error is org.json.JSONException) IOException("开发板控制响应字段格式无效").apply {
        stackTrace = error.stackTrace
    } else error

internal fun elapsedMillis(started: Long): Long = (System.nanoTime() - started) / 1_000_000
