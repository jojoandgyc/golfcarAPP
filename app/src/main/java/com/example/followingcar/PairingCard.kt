package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions

@Composable
internal fun PairingCard(state: PairingState, canManage: Boolean, sshConnected: Boolean,
    onPair: (String) -> Unit, onCancel: () -> Unit, onCheck: () -> Unit, onStopHotspot: () -> Unit,
    modifier: Modifier = Modifier, onCheckBluetooth: ((String) -> Unit)? = null,
    hotspotProfile: SystemHotspotProfileState = SystemHotspotProfileState(loading = false),
    onSaveHotspotProfile: (String, String) -> Unit = { _, _ -> },
    onOpenHotspotSettings: () -> Unit = {}) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var editHotspot by remember { mutableStateOf(false) }
    var address by rememberSaveable(state.selectedAddress) { mutableStateOf(state.selectedAddress) }
    Card(modifier.fillMaxWidth().testTag("pairingCard"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("一键配对小车", style = MaterialTheme.typography.titleLarge)
            Text("系统网络共享热点 → 蓝牙发送入网信息 → 自动连接小车", style = MaterialTheme.typography.bodyMedium)
            val awaitingSsh = state.hotspotActive && state.boardIp != null && !sshConnected &&
                !state.busy && !state.confirmationRequired
            Text(if (awaitingSsh) "小车已配网，尚未建立 SSH 连接" else state.status,
                modifier = Modifier.testTag("pairingStatus"))
            if (state.busy || state.recoveryLoading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("pairingProgress"))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("pairingError")) }
            if (state.firstError != null && state.firstError != state.error) Text("首次异常：${state.firstError}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pairingFirstError"))
            if (state.hotspotActive) {
                Text(if (state.boardIp != null) (if (sshConnected) "小车地址 ${state.boardIp}"
                    else "上次确认的小车地址 ${state.boardIp}") else "正在确认小车入网情况",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pairingNetwork"))
                if (sshConnected) Text("已连接开发板，可以切换到驾驶页面。", style = MaterialTheme.typography.bodySmall)
            }
            when {
                state.recoveryLoading -> Text("正在恢复上次配网状态…")
                state.busy -> OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth().testTag("cancelPairing")) {
                    Text("取消等待")
                }
                state.confirmationRequired -> Button(onClick = onCheck, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("checkPairing")) { Text("恢复并核对连接") }
                !state.hotspotActive -> Button(onClick = {
                    if (hotspotProfile.configured) onPair(address) else editHotspot = true
                }, enabled = canManage && !hotspotProfile.loading && !hotspotProfile.saving,
                    modifier = Modifier.fillMaxWidth().testTag("startPairing")) { Text("一键配对") }
            }
            if (state.hotspotActive && !state.busy && !state.confirmationRequired && !state.recoveryLoading) {
                if (awaitingSsh) Button(onClick = onCheck, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("reconnectPairedBoard")) { Text("核对并重新连接") }
                OutlinedButton(onClick = onStopHotspot, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("forgetPairing")) { Text("解除配对记录") }
            }
            if (!state.hotspotActive && !state.busy && !state.confirmationRequired &&
                !state.recoveryLoading && onCheckBluetooth != null) {
                OutlinedButton(onClick = { onCheckBluetooth(address) }, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("checkBluetooth")) { Text("检查蓝牙连接") }
                Text("只检查蓝牙服务，不切换小车的 Wi-Fi。", style = MaterialTheme.typography.bodySmall)
            }
            if (!canManage && !state.busy) Text(if (sshConnected) "断开 SSH 后可重新配网；系统热点继续保持。"
                else "控制或网络操作尚未结束，暂不能更改连接。", style = MaterialTheme.typography.bodySmall)
            if (!state.busy && !state.recoveryLoading) {
                OutlinedButton(onClick = onOpenHotspotSettings, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("systemHotspotSettings")) { Text("打开系统热点设置") }
                Text("请开启 2.4 GHz、WPA2 网络共享热点。首次填写名称和密码，以后沿用；App 不创建或关闭热点。",
                    style = MaterialTheme.typography.bodySmall)
                if (!state.confirmationRequired) {
                    Text(if (hotspotProfile.loading) "正在读取热点资料…" else if (hotspotProfile.configured)
                        "已保存：${hotspotProfile.ssid} · 密码已保存" else "尚未保存系统热点资料",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("systemHotspotProfile"))
                    TextButton(onClick = { editHotspot = true },
                        enabled = canManage && !hotspotProfile.loading && !hotspotProfile.saving,
                        modifier = Modifier.testTag("editSystemHotspot")) {
                        Text(if (hotspotProfile.configured) "修改热点资料" else "填写热点名称和密码")
                    }
                }
            }
            if (!state.hotspotActive && !state.busy && !state.confirmationRequired && !state.recoveryLoading) {
                TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("pairingAdvanced")) {
                    Text(if (advanced) "收起开发板设置" else "开发板设置")
                }
                if (advanced) OutlinedTextField(value = address, onValueChange = { address = it.uppercase() },
                    enabled = canManage, singleLine = true, label = { Text("开发板蓝牙地址") },
                    supportingText = { Text("已预填这台小车；更换开发板时再修改。") },
                    modifier = Modifier.fillMaxWidth().testTag("pairingBoardAddress"))
            }
        }
    }
    if (editHotspot && !state.busy && !state.confirmationRequired) {
        SystemHotspotProfileDialog(hotspotProfile, onSaveHotspotProfile, { editHotspot = false })
    }
}

@Composable
private fun SystemHotspotProfileDialog(profile: SystemHotspotProfileState,
    onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var ssid by remember { mutableStateOf(profile.ssid) }
    // Never use rememberSaveable for a credential or prefill the stored password in the UI.
    var password by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf(false) }
    val initialRevision = remember { profile.savedRevision }
    LaunchedEffect(profile.savedRevision) {
        if (submitted && profile.savedRevision > initialRevision) {
            password = ""
            onDismiss()
        }
    }
    AlertDialog(onDismissRequest = { if (!profile.saving) onDismiss() },
        title = { Text("系统热点资料") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("填写手机设置中的热点名称和密码。这里只保存入网资料，不修改系统热点。")
            OutlinedTextField(ssid, { ssid = it }, singleLine = true, enabled = !profile.saving,
                label = { Text("热点名称") }, modifier = Modifier.testTag("systemHotspotSsid"))
            OutlinedTextField(password, { password = it }, singleLine = true, enabled = !profile.saving,
                label = { Text(if (profile.configured) "热点密码（留空保留）" else "热点密码") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.testTag("systemHotspotPassword"))
            profile.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { Button(onClick = { submitted = true; onSave(ssid, password) },
            enabled = !profile.saving && ssid.isNotEmpty() && (profile.configured || password.isNotEmpty()),
            modifier = Modifier.testTag("saveSystemHotspot")) { Text(if (profile.saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !profile.saving) { Text("取消") } })
}
