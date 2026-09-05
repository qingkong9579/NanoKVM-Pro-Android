package com.nanokvm.app.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appDataStore by preferencesDataStore(name = "app_settings")

enum class ThemeSetting { SYSTEM, LIGHT, DARK }

/** Persisted connection/UX preferences. Passwords are stored in plain DataStore —
 *  acceptable for a LAN KVM MVP; switch to EncryptedSharedPreferences if this ships. */
data class AppSettings(
    val host: String = "",
    val username: String = "",
    val password: String = "",
    val streamMode: String = "h264-direct",
    val mouseMode: String = "absolute",
    val theme: ThemeSetting = ThemeSetting.SYSTEM,
)

class SettingsStore(private val context: Context) {
    companion object {
        private val HOST = stringPreferencesKey("host")
        private val USERNAME = stringPreferencesKey("username")
        private val PASSWORD = stringPreferencesKey("password")
        private val STREAM_MODE = stringPreferencesKey("stream_mode")
        private val MOUSE_MODE = stringPreferencesKey("mouse_mode")
        private val THEME = stringPreferencesKey("theme")
    }

    val settings: Flow<AppSettings> = context.appDataStore.data.map { prefs ->
        AppSettings(
            host = prefs[HOST] ?: "",
            username = prefs[USERNAME] ?: "",
            password = prefs[PASSWORD] ?: "",
            streamMode = prefs[STREAM_MODE] ?: "h264-direct",
            mouseMode = prefs[MOUSE_MODE] ?: "absolute",
            theme = when (prefs[THEME]) {
                "light" -> ThemeSetting.LIGHT
                "dark" -> ThemeSetting.DARK
                else -> ThemeSetting.SYSTEM
            },
        )
    }

    suspend fun save(host: String = "", username: String = "", password: String = "",
                     streamMode: String = "h264-direct", mouseMode: String = "absolute",
                     theme: ThemeSetting = ThemeSetting.SYSTEM) {
        context.appDataStore.edit { prefs ->
            prefs[HOST] = host
            prefs[USERNAME] = username
            prefs[PASSWORD] = password
            prefs[STREAM_MODE] = streamMode
            prefs[MOUSE_MODE] = mouseMode
            prefs[THEME] = when (theme) {
                ThemeSetting.LIGHT -> "light"
                ThemeSetting.DARK -> "dark"
                ThemeSetting.SYSTEM -> "system"
            }
        }
    }

    /** Persists only the theme — must not clobber saved connection fields. */
    suspend fun saveTheme(theme: ThemeSetting) {
        context.appDataStore.edit { prefs ->
            prefs[THEME] = when (theme) {
                ThemeSetting.LIGHT -> "light"
                ThemeSetting.DARK -> "dark"
                ThemeSetting.SYSTEM -> "system"
            }
        }
    }

    suspend fun clearConnection() {
        context.appDataStore.edit { prefs ->
            prefs.remove(HOST)
            prefs.remove(USERNAME)
            prefs.remove(PASSWORD)
        }
    }
}