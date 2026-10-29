package com.example.followingcar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val pairingBusy: Boolean = false,
    val pairingUnconfirmed: Boolean = false,
    val pairingHotspotActive: Boolean = false,
    val status: String = "尚未连接",
    val error: String? = null,
    val result: CommandResult? = null,
    val videoServiceState: VideoServiceState = VideoServiceState.UNKNOWN,
    val videoServiceBusy: Boolean = false,
    val videoServiceMessage: String = "连接开发板后可开启视频",
    val videoServiceError: String? = null,
    val videoPreviewRevision: Int = 0,
    val driveStarting: Boolean = false,
    val driveEnabled: Boolean = false,
    val driveStopping: Boolean = false,
    val driveStatus: String = "手动控制未启用",
    val drivePort: String = "自动检测串口",
    val driveError: String? = null,
    val driveFirstFailure: DiagnosticFailure? = null,
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
    val followFirstFailure: DiagnosticFailure? = null,
) {
    val manualActive: Boolean get() = driveStarting || driveEnabled || driveStopping
    val followBlocksControl: Boolean get() = followActive || followStopping || followUncertain
    val canEnableDrive: Boolean get() = connected && !busy && !pairingBusy && !pairingUnconfirmed && !manualActive && !followBlocksControl && !videoServiceBusy
    val canToggleVideoService: Boolean
        get() = connected && !busy && !videoServiceBusy && !driveStarting && !driveStopping && !followBlocksControl
    val canInspectFollow: Boolean get() = connected && !busy && !pairingBusy && !pairingUnconfirmed && !manualActive && !followActive && !followStopping && !videoServiceBusy
    // Restoring a lost wireless group must remain possible while the old control lock awaits SSH inspection.
    val canManagePairing: Boolean get() = !connected && !busy && !manualActive &&
        !followActive && !followStopping && !videoServiceBusy
    val canManageWifiDirect: Boolean get() = canManagePairing && !pairingBusy && !pairingHotspotActive && !pairingUnconfirmed
    val canStartFollow: Boolean
        get() = canInspectFollow && !followUncertain && !followConfigurationLoading &&
            runCatching { followLaunchOptions.validate(followCamera) }.isSuccess
    val canConfigureParking: Boolean
        get() = connected && driveEnabled && driveConfirmedStopped && driveLeft == 0 && driveRight == 0 &&
            !busy && !driveStarting && !driveStopping && !parkingUpdating && parkingSupported && parkingSettings != null &&
            !followBlocksControl
}

class BoardViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("board_connection", 0)
    private val credentialPreferences = application.getSharedPreferences("board_credentials", 0)
    private val recoveryPreferences = application.getSharedPreferences("follow_recovery", 0)
    private var followRecovery = FollowRecoverySnapshot.restore(recoveryPreferences.all)
    private val client = BoardSshClient()
    private val ownerDiagnostic = Diagnostics.newContext()
    private var followDiagnostic = followRecovery?.context ?: DiagnosticContext(
        preferences.getString("last_follow_operation", null) ?: Diagnostics.newContext().operationId)
    private var driveDiagnostic = DiagnosticContext(preferences.getString("last_drive_operation", null)
        ?: Diagnostics.newContext().operationId)
    private var firstFollowStopSource: ControlStopSource? = null
    private var firstDriveStopSource: ControlStopSource? = null
    private val mutableState = MutableStateFlow(
        BoardUiState(
            host = preferences.getString("host", null) ?: "172.16.16.82",
            port = preferences.getString("port", null) ?: "22",
            username = preferences.getString("username", null) ?: "topeet",
            password = credentialPreferences.getString("password", null).orEmpty(),
            followUncertain = followRecovery != null || preferences.getBoolean("follow_unconfirmed", false),
            followFirstFailure = followRecovery?.firstFailure,
            followMessage = if (followRecovery != null || preferences.getBoolean("follow_unconfirmed", false))
                "上次跟随进程退出未确认，连接后将自动检查控制占用" else "尚未启动自动跟随"
        )
    )
    val state = mutableState.asStateFlow()
    private val wifiDirectDelegate = lazy {
        WifiDirectController(application) { state.value.canManageWifiDirect }
    }
    internal val wifiDirect: WifiDirectController get() = wifiDirectDelegate.value
    private val pairingDelegate = lazy {
        BluetoothHotspotController(application,
            canMutate = { state.value.canManagePairing && !wifiDirect.mutationPending &&
                !wifiDirect.state.value.groupExists && !wifiDirect.state.value.confirmationRequired },
            onHotspotLost = ::onPairingHotspotLost,
            onPaired = { address ->
                // The controller has checked the Bluetooth result against the phone's real AP subnet.
                if (state.value.canManagePairing && !wifiDirect.mutationPending && pairing.state.value.hotspotActive &&
                    !pairing.state.value.confirmationRequired && pairing.state.value.boardIp == address) {
                    mutableState.update { it.copy(host = address, pairingBusy = false,
                        pairingUnconfirmed = false, pairingHotspotActive = true, error = null) }
                    connect()
                }
            },
            onHotspotLeaseChanged = { active -> PairingHotspotService.setActive(application, active) },
        )
    }
    internal val pairing: BluetoothHotspotController get() = pairingDelegate.value
    private var hotspotConnectionAddress: String? = null
    private var connectingHotspot = false
    private var directConnectionGroup: DirectNetworkLease? = null
    private var connectingDirect = false
    private var operation: Job? = null
    // Service commands must finish before a competing start/stop. Cancelling an SSH command
    // closes the shared session, so motor stop uses its own channel and never cancels this job.
    private val videoServiceMutex = Mutex()
    private var videoServiceJob: Job? = null
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
    private var followCleanupUnconfirmed = false
    private val followWakeup = Channel<Unit>(Channel.CONFLATED)

    init {
        Diagnostics.event("viewmodel.created", ownerDiagnostic)
        viewModelScope.launch {
            pairing.state.collect { pair ->
                mutableState.update { it.copy(pairingBusy = pair.busy,
                    pairingUnconfirmed = pair.confirmationRequired, pairingHotspotActive = pair.hotspotActive) }
            }
        }
        viewModelScope.launch {
            wifiDirect.state.collect { wireless ->
                val expected = directConnectionGroup ?: return@collect
                if (!expected.isLost(wireless)) return@collect
                directConnectionGroup = null
                val message = "Wi-Fi Direct 无线组已断开或失去确认，请恢复无线组后重新连接开发板。"
                if (connectingDirect) operation?.cancel(CancellationException(message))
                val failure = IOException(message)
                if (state.value.followActive) {
                    val first = recordFollowFailure("wifi_direct.transport_lost", failure)
                    mutableState.update { it.copy(followFirstFailure = first) }
                } else if (state.value.manualActive) {
                    val first = Diagnostics.failure(driveDiagnostic, "wifi_direct.transport_lost", failure)
                    mutableState.update { it.copy(driveFirstFailure = it.driveFirstFailure ?: first) }
                } else Diagnostics.failure(activeDiagnostic(), "wifi_direct.transport_lost", failure)
                disableDrive(message, source = ControlStopSource.TRANSPORT_LOST)
                stopFollow(message, ControlStopSource.TRANSPORT_LOST)
                client.disconnect("wifi_direct_group_lost", activeDiagnostic())
                mutableState.update { it.withTransportState(false).copy(error = message) }
            }
        }
        viewModelScope.launch {
            Diagnostics.failures.collect { failures ->
                val followFailure = selectFollowFirstFailure(followDiagnostic, followRecovery, state.value.followFirstFailure, failures)
                val driveFailure = failures.firstOrNull { it.context.operationId == driveDiagnostic.operationId }
                followFailure?.let {
                    followDiagnostic = followDiagnostic.copy(
                        connectionId = followDiagnostic.connectionId ?: it.context.connectionId,
                        runId = followDiagnostic.runId ?: it.context.runId,
                    )
                }
                driveFailure?.let {
                    driveDiagnostic = driveDiagnostic.copy(
                        connectionId = driveDiagnostic.connectionId ?: it.context.connectionId,
                        runId = driveDiagnostic.runId ?: it.context.runId,
                    )
                }
                mutableState.update { it.copy(
                    followFirstFailure = followFailure,
                    driveFirstFailure = driveFailure
                        ?: it.driveFirstFailure?.takeIf { f -> f.context.operationId == driveDiagnostic.operationId },
                ) }
                if (followRecovery != null || state.value.followUncertain) persistFollowRecovery()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(2000)
                if (state.value.connected && !state.value.busy && !client.isConnected) {
                    Diagnostics.event("connection.lost", activeDiagnostic(), critical = true)
                    disableDrive("SSH 连接中断", source = ControlStopSource.TRANSPORT_LOST)
                    stopFollow("SSH 连接中断，正在请求结束跟随", ControlStopSource.TRANSPORT_LOST)
                    mutableState.update {
                        it.withTransportState(false).copy(error = "连接中断，请检查 Wi-Fi 后重新连接。")
                    }
                }
            }
        }
    }

    private fun activeDiagnostic(): DiagnosticContext = when {
        state.value.followActive -> followDiagnostic
        state.value.manualActive -> driveDiagnostic
        else -> ownerDiagnostic.copy(connectionId = client.diagnosticConnectionId)
    }

    private fun persistFollowRecovery(first: DiagnosticFailure? = state.value.followFirstFailure) {
        val previous = followRecovery?.takeIf { it.context.operationId == followDiagnostic.operationId }
        val snapshot = FollowRecoverySnapshot(followDiagnostic,
            previous?.firstFailure ?: first?.takeIf { it.context.operationId == followDiagnostic.operationId })
        if (snapshot == followRecovery) return
        followRecovery = snapshot
        recoveryPreferences.edit().clear().apply {
            snapshot.storedValues().forEach { (key, value) -> putString(key, value) }
        }.apply()
    }

    private fun clearFollowRecovery() {
        followRecovery = null
        recoveryPreferences.edit().clear().apply()
        preferences.edit().putBoolean("follow_unconfirmed", false).apply()
    }

    private fun recordFollowFailure(event: String, failure: Throwable,
        fields: Map<String, Any?> = emptyMap()): DiagnosticFailure {
        val logged = Diagnostics.failure(followDiagnostic, event, failure, fields)
        return selectFollowFirstFailure(followDiagnostic, followRecovery, state.value.followFirstFailure, listOf(logged)) ?: logged
    }

    fun recordUiEvent(event: String, detail: String? = null) {
        Diagnostics.event(event, activeDiagnostic(), fields = mapOf("detail" to detail,
            "ownerId" to ownerDiagnostic.operationId, "followActive" to state.value.followActive,
            "manualActive" to state.value.manualActive))
    }

    fun updateHost(value: String) = editConnection { copy(host = value) }
    fun updatePort(value: String) = editConnection { copy(port = value.filter(Char::isDigit).take(5)) }
    fun updateUsername(value: String) = editConnection { copy(username = value) }
    fun updatePassword(value: String) = editConnection { copy(password = value) }
    fun updateCommand(value: String) { mutableState.update { it.copy(command = value) } }

    internal fun startPairing(address: String) {
        if (!state.value.canManagePairing || pairing.state.value.busy) return
        if (wifiDirect.state.value.groupExists || wifiDirect.mutationPending || wifiDirect.state.value.confirmationRequired) {
            mutableState.update { it.copy(error = "请先在 Wi-Fi Direct 中确认并解散现有无线组，再一键配对。") }
            return
        }
        mutableState.update { it.copy(error = null) }
        pairing.start(address.trim().uppercase())
    }

    private fun onPairingHotspotLost() {
        if (hotspotConnectionAddress == null) return
        hotspotConnectionAddress = null
        val message = "手机配对热点已断开，请恢复配对后重新连接开发板。"
        if (connectingHotspot) operation?.cancel(CancellationException(message))
        val failure = IOException(message)
        if (state.value.followActive) {
            val first = recordFollowFailure("pairing.hotspot_lost", failure)
            mutableState.update { it.copy(followFirstFailure = first) }
        } else if (state.value.manualActive) {
            val first = Diagnostics.failure(driveDiagnostic, "pairing.hotspot_lost", failure)
            mutableState.update { it.copy(driveFirstFailure = it.driveFirstFailure ?: first) }
        } else Diagnostics.failure(activeDiagnostic(), "pairing.hotspot_lost", failure)
        disableDrive(message, source = ControlStopSource.TRANSPORT_LOST)
        stopFollow(message, ControlStopSource.TRANSPORT_LOST)
        client.disconnect("pairing_hotspot_lost", activeDiagnostic())
        mutableState.update { it.withTransportState(false).copy(error = message) }
    }

    private fun ensurePairingNetwork(address: String?) {
        if (address != null && (hotspotConnectionAddress != address || !pairing.state.value.hotspotActive ||
                pairing.state.value.confirmationRequired || pairing.state.value.boardIp != address))
            throw CancellationException("手机配对热点已变化，取消本次 SSH 连接。")
    }

    internal fun selectWifiDirectAddress(address: String) {
        val wireless = wifiDirect.state.value
        if (!state.value.canManageWifiDirect || !wireless.groupInfoKnown || !wireless.isGroupOwner || wireless.busy) return
        val actualAddress = wireless.clients.mapNotNull {
            usableWifiDirectClientAddress(it.ipAddress, wireless.ownerAddress)
        }.firstOrNull { it == address } ?: return
        if (wireless.discovering) wifiDirect.stopDiscovery()
        updateHost(actualAddress)
    }

    private fun editConnection(edit: BoardUiState.() -> BoardUiState) {
        if (!state.value.busy && !state.value.connected && !state.value.manualActive &&
            !state.value.followActive && !state.value.videoServiceBusy) mutableState.update { it.edit().copy(error = null) }
    }

    fun permissionDenied() {
        Diagnostics.event("connection.permission_denied", ownerDiagnostic, critical = true)
        mutableState.update { it.copy(error = "请允许访问本地网络，App 才能连接开发板。") }
    }

    fun connect(viaWifiDirect: Boolean = false) {
        val input = state.value
        if (disconnecting || input.busy || input.pairingBusy || input.pairingUnconfirmed || input.connected || input.manualActive ||
            input.followActive || input.followStopping || input.videoServiceBusy) return
        val wireless = wifiDirect.state.value
        if (wifiDirect.mutationPending || wireless.confirmationRequired || (viaWifiDirect && (wireless.busy || wireless.discovering))) {
            mutableState.update { it.copy(error = if (wireless.confirmationRequired) "上次无线操作结果未确认，请点击刷新无线状态后再连接 SSH。"
                else "请等待无线组操作结束，并停止搜索设备后再连接 SSH。") }
            return
        }
        val directGroup = if (viaWifiDirect) DirectNetworkLease.from(wireless) else null
        if (viaWifiDirect && (directGroup == null || wifiDirectHostForConnection(wireless, input.host.trim()) == null)) {
            mutableState.update { it.copy(error = "请先创建手机 GO 无线组，等待开发板加入，再使用当前组内的开发板 IP；不能使用手机地址或原局域网地址。") }
            return
        }
        val host = input.host.trim()
        val username = input.username.trim()
        val port = input.port.toIntOrNull()
        if (host.isBlank() || host.any(Char::isWhitespace) || username.isBlank() ||
            port == null || port !in 1..65535 || input.password.isEmpty()
        ) {
            mutableState.update { it.copy(error = "请填写开发板 IP、用户名、密码，以及 1–65535 范围内的端口。") }
            return
        }
        val diagnostic = Diagnostics.newContext()
        val pairedAddress = input.host.trim().takeIf { !viaWifiDirect && pairing.state.value.hotspotActive &&
            pairing.state.value.boardIp == it && !pairing.state.value.confirmationRequired }
        hotspotConnectionAddress = pairedAddress
        connectingHotspot = pairedAddress != null
        directConnectionGroup = directGroup
        connectingDirect = directGroup != null
        Diagnostics.protectSecret(input.password)
        Diagnostics.event("connection.requested", diagnostic, source = "USER_CONNECT",
            fields = mapOf("host" to host, "port" to port))
        mutableState.update {
            it.copy(host = host, username = username, busy = true, error = null, result = null,
                followConfiguration = null, followConfigurationError = null, status = "正在连接…",
                videoServiceState = VideoServiceState.UNKNOWN, videoServiceError = null)
        }
        operation = viewModelScope.launch {
            var connectionAttempts = 1
            var sshEstablished = false
            try {
                withInitialConnectionRetry(onRetry = { attempt ->
                    connectionAttempts = attempt
                    Diagnostics.event("connection.retry", diagnostic, fields = mapOf("attempt" to attempt))
                    mutableState.update { it.copy(status = "正在重新连接（$attempt/2）…") }
                }) {
                    ensureDirectConnectionGroup(directGroup)
                    ensurePairingNetwork(pairedAddress)
                    client.connect(BoardConnection(host, port, username, input.password), diagnostic)
                    ensureDirectConnectionGroup(directGroup)
                    ensurePairingNetwork(pairedAddress)
                }
                sshEstablished = true
                preferences.edit().putString("host", host).putString("port", port.toString())
                    .putString("username", username).apply()
                credentialPreferences.edit().putString("password", input.password).apply()
                mutableState.update { it.copy(connected = true, status = "正在读取开发板信息…") }
                val result = client.execute(BOARD_STATUS_COMMAND, kind = "board_status", diagnostic = diagnostic)
                mutableState.update { it.copy(result = result, status = "已连接") }
                refreshVideoServiceState()
                currentCoroutineContext().ensureActive()
                if (!disconnecting && client.isConnected && state.value.followUncertain) inspectFollowAvailability("RECONNECT")
            } catch (cancelled: CancellationException) {
                directConnectionGroup = null
                hotspotConnectionAddress = null
                client.disconnect("connection_cancelled", diagnostic)
                mutableState.update { it.withTransportState(false) }
                Diagnostics.event("connection.cancelled", diagnostic, critical = true)
                throw cancelled
            } catch (failure: Exception) {
                directConnectionGroup = null
                hotspotConnectionAddress = null
                Diagnostics.failure(diagnostic, "connection.failed", failure,
                    mapOf("sshEstablished" to sshEstablished, "attempts" to connectionAttempts))
                withContext(Dispatchers.IO) { client.disconnect("connection_failed", diagnostic) }
                mutableState.update {
                    it.withTransportState(false).copy(status = "连接失败",
                        error = describeFailure(failure, connecting = !sshEstablished, attempts = connectionAttempts))
                }
            } finally {
                connectingDirect = false
                connectingHotspot = false
                if (!disconnecting) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    private fun ensureDirectConnectionGroup(expected: DirectNetworkLease?) {
        if (expected != null && (directConnectionGroup !== expected || expected.isLost(wifiDirect.state.value))) {
            throw CancellationException("手机无线组已变化，取消本次 SSH 连接；请恢复网络后手动重连。")
        }
    }

    fun execute(command: String = state.value.command) {
        if (!state.value.connected || state.value.busy || state.value.videoServiceBusy || state.value.driveStarting ||
            state.value.driveEnabled || state.value.driveStopping || state.value.followBlocksControl || command.isBlank()) return
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        Diagnostics.event("terminal.requested", diagnostic, fields = mapOf("commandLength" to command.length))
        mutableState.update { it.copy(busy = true, error = null, result = null, status = "正在执行…") }
        operation = viewModelScope.launch {
            try {
                val result = client.execute(command.trim(), diagnostic = diagnostic)
                Diagnostics.event("terminal.completed", diagnostic, fields = mapOf("exitCode" to result.exitCode))
                mutableState.update {
                    it.copy(result = result, status = "已连接", error = if (result.exitCode != 0) "命令返回退出码 ${result.exitCode}，请查看下方输出。" else null)
                }
            } catch (cancelled: CancellationException) {
                Diagnostics.event("terminal.cancelled", diagnostic)
                throw cancelled
            } catch (failure: Exception) {
                // The transport records a sanitized exception; terminal contents are never logged.
                Diagnostics.event("terminal.failed", diagnostic, fields = mapOf("exceptionType" to failure.javaClass.name), critical = true)
                mutableState.update {
                    it.withTransportState(client.isConnected).copy(status = if (client.isConnected) "已连接" else "连接已断开", error = describeFailure(failure))
                }
            } finally {
                if (!disconnecting) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    fun setVideoServiceEnabled(enabled: Boolean) {
        if (disconnecting || !state.value.canToggleVideoService) return
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        Diagnostics.event("video.requested", diagnostic, source = "USER_VIDEO", fields = mapOf("enabled" to enabled))
        mutableState.update { it.copy(videoServiceBusy = true, videoServiceError = null,
            videoServiceMessage = if (enabled) "正在开启视频…" else "正在关闭视频…") }
        videoServiceJob = viewModelScope.launch {
            try {
                changeVideoService(enabled, diagnostic)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // changeVideoService publishes the failure and keeps the result UNKNOWN.
            }
        }
    }

    private suspend fun refreshVideoServiceState() = videoServiceMutex.withLock {
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        mutableState.update { it.copy(videoServiceBusy = true, videoServiceMessage = "正在读取视频服务状态…") }
        try {
            val videoState = client.readVideoServiceState(diagnostic)
            Diagnostics.event("video.inspected", diagnostic, fields = mapOf("videoState" to videoState.name))
            mutableState.update { it.copy(videoServiceState = videoState, videoServiceError = null,
                videoServiceMessage = when (videoState) {
                    VideoServiceState.RUNNING -> "视频服务已开启"
                    VideoServiceState.STOPPED -> "视频服务已关闭"
                    VideoServiceState.UNKNOWN -> "视频服务未就绪，可关闭后重试"
                }) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Diagnostics.failure(diagnostic, "video.inspect_failed", failure)
            recordVideoServiceFailure(failure)
        } finally {
            mutableState.update { it.copy(videoServiceBusy = false) }
        }
    }

    private suspend fun changeVideoService(enabled: Boolean,
        diagnostic: DiagnosticContext = Diagnostics.newContext(client.diagnosticConnectionId),
    ) = videoServiceMutex.withLock {
        Diagnostics.event("video.change_started", diagnostic, fields = mapOf("enabled" to enabled))
        mutableState.update { it.copy(videoServiceBusy = true, videoServiceError = null,
            videoServiceMessage = if (enabled) "正在开启视频…" else "正在关闭视频…") }
        try {
            check(client.isConnected) { "SSH 已断开，无法确认视频服务状态，请重连后关闭视频。" }
            val videoState = client.setVideoServiceEnabled(enabled, diagnostic)
            Diagnostics.event("video.change_confirmed", diagnostic, fields = mapOf("videoState" to videoState.name))
            mutableState.update { it.copy(videoServiceState = videoState, videoServiceError = null,
                videoServiceMessage = if (enabled) "视频服务已开启" else "视频服务已关闭",
                videoPreviewRevision = if (enabled) it.videoPreviewRevision + 1 else it.videoPreviewRevision) }
        } catch (failure: Exception) {
            if (failure !is CancellationException) Diagnostics.failure(diagnostic, "video.change_failed", failure)
            else Diagnostics.event("video.change_cancelled", diagnostic, critical = true)
            recordVideoServiceFailure(failure)
            throw failure
        } finally {
            mutableState.update { it.copy(videoServiceBusy = false) }
        }
    }

    private fun recordVideoServiceFailure(failure: Exception) {
        mutableState.update { it.withTransportState(client.isConnected).copy(videoServiceState = VideoServiceState.UNKNOWN,
            videoServiceMessage = "视频操作失败，状态未确认",
            videoServiceError = failure.message.orEmpty().ifBlank { "视频服务操作未完成，请重试。" }.take(800),
            connected = client.isConnected, status = if (client.isConnected) it.status else "连接已断开") }
    }

    fun disconnect() {
        if (disconnecting) return
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        Diagnostics.event("connection.disconnect_requested", diagnostic, source = ControlStopSource.DISCONNECT.name, critical = true)
        disconnecting = true
        val previous = operation
        previous?.cancel()
        mutableState.update { it.copy(busy = true, status = "正在断开…") }
        operation = viewModelScope.launch {
            var completed = false
            try {
                completed = withTimeoutOrNull(130_000L) {
                    disableDrive("连接已断开", source = ControlStopSource.DISCONNECT)
                    stopFollow("正在停止跟随后断开连接…", ControlStopSource.DISCONNECT)
                    // Preserve video start/stop's existing 60/45-second allowance; the outer
                    // deadline also bounds this startup join and every later cleanup join.
                    if (followChannel == null) followJob?.join()
                    val followFinished = withTimeoutOrNull(12_000L) { followJob?.join(); true } == true
                    if (!followFinished) {
                        markFollowCleanupUnconfirmed("follow.disconnect_timeout", "断开等待超时，跟随进程退出未确认")
                        followChannel?.close("disconnect_timeout")
                        followJob?.cancel()
                    }
                    driveJob?.join()
                    videoServiceJob?.join()
                    if (client.isConnected && state.value.videoServiceState != VideoServiceState.STOPPED) {
                        try { changeVideoService(false) } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Keep the failure visible. */ }
                    }
                    previous?.join()
                    true
                } == true
                if (!completed) {
                    Diagnostics.event("connection.disconnect_deadline", diagnostic,
                        fields = mapOf("timeoutMs" to 130_000L), critical = true)
                    if (followJob?.isActive == true || state.value.followUncertain)
                        markFollowCleanupUnconfirmed("follow.disconnect_deadline", "断开清理超时，控制退出仍未确认")
                }
            } catch (cancelled: CancellationException) {
                if (followJob?.isActive == true || state.value.followUncertain)
                    markFollowCleanupUnconfirmed("follow.disconnect_cancelled", "断开清理被中断，控制退出仍未确认")
                throw cancelled
            } catch (failure: Exception) {
                Diagnostics.failure(diagnostic, "connection.disconnect_failed", failure)
                if (followJob?.isActive == true || state.value.followUncertain)
                    markFollowCleanupUnconfirmed("follow.disconnect_failed", "断开清理失败，控制退出仍未确认")
            } finally {
                // Always detach the old transport, including failed/cancelled cleanup. No command
                // is retried on a new connection, and no successful stop is inferred from this.
                withContext(NonCancellable) {
                    try {
                        withContext(Dispatchers.IO) { client.disconnect("user_disconnect", diagnostic) }
                        if (!completed) {
                            followChannel?.close("disconnect_cleanup")
                            driveChannel?.close("disconnect_cleanup")
                            followJob?.cancel()
                            driveJob?.cancel()
                            videoServiceJob?.cancel()
                        }
                        withTimeoutOrNull(1_000L) { followJob?.join(); driveJob?.join(); videoServiceJob?.join() }
                    } finally {
                        directConnectionGroup = null
                        hotspotConnectionAddress = null
                        disconnecting = false
                        mutableState.update { it.withTransportState(false).copy(busy = false, status = "已断开", error = null) }
                        Diagnostics.event("connection.disconnect_completed", diagnostic,
                            fields = mapOf("cleanupCompleted" to completed), critical = true)
                    }
                }
            }
        }
    }

    private fun markFollowCleanupUnconfirmed(event: String, message: String) {
        followCleanupUnconfirmed = true
        val first = recordFollowFailure(event, SocketTimeoutException(message))
        mutableState.update { it.copy(followUncertain = true, followFirstFailure = first,
            followMessage = message, followError = "车端将按会话超时请求停止；请重新连接后检查控制占用。") }
        preferences.edit().putBoolean("follow_unconfirmed", true).apply()
        persistFollowRecovery(first)
    }

    override fun onCleared() {
        Diagnostics.event("viewmodel.cleared", activeDiagnostic(), source = ControlStopSource.VIEW_MODEL_CLEARED.name,
            fields = mapOf("ownerId" to ownerDiagnostic.operationId), critical = true)
        desiredWheels = WheelSpeeds()
        driveChannel?.close("viewmodel_cleared")
        followChannel?.close("viewmodel_cleared")
        client.disconnect("viewmodel_cleared", activeDiagnostic())
        if (wifiDirectDelegate.isInitialized()) wifiDirect.close()
        if (pairingDelegate.isInitialized()) pairing.close()
        super.onCleared()
    }

    /** dryRun simulates motors only; the video service is still managed on the board. */
    fun enableDrive(dryRun: Boolean = false) {
        val current = state.value
        if (disconnecting || !current.canEnableDrive) return
        driveDiagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        preferences.edit().putString("last_drive_operation", driveDiagnostic.operationId).apply()
        firstDriveStopSource = null
        Diagnostics.event("drive.start_requested", driveDiagnostic, source = "USER_START",
            fields = mapOf("dryRun" to dryRun, "speedLimit" to current.speedLimit), critical = true)
        desiredWheels = WheelSpeeds()
        stopRequest = null
        pendingParking = null
        while (driveWakeup.tryReceive().isSuccess) { /* Discard signals from the previous session. */ }
        mutableState.update {
            it.copy(driveStarting = true, driveConfirmedStopped = false, driveError = null, driveFirstFailure = null,
                driveStatus = "正在开启视频…", parkingSupported = false, parkingSettings = null,
                parkingUpdating = false, parkingStatus = "正在读取驻车设置…")
        }
        driveJob = viewModelScope.launch {
            var channel: BoardDriveChannel? = null
            var parkingCleared = false
            try {
                if (stopRequest == null) changeVideoService(true, driveDiagnostic)
                if (stopRequest != null) {
                    Diagnostics.event("drive.start_cancelled", driveDiagnostic,
                        source = firstDriveStopSource?.name, critical = true)
                    // Do not open the motor bridge after a stop received during video startup.
                    mutableState.update { it.copy(driveConfirmedStopped = true, driveStatus = stopReason) }
                    return@launch
                }
                mutableState.update { it.copy(driveStatus = "正在检测电机…") }
                val activeChannel = client.openDriveChannel(dryRun, driveDiagnostic)
                channel = activeChannel
                driveChannel = activeChannel
                driveDiagnostic = activeChannel.diagnosticContext
                Diagnostics.event("drive.ready", driveDiagnostic, fields = mapOf("dryRun" to activeChannel.ready.dryRun))
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
                        Diagnostics.event("drive.stop_sending", driveDiagnostic, source = firstDriveStopSource?.name,
                            fields = mapOf("stopKind" to requestedStop), critical = true)
                        if (requestedStop == "estop") activeChannel.exchange(0, 0, emergency = true)
                        else activeChannel.stopAndClose()
                        Diagnostics.event("drive.stop_acknowledged", driveDiagnostic, critical = true)
                        parkingCleared = activeChannel.ready.parkingSupported
                        mutableState.update { it.copy(driveConfirmedStopped = true, driveStatus = stopReason) }
                        break
                    }
                    // All configuration shares this loop with heartbeats; a UI callback never
                    // starts a competing channel transaction or queues a movement behind it.
                    val requestedParking = pendingParking
                    if (requestedParking != null) {
                        check(desiredWheels.stopped && state.value.driveConfirmedStopped) { "请先停车再设置驻车" }
                        val ack = client.configureParking(activeChannel, requestedParking, driveDiagnostic)
                        check(ack.stopped && ack.parking == requestedParking) { "开发板驻车设置回执不一致" }
                        Diagnostics.event("parking.confirmed", driveDiagnostic,
                            fields = mapOf("enabled" to requestedParking.enabled, "currentAmps" to requestedParking.currentAmps), critical = true)
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
                Diagnostics.event("drive.cancelled", driveDiagnostic, critical = true)
                throw cancelled
            } catch (failure: Exception) {
                val first = Diagnostics.failure(driveDiagnostic, "drive.failed", failure)
                mutableState.update {
                    it.copy(driveStatus = "手动控制已停用", driveConfirmedStopped = false, driveFirstFailure = first,
                        parkingStatus = "驻车状态未确认，请重新启用手动控制",
                        driveError = "控制连接已中止：${failure.message.orEmpty().take(220)}。请检查小车后重新启用。")
                }
            } finally {
                channel?.close("drive_cleanup")
                driveChannel = null
                desiredWheels = WheelSpeeds()
                pendingParking = null
                // Motor stop/close always precedes service cleanup. Retain manual ownership
                // until cleanup finishes so follow cannot race a late camera-service start.
                mutableState.update { it.copy(driveStarting = false, driveEnabled = false,
                    driveStopping = true, driveLeft = 0, driveRight = 0) }
                withContext(NonCancellable) {
                    try { changeVideoService(false, driveDiagnostic) } catch (_: Exception) { /* Expose unconfirmed cleanup. */ }
                }
                mutableState.update {
                    it.withTransportState(client.isConnected).copy(driveStarting = false, driveEnabled = false, driveStopping = false,
                        parkingSupported = false, parkingSettings = null, parkingUpdating = false,
                        parkingStatus = if (parkingCleared && channel?.ready?.dryRun == true) "模拟驻车已关闭，模拟电流已清零"
                            else if (parkingCleared) "驻车已关闭，电机驻车电流已清零"
                            else if (it.driveError != null) it.parkingStatus
                            else "驻车状态未确认，重新启用后读取",
                        driveLeft = 0, driveRight = 0, connected = client.isConnected,
                        status = if (client.isConnected) it.status else "连接已断开")
                }
                Diagnostics.event("drive.finished", driveDiagnostic, source = firstDriveStopSource?.name,
                    fields = mapOf("stopAcknowledged" to state.value.driveConfirmedStopped,
                        "transportConnected" to client.isConnected), critical = true)
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
        if (!desiredWheels.stopped) Diagnostics.event("drive.input_released", driveDiagnostic, critical = true)
        if (!desiredWheels.stopped) desiredGeneration += 1
        desiredWheels = WheelSpeeds()
        mutableState.update { it.copy(driveLeft = 0, driveRight = 0) }
        driveWakeup.trySend(Unit)
    }

    fun changeSpeedLimit(value: Int) {
        if (state.value.parkingUpdating || !desiredWheels.stopped || (state.value.driveEnabled && !state.value.driveConfirmedStopped)) return
        mutableState.update { it.copy(speedLimit = value.coerceIn(10, 100)) }
        Diagnostics.event("drive.speed_limit_changed", activeDiagnostic(), fields = mapOf("rpm" to state.value.speedLimit))
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
        Diagnostics.event("parking.requested", driveDiagnostic,
            fields = mapOf("enabled" to settings.enabled, "currentAmps" to settings.currentAmps), critical = true)
        // Keep the displayed switch/current at their last acknowledged values until the
        // board confirms the entire request. This flag also gates joystick input.
        mutableState.update { it.copy(parkingUpdating = true, parkingStatus = "正在应用驻车设置…") }
        driveWakeup.trySend(Unit)
    }

    fun disableDrive(reason: String = "手动控制已停用", emergency: Boolean = false,
        source: ControlStopSource = if (emergency) ControlStopSource.EMERGENCY_STOP else ControlStopSource.USER_STOP,
    ) {
        if (driveJob?.isActive != true) return
        if (firstDriveStopSource == null) firstDriveStopSource = source
        Diagnostics.event("drive.stop_requested", driveDiagnostic, source = source.name,
            fields = mapOf("firstSource" to firstDriveStopSource?.name, "emergency" to emergency), critical = true)
        releaseJoystick()
        if (state.value.driveStopping && !state.value.driveStarting && driveChannel == null) return
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
        Diagnostics.event("follow.camera_selected", activeDiagnostic(), fields = mapOf("camera" to camera.argument))
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
        Diagnostics.event("follow.execution_selected", activeDiagnostic(), fields = mapOf("execute" to execute))
    }

    fun setFollowLaunchOptions(options: FollowLaunchOptions) {
        val current = state.value
        if (current.busy || current.followBlocksControl || current.manualActive) return
        if (current.followLaunchOptions == options) return
        Diagnostics.event("follow.options_changed", activeDiagnostic(), fields = followOptionFields(options))
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
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        Diagnostics.event("follow.configuration_requested", diagnostic,
            fields = followOptionFields(options) + mapOf("camera" to camera.argument))
        runCatching { options.validate(camera) }.exceptionOrNull()?.let { failure ->
            Diagnostics.failure(diagnostic, "follow.configuration_invalid", failure)
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
                val configuration = client.readFollowConfiguration(camera, options, diagnostic)
                Diagnostics.event("follow.configuration_confirmed", diagnostic,
                    fields = mapOf("targetDistanceMeters" to configuration.targetDistanceMeters))
                mutableState.update { it.copy(followConfiguration = configuration) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Diagnostics.failure(diagnostic, "follow.configuration_failed", failure)
                mutableState.update {
                    it.withTransportState(client.isConnected).copy(followConfigurationError = describeFailure(failure), connected = client.isConnected,
                        status = if (client.isConnected) "已连接" else "连接已断开")
                }
            } finally {
                mutableState.update { it.copy(followConfigurationLoading = false, busy = if (disconnecting) it.busy else false) }
            }
        }
    }

    fun refreshFollowAvailability() {
        val current = state.value
        if (disconnecting || !current.canInspectFollow || !current.followUncertain) return
        mutableState.update { it.copy(busy = true) }
        operation = viewModelScope.launch {
            try {
                inspectFollowAvailability("USER_INSPECT")
            } finally {
                if (!disconnecting) mutableState.update { it.copy(busy = false) }
            }
        }
    }

    /** Reconnection and the manual button share one read-only recovery path while their caller owns busy. */
    private suspend fun inspectFollowAvailability(source: String) {
        if (disconnecting || !state.value.followUncertain || !client.isConnected) return
        val connectionId = client.diagnosticConnectionId
        val diagnostic = Diagnostics.newContext(connectionId)
        Diagnostics.event("follow.availability_requested", diagnostic, source = source,
            fields = mapOf("controlOperationId" to followDiagnostic.operationId, "runId" to followDiagnostic.runId))
        mutableState.update { it.copy(followError = null, followMessage = "正在检查车端控制占用…") }
        try {
            val availability = client.readFollowAvailability(diagnostic)
            currentCoroutineContext().ensureActive()
            // A late result from a cancelled/replaced connection must not release the recovery lock.
            if (disconnecting || connectionId != client.diagnosticConnectionId || !client.isConnected) return
            Diagnostics.event("follow.availability_result", diagnostic,
                fields = mapOf("controlAvailable" to availability.controlAvailable))
            if (availability.controlAvailable) {
                clearFollowRecovery()
                mutableState.update {
                    it.copy(followUncertain = false, followStatus = null,
                        followMessage = "控制会话已结束；车轮停稳未确认，可选择模式后手动启动跟随")
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
            Diagnostics.failure(diagnostic, "follow.availability_failed", failure)
            if (!disconnecting) mutableState.update {
                it.afterAvailabilityFailure(describeFailure(failure), client.isConnected)
            }
        }
    }

    /** dryRun simulates follow hardware; camera-service shutdown still runs on the board. */
    fun startFollow(dryRun: Boolean = false) {
        val current = state.value
        if (!current.canStartFollow) return
        followDiagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        preferences.edit().putString("last_follow_operation", followDiagnostic.operationId).apply()
        firstFollowStopSource = null
        followCleanupUnconfirmed = false
        Diagnostics.event("follow.start_requested", followDiagnostic, source = "USER_START",
            fields = followOptionFields(current.followLaunchOptions) + mapOf("camera" to current.followCamera.argument,
                "execute" to current.followExecute, "dryRun" to dryRun), critical = true)
        followStopRequested = false
        while (followWakeup.tryReceive().isSuccess) { /* Discard previous-session signals. */ }
        mutableState.update {
            it.copy(followActive = true, followStopping = false, followStatus = null,
                followError = null, followFirstFailure = null, followMessage = "正在关闭视频并释放摄像头…")
        }
        followJob = viewModelScope.launch {
            var channel: BoardFollowChannel? = null
            var startAttempted = false
            var terminal = false
            try {
                // A successful stop must confirm both video processes have exited before
                // anything can open the follow camera. Failure leaves follow unstarted.
                changeVideoService(false, followDiagnostic)
                if (followStopRequested) {
                    Diagnostics.event("follow.start_cancelled", followDiagnostic, source = firstFollowStopSource?.name, critical = true)
                    terminal = true
                    mutableState.update { it.copy(followMessage = "启动已取消，未发送跟随启动指令") }
                    return@launch
                }
                mutableState.update { it.copy(followMessage = "正在建立跟随会话…") }
                val control = client.openFollowChannel(current.followCamera, current.followExecute,
                    dryRun, current.followLaunchOptions, followDiagnostic)
                channel = control
                followChannel = control
                followDiagnostic = control.diagnosticContext
                Diagnostics.event("follow.ready", followDiagnostic,
                    fields = mapOf("dryRun" to control.ready.dryRun, "execute" to control.ready.execute))
                if (followStopRequested) {
                    // Opening the bridge does not start the runtime. A lifecycle stop received
                    // during connection must not be followed by a late start command.
                    terminal = true
                    Diagnostics.event("follow.start_cancelled", followDiagnostic, source = firstFollowStopSource?.name, critical = true)
                    mutableState.update { it.copy(followMessage = "启动已取消，未发送跟随启动指令") }
                    return@launch
                }
                startAttempted = true
                preferences.edit().putBoolean("follow_unconfirmed", true).apply()
                persistFollowRecovery()
                Diagnostics.event("follow.start_sending", followDiagnostic, critical = true)
                var reply = control.start()
                var stopSent = false
                var lastRunState: String? = null
                while (true) {
                    terminal = reply.terminal
                    if (reply.status.runState != lastRunState || terminal) {
                        Diagnostics.event(if (terminal) "follow.terminal_received" else "follow.state_changed", followDiagnostic,
                            fields = mapOf("runState" to reply.status.runState, "reason" to reply.status.reason,
                                "processExited" to reply.status.processExited,
                                "stopAcknowledged" to reply.status.stopAcknowledged,
                                "physicalStopped" to reply.status.physicalStopped), critical = terminal)
                        lastRunState = reply.status.runState
                    }
                    reply.status.fault?.let { fault ->
                        val first = recordFollowFailure("follow.board_fault", IllegalStateException(fault))
                        mutableState.update { it.copy(followFirstFailure = first) }
                    }
                    mutableState.update {
                        it.copy(followStatus = reply.status, followError = reply.status.fault,
                            followStopping = it.followStopping || reply.status.stopRequested,
                            followMessage = if (terminal) "跟随进程已结束；停车回执和机械停稳状态见下方"
                                else if (it.followStopping || reply.status.stopRequested) "正在等待跟随进程退出…"
                                else "跟随状态已更新")
                    }
                    if (terminal) break
                    if (followStopRequested && !stopSent) {
                        Diagnostics.event("follow.stop_sending", followDiagnostic, source = firstFollowStopSource?.name, critical = true)
                        reply = control.requestStop()
                        stopSent = true
                    } else {
                        withTimeoutOrNull(500L) { followWakeup.receive() }
                        // A stop request wakes the wait and has priority over the next heartbeat.
                        if (followStopRequested && !stopSent) {
                            Diagnostics.event("follow.stop_sending", followDiagnostic, source = firstFollowStopSource?.name, critical = true)
                            reply = control.requestStop()
                            stopSent = true
                        } else {
                            reply = control.heartbeat()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                Diagnostics.event("follow.cancelled", followDiagnostic, fields = mapOf("startAttempted" to startAttempted), critical = true)
                throw cancelled
            } catch (failure: Exception) {
                val first = recordFollowFailure("follow.failed", failure,
                    mapOf("startAttempted" to startAttempted, "terminalReceived" to terminal))
                mutableState.update {
                    it.copy(followStatus = null, followFirstFailure = first, followError = failure.message.orEmpty().take(350),
                        followMessage = if (startAttempted) "跟随连接中断，进程退出和车辆状态未知"
                            else "跟随会话未建立，未发送启动指令")
                }
            } finally {
                channel?.close("follow_cleanup")
                followChannel = null
                val uncertain = !terminal && (startAttempted || followCleanupUnconfirmed)
                if (terminal) clearFollowRecovery()
                else if (uncertain) {
                    preferences.edit().putBoolean("follow_unconfirmed", true).apply()
                    persistFollowRecovery()
                }
                mutableState.update {
                    it.withTransportState(client.isConnected).copy(followActive = false, followStopping = false, followUncertain = uncertain,
                        followExecute = false, connected = client.isConnected,
                        status = if (client.isConnected) it.status else "连接已断开",
                        followMessage = if (uncertain && it.followError == null) "跟随进程退出未确认，请在车端检查"
                            else it.followMessage)
                }
                Diagnostics.event("follow.finished", followDiagnostic, source = firstFollowStopSource?.name,
                    fields = mapOf("uncertain" to uncertain, "terminalReceived" to terminal,
                        "transportConnected" to client.isConnected), critical = true)
            }
        }
    }

    fun stopFollow(reason: String = "已请求停止，正在等待跟随进程退出…",
        source: ControlStopSource = ControlStopSource.USER_STOP,
    ) {
        if (followJob?.isActive != true) return
        if (firstFollowStopSource == null) firstFollowStopSource = source
        Diagnostics.event("follow.stop_requested", followDiagnostic, source = source.name,
            fields = mapOf("firstSource" to firstFollowStopSource?.name,
                "alreadyRequested" to followStopRequested), critical = true)
        followStopRequested = true
        mutableState.update { it.copy(followStopping = true, followMessage = reason) }
        followWakeup.trySend(Unit)
    }

    fun onControlSurfaceHidden(reason: String, source: ControlStopSource = ControlStopSource.CONTROL_SURFACE_HIDDEN) {
        Diagnostics.event("ui.control_surface_hidden", activeDiagnostic(), source = source.name,
            fields = mapOf("followActive" to state.value.followActive, "manualActive" to state.value.manualActive), critical = true)
        disableDrive(reason, source = source)
        stopFollow("$reason，正在请求结束跟随", source)
    }

    private fun followOptionFields(options: FollowLaunchOptions): Map<String, Any?> = mapOf(
        "targetDistanceMeters" to options.targetDistanceMeters, "gesturesEnabled" to options.gesturesEnabled,
        "resumeGesture" to options.resumeGesture.name, "avoidanceEnabled" to options.avoidanceEnabled,
        "reverseEnabled" to options.reverseEnabled, "reverseDistanceMeters" to options.reverseDistanceMeters,
    )

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
