package com.adv.feature.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.adv.core.common.formatBytes
import com.adv.core.common.formatClock
import com.adv.core.common.formatTimestamp
import com.adv.core.data.EngineDiagnostics
import com.adv.core.data.EngineRunState
import com.adv.core.data.LogEntry
import com.adv.core.data.LogLevel
import com.adv.core.design.EmptyState
import com.adv.core.design.KeyValueRow
import com.adv.core.design.ScreenScaffold
import com.adv.core.design.SectionTitle
import com.adv.core.design.SoftCard
import com.adv.core.design.SoftDivider
import com.adv.core.design.StatusPill
import com.adv.core.design.Tone

@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    ScreenScaffold(
        title = "引擎中心",
        subtitle = "Rust Core · JNI · 宿主进程",
        actions = {
            TextButton(onClick = onRefresh) { Text("刷新") }
            TextButton(onClick = onBack) { Text("返回") }
        },
    ) {
        val diagnostics = state.diagnostics
        if (diagnostics == null) {
            EmptyState(title = "正在读取引擎状态", message = "状态来自引擎 summary 与宿主进程采样")
            return@ScreenScaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item { EngineStateCard(diagnostics, onRetry) }
            item { SectionTitle("引擎信息") }
            item { EngineInfoCard(diagnostics) }
            item { SectionTitle("宿主进程") }
            item { ProcessCard(diagnostics) }
            item { SectionTitle("兼容性检测") }
            item { CompatibilityCard(diagnostics) }
            item { SectionTitle("错误与日志", trailing = "${diagnostics.logs.size} 条") }
            item { LogCard(diagnostics.logs) }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun EngineStateCard(diagnostics: EngineDiagnostics, onRetry: () -> Unit) {
    val tone = when (diagnostics.engineState) {
        EngineRunState.READY -> Tone.SUCCESS
        EngineRunState.FAILED -> Tone.ERROR
        EngineRunState.INITIALIZING -> Tone.WARNING
        else -> Tone.NEUTRAL
    }
    val jniLoaded = diagnostics.status?.jniLoaded == true
    val abiVersion = diagnostics.status?.abiVersion ?: 0
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("引擎初始化状态", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = if (jniLoaded) {
                        "JNI 已加载 · ABI $abiVersion"
                    } else {
                        "JNI 未加载，客户端无法调用 Native Core"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(text = diagnostics.engineState.label, tone = tone)
        }
        if (diagnostics.engineState == EngineRunState.FAILED || diagnostics.engineState == EngineRunState.STOPPED) {
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("重新初始化") }
        }
    }
}

@Composable
private fun EngineInfoCard(diagnostics: EngineDiagnostics) {
    val status = diagnostics.status
    SoftCard {
        if (status == null) {
            Text(
                text = "引擎尚未提供状态信息。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            KeyValueRow("Core 版本", status.coreVersion ?: "未知")
            KeyValueRow("ABI / 协议", "${status.abiVersion} / ${status.protocolVersion ?: "未知"}")
            KeyValueRow("引擎根目录", status.rootPath ?: "未知", mono = true)
            KeyValueRow("虚拟根目录", status.virtualRoot ?: "未知", mono = true)
            KeyValueRow("启动时间", formatTimestamp(status.startedAtMs ?: 0L))
            KeyValueRow("实例数 / 运行中", "${status.instanceCount} / ${status.runningCount}")
            KeyValueRow("重启恢复实例", status.recoveredInstances.toString())
            KeyValueRow("任务 排队 / 执行", "${status.queuedTasks} / ${status.activeTasks}")
            KeyValueRow("资源缓存", "${status.cacheEntries} 项 · ${formatBytes(status.cacheBytes)}")
            val lastError = status.lastError
            if (lastError != null) {
                SoftDivider()
                Text(
                    text = lastError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ProcessCard(diagnostics: EngineDiagnostics) {
    val status = diagnostics.status
    SoftCard {
        KeyValueRow("宿主 API", status?.hostSdk?.toString() ?: "未知")
        KeyValueRow(
            "进程 CPU",
            status?.processCpuPercent?.let { String.format("%.1f%%", it) } ?: "不可用",
        )
        KeyValueRow(
            "进程 PSS",
            status?.processPssKb?.let { formatBytes(it * 1024L) } ?: "不可用",
        )
        Text(
            text = "虚拟应用与宿主共享进程，因此这里是整个宿主进程的占用，不能归因到单个实例。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CompatibilityCard(diagnostics: EngineDiagnostics) {
    SoftCard {
        Text(
            text = diagnostics.status?.compatibilityReport ?: "兼容性矩阵不可用",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "矩阵条目默认 PENDING：未在真机验证的兼容性不会被标记为已支持。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LogCard(logs: List<LogEntry>) {
    SoftCard {
        if (logs.isEmpty()) {
            Text(
                text = "暂无日志。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SoftCard
        }
        logs.takeLast(80).reversed().forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = formatClock(entry.atMs),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = entry.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.level) {
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                        LogLevel.WARNING -> MaterialTheme.colorScheme.primary
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
    }
}
