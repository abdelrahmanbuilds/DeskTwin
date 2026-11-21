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

private val Context.offlineCommandStore by preferencesDataStore(name = "desktwin_offline_commands")

/**
 * أمر واحد اتسجل وأنت أوفلاين على شجرة الملفات المرآة (حذف/تسمية/طلب
 * تحميل/طلب رفع). بيتخزن محليًا بترتيب زمني (addedAt) عشان لما نبعتهم
 * للكمبيوتر كدفعة واحدة بعد الاتصال، الترتيب يتحفظ بالظبط زي ما حصل.
 */
data class QueuedOfflineCommand(
    val id: String,
    val commandType: String,  // delete | rename | download | upload | copy | move
    val filePath: String,
    val displayName: String,   // اسم الملف/الفولدر - للعرض في شاشة المراجعة بس
    val newValue: String? = null,  // الاسم الجديد لو rename، أو target_folder لو upload
    val addedAt: Long
)

class OfflineCommandQueue(private val context: Context) {

    companion object {
        private val QUEUE_KEY = stringPreferencesKey("queued_offline_commands")
    }

    private val gson = Gson()
    private val listType = object : TypeToken<List<QueuedOfflineCommand>>() {}.type

    /** بترتيب addedAt دايمًا - ده الترتيب اللي المفروض ينفذ بيه الكمبيوتر بالظبط. */
    val queue: Flow<List<QueuedOfflineCommand>> = context.offlineCommandStore.data.map { prefs ->
        val json = prefs[QUEUE_KEY] ?: "[]"
        try {
            (gson.fromJson<List<QueuedOfflineCommand>>(json, listType) ?: emptyList())
                .sortedBy { it.addedAt }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun add(item: QueuedOfflineCommand) {
        context.offlineCommandStore.edit { prefs ->
            val current = getCurrentList(prefs[QUEUE_KEY])
            // منع تكرار نفس النية عند ضغط المستخدم أكثر من مرة أو عند
            // إعادة محاولة الطلب بعد timeout. النسخ/التحميل يفضلان قابلية
            // التكرار، أما rename/delete/move فهي أوامر حالة لا داعي لتكرارها.
            val stateCommand = item.commandType in setOf("rename", "delete", "move")
            val duplicate = stateCommand && current.any {
                it.commandType == item.commandType &&
                    it.filePath == item.filePath &&
                    it.newValue == item.newValue
            }
            val updated = if (duplicate) current else current + item
            prefs[QUEUE_KEY] = gson.toJson(updated)
        }
    }

    suspend fun remove(id: String) {
        context.offlineCommandStore.edit { prefs ->
            val current = getCurrentList(prefs[QUEUE_KEY])
            val updated = current.filter { it.id != id }
            prefs[QUEUE_KEY] = gson.toJson(updated)
        }
    }

    suspend fun clear() {
        context.offlineCommandStore.edit { prefs ->
            prefs[QUEUE_KEY] = "[]"
        }
    }

    suspend fun getAll(): List<QueuedOfflineCommand> {
        val data = context.offlineCommandStore.data.first()
        return getCurrentList(data[QUEUE_KEY]).sortedBy { it.addedAt }
    }

    private fun getCurrentList(json: String?): List<QueuedOfflineCommand> {
        if (json.isNullOrEmpty()) return emptyList()
        return try {
            gson.fromJson(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
