package com.desktwin.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.deskTwinPrefs by preferencesDataStore(name = "desktwin_prefs")

data class FavoriteFolder(val path: String, val name: String)

class DeskTwinPreferences(private val context: Context) {
    companion object {
        private val TOKEN = stringPreferencesKey("device_token")
        private val IP = stringPreferencesKey("last_known_ip")
        private val PORT = stringPreferencesKey("last_known_port")
        private val NAME = stringPreferencesKey("computer_name")
        private val CERTIFICATE_FINGERPRINT = stringPreferencesKey("certificate_fingerprint")
        private val THEME_MODE = stringPreferencesKey("theme_mode")
        private val THEME_PRESET = stringPreferencesKey("theme_preset")
        private val REDUCE_MOTION = booleanPreferencesKey("reduce_motion")
        private val LANGUAGE = stringPreferencesKey("language")
        private val INTRO_VIDEO_URI = stringPreferencesKey("intro_video_uri")
        private val INTRO_MODE = stringPreferencesKey("intro_mode")
        private val FAVORITE_FOLDERS = stringPreferencesKey("favorite_folders")
        private val FILE_VIEW_MODE = stringPreferencesKey("file_view_mode")
    }
    val deviceToken: Flow<String?> = context.deskTwinPrefs.data.map { it[TOKEN]?.let(SecureTokenCodec::decrypt) }
    val lastKnownIp: Flow<String?> = context.deskTwinPrefs.data.map { it[IP] }
    val lastKnownPort: Flow<String?> = context.deskTwinPrefs.data.map { it[PORT] }
    val computerName: Flow<String?> = context.deskTwinPrefs.data.map { it[NAME] }
    suspend fun saveToken(token: String) = context.deskTwinPrefs.edit { it[TOKEN] = SecureTokenCodec.encrypt(token) }
    suspend fun saveCertificateFingerprint(value: String) = context.deskTwinPrefs.edit { it[CERTIFICATE_FINGERPRINT] = value }
    val certificateFingerprint: Flow<String?> = context.deskTwinPrefs.data.map { it[CERTIFICATE_FINGERPRINT] }
    val themeMode: Flow<String> = context.deskTwinPrefs.data.map { it[THEME_MODE] ?: "dark" }
    val themePreset: Flow<String> = context.deskTwinPrefs.data.map { it[THEME_PRESET] ?: "desktwin" }
    val themeSoundUris: Flow<Map<String, String>> = context.deskTwinPrefs.data.map { preferences ->
        preferences.asMap().mapNotNull { (key, value) ->
            if (key.name.startsWith(THEME_SOUND_PREFIX) && value is String) {
                key.name.removePrefix(THEME_SOUND_PREFIX) to value
            } else null
        }.toMap()
    }
    val reduceMotion: Flow<Boolean> = context.deskTwinPrefs.data.map { it[REDUCE_MOTION] ?: false }
    val language: Flow<String> = context.deskTwinPrefs.data.map { it[LANGUAGE] ?: "ar" }
    val introVideoUri: Flow<String?> = context.deskTwinPrefs.data.map { it[INTRO_VIDEO_URI] }
    val introMode: Flow<String> = context.deskTwinPrefs.data.map { it[INTRO_MODE] ?: "auto" }
    val fileViewMode: Flow<String> = context.deskTwinPrefs.data.map { it[FILE_VIEW_MODE] ?: "list" }
    private val favoritesType = object : TypeToken<List<FavoriteFolder>>() {}.type
    val favoriteFolders: Flow<List<FavoriteFolder>> = context.deskTwinPrefs.data.map { prefs ->
        runCatching { Gson().fromJson<List<FavoriteFolder>>(prefs[FAVORITE_FOLDERS] ?: "[]", favoritesType) ?: emptyList() }
            .getOrDefault(emptyList())
    }
    suspend fun saveThemeMode(value: String) = context.deskTwinPrefs.edit { it[THEME_MODE] = value }
    suspend fun saveThemePreset(value: String) = context.deskTwinPrefs.edit { it[THEME_PRESET] = value }
    suspend fun saveThemeSoundUri(themeId: String, uri: String?) {
        require(themeId.matches(Regex("[a-z0-9-]{1,48}"))) { "Invalid theme id" }
        val key = stringPreferencesKey(THEME_SOUND_PREFIX + themeId)
        context.deskTwinPrefs.edit { preferences ->
            if (uri.isNullOrBlank()) preferences.remove(key) else preferences[key] = uri
        }
    }
    suspend fun saveReduceMotion(value: Boolean) = context.deskTwinPrefs.edit { it[REDUCE_MOTION] = value }
    suspend fun saveLanguage(value: String) = context.deskTwinPrefs.edit { it[LANGUAGE] = value }
    suspend fun saveIntroVideoUri(value: String) = context.deskTwinPrefs.edit { it[INTRO_VIDEO_URI] = value }
    suspend fun clearIntroVideoUri() = context.deskTwinPrefs.edit { it.remove(INTRO_VIDEO_URI) }
    suspend fun saveIntroMode(value: String) = context.deskTwinPrefs.edit { it[INTRO_MODE] = value }
    suspend fun saveFileViewMode(value: String) = context.deskTwinPrefs.edit { it[FILE_VIEW_MODE] = value }
    suspend fun toggleFavoriteFolder(path: String, name: String) = context.deskTwinPrefs.edit { prefs ->
        val current = runCatching { Gson().fromJson<List<FavoriteFolder>>(prefs[FAVORITE_FOLDERS] ?: "[]", favoritesType) ?: emptyList() }
            .getOrDefault(emptyList())
        val next = if (current.any { it.path == path }) current.filterNot { it.path == path }
        else (current + FavoriteFolder(path, name)).takeLast(12)
        prefs[FAVORITE_FOLDERS] = Gson().toJson(next)
    }
    suspend fun saveLastConnection(ip: String, port: Int, computerName: String) = context.deskTwinPrefs.edit {
        it[IP] = ip; it[PORT] = port.toString(); it[NAME] = computerName
    }
    suspend fun clearAll() = context.deskTwinPrefs.edit { it.clear() }
}

private const val THEME_SOUND_PREFIX = "theme_sound_uri_"
