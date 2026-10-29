package com.example.followingcar

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local inspection remains available when SSH is disconnected or follow recovery is locked. */
@Composable
internal fun DiagnosticLogCard(controlActive: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentControlActive by rememberUpdatedState(controlActive)
    val status by Diagnostics.status.collectAsStateWithLifecycle()
    val failures by Diagnostics.failures.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(false) }
    var snapshot by remember { mutableStateOf<DiagnosticSnapshot?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded) {
        if (expanded) snapshot = Diagnostics.snapshot()
    }
    Card(Modifier.fillMaxWidth().testTag("diagnosticCard")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("诊断日志", style = MaterialTheme.typography.titleMedium)
            Text(status.message, color = if (status.available) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error, modifier = Modifier.testTag("diagnosticStatus"))
            if (status.droppedEvents > 0) Text("已省略 ${status.droppedEvents} 条事件，详情见日志。",
                style = MaterialTheme.typography.bodySmall)
            Text("自动记录操作、连接和停止原因。保留最近 7 天，最多 20 MB；首次故障另外保留最近 10 次。",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.testTag("diagnosticView")) {
                    Text(if (expanded) "收起日志" else "查看日志")
                }
                OutlinedButton(enabled = !controlActive && !working,
                    modifier = Modifier.testTag("diagnosticExport"), onClick = {
                        working = true
                        error = null
                        scope.launch {
                            try {
                                Diagnostics.event("diagnostics.export_requested")
                                val file = Diagnostics.export()
                                // Control may have started while the archive was being written.
                                if (currentControlActive) {
                                    error = "控制会话正在运行，请结束后再导出。"
                                    return@launch
                                }
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.diagnostics", file)
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri("诊断日志", uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "导出诊断日志"))
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                Diagnostics.failure(Diagnostics.newContext(), "diagnostics.export_failed", failure)
                                error = "导出失败，请查看日志状态后重试。"
                            } finally { working = false }
                        }
                    }) { Text(if (working) "正在导出…" else "导出 ZIP") }
            }
            if (controlActive) Text("控制会话结束后可导出，运行期间日志仍自动记录。", style = MaterialTheme.typography.bodySmall)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (expanded) {
                OutlinedButton(onClick = { scope.launch { snapshot = Diagnostics.snapshot() } },
                    modifier = Modifier.testTag("diagnosticRefresh")) { Text("刷新") }
                Text("最近会话的首次故障", style = MaterialTheme.typography.titleSmall)
                if (failures.isEmpty()) Text("暂无故障记录", modifier = Modifier.testTag("diagnosticFailures"))
                else SelectionContainer {
                    Text(failures.asReversed().joinToString("\n\n") { failure ->
                        "${localDiagnosticTime(failure.time)} · ${failure.event}\n${failure.message}\n" +
                            "操作：${failure.context.operationId}\n板端会话：${failure.context.runId ?: "尚未建立"}"
                    }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("diagnosticFailures"))
                }
                Text("最近事件（新事件在前）", style = MaterialTheme.typography.titleSmall)
                Text("导出包仅含 App 日志；板端日志可按会话编号通过 SSH 对照。", style = MaterialTheme.typography.bodySmall)
                SelectionContainer {
                    Text(snapshot?.events?.takeLast(80)?.asReversed()?.joinToString("\n\n", transform = ::displayDiagnosticEvent)
                        ?.ifBlank { "暂无事件" } ?: "正在读取…", fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("diagnosticEvents"))
                }
            }
        }
    }
}

private fun localDiagnosticTime(time: String): String = runCatching {
    DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault()).format(Instant.parse(time))
}.getOrDefault(time)

private fun displayDiagnosticEvent(line: String): String = runCatching {
    val event = JSONObject(line)
    "${localDiagnosticTime(event.optString("time"))} · ${event.optString("event")}\n" +
        (if (!event.isNull("source")) "来源：${event.optString("source")}\n" else "") +
        (if (!event.isNull("runId")) "会话：${event.optString("runId")}\n" else "") +
        event.optJSONObject("fields")?.toString()?.take(1400).orEmpty()
}.getOrDefault(line)
