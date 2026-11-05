package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** A recovery entry point shared by all connection methods; rendering never starts an operation. */
@Composable
internal fun ConnectionRecoveryCard(
    pairing: PairingState,
    wireless: WifiDirectState,
    canManagePairing: Boolean,
    canRefreshWireless: Boolean,
    onCheckPairing: () -> Unit,
    onCancelPairing: () -> Unit,
    onRefreshWireless: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSystemHotspotSettings: (() -> Unit)? = null,
) {
    val showPairing = pairing.recoveryLoading || pairing.busy || pairing.confirmationRequired
    val showWireless = wireless.confirmationRequired || wireless.busy
    if (!showPairing && !showWireless) return

    Card(modifier.fillMaxWidth().testTag("connectionRecoveryCard"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (pairing.busy || wireless.busy) "配网进行中" else "本地网络待核对",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.testTag("connectionRecoveryTitle"))
            if (pairing.recoveryLoading || pairing.busy || wireless.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("connectionRecoveryProgress"))
            }
            if (showPairing) {
                Text(pairingRecoveryReason(pairing), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("connectionPairingRecoveryReason"))
                when {
                    // Cancellation remains reachable even when another guard disallows new operations.
                    pairing.busy -> OutlinedButton(onClick = onCancelPairing,
                        modifier = Modifier.fillMaxWidth().testTag("cancelPairing")) { Text("取消等待") }
                    pairing.confirmationRequired && !pairing.recoveryLoading -> {
                        Button(onClick = onCheckPairing, enabled = canManagePairing,
                            modifier = Modifier.fillMaxWidth().testTag("checkPairing")) { Text("恢复并核对配网") }
                        if (!canManagePairing) Text(
                            "请先结束当前控制并断开 SSH，等待其他网络操作完成后再恢复配网。",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("connectionPairingRecoveryBlocked"))
                    }
                }
            }
            if (showWireless) {
                Text(wirelessRecoveryReason(wireless), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("connectionWirelessRecoveryReason"))
                if (wireless.confirmationRequired) {
                    OutlinedButton(onClick = onRefreshWireless, enabled = canRefreshWireless && !wireless.busy,
                        modifier = Modifier.fillMaxWidth().testTag("connectionRefreshWireless")) {
                        Text("刷新无线状态")
                    }
                    if (!canRefreshWireless && !wireless.busy) Text(
                        "暂不能读取无线状态；请先结束当前控制或网络操作，确认附近设备权限后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("connectionWirelessRecoveryBlocked"))
                }
            }
            if (pairing.confirmationRequired && !pairing.busy && onOpenSystemHotspotSettings != null) {
                TextButton(onClick = onOpenSystemHotspotSettings, enabled = canManagePairing,
                    modifier = Modifier.testTag("recoverySystemHotspotSettings")) { Text("检查系统热点设置") }
            }
        }
    }
}

// Fixed text keeps board addresses, hotspot credentials and journal identifiers out of this card.
private fun pairingRecoveryReason(state: PairingState): String = when {
    state.recoveryLoading -> "正在读取上次配网记录，请稍候。"
    state.busy -> when (state.stage) {
        PairingStage.DISCOVERING -> "正在查找小车蓝牙，请保持小车在附近。"
        PairingStage.PAIRING_BLUETOOTH -> "正在配对小车蓝牙，请确认手机系统的配对提示。"
        PairingStage.CONNECTING_BLUETOOTH -> "正在连接小车蓝牙服务。"
        PairingStage.CHECKING_BOARD -> "正在核对小车状态，确认是否可以配网。"
        PairingStage.STARTING_HOTSPOT -> "正在检查手机系统热点。"
        PairingStage.SENDING_CREDENTIALS -> "正在发送配网信息，等待小车确认。"
        PairingStage.WAITING_NETWORK -> "正在等待小车接入手机热点。"
        PairingStage.VERIFYING_NETWORK -> "正在核对小车是否已接入配对网络。"
        PairingStage.RECOVERING -> "正在恢复并核对上次配网结果，可取消等待。"
        else -> "配网操作正在处理中，可取消等待。"
    }
    else -> "上次配网结果尚未确认；可先使用远程连接，本地连接需核对。"
}

private fun wirelessRecoveryReason(state: WifiDirectState): String = when {
    state.busy -> "系统正在读取或处理 Wi-Fi Direct 状态，请等待操作完成。"
    !state.supported -> "此手机不支持 Wi-Fi Direct，无法核对旧无线组；可先使用远程连接。"
    !state.permissionGranted -> "缺少附近 Wi-Fi 设备权限，暂时无法确认上次直连操作结果。"
    !state.wifiEnabled -> "手机 Wi-Fi 已关闭，上次直连操作结果尚未确认；开启后请刷新无线状态。"
    !state.groupInfoKnown -> "系统尚未确认无线组状态，请刷新后再使用本地连接；也可先使用远程连接。"
    else -> "上次 Wi-Fi Direct 操作结果尚未确认，请刷新无线状态后再使用本地连接。"
}
