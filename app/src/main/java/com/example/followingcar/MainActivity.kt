package com.example.followingcar

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
        setContent {
            FollowingCarTheme { BoardScreen(boardViewModel) }
        }
    }

    override fun onPause() {
        if (::boardViewModel.isInitialized) boardViewModel.onControlSurfaceHidden("App 已离开前台")
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ::boardViewModel.isInitialized) boardViewModel.onControlSurfaceHidden("界面失去焦点")
    }
}

private enum class BoardPage(val label: String, val tag: String) {
    CONNECTION("连接", "pageConnection"),
    FOLLOW("自动跟随", "pageFollow"),
    JOYSTICK("手动", "pageJoystick"),
    TERMINAL("终端", "pageTerminal"),
}

@Composable
private fun BoardScreen(viewModel: BoardViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    var selectedPageIndex by rememberSaveable { mutableStateOf(BoardPage.CONNECTION.ordinal) }
    val connectionScroll = rememberScrollState()
    val followScroll = rememberScrollState()
    val joystickScroll = rememberScrollState()
    val terminalScroll = rememberScrollState()
    DisposableEffect(view, state.driveEnabled, state.followActive) {
        view.keepScreenOn = state.driveEnabled || state.followActive
        onDispose { view.keepScreenOn = false }
    }
    val networkPermission = "android.permission.ACCESS_LOCAL_NETWORK"
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) viewModel.connect() else viewModel.permissionDenied()
    }
    val connect: () -> Unit = {
        keyboard?.hide()
        if (Build.VERSION.SDK_INT >= 37 &&
            ContextCompat.checkSelfPermission(context, networkPermission) != PackageManager.PERMISSION_GRANTED
        ) permissionLauncher.launch(networkPermission) else viewModel.connect()
    }
    val selectPage: (BoardPage) -> Unit = { next ->
        if (next.ordinal != selectedPageIndex) {
            val leavingControl = (selectedPageIndex == BoardPage.FOLLOW.ordinal && state.followActive) ||
                (selectedPageIndex == BoardPage.JOYSTICK.ordinal && state.manualActive)
            if (leavingControl) viewModel.onControlSurfaceHidden("已离开控制页面")
            selectedPageIndex = next.ordinal
        }
    }

    Scaffold { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
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
            val scroll = when (BoardPage.entries[selectedPageIndex]) {
                BoardPage.CONNECTION -> connectionScroll
                BoardPage.FOLLOW -> followScroll
                BoardPage.JOYSTICK -> joystickScroll
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
                when (BoardPage.entries[selectedPageIndex]) {
                    BoardPage.CONNECTION -> {
                        if (!state.connected) ConnectionCard(state, viewModel, connect)
                        else Text("已连接开发板，可从上方分页查看配置或控制小车。")
                    }
                    BoardPage.FOLLOW -> {
                        FollowConfigurationCard(state, viewModel::selectFollowCamera,
                            viewModel::inspectFollowConfiguration)
                        FollowControlCard(state, viewModel::setFollowExecute, { viewModel.startFollow() },
                            { viewModel.stopFollow() }, viewModel::refreshFollowAvailability,
                            viewModel::setFollowLaunchOptions)
                    }
                    BoardPage.JOYSTICK -> JoystickCard(state, viewModel)
                    BoardPage.TERMINAL -> {
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

@Composable
private fun JoystickCard(state: BoardUiState, viewModel: BoardViewModel) {
    var useDirectionPad by rememberSaveable { mutableStateOf(false) }
    var waitingForModeStop by remember { mutableStateOf(false) }
    LaunchedEffect(waitingForModeStop, state.driveConfirmedStopped, state.driveEnabled) {
        if (waitingForModeStop && (!state.driveEnabled || state.driveConfirmedStopped)) {
            waitingForModeStop = false
        }
    }
    fun chooseControlMode(directionPad: Boolean) {
        if (useDirectionPad == directionPad) return
        waitingForModeStop = state.driveEnabled && !state.driveConfirmedStopped
        viewModel.releaseJoystick()
        useDirectionPad = directionPad
    }
    val controlling = state.driveEnabled || state.driveStarting || state.driveStopping
    val canAdjustSpeed = !state.parkingUpdating && !state.followBlocksControl &&
        (!controlling || (state.driveEnabled && state.driveLeft == 0 && state.driveRight == 0 && state.driveConfirmedStopped))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("手动控制", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(state.driveStatus, modifier = Modifier.testTag("driveStatus"), style = MaterialTheme.typography.bodyMedium)
            if (controlling) Text(state.drivePort, style = MaterialTheme.typography.bodySmall)
            state.driveError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("driveError"))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { viewModel.changeSpeedLimit(state.speedLimit - 10) },
                    enabled = state.speedLimit > 10 && canAdjustSpeed
                ) { Text("−") }
                Text("最高 ${state.speedLimit} RPM", fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = { viewModel.changeSpeedLimit(state.speedLimit + 10) },
                    enabled = state.speedLimit < 100 && canAdjustSpeed
                ) { Text("＋") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !useDirectionPad, onClick = { chooseControlMode(false) },
                    label = { Text("摇杆") }, modifier = Modifier.testTag("controlModeJoystick"))
                FilterChip(selected = useDirectionPad, onClick = { chooseControlMode(true) },
                    label = { Text("方向按键") }, modifier = Modifier.testTag("controlModeButtons"))
            }
            val inputEnabled = state.driveEnabled && !state.driveStopping &&
                !state.parkingUpdating && !state.followBlocksControl && !waitingForModeStop
            if (useDirectionPad) {
                DirectionPadControl(enabled = inputEnabled,
                    onMove = viewModel::moveJoystick,
                    onRelease = viewModel::releaseJoystick,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                JoystickControl(enabled = inputEnabled,
                    onMove = viewModel::moveJoystick,
                    onRelease = viewModel::releaseJoystick,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            if (waitingForModeStop) Text("正在确认停车，确认后可使用新控制方式。",
                style = MaterialTheme.typography.bodySmall)
            Text(
                "目标转速：左 ${state.driveLeft} / 右 ${state.driveRight} RPM",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("driveSpeeds")
            )
            Text(if (useDirectionPad) "按住方向键移动，松手、移出按键或按中间停止键即停车。"
                else "上推前进，下拉后退，左右转向；推得越远速度越快。松手停车。",
                style = MaterialTheme.typography.bodySmall)
            Text("切换控制方式或离开页面会停车；离开 App 会停用手动控制。",
                style = MaterialTheme.typography.bodySmall)
            ParkingControls(state, viewModel::setParkingEnabled, viewModel::changeParkingCurrent)
            if (!controlling) {
                if (state.followBlocksControl) Text("自动跟随控制尚未释放，手动控制暂不可用。", style = MaterialTheme.typography.bodySmall)
                Text("启用前请退出开发板上的键盘遥控或跟随程序。", style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = { viewModel.enableDrive() }, enabled = state.connected && !state.busy && !state.followBlocksControl,
                    modifier = Modifier.fillMaxWidth().testTag("enableDrive")
                ) { Text("启用手动控制") }
            } else {
                Button(
                    onClick = { viewModel.disableDrive("已急停 · 重新启用后可操作", emergency = true) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.fillMaxWidth().testTag("emergencyStop")
                ) { Text("急停并停用手动控制") }
            }
        }
    }
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
private fun ConnectionCard(state: BoardUiState, viewModel: BoardViewModel, connect: () -> Unit) {
    var showPassword by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("连接开发板", style = MaterialTheme.typography.titleMedium)
            Text("手机和开发板需接入同一局域网。", style = MaterialTheme.typography.bodySmall)
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
            Button(onClick = connect, enabled = !state.busy, modifier = Modifier.fillMaxWidth().testTag("connect")) { Text("连接开发板") }
        }
    }
}
