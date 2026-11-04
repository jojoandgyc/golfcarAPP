package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Connection choices and explicit actions only; composition never opens a connection or wireless group. */
@Composable
internal fun ConnectionSetupCard(
    state: BoardUiState,
    method: ConnectionMethod,
    selectionBlockedReason: String?,
    connectionBlockedReason: String?,
    onMethodChange: (ConnectionMethod) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onCancel: () -> Unit,
    onImport: () -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onDiscover: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val profile = state.remoteProfile
    val remote = method == ConnectionMethod.REMOTE
    val needsImport = remote && (profile == null || state.remoteProfileError != null)
    val selectionReason = selectionBlockedReason ?: state.connectionSelectionBlockReason()
    val canSelect = selectionReason == null
    val canDiscover = canSelect && !state.connected && !state.busy
    val actualEndpoint = state.connectionEndpointLabel?.takeIf { it.isNotBlank() }
    val showConnectionEndpoint = state.connected && !state.busy
    val needsLoginDetails = state.username.isBlank() || state.password.isEmpty() ||
        (!remote && (state.host.isBlank() || state.port.toIntOrNull()?.let { it in 1..65535 } != true))
    var settingsExpanded by rememberSaveable(method) { mutableStateOf(needsLoginDetails) }
    var showPassword by remember(method) { mutableStateOf(false) }

    Card(modifier.fillMaxWidth().testTag("connectionSetupCard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(ConnectionMethod.LAN, "局域网", "connectionLanMode"),
                    Triple(ConnectionMethod.WIFI_DIRECT, "Wi-Fi 直连", "connectionDirectMode"),
                    Triple(ConnectionMethod.REMOTE, "远程", "connectionRemoteMode"),
                ).forEach { (choice, label, tag) ->
                    FilterChip(
                        selected = method == choice,
                        enabled = canSelect,
                        onClick = { if (canSelect && method != choice) onMethodChange(choice) },
                        label = { Text(label, maxLines = 1) },
                        modifier = Modifier.weight(1f).testTag(tag),
                    )
                }
            }

            Text(
                when {
                    state.busy -> "SSH 正在连接或操作"
                    state.connected -> "SSH 已连接"
                    else -> "SSH 未连接"
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (state.connected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("connectionStatus"),
            )
            if (!showConnectionEndpoint) Text(
                when {
                    remote && profile != null -> "${profile.host}:${profile.controlPort}"
                    remote -> "尚未导入远程配置"
                    state.host.isBlank() -> "请填写小车地址"
                    else -> "${state.host}:${state.port}"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("connectionTarget"),
            )
            Text(
                "账号 ${state.username.ifBlank { "未填写" }} · " +
                    if (state.password.isEmpty()) "密码未填写" else "密码已保存",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("connectionLoginSummary"),
            )

            // Keep the primary action above settings and explanations, including on small phones.
            when {
                state.busy -> Button(onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().testTag("cancel")) {
                    Text("取消并断开")
                }
                state.connected -> OutlinedButton(onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth().testTag("disconnect")) {
                    Text(if (state.manualActive || state.followActive || state.followStopping)
                        "停止控制并断开" else "断开控制连接")
                }
                needsImport -> Button(
                    onClick = { if (canSelect) onImport() }, enabled = canSelect,
                    modifier = Modifier.fillMaxWidth().testTag("importRemoteProfile"),
                ) { Text(if (profile == null) "导入远程配置" else "重新导入远程配置") }
                else -> Button(
                    onClick = { if (connectionBlockedReason == null && canSelect) onConnect() },
                    enabled = connectionBlockedReason == null && canSelect,
                    modifier = Modifier.fillMaxWidth().testTag("connect"),
                ) { Text(if (remote) "远程连接小车" else "连接小车") }
            }

            if (showConnectionEndpoint) {
                Column(Modifier.testTag("connectionEndpoint")) {
                    Text(actualEndpoint ?: "连接端点待确认", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("connectionTarget"))
                }
            } else if (state.busy || !state.connected) {
                state.connectionRecoveryMessage?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("connectionRecoveryMessage"))
                }
            }
            if (method == ConnectionMethod.LAN) {
                OutlinedButton(onClick = { if (canDiscover) onDiscover() }, enabled = canDiscover,
                    modifier = Modifier.fillMaxWidth().testTag("connectionDiscover")) {
                    Text("重新查找小车")
                }
            }
            Text(when (method) {
                ConnectionMethod.LAN -> "自动发现同一Wi-Fi内的小车；地址变化时无需手动修改。"
                ConnectionMethod.WIFI_DIRECT -> "由手机创建无线组，等待小车加入后连接。"
                ConnectionMethod.REMOTE -> "手机和小车联网，经中转服务器连接。"
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connectionMethodHelp"))
            if (state.remoteProfileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            selectionReason?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("connectionSelectionBlocked"))
            }
            connectionBlockedReason?.takeIf { it != selectionReason }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("connectionBlocked"))
            }
            if (remote) state.remoteProfileError?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("remoteProfileError"))
            }

            TextButton(onClick = { settingsExpanded = !settingsExpanded; showPassword = false },
                modifier = Modifier.testTag("connectionLoginSettings")) {
                Text(if (settingsExpanded) "收起登录设置" else "登录设置")
            }
            if (settingsExpanded) {
                if (method == ConnectionMethod.LAN) Text("也可手动填写小车 IP 地址；用户名和密码仍用于登录。",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("connectionManualAddressHelp"))
                if (!remote) Row(horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = state.host, onValueChange = onHostChange,
                        label = { Text("小车 IP 地址") }, singleLine = true, enabled = canSelect,
                        modifier = Modifier.weight(1f).testTag("host"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri,
                            autoCorrectEnabled = false))
                    OutlinedTextField(value = state.port, onValueChange = onPortChange,
                        label = { Text("端口") }, singleLine = true, enabled = canSelect,
                        modifier = Modifier.width(90.dp).testTag("port"),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                OutlinedTextField(value = state.username, onValueChange = onUsernameChange,
                    label = { Text("开发板用户名") }, singleLine = true, enabled = canSelect,
                    modifier = Modifier.fillMaxWidth().testTag("username"),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                OutlinedTextField(value = state.password, onValueChange = onPasswordChange,
                    label = { Text("开发板密码") }, singleLine = true, enabled = canSelect,
                    modifier = Modifier.fillMaxWidth().testTag("password"),
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }, enabled = canSelect,
                            modifier = Modifier.testTag("connectionPasswordToggle")) {
                            Text(if (showPassword) "隐藏" else "显示")
                        }
                    })
                Text("连接成功后保存登录信息。", style = MaterialTheme.typography.bodySmall)
                if (remote && profile != null) {
                    Text("服务器 ${profile.host}:${profile.controlPort}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("remoteServerHost"))
                    Text("设备证书有效期至 " + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                        .format(Date(profile.certificateExpiresAtEpochMillis)),
                        style = MaterialTheme.typography.bodySmall)
                    if (!needsImport) OutlinedButton(
                        onClick = { if (canSelect) onImport() }, enabled = canSelect,
                        modifier = Modifier.fillMaxWidth().testTag("importRemoteProfile"),
                    ) { Text("更换远程配置") }
                }
            }
        }
    }
}
