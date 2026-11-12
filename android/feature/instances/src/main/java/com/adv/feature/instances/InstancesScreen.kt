package com.adv.feature.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adv.core.common.formatRelative
import com.adv.core.data.InstanceOverview
import com.adv.core.design.AppIcon
import com.adv.core.design.EmptyState
import com.adv.core.design.ErrorBanner
import com.adv.core.design.ScreenScaffold
import com.adv.core.design.SoftCard
import com.adv.core.design.SoftDivider
import com.adv.core.design.StatusPill
import com.adv.core.design.Tone

@Composable
fun InstancesScreen(
    state: InstancesUiState,
    onLaunch: (String) -> Unit,
    onStop: (String) -> Unit,
    onBringToFront: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onUninstall: (String) -> Unit,
    onOpenDetail: (String) -> Unit,
    onRefresh: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<InstanceOverview?>(null) }
    // 清数据改成只在详情页做：列表上多一个破坏性按钮，误触成本太高。
    var uninstallTarget by remember { mutableStateOf<InstanceOverview?>(null) }

    ScreenScaffold(
        title = "实例",
        subtitle = "每个实例拥有独立的虚拟数据目录",
        actions = { TextButton(onClick = onRefresh) { Text("刷新") } },
    ) {
        val message = state.message
        if (message != null) {
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

        if (state.instances.isEmpty()) {
            EmptyState(
                title = "还没有实例",
                message = "在「应用」中导入 APK 后创建实例，这里会显示运行状态与数据目录。",
            )
            return@ScreenScaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.instances, key = { it.instanceId }) { instance ->
                InstanceCard(
                    instance = instance,
                    busy = instance.instanceId in state.busyInstanceIds,
                    onLaunch = { onLaunch(instance.instanceId) },
                    onStop = { onStop(instance.instanceId) },
                    onBringToFront = { onBringToFront(instance.instanceId) },
                    onOpenDetail = { onOpenDetail(instance.instanceId) },
                    onRename = { renameTarget = instance },
                    onUninstall = { uninstallTarget = instance },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            instance = target,
            onConfirm = { name ->
                onRename(target.instanceId, name)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    uninstallTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { uninstallTarget = null },
            title = { Text("卸载实例？") },
            text = {
                Text(
                    text = "${target.displayName} 会从设备上移除：虚拟数据目录、备份文件、" +
                        "权限策略与操作记录都会一起删除，不可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onUninstall(target.instanceId)
                    uninstallTarget = null
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { uninstallTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InstanceCard(
    instance: InstanceOverview,
    busy: Boolean,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onBringToFront: () -> Unit,
    onOpenDetail: () -> Unit,
    onRename: () -> Unit,
    onUninstall: () -> Unit,
) {
    SoftCard {
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
            Spacer(Modifier.width(10.dp))
            StatusPill(
                text = if (busy) "处理中…" else instance.state.label,
                tone = when {
                    busy -> Tone.WARNING
                    instance.state.isActive -> Tone.SUCCESS
                    instance.state == com.adv.core.engine.api.VirtualInstanceState.FAILED -> Tone.ERROR
                    else -> Tone.NEUTRAL
                },
            )
        }

        Spacer(Modifier.height(6.dp))
        Text(
            text = "最近更新 ${formatRelative(instance.updatedAtMs)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val lastError = instance.lastError
        if (lastError != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = lastError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        SoftDivider()
        // 启动 / 停止合成一个按钮：状态就是按钮语义，运行中点它是"停止"，否则是"启动"。
        // 少一个按钮之后，这一行才放得下"跳转"。
        val running = instance.state.canStop
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = if (running) onStop else onLaunch,
                enabled = !busy && if (running) instance.state.canStop else instance.state.canLaunch,
            ) {
                Text(if (running) "停止" else "启动")
            }
            // 跳转：用户已经回到宿主 / 桌面，虚拟应用还在后台跑，一键把它的任务搬回前台。
            TextButton(onClick = onBringToFront, enabled = !busy && instance.state.isActive) {
                Text("跳转")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onOpenDetail) { Text("详情") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRename, enabled = !busy) { Text("重命名") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onUninstall, enabled = !busy) {
                Text("卸载", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RenameDialog(
    instance: InstanceOverview,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(instance.instanceId) { mutableStateOf(instance.displayName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名实例") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                label = { Text("显示名称") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) {
                Text("保存")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
