package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun FollowConfigurationCard(
    state: BoardUiState,
    onRefresh: () -> Unit,
) {
    var showRaw by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("车端配置", style = MaterialTheme.typography.titleMedium)
                    Text("按需查看，不影响启动", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("followConfigurationToggle")) {
                    Text(if (expanded) "收起" else "查看")
                }
            }
            if (expanded) {
                OutlinedButton(onClick = onRefresh, enabled = state.canInspectFollow,
                    modifier = Modifier.fillMaxWidth().testTag("inspectFollowConfiguration")) {
                    Text(if (state.followConfigurationLoading) "正在检查配置…" else "读取车端配置")
                }
                if (state.followConfigurationLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.followConfigurationError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("followConfigurationError"))
                }
                state.followConfiguration?.let { configuration ->
                    Text("目标距离：${configuration.targetDistanceMeters.display()} m")
                    Text("速度上限：${configuration.maxRpm.display()} RPM；前进 ${configuration.forwardMaxRpm.display()} RPM")
                    Text("跟随启动驻车电流：${configuration.parkingCurrentAmps.display()} A")
                    Text("电机串口：${configuration.motorPort}")
                    Text("深度后端：${configuration.depthBackend}")
                    Text("相机参数与说明", style = MaterialTheme.typography.titleSmall)
                    cameraParameterOrder.mapNotNull { key ->
                        configuration.cameraDetails[key]?.let { key to it }
                    }.plus(
                        configuration.cameraDetails.filterKeys { it !in cameraParameterOrder }
                            .toSortedMap().toList()
                    ).forEach { (name, value) ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("$name：$value", style = MaterialTheme.typography.bodySmall)
                            cameraParameterNote(name, configuration)?.let { note ->
                                Text(note, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("followCameraNote_$name"))
                            }
                        }
                    }
                    Text("模型：${configuration.models.joinToString("、")}", style = MaterialTheme.typography.bodySmall)
                    Text("这里显示最近一次读取的结果。实际启动使用上方设置，不需要先读取配置。", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showRaw = !showRaw }, modifier = Modifier.testTag("followRawToggle")) {
                        Text(if (showRaw) "收起原始配置" else "展开原始配置")
                    }
                    if (showRaw) SelectionContainer {
                        Text(configuration.rawJson, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("followRawConfiguration"))
                    }
                }
            }
        }
    }
}

private val cameraParameterOrder = listOf(
    "width", "height", "fps", "fourcc", "capture_mode", "jpeg_decoder",
    "raw_output", "retry_interval_sec", "latest_drain_max",
)

/** Explain checked values in the App; the board's original configuration stays visible below. */
private fun cameraParameterNote(name: String, configuration: FollowConfiguration): String? {
    val orbbecSdk = configuration.depthBackend == "orbbec_sdk_v2" &&
        configuration.cameraDetails["capture_mode"] == "orbbec_sdk"
    return when (name) {
        "width" -> "彩色画面宽度，单位像素。"
        "height" -> "彩色画面高度，单位像素。"
        "fps" -> "每秒采集帧数的请求值，不代表每秒完成同样次数的识别。"
        "fourcc" -> if (orbbecSdk)
            "视频压缩格式配置；当前 Orbbec SDK 实际按 gemini335_color_format 选择格式，此项不生效。"
            else "彩色画面的 OpenCV 格式请求；是否支持取决于相机。"
        "capture_mode" -> if (orbbecSdk)
            "使用 Orbbec SDK，同步采集彩色图和深度图。"
            else "彩色画面采集模式；深度图由所选深度后端采集。"
        "jpeg_decoder" -> "历史解码器选项；当前连续跟随代码没有读取，修改此项不生效。"
        "raw_output" -> "旧链的录像保存位置选项；连续跟随自行管理录像，可用 --no-record 关闭。"
        "retry_interval_sec" -> "旧链启动相机的重试间隔，单位秒；当前连续跟随不使用。"
        "latest_drain_max" -> "旧取帧路径的最大丢帧数，用于减少延迟；当前连续跟随不读取此配置。"
        else -> null
    }
}

@Composable
internal fun FollowStatusDetails(state: BoardUiState) {
    val status = state.followStatus ?: return
    val freshSnapshot = state.connected && state.followActive && !state.followUncertain && status.statusAgeMs?.let { it in 0..2_000 } == true
    val presentation = followPresentation(state)
    val freshStatus = presentation.fresh
    val targetFresh = presentation.targetFresh
    val rangeFresh = presentation.rangeFresh
    val feedbackFresh = freshStatus && status.feedbackAgeMs?.let { it in 0..600 } == true
    val lifecycle = if (status.processExited) "已结束" else when (status.runState) {
        "STARTING" -> "启动中"
        "RUNNING" -> "运行中"
        "STOPPING" -> "停止中"
        "STOPPED" -> "已停止"
        "FAULT" -> "故障"
        else -> "未知"
    }
    Text(if (!freshSnapshot && !status.processExited) "软件状态：已过期或未知（上次$lifecycle）"
        else "软件状态：$lifecycle", fontWeight = FontWeight.Bold, modifier = Modifier.testTag("followRunState"))
    Text("本次会话：${if (status.execute) "实车" else "影子"} · ${status.camera.label}")
    Text(if (freshStatus) "控制行为：${status.behavior ?: "未知"}；原因：${status.reason ?: "未知"}"
        else if (status.processExited) "结束原因：${status.reason ?: "未知"}"
        else "控制行为与原因：未知或已过期", style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("followBehavior"))
    val targetLabel = when {
        !targetFresh -> "未知或已过期"
        status.targetDetected == false -> "未检测到"
        status.targetDetected == true -> status.targetTrackId ?: "编号未知"
        else -> "检测状态未知"
    }
    Text("目标：$targetLabel")
    Text("距离：${if (rangeFresh) status.distanceMeters?.let { "${it.display()} m" } ?: "未知" else "未知或已过期"}",
        modifier = Modifier.testTag("followDistance"))
    Text("指令轮速：${if (freshStatus) wheelPair(status.commandLeftRpm, status.commandRightRpm) else "未知或已过期"}",
        modifier = Modifier.testTag("followCommand"))
    Text("反馈轮速：${if (feedbackFresh) wheelPair(status.feedbackLeftRpm, status.feedbackRightRpm) else "未知或已过期"}",
        modifier = Modifier.testTag("followFeedback"))
    Text("数据年龄：状态 ${status.statusAgeMs.age()} · 目标 ${status.targetAgeMs.age()} · 测距 ${status.rangeAgeMs.age()} · 反馈 ${status.feedbackAgeMs.age()}",
        style = MaterialTheme.typography.bodySmall)
    Text("停止请求：${if (status.stopRequested) "已受理" else if (freshSnapshot) "未请求" else "未知"}")
    Text("停车通信回执：${status.stopAcknowledged.confirmation()}", modifier = Modifier.testTag("followStopAck"))
    Text("跟随进程：${if (status.processExited) "已退出" else "未确认退出"}", modifier = Modifier.testTag("followProcessExited"))
    Text("机械停稳：${if (freshStatus) status.physicalStopped.confirmation() else "未知"}", modifier = Modifier.testTag("followPhysicalStopped"))
}

internal fun Double.display(): String = String.format(Locale.ROOT, "%.2f", this).trimEnd('0').trimEnd('.')
private fun Long?.age(): String = this?.let { "$it ms" } ?: "未知"
private fun Boolean?.confirmation(): String = when (this) { true -> "已确认"; false -> "未确认成功"; null -> "未知" }
private fun wheelPair(left: Double?, right: Double?): String =
    "左 ${left?.display() ?: "未知"} / 右 ${right?.display() ?: "未知"} RPM"
