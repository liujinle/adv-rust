package com.adv.feature.instances

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adv.core.common.backupStatusLabel
import com.adv.core.common.formatBytes
import com.adv.core.common.formatTimestamp
import com.adv.core.common.operationStatusLabel
import com.adv.core.data.BackupRow
import com.adv.core.data.InstanceDetails
import com.adv.core.data.OperationRow
import com.adv.core.data.PermissionPolicy
import com.adv.core.data.PermissionRow
import com.adv.core.design.AppIcon
import com.adv.core.design.EmptyState
import com.adv.core.design.ErrorBanner
import com.adv.core.design.KeyValueRow
import com.adv.core.design.LabeledSwitch
import com.adv.core.design.ScreenScaffold
import com.adv.core.design.SectionTitle
import com.adv.core.design.SoftCard
import com.adv.core.design.SoftDivider
import com.adv.core.design.StatusPill
import com.adv.core.design.Tone

@Composable
fun InstanceDetailScreen(
    state: InstanceDetailUiState,
    onBack: () -> Unit,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onBringToFront: () -> Unit,
    onBackup: () -> Unit,
    onRestore: (String) -> Unit,
    onSetPermission: (String, PermissionPolicy) -> Unit,
    onRefreshStorage: () -> Unit,
    onClearData: () -> Unit,
    onUninstall: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    val details = state.details
    var confirmClear by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }

    ScreenScaffold(
        title = details?.overview?.title ?: "实例详情",
        subtitle = details?.overview?.subtitle,
        actions = { TextButton(onClick = onBack) { Text("返回") } },
    ) {
        if (details == null) {
            EmptyState(title = "正在加载实例", message = "从本地数据库读取实例信息")
            return@ScreenScaffold
        }

        val message = state.message
        LazyColumn(modifier = Modifier.fillMaxSize()) {
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

            item {
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(
                            iconPath = details.overview.iconPath,
                            label = details.overview.displayName,
                            size = 44.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                        StatusPill(
                            text = if (state.busy) "处理中…" else details.overview.state.label,
                            tone = when {
                                state.busy -> Tone.WARNING
                                details.overview.state.isActive -> Tone.SUCCESS
                                details.overview.state == com.adv.core.engine.api.VirtualInstanceState.FAILED -> Tone.ERROR
                                else -> Tone.NEUTRAL
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        // 启动 / 停止合成一个按钮，跟列表页保持一致。
                        val running = details.overview.state.canStop
                        TextButton(
                            onClick = if (running) onStop else onLaunch,
                            enabled = !state.busy &&
                                if (running) details.overview.state.canStop
                                else details.overview.state.canLaunch,
                        ) { Text(if (running) "停止" else "启动") }
                        TextButton(
                            onClick = onBringToFront,
                            enabled = !state.busy && details.overview.state.isActive,
                        ) { Text("跳转") }
                    }
                    val lastError = details.overview.lastError
                    if (lastError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = lastError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            item { SectionTitle("基本信息") }
            item { BasicInfoCard(details) }

            item { SectionTitle("权限状态") }
            item { PermissionsCard(details.permissions, onSetPermission) }

            item { SectionTitle("存储空间") }
            item {
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                formatBytes(state.storageBytes),
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text(
                                "虚拟数据目录占用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = onRefreshStorage) { Text("重新统计") }
                    }
                    KeyValueRow("目录", details.overview.dataPath, mono = true)
                }
            }

            item { SectionTitle("网络与运行设置") }
            item { RuntimePolicyCard(details) }

            item { SectionTitle("备份与恢复") }
            item {
                BackupCard(
                    backups = details.backups,
                    busy = state.busy,
                    onBackup = onBackup,
                    onRestore = onRestore,
                )
            }

            item { SectionTitle("实例维护") }
            item {
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("清除数据", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "重置为刚装好的状态：账号、登录态、缓存与数据库清空；" +
                                    "实例名 / 权限 / 备份保留",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // 运行中清数据只会拿到「请先停止实例」，直接置灰。
                        TextButton(
                            onClick = { confirmClear = true },
                            enabled = !state.busy && !details.overview.state.isActive,
                        ) { Text("清除") }
                    }
                    SoftDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("卸载实例", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "虚拟数据目录、备份文件、权限策略与操作记录一起删除，不可恢复",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { confirmUninstall = true },
                            enabled = !state.busy,
                        ) { Text("卸载", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }

            item { SectionTitle("日志与错误") }
            item { HistoryCard(details.operations) }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清除数据？") },
            text = {
                Text(
                    text = "实例会被重置成刚装好的状态：账号、登录态、缓存与本地数据库全部清空，" +
                        "实例名、权限与备份保留（内部会重新解包，需要几秒）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClearData()
                }) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text("取消") }
            },
        )
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("卸载实例？") },
            text = {
                Text(
                    text = "虚拟数据目录、备份文件、权限策略与操作记录都会一起删除，不可恢复。",
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    onUninstall()
                }) { Text("卸载", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun BasicInfoCard(details: InstanceDetails) {
    SoftCard {
        KeyValueRow("实例名称", details.overview.displayName)
        KeyValueRow("实例 ID", details.overview.instanceId, mono = true)
        KeyValueRow("包名", details.overview.packageName, mono = true)
        KeyValueRow("版本", details.overview.versionLabel ?: "未知")
        KeyValueRow("APK 摘要", details.apkSha256 ?: "不可用", mono = true)
        KeyValueRow("创建时间", formatTimestamp(details.overview.createdAtMs))
        KeyValueRow("最近更新", formatTimestamp(details.overview.updatedAtMs))
    }
}

@Composable
private fun PermissionsCard(
    permissions: List<PermissionRow>,
    onSetPermission: (String, PermissionPolicy) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }

    SoftCard {
        Text(
            text = "以下为虚拟层策略，不代表宿主 Android 系统已授予该权限。虚拟应用与宿主共享 UID，" +
                "真实授权仍由宿主系统决定。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SoftDivider()
        if (permissions.isEmpty()) {
            Text(
                text = "该 APK 未在 Manifest 中声明权限。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            // A popular APK can declare 200+ permissions; listing them all inline
            // would balloon the page and compose every row at once.
            permissions.take(PERMISSION_PREVIEW).forEach { row ->
                PermissionLine(row, onSetPermission)
            }
            if (permissions.size > PERMISSION_PREVIEW) {
                TextButton(onClick = { showAll = true }) {
                    Text("查看全部 ${permissions.size} 项权限")
                }
            }
            Text(
                text = "点按策略可切换：虚拟放行 → 虚拟询问 → 虚拟拒绝",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showAll) {
        AlertDialog(
            onDismissRequest = { showAll = false },
            title = { Text("Manifest 权限（${permissions.size}）") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(permissions, key = { it.permission }) { row ->
                        PermissionLine(row, onSetPermission)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAll = false }) { Text("关闭") }
            },
        )
    }
}

@Composable
private fun PermissionLine(
    row: PermissionRow,
    onSetPermission: (String, PermissionPolicy) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = row.permission.removePrefix("android.permission."),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onSetPermission(row.permission, row.policy.next()) }) {
            Text(row.policy.label)
        }
    }
}

private const val PERMISSION_PREVIEW = 6

@Composable
private fun RuntimePolicyCard(details: InstanceDetails) {
    val policy = details.policy
    SoftCard {
        LabeledSwitch(
            label = "网络访问",
            description = "创建实例时写入引擎的虚拟层策略",
            checked = policy.allowNetwork,
            onCheckedChange = {},
            enabled = false,
        )
        LabeledSwitch(
            label = "外部存储映射",
            description = "仅在虚拟沙箱内映射，不暴露宿主真实路径",
            checked = policy.allowExternalStorage,
            onCheckedChange = {},
            enabled = false,
        )
        LabeledSwitch(
            label = "后台启动",
            description = "允许虚拟组件在宿主后台被拉起",
            checked = policy.allowBackgroundStart,
            onCheckedChange = {},
            enabled = false,
        )
        SoftDivider()
        KeyValueRow("存储上限", formatBytes(policy.maxStorageBytes))
        Text(
            text = "策略在创建实例时固定；当前引擎版本不提供在线修改入口，重建实例后生效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BackupCard(
    backups: List<BackupRow>,
    busy: Boolean,
    onBackup: () -> Unit,
    onRestore: (String) -> Unit,
) {
    SoftCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = onBackup, enabled = !busy) { Text("立即备份") }
            Spacer(Modifier.weight(1f))
            Text(
                text = "${backups.size} 个备份",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (backups.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "备份为实例数据目录的 ZIP 归档，保存在宿主应用私有目录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            SoftDivider()
            backups.forEach { backup ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = formatTimestamp(backup.createdAtMs),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = "${formatBytes(backup.sizeBytes)} · ${backupStatusLabel(backup.status)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onRestore(backup.backupId) }, enabled = !busy) {
                        Text("恢复")
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryCard(operations: List<OperationRow>) {
    SoftCard {
        if (operations.isEmpty()) {
            Text(
                text = "暂无操作记录。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            operations.forEach { operation ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = operationLabel(operation.operation),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text = formatTimestamp(operation.createdAtMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        text = operationStatusLabel(operation.status),
                        tone = if (operation.isFailed) Tone.ERROR else Tone.NEUTRAL,
                    )
                }
            }
        }
    }
}

private fun PermissionPolicy.next(): PermissionPolicy = when (this) {
    PermissionPolicy.ALLOW -> PermissionPolicy.PROMPT
    PermissionPolicy.PROMPT -> PermissionPolicy.DENY
    PermissionPolicy.DENY -> PermissionPolicy.ALLOW
}

private fun operationLabel(operation: String): String = when (operation) {
    "create_instance" -> "创建实例"
    "launch" -> "启动"
    "stop" -> "停止"
    "uninstall" -> "卸载实例"
    "clear_data" -> "清除数据"
    "backup" -> "备份"
    "restore" -> "恢复"
    else -> operation
}
