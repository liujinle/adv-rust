package com.adv.bb.app

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.adv.core.data.AppSettings
import com.adv.core.data.ThemeMode
import com.adv.core.design.AdvTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as AdvApplication).container
        setContent {
            val settingsFlow = remember(container) { container.observeSettings() }
            val settings by settingsFlow.collectAsStateWithLifecycle(initialValue = AppSettings.Default)
            val darkTheme = settings.themeMode.isDark()

            // `enableEdgeToEdge()` picks bar icon colours from the *system* night
            // mode; when the user overrides the theme in-app the icons must follow
            // our scheme instead.
            val view = LocalView.current
            SideEffect {
                val window = (view.context as? Activity)?.window ?: return@SideEffect
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            AdvTheme(darkTheme = darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AdvNavHost(container)
                }
            }
        }
    }

    /**
     * 切回宿主时立即补一次运行态对账：常驻看护是 5s 一轮，用户从最近任务/锁屏
     * 回来时不该等这一拍才看见「运行中」被收回。
     */
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            runCatching { (application as AdvApplication).container.runtimeWatch.reconcileOnce() }
        }
    }
}

@Composable
private fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}
