package com.adv.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adv.core.common.formatBytes
import com.adv.core.data.AppSettings
import com.adv.core.data.EngineRunState
import com.adv.core.data.ThemeMode
import com.adv.core.design.ErrorBanner
import com.adv.core.design.LabeledSwitch
import com.adv.core.design.ScreenScaffold
import com.adv.core.design.SectionTitle
import com.adv.core.design.SoftCard
import com.adv.core.design.SoftDivider
import com.adv.core.design.StatusPill
import com.adv.core.design.Tone

@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onThemeChange: (ThemeMode) -> Unit,
    onLockPortraitChange: (Boolean) -> Unit,
    onLogLevelChange: (String) -> Unit,
    onMaxInstancesChange: (Int) -> Unit,
    onCacheBudgetChange: (Int) -> Unit,
    onNetworkChanged: (Boolean) -> Unit,
    onExternalStorageChanged: (Boolean) -> Unit,
    onBackgroundStartChanged: (Boolean) -> Unit,
    onRestartEngine: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onDismissMessage: () -> Unit,
) {
    ScreenScaffold(
        title = "设置",
        subtitle = "外观、引擎参数与默认实例策略",
        actions = { TextButton(onClick = onOpenDiagnostics) { Text("引擎中心") } },
    ) {
        val message = state.message
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (message != null) {
                item {
                    if (state.messageIsError) {
                        ErrorBanner(message = message, onDismiss = onDismissMessage)
                    } else {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }

            item { SectionTitle("外观") }
            item {
                SoftCard {
                    Text("主题", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    ChoiceRow(
                        options = listOf(
                            ThemeMode.SYSTEM to "跟随系统",
                            ThemeMode.LIGHT to "浅色",
                            ThemeMode.DARK to "深色",
                        ),
                        selected = state.settings.themeMode,
                        onSelect = onThemeChange,
                    )
                    SoftDivider()
                    LabeledSwitch(
                        label = "禁止横屏",
                        description = "宿主界面固定竖屏，不随系统旋转；切换后立即生效",
                        checked = state.settings.lockPortrait,
                        onCheckedChange = onLockPortraitChange,
                    )
                }
            }

            item { SectionTitle("引擎参数", trailing = "下次启动生效") }
            item { EngineParamsCard(state.settings, onLogLevelChange, onMaxInstancesChange, onCacheBudgetChange) }

            item { SectionTitle("默认实例策略") }
            item { DefaultPolicyCard(state.settings, onNetworkChanged, onExternalStorageChanged, onBackgroundStartChanged) }

            item { SectionTitle("引擎控制") }
            item {
                SoftCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("当前状态", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "重新初始化会清空内存中的引擎句柄，实例元数据保留。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        StatusPill(
                            text = state.engineState.label,
                            tone = when (state.engineState) {
                                EngineRunState.READY -> Tone.SUCCESS
                                EngineRunState.FAILED -> Tone.ERROR
                                EngineRunState.INITIALIZING -> Tone.WARNING
                                else -> Tone.NEUTRAL
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    TextButton(onClick = onRestartEngine) { Text("重新初始化引擎") }
                }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            TextButton(onClick = { onSelect(value) }) {
                Text(
                    text = label,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun EngineParamsCard(
    settings: AppSettings,
    onLogLevelChange: (String) -> Unit,
    onMaxInstancesChange: (Int) -> Unit,
    onCacheBudgetChange: (Int) -> Unit,
) {
    SoftCard {
        Text("日志级别", style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(
            options = listOf("error" to "error", "warn" to "warn", "info" to "info", "debug" to "debug"),
            selected = settings.logLevel,
            onSelect = onLogLevelChange,
        )
        SoftDivider()
        StepperRow(
            label = "最大实例数",
            value = settings.maxInstances.toString(),
            onMinus = { onMaxInstancesChange(-1) },
            onPlus = { onMaxInstancesChange(1) },
        )
        StepperRow(
            label = "资源缓存预算",
            value = formatBytes(settings.cacheBudgetBytes),
            onMinus = { onCacheBudgetChange(-16) },
            onPlus = { onCacheBudgetChange(16) },
        )
        Text(
            text = "这些参数在引擎初始化时读取，修改后需要重启应用（或重新初始化引擎）。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−") }
        Text(value, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onPlus) { Text("+") }
    }
}

@Composable
private fun DefaultPolicyCard(
    settings: AppSettings,
    onNetworkChanged: (Boolean) -> Unit,
    onExternalStorageChanged: (Boolean) -> Unit,
    onBackgroundStartChanged: (Boolean) -> Unit,
) {
    val policy = settings.defaultPolicy
    SoftCard {
        LabeledSwitch(
            label = "允许网络访问",
            checked = policy.allowNetwork,
            onCheckedChange = onNetworkChanged,
        )
        LabeledSwitch(
            label = "允许外部存储映射",
            description = "映射到虚拟目录，不暴露宿主真实路径",
            checked = policy.allowExternalStorage,
            onCheckedChange = onExternalStorageChanged,
        )
        LabeledSwitch(
            label = "允许后台启动",
            checked = policy.allowBackgroundStart,
            onCheckedChange = onBackgroundStartChanged,
        )
        SoftDivider()
        Text(
            text = "新创建的实例会继承这些虚拟层策略；已存在的实例保持不变。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
