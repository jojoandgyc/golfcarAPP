package com.example.followingcar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlin.math.roundToInt

@Composable
internal fun FollowConfigurationCard(
    state: BoardUiState,
    onCameraChange: (FollowCamera) -> Unit,
    onRefresh: () -> Unit,
) {
    var showRaw by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("自动跟随配置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("选择相机后检查车端配置；检查不会打开相机或电机。", style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FollowCamera.entries.forEach { camera ->
                    FilterChip(
                        selected = state.followCamera == camera,
                        onClick = { onCameraChange(camera) },
                        label = { Text(camera.label) },
                        enabled = !state.busy && !state.followActive && !state.manualActive,
                        modifier = Modifier.testTag("followCamera_${camera.argument}"),
                    )
                }
            }
            OutlinedButton(onClick = onRefresh, enabled = state.canInspectFollow,
                modifier = Modifier.fillMaxWidth().testTag("inspectFollowConfiguration")) {
                Text(if (state.followConfigurationLoading) "正在检查配置…" else "检查 / 刷新车端配置")
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
                Text("静态检查通过不代表设备已就绪。配置更改后需重新检查并启动。", style = MaterialTheme.typography.bodySmall)
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
internal fun FollowControlCard(
    state: BoardUiState,
    onExecuteChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCheckAvailability: () -> Unit,
    onOptionsChange: (FollowLaunchOptions) -> Unit = {},
) {
    val options = state.followLaunchOptions
    val canEditOptions = !state.busy && !state.followBlocksControl && !state.manualActive
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("自动跟随", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            FollowDistanceStepper(
                label = "目标跟随距离",
                value = options.targetDistanceMeters,
                defaultValue = 1.5,
                enabled = canEditOptions,
                onValueChange = { onOptionsChange(options.copy(targetDistanceMeters = it)) },
                tag = "followTargetDistance",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("启用手势", modifier = Modifier.weight(1f))
                Switch(checked = options.gesturesEnabled,
                    onCheckedChange = { onOptionsChange(options.copy(gesturesEnabled = it)) },
                    enabled = canEditOptions,
                    modifier = Modifier.testTag("followGestures"))
            }
            if (options.gesturesEnabled) {
                Text("启动 / 恢复手势", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FollowResumeGesture.entries.forEach { gesture ->
                        FilterChip(
                            selected = options.resumeGesture == gesture,
                            onClick = { onOptionsChange(options.copy(resumeGesture = gesture)) },
                            label = { Text(gesture.label) },
                            enabled = canEditOptions,
                            modifier = Modifier.testTag("followGesture_${gesture.argument}"),
                        )
                    }
                }
                Text("启用手势后，识别所选手势才会启动或恢复跟随。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("启用避障", modifier = Modifier.weight(1f))
                Switch(checked = options.avoidanceEnabled,
                    onCheckedChange = { onOptionsChange(options.copy(avoidanceEnabled = it)) },
                    enabled = canEditOptions,
                    modifier = Modifier.testTag("followAvoidance"))
            }
            Text("避障为实验功能；开启时自动选用 Astra Pro，切换到其他相机会关闭避障。",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("启用倒车跟随", modifier = Modifier.weight(1f))
                Switch(checked = options.reverseEnabled,
                    onCheckedChange = { onOptionsChange(options.copy(reverseEnabled = it)) },
                    enabled = canEditOptions,
                    modifier = Modifier.testTag("followReverse"))
            }
            if (options.reverseEnabled) {
                FollowDistanceStepper(
                    label = "倒车目标距离",
                    value = options.reverseDistanceMeters,
                    defaultValue = 1.4,
                    enabled = canEditOptions,
                    onValueChange = { onOptionsChange(options.copy(reverseDistanceMeters = it)) },
                    tag = "followReverseDistance",
                )
                Text("倒车没有后方障碍物保护；开启时，倒车目标距离也是实际前进跟随距离。",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("允许实车驱动电机", modifier = Modifier.weight(1f))
                Switch(checked = state.followExecute, onCheckedChange = onExecuteChange,
                    enabled = !state.busy && !state.followBlocksControl && !state.manualActive,
                    modifier = Modifier.testTag("followExecute").semantics { contentDescription = "允许实车驱动电机" })
            }
            Text(if (state.followExecute) "实车模式：启动后可根据目标自动驱动车辆。"
                else "影子模式：打开相机和模型，计算跟随指令，不打开电机。",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("followMode"))
            Text(state.followMessage, modifier = Modifier.testTag("followMessage"))
            state.followFirstFailure?.let { first ->
                Text("本次会话首次异常 · ${first.time}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error)
                Text("${first.event}\n${first.message}", modifier = Modifier.testTag("followFirstFailure"),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            state.followError?.let {
                if (state.followFirstFailure != null) Text("最近操作错误", style = MaterialTheme.typography.labelMedium)
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("followError"))
            }
            state.followStatus?.let { FollowStatusDetails(it, state.followUncertain) }
            if (state.followActive) {
                Button(onClick = onStop, enabled = !state.followStopping,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth().testTag("stopFollow")) {
                    Text(if (state.followStopping) "正在停止跟随…" else "停止自动跟随")
                }
                Text("停止会结束本次跟随进程；进程退出与车轮停稳分别显示。离开 App 会请求停止。",
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = onStart, enabled = state.canStartFollow,
                    modifier = Modifier.fillMaxWidth().testTag("startFollow")) {
                    Text(if (state.followExecute) "启动实车跟随" else "启动影子跟随")
                }
                if (state.followUncertain) {
                    Text("上次控制是否结束尚未确认。启动跟随、摇杆和终端控制保持锁定。",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Text(if (!state.connected) "请到连接页重新连接，连接成功后会自动检查控制占用，不会自动恢复跟随。"
                        else if (state.busy) "正在恢复连接或检查占用，控制保持锁定。"
                        else "检查只确认控制资源是否释放；不会自动启动跟随，也不代表车轮已经停稳。",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("followRecoveryHint"))
                    OutlinedButton(onClick = onCheckAvailability, enabled = state.canInspectFollow,
                        modifier = Modifier.fillMaxWidth().testTag("inspectFollowAvailability")) {
                        Text("检查控制占用")
                    }
                } else if (state.manualActive) {
                    Text("请先停用摇杆，再启动自动跟随。", style = MaterialTheme.typography.bodySmall)
                } else if (state.followConfiguration == null) {
                    Text("启动前先检查所选相机的车端配置。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun FollowDistanceStepper(
    label: String,
    value: Double?,
    defaultValue: Double,
    enabled: Boolean,
    onValueChange: (Double) -> Unit,
    tag: String,
) {
    val tenths = ((value ?: defaultValue) * 10).roundToInt()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { onValueChange((tenths - 1) / 10.0) },
            enabled = enabled && tenths > 5, modifier = Modifier.testTag("${tag}Decrease")) {
            Text("−")
        }
        Text(if (value == null) "车端默认" else "${value.display()} m",
            modifier = Modifier.testTag(tag))
        OutlinedButton(onClick = { onValueChange((tenths + 1) / 10.0) },
            enabled = enabled && tenths < 50, modifier = Modifier.testTag("${tag}Increase")) {
            Text("+")
        }
    }
}

@Composable
private fun FollowStatusDetails(status: FollowStatus, uncertain: Boolean) {
    val freshSnapshot = !uncertain && status.statusAgeMs?.let { it in 0..2_000 } == true
    val freshStatus = freshSnapshot && !status.processExited
    val targetFresh = freshStatus && status.targetAgeMs?.let { it in 0..600 } == true
    val rangeFresh = freshStatus && status.rangeAgeMs?.let { it in 0..600 } == true
    val feedbackFresh = freshStatus && status.feedbackAgeMs?.let { it in 0..600 } == true
    val lifecycle = when (status.runState) {
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

private fun Double.display(): String = String.format(Locale.ROOT, "%.2f", this).trimEnd('0').trimEnd('.')
private fun Long?.age(): String = this?.let { "$it ms" } ?: "未知"
private fun Boolean?.confirmation(): String = when (this) { true -> "已确认"; false -> "未确认成功"; null -> "未知" }
private fun wheelPair(left: Double?, right: Double?): String =
    "左 ${left?.display() ?: "未知"} / 右 ${right?.display() ?: "未知"} RPM"
