package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Merely viewing this card never creates a group, discovers peers, or connects SSH. */
@Composable
internal fun WifiDirectCard(
    state: WifiDirectState,
    canManage: Boolean,
    lockedReason: String?,
    onPermission: () -> Unit,
    onWifiSettings: () -> Unit,
    onRefresh: () -> Unit,
    onCreate: () -> Unit,
    onRemove: () -> Unit,
    onDiscover: () -> Unit,
    onStopDiscovery: () -> Unit,
    onInvite: (String) -> Unit,
    onUseAddress: (String) -> Unit,
) {
    // A new group/permission loss must never leave a previous group's password visible.
    var showPassword by remember(state.networkName, state.permissionGranted) { mutableStateOf(false) }
    val enabled = canManage && !state.busy && !state.confirmationRequired && state.permissionGranted && state.wifiEnabled && state.supported
    Card(Modifier.fillMaxWidth().testTag("wifiDirectCard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Wi-Fi Direct · 手机组主", style = MaterialTheme.typography.titleMedium)
            Text("手机创建并管理无线组，开发板加入后，通过 SSH 控制小车。",
                style = MaterialTheme.typography.bodySmall)
            Text(state.status, fontWeight = FontWeight.Bold, modifier = Modifier.testTag("wifiDirectStatus"))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("wifiDirectError")) }
            if (state.confirmationRequired) Text("上次无线操作结果未确认。请先刷新状态，再更改无线组或连接 SSH。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("wifiDirectConfirmationRequired"))
            lockedReason?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("wifiDirectLocked")) }
            if (!state.supported) {
                Text("此设备不支持 Wi-Fi Direct，请使用局域网连接。", style = MaterialTheme.typography.bodySmall)
            } else if (!state.permissionGranted) {
                Button(onClick = onPermission, enabled = canManage,
                    modifier = Modifier.fillMaxWidth().testTag("wifiDirectPermission")) { Text("允许附近设备权限") }
            } else {
                if (!state.wifiEnabled) OutlinedButton(onClick = onWifiSettings, enabled = canManage,
                    modifier = Modifier.testTag("wifiDirectOpenSettings")) { Text("打开 Wi-Fi 设置") }
                if (state.groupExists) {
                    if (state.isGroupOwner) {
                        Text("无线组：${state.networkName ?: "等待系统返回"}", modifier = Modifier.testTag("wifiDirectGroupName"))
                        Text("手机地址：${state.ownerAddress ?: "等待分配"}（不是开发板地址）",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("wifiDirectOwnerAddress"))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("入组密码：${if (showPassword) state.passphrase ?: "等待系统返回" else "••••••••"}",
                                modifier = Modifier.weight(1f).testTag("wifiDirectPassword"), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { showPassword = !showPassword }, enabled = !state.passphrase.isNullOrBlank(),
                                modifier = Modifier.testTag("wifiDirectPasswordToggle")) { Text(if (showPassword) "隐藏" else "显示") }
                        }
                        Text("开发板可通过 P2P 加入；也可在其 Wi-Fi 设置中选择上方无线组并输入入组密码。",
                            style = MaterialTheme.typography.bodySmall)
                        HorizontalDivider()
                        Text("已加入设备 · ${state.clients.size}", style = MaterialTheme.typography.titleSmall)
                        if (state.clients.isEmpty()) Text("等待开发板加入…", style = MaterialTheme.typography.bodySmall)
                        state.clients.forEach { peer ->
                            val address = usableWifiDirectClientAddress(peer.ipAddress, state.ownerAddress)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(peer.name.ifBlank { "无线设备" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(address ?: "等待开发板 IP，可在下方手动填写", style = MaterialTheme.typography.bodySmall)
                                }
                                OutlinedButton(onClick = { address?.let(onUseAddress) }, enabled = canManage && !state.busy && address != null,
                                    modifier = Modifier.testTag("wifiDirectUse_${peer.address}")) { Text("使用 IP") }
                            }
                        }
                        Text("使用 IP 只填入连接地址；确认用户名和密码后，再点击连接开发板。",
                            style = MaterialTheme.typography.bodySmall)
                    } else Text("当前手机是组客户端。若要由手机管理，请先离开此组，再创建自己的无线组。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = onRemove, enabled = enabled,
                        modifier = Modifier.fillMaxWidth().testTag("wifiDirectRemove")) {
                        Text(if (state.isGroupOwner) "解散无线组" else "离开当前无线组")
                    }
                } else Button(onClick = onCreate, enabled = enabled,
                    modifier = Modifier.fillMaxWidth().testTag("wifiDirectCreate")) { Text("创建手机无线组（GO）") }
                if (state.groupExists && state.isGroupOwner) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = if (state.discovering) onStopDiscovery else onDiscover,
                            enabled = enabled, modifier = Modifier.weight(1f).testTag("wifiDirectDiscover")) {
                            Text(if (state.discovering) "停止搜索" else "搜索开发板")
                        }
                        TextButton(onClick = onRefresh, enabled = !state.busy,
                            modifier = Modifier.testTag("wifiDirectRefresh")) { Text("刷新状态") }
                    }
                    state.peers.filter { candidate -> state.clients.none { it.address == candidate.address } }.forEach { peer ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.name.ifBlank { "附近设备" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(wifiDirectPeerStatusLabel(peer.status), style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { onInvite(peer.address) }, enabled = enabled,
                                modifier = Modifier.testTag("wifiDirectInvite_${peer.address}")) { Text("邀请入组") }
                        }
                    }
                    Text("开发板需开启 P2P 发现并确认配对。没有搜索结果时，可使用上方无线组名称和密码入组。",
                        style = MaterialTheme.typography.bodySmall)
                } else TextButton(onClick = onRefresh, enabled = !state.busy,
                    modifier = Modifier.testTag("wifiDirectRefresh")) { Text("刷新状态") }
            }
        }
    }
}
