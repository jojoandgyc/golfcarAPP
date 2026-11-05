package com.example.followingcar

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/** No side effects until the user explicitly requests a start or a read-only occupancy check. */
@Composable
internal fun ManualDriveStartButton(
    state: BoardUiState,
    onEnable: () -> Unit,
    onInspect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val inspect = state.driveCleanupUnconfirmed
    val enabled = if (inspect) state.canInspectFollow else state.canEnableDrive
    Column(modifier.fillMaxWidth()) {
        if (!enabled) state.driveStartBlockReason?.let { reason ->
            Text(reason, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().testTag("driveStartBlockReason"))
        }
        Button(onClick = if (inspect) onInspect else onEnable, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("enableDrive")) {
            Text(if (inspect) "检查控制占用" else "启用手动控制")
        }
    }
}
