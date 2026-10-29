package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** One dual-axis controller. Reads and persistent configuration only; no motor-start commands. */
@Composable
internal fun MotorConfigurationPage(
    state: MotorConfigurationUiState,
    connected: Boolean,
    accessAllowed: Boolean,
    blockedReason: String?,
    onRead: () -> Unit,
    onEdit: (MotorConfigurationField, String) -> Unit,
    onDiscard: () -> Unit,
    onApply: (MotorConfigurationWritePlan) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val snapshot = state.snapshot
    val canRead = connected && accessAllowed && !state.busy
    val canEdit = canRead && snapshot != null && !state.requiresRead
    val analysis = analyzeMotorConfigurationDraft(state)
    val plan = motorConfigurationWritePlan(state)
    var preview by remember(snapshot?.snapshotId, state.draftValues, state.requiresRead, state.busy,
        connected, accessAllowed, snapshot?.writeAllowed) { mutableStateOf<MotorConfigurationWritePlan?>(null) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }

    Column(modifier.testTag("motorConfigurationPage")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("电机配置", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text(if (connected) "已连接" else "未连接", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("motorConfigurationConnection"))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MotorConfigurationSection("设备与读取状态") {
                Text("同一个控制器管理左右电机，下面的通用限制同时作用于两侧。",
                    style = MaterialTheme.typography.bodySmall)
                Text(when {
                    state.reading -> "正在读取配置…"
                    state.applying -> "正在写入并回读核对…"
                    !connected -> "请先连接开发板"
                    !accessAllowed -> blockedReason ?: "控制尚未空闲，暂不能读取或写入电机配置"
                    state.requiresRead -> "此前读值已失效，请重新读取后再修改"
                    snapshot == null -> "尚未读取配置，请先读取当前设备"
                    else -> "显示上次读取的配置与状态"
                }, modifier = Modifier.testTag("motorConfigurationStatus"), fontWeight = FontWeight.Medium)
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (snapshot != null) {
                    Text("上次读取：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(snapshot.readAtMillis))}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("motorConfigurationReadTime"))
                    snapshot.deviceInfo.forEach { MotorConfigurationDetailRow(it) }
                    if (!snapshot.writeAllowed) Text(snapshot.writeBlockedReason ?: "板端当前不允许写入，请检查状态后重新读取",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("motorConfigurationWriteBlocked"))
                }
                state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("motorConfigurationMessage")) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("motorConfigurationError")) }
            }
            if (snapshot != null) MotorConfigurationSection("左右电机 · 读取时状态") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f).testTag("motorConfigurationLeftStatus"),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("左电机", fontWeight = FontWeight.Bold)
                        if (snapshot.leftMotorInfo.isEmpty()) Text("状态未返回", style = MaterialTheme.typography.bodySmall)
                        snapshot.leftMotorInfo.forEach { detail ->
                            Text("${detail.label}：${detail.value ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Column(Modifier.weight(1f).testTag("motorConfigurationRightStatus"),
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("右电机", fontWeight = FontWeight.Bold)
                        if (snapshot.rightMotorInfo.isEmpty()) Text("状态未返回", style = MaterialTheme.typography.bodySmall)
                        snapshot.rightMotorInfo.forEach { detail ->
                            Text("${detail.label}：${detail.value ?: "未知"}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("这些是读取瞬间的观测；零转速不代表驱动输出已禁能。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MotorConfigurationSection("通用电流限制") {
                MotorConfigurationEditor(MotorConfigurationField.MAX_OUTPUT_CURRENT, state, analysis, canEdit, onEdit)
            }
            MotorConfigurationSection("通用速度与加减速") {
                Text("速度上限是控制器配置，正反转按驱动器定义；不在此发送目标转速。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MotorConfigurationField.entries.filter { it != MotorConfigurationField.MAX_OUTPUT_CURRENT }.forEach { field ->
                    MotorConfigurationEditor(field, state, analysis, canEdit, onEdit)
                }
            }
            if (snapshot?.readOnlyInfo?.isNotEmpty() == true) MotorConfigurationSection("更多设备参数 · 只读") {
                TextButton(onClick = { detailsExpanded = !detailsExpanded },
                    modifier = Modifier.testTag("motorConfigurationDetailsToggle")) {
                    Text(if (detailsExpanded) "收起参数" else "查看其他配置")
                }
                if (detailsExpanded) snapshot.readOnlyInfo.forEach { MotorConfigurationDetailRow(it) }
            }
            if (state.writeResults.isNotEmpty()) MotorConfigurationSection("上次保存结果") {
                state.writeResults.forEach { result -> MotorConfigurationResultRow(result) }
                Text("仅标为回读一致的项目已确认；其他项目按各自结果处理，重新读取后再决定下一步。",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        // Inline review never opens a dialog or steals focus from another control surface.
        Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val reviewed = preview
                if (reviewed != null) {
                    Text("保存预览 · ${reviewed.changes.size} 项", fontWeight = FontWeight.Bold,
                        modifier = Modifier.testTag("motorConfigurationPreview"))
                    reviewed.changes.forEach { change ->
                        Text("${change.field.label}：${change.field.display(change.expectedRaw)} → ${change.field.display(change.raw)} ${change.field.unit}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("写入控制器并断电保存；每项写入后回读核对，发生异常会停止后续项目。",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { preview = null }, modifier = Modifier.weight(1f)
                            .testTag("motorConfigurationCancelPreview")) { Text("返回修改") }
                        Button(onClick = { if (canEdit && plan == reviewed) { preview = null; onApply(reviewed) } },
                            enabled = canEdit && plan == reviewed, modifier = Modifier.weight(1f)
                                .testTag("motorConfigurationConfirmApply")) { Text("确认写入并保存") }
                    }
                } else {
                    Text(when {
                        state.requiresRead -> "请重新读取；不会直接重复上次写入"
                        snapshot == null -> "读取后才可编辑和保存"
                        analysis.errors.isNotEmpty() -> "请修正输入后再预览"
                        analysis.changes.isNotEmpty() -> "${analysis.changes.size} 项待写入，先预览再确认"
                        else -> "尚无待写入的改动"
                    }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("motorConfigurationPending"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { keyboard?.hide(); onRead() }, enabled = canRead, modifier = Modifier.weight(1f)
                            .testTag("motorConfigurationRead")) { Text(if (state.reading) "读取中…" else "读取当前配置") }
                        Button(onClick = { if (canEdit && plan != null) { keyboard?.hide(); preview = plan } }, enabled = canEdit && plan != null,
                            modifier = Modifier.weight(1f).testTag("motorConfigurationReview")) { Text("应用已改项") }
                    }
                    if (state.draftValues.isNotEmpty()) TextButton(onClick = onDiscard, enabled = !state.busy,
                        modifier = Modifier.testTag("motorConfigurationDiscard")) { Text("撤销待写改动") }
                }
            }
        }
    }
}

@Composable
private fun MotorConfigurationSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun MotorConfigurationDetailRow(detail: MotorConfigurationDetail) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(detail.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(detail.value ?: "未知", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MotorConfigurationEditor(field: MotorConfigurationField, state: MotorConfigurationUiState,
    analysis: MotorConfigurationDraftAnalysis, enabled: Boolean,
    onEdit: (MotorConfigurationField, String) -> Unit) {
    val original = state.snapshot?.values?.get(field)
    val text = state.draftValues[field] ?: original?.let(field::display).orEmpty()
    val error = analysis.errors[field]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(value = text, onValueChange = { onEdit(field, it.take(24)) },
            label = { Text(field.label) }, suffix = { Text(field.unit) }, singleLine = true,
            enabled = enabled && original != null, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = if (field.decimalPlaces > 0) KeyboardType.Decimal else KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("motorConfigurationField_${field.id}"))
        Text("读取值：${original?.let(field::display) ?: "—"} ${field.unit} · 范围 ${field.rangeLabel}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("motorConfigurationOriginal_${field.id}"))
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("motorConfigurationValidation_${field.id}"))
        analysis.changes.firstOrNull { it.field == field }?.let { change ->
            Text("待写入：${field.display(change.expectedRaw)} → ${field.display(change.raw)} ${field.unit}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("motorConfigurationChange_${field.id}"))
        }
    }
}

@Composable
private fun MotorConfigurationResultRow(result: MotorConfigurationWriteResult) {
    val description = when (result.outcome) {
        MotorConfigurationWriteOutcome.VERIFIED -> "回读一致"
        MotorConfigurationWriteOutcome.ACKNOWLEDGED_UNVERIFIED -> "已收写入回执，回读未确认"
        MotorConfigurationWriteOutcome.WRITE_OUTCOME_UNKNOWN -> "写入结果未知，可能已更改"
        MotorConfigurationWriteOutcome.FAILED -> "处理失败，请重新读取核对"
        MotorConfigurationWriteOutcome.NOT_ATTEMPTED -> "尚未尝试写入"
    }
    Column(verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = Modifier.testTag("motorConfigurationResult_${result.field.id}")) {
        Text("${result.field.label}：$description", fontWeight = FontWeight.Medium,
            color = if (result.outcome == MotorConfigurationWriteOutcome.VERIFIED) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.error)
        Text("请求 ${result.field.display(result.requestedRaw)} ${result.field.unit} · 回读 ${result.observedRaw?.let(result.field::display) ?: "未确认"}",
            style = MaterialTheme.typography.bodySmall)
        result.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
    }
}
