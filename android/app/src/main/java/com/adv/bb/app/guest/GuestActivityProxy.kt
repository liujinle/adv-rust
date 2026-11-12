package com.adv.bb.app.guest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.adv.bb.app.AdvApplication
import com.adv.core.common.EngineResult
import com.adv.core.data.LaunchRequest
import com.adv.core.design.AdvTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface LaunchState {
    data object Loading : LaunchState
    data class Failed(val message: String) : LaunchState
    data class Loaded(val request: LaunchRequest, val report: GuestLoadReport) : LaunchState
}

/**
 * Host-side entry point for a virtual instance.
 *
 * Today it proves the guest is loadable (dex + resources) and reports exactly
 * what failed when it is not. Attaching the guest Activity through a hooked
 * Instrumentation is the next stage; until that exists this screen is the honest
 * answer to "启动" instead of a RUNNING flag with no process behind it.
 */
open class GuestActivityProxy : ComponentActivity() {

    class P1 : GuestActivityProxy()
    class P2 : GuestActivityProxy()
    class P3 : GuestActivityProxy()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val instanceId = intent.getStringExtra(GuestLauncher.EXTRA_INSTANCE_ID).orEmpty()
        // 兜底页现在跑在 `:guest` 进程里，那里**没有** AppContainer（双账本：账本只在
        // 主进程）。所以诊断页优先用 gate 通过 Intent 传来的扁平字段，只有退回主进程
        // 跑（旧路径/单测）时才去问 Room。
        val container = (application as AdvApplication).containerOrNull

        // 这一页被实例化只有两种可能：
        //  1) gate 装 hook / 加载 guest 失败 —— 那份 Intent 带 EXTRA_HOOK_ERROR；
        //  2) 进程被 AMS/LMK 杀掉，用户从最近任务点回来，系统恢复了 proxy 自己 ——
        //     本进程里根本没有这个实例的已装载对象（hook 只存在于装它的进程）。
        // 第 2 种重开一次 gate 冷启动才有意义；照原样显示诊断页等于"点回来就报废"。
        val restored = GuestLauncher.requestFromIntent(intent)
            ?: GuestLauncher.requestFromRecord(this)
        if (restored != null &&
            intent.getStringExtra(GuestLauncher.EXTRA_HOOK_ERROR) == null &&
            GuestRuntime.guestFor(restored.instanceId) == null
        ) {
            val attempt = intent.getIntExtra(GuestLauncher.EXTRA_RESTART_ATTEMPT, 0)
            if (attempt < 2 && GuestLauncher.relaunchFromProxy(this, restored, attempt + 1)) {
                return
            }
        }
        // 点"启动"到 guest 启动页出现之间是 guest Application.onCreate（实测 2.4~4.8s），
        // 窗口已经在前台、里面什么都没有 —— 用户看到的就是白屏。先在 onCreate 就盖一层。
        GuestRuntime.installBootOverlay(this, instanceId)
        // 这一页只在 hook/加载失败时才会真的被实例化：gate 的遮罩该收了，别让用户
        // 被"正在启动"糊住而看不见失败原因。
        GuestRuntime.dismissLaunchGate("proxy onCreate（兜底页）")

        setContent {
            AdvTheme(darkTheme = isSystemInDarkTheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    var state by remember { mutableStateOf<LaunchState>(LaunchState.Loading) }

                    LaunchedEffect(instanceId) {
                        state = withContext(Dispatchers.IO) { load(instanceId, container) }
                    }

                    Scaffold { padding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(padding)
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 20.dp, vertical = 28.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = "虚拟应用",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            when (val current = state) {
                                LaunchState.Loading -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    CircularProgressIndicator()
                                    Text("正在加载 APK…")
                                }

                                is LaunchState.Failed -> {
                                    Text(
                                        text = "无法启动",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Text(current.message, style = MaterialTheme.typography.bodyMedium)
                                }

                                is LaunchState.Loaded -> {
                                    Text(
                                        text = current.request.displayName,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Line("包名", current.request.packageName)
                                    Line("Launcher", current.request.launcherActivity)
                                    Line("minSdk", current.request.minSdk.toString())
                                    Line("APK", current.request.apkPath)
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = if (current.report.ok) {
                                            "APK 已加载：下一步需要接入代理 Activity 才能显示界面。"
                                        } else {
                                            "APK 加载未通过，见下方诊断。"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (current.report.ok) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.error
                                        },
                                    )
                                    current.report.notes.forEach { note ->
                                        Text(
                                            text = "· $note",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { finish() },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("关闭") }
                        }
                    }
                }
            }
        }
    }

    private suspend fun load(instanceId: String, container: com.adv.bb.app.AppContainer?): LaunchState {
        if (instanceId.isEmpty()) return LaunchState.Failed("缺少实例标识")
        intent.getStringExtra(GuestLauncher.EXTRA_HOOK_ERROR)?.let {
            return LaunchState.Failed("代理启动失败：$it")
        }
        // gate 的 Intent 里本来就带齐了 LaunchRequest 的扁平字段（instanceId / apk /
        // launcher / minSdk / data），子进程不需要 Room 也能重建它。
        val fromIntent = GuestLauncher.requestFromIntent(intent)
            ?: GuestLauncher.requestFromRecord(this)
        if (fromIntent != null) {
            return LaunchState.Loaded(
                request = fromIntent,
                report = GuestLoader(this).load(fromIntent),
            )
        }
        val host = container ?: return LaunchState.Failed("缺少实例参数（:guest 进程没有账本）")
        return when (val prepared = host.instanceRepository.prepareLaunch(instanceId)) {
            is EngineResult.Failure -> LaunchState.Failed(prepared.error.detail)
            is EngineResult.Success -> LaunchState.Loaded(
                request = prepared.value,
                report = GuestLoader(this).load(prepared.value),
            )
        }
    }
}

@Composable
private fun Line(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall)
    }
}
