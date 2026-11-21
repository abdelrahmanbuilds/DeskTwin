package com.desktwin.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.commandQueueStore by preferencesDataStore(name = "desktwin_command_queue")
enum class CommandType { DELETE_FILE, RENAME_FILE, SET_AVAILABILITY }
data class QueuedCommand(val id: String, val type: CommandType, val filePath: String? = null, val fileId: Int? = null, val newValue: String? = null, val computerToken: String, val createdAt: Long)

class CommandQueue(private val context: Context) {
    private val key = stringPreferencesKey("queued_commands")
    private val gson = Gson()
    private val type = object : TypeToken<List<QueuedCommand>>() {}.type

    val queue: Flow<List<QueuedCommand>> = context.commandQueueStore.data.map { prefs -> parse(prefs[key]) }

    suspend fun add(value: QueuedCommand) {
        context.commandQueueStore.edit { prefs ->
            val current = parse(prefs[key])
            prefs[key] = encode(current + value)
        }
    }

    suspend fun remove(id: String) {
        context.commandQueueStore.edit { prefs ->
            val current = parse(prefs[key])
            prefs[key] = encode(current.filter { q -> q.id != id })
        }
    }

    suspend fun getForComputer(token: String): List<QueuedCommand> {
        val prefs = context.commandQueueStore.data.first()
        val stored = prefs[key]
        val commands = parse(stored)
        // Migrate existing plaintext queues the next time they are accessed;
        // never leave a bearer token readable merely because the user has not
        // added or removed an offline command since upgrading.
        if (!stored.isNullOrEmpty() && SecureTokenCodec.decrypt(stored) == null) {
            context.commandQueueStore.edit { current ->
                if (current[key] == stored) current[key] = encode(commands)
            }
        }
        return commands.filter { it.computerToken == token }
    }

    private fun parse(json: String?): List<QueuedCommand> {
        return runCatching {
            // Existing installations stored this queue as plaintext JSON. Read
            // it once for migration; every later write re-encrypts it with the
            // Android Keystore-backed key.
            val serialized = json?.let { SecureTokenCodec.decrypt(it) ?: it }
            if (serialized.isNullOrEmpty()) emptyList() else gson.fromJson<List<QueuedCommand>>(serialized, type) ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun encode(values: List<QueuedCommand>): String =
        SecureTokenCodec.encrypt(gson.toJson(values))
}
