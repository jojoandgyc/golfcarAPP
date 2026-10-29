package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun RemoteConnectionCard(state: BoardUiState, onImport: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("remoteConnectionCard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("服务器远程连接", style = MaterialTheme.typography.titleMedium)
            val profile = state.remoteProfile
            if (profile != null) {
                Text(profile.host, modifier = Modifier.testTag("remoteServerHost"))
                Text("设备证书有效期至 " + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT)
                    .format(Date(profile.certificateExpiresAtEpochMillis)), style = MaterialTheme.typography.bodySmall)
            } else Text("首次使用请导入为这台手机生成的 .carremote 连接配置。")
            Text("控制、配置和实时画面通过中转服务器连接，无需另外开启 VPN。断网后保持停车保护，重连不会自动恢复行驶。",
                style = MaterialTheme.typography.bodySmall)
            state.remoteProfileError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("remoteProfileError"))
            }
            if (state.remoteProfileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
            OutlinedButton(onClick = onImport, enabled = state.canManageConnectionMode,
                modifier = Modifier.fillMaxWidth().testTag("importRemoteProfile")) {
                Text(if (profile == null) "导入远程配置" else "更换远程配置")
            }
        }
    }
}
