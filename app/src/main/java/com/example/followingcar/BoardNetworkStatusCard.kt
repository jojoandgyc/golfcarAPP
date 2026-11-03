package com.example.followingcar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Presentation only: observing this card never probes, reconnects or starts a control session. */
@Composable
internal fun BoardNetworkStatusCard(
    health: BoardNetworkHealth,
    nowElapsedMs: Long,
    connected: Boolean,
    remoteMode: Boolean,
    canReconnectRemote: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onReconnectRemote: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    canRefresh: Boolean = true,
) {
    val localOnline = health.local.isFreshOnline(nowElapsedMs)
    val remoteOnline = health.remote.isFreshOnline(nowElapsedMs)
    val summary = when {
        localOnline && remoteOnline -> "小车在线 · 局域网 / 公网"
        localOnline -> "小车在线 · 局域网"
        remoteOnline -> "小车在线 · 公网"
        else -> health.onlineLabel(nowElapsedMs)
    }
    val lastOnline = listOfNotNull(health.local.lastOnlineElapsedMs, health.remote.lastOnlineElapsedMs).maxOrNull()
    val lastSeen = lastOnline?.let { "${ageLabel(nowElapsedMs, it)}确认在线" } ?: "尚无在线确认"
    val activePath = if (remoteMode) "公网" else "局域网"
    val ssh = if (connected) "$activePath SSH 已连接" else "SSH 未连接"
    val localLost = !localOnline && health.local.effectiveStatus(nowElapsedMs) in
        setOf(Reachability.UNKNOWN, Reachability.STALE, Reachability.UNREACHABLE, Reachability.CHECKING)
    val offerRemote = !connected && !remoteMode && localLost
    val remoteConfigured = health.remote.status != Reachability.NOT_CONFIGURED
    // An independent background presence probe must not delay explicit recovery.
    // The caller retains control/cleanup locks and cancels probes before connecting.
    val reconnectEnabled = canReconnectRemote
    val indicator = if (localOnline || remoteOnline) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outline
    val checking = health.local.probeInProgress || health.remote.probeInProgress

    if (compact) {
        Surface(modifier.fillMaxWidth().testTag("networkHealthCompact"),
            color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(indicator, CircleShape))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(summary, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("networkHealthCompactSummary"))
                    Text(if (offerRemote && !remoteConfigured) "公网未配置，请到连接页导入" else "$ssh · $lastSeen",
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("networkHealthCompactDetail"))
                }
                if (checking) Text("复核中", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("networkCompactRefreshing"))
                if (offerRemote && remoteConfigured) TextButton(
                    onClick = { if (reconnectEnabled) onReconnectRemote() }, enabled = reconnectEnabled,
                    modifier = Modifier.testTag("networkCompactRemoteReconnect")) { Text("公网重连") }
            }
        }
        return
    }

    Card(modifier.fillMaxWidth().testTag("networkHealthCard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(indicator, CircleShape))
                Text(summary, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("networkHealthSummary"))
            }
            Text("$ssh · $lastSeen", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("networkLastSeen"))
            Text(health.phone.statusLabel(), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("networkPhoneStatus"))
            RouteStatusRow("局域网", health.local, nowElapsedMs, "networkLocalStatus")
            RouteStatusRow("公网", health.remote, nowElapsedMs, "networkRemoteStatus")
            Text("暂不可达只表示当前路径未能确认小车在线，不代表小车已断电。手机能上网也不代表小车能通过公网连接。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { if (canRefresh && !refreshing) onRefresh() },
                enabled = canRefresh && !refreshing,
                modifier = Modifier.fillMaxWidth().testTag("networkRefresh")) {
                Text(if (refreshing) "正在检测在线状态…" else "刷新在线状态")
            }
            if (offerRemote) {
                if (remoteConfigured) {
                    Text(if (remoteOnline) "公网已确认可达，可重新建立连接。" else "可尝试公网连接；手机和小车都需要能访问中转服务器。",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("networkRemoteRecoveryHint"))
                    OutlinedButton(onClick = { if (reconnectEnabled) onReconnectRemote() }, enabled = reconnectEnabled,
                        modifier = Modifier.fillMaxWidth().testTag("networkRemoteReconnect")) { Text("通过公网重新连接") }
                    Text(if (!canReconnectRemote) "正在等待当前操作结束；控制锁定会保留。" else "重连不会自动恢复行驶；未确认的控制会话仍需核对。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("公网尚未配置。请在连接页选择“远程”，导入这台手机的连接配置。",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("networkRemoteImportHint"))
                }
            }
        }
    }
}

@Composable
private fun RouteStatusRow(label: String, route: RouteHealth, nowElapsedMs: Long, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("$label：${route.statusLabel(nowElapsedMs)}", style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(tag))
        if (route.probeInProgress) Text("正在后台复核…", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("${tag}Refreshing"))
        route.lastOnlineElapsedMs?.let { last ->
            val expired = if (route.effectiveStatus(nowElapsedMs) == Reachability.STALE) "上次确认已过期 · " else ""
            Text("$expired${ageLabel(nowElapsedMs, last)}确认可达", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("${tag}LastSeen"))
        }
        route.failure?.let { failure ->
            Text(failure.label, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("${tag}Failure"))
        }
    }
}

private fun ageLabel(nowElapsedMs: Long, lastElapsedMs: Long): String {
    val seconds = ((nowElapsedMs - lastElapsedMs).coerceAtLeast(0L) / 1_000L)
    return when {
        seconds < 1 -> "刚刚"
        seconds < 60 -> "${seconds} 秒前"
        seconds < 3_600 -> "${seconds / 60} 分钟前"
        else -> "${seconds / 3_600} 小时前"
    }
}
