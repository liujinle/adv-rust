package com.adv.feature.apps

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.adv.core.common.formatBytes
import com.adv.core.common.formatTimestamp
import com.adv.core.data.AppSummary
import com.adv.core.design.AppIcon
import com.adv.core.design.EmptyState
import com.adv.core.design.ErrorBanner
import com.adv.core.design.KeyValueRow
import com.adv.core.design.ScreenScaffold
import com.adv.core.design.SoftCard
import com.adv.core.design.SoftDivider
import com.adv.core.design.StatusPill
import com.adv.core.design.Tone

@Composable
fun AppsScreen(
    state: AppsUiState,
    onPickApk: () -> Unit,
    onInstallAnother: (AppSummary) -> Unit,
    onDelete: (AppSummary) -> Unit,
    onDismissMessage: () -> Unit,
) {
    var expandedId by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(
        title = "应用库",
        subtitle = "选择 APK 即安装为虚拟实例",
        actions = {
            TextButton(onClick = onPickApk, enabled = !state.busy) {
                Text(if (state.busy) "安装中…" else "导入")
            }
        },
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

        if (state.apps.isEmpty()) {
            EmptyState(
                title = "还没有应用",
                message = "选择一个 APK，解析完成后会直接安装为虚拟实例，无需再确认。",
                actionLabel = if (state.busy) "安装中…" else "选择 APK 安装",
                onAction = onPickApk,
            )
            return@ScreenScaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.apps, key = { it.appId }) { app ->
                AppCard(
                    app = app,
                    expanded = expandedId == app.appId,
                    busy = state.busy,
                    onToggle = { expandedId = if (expandedId == app.appId) null else app.appId },
                    onInstallAnother = { onInstallAnother(app) },
                    onDelete = { onDelete(app) },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun AppCard(
    app: AppSummary,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onInstallAnother: () -> Unit,
    onDelete: () -> Unit,
) {
    SoftCard(onClick = onToggle) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(iconPath = app.iconPath, label = app.displayName)
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
                    text = "v${app.versionName ?: app.versionCode} · ${formatBytes(app.fileSizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (app.installedInVirtual) {
                StatusPill(text = "${app.instanceCount} 个实例", tone = Tone.SUCCESS)
            } else {
                StatusPill(text = "未安装", tone = Tone.NEUTRAL)
            }
        }

        if (expanded) {
            SoftDivider()
            KeyValueRow("包名", app.packageName, mono = true)
            KeyValueRow("版本号", app.versionCode.toString())
            KeyValueRow("min / target SDK", "${app.minSdk} / ${app.targetSdk}")
            KeyValueRow("APK 摘要 (SHA-256)", app.sha256 ?: "不可用", mono = true)
            KeyValueRow(
                "ABI",
                app.nativeAbis.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "无 native 库",
            )
            KeyValueRow("Launcher", app.launcherActivity ?: "未声明")
            KeyValueRow("导入时间", formatTimestamp(app.importedAtMs))
            SoftDivider()
            Text(
                text = "Manifest 权限（${app.permissions.size}）",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            if (app.permissions.isEmpty()) {
                Text(
                    text = "未声明权限",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                app.permissions.take(8).forEach { permission ->
                    Text(
                        text = "· $permission",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (app.permissions.size > 8) {
                    Text(
                        text = "… 其余 ${app.permissions.size - 8} 项",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onInstallAnother, enabled = !busy) {
                    Text(if (app.installedInVirtual) "再装一个实例" else "安装")
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete) { Text("移除记录") }
            }
        }
    }
}
