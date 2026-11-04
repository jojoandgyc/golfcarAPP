package com.example.followingcar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.math.BigDecimal

/** Readback and editable drafts are separate; null mode means no confirmed mode. */
internal data class ParkingPageState(
    val mode: Boolean? = null,
    val leftCurrentAmps: Double? = null,
    val rightCurrentAmps: Double? = null,
    val maxCurrentAmps: Double? = null,
    val draftLeft: String = "",
    val draftRight: String = "",
    val connected: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val feedbackText: String? = null,
)

/** UI actions only. The owner of state explains connection/operation failures. */
@Composable
internal fun ParkingPage(
    state: ParkingPageState,
    onRefresh: () -> Unit,
    onLeftChange: (String) -> Unit,
    onRightChange: (String) -> Unit,
    onSave: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val left = parkingPageCurrent(state.draftLeft)
    val right = parkingPageCurrent(state.draftRight)
    val validDraft = left != null && right != null
    val validation = when {
        state.draftLeft.isBlank() || state.draftRight.isBlank() -> "请输入左右电流（0–60 A）"
        left == null && right == null -> "左右电流需为 0–60 A 的数字"
        left == null -> "左侧电流需为 0–60 A 的数字"
        right == null -> "右侧电流需为 0–60 A 的数字"
        else -> null
    }

    Column(modifier.fillMaxWidth().testTag("parkingPage"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("驻车", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(if (state.connected) "已连接" else "未连接", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("parkingPageConnection"))
            OutlinedButton(onClick = { if (!state.busy) onRefresh() }, enabled = !state.busy,
                modifier = Modifier.testTag("parkingPageRefresh")) { Text("刷新") }
        }
        Text(when (state.mode) {
            true -> "最近确认：开启驻车"
            false -> "最近确认：解除驻车"
            null -> "驻车状态待确认"
        }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("parkingPageMode"))
        Text("控制器未提供实时驻车状态", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag("parkingPageModeSource"))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ParkingModeAction("开启驻车", state.mode == true, !state.busy,
                { onEnabledChange(true) }, Modifier.weight(1f).testTag("parkingPageEnable"))
            ParkingModeAction("解除驻车", state.mode == false, !state.busy,
                { onEnabledChange(false) }, Modifier.weight(1f).testTag("parkingPageDisable"))
        }
        ParkingPageInput("左", "Left", state.draftLeft, onLeftChange)
        ParkingPageInput("右", "Right", state.draftRight, onRightChange)
        Text("读回：左 ${parkingPageDisplay(state.leftCurrentAmps)} / 右 ${parkingPageDisplay(state.rightCurrentAmps)} A · 输出上限 ${parkingPageDisplay(state.maxCurrentAmps)} A",
            style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("parkingPageReadback"))
        if (validation != null) {
            Text(validation, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("parkingPageValidation"))
        }
        Button(onClick = {
            if (!state.busy && validDraft) { keyboard?.hide(); onSave() }
        }, enabled = !state.busy && validDraft, modifier = Modifier.fillMaxWidth().testTag("parkingPageSave")) {
            Text(if (state.busy) "正在处理…" else "保存驻车电流")
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("parkingPageProgress"))
        state.feedbackText?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("parkingPageFeedback"))
        }
        state.message?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("parkingPageMessage"))
        }
        state.error?.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("parkingPageError"))
        }
    }
}

@Composable
private fun ParkingModeAction(text: String, isSelected: Boolean, enabled: Boolean,
    onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = { if (enabled) onClick() }, enabled = enabled,
        modifier = modifier.semantics { selected = isSelected },
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary),
        border = BorderStroke(1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
        Text(text)
    }
}

@Composable
private fun ParkingPageInput(label: String, tag: String, text: String, onChange: (String) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, modifier = Modifier.width(20.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onChange(parkingPageStep(text, -1)) }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(48.dp).testTag("parkingPage${tag}Decrease")) { Text("−") }
        OutlinedTextField(value = text, onValueChange = onChange, singleLine = true,
            suffix = { Text("A") }, placeholder = { Text("输入电流") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).testTag("parkingPage${tag}Input"))
        OutlinedButton(onClick = { onChange(parkingPageStep(text, 1)) }, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(48.dp).testTag("parkingPage${tag}Increase")) { Text("＋") }
    }
}

private fun parkingPageCurrent(text: String): Double? = text.trim().toDoubleOrNull()
    ?.takeIf { it.isFinite() && it in 0.0..60.0 }

private fun parkingPageDisplay(value: Double?): String = value?.takeIf { it.isFinite() && it >= 0.0 }
    ?.let { BigDecimal.valueOf(it).stripTrailingZeros().toPlainString() } ?: "—"

private fun parkingPageStep(text: String, delta: Int): String =
    parkingPageDisplay(((parkingPageCurrent(text) ?: 0.0) + delta).coerceIn(0.0, 60.0))
