package com.nanokvm.app.ui.connect

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.nanokvm.app.data.api.NanoKvmApi
import com.nanokvm.app.data.net.Tls
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.nanokvm.app.settings.SettingsStore
import java.util.concurrent.TimeUnit

data class ConnectUiState(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val showPassword: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
)

/**
 * Connect/login screen state. Loading persisted values on init, and (optionally)
 * validating the credentials against `POST /api/auth/login` before navigating — so
 * wrong passwords surface here rather than as a black screen in the console.
 */
class ConnectViewModel(
    private val store: SettingsStore,
) : ViewModel() {

    companion object {
        fun factory(store: SettingsStore): androidx.lifecycle.ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ConnectViewModel(store) }
            }
    }

    private val _state = MutableStateFlow(ConnectUiState())
    val state: StateFlow<ConnectUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val saved = store.settings.first()
            _state.value = ConnectUiState(
                host = saved.host,
                username = saved.username,
                password = saved.password,
            )
        }
    }

    fun onHostChange(v: String) { _state.value = _state.value.copy(host = v) }
    fun onUsernameChange(v: String) { _state.value = _state.value.copy(username = v) }
    fun onPasswordChange(v: String) { _state.value = _state.value.copy(password = v) }
    fun togglePasswordVisibility() {
        _state.value = _state.value.copy(showPassword = !_state.value.showPassword)
    }

    /** Host must be reachable & creds verified before proceeding. */
    fun connect(onSuccess: () -> Unit) {
        val s = _state.value
        val host = s.host.trim()
        val user = s.username.trim()
        val pass = s.password
        when {
            host.isEmpty() -> _state.value = s.copy(error = "请输入主机地址")
            user.isEmpty() -> _state.value = s.copy(error = "请输入用户名")
            pass.isEmpty() -> _state.value = s.copy(error = "请输入密码")
            else -> viewModelScope.launch {
                _state.value = s.copy(busy = true, error = null)
                // Persist the attempt so a later relaunch is pre-filled.
                store.save(host = host, username = user, password = pass)
                try {
                    val okHttp = Tls.okHttpBuilder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(8, TimeUnit.SECONDS)
                        .build()
                    NanoKvmApi("https://$host", okHttp).login(user, pass)
                    onSuccess()
                } catch (e: Exception) {
                    _state.value = _state.value.copy(error = "连接失败: ${e.message ?: "无法访问设备"}")
                } finally {
                    _state.value = _state.value.copy(busy = false)
                }
            }
        }
    }
}