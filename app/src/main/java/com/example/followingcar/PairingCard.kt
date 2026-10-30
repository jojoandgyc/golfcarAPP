package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
internal fun PairingCard(state: PairingState, canManage: Boolean, sshConnected: Boolean,
    onPair: (String) -> Unit, onCancel: () -> Unit, onCheck: () -> Unit, onStopHotspot: () -> Unit,
    modifier: Modifier = Modifier, onCheckBluetooth: ((String) -> Unit)? = null) {
    var advanced by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable(state.selectedAddress) { mutableStateOf(state.selectedAddress) }
    Card(modifier.fillMaxWidth().testTag("pairingCard"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("一键配对小车", style = MaterialTheme.typography.titleLarge)
            Text("蓝牙连接 → 自动创建手机热点 → 小车入网", style = MaterialTheme.typography.bodyMedium)
            val awaitingSsh = state.hotspotActive && state.boardIp != null && !sshConnected &&
                !state.busy && !state.confirmationRequired
            Text(if (awaitingSsh) "配对热点已保留，尚未建立 SSH 连接" else state.status,
                modifier = Modifier.testTag("pairingStatus"))
            if (state.busy || state.recoveryLoading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("pairingProgress"))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("pairingError")) }
            if (state.firstError != null && state.firstError != state.error) Text("首次异常：${state.firstError}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pairingFirstError"))
            if (state.hotspotActive) {
                Text(if (state.boardIp != null) (if (sshConnected) "热点已开启 · 小车地址 ${state.boardIp}"
                    else "上次确认的小车地址 ${state.boardIp}") else "热点已保留，正在确认小车入网情况",
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
                !state.hotspotActive -> Button(onClick = { onPair(address) }, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("startPairing")) { Text("一键配对") }
            }
            if (state.hotspotActive && !state.busy && !state.confirmationRequired && !state.recoveryLoading) {
                if (awaitingSsh) Button(onClick = onCheck, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("reconnectPairedBoard")) { Text("核对并重新连接") }
                OutlinedButton(onClick = onStopHotspot, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("stopPairingHotspot")) { Text("关闭配对热点") }
            }
            if (!state.hotspotActive && !state.busy && !state.confirmationRequired &&
                !state.recoveryLoading && onCheckBluetooth != null) {
                OutlinedButton(onClick = { onCheckBluetooth(address) }, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("checkBluetooth")) { Text("检查蓝牙连接") }
                Text("只检查蓝牙服务，不切换小车的 Wi-Fi。", style = MaterialTheme.typography.bodySmall)
            }
            if (!canManage && !state.busy) Text(if (sshConnected) "断开 SSH 后可重新配对或关闭热点。"
                else "控制或网络操作尚未结束，暂不能更改连接。", style = MaterialTheme.typography.bodySmall)
            Text("首次请确认系统蓝牙配对提示。专用热点用于控制和视频，不共享手机流量；热点密码自动传输。",
                style = MaterialTheme.typography.bodySmall)
            Text("短暂断线会自动重连并核对结果。小车未开机或不在附近时保留记录，下次可继续恢复。",
                style = MaterialTheme.typography.bodySmall)
            Text("配网结果确认后，也可在连接页使用当前局域网或已配置的远程连接。",
                style = MaterialTheme.typography.bodySmall)
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
}
