package com.desktwin.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.uploadQueueStore by preferencesDataStore(name = "desktwin_upload_queue")
data class QueuedUpload(val id: String, val localFilePath: String, val fileName: String, val sizeBytes: Long, val targetFolder: String, val deleteAfterUpload: Boolean, val addedAt: Long, val setAsWallpaper: Boolean = false, val paused: Boolean = false)
class UploadQueue(private val context: Context) {
    private val key = stringPreferencesKey("queued_uploads"); private val gson = Gson(); private val type = object : TypeToken<List<QueuedUpload>>() {}.type
    val queue: Flow<List<QueuedUpload>> = context.uploadQueueStore.data.map { runCatching { gson.fromJson<List<QueuedUpload>>(it[key] ?: "[]", type) ?: emptyList() }.getOrDefault(emptyList()) }
    suspend fun add(value: QueuedUpload) = context.uploadQueueStore.edit { it[key] = gson.toJson(parse(it[key]) + value) }
    suspend fun remove(id: String) = context.uploadQueueStore.edit { it[key] = gson.toJson(parse(it[key]).filter { q -> q.id != id }) }
    suspend fun setPaused(id: String, value: Boolean) = context.uploadQueueStore.edit { it[key] = gson.toJson(parse(it[key]).map { q -> if (q.id == id) q.copy(paused = value) else q }) }
    private fun parse(json: String?) = runCatching { if (json.isNullOrEmpty()) emptyList() else gson.fromJson<List<QueuedUpload>>(json, type) ?: emptyList() }.getOrDefault(emptyList())
}
