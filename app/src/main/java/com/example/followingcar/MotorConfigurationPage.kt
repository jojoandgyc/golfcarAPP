package com.example.followingcar

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.key
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

private enum class MotorPageCategory(val label: String, val groups: Set<MotorConfigurationGroup> = emptySet()) {
    OVERVIEW("概览"),
    MOTOR("电机", setOf(MotorConfigurationGroup.COMMON, MotorConfigurationGroup.RIGHT_MOTOR, MotorConfigurationGroup.LEFT_MOTOR)),
    PROTECTION("保护与模式", setOf(MotorConfigurationGroup.SYSTEM)),
    BRAKE("抱闸", setOf(MotorConfigurationGroup.BRAKE)),
    COMMUNICATION("通信", setOf(MotorConfigurationGroup.RS485, MotorConfigurationGroup.CAN, MotorConfigurationGroup.TTL)),
    PID("PID", setOf(MotorConfigurationGroup.PID_SPEED, MotorConfigurationGroup.PID_D, MotorConfigurationGroup.PID_Q, MotorConfigurationGroup.PID_PARKING)),
    OPERATIONS("操作"),
}

/** All I/O is explicit. Rendering, searching and selecting a tab never touches the controller. */
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
    onConnectionChange: ((MotorSerialSettings) -> Unit)? = null,
    onWriteModeChange: ((MotorConfigurationWriteMode) -> Unit)? = null,
    onAction: ((MotorActionPlan) -> Unit)? = null,
    actionUnconfirmed: Boolean = false,
    actionCancellationAllowed: Boolean = false,
    onCancelAction: (() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val snapshot = state.snapshot
    val canRead = connected && accessAllowed && !state.busy
    val canEdit = canRead && !actionUnconfirmed && snapshot != null && !state.requiresRead
    val analysis = analyzeMotorConfigurationDraft(state)
    val plan = motorConfigurationWritePlan(state)
    var category by rememberSaveable { mutableStateOf(MotorPageCategory.OVERVIEW) }
    var search by rememberSaveable(category) { mutableStateOf("") }
    var preview by remember(snapshot?.snapshotId, state.draftValues, state.requiresRead, state.busy,
        state.connection, state.writeMode, connected, accessAllowed, actionUnconfirmed, snapshot?.writeAllowed, category) {
        mutableStateOf<MotorConfigurationWritePlan?>(null)
    }

    Column(modifier.testTag("motorConfigurationPage")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("电机配置", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(if (connected) "已连接" else "未连接", style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.testTag("motorConfigurationConnection"))
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MotorPageCategory.entries.forEach { item ->
                FilterChip(selected = category == item, onClick = { keyboard?.hide(); category = item },
                    label = { Text(item.label) }, modifier = Modifier.testTag("motorConfigurationTab_${item.name}"))
            }
        }
        // Keep the lock / stale-snapshot reason visible in every category.
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(when {
                state.reading -> "正在读取配置…"
                state.applying -> "正在写入并回读核对…"
                state.actioning -> "正在执行操作及收尾，其他操作暂不可用…"
                !connected -> "请先连接开发板"
                !accessAllowed -> blockedReason ?: "控制尚未空闲，暂不能访问电机配置"
                state.requiresRead -> "此前读值已失效，请重新读取后再修改或操作"
                snapshot == null -> "尚未读取配置，请先读取当前设备"
                else -> "上次读取的快照 · ${snapshot.values.size} 项配置"
            }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("motorConfigurationStatus"))
            if (snapshot != null && !snapshot.writeAllowed) Text(
                snapshot.writeBlockedReason ?: "板端当前不允许写入，请检查状态后重新读取",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("motorConfigurationWriteBlocked"))
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (actionUnconfirmed && !state.actioning) Text(
                "上次电机操作或停止收尾未确认，控制保持锁定。请主动读取，核对板端空闲、零转速/输出及通讯来源；读取观测不等同物理停稳。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("motorActionRecoveryRequired"))
            if (state.actioning && actionCancellationAllowed && onCancelAction != null) {
                OutlinedButton(onClick = onCancelAction, modifier = Modifier.fillMaxWidth().testTag("motorActionCancelRunning")) {
                    Text("取消操作并核对状态")
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("motorConfigurationError")) }
            if (state.writeResults.isNotEmpty() && category != MotorPageCategory.OVERVIEW) {
                TextButton(onClick = { keyboard?.hide(); category = MotorPageCategory.OVERVIEW },
                    modifier = Modifier.testTag("motorConfigurationShowResults")) {
                    Text("查看写入结果 · ${state.writeResults.count { it.outcome == MotorConfigurationWriteOutcome.VERIFIED }} / ${state.writeResults.size} 项已核对")
                }
            }
        }
        key(category) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (category) {
                    MotorPageCategory.OVERVIEW -> MotorOverview(state)
                    MotorPageCategory.OPERATIONS -> MotorOperations(state, canEdit, onAction)
                    else -> {
                        if (category == MotorPageCategory.COMMUNICATION) {
                            MotorConnectionEditor(state.connection, canRead && onConnectionChange != null, onConnectionChange)
                        }
                        MotorWriteMode(state.writeMode, canRead && !actionUnconfirmed && onWriteModeChange != null, onWriteModeChange)
                        OutlinedTextField(value = search, onValueChange = { search = it.take(80) }, singleLine = true,
                            label = { Text("搜索当前分类（名称 / 寄存器）") },
                            modifier = Modifier.fillMaxWidth().testTag("motorConfigurationSearch"))
                        val fields = MotorConfigurationField.entries.filter { field ->
                            field.group in category.groups && (search.isBlank() ||
                                listOf(field.label, field.id, field.addressLabel, field.note.orEmpty())
                                    .any { it.contains(search.trim(), ignoreCase = true) })
                        }
                        if (fields.isEmpty()) Text("当前分类没有匹配的参数", modifier = Modifier.testTag("motorConfigurationNoMatches"))
                        fields.groupBy { it.group }.entries.forEachIndexed { index, (group, members) ->
                            key(group) {
                                var expanded by rememberSaveable { mutableStateOf(index == 0) }
                                MotorConfigurationSection(group.label, expanded = expanded || search.isNotBlank(),
                                    onToggle = if (search.isBlank()) ({ expanded = !expanded }) else null,
                                    tag = "motorConfigurationGroup_${group.name}") {
                                    members.forEach { field ->
                                        MotorConfigurationEditor(field, state, analysis, canEdit, onEdit)
                                        if (field != members.last()) HorizontalDivider()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // Reviews stay inline: a system dialog must not change focus on another control surface.
        Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val reviewed = preview
                if (reviewed != null) {
                    Text("${if (reviewed.writeMode == MotorConfigurationWriteMode.TEMPORARY) "临时写入" else "保存"}预览 · ${reviewed.changes.size} 项",
                        fontWeight = FontWeight.Bold, modifier = Modifier.testTag("motorConfigurationPreview"))
                    Column(Modifier.heightIn(max = 150.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        reviewed.changes.forEach { change ->
                            Text("${change.field.label} · ${change.field.addressLabel}\n${motorValueLabel(change.field, change.expectedRaw)} → ${motorValueLabel(change.field, change.raw)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(if (reviewed.writeMode == MotorConfigurationWriteMode.PERSISTENT)
                        "写入控制器并断电保存；逐项回读核对，异常时停止后续项目。"
                    else "仅临时设置，不保存到断电存储；逐项回读核对，异常时停止后续项目。",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { preview = null }, modifier = Modifier.weight(1f)
                            .testTag("motorConfigurationCancelPreview")) { Text("返回修改") }
                        Button(onClick = { if (canEdit && plan == reviewed) { preview = null; onApply(reviewed) } },
                            enabled = canEdit && plan == reviewed, modifier = Modifier.weight(1f)
                                .testTag("motorConfigurationConfirmApply")) {
                            Text(if (reviewed.writeMode == MotorConfigurationWriteMode.TEMPORARY) "确认临时写入" else "确认写入并保存")
                        }
                    }
                } else {
                    Text(when {
                        state.requiresRead -> "请重新读取；不会直接重复上次写入"
                        snapshot == null -> "读取后才可编辑和操作"
                        category == MotorPageCategory.OPERATIONS -> "单次操作在上方预览并确认"
                        analysis.errors.isNotEmpty() -> "${analysis.errors.size} 项输入需修正，请在对应分类查看"
                        analysis.changes.isNotEmpty() -> "${analysis.changes.size} 项待写入 · ${state.writeMode.label}"
                        else -> "没有待写入的配置改动"
                    }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("motorConfigurationPending"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { if (canRead) { keyboard?.hide(); onRead() } }, enabled = canRead,
                            modifier = Modifier.weight(1f).testTag("motorConfigurationRead")) {
                            Text(if (state.reading) "读取中…" else "读取当前配置")
                        }
                        if (category != MotorPageCategory.OPERATIONS) {
                            Button(onClick = { if (canEdit && plan != null) { keyboard?.hide(); preview = plan } },
                                enabled = canEdit && plan != null, modifier = Modifier.weight(1f).testTag("motorConfigurationReview")) { Text("预览配置改动") }
                        }
                    }
                    if (state.draftValues.isNotEmpty() && category != MotorPageCategory.OPERATIONS) TextButton(onClick = onDiscard, enabled = !state.busy,
                        modifier = Modifier.testTag("motorConfigurationDiscard")) { Text("撤销待写改动") }
                }
            }
        }
    }
}

@Composable
private fun MotorOverview(state: MotorConfigurationUiState) {
    val snapshot = state.snapshot
    MotorConfigurationSection("设备与连接") {
        Text("同一控制器管理左右电机；电机、保护、抱闸、通信及 PID 参数按分类查看。", style = MaterialTheme.typography.bodySmall)
        Text("RS485 从站 ${state.connection.slave} · ${state.connection.baudRate} bps · ${motorParityLabel(state.connection.parityCode)}",
            style = MaterialTheme.typography.bodySmall)
        if (snapshot != null) {
            Text("上次读取：${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(snapshot.readAtMillis))}",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("motorConfigurationReadTime"))
            snapshot.deviceInfo.forEach { MotorConfigurationDetailRow(it) }
        }
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("motorConfigurationMessage")) }
    }
    if (snapshot != null) {
        MotorConfigurationSection("左右电机 · 读取时状态") {
            Text("这些是读取瞬间的观测；零转速不代表驱动输出已禁能。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("左电机", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("motorConfigurationLeftStatus"))
            if (snapshot.leftMotorInfo.isEmpty()) Text("状态未返回", style = MaterialTheme.typography.bodySmall)
            snapshot.leftMotorInfo.forEach { MotorConfigurationDetailRow(it) }
            HorizontalDivider()
            Text("右电机", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("motorConfigurationRightStatus"))
            if (snapshot.rightMotorInfo.isEmpty()) Text("状态未返回", style = MaterialTheme.typography.bodySmall)
            snapshot.rightMotorInfo.forEach { MotorConfigurationDetailRow(it) }
        }
        if (snapshot.readOnlyInfo.isNotEmpty()) {
            var expanded by rememberSaveable { mutableStateOf(false) }
            MotorConfigurationSection("其他寄存器 · 只读", expanded, { expanded = !expanded }, "motorConfigurationDetailsToggle") {
                snapshot.readOnlyInfo.forEach { MotorConfigurationDetailRow(it) }
            }
        }
    }
    if (state.writeResults.isNotEmpty()) MotorConfigurationSection("上次配置写入结果") {
        state.writeResults.forEach { MotorConfigurationResultRow(it) }
        Text("仅“回读一致”代表该项已核对。结果未知时先重新读取。", style = MaterialTheme.typography.bodySmall)
    }
    state.actionResult?.let { MotorActionResultCard(it) }
}

@Composable
private fun MotorWriteMode(mode: MotorConfigurationWriteMode, enabled: Boolean,
    onChange: ((MotorConfigurationWriteMode) -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MotorConfigurationWriteMode.entries.forEach { item ->
                FilterChip(selected = mode == item, onClick = { if (enabled) onChange?.invoke(item) }, enabled = enabled,
                    label = { Text(if (item == MotorConfigurationWriteMode.PERSISTENT) "断电保存" else "临时设置") },
                    modifier = Modifier.testTag("motorConfigurationWriteMode_${item.name}"))
            }
        }
        Text(if (mode == MotorConfigurationWriteMode.PERSISTENT) "配置写入后保存到控制器；下方只编辑草稿。"
            else "临时值不写入断电存储；PID 仅支持保存。三项转速参数分两帧临时写入，异常后需重新读取。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MotorConfigurationSection(title: String, expanded: Boolean = true, onToggle: (() -> Unit)? = null,
    tag: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onToggle != null) TextButton(onClick = onToggle,
                modifier = Modifier.fillMaxWidth().then(if (tag == null) Modifier else Modifier.testTag(tag))) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (expanded) "收起" else "展开")
            } else Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (expanded) content()
        }
    }
}

@Composable
private fun MotorConfigurationDetailRow(detail: MotorConfigurationDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(detail.label + (detail.address?.let { " · 0x%04X".format(it) } ?: ""), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(detail.value ?: "未知", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        }
        detail.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

private fun motorValueLabel(field: MotorConfigurationField, raw: Long): String =
    field.choices[raw]?.let { "$it（$raw）" } ?: "${field.display(raw)} ${field.unit}".trim()

@Composable
private fun MotorConfigurationEditor(field: MotorConfigurationField, state: MotorConfigurationUiState,
    analysis: MotorConfigurationDraftAnalysis, enabled: Boolean, onEdit: (MotorConfigurationField, String) -> Unit) {
    val original = state.snapshot?.values?.get(field)
    val text = state.draftValues[field] ?: original?.let(field::display).orEmpty()
    val error = analysis.errors[field]
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        if (field.choices.isNotEmpty()) {
            MotorChoice(label = field.label, value = field.parse(text)?.let { motorValueLabel(field, it) } ?: "未读取",
                choices = field.choices.map { (raw, label) -> raw.toString() to "$label（$raw）" },
                enabled = enabled && original != null, tag = "motorConfigurationField_${field.id}",
                onSelect = { onEdit(field, it) })
        } else OutlinedTextField(value = text, onValueChange = { onEdit(field, it.take(32)) },
            label = { Text(field.label) }, suffix = { Text(field.unit) }, singleLine = true,
            enabled = enabled && original != null, isError = error != null,
            keyboardOptions = KeyboardOptions(keyboardType = when {
                field.phasePair -> KeyboardType.Text
                // Android's decimal keypad need not offer a minus key.
                field.signed -> KeyboardType.Ascii
                field.decimalPlaces > 0 -> KeyboardType.Decimal
                else -> KeyboardType.Number
            }), modifier = Modifier.fillMaxWidth().testTag("motorConfigurationField_${field.id}"))
        Text("${field.addressLabel} · 读取值：${original?.let { motorValueLabel(field, it) } ?: "—"}",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("motorConfigurationOriginal_${field.id}"))
        if (field.choices.isEmpty()) Text("范围：${field.rangeLabel}", style = MaterialTheme.typography.bodySmall)
        field.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (!field.supportsTemporary) Text("仅支持完整参数保存", style = MaterialTheme.typography.labelSmall)
        if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("motorConfigurationValidation_${field.id}"))
        analysis.changes.firstOrNull { it.field == field }?.let { change ->
            Text("待写入：${motorValueLabel(field, change.expectedRaw)} → ${motorValueLabel(field, change.raw)}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("motorConfigurationChange_${field.id}"))
        }
    }
}

/** Menu keys are protocol values, labels always include their decoded meaning. */
@Composable
private fun MotorChoice(label: String, value: String, choices: List<Pair<String, String>>, enabled: Boolean,
    tag: String, onSelect: (String) -> Unit) {
    var expanded by remember(enabled) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag(tag)) {
                Text(value, Modifier.weight(1f)); Text("▾")
            }
            DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
                choices.forEach { (key, title) ->
                    DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; if (enabled) onSelect(key) },
                        modifier = Modifier.testTag("${tag}_option_$key"))
                }
            }
        }
    }
}

private fun motorParityLabel(code: Int): String = when (code) {
    0 -> "无校验 / 1 停止位"
    1 -> "偶校验 / 1 停止位"
    2 -> "奇校验 / 1 停止位"
    3 -> "无校验 / 2 停止位"
    else -> "未知校验方式"
}

@Composable
private fun MotorConnectionEditor(connection: MotorSerialSettings, enabled: Boolean,
    onChange: ((MotorSerialSettings) -> Unit)?) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var slave by remember(connection) { mutableStateOf(connection.slave.toString()) }
    var baud by remember(connection) { mutableStateOf(connection.baudRate) }
    var parity by remember(connection) { mutableStateOf(connection.parityCode) }
    val candidate = slave.toIntOrNull()?.let { MotorSerialSettings(it, baud, parity) }
    MotorConfigurationSection("本次 RS485 连接", expanded, { expanded = !expanded }, "motorConnectionToggle") {
        Text("仅设置 App 下次访问驱动器使用的参数，不会写入驱动器。修改后需重新读取。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(slave, { slave = it.take(3) }, label = { Text("从站地址（1–254）") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, enabled = enabled,
            modifier = Modifier.fillMaxWidth().testTag("motorConnectionSlave"))
        MotorChoice("波特率", "$baud bps", listOf(9600, 19200, 38400, 57600, 115200).map { it.toString() to "$it bps" },
            enabled, "motorConnectionBaud") { baud = it.toInt() }
        MotorChoice("校验方式", motorParityLabel(parity), (0..3).map { it.toString() to motorParityLabel(it) },
            enabled, "motorConnectionParity") { parity = it.toInt() }
        OutlinedButton(onClick = { if (enabled && candidate?.isValid == true && candidate != connection) onChange?.invoke(candidate) },
            enabled = enabled && candidate?.isValid == true && candidate != connection,
            modifier = Modifier.fillMaxWidth().testTag("motorConnectionApply")) { Text("使用此连接参数") }
    }
}

@Composable
private fun MotorOperations(state: MotorConfigurationUiState, canEdit: Boolean, onAction: ((MotorActionPlan) -> Unit)?) {
    val keyboard = LocalSoftwareKeyboardController.current
    var action by rememberSaveable { mutableStateOf(MotorAction.entries.first()) }
    var value by rememberSaveable(action) { mutableStateOf(action.choices.keys.firstOrNull()?.toString() ?: "0") }
    var duration by rememberSaveable(action) { mutableStateOf(if (action.requiresDuration) "1000" else "0") }
    // Stop/cancel may be allowed when ordinary parameter writes are blocked. The shared
    // validator owns that distinction; the UI must not add a writeAllowed gate here.
    val enabled = canEdit && onAction != null
    val raw = value.toLongOrNull()
    val effectiveDuration = if (raw != null && action.durationRange(raw).last == 0) "0" else duration
    val actionPlan = motorActionPlan(state, action, value, effectiveDuration)
    val validation = motorActionValidationError(state, action, value, effectiveDuration)
    var preview by remember(state.snapshot?.snapshotId, state.connection, state.requiresRead, state.busy,
        state.snapshot?.writeAllowed, state.snapshot?.stopAllowed, enabled, action, value, duration) { mutableStateOf<MotorActionPlan?>(null) }
    MotorConfigurationSection("单次电机操作") {
        Text("操作会向驱动器发送控制指令；每次先查看具体值和影响，再单独确认。", style = MaterialTheme.typography.bodySmall)
        MotorChoice("操作", action.label, MotorAction.entries.map { it.id to it.label }, enabled,
            "motorActionSelector") { id -> action = MotorAction.entries.first { it.id == id } }
        Text(action.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action.choices.isNotEmpty()) MotorChoice("请求值", action.choices[value.toLongOrNull()]?.let { "$it（$value）" } ?: value,
            action.choices.map { (raw, label) -> raw.toString() to "$label（$raw）" }, enabled, "motorActionValue") { value = it }
        else if (action.minRaw != action.maxRaw) OutlinedTextField(value, { value = it.take(16) },
            label = { Text("${action.label}值") }, suffix = { Text(action.unit) }, singleLine = true, enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = if (action.minRaw < 0) KeyboardType.Ascii else KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("motorActionValue"))
        else Text("请求值：${action.minRaw} ${action.unit}", style = MaterialTheme.typography.bodySmall)
        if (action.requiresDuration && raw != null && action.durationRange(raw).last > 0) {
            OutlinedTextField(duration, { duration = it.take(6) }, label = { Text("最长执行时间（ms）") },
                singleLine = true, enabled = enabled, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag("motorActionDuration"))
            Text("范围：${action.durationRange(raw).first}–${action.durationRange(raw).last} ms；到时执行收尾停止。",
                style = MaterialTheme.typography.bodySmall)
        }
        if (validation != null) Text(validation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("motorActionValidation"))
        val reviewed = preview
        if (reviewed == null) Button(onClick = { if (enabled && actionPlan != null) { keyboard?.hide(); preview = actionPlan } },
            enabled = enabled && actionPlan != null, modifier = Modifier.fillMaxWidth().testTag("motorActionReview")) { Text("预览本次操作") }
        else {
            HorizontalDivider()
            Text("确认本次操作", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("motorActionPreview"))
            Text("${reviewed.action.label} · ${reviewed.action.addressLabel}")
            Text("请求：${reviewed.action.choices[reviewed.value] ?: reviewed.value.toString()} ${reviewed.action.unit}")
            if (reviewed.durationMs > 0) Text("最长 ${reviewed.durationMs} ms，随后执行停止收尾")
            Text(reviewed.action.note, style = MaterialTheme.typography.bodySmall)
            Text("收到写入回执不等于实机动作已完成；请核对操作结果。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { preview = null }, modifier = Modifier.weight(1f).testTag("motorActionCancel")) { Text("返回修改") }
                Button(onClick = { if (enabled && actionPlan == reviewed) { preview = null; onAction?.invoke(reviewed) } },
                    enabled = enabled && actionPlan == reviewed, modifier = Modifier.weight(1f).testTag("motorActionConfirm")) { Text("确认执行") }
            }
        }
    }
    state.actionResult?.let { MotorActionResultCard(it) }
}

@Composable
private fun MotorActionResultCard(result: MotorActionResult) {
    MotorConfigurationSection("上次操作结果") {
        Text(result.action.label, fontWeight = FontWeight.Bold)
        Text(when (result.outcome) {
            MotorActionOutcome.ACKNOWLEDGED -> "收到指令回执；不代表实机动作已完成"
            MotorActionOutcome.WRITE_OUTCOME_UNKNOWN -> "指令结果未知，可能已执行，请先核对状态"
            MotorActionOutcome.NOT_ATTEMPTED -> "未发送指令"
        }, modifier = Modifier.testTag("motorActionResult"))
        result.cleanupConfirmed?.let { Text(if (it) "停止收尾回执已确认" else "停止收尾未确认，请核对实车状态", style = MaterialTheme.typography.bodySmall) }
        if (result.reconnectRequired) Text("通信参数可能已变化，请使用正确参数重新连接并读取", style = MaterialTheme.typography.bodySmall)
        result.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
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
    Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.testTag("motorConfigurationResult_${result.field.id}")) {
        Text("${result.field.label}：$description", fontWeight = FontWeight.Medium,
            color = if (result.outcome == MotorConfigurationWriteOutcome.VERIFIED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Text("请求 ${motorValueLabel(result.field, result.requestedRaw)} · 回读 ${result.observedRaw?.let { motorValueLabel(result.field, it) } ?: "未确认"}",
            style = MaterialTheme.typography.bodySmall)
        result.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        HorizontalDivider()
    }
}
