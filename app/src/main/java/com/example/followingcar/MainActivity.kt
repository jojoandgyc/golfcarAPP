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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.followingcar.ui.theme.FollowingCarTheme

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
            boardViewModel.onControlSurfaceHidden("App 已离开前台", ControlStopSource.APP_PAUSED)
        }
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (::boardViewModel.isInitialized) {
            boardViewModel.recordUiEvent("ui.activity_resumed")
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
    TERMINAL("终端", "pageTerminal"),
}

// CAMERA remains readable for saved state from the previous version; it now shows DRIVE.
private enum class ManualSection { DRIVE, PARKING, CAMERA }

@Composable
private fun BoardScreen(viewModel: BoardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val wireless by viewModel.wifiDirect.state.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var selectedPageIndex by rememberSaveable { mutableStateOf(BoardPage.CONNECTION.ordinal) }
    var useWifiDirect by rememberSaveable { mutableStateOf(false) }
    val connectionScroll = rememberScrollState()
    val followScroll = rememberScrollState()
    val terminalScroll = rememberScrollState()
    DisposableEffect(view, state.driveEnabled, state.followActive) {
        view.keepScreenOn = state.driveEnabled || state.followActive
        onDispose { view.keepScreenOn = false }
    }
    val networkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) viewModel.connect(viaWifiDirect = useWifiDirect) else viewModel.permissionDenied()
    }
    val nearbyPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.wifiDirect.refreshStatus()
    }
    var pendingPairAddress by rememberSaveable { mutableStateOf(DEFAULT_PAIRING_BOARD_ADDRESS) }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.pairing.refresh()
        if (viewModel.pairing.state.value.bluetoothEnabled) viewModel.startPairing(pendingPairAddress)
    }
    val pairAfterPermission: () -> Unit = {
        viewModel.pairing.refresh()
        if (viewModel.state.value.canManagePairing && !viewModel.pairing.state.value.busy) {
            if (viewModel.pairing.state.value.bluetoothEnabled) viewModel.startPairing(pendingPairAddress)
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
    val pair: (String) -> Unit = { address ->
        if (viewModel.state.value.canManagePairing && !viewModel.pairing.state.value.busy) {
            useWifiDirect = false
            pendingPairAddress = address
            keyboard?.hide()
            val missing = pairingPermissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isEmpty()) pairAfterPermission() else pairingPermissionLauncher.launch(missing.toTypedArray())
        }
    }
    val connect: () -> Unit = {
        val current = viewModel.state.value
        val currentWireless = viewModel.wifiDirect.state.value
        if (current.canManagePairing && !current.pairingBusy && !current.pairingUnconfirmed && !currentWireless.busy && !currentWireless.confirmationRequired &&
            (!useWifiDirect || !currentWireless.discovering)) {
            keyboard?.hide()
            if (Build.VERSION.SDK_INT >= 37 &&
                ContextCompat.checkSelfPermission(context, networkPermission) != PackageManager.PERMISSION_GRANTED
            ) permissionLauncher.launch(networkPermission) else viewModel.connect(viaWifiDirect = useWifiDirect)
        }
    }
    val selectPage: (BoardPage) -> Unit = { next ->
        if (next.ordinal != selectedPageIndex) {
            viewModel.recordUiEvent("ui.page_changed", "${BoardPage.entries[selectedPageIndex].name}->${next.name}")
            val leavingControl = (selectedPageIndex == BoardPage.FOLLOW.ordinal && state.followActive) ||
                (selectedPageIndex == BoardPage.JOYSTICK.ordinal && state.manualActive)
            if (leavingControl) viewModel.onControlSurfaceHidden("已离开控制页面", ControlStopSource.PAGE_CHANGED)
            selectedPageIndex = next.ordinal
        }
    }

    val page = BoardPage.entries[selectedPageIndex]
    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (page != BoardPage.JOYSTICK && page != BoardPage.FOLLOW) {
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
                                Text(state.username + "@" + state.host + ":" + state.port,
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
            PrimaryTabRow(selectedTabIndex = selectedPageIndex) {
                BoardPage.entries.forEach { page ->
                    Tab(
                        selected = selectedPageIndex == page.ordinal,
                        onClick = { selectPage(page) },
                        text = { Text(page.label) },
                        modifier = Modifier.testTag(page.tag),
                    )
                }
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
            } else {
                val scroll = when (page) {
                    BoardPage.CONNECTION -> connectionScroll
                    BoardPage.FOLLOW -> followScroll
                    BoardPage.JOYSTICK -> error("Manual page has no scroll state")
                    BoardPage.TERMINAL -> terminalScroll
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(scroll).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    state.error?.let { error ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                            Text(error, Modifier.padding(16.dp).testTag("error"),
                                color = MaterialTheme.colorScheme.onErrorContainer)
                        }
                    }
                    when (page) {
                        BoardPage.CONNECTION -> {
                            PairingCard(state = pairing, canManage = state.canManagePairing && !viewModel.wifiDirect.mutationPending,
                                sshConnected = state.connected, onPair = pair, onCancel = viewModel.pairing::cancel,
                                onCheck = viewModel.pairing::retryStatus, onStopHotspot = viewModel.pairing::stopHotspot)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !useWifiDirect, onClick = { useWifiDirect = false },
                                    enabled = state.canManageWifiDirect && !wireless.busy && (!useWifiDirect || !wireless.discovering),
                                    label = { Text("局域网") }, modifier = Modifier.weight(1f).testTag("connectionLanMode"))
                                FilterChip(selected = useWifiDirect, onClick = { useWifiDirect = true; viewModel.wifiDirect.refreshStatus() },
                                    enabled = state.canManageWifiDirect && !wireless.busy,
                                    label = { Text("Wi-Fi Direct") }, modifier = Modifier.weight(1f).testTag("connectionDirectMode"))
                            }
                            if (!useWifiDirect && wireless.confirmationRequired) {
                                Text("上次直连操作结果尚未确认，请先刷新无线状态再连接 SSH。", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = viewModel.wifiDirect::refresh, enabled = !wireless.busy,
                                    modifier = Modifier.testTag("connectionRefreshWireless")) { Text("刷新无线状态") }
                            }
                            if (useWifiDirect) WifiDirectCard(
                                state = wireless, canManage = state.canManageWifiDirect,
                                lockedReason = when {
                                    state.connected -> "先断开 SSH，才能更改无线组。"
                                    !state.canManageWifiDirect -> "控制或连接操作尚未结束，暂不能更改无线组。"
                                    state.followUncertain -> "可以恢复无线连接；旧控制仍保持锁定，SSH 重连后会检查占用。"
                                    else -> null
                                },
                                onPermission = { if (viewModel.state.value.canManageWifiDirect) nearbyPermissionLauncher.launch(android.Manifest.permission.NEARBY_WIFI_DEVICES) },
                                onWifiSettings = { if (viewModel.state.value.canManageWifiDirect) context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) },
                                onRefresh = viewModel.wifiDirect::refresh,
                                onCreate = viewModel.wifiDirect::createGroup,
                                onRemove = viewModel.wifiDirect::removeGroup,
                                onDiscover = viewModel.wifiDirect::discoverPeers,
                                onStopDiscovery = viewModel.wifiDirect::stopDiscovery,
                                onInvite = viewModel.wifiDirect::invitePeer,
                                onUseAddress = viewModel::selectWifiDirectAddress,
                            )
                            if (!state.connected) ConnectionCard(state, viewModel, connect,
                                networkReady = !pairing.busy && !pairing.confirmationRequired && !wireless.busy && !wireless.confirmationRequired && (!useWifiDirect || !wireless.discovering) &&
                                    (!useWifiDirect || (DirectNetworkLease.from(wireless) != null && wifiDirectHostForConnection(wireless, state.host) != null)),
                                networkHint = if (useWifiDirect && wireless.groupExists && !wireless.busy && !wireless.discovering && !wireless.confirmationRequired)
                                    "请选择已加入的开发板 IP，或填写当前无线组内的地址；不能沿用原热点 IP。"
                                    else "请先完成无线组操作并停止搜索。",
                                useWifiDirect = useWifiDirect)
                            else Text("已连接开发板，可从上方分页查看配置或控制小车。")
                        }
                        BoardPage.FOLLOW -> Unit
                        BoardPage.JOYSTICK -> Unit
                        BoardPage.TERMINAL -> {
                            DiagnosticLogCard(controlActive = state.manualActive || state.followActive || state.followStopping)
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text("快捷查询", style = MaterialTheme.typography.titleMedium)
                                    val enabled = state.connected && !state.busy &&
                                        !state.manualActive && !state.followBlocksControl
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
                                        enabled = state.connected && !state.busy && !state.manualActive &&
                                            !state.followBlocksControl && state.command.isNotBlank(),
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
    var useDirectionPad by rememberSaveable { mutableStateOf(false) }
    var manualSection by rememberSaveable { mutableStateOf(ManualSection.DRIVE) }
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
    }
    val controlling = state.driveEnabled || state.driveStarting || state.driveStopping
    val canAdjustSpeed = !state.parkingUpdating && !state.followBlocksControl &&
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
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    ParkingControls(state, viewModel::setParkingEnabled, viewModel::changeParkingCurrent)
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
                Button(onClick = { viewModel.enableDrive() },
                    enabled = state.canEnableDrive,
                    modifier = Modifier.fillMaxWidth().testTag("enableDrive")) {
                    Text("启用手动控制")
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
    onEnabledChange: (Boolean) -> Unit,
    onCurrentChange: (Int) -> Unit,
) {
    val settings = state.parkingSettings
    val enabled = settings?.enabled == true
    val current = settings?.currentAmps ?: 5
    val canConfigure = state.canConfigureParking
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("驻车开关", style = MaterialTheme.typography.titleMedium)
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = canConfigure && (enabled || current > 0),
                modifier = Modifier.testTag("parkingSwitch").semantics { contentDescription = "驻车开关" }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { onCurrentChange(current - 1) },
                enabled = canConfigure && current > 0,
                modifier = Modifier.testTag("parkingDecrease").semantics { contentDescription = "驻车电流减少 1 A" }
            ) { Text("−") }
            Text(
                "驻车电流：${if (settings == null) "—" else current} A",
                fontWeight = FontWeight.Bold, modifier = Modifier.testTag("parkingCurrent")
            )
            OutlinedButton(
                onClick = { onCurrentChange(current + 1) },
                enabled = canConfigure && current < 60,
                modifier = Modifier.testTag("parkingIncrease").semantics { contentDescription = "驻车电流增加 1 A" }
            ) { Text("＋") }
        }
        Text("范围 0–60 A，每次调整 1 A；设为 0 A 会关闭驻车。", style = MaterialTheme.typography.bodySmall)
        Text("开启后松手停车时按设定电流锁定车轮，使用摇杆或方向键仍可行驶。停车确认后可修改设置。", style = MaterialTheme.typography.bodySmall)
        Text(state.parkingStatus, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingStatus"))
        if (state.parkingUpdating) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun ConnectionCard(state: BoardUiState, viewModel: BoardViewModel, connect: () -> Unit,
    networkReady: Boolean = true, useWifiDirect: Boolean = false,
    networkHint: String = "请先完成无线组操作并停止搜索。") {
    var showPassword by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接开发板", style = MaterialTheme.typography.titleMedium)
            Text(if (useWifiDirect) "先让开发板加入手机无线组，再使用开发板分配到的 IP 连接 SSH。"
                else "手机和开发板需接入同一局域网。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = state.host, onValueChange = viewModel::updateHost, label = { Text("IP 地址") },
                    singleLine = true, enabled = !state.busy, modifier = Modifier.weight(1f).testTag("host"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
                )
                OutlinedTextField(
                    value = state.port, onValueChange = viewModel::updatePort, label = { Text("端口") },
                    singleLine = true, enabled = !state.busy, modifier = Modifier.width(90.dp).testTag("port"),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            OutlinedTextField(
                value = state.username, onValueChange = viewModel::updateUsername, label = { Text("用户名") },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("username"),
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false)
            )
            OutlinedTextField(
                value = state.password, onValueChange = viewModel::updatePassword, label = { Text("密码") },
                singleLine = true, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("password"),
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "隐藏" else "显示") } }
            )
            Text("连接成功后会在本机保存密码，下次自动填入。", style = MaterialTheme.typography.bodySmall)
            if (!networkReady) Text(networkHint, style = MaterialTheme.typography.bodySmall)
            Button(onClick = connect, enabled = state.canManageWifiDirect && networkReady, modifier = Modifier.fillMaxWidth().testTag("connect")) { Text("连接开发板") }
        }
    }
}
