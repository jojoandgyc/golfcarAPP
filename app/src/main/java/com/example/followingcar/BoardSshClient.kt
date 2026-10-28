package com.example.followingcar

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SocketFactory
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
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

    val isConnected: Boolean
        get() = synchronized(stateLock) {
            connection?.let { it.ready && it.session.isConnected && !it.socket.isClosed } == true
        }

    suspend fun connect(config: BoardConnection) = withContext(Dispatchers.IO) {
        operations.withLock {
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
            previous?.close()

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
                        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                        return socket
                    }

                    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
                    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
                })
            }
            val candidate = Connection(session, socket)
            try {
                currentCoroutineContext().ensureActive()
                synchronized(stateLock) {
                    if (generation != attempt) throw CancellationException("连接已取消")
                    connection = candidate
                }
                // Cancellation closes the socket even while JSch is blocked in connect/authentication.
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { closeConnection(candidate) }
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
            } catch (error: Exception) {
                closeConnection(candidate)
                throw error
            }
        }
    }

    suspend fun execute(command: String, timeoutMillis: Long = 15_000L): CommandResult =
        withContext(Dispatchers.IO) {
            operations.withLock {
                require(command.isNotBlank()) { "请输入要执行的命令" }
                require(timeoutMillis in 1..300_000) { "命令超时必须在 1 毫秒到 5 分钟之间" }
                val active = synchronized(stateLock) {
                    connection?.takeIf { it.ready && it.session.isConnected }
                } ?: throw IOException("尚未连接开发板，请先连接")
                val stdout = BoundedOutputStream(OUTPUT_LIMIT_PER_STREAM)
                val stderr = BoundedOutputStream(OUTPUT_LIMIT_PER_STREAM)
                var channel: ChannelExec? = null
                val started = System.nanoTime()
                try {
                    currentCoroutineContext().ensureActive()
                    channel = (active.session.openChannel("exec") as ChannelExec).apply {
                        setCommand(command)
                        setInputStream(null)
                        setPty(false)
                        setAgentForwarding(false)
                        // JSch drains both streams on its reader thread. The bounded sinks keep
                        // accepting (and discarding) excess bytes, so verbose commands cannot stall.
                        setOutputStream(stdout, true)
                        setErrStream(stderr, true)
                    }
                    val execChannel = channel
                    suspendCancellableCoroutine<Unit> { continuation ->
                        continuation.invokeOnCancellation { closeConnection(active) }
                        try {
                            if (continuation.isActive) {
                                execChannel.connect(minOf(timeoutMillis, CHANNEL_TIMEOUT_MS).toInt())
                                continuation.resume(Unit)
                            }
                        } catch (error: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(error)
                        }
                    }
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
                    CommandResult(
                        command = command,
                        stdout = stdout.text(),
                        stderr = stderr.text(),
                        exitCode = execChannel.exitStatus,
                        truncated = stdout.truncated || stderr.truncated,
                    )
                } catch (error: Exception) {
                    closeConnection(active)
                    throw error
                } finally {
                    channel?.disconnect()
                }
            }
        }

    /** Opens the board's persistent control bridge on the already authenticated SSH session. */
    suspend fun openDriveChannel(dryRun: Boolean = false): BoardDriveChannel =
        withContext(Dispatchers.IO) {
            operations.withLock {
                val active = synchronized(stateLock) {
                    connection?.takeIf { it.ready && it.session.isConnected && !it.socket.isClosed }
                } ?: throw IOException("尚未连接开发板，请先连接")
                BoardDriveChannel.open(active.session, dryRun) {
                    // A timed-out write cannot be safely retried. Closing the socket also
                    // unblocks JSch without waiting for its channel/write monitor.
                    runCatching { active.socket.close() }
                }
            }
        }

    /** Uses the active joystick session so parking settings share its serial ownership and ACKs. */
    suspend fun configureParking(channel: BoardDriveChannel, settings: ParkingSettings): DriveAck {
        check(isConnected) { "尚未连接开发板，请先连接" }
        return channel.configureParking(settings)
    }

    /**
     * Static configuration validation only: this command never opens the camera or motors.
     * Camera fields remain the board's original values. The configuration page adds their
     * Chinese notes according to the active capture backend, without treating legacy fields
     * as settings used by the current continuous-follow runtime.
     */
    suspend fun readFollowConfiguration(
        camera: FollowCamera, options: FollowLaunchOptions = FollowLaunchOptions(),
    ): FollowConfiguration {
        val result = execute(followConfigurationCommand(camera, options), timeoutMillis = 30_000L)
        requireFollowResult(result, "跟随配置检查失败")
        return parseFollowConfiguration(result.stdout, camera)
    }

    /** Checks ownership after a lost session without opening devices or restarting a run. */
    suspend fun readFollowAvailability(): FollowAvailability {
        val result = execute("python3 -u $FOLLOW_PROJECT_ROOT/app_follow_bridge.py --inspect")
        requireFollowResult(result, "无法检查跟随控制占用")
        return parseFollowAvailability(result.stdout)
    }

    suspend fun openFollowChannel(
        camera: FollowCamera, execute: Boolean, dryRun: Boolean = false,
        options: FollowLaunchOptions = FollowLaunchOptions(),
    ): BoardFollowChannel = withContext(Dispatchers.IO) {
        operations.withLock {
            val active = synchronized(stateLock) {
                connection?.takeIf { it.ready && it.session.isConnected && !it.socket.isClosed }
            } ?: throw IOException("尚未连接开发板，请先连接")
            BoardFollowChannel.open(active.session, camera, execute, dryRun, options) {
                runCatching { active.socket.close() }
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
    fun disconnect() {
        val old = synchronized(stateLock) {
            generation += 1
            connection.also { connection = null }
        }
        old?.close()
    }

    private fun closeConnection(target: Connection) {
        synchronized(stateLock) {
            if (connection === target) connection = null
        }
        target.close()
    }

    private class Connection(val session: Session, val socket: Socket) {
        var ready = false // Accessed only under stateLock.

        fun close() {
            // Closing our socket first also interrupts a connection not yet registered by JSch.
            runCatching { socket.close() }
            runCatching { session.disconnect() }
        }
    }

    private companion object {
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
