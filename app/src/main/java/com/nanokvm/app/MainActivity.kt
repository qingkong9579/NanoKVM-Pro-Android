package com.nanokvm.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nanokvm.app.data.net.Tls
import com.nanokvm.app.settings.SettingsStore
import com.nanokvm.app.settings.ThemeSetting
import com.nanokvm.app.ui.AppNav
import com.nanokvm.app.ui.theme.NanoKvmProTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.nanokvm.app.ui.assistant.AssistSession.appContext = applicationContext
        setContent {
            val store = remember { SettingsStore(applicationContext) }

            // Theme default + persisted override (SYSTEM follows the OS).
            var themeSetting by remember { mutableStateOf(ThemeSetting.SYSTEM) }
            LaunchedEffect(Unit) {
                val s = store.settings.first()
                themeSetting = s.theme
                com.nanokvm.app.ui.theme.GlassPrefs.blurRadiusDp = s.blurRadius.toFloat()
                com.nanokvm.app.ui.theme.GlassPrefs.tintAlpha = s.glassAlpha / 100f
            }
            val systemDark = isSystemInDarkTheme()
            val dark = when (themeSetting) {
                ThemeSetting.SYSTEM -> systemDark
                ThemeSetting.LIGHT -> false
                ThemeSetting.DARK -> true
            }

            NanoKvmProTheme(darkTheme = dark) {
                val okHttp = remember { Tls.okHttpBuilder().build() }
                AppNav(
                    okHttp = okHttp,
                    settingsStore = store,
                    isDark = dark,
                    onToggleTheme = {
                        val next = if (dark) ThemeSetting.LIGHT else ThemeSetting.DARK
                        themeSetting = next
                        lifecycleScope.launch { store.saveTheme(next) }
                    },
                )
            }
        }
    }
}