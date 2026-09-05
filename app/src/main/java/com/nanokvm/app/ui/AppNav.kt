package com.nanokvm.app.ui

import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import okhttp3.OkHttpClient
import com.nanokvm.app.settings.SettingsStore
import com.nanokvm.app.ui.connect.ConnectScreen
import com.nanokvm.app.ui.connect.ConnectViewModel
import com.nanokvm.app.ui.console.ConsoleScreen
import com.nanokvm.app.ui.console.ConsoleViewModel
import com.nanokvm.app.ui.terminal.TerminalLauncher
import com.nanokvm.app.ui.terminal.TerminalRequest
import com.nanokvm.app.ui.terminal.TerminalScreen
import com.nanokvm.app.ui.assistant.AssistantChatScreen

@Composable
fun AppNav(
    okHttp: OkHttpClient,
    settingsStore: SettingsStore,
    navController: NavHostController = rememberNavController(),
    isDark: Boolean,
    onToggleTheme: () -> Unit,
) {
    NavHost(navController = navController, startDestination = "connect") {
        composable("connect") {
            val vm: ConnectViewModel = viewModel(factory = ConnectViewModel.factory(settingsStore))
            ConnectScreen(
                viewModel = vm,
                isDark = isDark,
                onToggleTheme = onToggleTheme,
                onConnected = { _, _, _ ->
                    navController.navigate("console") { launchSingleTop = true }
                },
            )
        }
        composable("console") {
            val vm: ConsoleViewModel = viewModel(
                factory = ConsoleViewModel.factory(
                    host = AppSession.host,
                    username = AppSession.username,
                    password = AppSession.password,
                    okHttp = okHttp,
                    appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext,
                ),
            )
            ConsoleScreen(
                viewModel = vm,
                isDark = isDark,
                onToggleTheme = onToggleTheme,
                onBack = { navController.popBackStack() },
                onOpenTerminal = { req ->
                    TerminalLauncher.request = req
                    navController.navigate("terminal")
                },
                onOpenAssistant = { navController.navigate("assistant") },
            )
        }
        composable("terminal") {
            TerminalScreen(
                request = TerminalLauncher.request,
                host = AppSession.host,
                username = AppSession.username,
                password = AppSession.password,
                onExit = { navController.popBackStack() },
            )
        }
        composable("assistant") {
            AssistantChatScreen(
                host = AppSession.host,
                onExit = { navController.popBackStack() },
            )
        }
    }
}