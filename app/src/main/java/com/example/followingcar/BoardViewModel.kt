package com.example.followingcar

import android.app.Application
import android.net.Uri
import android.os.SystemClock
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
    val remoteMode: Boolean = false,
    val remoteProfile: RemoteProfile? = null,
    val remoteProfileLoading: Boolean = false,
    val remoteProfileError: String? = null,
    val command: String = "whoami",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val motorActionUnconfirmed: Boolean = false,
    val motorActionCancellable: Boolean = false,
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
    // Local receive-only intent survives unexpected SSH loss, never an explicit stop.
    val videoPreviewWanted: Boolean = false,
    val driveStarting: Boolean = false,
    val driveEnabled: Boolean = false,
    val driveStopping: Boolean = false,
    val driveStatus: String = "手动控制未启用",
    val driveInterlock: DriveInterlock? = null,
    val driveRecoveryNeedsRelease: Boolean = false,
    val driveFeedbackRecheck: Boolean = false,
    val driveStandstillPending: Boolean = false,
    val drivePort: String = "自动检测串口",
    val driveError: String? = null,
    val driveFirstFailure: DiagnosticFailure? = null,
    val speedLimit: Int = 50,
    val driveLeft: Int = 0,
    val driveRight: Int = 0,
    val driveConfirmedStopped: Boolean = true,
    val driveFeedbackLeftRpm: Int? = null,
    val driveFeedbackRightRpm: Int? = null,
    val driveFeedbackVerified: Boolean = false,
    val driveLastFeedbackNanos: Long = 0L,
    val driveFeedbackAgeAtReceiptMs: Long = Long.MAX_VALUE,
    val parkingSupported: Boolean = false,
    val parkingSettings: ParkingSettings? = null,
    // Display-only values from this connection; never authorize a mode from this snapshot.
    val lastReadParkingSettings: ParkingSettings? = null,
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
    // Only a validated terminal ACK ends the protocol. processExited alone is insufficient.
    val followTerminal: Boolean = false,
    val followLastReplyElapsedMs: Long? = null,
    val followStartCancelled: Boolean = false,
    val followMessage: String = "尚未启动自动跟随",
    val followError: String? = null,
    val followFirstFailure: DiagnosticFailure? = null,
) {
    val connectionHost: String get() = if (remoteMode) remoteProfile?.host ?: DEFAULT_REMOTE_HOST else host
    val connectionPort: String get() = if (remoteMode) (remoteProfile?.controlPort ?: DEFAULT_REMOTE_CONTROL_PORT).toString() else port
    val canManageConnectionMode: Boolean get() = canRecoverConnection && !pairingBusy &&
        !pairingUnconfirmed && !remoteProfileLoading
    // An uncertain local wireless group must not block recovery through an independent public route.
    val canSelectRemoteMode: Boolean get() = canRecoverConnection && !pairingBusy && !remoteProfileLoading
    val canReconnectViaRemote: Boolean get() = canSelectRemoteMode && remoteProfile != null && remoteProfileError == null
    val manualActive: Boolean get() = driveStarting || driveEnabled || driveStopping
    val followBlocksControl: Boolean get() = followActive || followStopping || followUncertain
    val canEnableDrive: Boolean get() = connected && !busy && !motorActionUnconfirmed && !pairingBusy && !pairingUnconfirmed && !manualActive && !followBlocksControl && !videoServiceBusy
    val canToggleVideoService: Boolean
        get() = connected && !busy && !motorActionUnconfirmed && !videoServiceBusy && !driveStarting && !driveStopping && !followBlocksControl
    val canInspectFollow: Boolean get() = connected && !busy && !pairingBusy && !pairingUnconfirmed && !manualActive && !followActive && !followStopping && !videoServiceBusy
    // Restoring a lost wireless group must remain possible while the old control lock awaits SSH inspection.
    val canRecoverConnection: Boolean get() = !connected && !busy && !manualActive &&
        !followActive && !followStopping && !videoServiceBusy
    // Rebuilding a lost wireless group is recovery, not permission to control the motors.
    val canManagePairing: Boolean get() = canRecoverConnection
    val canManageWifiDirect: Boolean get() = canManagePairing && !pairingBusy && !pairingHotspotActive && !pairingUnconfirmed
    val canStartFollow: Boolean
        get() = canInspectFollow && !motorActionUnconfirmed && !followUncertain && !followConfigurationLoading &&
            runCatching { followLaunchOptions.validate(followCamera) }.isSuccess
    val canReadMotorConfiguration: Boolean get() = connected && !busy && !manualActive &&
        !followBlocksControl && !videoServiceBusy && !parkingUpdating && !pairingBusy && !pairingUnconfirmed
    val canConfigureMotor: Boolean get() = canReadMotorConfiguration && !motorActionUnconfirmed
    val canExecuteTerminal: Boolean get() = connected && !busy && !motorActionUnconfirmed &&
        !videoServiceBusy && !manualActive && !followBlocksControl
    val canEditParking: Boolean get() = canEditParkingAt(System.nanoTime())
    val canConfigureParking: Boolean get() = canEditParking
}

/** Applies a controller-verified local lease without enabling or restarting any control mode. */
internal fun BoardUiState.withVerifiedPairingNetwork(address: String): BoardUiState = copy(
    host = address, remoteMode = false, pairingBusy = false, pairingUnconfirmed = false,
    pairingHotspotActive = true, error = null,
)

class BoardViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("board_connection", 0)
    private val credentialPreferences = application.getSharedPreferences("board_credentials", 0)
    private val recoveryPreferences = application.getSharedPreferences("follow_recovery", 0)
    private val motorRecoveryPreferences = application.getSharedPreferences("motor_recovery", 0)
    private val remoteCredentials = RemoteCredentials(application)
    private val mutableRemoteVideo = MutableStateFlow<RemoteVideoEndpoint?>(null)
    internal val remoteVideo = mutableRemoteVideo.asStateFlow()
    private var followRecovery = FollowRecoverySnapshot.restore(recoveryPreferences.all)
    private val client = BoardSshClient()
    private val networkMonitor = BoardNetworkMonitor(application)
    private val reachabilityProbe = BoardReachabilityProbe()
    private val localPresenceDiagnostic = Diagnostics.newContext()
    private val remotePresenceDiagnostic = Diagnostics.newContext()
    private val networkStatus = BoardNetworkStatusStore(SystemClock::elapsedRealtime)
    val networkSnapshot = networkStatus.snapshot
    private val mutableNetworkRefreshing = MutableStateFlow(false)
    val networkRefreshing = mutableNetworkRefreshing.asStateFlow()
    private var networkForeground = false
    private var networkProbeJob: Job? = null
    private var networkProbeGeneration = 0L
    private var nextNetworkProbeAt = 0L
    private var networkProbeForceRetry = false
    private val ownerDiagnostic = Diagnostics.newContext()
    private var followDiagnostic = followRecovery?.context ?: DiagnosticContext(
        preferences.getString("last_follow_operation", null) ?: Diagnostics.newContext().operationId)
    private var driveDiagnostic = DiagnosticContext(preferences.getString("last_drive_operation", null)
        ?: Diagnostics.newContext().operationId)
    private var firstFollowStopSource: ControlStopSource? = null
    private var firstDriveStopSource: ControlStopSource? = null
    private val mutableState = MutableStateFlow(
        BoardUiState(
            host = preferences.getString("host", null)?.takeIf { it.isNotBlank() } ?: "172.16.16.82",
            port = preferences.getString("port", null) ?: "22",
            username = preferences.getString("username", null) ?: "topeet",
            password = credentialPreferences.getString("password", null).orEmpty(),
            remoteMode = preferences.getBoolean("remote_mode", false),
            motorActionUnconfirmed = runCatching {
                motorRecoveryPreferences.getBoolean(MOTOR_ACTION_RECOVERY_KEY, false)
            }.getOrDefault(true),
            remoteProfileLoading = true,
            followUncertain = followRecovery != null || preferences.getBoolean("follow_unconfirmed", false),
            followFirstFailure = followRecovery?.firstFailure,
            followMessage = if (followRecovery != null || preferences.getBoolean("follow_unconfirmed", false))
                "上次跟随进程退出未确认，连接后将自动检查控制占用" else "尚未启动自动跟随"
        )
    )
    val state = mutableState.asStateFlow()
    private val mutableMotorConfiguration = MutableStateFlow(MotorConfigurationUiState(connection =
        MotorSerialSettings(preferences.getInt("motor_slave", 1), preferences.getInt("motor_baud", 115200),
            preferences.getInt("motor_parity", 0)).takeIf { it.isValid } ?: MotorSerialSettings()))
    val motorConfiguration = mutableMotorConfiguration.asStateFlow()
    private val mutableParkingPage = MutableStateFlow(ParkingPageState())
    internal val parkingPage = mutableParkingPage.asStateFlow()
    private var parkingLeftEdited = false
    private var parkingRightEdited = false
    private var parkingObservationConnectionId: String? = null
    private var pendingUnifiedParking: ParkingRequest? = null
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
                    // Pairing creates a local link. A previously selected remote profile must not
                    // redirect this automatic SSH connection through the overseas server.
                    mutableState.update { it.withVerifiedPairingNetwork(address) }
                    preferences.edit().putBoolean("remote_mode", false).apply()
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
    private var motorActionJob: Job? = null
    private var activeMotorAction: MotorActionPlan? = null
    private var motorActionDiagnostic: DiagnosticContext? = null
    // Service commands must finish before a competing start/stop. Cancelling an SSH command
    // closes the shared session, so motor stop uses its own channel and never cancels this job.
    private val videoServiceMutex = Mutex()
    private var videoServiceJob: Job? = null
    private var disconnecting = false
    private var intentionalConnectionClose = false
    private var driveJob: Job? = null
    private val driveRecoveryGate = DriveRecoveryGate()
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
        observeNetworkHealth()
        viewModelScope.launch {
            try { reloadRemoteIdentity() }
            catch (_: Exception) {
                mutableState.update { it.copy(remoteProfileError = "远程凭据不可用，请重新导入连接配置") }
            } finally { mutableState.update { it.copy(remoteProfileLoading = false) } }
        }
        viewModelScope.launch {
            state.collect { current ->
                val connectionId = client.diagnosticConnectionId
                mutableParkingPage.update { parking ->
                    val stale = !current.connected || current.driveStarting || current.driveStopping ||
                        current.followBlocksControl || current.motorActionUnconfirmed || parkingObservationConnectionId != null &&
                        parkingObservationConnectionId != connectionId
                    parking.copy(connected = current.connected, mode = if (stale) null else parking.mode,
                        feedbackText = if (stale) null else parking.feedbackText,
                        message = if (stale && parking.mode != null) "连接或控制占用已变化，驻车模式待确认；电流为上次读值" else parking.message)
                }
                if (!current.connected || current.manualActive || current.followBlocksControl) {
                    mutableMotorConfiguration.update { motor ->
                        if (motor.snapshot == null || motor.requiresRead) motor else motor.copy(requiresRead = true,
                            message = "连接或控制状态已变化，请重新读取电机配置")
                    }
                }
            }
        }
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
                        it.withTransportState(false).copy(error = if (it.remoteMode)
                            "远程连接中断，请检查手机网络、小车网络及中转服务后重新连接。"
                            else "连接中断，请检查 Wi-Fi 后重新连接。")
                    }
                }
            }
        }
    }

    private fun activeDiagnostic(): DiagnosticContext = when {
        state.value.followActive -> followDiagnostic
        state.value.manualActive -> driveDiagnostic
        else -> motorActionDiagnostic ?: ownerDiagnostic.copy(connectionId = client.diagnosticConnectionId)
    }

    /** Observation is independent of the control socket and never sends a control command. */
    private fun observeNetworkHealth() {
        viewModelScope.launch {
            networkMonitor.state.collect { phone ->
                val previous = networkStatus.health.phone
                networkStatus.update { it.copy(phone = phone) }
                if (phone.revision != previous.revision) {
                    cancelNetworkProbe()
                    // A default-network/Internet change does not prove the local car link was lost.
                    // Keep bounded, authenticated evidence until a path-specific check/ACK replaces it.
                    nextNetworkProbeAt = 0L
                }
            }
        }
        viewModelScope.launch {
            var previous = state.value
            var settings: List<Any?>? = null
            state.collect { current ->
                // Compared only in memory: never log the credential or its digest.
                val currentSettings = listOf(current.host, current.port, current.username, current.password,
                    current.remoteProfile, current.remoteProfileError, current.remoteProfileLoading)
                if (settings != currentSettings) {
                    settings = currentSettings
                    cancelNetworkProbe()
                    networkStatus.update { it.copy(local = RouteHealth(), remote = RouteHealth(
                        status = if (current.remoteProfile == null && !current.remoteProfileLoading)
                            Reachability.NOT_CONFIGURED else Reachability.UNKNOWN)) }
                    nextNetworkProbeAt = 0L
                }
                if (previous.connected && !current.connected && !disconnecting && !intentionalConnectionClose) {
                    val route = if (previous.remoteMode) BoardRoute.REMOTE else BoardRoute.LOCAL
                    networkStatus.update { health ->
                        health.updateRoute(route, health.route(route).unavailable(SystemClock.elapsedRealtime()))
                    }
                    cancelNetworkProbe()
                    nextNetworkProbeAt = 0L
                    Diagnostics.event("network.control_connection_lost", activeDiagnostic(), fields = mapOf(
                        "route" to route.name, "phoneInternetValidated" to networkStatus.health.phone.internetValidated,
                        "phoneWifiAvailable" to networkStatus.health.phone.wifiAvailable), critical = true)
                }
                if (current.connected && current.followLastReplyElapsedMs != null &&
                    current.followLastReplyElapsedMs != previous.followLastReplyElapsedMs) {
                    markBoardReachable(if (current.remoteMode) BoardRoute.REMOTE else BoardRoute.LOCAL,
                        current.followLastReplyElapsedMs)
                }
                if (!canProbeNetwork(current) && networkProbeJob != null) cancelNetworkProbe()
                if (!canProbeNetwork(previous) && canProbeNetwork(current)) nextNetworkProbeAt = 0L
                previous = current
            }
        }
        viewModelScope.launch {
            while (true) {
                if (networkForeground) {
                    val now = SystemClock.elapsedRealtime()
                    networkStatus.tick()
                    if (now >= nextNetworkProbeAt && canProbeNetwork(state.value) && networkProbeJob?.isActive != true)
                        probeBoardRoutes()
                }
                delay(1_000)
            }
        }
    }

    private fun canProbeNetwork(current: BoardUiState) = !disconnecting && !current.busy &&
        !current.manualActive && !current.followActive && !current.followStopping &&
        !current.videoServiceBusy && !current.pairingBusy && !current.remoteProfileLoading

    private fun cancelNetworkProbe() {
        networkProbeGeneration++
        networkProbeJob?.cancel()
        networkProbeJob = null
        mutableNetworkRefreshing.value = false
        networkStatus.update { health -> health.copy(
            local = health.local.cancelChecking(), remote = health.remote.cancelChecking()) }
    }

    fun setNetworkForeground(foreground: Boolean) {
        networkForeground = foreground
        if (foreground) {
            networkMonitor.refresh()
            nextNetworkProbeAt = 0L
        } else cancelNetworkProbe()
    }

    fun refreshNetworkHealth() {
        if (!networkForeground || !canProbeNetwork(state.value)) return
        cancelNetworkProbe()
        networkProbeForceRetry = true
        networkMonitor.refresh()
        nextNetworkProbeAt = 0L
        Diagnostics.event("network.refresh_requested", ownerDiagnostic, source = "USER_REFRESH")
    }

    private fun markBoardReachable(route: BoardRoute, now: Long = SystemClock.elapsedRealtime()) {
        networkStatus.update { health -> health.updateRoute(route, health.route(route).online(now)) }
    }

    private fun probeBoardRoutes() {
        val input = state.value
        val generation = ++networkProbeGeneration
        val forceRetry = networkProbeForceRetry
        networkProbeForceRetry = false
        // Rate-limited and cancelled whenever control starts. No extra SSH work during driving.
        nextNetworkProbeAt = SystemClock.elapsedRealtime() + 20_000L
        mutableNetworkRefreshing.value = true
        networkProbeJob = viewModelScope.launch {
            try {
                for (route in BoardRoute.entries) {
                    currentCoroutineContext().ensureActive()
                    if (!networkForeground || !canProbeNetwork(state.value) || generation != networkProbeGeneration) return@launch
                    // Invalid credentials require an edit or explicit refresh, not recurring authentication attempts.
                    if (!forceRetry && networkStatus.health.route(route).failure in setOf(ReachabilityFailure.AUTHENTICATION,
                            ReachabilityFailure.IDENTITY, ReachabilityFailure.CONFIGURATION)) continue
                    val started = SystemClock.elapsedRealtime()
                    val configured = input.username.isNotBlank() && input.password.isNotEmpty() &&
                        if (route == BoardRoute.LOCAL) input.host.isNotBlank() && input.port.toIntOrNull() in 1..65535
                        else input.remoteProfile != null && input.remoteProfileError == null
                    if (!configured) {
                        networkStatus.update { it.updateRoute(route, RouteHealth(Reachability.NOT_CONFIGURED)) }
                        continue
                    }
                    networkStatus.update { it.updateRoute(route, it.route(route).checking()) }
                    val result = try {
                        val tls = if (route == BoardRoute.REMOTE) withContext(Dispatchers.IO) {
                            remoteCredentials.createTlsConfig()
                        } else null
                        val config = if (route == BoardRoute.REMOTE) BoardConnection(input.remoteProfile!!.host,
                            input.remoteProfile.controlPort, input.username.trim(), input.password, tls)
                        else BoardConnection(input.host.trim(), input.port.toInt(), input.username.trim(), input.password)
                        reachabilityProbe.check(config, if (route == BoardRoute.LOCAL) localPresenceDiagnostic else remotePresenceDiagnostic)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        RouteHealth().unavailable(SystemClock.elapsedRealtime(), ReachabilityFailure.CONFIGURATION)
                    }
                    currentCoroutineContext().ensureActive()
                    if (generation != networkProbeGeneration) return@launch
                    networkStatus.update { health ->
                        health.updateRoute(route, health.route(route).acceptProbe(result, started))
                    }
                    Diagnostics.event("network.board_probe", ownerDiagnostic, fields = mapOf("route" to route.name,
                        "result" to result.status.name, "failure" to result.failure?.name,
                        "durationMs" to SystemClock.elapsedRealtime() - started))
                }
            } finally {
                if (generation == networkProbeGeneration) {
                    mutableNetworkRefreshing.value = false
                    networkProbeJob = null
                    nextNetworkProbeAt = SystemClock.elapsedRealtime() + 15_000L
                }
            }
        }
    }

    /** Explicit recovery only: connect() checks occupation; it never restarts the previous session. */
    fun reconnectViaRemote() {
        if (disconnecting || !state.value.canReconnectViaRemote || wifiDirect.mutationPending) return
        Diagnostics.event("connection.remote_recovery_requested", ownerDiagnostic, source = "USER_REMOTE_RECOVERY",
            fields = mapOf("followUncertain" to state.value.followUncertain,
                "motorActionUnconfirmed" to state.value.motorActionUnconfirmed), critical = true)
        setRemoteMode(true)
        if (state.value.remoteMode) connect()
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

    fun updateHost(value: String) {
        if (!canEditConnection()) return
        editConnection { copy(host = value) }
        // Remember an explicit edit even if SSH is unavailable or authentication fails.
        preferences.edit().putString("host", value.trim().ifBlank { "172.16.16.82" }).apply()
    }
    fun updatePort(value: String) = editConnection { copy(port = value.filter(Char::isDigit).take(5)) }
    fun updateUsername(value: String) = editConnection { copy(username = value) }
    fun updatePassword(value: String) = editConnection { copy(password = value) }
    fun updateCommand(value: String) { mutableState.update { it.copy(command = value) } }

    fun setRemoteMode(remote: Boolean): Boolean {
        val reason = if (disconnecting) "正在断开连接，请等待完成。"
            else state.value.connectionSelectionBlockReason(wifiDirect.mutationPending)
        if (reason != null) {
            mutableState.update { it.copy(error = reason) }
            return false
        }
        if (state.value.remoteMode == remote) return true
        preferences.edit().putBoolean("remote_mode", remote).apply()
        mutableState.update { it.withSelectedRemoteMode(remote) }
        Diagnostics.event("connection.mode_changed", ownerDiagnostic,
            fields = mapOf("mode" to if (remote) "REMOTE" else "LOCAL"), critical = true)
        return true
    }

    private suspend fun reloadRemoteIdentity() {
        val identity = withContext(Dispatchers.IO) { remoteCredentials.getClientIdentity() }
        mutableRemoteVideo.value = identity?.let {
            RemoteVideoEndpoint(it.profile.host, it.profile.videoPort, it.profile.clientCertificateSha256,
                privateKey = { it.privateKey }, certificateChain = { it.certificateChain })
        }
        mutableState.update { it.copy(remoteProfile = identity?.profile, remoteProfileError = null) }
    }

    fun importRemoteConfiguration(uri: Uri) {
        if (!state.value.canSelectRemoteMode || wifiDirect.mutationPending) return
        mutableState.update { it.copy(busy = true, remoteProfileLoading = true, remoteProfileError = null,
            videoPreviewWanted = false) }
        operation = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IOException("无法打开连接配置")
                    input.use { remoteCredentials.importProfile(it) }
                }
                reloadRemoteIdentity()
                preferences.edit().putBoolean("remote_mode", true).apply()
                mutableState.update { it.copy(remoteMode = true, status = "远程配置已导入，请连接开发板", error = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                Diagnostics.event("remote.import_failed", ownerDiagnostic,
                    fields = mapOf("exceptionType" to failure.javaClass.simpleName), critical = true)
                mutableState.update { it.copy(remoteProfileError = "远程配置导入失败，请使用有效的 .carremote 文件") }
            } finally {
                mutableState.update { it.copy(remoteProfileLoading = false, busy = false) }
            }
        }
    }

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
        // Group addresses are temporary; keep the user's saved LAN address.
        editConnection { copy(host = actualAddress) }
    }

    private fun canEditConnection() = !state.value.busy && !state.value.connected && !state.value.manualActive &&
        !state.value.followActive && !state.value.videoServiceBusy

    private fun editConnection(edit: BoardUiState.() -> BoardUiState) {
        if (canEditConnection()) mutableState.update { it.edit().copy(error = null, videoPreviewWanted = false) }
    }

    fun permissionDenied() {
        Diagnostics.event("connection.permission_denied", ownerDiagnostic, critical = true)
        mutableState.update { it.copy(error = "请允许访问本地网络，App 才能连接开发板。") }
    }

    fun connect(viaWifiDirect: Boolean = false) {
        val input = state.value
        val wireless = wifiDirect.state.value
        val method = when {
            input.remoteMode -> ConnectionMethod.REMOTE
            viaWifiDirect -> ConnectionMethod.WIFI_DIRECT
            else -> ConnectionMethod.LAN
        }
        val reason = when {
            disconnecting -> "正在断开连接，请等待完成。"
            input.remoteMode && viaWifiDirect -> "远程连接不能同时使用 Wi-Fi 直连，请重新选择连接方式。"
            else -> input.connectionBlockReason(method, wireless, wifiDirect.mutationPending)
        }
        if (reason != null) {
            mutableState.update { it.copy(error = reason) }
            return
        }
        val directGroup = if (viaWifiDirect) DirectNetworkLease.from(wireless) else null
        if (viaWifiDirect && (directGroup == null || wifiDirectHostForConnection(wireless, input.host.trim()) == null)) {
            mutableState.update { it.copy(error = "请先创建手机 GO 无线组，等待开发板加入，再使用当前组内的开发板 IP；不能使用手机地址或原局域网地址。") }
            return
        }
        val host = input.connectionHost.trim()
        val username = input.username.trim()
        val port = input.connectionPort.toIntOrNull()
        if (host.isBlank() || host.any(Char::isWhitespace) || username.isBlank() ||
            port == null || port !in 1..65535 || input.password.isEmpty()
        ) {
            mutableState.update { it.copy(error = "请填写开发板 IP、用户名、密码，以及 1–65535 范围内的端口。") }
            return
        }
        val diagnostic = Diagnostics.newContext()
        intentionalConnectionClose = false
        val pairedAddress = input.host.trim().takeIf { !input.remoteMode && !viaWifiDirect && pairing.state.value.hotspotActive &&
            pairing.state.value.boardIp == it && !pairing.state.value.confirmationRequired }
        hotspotConnectionAddress = pairedAddress
        connectingHotspot = pairedAddress != null
        directConnectionGroup = directGroup
        connectingDirect = directGroup != null
        Diagnostics.protectSecret(input.password)
        Diagnostics.event("connection.requested", diagnostic,
            source = if (pairedAddress != null) "PAIRING_CONNECT" else "USER_CONNECT",
            fields = mapOf("host" to host, "port" to port, "mode" to if (input.remoteMode) "REMOTE" else "LOCAL"))
        mutableState.update {
            it.copy(host = if (input.remoteMode) it.host else host, username = username, busy = true, error = null, result = null,
                followConfiguration = null, followConfigurationError = null, status = "正在连接…",
                lastReadParkingSettings = null,
                videoServiceState = VideoServiceState.UNKNOWN, videoServiceError = null)
        }
        operation = viewModelScope.launch {
            var connectionAttempts = 1
            var sshEstablished = false
            try {
                val remoteTls = if (input.remoteMode) withContext(Dispatchers.IO) {
                    remoteCredentials.createTlsConfig()
                } else null
                // DHCP confirmation can precede reachability/SSH readiness. Retry connection
                // establishment only; never repeat a remote command or resume driving.
                val retryDelays = if (pairedAddress != null) listOf(750L, 1_500L) else listOf(300L)
                withInitialConnectionRetry(retryDelaysMillis = retryDelays, onRetry = { attempt ->
                    connectionAttempts = attempt
                    Diagnostics.event("connection.retry", diagnostic, fields = mapOf("attempt" to attempt,
                        "maxAttempts" to retryDelays.size + 1, "pairedHotspot" to (pairedAddress != null)))
                    mutableState.update { it.copy(status = "正在重新连接（$attempt/${retryDelays.size + 1}）…") }
                }) {
                    ensureDirectConnectionGroup(directGroup)
                    ensurePairingNetwork(pairedAddress)
                    client.connect(BoardConnection(host, port, username, input.password, tls = remoteTls), diagnostic)
                    ensureDirectConnectionGroup(directGroup)
                    ensurePairingNetwork(pairedAddress)
                }
                sshEstablished = true
                markBoardReachable(if (input.remoteMode) BoardRoute.REMOTE else BoardRoute.LOCAL)
                preferences.edit().apply {
                    if (!input.remoteMode && directGroup == null && pairedAddress == null)
                        putString("host", host).putString("port", port.toString())
                    putString("username", username)
                }.apply()
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
                        error = describeFailure(failure, connecting = !sshEstablished, attempts = connectionAttempts) +
                            if (pairedAddress != null && pairing.state.value.hotspotActive)
                                " 热点已保留，可在配对卡片中核对并重新连接。" else "")
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
        if (!state.value.canExecuteTerminal || command.isBlank()) return
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

    internal fun editParkingLeft(value: String) {
        parkingLeftEdited = true
        mutableParkingPage.update { it.copy(draftLeft = value.take(32)) }
    }

    internal fun editParkingRight(value: String) {
        parkingRightEdited = true
        mutableParkingPage.update { it.copy(draftRight = value.take(32)) }
    }

    internal fun refreshParking() = submitParkingRequest(ParkingRequest("read"))

    internal fun requestParkingMode(enabled: Boolean) = submitParkingRequest(ParkingRequest("mode", enabled = enabled))

    internal fun saveParkingCurrents() {
        val draft = parkingPage.value
        val left = parkingDraftRaw(draft.draftLeft)
        val right = parkingDraftRaw(draft.draftRight)
        if (left == null || right == null) {
            mutableParkingPage.update { it.copy(error = "请输入 0–60 A 的左右电流，最多两位小数") }
            return
        }
        // Freeze only this request. Later user edits remain independent of its readback.
        submitParkingRequest(ParkingRequest("currents", leftRaw = left, rightRaw = right))
    }

    private fun submitParkingRequest(request: ParkingRequest) {
        val current = state.value
        val blocked = when {
            parkingPage.value.busy -> "驻车操作正在进行，请等待本次结果"
            disconnecting || !current.connected || !client.isConnected -> "请先连接小车；已编辑的电流会保留"
            current.followBlocksControl -> "自动跟随正在占用或退出尚未确认，请先结束跟随并核对占用"
            current.motorActionUnconfirmed -> "上次电机操作尚未确认，请先核对控制占用"
            current.pairingBusy || current.pairingUnconfirmed -> "无线配网尚未结束，请先完成连接确认"
            current.driveStarting || current.driveStopping -> "手动控制正在启动或停用，请等待完成后重试"
            current.busy || current.videoServiceBusy || current.parkingUpdating -> "其他操作正在进行，请稍后重试"
            current.driveEnabled && driveChannel?.ready?.unifiedParkingSupported != true -> "开发板尚未支持统一驻车接口，请更新桥接程序"
            else -> null
        }
        if (blocked != null) {
            mutableParkingPage.update { it.copy(error = blocked) }
            return
        }
        mutableParkingPage.update { it.copy(busy = true, error = null,
            message = when (request.op) { "read" -> "正在读取驻车信息…"; "mode" -> "正在发送停止模式指令…"; else -> "正在读取当前上限并保存驻车电流…" }) }
        Diagnostics.event("parking.operation_requested", activeDiagnostic(),
            fields = mapOf("op" to request.op, "route" to if (current.driveEnabled) "manual_owner" else "idle"), critical = true)
        if (current.driveEnabled) {
            desiredWheels = WheelSpeeds()
            desiredGeneration += 1
            // A previously held input cannot resume when the operation completes.
            driveRecoveryGate.recovered(neutralRequest = false)
            pendingUnifiedParking = request
            mutableState.update { it.copy(driveLeft = 0, driveRight = 0, driveConfirmedStopped = false,
                parkingUpdating = true, driveRecoveryNeedsRelease = driveRecoveryGate.needsRelease) }
            driveWakeup.trySend(Unit)
        } else {
            val connectionId = client.diagnosticConnectionId
            val diagnostic = Diagnostics.newContext(connectionId)
            mutableState.update { it.copy(busy = true) }
            operation = viewModelScope.launch {
                try {
                    val reply = if (request.op == "currents") {
                        val baseline = client.parkingRequest(ParkingRequest("read"), diagnostic)
                        if (!baseline.ok) baseline else if (!parkingCurrentsWithinReadLimit(request, baseline)) null
                        else client.parkingRequest(request, Diagnostics.newContext(connectionId))
                    } else client.parkingRequest(request, diagnostic)
                    if (client.diagnosticConnectionId == connectionId && client.isConnected && !disconnecting) {
                        if (reply != null) acceptParkingReply(reply, request)
                    } else parkingOperationUnknown(request)
                } catch (cancelled: CancellationException) {
                    parkingOperationUnknown(request)
                    throw cancelled
                } catch (failure: Exception) {
                    parkingOperationUnknown(request)
                    Diagnostics.failure(diagnostic, "parking.operation_failed", failure)
                    mutableState.update { it.withTransportState(client.isConnected) }
                } finally {
                    mutableParkingPage.update { it.copy(busy = false) }
                    if (!disconnecting) mutableState.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun parkingCurrentsWithinReadLimit(request: ParkingRequest, baseline: ParkingReply): Boolean {
        val maximum = baseline.maxRaw
        val valid = maximum != null && request.leftRaw != null && request.rightRaw != null &&
            request.leftRaw <= maximum && request.rightRaw <= maximum
        if (!valid) {
            acceptParkingReply(baseline, ParkingRequest("read"))
            mutableParkingPage.update { it.copy(error = "左右驻车电流不能超过刚读取的最大输出电流；本次未写入") }
        }
        return valid
    }

    private fun acceptParkingReply(reply: ParkingReply, request: ParkingRequest) {
        parkingObservationConnectionId = client.diagnosticConnectionId
        val resultText = reply.results.joinToString("；") {
            (if (it.side == "left") "左" else "右") + when (it.status) {
                "verified" -> "已回读确认 ${parkingRawText(requireNotNull(it.actualRaw))} A"
                "not_attempted" -> "未写入"
                else -> "结果待核对"
            }
        }
        mutableParkingPage.update { page -> page.copy(mode = reply.mode,
            leftCurrentAmps = reply.leftRaw?.div(100.0) ?: page.leftCurrentAmps.takeUnless {
                request.op == "currents" && reply.writeMayHaveOccurred },
            rightCurrentAmps = reply.rightRaw?.div(100.0) ?: page.rightCurrentAmps.takeUnless {
                request.op == "currents" && reply.writeMayHaveOccurred },
            maxCurrentAmps = reply.maxRaw?.div(100.0) ?: page.maxCurrentAmps,
            draftLeft = if (!parkingLeftEdited && reply.leftRaw != null) parkingRawText(reply.leftRaw) else page.draftLeft,
            draftRight = if (!parkingRightEdited && reply.rightRaw != null) parkingRawText(reply.rightRaw) else page.draftRight,
            feedbackText = if (reply.leftRpm != null && reply.rightRpm != null)
                "轮速：左 ${reply.leftRpm} / 右 ${reply.rightRpm} RPM" else null,
            message = when {
                resultText.isNotBlank() -> resultText + if (reply.leftRaw == 0 || reply.rightRaw == 0) "；0 A 的一侧不能提供驻车电流" else ""
                reply.ok && request.op == "mode" ->
                    (if (reply.mode == true) "开启驻车" else "自由停止") + "指令已确认"
                !reply.ok -> "本次操作未完成，请查看错误信息"
                reply.mode == null -> "电流已读取"
                else -> "驻车信息已更新"
            }, error = if (reply.ok) null else parkingReplyError(reply)) }
        if (request.op == "currents" && (reply.ok || reply.writeMayHaveOccurred)) {
            mutableMotorConfiguration.update { it.copy(requiresRead = true,
                message = "驻车电流可能已更新，请重新读取电机配置；其他编辑草稿已保留") }
        }
        if (reply.leftRaw != null && reply.rightRaw != null && reply.maxRaw != null)
            mutableState.update { it.copy(lastReadParkingSettings = ParkingSettings(false,
                reply.leftRaw / 100.0, reply.rightRaw / 100.0, reply.maxRaw / 100.0)) }
        Diagnostics.event("parking.operation_completed", activeDiagnostic(), fields = mapOf("op" to request.op,
            "ok" to reply.ok, "modeSource" to reply.modeSource, "writeMayHaveOccurred" to reply.writeMayHaveOccurred,
            "results" to reply.results.map { "${it.side}:${it.status}" }), critical = true)
    }

    private fun parkingOperationUnknown(request: ParkingRequest?) {
        mutableParkingPage.update { it.copy(mode = null, busy = false, feedbackText = null,
            leftCurrentAmps = if (request?.op == "currents") null else it.leftCurrentAmps,
            rightCurrentAmps = if (request?.op == "currents") null else it.rightCurrentAmps,
            error = if (request?.op == "read") "驻车读取未完成，请重新连接后重试"
                else "驻车设置结果未确认，请重新连接并读取核对；不会自动重试") }
    }

    private fun observeDriveParking(ack: DriveAck) {
        val settings = ack.parking ?: return
        parkingObservationConnectionId = client.diagnosticConnectionId
        mutableParkingPage.update { page -> page.copy(
            mode = ack.parkingMode,
            leftCurrentAmps = settings.leftCurrentAmps, rightCurrentAmps = settings.rightCurrentAmps,
            maxCurrentAmps = settings.maxCurrentAmps,
            draftLeft = if (!parkingLeftEdited) parkingRawText((settings.leftCurrentAmps * 100).toInt()) else page.draftLeft,
            draftRight = if (!parkingRightEdited) parkingRawText((settings.rightCurrentAmps * 100).toInt()) else page.draftRight,
            feedbackText = ack.feedback?.let { "轮速：左 ${it.leftRpm} / 右 ${it.rightRpm} RPM" }) }
    }

    private suspend fun performOwnerParking(channel: BoardDriveChannel, request: ParkingRequest) {
        // READ stays read-only on the board. This explicit neutral request establishes
        // zero targets before any owner parking request; it does not assert physical rest.
        val neutral = channel.exchange(0, 0)
        handleDriveRecovery(neutral, neutralRequest = true)
        mutableState.update { it.withDriveFeedback(neutral).copy(driveConfirmedStopped =
            neutral.stopped && neutral.hasFreshFeedback(), parkingSettings = neutral.parking) }
        if (stopRequest != null) return
        suspend fun exchange(operation: ParkingRequest): ParkingReply {
            val ack = channel.parkingSettings(operation)
            handleDriveRecovery(ack, neutralRequest = true)
            mutableState.update { it.withDriveFeedback(ack).copy(driveConfirmedStopped =
                ack.stopped && ack.hasFreshFeedback(), parkingSettings = ack.parking) }
            return requireNotNull(ack.parkingResult)
        }
        val result = if (request.op == "currents") {
            val baseline = exchange(ParkingRequest("read"))
            if (!baseline.ok) baseline else if (!parkingCurrentsWithinReadLimit(request, baseline)) null
            else if (stopRequest == null) exchange(request) else null
        } else exchange(request)
        if (stopRequest == null && result != null) acceptParkingReply(result, request)
    }

    fun refreshMotorConfiguration() {
        refreshMotorConfiguration(preserveDrafts = false)
    }

    /** The parking editor reads the same guarded snapshot without clearing motor-page edits. */
    fun refreshParkingCurrentConfiguration() {
        refreshMotorConfiguration(preserveDrafts = true)
    }

    private fun refreshMotorConfiguration(preserveDrafts: Boolean) {
        if (disconnecting || !state.value.canReadMotorConfiguration || motorConfiguration.value.busy) return
        val recoveringAction = state.value.motorActionUnconfirmed
        val connection = motorConfiguration.value.connection
        val writeMode = motorConfiguration.value.writeMode
        val savedDrafts = if (preserveDrafts) motorConfiguration.value.draftValues else emptyMap()
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        mutableState.update { it.copy(busy = true, status = "正在读取电机配置…") }
        mutableMotorConfiguration.update { it.copy(reading = true, error = null) }
        Diagnostics.event("motor_config.read_requested", diagnostic,
            source = if (preserveDrafts) "USER_PARKING_CURRENT" else "USER_MOTOR_CONFIG")
        operation = viewModelScope.launch {
            try {
                val reply = client.motorConfigurationRequest(diagnostic = diagnostic, connection = connection)
                val recovered = recoveringAction && motorActionRecoveryReadConfirmed(reply) && persistMotorActionLock(false)
                mutableMotorConfiguration.value = MotorConfigurationUiState(snapshot = reply.snapshot,
                    connection = connection, writeMode = writeMode, draftValues = savedDrafts,
                    requiresRead = !reply.ok, message = when {
                        recovered -> "板端占用、零转速/输出及通讯控制来源已核对，恢复控制；这是读取观测，不代表物理停稳"
                        recoveringAction -> "电机操作状态仍未确认，控制保持锁定；需读取到空闲、零转速/输出及通讯控制来源"
                        reply.ok -> "已读取当前配置；电机状态为本次读取时的观测"
                        else -> null
                    },
                    error = reply.error ?: if (!reply.ok) "读取未完成，请重试" else null)
                rememberReadParkingSettings(reply.snapshot)
                Diagnostics.event("motor_config.read_completed", diagnostic, fields = mapOf("ok" to reply.ok), critical = true)
            } catch (cancelled: CancellationException) {
                if (recoveringAction) retainMotorActionLock()
                mutableMotorConfiguration.update { it.copy(requiresRead = true, error = "读取已取消，请重新读取") }
                throw cancelled
            } catch (failure: Exception) {
                if (recoveringAction) retainMotorActionLock()
                Diagnostics.failure(diagnostic, "motor_config.read_failed", failure)
                mutableMotorConfiguration.update { it.copy(requiresRead = true, error = describeFailure(failure)) }
                mutableState.update { it.withTransportState(client.isConnected) }
            } finally {
                mutableMotorConfiguration.update { it.copy(reading = false) }
                if (!disconnecting) mutableState.update { it.copy(busy = false,
                    status = if (client.isConnected) "已连接" else "连接已断开") }
            }
        }
    }

    fun editMotorConfiguration(field: MotorConfigurationField, text: String) {
        if (!state.value.canConfigureMotor || motorConfiguration.value.busy) return
        mutableMotorConfiguration.update { current ->
            if (current.snapshot == null || current.requiresRead) current else current.copy(
                draftValues = current.draftValues + (field to text.take(32)), error = null)
        }
    }

    fun discardMotorConfigurationChanges() {
        if (motorConfiguration.value.busy) return
        mutableMotorConfiguration.update { it.copy(draftValues = emptyMap()) }
    }

    fun setMotorWriteMode(mode: MotorConfigurationWriteMode) {
        if (!state.value.canConfigureMotor || motorConfiguration.value.busy) return
        mutableMotorConfiguration.update { it.copy(writeMode = mode, error = null) }
    }

    fun editMotorConnection(connection: MotorSerialSettings) {
        if (!connection.isValid || !state.value.canReadMotorConfiguration || motorConfiguration.value.busy) return
        if (connection == motorConfiguration.value.connection) return
        // This only changes the UART settings for the next request, never a controller register.
        preferences.edit().putInt("motor_slave", connection.slave).putInt("motor_baud", connection.baudRate)
            .putInt("motor_parity", connection.parityCode).apply()
        mutableMotorConfiguration.update { it.copy(connection = connection, snapshot = null,
            draftValues = emptyMap(), writeResults = emptyList(), actionResult = null,
            requiresRead = true, error = null, message = "连接参数已更新，请重新读取设备") }
    }

    fun applyMotorConfiguration(plan: MotorConfigurationWritePlan) {
        if (disconnecting || !state.value.canConfigureMotor ||
            plan != motorConfigurationWritePlan(motorConfiguration.value)) return
        executeMotorConfigurationWrite(plan, preserveDrafts = false)
    }

    /** Only these two reviewed persistent settings may be submitted from the parking page. */
    fun applyParkingCurrentConfiguration(plan: MotorConfigurationWritePlan) {
        if (disconnecting || !state.value.canConfigureMotor ||
            !parkingCurrentWritePlanIsCurrent(motorConfiguration.value, plan)) return
        executeMotorConfigurationWrite(plan, preserveDrafts = true)
    }

    private fun rememberReadParkingSettings(snapshot: MotorConfigurationSnapshot?) {
        val settings = parkingCurrentSettings(snapshot) ?: return
        mutableState.update { it.copy(lastReadParkingSettings = settings) }
    }

    private fun executeMotorConfigurationWrite(plan: MotorConfigurationWritePlan, preserveDrafts: Boolean) {
        val connection = motorConfiguration.value.connection
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        mutableState.update { it.copy(busy = true, status = "正在保存电机配置…") }
        mutableMotorConfiguration.update { it.copy(applying = true, requiresRead = true,
            writeResults = emptyList(), error = null, message = "正在写入并核对回读，请等待结果") }
        Diagnostics.event("motor_config.write_requested", diagnostic,
            source = if (preserveDrafts) "USER_PARKING_CURRENT" else "USER_MOTOR_CONFIG",
            fields = mapOf("fields" to plan.changes.map { it.field.id }, "count" to plan.changes.size,
                "writeMode" to plan.writeMode.name), critical = true)
        operation = viewModelScope.launch {
            try {
                val reply = client.motorConfigurationRequest(plan, diagnostic, connection = connection)
                val results = plan.changes.map { change ->
                    reply.results.singleOrNull { it.field == change.field } ?: MotorConfigurationWriteResult(
                        change.field, change.raw, if (reply.writeMayHaveOccurred)
                            MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN else MotorConfigurationWriteOutcome.NOT_ATTEMPTED)
                }
                mutableMotorConfiguration.update { current -> current.copy(snapshot = reply.snapshot ?: current.snapshot,
                    draftValues = if (reply.ok && !preserveDrafts) emptyMap() else current.draftValues,
                    requiresRead = !reply.ok || reply.reconnectRequired,
                    writeResults = results,
                    message = when {
                        reply.reconnectRequired -> "通讯设置可能已变化，请在通信页核对连接参数并重新读取；不会自动重复写入"
                        !reply.ok -> "本次写入未全部确认，请先重新读取；不会自动重试"
                        plan.writeMode == MotorConfigurationWriteMode.TEMPORARY -> "已临时写入并回读确认；重启后恢复保存值"
                        else -> "已写入并回读确认，使用驱动器的断电保存指令"
                    },
                    error = reply.error ?: if (!reply.ok) "请核对下方每项结果" else null) }
                rememberReadParkingSettings(reply.snapshot)
                Diagnostics.event("motor_config.write_completed", diagnostic,
                    fields = mapOf("ok" to reply.ok, "writeMayHaveOccurred" to reply.writeMayHaveOccurred,
                        "results" to results.map { "${it.field.id}:${it.outcome.name}" }), critical = true)
            } catch (failure: Exception) {
                // Once dispatched, a lost SSH reply cannot prove the controller did not write.
                mutableMotorConfiguration.update { it.copy(requiresRead = true,
                    writeResults = plan.changes.map { change -> MotorConfigurationWriteResult(change.field,
                        change.raw, MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN) },
                    message = "保存结果未确认，请重新连接并读取；不会自动重复写入",
                    error = if (failure is CancellationException) "保存等待已取消，驱动器参数可能已更改" else describeFailure(failure)) }
                Diagnostics.failure(diagnostic, "motor_config.write_unconfirmed", failure)
                if (failure is CancellationException) throw failure
                mutableState.update { it.withTransportState(client.isConnected) }
            } finally {
                mutableMotorConfiguration.update { it.copy(applying = false) }
                if (!disconnecting) mutableState.update { it.copy(busy = false,
                    status = if (client.isConnected) "已连接" else "连接已断开") }
            }
        }
    }

    fun applyMotorAction(plan: MotorActionPlan) {
        val current = motorConfiguration.value
        if (disconnecting || !state.value.canConfigureMotor || current.busy ||
            plan != motorActionPlan(current, plan.action, plan.value.toString(), plan.durationMs.toString())) return
        val connection = current.connection
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        // Latch synchronously before yielding, and durably before dispatching any command.
        mutableState.update { it.copy(busy = true, motorActionUnconfirmed = true,
            motorActionCancellable = motorActionNeedsVisibility(plan), status = "正在执行电机操作…") }
        activeMotorAction = plan
        motorActionDiagnostic = diagnostic
        mutableMotorConfiguration.update { it.copy(actioning = true, requiresRead = true,
            actionResult = null, error = null, message = "操作请求已确认，正在等待板端回执") }
        Diagnostics.event("motor_config.action_requested", diagnostic, source = "USER_MOTOR_ACTION",
            fields = mapOf("action" to plan.action.id, "durationMs" to plan.durationMs), critical = true)
        val job = viewModelScope.launch {
            try {
                check(persistMotorActionLock(true)) { "无法保存电机操作恢复状态，未发送指令；控制保持锁定" }
                currentCoroutineContext().ensureActive()
                val reply = client.motorActionRequest(plan, diagnostic = diagnostic, connection = connection)
                val confirmed = motorActionCompletionConfirmed(plan, reply) && persistMotorActionLock(false)
                mutableMotorConfiguration.update { it.copy(snapshot = reply.snapshot ?: it.snapshot,
                    actionResult = reply.actionResult, draftValues = emptyMap(), requiresRead = true,
                    message = when {
                        reply.reconnectRequired -> "操作可能改变通讯和配置，控制保持锁定；请核对连接参数后重新读取"
                        !confirmed -> "操作或停止收尾仍未确认，控制保持锁定；请主动读取电机状态核对"
                        else -> "操作回执已返回，请重新读取状态；回执不代表车轮停稳或相序学习已完成"
                    },
                    error = reply.error) }
                Diagnostics.event("motor_config.action_completed", diagnostic, critical = true,
                    fields = mapOf("action" to plan.action.id, "ok" to reply.ok,
                        "cleanupConfirmed" to reply.actionResult?.cleanupConfirmed,
                        "reconnectRequired" to reply.reconnectRequired))
            } catch (failure: Exception) {
                retainMotorActionLock()
                mutableMotorConfiguration.update { it.copy(requiresRead = true,
                    actionResult = MotorActionResult(plan.action, plan.value, plan.durationMs,
                        MotorActionOutcome.WRITE_OUTCOME_UNKNOWN, error = "操作回执未确认"),
                    message = "操作结果未确认，请重新连接并读取；不会自动重发",
                    error = if (failure is CancellationException) "等待已取消，板端操作状态未确认" else describeFailure(failure)) }
                Diagnostics.failure(diagnostic, "motor_config.action_unconfirmed", failure)
                mutableState.update { it.withTransportState(client.isConnected) }
                if (failure is CancellationException) throw failure
            } finally {
                activeMotorAction = null
                motorActionJob = null
                motorActionDiagnostic = null
                mutableMotorConfiguration.update { it.copy(actioning = false) }
                mutableState.update { it.copy(motorActionCancellable = false) }
                if (!disconnecting) mutableState.update { it.copy(busy = false,
                    status = if (client.isConnected) "已连接" else "连接已断开") }
            }
        }
        motorActionJob = job
        operation = job
    }

    /** A failed persistence write never authorizes dispatch or clears the in-memory latch. */
    private suspend fun persistMotorActionLock(locked: Boolean): Boolean {
        val saved = withContext(Dispatchers.IO) {
            runCatching { motorRecoveryPreferences.edit().putBoolean(MOTOR_ACTION_RECOVERY_KEY, locked).commit() }.getOrDefault(false)
        }
        currentCoroutineContext().ensureActive()
        if (saved) mutableState.update { it.copy(motorActionUnconfirmed = locked) }
        return saved
    }

    private suspend fun retainMotorActionLock() {
        mutableState.update { it.copy(motorActionUnconfirmed = true) }
        // Also re-arm if cancellation raced a successful commit that was clearing the latch.
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { motorRecoveryPreferences.edit().putBoolean(MOTOR_ACTION_RECOVERY_KEY, true).commit() }
        }
    }

    fun cancelMotorAction(reason: String = "用户取消限时电机操作",
        source: ControlStopSource = ControlStopSource.USER_STOP) {
        val plan = activeMotorAction
        if (!motorActionNeedsVisibility(plan) || !motorConfiguration.value.actioning || motorActionJob?.isActive != true) return
        Diagnostics.event("motor_config.action_cancel_requested", activeDiagnostic(), source = source.name,
            fields = mapOf("action" to plan?.action?.id), critical = true)
        // Cancellation closes this SSH execution. The board attempts bounded-action cleanup;
        // the phone deliberately keeps the durable lock until a new explicit read succeeds.
        mutableState.update { it.copy(motorActionUnconfirmed = true, motorActionCancellable = false) }
        mutableMotorConfiguration.update { it.copy(message = "$reason；停止状态待核对，控制保持锁定") }
        motorActionJob?.cancel(CancellationException(reason))
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
                videoPreviewWanted = when (videoState) {
                    VideoServiceState.RUNNING -> !disconnecting && !it.followBlocksControl
                    VideoServiceState.STOPPED -> false
                    VideoServiceState.UNKNOWN -> it.videoPreviewWanted
                },
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
            videoPreviewWanted = false,
            videoServiceMessage = if (enabled) "正在开启视频…" else "正在关闭视频…") }
        try {
            check(client.isConnected) { "SSH 已断开，无法确认视频服务状态，请重连后关闭视频。" }
            val videoState = client.setVideoServiceEnabled(enabled, diagnostic)
            Diagnostics.event("video.change_confirmed", diagnostic, fields = mapOf("videoState" to videoState.name))
            mutableState.update { it.copy(videoServiceState = videoState, videoServiceError = null,
                videoPreviewWanted = enabled && videoState == VideoServiceState.RUNNING &&
                    !disconnecting && !it.followBlocksControl,
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
        intentionalConnectionClose = true
        cancelNetworkProbe()
        val diagnostic = Diagnostics.newContext(client.diagnosticConnectionId)
        Diagnostics.event("connection.disconnect_requested", diagnostic, source = ControlStopSource.DISCONNECT.name, critical = true)
        disconnecting = true
        val previous = operation
        previous?.cancel()
        mutableState.update { it.copy(busy = true, status = "正在断开…", videoPreviewWanted = false) }
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
        networkProbeJob?.cancel()
        networkMonitor.close()
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
        driveRecoveryGate.reset()
        Diagnostics.event("drive.start_requested", driveDiagnostic, source = "USER_START",
            fields = mapOf("dryRun" to dryRun, "speedLimit" to current.speedLimit), critical = true)
        desiredWheels = WheelSpeeds()
        stopRequest = null
        pendingParking = null
        while (driveWakeup.tryReceive().isSuccess) { /* Discard signals from the previous session. */ }
        mutableState.update {
            it.copy(driveStarting = true, driveConfirmedStopped = false, driveError = null, driveFirstFailure = null,
                driveFeedbackLeftRpm = null, driveFeedbackRightRpm = null, driveFeedbackVerified = false,
                driveLastFeedbackNanos = 0L, driveFeedbackAgeAtReceiptMs = Long.MAX_VALUE,
                driveInterlock = null,
                driveStatus = "正在开启视频…", driveRecoveryNeedsRelease = false, driveFeedbackRecheck = false,
                driveStandstillPending = false,
                parkingSupported = false, parkingSettings = null,
                parkingUpdating = false, parkingStatus = "正在读取驻车设置…")
        }
        driveJob = viewModelScope.launch {
            var channel: BoardDriveChannel? = null
            var parkingModeReleased = false
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
                mutableState.update { it.copy(lastReadParkingSettings = activeChannel.ready.parking?.copy(enabled = false)) }
                Diagnostics.event("drive.ready", driveDiagnostic, fields = mapOf("dryRun" to activeChannel.ready.dryRun))
                while (stopRequest == null) {
                    // READY may have spent time in the network. Confirm a current stopped
                    // request before enabling input; the existing ACK/watchdog deadlines apply.
                    mutableState.update { it.copy(driveStatus = "正在确认实测停车反馈…") }
                    val initialStarted = System.nanoTime()
                    val initialAck = activeChannel.exchange(0, 0)
                    handleDriveRecovery(initialAck, neutralRequest = true)
                    markBoardReachable(if (state.value.remoteMode) BoardRoute.REMOTE else BoardRoute.LOCAL)
                    mutableState.update { it.withDriveFeedback(initialAck).copy(
                        driveConfirmedStopped = initialAck.stopped && initialAck.hasFreshFeedback()) }
                    if (state.value.driveConfirmedStopped && !initialAck.feedbackRecheck) {
                        Diagnostics.event("drive.initial_stop_acknowledged", driveDiagnostic, critical = true)
                        break
                    }
                    val remaining = driveHeartbeatWaitMs(initialStarted)
                    if (remaining > 0) withTimeoutOrNull(remaining) { driveWakeup.receive() }
                }
                // A stop arriving during the first ACK must keep input locked.
                if (stopRequest == null) {
                    mutableState.update {
                        it.copy(driveStarting = false, driveEnabled = true, driveConfirmedStopped = true,
                            drivePort = activeChannel.ready.port,
                            driveStatus = if (activeChannel.ready.dryRun) "模拟控制 · 不驱动电机" else "手动控制已就绪 · 松手停车",
                            parkingSupported = activeChannel.ready.parkingSupported,
                            parkingSettings = activeChannel.ready.parking,
                            parkingStatus = if (activeChannel.ready.parkingSupported) "驻车电流已读取 · 本页只切换停止模式"
                                else "开发板脚本不支持驻车设置，请更新 app_joystick_bridge.py")
                    }
                }
                while (true) {
                    val requestedStop = stopRequest
                    if (requestedStop != null) {
                        Diagnostics.event("drive.stop_sending", driveDiagnostic, source = firstDriveStopSource?.name,
                            fields = mapOf("stopKind" to requestedStop), critical = true)
                        val stopAck = if (requestedStop == "estop") activeChannel.exchange(0, 0, emergency = true)
                        else activeChannel.stopAndClose()
                        val measuredStopped = stopAck.stopped && stopAck.hasFreshFeedback()
                        Diagnostics.event("drive.stop_acknowledged", driveDiagnostic,
                            fields = mapOf("physicalStopped" to measuredStopped), critical = true)
                        parkingModeReleased = activeChannel.ready.parkingSupported && stopAck.parking?.enabled == false
                        mutableState.update { it.withDriveFeedback(stopAck).copy(driveConfirmedStopped = measuredStopped,
                            driveStatus = if (measuredStopped) stopReason else "$stopReason · 实测停车尚未确认") }
                        break
                    }
                    // All configuration shares this loop with heartbeats; a UI callback never
                    // starts a competing channel transaction or queues a movement behind it.
                    val unifiedRequest = pendingUnifiedParking
                    if (unifiedRequest != null) {
                        performOwnerParking(activeChannel, unifiedRequest)
                        pendingUnifiedParking = null
                        mutableState.update { it.copy(parkingUpdating = false) }
                        mutableParkingPage.update { it.copy(busy = false) }
                        continue
                    }
                    val requestedParking = pendingParking
                    if (requestedParking != null) {
                        val current = state.value
                        val blocked = when {
                            current.driveStandstillPending -> "STANDSTILL_PENDING"
                            current.driveFeedbackRecheck -> "FEEDBACK_RECHECK"
                            current.driveRecoveryNeedsRelease || driveRecoveryGate.needsRelease -> "CONTROL_RECOVERY"
                            !desiredWheels.stopped || !current.driveConfirmedStopped -> "STOP_UNCONFIRMED"
                            !current.hasFreshDriveFeedback() -> "FEEDBACK_STALE"
                            else -> null
                        }
                        if (blocked != null) {
                            // The last healthy ACK may have admitted this click while another
                            // heartbeat was in flight. Cancel the unsent mode change, not control.
                            pendingParking = null
                            if (!desiredWheels.stopped) desiredGeneration += 1
                            desiredWheels = WheelSpeeds()
                            mutableState.update { it.copy(parkingUpdating = false, driveLeft = 0, driveRight = 0,
                                parkingStatus = "本次驻车切换已取消：停车反馈待复核，确认后请重新切换") }
                            Diagnostics.event("parking.cancelled_before_send", driveDiagnostic,
                                source = blocked, critical = true)
                        } else {
                            val ack = client.configureParking(activeChannel, requestedParking, driveDiagnostic)
                            handleDriveRecovery(ack)
                            check(ack.stopped && ack.parking == requestedParking) { "开发板驻车设置回执不一致" }
                            Diagnostics.event("parking.confirmed", driveDiagnostic,
                                fields = mapOf("enabled" to requestedParking.enabled, "currentReadOnly" to true), critical = true)
                            pendingParking = null
                            mutableState.update {
                                it.withDriveFeedback(ack).copy(parkingSettings = ack.parking, parkingUpdating = false,
                                    driveConfirmedStopped = ack.stopped && ack.hasFreshFeedback(),
                                    parkingStatus = if (activeChannel.ready.dryRun) "模拟驻车设置已确认"
                                        else if (ack.hasFreshFeedback()) "停止模式已切换 · 电流参数未修改"
                                        else "停止模式已切换 · 等待新鲜轮速反馈")
                            }
                            // Recheck emergency/close before the next heartbeat, including when
                            // the user pressed stop while the configuration ACK was in flight.
                            continue
                        }
                    }
                    val target = desiredWheels
                    val sentGeneration = desiredGeneration
                    val heartbeatStarted = System.nanoTime()
                    val ack = activeChannel.exchange(target.left, target.right)
                    val wasStandstillPending = state.value.driveStandstillPending
                    handleDriveRecovery(ack, neutralRequest = target.stopped)
                    observeDriveParking(ack)
                    markBoardReachable(if (state.value.remoteMode) BoardRoute.REMOTE else BoardRoute.LOCAL)
                    val interlock = ack.interlock?.takeIf { it.blocked }
                    if (interlock != state.value.driveInterlock) Diagnostics.event("drive.infrared_interlock",
                        driveDiagnostic, fields = mapOf("blocked" to (interlock != null),
                            "directions" to interlock?.directions?.sorted()), critical = true)
                    mutableState.update {
                        it.withDriveFeedback(ack).copy(driveConfirmedStopped = ack.stopped && ack.hasFreshFeedback() && desiredWheels.stopped &&
                            (sentGeneration == desiredGeneration || ack.recoveryRequired),
                            driveInterlock = interlock,
                            parkingSettings = ack.parking,
                            parkingStatus = when {
                                it.driveStandstillPending && !ack.hasFreshFeedback() -> "停车等待中 · 轮速反馈待更新，电子驻车已暂停"
                                it.driveStandstillPending && !ack.stopped -> "正在减速，等待停车确认 · 电子驻车已暂停"
                                it.driveStandstillPending -> "车轮已停稳，松手后可重新开启电子驻车"
                                wasStandstillPending ->
                                    "停车已确认，电子驻车保持关闭；需要时请重新开启"
                                else -> it.parkingStatus
                            },
                            driveStatus = when {
                                !ack.hasFreshFeedback() -> "轮速反馈待更新 · 驻车设置暂不可用"
                                it.driveStandstillPending && !ack.stopped -> "正在减速，等待停车确认"
                                it.driveStandstillPending -> "车轮已停稳 · 松手后再操作"
                                it.driveFeedbackRecheck && !ack.stopped -> "轮速反馈复核中 · 保持停车"
                                it.driveFeedbackRecheck -> "反馈已恢复 · 松手后再操作"
                                driveRecoveryGate.needsRelease -> "网络恢复 · 目标已归零，请松手再操作"
                                ack.recoveryRequired -> "网络已恢复 · 目标已归零"
                                ack.targetStopped && !ack.stopped -> "目标已归零 · 等待实测停车"
                                interlock != null -> interlock.message()
                                activeChannel.ready.dryRun -> "模拟控制 · 不驱动电机"
                                else -> "手动控制已就绪 · 松手停车"
                            })
                    }
                    // Never queue motion or overlap requests. A slow ACK must not incur another
                    // full 100 ms sleep before sampling the current target/stop request.
                    val remaining = driveHeartbeatWaitMs(heartbeatStarted)
                    if (remaining > 0) withTimeoutOrNull(remaining) { driveWakeup.receive() }
                }
            } catch (cancelled: CancellationException) {
                Diagnostics.event("drive.cancelled", driveDiagnostic, source = firstDriveStopSource?.name,
                    fields = mapOf("stopAcknowledged" to false), critical = true)
                // An earlier neutral ACK is not acknowledgement of this stop/close request.
                mutableState.update { it.copy(driveConfirmedStopped = false,
                    driveFeedbackVerified = false, driveStandstillPending = false,
                    driveStatus = if (stopRequest != null) "$stopReason · 停车回执未确认" else "手动控制已中止 · 停车回执未确认",
                    parkingStatus = "驻车状态未确认，重新启用后读取") }
                throw cancelled
            } catch (failure: Exception) {
                if (failure is ParkingResultException) {
                    pendingUnifiedParking?.let { acceptParkingReply(failure.result, it) }
                    pendingUnifiedParking = null
                    mutableParkingPage.update { it.copy(busy = false) }
                }
                val first = Diagnostics.failure(driveDiagnostic, "drive.failed", failure)
                mutableState.update {
                    it.withTransportState(client.isConnected).copy(driveStatus = "手动控制已停用", driveConfirmedStopped = false,
                        driveFeedbackVerified = false, driveStandstillPending = false, driveFirstFailure = first,
                        parkingStatus = "驻车状态未确认，请重新启用手动控制",
                        driveError = if (it.remoteMode && failure.message.orEmpty().contains("watchdog", ignoreCase = true))
                            "远程指令未能在停车保护期限内到达，手动控制已退出。请检查网络延迟后重新启用。"
                        else "控制连接已中止：${failure.message.orEmpty().take(220)}。请检查小车后重新启用。")
                }
            } finally {
                channel?.close("drive_cleanup")
                driveChannel = null
                driveRecoveryGate.reset()
                desiredWheels = WheelSpeeds()
                pendingParking = null
                if (pendingUnifiedParking != null) parkingOperationUnknown(pendingUnifiedParking)
                pendingUnifiedParking = null
                mutableParkingPage.update { it.copy(mode = null, busy = false,
                    message = if (it.error != null) it.message else "手动控制已结束，驻车模式待确认；电流为上次读值") }
                // Motor stop/close always precedes service cleanup. Retain manual ownership
                // until cleanup finishes so follow cannot race a late camera-service start.
                mutableState.update { it.copy(driveStarting = false, driveEnabled = false,
                    driveStopping = true, driveLeft = 0, driveRight = 0, driveStandstillPending = false) }
                withContext(NonCancellable) {
                    if (client.isConnected) {
                        try { changeVideoService(false, driveDiagnostic) } catch (_: Exception) { /* Expose unconfirmed cleanup. */ }
                    }
                }
                mutableState.update {
                    it.withTransportState(client.isConnected).copy(driveStarting = false, driveEnabled = false, driveStopping = false,
                        parkingSupported = false, parkingSettings = null, parkingUpdating = false, driveInterlock = null,
                        driveFeedbackVerified = false,
                        driveRecoveryNeedsRelease = false, driveFeedbackRecheck = false, driveStandstillPending = false,
                        parkingStatus = if (parkingModeReleased && channel?.ready?.dryRun == true) "模拟驻车模式已退出，电流参数未修改"
                            else if (parkingModeReleased) "驻车模式已退出，电机电流参数未修改"
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

    private fun handleDriveRecovery(ack: DriveAck, neutralRequest: Boolean = false) {
        val before = state.value
        val hadMotionTarget = !desiredWheels.stopped
        mutableState.update { it.copy(driveFeedbackRecheck = ack.feedbackRecheck ||
            (it.driveFeedbackRecheck && driveRecoveryGate.needsRelease),
            driveStandstillPending = ack.standstillPending ||
                (it.driveStandstillPending && driveRecoveryGate.needsRelease),
            parkingSettings = if (ack.standstillPending) ack.parking else it.parkingSettings) }
        if (!ack.recoveryRequired) {
            driveRecoveryGate.healthyAck(neutralRequest)
            return
        }
        // Feedback can start during the neutral ACK of an older network recovery.
        // It must reset newly held input instead of being discarded as that duplicate ACK.
        if (!driveRecoveryGate.recovered(neutralRequest && !ack.feedbackRecheck)) return
        desiredWheels = WheelSpeeds()
        val changed = hadMotionTarget || before.driveFeedbackRecheck != ack.feedbackRecheck ||
            before.driveStandstillPending != ack.standstillPending ||
            before.driveRecoveryNeedsRelease != driveRecoveryGate.needsRelease
        if (changed) desiredGeneration += 1
        mutableState.update { it.copy(driveLeft = 0, driveRight = 0,
            driveRecoveryNeedsRelease = driveRecoveryGate.needsRelease) }
        if (changed) Diagnostics.event("drive.recovery_input_reset", driveDiagnostic,
            source = when {
                ack.standstillPending -> "STANDSTILL_PENDING"
                ack.feedbackRecheck -> "FEEDBACK_RECHECK"
                else -> "NETWORK_RECOVERY"
            },
            fields = mapOf("needsRelease" to driveRecoveryGate.needsRelease), critical = true)
    }

    fun moveJoystick(x: Float, forward: Float) {
        if (!state.value.driveEnabled || state.value.followBlocksControl || stopRequest != null) return
        if (state.value.parkingUpdating || pendingParking != null || pendingUnifiedParking != null) {
            driveRecoveryGate.input()
            driveRecoveryGate.recovered(neutralRequest = false)
            mutableState.update { it.copy(driveRecoveryNeedsRelease = true) }
            return
        }
        if (!driveRecoveryGate.input()) return
        if (state.value.driveFeedbackRecheck || state.value.driveStandstillPending) {
            // Input can begin after the final recovery ACK but before its healthy
            // successor. Latch the release now, rather than waiting for another
            // recovery ACK that may never arrive.
            driveRecoveryGate.recovered(neutralRequest = false)
            mutableState.update { it.copy(driveRecoveryNeedsRelease = true) }
            return
        }
        val previous = desiredWheels
        desiredWheels = joystickToWheels(x, forward, state.value.speedLimit)
        if (previous != desiredWheels) desiredGeneration += 1
        mutableState.update {
            it.copy(driveLeft = desiredWheels.left, driveRight = desiredWheels.right,
                driveFeedbackVerified = it.driveFeedbackVerified && desiredWheels.stopped,
                driveConfirmedStopped = it.driveConfirmedStopped && previous == desiredWheels && desiredWheels.stopped)
        }
        if (previous.stopped && !desiredWheels.stopped) driveWakeup.trySend(Unit)
    }

    fun releaseJoystick() {
        driveRecoveryGate.released()
        if (!desiredWheels.stopped) Diagnostics.event("drive.input_released", driveDiagnostic, critical = true)
        if (!desiredWheels.stopped) desiredGeneration += 1
        desiredWheels = WheelSpeeds()
        mutableState.update { it.copy(driveLeft = 0, driveRight = 0, driveRecoveryNeedsRelease = false,
            driveStatus = when {
                it.driveStandstillPending && !it.driveConfirmedStopped -> "已回中 · 等待停车确认"
                it.driveStandstillPending -> "已回中 · 等待停车恢复回执"
                it.driveFeedbackRecheck -> "已回中 · 等待反馈恢复回执"
                it.driveRecoveryNeedsRelease && it.driveEnabled -> "已回中 · 可以重新操作"
                else -> it.driveStatus
            }) }
        driveWakeup.trySend(Unit)
    }

    fun changeSpeedLimit(value: Int) {
        if (state.value.parkingUpdating || state.value.driveFeedbackRecheck || state.value.driveStandstillPending ||
            state.value.driveRecoveryNeedsRelease ||
            !desiredWheels.stopped || (state.value.driveEnabled &&
                (!state.value.driveConfirmedStopped || !state.value.hasFreshDriveFeedback()))) return
        mutableState.update { it.copy(speedLimit = value.coerceIn(10, 100)) }
        Diagnostics.event("drive.speed_limit_changed", activeDiagnostic(), fields = mapOf("rpm" to state.value.speedLimit))
    }

    fun setParkingEnabled(enabled: Boolean) {
        val settings = state.value.parkingSettings ?: return
        if (enabled && !settings.canEnable) {
            mutableState.update { it.copy(parkingStatus = "当前驻车电流不具备开启条件，请停用手动控制后检查电机配置") }
            return
        }
        requestParking(settings.copy(enabled = enabled))
    }

    fun changeParkingCurrent(value: Int) {
        Diagnostics.event("parking.current_change_rejected", driveDiagnostic,
            fields = mapOf("currentReadOnly" to true), critical = true)
        mutableState.update { it.copy(parkingStatus = "驻车电流只读，请停用手动控制后到电机配置页修改") }
    }

    private fun requestParking(settings: ParkingSettings) {
        val current = state.value
        if (!current.canEditParking || !desiredWheels.stopped || stopRequest != null ||
            settings == current.parkingSettings) return
        pendingParking = settings
        Diagnostics.event("parking.requested", driveDiagnostic,
            fields = mapOf("enabled" to settings.enabled, "currentReadOnly" to true), critical = true)
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
        if (stopRequest == "estop") {
            interruptDriveForActiveStop(reason, emergency, source)
            return
        }
        pendingParking = null
        stopRequest = if (emergency) "estop" else "close"
        stopReason = reason
        mutableState.update {
            it.copy(driveEnabled = false, driveStopping = true, driveStandstillPending = false,
                driveStatus = "正在发送停车指令…")
        }
        interruptDriveForActiveStop(reason, emergency, source)
        driveWakeup.trySend(Unit)
    }

    private fun interruptDriveForActiveStop(reason: String, emergency: Boolean, source: ControlStopSource) {
        if (shouldInterruptDriveForStop(emergency, source) && driveChannel?.interruptForActiveStop(source) == true) {
            // The admission gate also covers a request that has not reached write/ACK wait yet.
            // Closing the channel first interrupts blocking I/O; cancellation then unwinds the loop.
            mutableState.update { it.copy(driveConfirmedStopped = false,
                driveStatus = "$reason · 控制通道已中止，停车回执未确认") }
            driveJob?.cancel(DriveStopRequestedException(source.name))
        }
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
        mutableState.update { it.copy(followExecute = execute, followConfiguration = null, followConfigurationError = null) }
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
                val configuration = client.readFollowConfiguration(camera, options, diagnostic, execute = current.followExecute)
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
                    it.copy(followUncertain = false,
                        followMessage = "控制占用已释放；上次会话终态和车轮停稳仍未确认，可手动开始新会话")
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
        if (dryRun && current.followExecute) {
            mutableState.update { it.copy(followError = "纯协议模拟不能同时启用实车执行") }
            return
        }
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
            it.copy(followActive = true, followStopping = false, followStatus = null, videoPreviewWanted = false,
                followTerminal = false, followLastReplyElapsedMs = null, followStartCancelled = false,
                followError = null, followFirstFailure = null,
                followMessage = if (dryRun) "正在关闭视频并释放摄像头…" else "正在校验本次启动配置…")
        }
        followJob = viewModelScope.launch {
            var channel: BoardFollowChannel? = null
            var startAttempted = false
            var sessionAttempted = false
            var terminal = false
            var firstBoardFault: String? = null
            try {
                // No separate refresh action is required. Check the same immutable option/mode
                // snapshot that will create the session; this must report hardware_opened=false.
                if (!dryRun) {
                    Diagnostics.event("follow.preflight_requested", followDiagnostic,
                        fields = followOptionFields(current.followLaunchOptions) + mapOf(
                            "camera" to current.followCamera.argument, "execute" to current.followExecute), critical = true)
                    val configuration = client.readFollowConfiguration(current.followCamera,
                        current.followLaunchOptions, followDiagnostic, execute = current.followExecute)
                    mutableState.update { it.copy(followConfiguration = configuration, followConfigurationError = null) }
                    Diagnostics.event("follow.preflight_confirmed", followDiagnostic,
                        fields = mapOf("execute" to configuration.execute, "hardwareOpened" to false), critical = true)
                }
                if (followStopRequested) {
                    Diagnostics.event("follow.start_cancelled", followDiagnostic, source = firstFollowStopSource?.name, critical = true)
                    mutableState.update { it.copy(followMessage = "启动已取消，未建立跟随会话") }
                    return@launch
                }
                // A successful stop must confirm both video processes have exited before
                // anything can open the follow camera. Failure leaves follow unstarted.
                mutableState.update { it.copy(followMessage = "正在关闭视频并释放摄像头…") }
                changeVideoService(false, followDiagnostic)
                if (followStopRequested) {
                    Diagnostics.event("follow.start_cancelled", followDiagnostic, source = firstFollowStopSource?.name, critical = true)
                    mutableState.update { it.copy(followMessage = "启动已取消，未发送跟随启动指令") }
                    return@launch
                }
                mutableState.update { it.copy(followMessage = "正在建立跟随会话…") }
                // The bridge owns its lock before ready, even without a worker. A lost ready
                // or close reply therefore requires an inspect before another control session.
                sessionAttempted = true
                preferences.edit().putBoolean("follow_unconfirmed", true).apply()
                persistFollowRecovery()
                val control = client.openFollowChannel(current.followCamera, current.followExecute,
                    dryRun, current.followLaunchOptions, followDiagnostic)
                channel = control
                followChannel = control
                followDiagnostic = control.diagnosticContext
                Diagnostics.event("follow.ready", followDiagnostic,
                    fields = mapOf("dryRun" to control.ready.dryRun, "execute" to control.ready.execute))
                persistFollowRecovery()
                mutableState.update { it.copy(followStatus = control.lastStatus,
                    followLastReplyElapsedMs = SystemClock.elapsedRealtime()) }
                var stopSent = followStopRequested
                var lastSentElapsedMs = SystemClock.elapsedRealtime()
                var reply = if (followStopRequested) {
                    // Cancel an owned ready session with close, not an invented local terminal.
                    Diagnostics.event("follow.start_cancelled", followDiagnostic, source = firstFollowStopSource?.name, critical = true)
                    Diagnostics.event("follow.stop_sending", followDiagnostic, source = firstFollowStopSource?.name,
                        fields = mapOf("commandType" to "close"), critical = true)
                    mutableState.update { it.copy(followMessage = "启动已取消，正在确认会话关闭…") }
                    control.requestClose()
                } else {
                    startAttempted = true
                    Diagnostics.event("follow.start_sending", followDiagnostic, critical = true)
                    control.start()
                }
                var lastRunState: String? = null
                while (true) {
                    terminal = reply.terminal
                    if (reply.status.runState != lastRunState || terminal) {
                        Diagnostics.event(if (terminal) "follow.terminal_received" else "follow.state_changed", followDiagnostic,
                            fields = mapOf("runState" to reply.status.runState, "reason" to reply.status.reason,
                                "processExited" to reply.status.processExited,
                                "finalStatusPending" to reply.status.finalStatusPending,
                                "startupStage" to reply.status.startup?.stage,
                                "exitCode" to reply.status.exitCode,
                                "stopAcknowledged" to reply.status.stopAcknowledged,
                                "physicalStopped" to reply.status.physicalStopped), critical = terminal)
                        lastRunState = reply.status.runState
                    }
                    reply.status.fault?.let { fault ->
                        if (firstBoardFault == null) firstBoardFault = fault
                        val first = recordFollowFailure("follow.board_fault", IllegalStateException(fault))
                        mutableState.update { it.copy(followFirstFailure = first) }
                    }
                    mutableState.update {
                        it.copy(followStatus = reply.status.copy(fault = firstBoardFault ?: reply.status.fault),
                            followTerminal = terminal, followLastReplyElapsedMs = SystemClock.elapsedRealtime(),
                            followError = firstBoardFault ?: reply.status.fault,
                            followStopping = it.followStopping || reply.status.stopRequested,
                            followMessage = if (terminal && !startAttempted) "启动已取消，跟随会话已关闭"
                                else if (terminal) "跟随程序已退出；这不代表车轮已经停稳"
                                else if (reply.status.finalStatusPending) "进程已退出，正在等待最终关闭状态…"
                                else if (reply.status.runState == "FAULT") "跟随发生故障，继续确认进程退出结果…"
                                else if (it.followStopping || reply.status.stopRequested) "正在等待跟随进程退出…"
                                else "跟随状态已更新")
                    }
                    if (terminal) break
                    if (!followStopRequested || stopSent) {
                        // Target 500 ms between sends, without adding another 500 ms to RTT.
                        // There is still at most one request in flight and no retry queue.
                        val waitMs = (500L - (SystemClock.elapsedRealtime() - lastSentElapsedMs)).coerceAtLeast(0L)
                        if (waitMs > 0) withTimeoutOrNull(waitMs) { followWakeup.receive() }
                    }
                    lastSentElapsedMs = SystemClock.elapsedRealtime()
                    // UI intent never writes directly: wait for the current ACK's rotated token.
                    reply = if (followStopRequested && !stopSent) {
                        Diagnostics.event("follow.stop_sending", followDiagnostic, source = firstFollowStopSource?.name, critical = true)
                        stopSent = true
                        control.requestStop()
                    } else control.heartbeat()
                }
            } catch (cancelled: CancellationException) {
                Diagnostics.event("follow.cancelled", followDiagnostic, fields = mapOf("startAttempted" to startAttempted), critical = true)
                throw cancelled
            } catch (failure: Exception) {
                if (channel?.terminalReply == null) {
                    val first = recordFollowFailure("follow.failed", failure,
                        mapOf("startAttempted" to startAttempted, "terminalReceived" to terminal))
                    val lastStatus = (failure as? FollowSessionException)?.status ?: channel?.lastStatus ?: state.value.followStatus
                    mutableState.update {
                        it.copy(followStatus = lastStatus?.copy(fault = firstBoardFault ?: lastStatus.fault),
                            followFirstFailure = first, followError = Diagnostics.safeText(failure.message.orEmpty(), 350),
                            followMessage = if (sessionAttempted) "跟随会话异常，退出和停车结果未知"
                                else "启动准备未完成，未发送跟随启动指令")
                    }
                }
            } finally {
                // Cancellation may win the dispatcher hand-off after the I/O task validated
                // terminal. Keep that evidence instead of inventing an unconfirmed shutdown.
                channel?.terminalReply?.let { ended ->
                    if (!terminal) {
                        val endedStatus = ended.status.copy(fault = firstBoardFault ?: ended.status.fault)
                        val first = endedStatus.fault?.let {
                            recordFollowFailure("follow.board_fault", IllegalStateException(it))
                        }
                        mutableState.update { it.copy(followStatus = endedStatus, followTerminal = true,
                            followLastReplyElapsedMs = SystemClock.elapsedRealtime(),
                            followFirstFailure = first ?: it.followFirstFailure,
                            followError = endedStatus.fault,
                            followMessage = if (startAttempted) "跟随程序已退出；这不代表车轮已经停稳"
                                else "启动已取消，跟随会话已关闭") }
                        Diagnostics.event("follow.terminal_received", followDiagnostic,
                            fields = mapOf("runState" to endedStatus.runState, "processExited" to true,
                                "receivedBeforeCancellation" to true), critical = true)
                    }
                    terminal = true
                }
                channel?.close("follow_cleanup")
                followChannel = null
                val uncertain = !terminal && (sessionAttempted || followCleanupUnconfirmed)
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
        mutableState.update { it.copy(followStopping = true, followMessage = reason,
            followStartCancelled = it.followStartCancelled || it.followStatus?.runState in setOf(null, "STOPPED", "STARTING")) }
        followWakeup.trySend(Unit)
    }

    fun onControlSurfaceHidden(reason: String, source: ControlStopSource = ControlStopSource.CONTROL_SURFACE_HIDDEN) {
        Diagnostics.event("ui.control_surface_hidden", activeDiagnostic(), source = source.name,
            fields = mapOf("followActive" to state.value.followActive, "manualActive" to state.value.manualActive), critical = true)
        disableDrive(reason, source = source)
        stopFollow("$reason，正在请求结束跟随", source)
        cancelMotorAction(reason, source)
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
            generateSequence(failure as Throwable?) { it.cause }.take(8).any { it is javax.net.ssl.SSLException } ->
                "远程加密连接失败，请检查设备凭据、服务器证书和设备时间；连接未放行。"
            message.contains("Auth fail", true) -> "登录失败，请检查用户名和密码。"
            message.contains("hostkey", true) || message.contains("host key", true) ->
                "开发板身份校验失败。当前 App 只信任已配置的开发板；重装系统后需要更新主机公钥。"
            failure is SocketTimeoutException || message.contains("timeout", true) || message.contains("timed out", true) ->
                if (connecting && state.value.remoteMode) "远程连接超时（已尝试 $attempts 次）。请检查两端网络和服务器中转服务。"
                else if (connecting) "连接开发板超时（已尝试 $attempts 次）。请确认手机与开发板网络互通，IP 地址和端口正确。"
                else "连接或命令超时，连接已断开。请检查网络；板上已启动的程序可能仍在运行。"
            message.contains("refused", true) -> if (state.value.remoteMode)
                "远程入口拒绝连接，请检查中转服务和小车 SSH 服务。" else "开发板拒绝连接，请检查 SSH 服务和端口。"
            message.contains("unreachable", true) || message.contains("resolve", true) ->
                if (state.value.remoteMode) "无法访问远程服务器，请检查域名和手机网络。"
                else "无法访问开发板，请检查 IP 地址与手机 Wi-Fi。"
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
