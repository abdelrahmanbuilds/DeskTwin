package com.desktwin.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.computerProfile by preferencesDataStore(name = "desktwin_computer_profile")

data class ComputerProfile(
    val deviceToken: String,
    val computerName: String,
    val lastKnownIp: String,
    val lastKnownPort: Int,
    val boundUserName: String?
)

class ComputerProfileStore(private val context: Context) {
    private val token = stringPreferencesKey("profile_token")
    private val name = stringPreferencesKey("profile_name")
    private val ip = stringPreferencesKey("profile_ip")
    private val port = stringPreferencesKey("profile_port")
    private val user = stringPreferencesKey("profile_user")
    val profile: Flow<ComputerProfile?> = context.computerProfile.data.map { p ->
        p[token]?.let { encoded -> SecureTokenCodec.decrypt(encoded) }?.let { raw ->
            ComputerProfile(raw, p[name] ?: "الكمبيوتر", p[ip] ?: "", p[port]?.toIntOrNull() ?: 8420, p[user])
        }
    }
    suspend fun save(value: ComputerProfile) = context.computerProfile.edit { p ->
        p[token] = SecureTokenCodec.encrypt(value.deviceToken)
        p[name] = value.computerName; p[ip] = value.lastKnownIp; p[port] = value.lastKnownPort.toString()
        value.boundUserName?.let { p[user] = it }
    }
    suspend fun updateConnection(newIp: String, newPort: Int) = context.computerProfile.edit { p ->
        p[ip] = newIp; p[port] = newPort.toString()
    }
    suspend fun getCurrent(): ComputerProfile? = profile.first()
    suspend fun clear() = context.computerProfile.edit { it.clear() }
}
