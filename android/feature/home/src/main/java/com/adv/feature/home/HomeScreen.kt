package com.adv.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adv.core.common.formatRelative
import com.adv.core.common.operationStatusLabel
import com.adv.core.data.AppSummary
import com.adv.core.data.EngineRunState
import com.adv.core.data.HomeSnapshot
import com.adv.core.data.InstanceOverview
import com.adv.core.data.OperationRow
import com.adv.core.design.AppIcon
import com.adv.core.design.EmptyState
import com.adv.core.design.ErrorBanner
import com.adv.core.design.KeyValueRow
import com.adv.core.design.ScreenScaffold
import com.adv.core.design.SectionTitle
import com.adv.core.design.SoftCard
import com.adv.core.design.StatRow
import com.adv.core.design.StatTile
import com.adv.core.design.StatusPill
import com.adv.core.design.Tone

@Composable
fun HomeScreen(
    state: HomeUiState,
    onPickApk: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenApps: () -> Unit,
    onOpenInstances: () -> Unit,
    onOpenInstance: (String) -> Unit,
    onDismissMessage: () -> Unit,
) {
    ScreenScaffold(
        title = "BlackBox",
        subtitle = "应用虚拟化工作台",
        actions = {
            TextButton(onClick = onOpenDiagnostics) { Text("引擎") }
        },
    ) {
        val snapshot = state.snapshot
        if (snapshot == null) {
            EmptyState(title = "正在读取引擎状态", message = "首次启动需要初始化 Native Core")
            return@ScreenScaffold
        }

        val message = state.message
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            if (message != null) {
                item {
                    if (state.messageIsError) {
                        ErrorBanner(message = message, onDismiss = onDismissMessage)
                    } else {
                        SoftCard(onClick = onDismissMessage) {
                            Text(
                                text = message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            item { EngineStatusCard(snapshot.engineState, onOpenDiagnostics) }

            item {
                StatRow(
                    {
                        StatTile(
                            label = "应用",
                            value = snapshot.appCount.toString(),
                            caption = "已导入 APK",
                        )
                    },
                    {
                        StatTile(
                            label = "实例",
                            value = snapshot.instanceCount.toString(),
                            caption = "虚拟环境",
                        )
                    },
                    {
                        StatTile(
                            label = "运行中",
                            value = snapshot.runningCount.toString(),
                            caption = "活跃实例",
                        )
                    },
                )
            }

            item {
                SoftCard {
                    Text("快速安装", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "选好 APK 就直接安装为虚拟实例，不需要再确认或填名称。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onPickApk, enabled = !state.importing) {
                        Text(if (state.importing) "正在安装…" else "选择 APK 安装")
                    }
                }
            }

            item {
                SectionTitle("最近使用", trailing = if (snapshot.recentApps.isEmpty()) null else "查看全部")
            }
            if (snapshot.recentApps.isEmpty()) {
                item {
                    SoftCard {
                        Text(
                            text = "还没有导入任何应用。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            items(snapshot.recentApps, key = { it.appId }) { app ->
                AppRow(app = app, onClick = onOpenApps)
            }

            if (snapshot.recentInstances.isNotEmpty()) {
                item { SectionTitle("最近运行的实例") }
                items(snapshot.recentInstances, key = { it.instanceId }) { instance ->
                    InstanceRow(instance = instance, onClick = { onOpenInstance(instance.instanceId) })
                }
            }

            if (snapshot.recentOperations.isNotEmpty()) {
                item { SectionTitle("最近记录") }
                items(snapshot.recentOperations, key = { it.taskId }) { operation ->
                    OperationRowView(operation)
                }
                item {
                    TextButton(onClick = onOpenInstances, modifier = Modifier.padding(bottom = 24.dp)) {
                        Text("管理全部实例")
                    }
                }
            } else {
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun EngineStatusCard(state: EngineRunState, onOpenDiagnostics: () -> Unit) {
    val tone = when (state) {
        EngineRunState.READY -> Tone.SUCCESS
        EngineRunState.INITIALIZING -> Tone.WARNING
        EngineRunState.FAILED -> Tone.ERROR
        else -> Tone.NEUTRAL
    }
    SoftCard(onClick = onOpenDiagnostics) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("引擎状态", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Rust Core · BlackBox 兼容层",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            StatusPill(text = state.label, tone = tone)
        }
    }
}

@Composable
private fun AppRow(app: AppSummary, onClick: () -> Unit) {
    SoftCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(iconPath = app.iconPath, label = app.displayName, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = app.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = formatRelative(app.importedAtMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InstanceRow(instance: InstanceOverview, onClick: () -> Unit) {
    SoftCard(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(iconPath = instance.iconPath, label = instance.displayName, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = instance.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = instance.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            StatusPill(
                text = instance.state.label,
                tone = if (instance.state.isActive) Tone.SUCCESS else Tone.NEUTRAL,
            )
        }
    }
}

@Composable
private fun OperationRowView(operation: OperationRow) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        KeyValueRow(
            label = formatRelative(operation.createdAtMs),
            value = "${operationLabel(operation.operation)} · ${operationStatusLabel(operation.status)}",
            mono = false,
        )
    }
}

private fun operationLabel(operation: String): String = when (operation) {
    "create_instance" -> "创建实例"
    "launch" -> "启动"
    "stop" -> "停止"
    "uninstall" -> "删除实例"
    "backup" -> "备份"
    "restore" -> "恢复"
    else -> operation
}
