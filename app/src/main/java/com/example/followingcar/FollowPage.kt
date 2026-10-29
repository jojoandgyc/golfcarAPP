package com.example.followingcar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Settings and details stay in this window: opening a dialog would trigger focus-loss stop. */
@Composable
internal fun FollowPage(
    state: BoardUiState,
    onExecuteChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onInspect: () -> Unit,
    onOptionsChange: (FollowLaunchOptions) -> Unit,
    onCameraChange: (FollowCamera) -> Unit,
    onRefreshConfiguration: () -> Unit,
    onOpenConnection: () -> Unit,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    var settingsExpanded by rememberSaveable { mutableStateOf(!state.followActive && !state.followUncertain) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.followActive, state.followUncertain) {
        settingsExpanded = !state.followActive && !state.followUncertain
        if (state.followActive) scrollState.scrollTo(0)
    }
    val presentation = followPresentation(state)
    val options = state.followLaunchOptions
    val distance = if (options.reverseEnabled) options.reverseDistanceMeters ?: options.targetDistanceMeters ?: 1.5
        else options.targetDistanceMeters ?: 1.5
    val settingsEnabled = !state.busy && !state.manualActive && !state.followBlocksControl &&
        !state.videoServiceBusy && !state.followConfigurationLoading
    val hasProblem = state.followUncertain || state.followFirstFailure != null ||
        state.followError != null || state.error != null || state.followStatus?.fault != null

    Column(modifier.testTag("followPage")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("自动跟随", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(50), color = if (state.connected)
                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
                Text(if (state.connected) "● 已连接" else "○ 未连接",
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("followConnectionStatus"),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(scrollState).padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("followContent"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FollowSection {
                Text(presentation.title, style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("followStatusTitle"),
                    color = if (hasProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                Text(presentation.subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.followStopping || (state.followActive &&
                        (state.followStatus == null || state.followStatus.runState == "STARTING"))) {
                    Text(state.followMessage, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("followProgressMessage"))
                }
                if (state.followActive || state.followStatus != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("当前距离", style = MaterialTheme.typography.labelMedium)
                            Text(if (presentation.rangeFresh) "${state.followStatus?.distanceMeters?.display()} m" else "—",
                                style = MaterialTheme.typography.headlineMedium,
                                modifier = Modifier.testTag("followLiveDistance"))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("目标识别", style = MaterialTheme.typography.labelMedium)
                            Text(when {
                                !presentation.targetFresh -> "待确认"
                                state.followStatus?.targetDetected == true -> "已识别"
                                state.followStatus?.targetDetected == false -> "未检测到"
                                else -> "待确认"
                            }, style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(top = 5.dp).testTag("followLiveTarget"))
                        }
                    }
                }
                if (state.followUncertain) Text("上次控制是否结束尚未确认，控制保持锁定。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("followUncertainHint"))
                state.followFirstFailure?.let { failure ->
                    Text("首次故障：${failure.message}", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("followFirstFailure"))
                }
                (state.followError ?: state.error)?.let { error ->
                    Text("最近操作：$error", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("followError"))
                }
                if (state.followStatus != null || state.followFirstFailure != null) {
                    TextButton(onClick = { detailsExpanded = !detailsExpanded },
                        modifier = Modifier.testTag("followDetailsToggle")) {
                        Text(if (detailsExpanded) "收起运行详情" else "查看运行详情")
                    }
                    if (detailsExpanded) {
                        HorizontalDivider()
                        FollowStatusDetails(state)
                        state.followStatus?.fault?.let { Text("程序异常：$it", color = MaterialTheme.colorScheme.error) }
                        state.followFirstFailure?.let {
                            Text("首次故障时间：${it.time}\n会话：${it.context.runId ?: "尚未建立"}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            FollowSection {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (state.followUncertain) "下次启动设置" else "启动设置", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (!settingsExpanded) Text("${if (state.followExecute) "实车" else "影子"} · ${distance.display()} m · ${state.followCamera.shortLabel()}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { settingsExpanded = !settingsExpanded },
                        modifier = Modifier.testTag("followSettingsToggle")) { Text(if (settingsExpanded) "收起" else "展开") }
                }
                if (!settingsExpanded) Text("手势${if (options.gesturesEnabled) "：${options.resumeGesture.label}" else "关闭"} · 避障${if (options.avoidanceEnabled) "开启" else "关闭"} · 倒车${if (options.reverseEnabled) "开启" else "关闭"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (settingsExpanded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !state.followExecute, onClick = { onExecuteChange(false) },
                            enabled = settingsEnabled, label = { Text("影子测试") },
                            modifier = Modifier.weight(1f).testTag("followShadow"))
                        FilterChip(selected = state.followExecute, onClick = { onExecuteChange(true) },
                            enabled = settingsEnabled, label = { Text("实车跟随") },
                            modifier = Modifier.weight(1f).testTag("followExecute"))
                    }
                    Text(if (state.followUncertain) "以下为下次启动选项；上次会话是否退出、车辆是否停稳仍未确认。"
                        else if (state.followExecute) "实车模式会驱动电机；点击下方按钮启动。" else "仅计算跟随指令，不驱动电机。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("相机", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FollowCamera.entries.forEach { camera ->
                            FilterChip(selected = state.followCamera == camera, onClick = { onCameraChange(camera) },
                                enabled = settingsEnabled, label = { Text(camera.shortLabel(), style = MaterialTheme.typography.labelMedium) },
                                modifier = Modifier.weight(1f).testTag("followCamera_${camera.argument}"))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("跟随距离", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        OutlinedButton(onClick = {
                            val next = ((distance * 10).roundToInt() - 1).coerceIn(5, 50) / 10.0
                            onOptionsChange(options.copy(targetDistanceMeters = next, reverseDistanceMeters = next))
                        }, enabled = settingsEnabled && distance > 0.5,
                            modifier = Modifier.semantics { contentDescription = "减小跟随距离" }.testTag("followTargetDistanceDecrease")) { Text("−") }
                        Text("${distance.display()} m", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("followTargetDistance"))
                        OutlinedButton(onClick = {
                            val next = ((distance * 10).roundToInt() + 1).coerceIn(5, 50) / 10.0
                            onOptionsChange(options.copy(targetDistanceMeters = next, reverseDistanceMeters = next))
                        }, enabled = settingsEnabled && distance < 5,
                            modifier = Modifier.semantics { contentDescription = "增大跟随距离" }.testTag("followTargetDistanceIncrease")) { Text("+") }
                    }
                    HorizontalDivider()
                    FollowToggle("手势启动", "识别所选手势后启动或恢复跟随", options.gesturesEnabled,
                        settingsEnabled, "followGestures") { onOptionsChange(options.copy(gesturesEnabled = it)) }
                    if (options.gesturesEnabled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FollowResumeGesture.entries.forEach { gesture ->
                            FilterChip(selected = options.resumeGesture == gesture, enabled = settingsEnabled,
                                onClick = { onOptionsChange(options.copy(resumeGesture = gesture)) },
                                label = { Text(gesture.label) }, modifier = Modifier.weight(1f).testTag("followGesture_${gesture.argument}"))
                        }
                    }
                    FollowToggle("前方避障", "实验功能 · 开启后自动选用 Astra Pro", options.avoidanceEnabled,
                        settingsEnabled, "followAvoidance") { onOptionsChange(options.copy(avoidanceEnabled = it)) }
                    FollowToggle("倒车跟随", "过近时倒车，保持同一跟随距离", options.reverseEnabled,
                        settingsEnabled, "followReverse") {
                        onOptionsChange(options.copy(reverseEnabled = it, targetDistanceMeters = distance, reverseDistanceMeters = distance))
                    }
                    if (options.reverseEnabled) Text("倒车没有后方障碍物保护。前进和倒车均使用上方跟随距离。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FollowConfigurationCard(state, onRefreshConfiguration)
        }
        // This footer is outside the scrolling content, so stop never scrolls out of reach.
        Surface(shadowElevation = 8.dp, tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    state.followActive || state.followStopping -> Button(onClick = onStop,
                        enabled = !state.followStopping, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("stopFollow")) {
                        Text(if (state.followStopping) "正在停止…" else "停止跟随")
                    }
                    state.followUncertain && state.connected -> {
                        Text("检查确认旧会话结束后解锁", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onInspect, enabled = state.canInspectFollow,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("inspectFollowAvailability")) { Text("检查控制占用") }
                    }
                    !state.connected -> Button(onClick = onOpenConnection, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("followConnect")) { Text("前往连接开发板") }
                    else -> {
                        Text(when {
                            state.manualActive -> "请先结束手动驾驶"
                            state.followConfigurationLoading -> "正在读取车端配置…"
                            state.videoServiceBusy -> "正在处理视频服务…"
                            state.busy -> "正在处理上一项操作…"
                            !state.canStartFollow -> "请检查启动参数"
                            else -> "${if (state.followExecute) "实车模式" else "影子模式"} · ${distance.display()} m · 启动时自动关闭视频"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onStart, enabled = state.canStartFollow,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("startFollow")) {
                            Text(if (state.followExecute) "启动实车跟随" else "启动影子测试")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FollowSection(content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun FollowToggle(title: String, note: String, checked: Boolean, enabled: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.testTag(tag).semantics { contentDescription = title })
    }
}

private fun FollowCamera.shortLabel(): String = if (this == FollowCamera.CONFIG) "车端默认" else label
