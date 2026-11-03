package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/** Local drafts only. Reads and persistent writes require separate, explicit user actions. */
@Composable
internal fun ParkingCurrentEditor(
    boardState: BoardUiState,
    configuration: MotorConfigurationUiState,
    onRead: () -> Unit,
    onApply: (MotorConfigurationWritePlan) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val snapshot = configuration.snapshot
    val leftField = MotorConfigurationField.LEFT_PARKING_CURRENT
    val rightField = MotorConfigurationField.RIGHT_PARKING_CURRENT
    val maximumField = MotorConfigurationField.MAX_OUTPUT_CURRENT
    val leftOriginal = snapshot?.values?.get(leftField)
    val rightOriginal = snapshot?.values?.get(rightField)
    val maximum = snapshot?.values?.get(maximumField)
    var leftText by remember(snapshot, configuration.connection) { mutableStateOf(leftOriginal?.let(leftField::display).orEmpty()) }
    var rightText by remember(snapshot, configuration.connection) { mutableStateOf(rightOriginal?.let(rightField::display).orEmpty()) }
    // One confirmation per read baseline. A failure or uncertain result requires another read.
    var submitted by remember(snapshot, configuration.connection) { mutableStateOf(false) }
    val canRead = boardState.canReadMotorConfiguration && !configuration.busy
    val canEdit = boardState.canConfigureMotor && !configuration.busy && !configuration.requiresRead &&
        snapshot != null && snapshot.writeAllowed && snapshot.snapshotId.isNotBlank() &&
        snapshot.connection == configuration.connection && configuration.connection.isValid &&
        leftOriginal != null && rightOriginal != null && maximum != null && !submitted
    val analysis = parkingCurrentDraftAnalysis(configuration, leftText, rightText)
    val plan = parkingCurrentWritePlan(configuration, leftText, rightText)
    var preview by remember(snapshot, configuration.connection, configuration.requiresRead,
        configuration.busy, configuration.error, configuration.writeResults, configuration.draftValues,
        boardState.connected, boardState.busy, boardState.manualActive, boardState.followBlocksControl,
        boardState.motorActionUnconfirmed, boardState.pairingBusy, boardState.pairingUnconfirmed,
        boardState.videoServiceBusy, boardState.parkingUpdating, boardState.remoteMode,
        boardState.connectionHost, boardState.connectionPort, leftText, rightText) {
        mutableStateOf<MotorConfigurationWritePlan?>(null)
    }
    val status = when {
        !boardState.connected -> "请先连接小车，再读取当前配置。"
        boardState.manualActive -> "请先用页底按钮停用手动控制，再读取和设置电流。"
        boardState.followBlocksControl -> "自动跟随尚未结束或状态待核对，暂不能读取或设置。"
        configuration.reading -> "正在读取当前电机配置…"
        configuration.applying -> "正在保存并回读核对，请等待结果。"
        configuration.actioning -> "电机操作正在进行，请等待完成。"
        !boardState.canReadMotorConfiguration -> "控制或配网操作尚未结束，暂不能读取和设置。"
        boardState.motorActionUnconfirmed -> "电机状态待核对，请先重新读取。"
        submitted -> "本次已提交；再次设置前请先读取核对。"
        snapshot == null -> "先点“读取”，沿用小车当前的左右电流值。"
        configuration.requiresRead || snapshot.connection != configuration.connection -> "上次配置已失效，请重新读取后设置。"
        !snapshot.writeAllowed -> snapshot.writeBlockedReason ?: "板端暂不允许修改，请读取并核对控制状态。"
        leftOriginal == null || rightOriginal == null || maximum == null -> "左右电流或最大输出电流未完整读取，请重新读取。"
        else -> "左右独立调整；± 每次 1 A，也可输入小数。"
    }
    val leftRaw = leftField.parse(leftText)
    val rightRaw = rightField.parse(rightText)
    val upper = maximum?.coerceAtMost(6000L)
    val results = configuration.writeResults.filter { it.field == leftField || it.field == rightField }

    Column(modifier.fillMaxWidth().testTag("parkingCurrentEditor"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("驻车电流设置", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = {
                if (canRead) { keyboard?.hide(); preview = null; onRead() }
            }, enabled = canRead, modifier = Modifier.testTag("parkingCurrentRead")) { Text("读取") }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingCurrentEditorStatus"))
        // Once a write needs reconciliation, prioritize per-side outcomes in the fixed
        // viewport. Editing returns after an explicit successful read replaces the result.
        if (!(configuration.requiresRead && results.isNotEmpty())) {
            ParkingCurrentInput("左", "Left", leftText, canEdit, leftRaw, upper,
                onChange = { leftText = it.take(32); preview = null })
            ParkingCurrentInput("右", "Right", rightText, canEdit, rightRaw, upper,
                onChange = { rightText = it.take(32); preview = null })
        }
        Text("读取值：左 ${leftOriginal?.let(leftField::display) ?: "—"} / 右 ${rightOriginal?.let(rightField::display) ?: "—"} A · 上限 ${maximum?.let(maximumField::display) ?: "—"} A",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("parkingCurrentBaseline"))
        if (snapshot != null && !configuration.requiresRead && analysis.errors.isNotEmpty()) {
            Text(analysis.errors.entries.joinToString("；") { (field, error) ->
                "${if (field == leftField) "左" else "右"}：$error"
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("parkingCurrentValidation"))
        }
        if (leftRaw == 0L || rightRaw == 0L) {
            Text("任一侧为 0 A 时，电子驻车不能开启。", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("parkingCurrentZeroHint"))
        }
        val reviewed = preview
        if (reviewed != null && canEdit && reviewed == plan) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("parkingCurrentPreview")) {
                Text("确认保存参数（断电后保留）", fontWeight = FontWeight.Medium)
                reviewed.changes.forEach { change ->
                    Text("${if (change.field == leftField) "左" else "右"}：${change.field.display(change.expectedRaw)} A → ${change.field.display(change.raw)} A",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("parkingCurrentPreview_${change.field.id}"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { preview = null }, modifier = Modifier.weight(1f)
                        .testTag("parkingCurrentCancelPreview")) { Text("返回修改") }
                    Button(onClick = {
                        if (canEdit && !submitted && preview == plan && reviewed == plan) {
                            submitted = true
                            preview = null
                            keyboard?.hide()
                            onApply(reviewed)
                        }
                    }, modifier = Modifier.weight(1f).testTag("parkingCurrentConfirm")) { Text("确认应用") }
                }
            }
        } else Button(onClick = { if (canEdit && plan != null) { keyboard?.hide(); preview = plan } },
            enabled = canEdit && plan != null,
            modifier = Modifier.fillMaxWidth().testTag("parkingCurrentReview")) { Text("预览电流修改") }
        if (configuration.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        configuration.error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("parkingCurrentError"))
        }
        results.forEach { result ->
            val outcome = when (result.outcome) {
                MotorConfigurationWriteOutcome.VERIFIED -> "已保存并回读确认"
                MotorConfigurationWriteOutcome.ACKNOWLEDGED_UNVERIFIED -> "已收写入回执，回读未确认"
                MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN -> "结果未知，可能已写入"
                MotorConfigurationWriteOutcome.FAILED -> "失败，需重新读取核对"
                MotorConfigurationWriteOutcome.NOT_ATTEMPTED -> "未执行"
            }
            Text("${if (result.field == leftField) "左" else "右"}：$outcome · 请求 ${result.field.display(result.requestedRaw)} A / 回读 ${result.observedRaw?.let(result.field::display) ?: "未确认"} A",
                style = MaterialTheme.typography.bodySmall,
                color = if (result.outcome == MotorConfigurationWriteOutcome.VERIFIED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("parkingCurrentResult_${result.field.id}"))
        }
        if (results.any { it.outcome != MotorConfigurationWriteOutcome.VERIFIED }) {
            Text("未自动重试。请先重新读取核对实际值。", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("parkingCurrentResultHint"))
        }
    }
}

@Composable
private fun ParkingCurrentInput(label: String, tag: String, text: String, enabled: Boolean,
    raw: Long?, upper: Long?, onChange: (String) -> Unit) {
    val field = MotorConfigurationField.LEFT_PARKING_CURRENT // Both currents share the same decimal codec.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.width(20.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { if (enabled && raw != null && raw >= 100) onChange(field.display(raw - 100)) },
            enabled = enabled && raw != null && raw >= 100,
            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).testTag("parkingCurrent${tag}Decrease")) { Text("−") }
        OutlinedTextField(value = text, onValueChange = { if (enabled) onChange(it) }, enabled = enabled,
            singleLine = true, suffix = { Text("A") }, placeholder = { Text("未读取") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).testTag("parkingCurrent${tag}Input"))
        OutlinedButton(onClick = {
            if (enabled && raw != null && upper != null && raw + 100 <= upper) onChange(field.display(raw + 100))
        }, enabled = enabled && raw != null && upper != null && raw + 100 <= upper,
            contentPadding = PaddingValues(0.dp), modifier = Modifier.size(48.dp).testTag("parkingCurrent${tag}Increase")) { Text("＋") }
    }
}
