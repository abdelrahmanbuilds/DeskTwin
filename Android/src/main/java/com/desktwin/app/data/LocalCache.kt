package com.desktwin.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.desktwin.app.network.BrowseResponse
import com.desktwin.app.network.HomeResponse
import com.google.gson.Gson
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.cacheStore by preferencesDataStore(name = "desktwin_local_cache")

private const val BROWSE_KEY_PREFIX = "browse_v2_"
private const val BROWSE_TIME_KEY_PREFIX = "browse_time_v2_"
private const val MAX_BROWSE_SNAPSHOTS = 60
private const val MAX_BROWSE_CACHE_BYTES = 8 * 1024 * 1024
private const val MAX_BROWSE_SNAPSHOT_BYTES = 2 * 1024 * 1024
private const val BROWSE_CACHE_MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000

private fun browseDigest(path: String): String = MessageDigest.getInstance("SHA-256")
    .digest(path.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

class LocalCache(private val context: Context) {
    private fun browseKey(path: String) = stringPreferencesKey(BROWSE_KEY_PREFIX + browseDigest(path))
    private fun browseTimeKey(path: String) = stringPreferencesKey(BROWSE_TIME_KEY_PREFIX + browseDigest(path))
    private val homeKey = stringPreferencesKey("home_snapshot"); private val syncKey = stringPreferencesKey("last_sync_time")
    private val gson = Gson()
    suspend fun saveBrowseSnapshot(path: String, value: BrowseResponse) {
        val key = browseKey(path)
        val timeKey = browseTimeKey(path)
        val json = gson.toJson(value)
        val sizeBytes = json.toByteArray(Charsets.UTF_8).size
        val now = System.currentTimeMillis()
        context.cacheStore.edit { preferences ->
            // Drop snapshots written by the previous hashCode-based key scheme; hashCode
            // collisions could otherwise make one folder display another folder's files.
            preferences.asMap().keys.map { it.name }
                .filter { it.startsWith("browse_") && !it.startsWith(BROWSE_KEY_PREFIX) && !it.startsWith(BROWSE_TIME_KEY_PREFIX) }
                .forEach { preferences.remove(stringPreferencesKey(it)) }

            if (sizeBytes <= MAX_BROWSE_SNAPSHOT_BYTES) {
                preferences[key] = json
                preferences[timeKey] = now.toString()
            } else {
                preferences.remove(key)
                preferences.remove(timeKey)
            }
            preferences[syncKey] = now.toString()

            val snapshots = preferences.asMap().keys.map { it.name }
                .filter { it.startsWith(BROWSE_KEY_PREFIX) }
                .map { name ->
                    val digest = name.removePrefix(BROWSE_KEY_PREFIX)
                    val snapshotKey = stringPreferencesKey(name)
                    val timestamp = preferences[stringPreferencesKey(BROWSE_TIME_KEY_PREFIX + digest)]?.toLongOrNull() ?: 0L
                    Triple(name, preferences[snapshotKey]?.toByteArray(Charsets.UTF_8)?.size ?: 0, timestamp)
                }
                .sortedBy { it.third }
            var count = snapshots.size
            var bytes = snapshots.sumOf { it.second }
            for ((name, entryBytes, timestamp) in snapshots) {
                if (name == key.name || (timestamp > 0L && now - timestamp <= BROWSE_CACHE_MAX_AGE_MS)) continue
                preferences.remove(stringPreferencesKey(name))
                preferences.remove(stringPreferencesKey(BROWSE_TIME_KEY_PREFIX + name.removePrefix(BROWSE_KEY_PREFIX)))
                count--
                bytes -= entryBytes
            }
            for ((name, entryBytes, _) in snapshots) {
                if (count <= MAX_BROWSE_SNAPSHOTS && bytes <= MAX_BROWSE_CACHE_BYTES) break
                if (name == key.name || preferences[stringPreferencesKey(name)] == null) continue
                preferences.remove(stringPreferencesKey(name))
                preferences.remove(stringPreferencesKey(BROWSE_TIME_KEY_PREFIX + name.removePrefix(BROWSE_KEY_PREFIX)))
                count--
                bytes -= entryBytes
            }
        }
    }

    suspend fun getBrowseSnapshot(path: String): BrowseResponse? = runCatching {
        val key = browseKey(path)
        val timeKey = browseTimeKey(path)
        val preferences = context.cacheStore.data.first()
        val json = preferences[key] ?: return@runCatching null
        val savedAt = preferences[timeKey]?.toLongOrNull() ?: 0L
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > BROWSE_CACHE_MAX_AGE_MS) {
            context.cacheStore.edit {
                it.remove(key)
                it.remove(timeKey)
            }
            return@runCatching null
        }
        gson.fromJson(json, BrowseResponse::class.java)
    }.getOrNull()
    suspend fun saveHomeSnapshot(value: HomeResponse) = context.cacheStore.edit { it[homeKey] = gson.toJson(value); it[syncKey] = System.currentTimeMillis().toString() }
    suspend fun getHomeSnapshot(): HomeResponse? = runCatching { context.cacheStore.data.first()[homeKey]?.let { gson.fromJson(it, HomeResponse::class.java) } }.getOrNull()
    val lastSyncTime: Flow<Long?> = context.cacheStore.data.map { it[syncKey]?.toLongOrNull() }
}
