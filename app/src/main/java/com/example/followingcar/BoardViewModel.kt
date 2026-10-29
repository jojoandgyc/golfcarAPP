package com.example.followingcar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class BoardUiState(
    val host: String = "172.16.16.82",
    val port: String = "22",
    val username: String = "topeet",
    val password: String = "",
    val command: String = "whoami",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: String = "尚未连接",
    val error: String? = null,
    val result: CommandResult? = null,
    val driveStarting: Boolean = false,
    val driveEnabled: Boolean = false,
    val driveStopping: Boolean = false,
    val driveStatus: String = "手动控制未启用",
    val drivePort: String = "自动检测串口",
    val driveError: String? = null,
    val speedLimit: Int = 50,
    val driveLeft: Int = 0,
    val driveRight: Int = 0,
    val driveConfirmedStopped: Boolean = true,
    val parkingSupported: Boolean = false,
    val parkingSettings: ParkingSettings? = null,
    val parkingUpdating: Boolean = false,
    val parkingStatus: String = "启用手动控制并停车后可设置驻车",
    val followCamera: FollowCamera = FollowCamera.CONFIG,
    val followExecute: Boolean = false,
    val followLaunchOptions: FollowLaunchOptions = FollowLaunchOptions(
        targetDistanceMeters = 1.5,
        resumeGesture = FollowResumeGesture.THUMB_UP,
        reverseDistanceMeters = 1.4,
    ),
    val followConfiguration: FollowConfiguration? = null,
    val followConfigurationLoading: Boolean = false,
    val followConfigurationError: String? = null,
    val followActive: Boolean = false,
    val followStopping: Boolean = false,
    val followUncertain: Boolean = false,
    val followStatus: FollowStatus? = null,
    val followMessage: String = "尚未启动自动跟随",
    val followError: String? = null,
) {
    val manualActive: Boolean get() = driveStarting || driveEnabled || driveStopping
    val followBlocksControl: Boolean get() = followActive || followUncertain
    val canInspectFollow: Boolean get() = connected && !busy && !manualActive && !followActive
    val canStartFollow: Boolean
        get() = canInspectFollow && !followUncertain && !followConfigurationLoading &&
            followConfiguration != null && followConfigurationError == null &&
            runCatching { followLaunchOptions.validate(followCamera) }.isSuccess
    val canConfigureParking: Boolean
        get() = connected && driveEnabled && driveConfirmedStopped && driveLeft == 0 && driveRight == 0 &&
            !busy && !driveStarting && !driveStopping && !parkingUpdating && parkingSupported && parkingSettings != null &&
            !followBlocksControl
}

class BoardViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("board_connection", 0)
    private val credentialPreferences = application.getSharedPreferences("board_credentials", 0)
    private val client = BoardSshClient()
    private val mutableState = MutableStateFlow(
        BoardUiState(
            host = preferences.getString("host", null) ?: "172.16.16.82",
            port = preferences.getString("port", null) ?: "22",
            username = preferences.getString("username", null) ?: "topeet",
            password = credentialPreferences.getString("password", null).orEmpty(),
            followUncertain = preferences.getBoolean("follow_unconfirmed", false),
            followMessage = if (preferences.getBoolean("follow_unconfirmed", false))
                "上次跟随进程退出未确认，请先在车端检查" else "尚未启动自动跟随"
        )
    )
    val state = mutableState.asStateFlow()
    private var operation: Job? = null
    private var disconnecting = false
    private var driveJob: Job? = null
    private var driveChannel: BoardDriveChannel? = null
    private var desiredWheels = WheelSpeeds()
    private var desiredGeneration = 0L
    private var stopRequest: String? = null
    private var stopReason = "手动控制已停用"
    private var pendingParking: ParkingSettings? = null
    private val driveWakeup = Channel<Unit>(Channel.CONFLATED)
    private var followJob: Job? = null
    private var followChannel: BoardFollowChannel? = null
    private var followStopRequested = false
    private val followWakeup = Channel<Unit>(Channel.CONFLATED)

    init {
        viewModelScope.launch {
            while (true) {
                delay(2000)
                if (state.value.connected && !state.value.busy && !client.isConnected) {
                    disableDrive("SSH 连接中断")
                    stopFollow("SSH 连接中断，正在请求结束跟随")
                    mutableState.update {
                        it.copy(connected = false, status = "连接已断开", error = "连接中断，请检查 Wi-Fi 后重新连接。")
                    }
                }
            }
        }
    }

    fun updateHost(value: String) = editConnection { copy(host = value) }
    fun updatePort(value: String) = editConnection { copy(port = value.filter(Char::isDigit).take(5)) }
    fun updateUsername(value: String) = editConnection { copy(username = value) }
    fun updatePassword(value: String) = editConnection { copy(password = value) }
    fun updateCommand(value: String) { mutableState.update { it.copy(command = value) } }

    private fun editConnection(edit: BoardUiState.() -> BoardUiState) {
        if (!state.value.busy && !state.value.connected) mutableState.update { it.edit().copy(error = null) }
    }

    fun permissionDenied() {
        mutableState.update { it.copy(error = "请允许访问本地网络，App 才能连接开发板。") }
    }

    fun connect() {
        val input = state.value
        if (input.busy || input.connected) return
        val host = input.host.trim()
        val username = input.username.trim()
        val port = input.port.toIntOrNull()
        if (host.isBlank() || host.any(Char::isWhitespace) || username.isBlank() ||
            port == null || port !in 1..65535 || input.password.isEmpty()
        ) {
            mutableState.update { it.copy(error = "请填写开发板 IP、用户名、密码，以及 1–65535 范围内的端口。") }
            return
        }
        mutableState.update {
            it.copy(host = host, username = username, busy = true, error = null, result = null,
                followConfiguration = null, followConfigurationError = null, status = "正在连接…")
        }
        operation = viewModelScope.launch {
            var connectionAttempts = 1
            var sshEstablished = false
            try {
                withInitialConnectionRetry(onRetry = { attempt ->
                    connectionAttempts = attempt
                    mutableState.update { it.copy(status = "正在重新连接（$attempt/2）…") }
                }) {
                    client.connect(BoardConnection(host, port, username, input.password))
                }
                sshEstablished = true
                preferences.edit().putString("host", host).putString("port", port.toString())
                    .putString("username", username).apply()
                credentialPreferences.edit().putString("password", input.password).apply()
                mutableState.update { it.copy(connected = true, status = "正在读取开发板信息…") }
                val result = client.execute(BOARD_STATUS_COMMAND)
                mutableState.update { it.copy(result = result, status = "已连接") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                withContext(Dispatchers.IO) { client.disconnect() }
                mutableState.update {
                    it.copy(connected = false, status = "连接失败",
                        error = describeFailure(failure, connecting = !sshEstablished, attempts = connectionAttempts))
                }
            } finally {
                if (!disconnecting) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun execute(command: String = state.value.command) {
        if (!state.value.connected || state.value.busy || state.value.driveStarting ||
            state.value.driveEnabled || state.value.driveStopping || state.value.followBlocksControl || command.isBlank()) return
        mutableState.update { it.copy(busy = true, error = null, result = null, status = "正在执行…") }
        operation = viewModelScope.launch {
            try {
                val result = client.execute(command.trim())
                mutableState.update {
                    it.copy(result = result, status = "已连接", error = if (result.exitCode != 0) "命令返回退出码 ${result.exitCode}，请查看下方输出。" else null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(connected = client.isConnected, status = if (client.isConnected) "已连接" else "连接已断开", error = describeFailure(failure))
                }
            } finally {
                if (!disconnecting) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun disconnect() {
        if (disconnecting) return
        disconnecting = true
        val previous = operation
        previous?.cancel()
        mutableState.update { it.copy(busy = true, status = "正在断开…") }
        operation = viewModelScope.launch {
            disableDrive("连接已断开")
            stopFollow("正在停止跟随后断开连接…")
            val followFinished = withTimeoutOrNull(12_000L) { followJob?.join(); true } == true
            if (!followFinished) {
                followChannel?.close()
                followJob?.cancel()
                mutableState.update {
                    it.copy(followUncertain = true, followMessage = "断开等待超时，跟随进程退出未确认",
                        followError = "车端将按会话超时请求停止；请检查进程和车辆状态。")
                }
            }
            driveJob?.join()
            withContext(Dispatchers.IO) { client.disconnect() }
            withTimeoutOrNull(1_000L) { followJob?.join() }
            previous?.join()
            disconnecting = false
            mutableState.update { it.copy(connected = false, busy = false, status = "已断开", error = null) }
        }
    }

    override fun onCleared() {
        desiredWheels = WheelSpeeds()
        driveChannel?.close()
        followChannel?.close()
        client.disconnect()
        super.onCleared()
    }

    /** dryRun is used by instrumented tests; the user-facing button always controls real hardware. */
    fun enableDrive(dryRun: Boolean = false) {
        val current = state.value
        if (!current.connected || current.busy || current.driveEnabled || current.driveStarting || current.driveStopping ||
            current.followBlocksControl) return
        desiredWheels = WheelSpeeds()
        stopRequest = null
        pendingParking = null
        while (driveWakeup.tryReceive().isSuccess) { /* Discard signals from the previous session. */ }
        mutableState.update {
            it.copy(driveStarting = true, driveConfirmedStopped = false, driveError = null,
                driveStatus = "正在检测电机…", parkingSupported = false, parkingSettings = null,
                parkingUpdating = false, parkingStatus = "正在读取驻车设置…")
        }
        driveJob = viewModelScope.launch {
            var channel: BoardDriveChannel? = null
            var parkingCleared = false
            try {
                val activeChannel = client.openDriveChannel(dryRun)
                channel = activeChannel
                driveChannel = activeChannel
                if (stopRequest == null) {
                    mutableState.update {
                        it.copy(driveStarting = false, driveEnabled = true, driveConfirmedStopped = true,
                            drivePort = activeChannel.ready.port,
                            driveStatus = if (activeChannel.ready.dryRun) "模拟控制 · 不驱动电机" else "手动控制已就绪 · 松手停车",
                            parkingSupported = activeChannel.ready.parkingSupported,
                            parkingSettings = activeChannel.ready.parking,
                            parkingStatus = if (activeChannel.ready.parkingSupported) "驻车设置已读取"
                                else "开发板脚本不支持驻车设置，请更新 app_joystick_bridge.py")
                    }
                }
                while (true) {
                    val requestedStop = stopRequest
                    if (requestedStop != null) {
                        if (requestedStop == "estop") activeChannel.exchange(0, 0, emergency = true)
                        else activeChannel.stopAndClose()
                        parkingCleared = activeChannel.ready.parkingSupported
                        mutableState.update { it.copy(driveConfirmedStopped = true, driveStatus = stopReason) }
                        break
                    }
                    // All configuration shares this loop with heartbeats; a UI callback never
                    // starts a competing channel transaction or queues a movement behind it.
                    val requestedParking = pendingParking
                    if (requestedParking != null) {
                        check(desiredWheels.stopped && state.value.driveConfirmedStopped) { "请先停车再设置驻车" }
                        val ack = client.configureParking(activeChannel, requestedParking)
                        check(ack.stopped && ack.parking == requestedParking) { "开发板驻车设置回执不一致" }
                        pendingParking = null
                        mutableState.update {
                            it.copy(parkingSettings = ack.parking, parkingUpdating = false,
                                parkingStatus = if (activeChannel.ready.dryRun) "模拟驻车设置已确认"
                                    else "驻车设置已生效")
                        }
                        // Recheck emergency/close before the next heartbeat, including when
                        // the user pressed stop while the configuration ACK was in flight.
                        continue
                    }
                    val target = desiredWheels
                    val sentGeneration = desiredGeneration
                    val ack = activeChannel.exchange(target.left, target.right)
                    mutableState.update {
                        it.copy(driveConfirmedStopped = ack.stopped && desiredWheels.stopped && sentGeneration == desiredGeneration)
                    }
                    // Keep at most one request in flight; always sample the latest joystick value.
                    withTimeoutOrNull(100L) { driveWakeup.receive() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(driveStatus = "手动控制已停用", driveConfirmedStopped = false,
                        parkingStatus = "驻车状态未确认，请重新启用手动控制",
                        driveError = "控制连接已中止：${failure.message.orEmpty().take(220)}。请检查小车后重新启用。")
                }
            } finally {
                channel?.close()
                driveChannel = null
                desiredWheels = WheelSpeeds()
                pendingParking = null
                mutableState.update {
                    it.copy(driveStarting = false, driveEnabled = false, driveStopping = false,
                        parkingSupported = false, parkingSettings = null, parkingUpdating = false,
                        parkingStatus = if (parkingCleared && channel?.ready?.dryRun == true) "模拟驻车已关闭，模拟电流已清零"
                            else if (parkingCleared) "驻车已关闭，电机驻车电流已清零"
                            else if (it.driveError != null) it.parkingStatus
                            else "驻车状态未确认，重新启用后读取",
                        driveLeft = 0, driveRight = 0, connected = client.isConnected,
                        status = if (client.isConnected) it.status else "连接已断开")
                }
            }
        }
    }

    fun moveJoystick(x: Float, forward: Float) {
        if (!state.value.driveEnabled || state.value.followBlocksControl || state.value.parkingUpdating || pendingParking != null || stopRequest != null) return
        val previous = desiredWheels
        desiredWheels = joystickToWheels(x, forward, state.value.speedLimit)
        if (previous != desiredWheels) desiredGeneration += 1
        mutableState.update {
            it.copy(driveLeft = desiredWheels.left, driveRight = desiredWheels.right,
                driveConfirmedStopped = it.driveConfirmedStopped && previous == desiredWheels && desiredWheels.stopped)
        }
        if (previous.stopped && !desiredWheels.stopped) driveWakeup.trySend(Unit)
    }

    fun releaseJoystick() {
        if (!desiredWheels.stopped) desiredGeneration += 1
        desiredWheels = WheelSpeeds()
        mutableState.update { it.copy(driveLeft = 0, driveRight = 0) }
        driveWakeup.trySend(Unit)
    }

    fun changeSpeedLimit(value: Int) {
        if (state.value.parkingUpdating || !desiredWheels.stopped || (state.value.driveEnabled && !state.value.driveConfirmedStopped)) return
        mutableState.update { it.copy(speedLimit = value.coerceIn(10, 100)) }
    }

    fun setParkingEnabled(enabled: Boolean) {
        val settings = state.value.parkingSettings ?: return
        if (enabled && settings.currentAmps == 0) return
        requestParking(settings.copy(enabled = enabled))
    }

    fun changeParkingCurrent(value: Int) {
        val settings = state.value.parkingSettings ?: return
        if (value !in 0..60) return
        requestParking(settings.copy(enabled = settings.enabled && value > 0, currentAmps = value))
    }

    private fun requestParking(settings: ParkingSettings) {
        val current = state.value
        if (!current.canConfigureParking || !desiredWheels.stopped || stopRequest != null ||
            settings == current.parkingSettings) return
        pendingParking = settings
        // Keep the displayed switch/current at their last acknowledged values until the
        // board confirms the entire request. This flag also gates joystick input.
        mutableState.update { it.copy(parkingUpdating = true, parkingStatus = "正在应用驻车设置…") }
        driveWakeup.trySend(Unit)
    }

    fun disableDrive(reason: String = "手动控制已停用", emergency: Boolean = false) {
        if (driveJob?.isActive != true) return
        releaseJoystick()
        if (stopRequest == "estop") return
        pendingParking = null
        stopRequest = if (emergency) "estop" else "close"
        stopReason = reason
        mutableState.update {
            it.copy(driveEnabled = false, driveStopping = true, driveStatus = "正在发送停车指令…")
        }
        driveWakeup.trySend(Unit)
    }

    fun selectFollowCamera(camera: FollowCamera) {
        val current = state.value
        if (current.busy || current.followActive || current.manualActive || current.followCamera == camera) return
        mutableState.update {
            it.copy(followCamera = camera,
                followLaunchOptions = if (camera == FollowCamera.ASTRA_PRO) it.followLaunchOptions
                    else it.followLaunchOptions.copy(avoidanceEnabled = false),
                followConfiguration = null, followConfigurationError = null)
        }
    }

    fun setFollowExecute(execute: Boolean) {
        if (state.value.busy || state.value.followBlocksControl || state.value.manualActive) return
        mutableState.update { it.copy(followExecute = execute) }
    }

    fun setFollowLaunchOptions(options: FollowLaunchOptions) {
        val current = state.value
        if (current.busy || current.followBlocksControl || current.manualActive) return
        if (current.followLaunchOptions == options) return
        mutableState.update {
            it.copy(followCamera = if (options.avoidanceEnabled) FollowCamera.ASTRA_PRO else it.followCamera,
                followLaunchOptions = options, followConfiguration = null,
                followConfigurationError = null)
        }
    }

    fun inspectFollowConfiguration() {
        val current = state.value
        if (!current.canInspectFollow) return
        val camera = current.followCamera
        val options = current.followLaunchOptions
        runCatching { options.validate(camera) }.exceptionOrNull()?.let { failure ->
            mutableState.update { it.copy(followConfiguration = null,
                followConfigurationError = failure.message ?: "自动跟随参数无效") }
            return
        }
        mutableState.update {
            it.copy(busy = true, followConfigurationLoading = true, followConfiguration = null,
                followConfigurationError = null)
        }
        operation = viewModelScope.launch {
            try {
                val configuration = client.readFollowConfiguration(camera, options)
                mutableState.update { it.copy(followConfiguration = configuration) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(followConfigurationError = describeFailure(failure), connected = client.isConnected)
                }
            } finally {
                mutableState.update { it.copy(followConfigurationLoading = false, busy = if (disconnecting) it.busy else false) }
            }
        }
    }

    fun refreshFollowAvailability() {
        val current = state.value
        if (!current.canInspectFollow || !current.followUncertain) return
        mutableState.update { it.copy(busy = true, followError = null, followMessage = "正在检查车端控制占用…") }
        operation = viewModelScope.launch {
            try {
                val availability = client.readFollowAvailability()
                if (availability.controlAvailable) {
                    preferences.edit().putBoolean("follow_unconfirmed", false).apply()
                    mutableState.update {
                        it.copy(followUncertain = false, followStatus = null,
                            followMessage = "控制会话已结束；车轮停稳未确认")
                    }
                } else {
                    mutableState.update {
                        it.copy(followMessage = "控制仍被占用，保持锁定",
                            followError = availability.reason ?: "车端尚未确认控制资源已释放")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(followMessage = "控制占用检查失败，保持锁定",
                        followError = describeFailure(failure), connected = client.isConnected)
                }
            } finally {
                if (!disconnecting) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Only explicit UI activation starts a session; dryRun belongs to offline integration tests. */
    fun startFollow(dryRun: Boolean = false) {
        val current = state.value
        if (!current.canStartFollow) return
        followStopRequested = false
        while (followWakeup.tryReceive().isSuccess) { /* Discard previous-session signals. */ }
        mutableState.update {
            it.copy(followActive = true, followStopping = false, followStatus = null,
                followError = null, followMessage = "正在建立跟随会话…")
        }
        followJob = viewModelScope.launch {
            var channel: BoardFollowChannel? = null
            var startAttempted = false
            var terminal = false
            try {
                val control = client.openFollowChannel(current.followCamera, current.followExecute,
                    dryRun, current.followLaunchOptions)
                channel = control
                followChannel = control
                if (followStopRequested) {
                    // Opening the bridge does not start the runtime. A lifecycle stop received
                    // during connection must not be followed by a late start command.
                    terminal = true
                    mutableState.update { it.copy(followMessage = "启动已取消，未发送跟随启动指令") }
                    return@launch
                }
                startAttempted = true
                preferences.edit().putBoolean("follow_unconfirmed", true).apply()
                var reply = control.start()
                var stopSent = false
                while (true) {
                    terminal = reply.terminal
                    mutableState.update {
                        it.copy(followStatus = reply.status, followError = reply.status.fault,
                            followStopping = it.followStopping || reply.status.stopRequested,
                            followMessage = if (terminal) "跟随进程已结束；停车回执和机械停稳状态见下方"
                                else if (it.followStopping || reply.status.stopRequested) "正在等待跟随进程退出…"
                                else "跟随状态已更新")
                    }
                    if (terminal) break
                    if (followStopRequested && !stopSent) {
                        reply = control.requestStop()
                        stopSent = true
                    } else {
                        withTimeoutOrNull(500L) { followWakeup.receive() }
                        // A stop request wakes the wait and has priority over the next heartbeat.
                        if (followStopRequested && !stopSent) {
                            reply = control.requestStop()
                            stopSent = true
                        } else {
                            reply = control.heartbeat()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableState.update {
                    it.copy(followStatus = null, followError = failure.message.orEmpty().take(350),
                        followMessage = if (startAttempted) "跟随连接中断，进程退出和车辆状态未知"
                            else "跟随会话未建立，未发送启动指令")
                }
            } finally {
                channel?.close()
                followChannel = null
                val uncertain = startAttempted && !terminal
                preferences.edit().putBoolean("follow_unconfirmed", uncertain).apply()
                mutableState.update {
                    it.copy(followActive = false, followStopping = false, followUncertain = uncertain,
                        followExecute = false, connected = client.isConnected,
                        status = if (client.isConnected) it.status else "连接已断开",
                        followMessage = if (uncertain && it.followError == null) "跟随进程退出未确认，请在车端检查"
                            else it.followMessage)
                }
            }
        }
    }

    fun stopFollow(reason: String = "已请求停止，正在等待跟随进程退出…") {
        if (followJob?.isActive != true) return
        followStopRequested = true
        mutableState.update { it.copy(followStopping = true, followMessage = reason) }
        followWakeup.trySend(Unit)
    }

    fun onControlSurfaceHidden(reason: String) {
        disableDrive(reason)
        stopFollow("$reason，正在请求结束跟随")
    }

    private fun describeFailure(failure: Exception, connecting: Boolean = false, attempts: Int = 1): String {
        val message = generateSequence(failure as Throwable?) { it.cause }.take(8)
            .joinToString(" ") { it.message.orEmpty() }
        return when {
            message.contains("Auth fail", true) -> "登录失败，请检查用户名和密码。"
            message.contains("hostkey", true) || message.contains("host key", true) ->
                "开发板身份校验失败。当前 App 只信任已配置的开发板；重装系统后需要更新主机公钥。"
            failure is SocketTimeoutException || message.contains("timeout", true) || message.contains("timed out", true) ->
                if (connecting) "连接开发板超时（已尝试 $attempts 次）。请确认手机与开发板网络互通，IP 地址和端口正确。"
                else "连接或命令超时，连接已断开。请检查 Wi-Fi；板上已启动的程序可能仍在运行。"
            message.contains("refused", true) -> "开发板拒绝连接，请检查 SSH 服务和端口。"
            message.contains("unreachable", true) || message.contains("resolve", true) ->
                "无法访问开发板，请检查 IP 地址与手机 Wi-Fi。"
            else -> "操作失败：${message.ifBlank { failure.javaClass.simpleName }.take(350)}"
        }
    }

    companion object {
        const val BOARD_STATUS_COMMAND = "printf 'Host: '; hostname; printf 'User: '; whoami; uname -srmo; uptime"
        const val MEMORY_COMMAND = "free -h"
        const val DISK_COMMAND = "df -h /"
        const val NETWORK_COMMAND = "ip -brief address"
    }
}
