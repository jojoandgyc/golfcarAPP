package com.example.followingcar

import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.followingcar.ui.theme.FollowingCarTheme
import java.util.Locale
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var boardViewModel: BoardViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        boardViewModel = ViewModelProvider(this)[BoardViewModel::class.java]
        boardViewModel.recordUiEvent("ui.activity_created", "restored=${savedInstanceState != null}")
        setContent {
            FollowingCarTheme { BoardScreen(boardViewModel) }
        }
    }

    override fun onPause() {
        if (::boardViewModel.isInitialized) {
            boardViewModel.recordUiEvent("ui.activity_paused")
            boardViewModel.setNetworkForeground(false)
            boardViewModel.onControlSurfaceHidden("App 已离开前台", ControlStopSource.APP_PAUSED)
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::boardViewModel.isInitialized) {
            boardViewModel.recordUiEvent("ui.activity_resumed")
            boardViewModel.setNetworkForeground(true)
            if (!boardViewModel.pairing.state.value.busy) boardViewModel.wifiDirect.refreshStatus()
            boardViewModel.pairing.refresh()
        }
    }

    override fun onDestroy() {
        if (::boardViewModel.isInitialized) boardViewModel.recordUiEvent("ui.activity_destroyed", "finishing=$isFinishing")
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::boardViewModel.isInitialized) {
            boardViewModel.recordUiEvent("ui.window_focus", "hasFocus=$hasFocus")
            if (!hasFocus) boardViewModel.onControlSurfaceHidden("界面失去焦点", ControlStopSource.WINDOW_FOCUS_LOST)
        }
    }
}

private enum class BoardPage(val label: String, val tag: String) {
    CONNECTION("连接", "pageConnection"),
    FOLLOW("自动跟随", "pageFollow"),
    JOYSTICK("手动", "pageJoystick"),
    MOTOR("电机配置", "pageMotorConfiguration"),
    TERMINAL("终端", "pageTerminal"),
}

// CAMERA remains readable for saved state from the previous version; it now shows DRIVE.
private enum class ManualSection { DRIVE, PARKING, CAMERA }

@Composable
private fun BoardScreen(viewModel: BoardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val networkSnapshot by viewModel.networkSnapshot.collectAsStateWithLifecycle()
    val networkHealth = networkSnapshot.health
    val networkNow = networkSnapshot.nowElapsedMs
    val networkRefreshing by viewModel.networkRefreshing.collectAsStateWithLifecycle()
    val wireless by viewModel.wifiDirect.state.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.state.collectAsStateWithLifecycle()
    val motorConfiguration by viewModel.motorConfiguration.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var selectedPageIndex by rememberSaveable("motor-configuration-pages") { mutableStateOf(BoardPage.CONNECTION.ordinal) }
    var useWifiDirect by rememberSaveable { mutableStateOf(false) }
    var showNetworkDetails by rememberSaveable { mutableStateOf(false) }
    var showPairingSetup by rememberSaveable { mutableStateOf(false) }
    val connectionScroll = rememberScrollState()
    val followScroll = rememberScrollState()
    val terminalScroll = rememberScrollState()
    DisposableEffect(view, state.driveEnabled, state.followActive) {
        view.keepScreenOn = state.driveEnabled || state.followActive
        onDispose { view.keepScreenOn = false }
    }
    val networkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) viewModel.connect(viaWifiDirect = useWifiDirect && !viewModel.state.value.remoteMode) else viewModel.permissionDenied()
    }
    val remoteImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importRemoteConfiguration)
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.wifiDirect.refreshStatus()
    }
    var pendingPairAddress by rememberSaveable { mutableStateOf(DEFAULT_PAIRING_BOARD_ADDRESS) }
    var pendingBluetoothCheck by rememberSaveable { mutableStateOf(false) }
    var pendingPairingRecovery by rememberSaveable { mutableStateOf(false) }
    val runPendingPairingAction: () -> Unit = {
        if (pendingPairingRecovery) viewModel.pairing.retryStatus()
        else if (pendingBluetoothCheck) viewModel.pairing.checkBluetooth(pendingPairAddress)
        else viewModel.startPairing(pendingPairAddress)
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.pairing.refresh()
        if (viewModel.pairing.state.value.bluetoothEnabled) runPendingPairingAction()
    }
    val pairAfterPermission: () -> Unit = {
        viewModel.pairing.refresh()
        if (viewModel.state.value.canManagePairing && !viewModel.pairing.state.value.busy) {
            if (viewModel.pairing.state.value.bluetoothEnabled) runPendingPairingAction()
            else enableBluetoothLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
        }
    }
    val pairingPermissions = listOf(android.Manifest.permission.BLUETOOTH_CONNECT,
        android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.NEARBY_WIFI_DEVICES) +
        if (Build.VERSION.SDK_INT >= 37) listOf(networkPermission) else emptyList()
    val pairingPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.wifiDirect.refreshStatus()
        if (pairingPermissions.all { permission -> ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED })
            pairAfterPermission()
        else viewModel.pairing.refresh()
    }
    val beginPairingAction: (String, Boolean) -> Unit = { address, checkOnly ->
        if (viewModel.state.value.canManagePairing && !viewModel.pairing.state.value.busy) {
            if (viewModel.setRemoteMode(false)) {
                useWifiDirect = false
                pendingPairAddress = address
                pendingBluetoothCheck = checkOnly
                pendingPairingRecovery = false
                keyboard?.hide()
                val missing = pairingPermissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) pairAfterPermission() else pairingPermissionLauncher.launch(missing.toTypedArray())
            }
        }
    }
    val pair: (String) -> Unit = { address -> beginPairingAction(address, false) }
    val recoverPairing: () -> Unit = {
        if (viewModel.state.value.canManagePairing && !viewModel.pairing.state.value.busy &&
            !viewModel.wifiDirect.mutationPending) {
            pendingPairingRecovery = true
            pendingBluetoothCheck = false
            keyboard?.hide()
            val missing = pairingPermissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) pairAfterPermission() else pairingPermissionLauncher.launch(missing.toTypedArray())
        }
    }
    LaunchedEffect(state.connected, state.remoteMode, pairing.hotspotActive, pairing.boardIp, state.host) {
        if (state.connected && !state.remoteMode && pairing.hotspotActive && pairing.boardIp == state.host)
            useWifiDirect = false
    }
    val connect: () -> Unit = {
        val current = viewModel.state.value
        val currentWireless = viewModel.wifiDirect.state.value
        val method = if (current.remoteMode) ConnectionMethod.REMOTE else if (useWifiDirect)
            ConnectionMethod.WIFI_DIRECT else ConnectionMethod.LAN
        if (current.connectionBlockReason(method, currentWireless, viewModel.wifiDirect.mutationPending) == null) {
            keyboard?.hide()
            if (!current.remoteMode && Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(context, networkPermission) != PackageManager.PERMISSION_GRANTED
            ) permissionLauncher.launch(networkPermission) else viewModel.connect(viaWifiDirect = useWifiDirect && !current.remoteMode)
        } else viewModel.connect(viaWifiDirect = useWifiDirect && !current.remoteMode)
    }
    val selectPage: (BoardPage) -> Unit = { next ->
        if (next.ordinal != selectedPageIndex) {
            viewModel.recordUiEvent("ui.page_changed", "${BoardPage.entries[selectedPageIndex].name}->${next.name}")
            val leavingControl = (selectedPageIndex == BoardPage.FOLLOW.ordinal && state.followActive) ||
                (selectedPageIndex == BoardPage.JOYSTICK.ordinal && state.manualActive) ||
                (selectedPageIndex == BoardPage.MOTOR.ordinal && state.motorActionCancellable)
            if (leavingControl) viewModel.onControlSurfaceHidden("已离开控制页面", ControlStopSource.PAGE_CHANGED)
            selectedPageIndex = next.ordinal
        }
    }

    val page = BoardPage.entries[selectedPageIndex]
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (page == BoardPage.CONNECTION) {
                Text("FollowingCar", Modifier.padding(horizontal = 20.dp, vertical = 10.dp).testTag("boardHeader"),
                    style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (page == BoardPage.TERMINAL) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp).testTag("boardHeader"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("FollowingCar", style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold)
                    Text("开发板控制台", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(
                    color = if (state.connected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("connectionSummary")
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(state.status, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.testTag("connectionStatus"))
                                Text((if (state.remoteMode) "远程 · " else "") + state.username + "@" + state.connectionHost + ":" + state.connectionPort,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (state.connected || state.busy) {
                                OutlinedButton(
                                    onClick = viewModel::disconnect,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = if (state.connected) MaterialTheme.colorScheme.onPrimaryContainer
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                    ),
                                    modifier = Modifier.testTag("disconnect")
                                ) {
                                    Text(if (state.busy) "取消并断开" else "断开连接")
                                }
                            }
                        }
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            if (state.motorActionUnconfirmed && page != BoardPage.MOTOR) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("电机操作状态待核对，驾驶、跟随、终端及视频保持锁定。可恢复网络并连接 SSH 后，到电机页主动读取恢复。",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("motorRecoveryBanner"))
                        TextButton(onClick = { selectPage(BoardPage.MOTOR) }) { Text("打开电机状态核对") }
                    }
                }
            }
            PrimaryTabRow(selectedTabIndex = selectedPageIndex) {
                BoardPage.entries.forEach { page ->
                    Tab(
                        selected = selectedPageIndex == page.ordinal,
                        onClick = { selectPage(page) },
                        text = { Text(when (page) {
                            BoardPage.FOLLOW -> "跟随"
                            BoardPage.MOTOR -> "电机"
                            else -> page.label
                        }, maxLines = 1, softWrap = false) },
                        modifier = Modifier.testTag(page.tag),
                    )
                }
            }
            if (page == BoardPage.JOYSTICK || page == BoardPage.FOLLOW || page == BoardPage.MOTOR) {
                BoardNetworkStatusCard(networkHealth, networkNow, state.connected, state.remoteMode,
                    canReconnectRemote = state.canReconnectViaRemote && !viewModel.wifiDirect.mutationPending,
                    refreshing = networkRefreshing, onRefresh = viewModel::refreshNetworkHealth,
                    onReconnectRemote = { viewModel.reconnectViaRemote(); if (viewModel.state.value.remoteMode) useWifiDirect = false },
                    modifier = Modifier.fillMaxWidth(), compact = true,
                    canRefresh = !state.busy && !state.manualActive && !state.followActive &&
                        !state.followStopping && !state.videoServiceBusy && !state.pairingBusy)
            }
            if (page == BoardPage.JOYSTICK) {
                JoystickCard(state, viewModel,
                    modifier = Modifier.fillMaxWidth().weight(1f).testTag("manualPage"))
            } else if (page == BoardPage.FOLLOW) {
                FollowPage(state = state,
                    onExecuteChange = viewModel::setFollowExecute,
                    onStart = { viewModel.startFollow() }, onStop = { viewModel.stopFollow() },
                    onInspect = viewModel::refreshFollowAvailability,
                    onOptionsChange = viewModel::setFollowLaunchOptions,
                    onCameraChange = viewModel::selectFollowCamera,
                    onRefreshConfiguration = viewModel::inspectFollowConfiguration,
                    onOpenConnection = { selectPage(BoardPage.CONNECTION) },
                    modifier = Modifier.fillMaxWidth().weight(1f), scrollState = followScroll)
            } else if (page == BoardPage.MOTOR) {
                MotorConfigurationPage(state = motorConfiguration, connected = state.connected,
                    accessAllowed = state.canReadMotorConfiguration,
                    blockedReason = when {
                        !state.connected -> "请先在连接页连接开发板"
                        state.manualActive -> "请先停用手动驾驶，再读取或修改电机配置"
                        state.followBlocksControl -> "自动跟随尚未确认结束，电机配置保持锁定"
                        state.pairingBusy || state.pairingUnconfirmed -> "网络配对操作尚未确认结束，请先完成连接恢复"
                        state.videoServiceBusy -> "视频操作进行中，请稍后再试"
                        state.busy && !motorConfiguration.busy -> "其他操作进行中，请稍后再试"
                        else -> null
                    },
                    onRead = viewModel::refreshMotorConfiguration,
                    onEdit = viewModel::editMotorConfiguration,
                    onDiscard = viewModel::discardMotorConfigurationChanges,
                    onApply = viewModel::applyMotorConfiguration,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    onConnectionChange = viewModel::editMotorConnection,
                    onWriteModeChange = viewModel::setMotorWriteMode,
                    onAction = viewModel::applyMotorAction,
                    actionUnconfirmed = state.motorActionUnconfirmed,
                    actionCancellationAllowed = state.motorActionCancellable,
                    onCancelAction = { viewModel.cancelMotorAction() })
            } else {
                val scroll = when (page) {
                    BoardPage.CONNECTION -> connectionScroll
                    BoardPage.FOLLOW -> followScroll
                    BoardPage.JOYSTICK -> error("Manual page has no scroll state")
                    BoardPage.TERMINAL -> terminalScroll
                    BoardPage.MOTOR -> error("Motor configuration owns its scroll state")
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    state.error?.takeIf { page != BoardPage.CONNECTION }?.let { error ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(error, Modifier.padding(16.dp).testTag("error"),
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    when (page) {
                        BoardPage.CONNECTION -> {
                            val method = if (state.remoteMode) ConnectionMethod.REMOTE else if (useWifiDirect)
                                ConnectionMethod.WIFI_DIRECT else ConnectionMethod.LAN
                            val mutationPending = viewModel.wifiDirect.mutationPending
                            val selectionReason = state.connectionSelectionBlockReason(mutationPending)
                            val canManagePairing = state.canManagePairing && !mutationPending
                            val canRefreshNetwork = !state.busy && !state.manualActive && !state.followActive &&
                                !state.followStopping && !state.videoServiceBusy && !state.pairingBusy
                            ConnectionSetupCard(state = state, method = method,
                                selectionBlockedReason = selectionReason,
                                connectionBlockedReason = state.connectionBlockReason(method, wireless, mutationPending),
                                onMethodChange = { selected ->
                                    if (viewModel.setRemoteMode(selected == ConnectionMethod.REMOTE)) {
                                        useWifiDirect = selected == ConnectionMethod.WIFI_DIRECT
                                        keyboard?.hide()
                                    }
                                },
                                onConnect = connect, onDisconnect = viewModel::disconnect, onCancel = viewModel::disconnect,
                                onImport = { if (viewModel.state.value.canSelectRemoteMode && !viewModel.wifiDirect.mutationPending)
                                    remoteImportLauncher.launch(arrayOf("application/json", "application/octet-stream", "*/*")) },
                                onHostChange = viewModel::updateHost, onPortChange = viewModel::updatePort,
                                onUsernameChange = viewModel::updateUsername, onPasswordChange = viewModel::updatePassword)
                            state.error?.let { error ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                                    Text(error, Modifier.padding(12.dp).testTag("error"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer)
                                }
                            }
                            ConnectionRecoveryCard(pairing = pairing, wireless = wireless,
                                canManagePairing = canManagePairing,
                                canRefreshWireless = !wireless.busy && !mutationPending,
                                onCheckPairing = recoverPairing, onCancelPairing = viewModel.pairing::cancel,
                                onRefreshWireless = viewModel.wifiDirect::refresh)
                            if (method == ConnectionMethod.WIFI_DIRECT) WifiDirectCard(
                                state = wireless, canManage = state.canManageWifiDirect,
                                lockedReason = when {
                                    state.pairingUnconfirmed -> "先在上方恢复并核对配网，再更改直连无线组。"
                                    state.pairingHotspotActive -> "配对热点仍在使用，请先在下方关闭热点，再创建直连无线组。"
                                    state.connected -> "先断开 SSH，才能更改无线组。"
                                    !state.canManageWifiDirect -> selectionReason ?: "控制或配网操作尚未结束。"
                                    state.followUncertain -> "可以恢复无线连接；旧控制仍保持锁定，SSH 重连后会检查占用。"
                                    else -> null
                                },
                                onPermission = { if (viewModel.state.value.canManageWifiDirect) nearbyPermissionLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES) },
                                onWifiSettings = { if (viewModel.state.value.canManageWifiDirect) context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                                onRefresh = viewModel.wifiDirect::refresh, onCreate = viewModel.wifiDirect::createGroup,
                                onRemove = viewModel.wifiDirect::removeGroup, onDiscover = viewModel.wifiDirect::discoverPeers,
                                onStopDiscovery = viewModel.wifiDirect::stopDiscovery, onInvite = viewModel.wifiDirect::invitePeer,
                                onUseAddress = viewModel::selectWifiDirectAddress)
                            val pairingNeedsRecovery = pairing.confirmationRequired || pairing.busy || pairing.recoveryLoading
                            if (method == ConnectionMethod.LAN && !pairingNeedsRecovery && !pairing.hotspotActive) {
                                TextButton(onClick = { showPairingSetup = !showPairingSetup },
                                    modifier = Modifier.testTag("connectionPairingDetails")) {
                                    Text(if (showPairingSetup) "收起手机热点配网" else "手机热点配网（可选）")
                                }
                            }
                            if (!pairingNeedsRecovery && (pairing.hotspotActive ||
                                    (method == ConnectionMethod.LAN && showPairingSetup))) {
                                PairingCard(state = pairing, canManage = canManagePairing,
                                    sshConnected = state.connected, onPair = pair, onCancel = viewModel.pairing::cancel,
                                    onCheck = recoverPairing, onStopHotspot = viewModel.pairing::stopHotspot,
                                    onCheckBluetooth = { address -> beginPairingAction(address, true) })
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(onClick = { showNetworkDetails = !showNetworkDetails },
                                        modifier = Modifier.testTag("connectionNetworkDetails")) {
                                        Text(if (showNetworkDetails) "收起网络检测详情" else "网络检测详情")
                                    }
                                    if (!showNetworkDetails) TextButton(onClick = viewModel::refreshNetworkHealth,
                                        enabled = canRefreshNetwork && !networkRefreshing,
                                        modifier = Modifier.testTag("networkRefresh")) {
                                        Text(if (networkRefreshing) "检测中…" else "刷新")
                                    }
                                }
                                BoardNetworkStatusCard(networkHealth, networkNow, state.connected, state.remoteMode,
                                    canReconnectRemote = state.canReconnectViaRemote && !mutationPending,
                                    refreshing = networkRefreshing, onRefresh = viewModel::refreshNetworkHealth,
                                    onReconnectRemote = { viewModel.reconnectViaRemote(); if (viewModel.state.value.remoteMode) useWifiDirect = false },
                                    compact = !showNetworkDetails, canRefresh = canRefreshNetwork)
                            }
                        }
                        BoardPage.FOLLOW -> Unit
                        BoardPage.JOYSTICK -> Unit
                        BoardPage.MOTOR -> Unit
                        BoardPage.TERMINAL -> {
                            DiagnosticLogCard(controlActive = state.manualActive || state.followActive || state.followStopping || motorConfiguration.actioning)
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("快捷查询", style = MaterialTheme.typography.titleMedium)
                                    val enabled = state.canExecuteTerminal
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { viewModel.execute(BoardViewModel.BOARD_STATUS_COMMAND) },
                                            enabled = enabled, modifier = Modifier.weight(1f)) { Text("系统状态") }
                                        OutlinedButton(onClick = { viewModel.execute(BoardViewModel.MEMORY_COMMAND) },
                                            enabled = enabled, modifier = Modifier.weight(1f)) { Text("内存") }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { viewModel.execute(BoardViewModel.DISK_COMMAND) },
                                            enabled = enabled, modifier = Modifier.weight(1f)) { Text("存储空间") }
                                        OutlinedButton(onClick = { viewModel.execute(BoardViewModel.NETWORK_COMMAND) },
                                            enabled = enabled, modifier = Modifier.weight(1f)) { Text("网络地址") }
                                    }
                                }
                            }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("命令控制", style = MaterialTheme.typography.titleMedium)
                                    OutlinedTextField(
                                        value = state.command, onValueChange = viewModel::updateCommand,
                                        label = { Text("Linux 命令") }, minLines = 2, maxLines = 5,
                                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("command"),
                                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
                                    )
                                    Text("每条命令独立执行，最长等待 15 秒。支持非交互命令，例如 whoami 或运行自己的控制脚本。",
                                        style = MaterialTheme.typography.bodySmall)
                                    Button(
                                        onClick = { keyboard?.hide(); viewModel.execute() },
                                        enabled = state.canExecuteTerminal && state.command.isNotBlank(),
                                        modifier = Modifier.fillMaxWidth().testTag("execute")
                                    ) { Text("执行命令") }
                                    if (state.busy && state.connected) {
                                        Text("断开连接不保证结束板上已启动的后台程序。",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            state.result?.let { result ->
                                Card(modifier = Modifier.fillMaxWidth().testTag("result")) {
                                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("执行结果 · 退出码 " + result.exitCode,
                                            style = MaterialTheme.typography.titleMedium)
                                        SelectionContainer {
                                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Text("$ " + result.command, fontFamily = FontFamily.Monospace,
                                                    style = MaterialTheme.typography.bodySmall)
                                                Text(result.stdout.ifBlank { "（无标准输出）" },
                                                    fontFamily = FontFamily.Monospace,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    modifier = Modifier.testTag("stdout"))
                                                if (result.stderr.isNotBlank()) {
                                                    Text("标准错误输出", fontWeight = FontWeight.Bold)
                                                    Text(result.stderr, fontFamily = FontFamily.Monospace,
                                                        style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                        if (result.truncated) Text("输出较长，标准输出和错误输出各保留前 32 KB。",
                                            style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JoystickCard(state: BoardUiState, viewModel: BoardViewModel, modifier: Modifier = Modifier) {
    val remoteVideo by viewModel.remoteVideo.collectAsStateWithLifecycle()
    val networkSnapshot by viewModel.networkSnapshot.collectAsStateWithLifecycle()
    val motorConfiguration by viewModel.motorConfiguration.collectAsStateWithLifecycle()
    var useDirectionPad by rememberSaveable { mutableStateOf(false) }
    var manualSection by rememberSaveable { mutableStateOf(ManualSection.DRIVE) }
    var editingParkingCurrent by rememberSaveable { mutableStateOf(false) }
    var waitingForModeStop by remember { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    LaunchedEffect(waitingForModeStop, state.driveConfirmedStopped, state.driveEnabled) {
        if (waitingForModeStop && (!state.driveEnabled || state.driveConfirmedStopped)) {
            waitingForModeStop = false
        }
    }
    fun chooseControlMode(directionPad: Boolean) {
        if (useDirectionPad == directionPad) return
        viewModel.recordUiEvent("ui.drive_input_mode", if (directionPad) "BUTTONS" else "JOYSTICK")
        waitingForModeStop = state.driveEnabled && !state.driveConfirmedStopped
        viewModel.releaseJoystick()
        useDirectionPad = directionPad
    }
    fun chooseManualSection(next: ManualSection) {
        if (manualSection == next) return
        viewModel.recordUiEvent("ui.manual_section", "${manualSection.name}->${next.name}")
        if (manualSection != ManualSection.PARKING) {
            waitingForModeStop = state.driveEnabled && !state.driveConfirmedStopped
            viewModel.releaseJoystick()
        }
        manualSection = next
        editingParkingCurrent = false
    }
    val controlling = state.driveEnabled || state.driveStarting || state.driveStopping
    val canAdjustSpeed = !state.parkingUpdating && !state.followBlocksControl &&
        !state.driveFeedbackRecheck && !state.driveRecoveryNeedsRelease &&
        (!controlling || (state.driveEnabled && state.driveLeft == 0 && state.driveRight == 0 && state.driveConfirmedStopped))
    val latestError = state.driveError ?: state.videoServiceError ?: state.error
    val errorText = state.driveFirstFailure?.let {
        "本次会话首次异常\n${it.time}\n${it.event}\n${it.message}" +
            (latestError?.let { last -> "\n\n最近操作错误\n$last" } ?: "")
    } ?: latestError
    Card(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = manualSection != ManualSection.PARKING,
                    onClick = { chooseManualSection(ManualSection.DRIVE) },
                    label = { Text("驾驶") }, modifier = Modifier.testTag("manualDriveTab"))
                FilterChip(selected = manualSection == ManualSection.PARKING,
                    onClick = { chooseManualSection(ManualSection.PARKING) },
                    label = { Text("驻车") }, modifier = Modifier.testTag("manualParkingTab"))
                Box(Modifier.weight(1f))
                if (errorText != null) TextButton(onClick = { showError = true },
                    modifier = Modifier.testTag("driveError")) { Text("故障") }
                TextButton(onClick = { showHelp = true }) { Text("说明") }
            }
            Text("${state.status} · ${state.driveStatus}",
                modifier = Modifier.fillMaxWidth().testTag("driveStatus"),
                style = MaterialTheme.typography.bodySmall, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            if (manualSection == ManualSection.PARKING) {
                Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (editingParkingCurrent) {
                        TextButton(onClick = { editingParkingCurrent = false },
                            modifier = Modifier.testTag("parkingCurrentBackButton")) { Text("返回驻车开关") }
                        ParkingCurrentEditor(boardState = state, configuration = motorConfiguration,
                            onRead = viewModel::refreshParkingCurrentConfiguration,
                            onApply = viewModel::applyParkingCurrentConfiguration,
                            modifier = Modifier.fillMaxWidth())
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = {
                                viewModel.recordUiEvent("ui.parking_current_editor", "OPEN")
                                editingParkingCurrent = true
                            }, modifier = Modifier.testTag("parkingCurrentSettingsButton")) { Text("设置驻车电流") }
                        }
                        ParkingControls(state = state, onEnabledChange = viewModel::setParkingEnabled)
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth().testTag("drivingCameraPanel")) {
                        // The board joins two 4:3 frames into one 8:3 video. Reserve the same
                        // space while disconnected so reconnecting never shifts the controls.
                        val panelHeight = maxWidth * 3 / 8 + 48.dp
                        BoardCameraPreview(state = state,
                            onVideoServiceEnabledChange = viewModel::setVideoServiceEnabled,
                            remoteVideo = if (state.remoteMode) remoteVideo else null,
                            phoneNetwork = networkSnapshot.health.phone,
                            modifier = Modifier.fillMaxWidth().height(panelHeight))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = !useDirectionPad, onClick = { chooseControlMode(false) },
                                label = { Text("摇杆") }, modifier = Modifier.testTag("controlModeJoystick"))
                            FilterChip(selected = useDirectionPad, onClick = { chooseControlMode(true) },
                                label = { Text("按键") }, modifier = Modifier.testTag("controlModeButtons"))
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedButton(onClick = { viewModel.changeSpeedLimit(state.speedLimit - 10) },
                                enabled = state.speedLimit > 10 && canAdjustSpeed,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "降低最高转速" },
                                contentPadding = PaddingValues(0.dp)) { Text("−") }
                            Column(Modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("最高转速", style = MaterialTheme.typography.labelSmall)
                                Text("${state.speedLimit} RPM", style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold)
                            }
                            OutlinedButton(onClick = { viewModel.changeSpeedLimit(state.speedLimit + 10) },
                                enabled = state.speedLimit < 100 && canAdjustSpeed,
                                modifier = Modifier.size(48.dp).semantics { contentDescription = "提高最高转速" },
                                contentPadding = PaddingValues(0.dp)) { Text("＋") }
                        }
                    }
                    val inputEnabled = state.driveEnabled && !state.driveStopping &&
                        !state.parkingUpdating && !state.followBlocksControl && !waitingForModeStop
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center) {
                        val controlSize = minOf(maxWidth, maxHeight, 256.dp)
                        if (useDirectionPad) {
                            DirectionPadControl(enabled = inputEnabled,
                                onMove = viewModel::moveJoystick,
                                onRelease = viewModel::releaseJoystick,
                                keySize = (controlSize - 16.dp).coerceAtLeast(0.dp) / 3)
                        } else {
                            JoystickControl(enabled = inputEnabled,
                                onMove = viewModel::moveJoystick,
                                onRelease = viewModel::releaseJoystick,
                                controlSize = controlSize)
                        }
                    }
                    Text("目标转速：左 ${state.driveLeft} / 右 ${state.driveRight} RPM",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("driveSpeeds"))
                    Text(when {
                        waitingForModeStop -> "正在确认停车，确认后可继续操控。"
                        state.followBlocksControl -> "自动跟随尚未释放，手动控制暂不可用。"
                        useDirectionPad -> "按住方向键移动，松手或按停止键停车。"
                        else -> "推动摇杆行驶，松手停车。"
                    }, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            if (!controlling) {
                Button(onClick = { if (editingParkingCurrent) editingParkingCurrent = false else viewModel.enableDrive() },
                    enabled = editingParkingCurrent || state.canEnableDrive,
                    modifier = Modifier.fillMaxWidth().testTag(if (editingParkingCurrent) "parkingCurrentDone" else "enableDrive")) {
                    Text(if (editingParkingCurrent) "返回驻车开关" else "启用手动控制")
                }
            } else {
                Button(onClick = { viewModel.disableDrive("已急停 · 重新启用后可操作", emergency = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth().testTag("emergencyStop")) {
                    Text("急停并停用手动控制")
                }
            }
        }
    }
    if (showError && errorText != null) AlertDialog(onDismissRequest = { showError = false },
        title = { Text("控制故障") }, text = { Text(errorText) },
        confirmButton = { TextButton(onClick = { showError = false }) { Text("关闭") } })
    if (showHelp) AlertDialog(onDismissRequest = { showHelp = false },
        title = { Text("手动控制说明") },
        text = { Text("启用前请退出开发板上的键盘遥控或跟随程序。摇杆上推前进、下拉后退、左右转向，推得越远速度越快。方向键按住行驶，松手停车。切换控制方式或页面会停车；离开 App 会停用手动控制。驾驶页上方视频左侧是 RGB、右侧是深度伪彩，可用视频按钮单独开关。启用手动控制会自动开启视频；停用手动控制会关闭视频。启动自动跟随前会先关闭视频，释放摄像头。") },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("关闭") } })
}

@Composable
internal fun ParkingControls(
    state: BoardUiState,
    // Optional monotonic time keeps offline expiry tests deterministic; production uses its clock.
    nowNanos: Long? = null,
    onEnabledChange: (Boolean) -> Unit,
) {
    var clockTick by remember { mutableLongStateOf(System.nanoTime()) }
    LaunchedEffect(state.driveEnabled, nowNanos) {
        if (state.driveEnabled && nowNanos == null) while (true) {
            clockTick = System.nanoTime()
            delay(100L)
        }
    }
    // Include the current clock on every ACK recomposition; a new sample must not be
    // compared against the previous tick and briefly become an apparent clock reversal.
    val observedNow = nowNanos ?: maxOf(clockTick, System.nanoTime())
    val settings = state.parkingSettings
    // Historical readbacks are display-only. In particular, never restore their enabled bit.
    val displayedSettings = settings ?: state.lastReadParkingSettings
    val historicalSettings = settings == null && displayedSettings != null
    val enabled = settings?.enabled == true
    val canSwitch = state.canEditParkingAt(observedNow) && (enabled || settings?.canEnable == true)
    val feedbackKnown = state.driveFeedbackLeftRpm != null && state.driveFeedbackRightRpm != null
    val feedbackFresh = state.hasFreshDriveFeedback(observedNow)
    val feedbackStopped = feedbackKnown && state.driveFeedbackLeftRpm == 0 &&
        state.driveFeedbackRightRpm == 0
    val availability = when {
        !state.connected -> "未连接小车"
        state.driveStarting -> "手动控制正在启动"
        state.driveStopping -> "手动控制正在停用"
        !state.driveEnabled -> "未启用手动控制"
        !state.parkingSupported -> "板端驻车安全接口不可用"
        settings == null -> "尚未读取驻车配置"
        state.parkingUpdating -> "正在切换驻车"
        state.followBlocksControl -> "自动跟随占用或状态待确认"
        state.motorActionUnconfirmed -> "电机状态待核对"
        state.busy -> "其他操作正在进行"
        state.driveFeedbackRecheck -> if (state.driveConfirmedStopped) "反馈已恢复，请松手后再操作" else "轮速反馈复核中，保持停车"
        state.driveRecoveryNeedsRelease -> "请松手完成控制恢复"
        !enabled && !settings.canEnable -> parkingCurrentIssue(settings)
        state.driveLeft != 0 || state.driveRight != 0 -> "目标转速尚未回零"
        !feedbackKnown -> "等待停车确认"
        !state.driveFeedbackVerified -> "轮速反馈未验证，等待停车确认"
        !feedbackFresh -> "轮速反馈已过期"
        !feedbackStopped || !state.driveConfirmedStopped -> "等待停车确认"
        !canSwitch -> "暂不能切换驻车"
        enabled -> "已开启，可关闭"
        else -> "可开启电子驻车"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("电子驻车", style = MaterialTheme.typography.titleMedium)
                Text(availability, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("parkingAvailability"),
                    color = if (canSwitch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = enabled,
                onCheckedChange = { next ->
                    // Recheck at the click as well as the display tick; expired evidence
                    // must not dispatch a toggle even in the final frame before disabling.
                    if (state.canEditParkingAt(nowNanos ?: System.nanoTime()) &&
                        (enabled || settings?.canEnable == true)) onEnabledChange(next)
                },
                enabled = canSwitch,
                modifier = Modifier.testTag("parkingSwitch").semantics { contentDescription = "驻车开关" }
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when {
                historicalSettings -> "上次读取（非实时）"
                settings == null -> "尚未读取驻车电流"
                else -> "已读取的驻车电流上限"
            },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingCurrentSource"))
            Row(Modifier.fillMaxWidth().testTag("parkingCurrent"), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("左：${parkingAmps(displayedSettings?.leftCurrentAmps)} A", fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("parkingLeftCurrent"))
                Text("右：${parkingAmps(displayedSettings?.rightCurrentAmps)} A", fontWeight = FontWeight.Bold,
                    modifier = Modifier.testTag("parkingRightCurrent"))
            }
            Text("最大输出电流：${parkingAmps(displayedSettings?.maxCurrentAmps)} A",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingMaxCurrent"))
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when {
                state.driveStarting -> "手动控制正在启动，等待轮速确认"
                state.driveStopping -> "正在停用手动控制"
                !state.driveEnabled -> "手动控制未启用，无实时转速目标"
                state.driveLeft == 0 && state.driveRight == 0 && !feedbackFresh -> "目标已回零 · 等待新鲜停车反馈"
                else -> "目标转速：左 ${state.driveLeft} / 右 ${state.driveRight} RPM"
            },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingTargetSpeeds"))
            Text(when {
                !feedbackKnown -> "最近轮速反馈：尚无可信读数"
                !state.driveFeedbackVerified -> "最近轮速反馈：左 ${state.driveFeedbackLeftRpm} / 右 ${state.driveFeedbackRightRpm} RPM · 未验证或已过期"
                !feedbackFresh -> "最近轮速反馈：左 ${state.driveFeedbackLeftRpm} / 右 ${state.driveFeedbackRightRpm} RPM · 已过期"
                state.driveFeedbackRecheck && !state.driveConfirmedStopped -> "最近轮速反馈：左 ${state.driveFeedbackLeftRpm} / 右 ${state.driveFeedbackRightRpm} RPM · 复核中，保持停车"
                state.driveFeedbackRecheck -> "最近轮速反馈已回零，松手后再操作"
                !feedbackStopped -> "最近轮速反馈：左 ${state.driveFeedbackLeftRpm} / 右 ${state.driveFeedbackRightRpm} RPM · 尚未停稳"
                !state.driveConfirmedStopped -> "最近轮速反馈已回零，正在复核停车"
                else -> "最近轮速反馈：左 0 / 右 0 RPM · 已确认停车"
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingFeedback"))
        }
        Text(when {
            historicalSettings && displayedSettings != null -> "上次左 ${parkingAmps(displayedSettings.leftCurrentAmps)} A、右 ${parkingAmps(displayedSettings.rightCurrentAmps)} A、上限 ${parkingAmps(displayedSettings.maxCurrentAmps)} A；非实时。" +
                if (displayedSettings.canEnable) "启用手动控制后重新读取核对。" else "按上次读值，${parkingCurrentIssue(displayedSettings)}。请到电机页读取并核对。"
            settings != null && !settings.canEnable ->
                (if (enabled) "可关闭驻车；再次开启前需核对电流。" else parkingCurrentIssue(settings) + "。") +
                    "左 ${parkingAmps(settings.leftCurrentAmps)} A、右 ${parkingAmps(settings.rightCurrentAmps)} A、上限 ${parkingAmps(settings.maxCurrentAmps)} A。先停用手动控制，再到电机页读取并调整。"
            !state.driveEnabled -> "启用手动控制后读取驻车状态。"
            !state.parkingSupported -> "板端驻车安全接口不可用，暂不能切换。"
            settings == null -> "尚未读取电机驻车配置，暂不能切换。"
            state.parkingUpdating -> "正在确认驻车切换，请稍候；需要停止时可使用下方急停。"
            !state.canEditParkingAt(observedNow) -> "请松手停车，等待新鲜的实测停车确认及当前操作结束。"
            else -> "沿用当前电机配置，开关不修改电流参数。调整电流请先停用手动控制，再到电机页操作。"
        }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingHint"))
        Text(state.parkingStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingStatus"))
        if (state.parkingUpdating) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

private fun parkingAmps(value: Double?): String = when {
    value == null -> "—"
    !value.isFinite() || value < 0.0 -> "无效"
    value >= 1_000_000 -> String.format(Locale.ROOT, "%.2e", value)
    else -> String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
}

private fun parkingCurrentIssue(settings: ParkingSettings): String = when {
    settings.leftCurrentAmps == 0.0 && settings.rightCurrentAmps == 0.0 -> "左右驻车电流为 0，无法开启"
    settings.leftCurrentAmps == 0.0 -> "左侧驻车电流为 0，无法开启"
    settings.rightCurrentAmps == 0.0 -> "右侧驻车电流为 0，无法开启"
    settings.maxCurrentAmps == 0.0 -> "最大输出电流为 0，无法开启"
    else -> "驻车电流超出允许上限，无法开启"
}
