package com.desktwin.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.desktwin.app.network.TreeEntry
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.treeMirrorStore by preferencesDataStore(name = "desktwin_tree_mirror")

/**
 * النسخة الكاملة من metadata شجرة ملفات وفولدرات الكمبيوتر - مش الملفات
 * الفعلية بحجمها الحقيقي، بس مسارات/أحجام/تواريخ تعديل + علامة is_folder.
 * الهدف: التليفون يقدر "يتصفح" شكل شجرة الكمبيوتر وهو أوفلاين بالكامل
 * (زي مستكشف ملفات محلي)، حتى لو مش هيقدر يفتح محتوى الملف الفعلي غير
 * وهو أونلاين.
 *
 * مختلف عن LocalCache: ده بيخزن أحدث نسخة موحدة للشجرة للتصفح أوفلاين،
 * مع baseline منفصل لا يتغير إلا بعد مراجعة /sync/tree-diff.
 */
class TreeMirrorStore(private val context: Context) {

    companion object {
        private val SNAPSHOT_KEY = stringPreferencesKey("tree_snapshot")
        private val SYNC_BASELINE_KEY = stringPreferencesKey("tree_sync_baseline")
        private val SYNC_BASELINE_INITIALIZED_KEY = booleanPreferencesKey("tree_sync_baseline_initialized")
        private val LAST_SYNCED_KEY = stringPreferencesKey("tree_last_synced_at")
        private val COVERED_FOLDERS_KEY = stringPreferencesKey("tree_covered_folders")
    }

    private val gson = Gson()
    private val listType = object : TypeToken<List<TreeEntry>>() {}.type
    private val pathListType = object : TypeToken<List<String>>() {}.type

    suspend fun saveSnapshot(entries: List<TreeEntry>) {
        context.treeMirrorStore.edit { prefs ->
            prefs[SNAPSHOT_KEY] = gson.toJson(entries)
            prefs[LAST_SYNCED_KEY] = System.currentTimeMillis().toString()
        }
    }

    /** Save a server-provided complete tree and mark its folders as browsed snapshots. */
    suspend fun saveFullSnapshot(entries: List<TreeEntry>, initializeSyncBaseline: Boolean = false) {
        context.treeMirrorStore.edit { prefs ->
            prefs[SNAPSHOT_KEY] = gson.toJson(entries)
            prefs[COVERED_FOLDERS_KEY] = gson.toJson(entries.filter { it.is_folder }.map { it.path })
            if (initializeSyncBaseline) {
                prefs[SYNC_BASELINE_KEY] = gson.toJson(entries)
                prefs[SYNC_BASELINE_INITIALIZED_KEY] = true
            }
            prefs[LAST_SYNCED_KEY] = System.currentTimeMillis().toString()
        }
    }

    /** Migrate an existing mirror into the review baseline without changing its contents. */
    suspend fun ensureSyncBaseline() {
        context.treeMirrorStore.edit { prefs ->
            if (SYNC_BASELINE_KEY !in prefs) {
                prefs[SYNC_BASELINE_KEY] = prefs[SNAPSHOT_KEY] ?: "[]"
                prefs[SYNC_BASELINE_INITIALIZED_KEY] = SNAPSHOT_KEY in prefs
            }
        }
    }

    suspend fun hasSyncBaseline(): Boolean {
        val prefs = context.treeMirrorStore.data.first()
        return prefs[SYNC_BASELINE_INITIALIZED_KEY] ?: (SNAPSHOT_KEY in prefs)
    }

    /** Replace one folder's children with the exact listing most recently returned by the server. */
    suspend fun replaceFolderContents(parentPath: String, entries: List<TreeEntry>) {
        context.treeMirrorStore.edit { prefs ->
            val current = runCatching {
                prefs[SNAPSHOT_KEY]?.let { gson.fromJson<List<TreeEntry>>(it, listType) }.orEmpty()
            }.getOrDefault(emptyList())
            val incomingByPath = entries.associateBy { it.path }
            val existingDirectChildren = current.filter { isDirectParent(parentPath, it.path) }
            val removedSubtrees = existingDirectChildren.filter { existing ->
                val incoming = incomingByPath[existing.path]
                incoming == null || (existing.is_folder && !incoming.is_folder)
            }.map { it.path }
            val updated = current.filterNot { existing ->
                isDirectParent(parentPath, existing.path) ||
                    removedSubtrees.any { isSameOrDescendant(it, existing.path) && !it.equals(existing.path, true) }
            }.associateBy { it.path }.toMutableMap()
            entries.forEach { updated[it.path] = it }

            val covered = runCatching {
                prefs[COVERED_FOLDERS_KEY]?.let { gson.fromJson<List<String>>(it, pathListType) }.orEmpty()
            }.getOrDefault(emptyList())
            val removedCoverage = removedSubtrees + entries.filterNot { it.is_folder }.map { it.path }
            val refreshedCoverage = covered
                .filterNot { folder -> removedCoverage.any { isSameOrDescendant(it, folder) } }
                .plus(parentPath)
                .distinct()

            prefs[SNAPSHOT_KEY] = gson.toJson(updated.values.toList())
            prefs[COVERED_FOLDERS_KEY] = gson.toJson(refreshedCoverage)
            prefs[LAST_SYNCED_KEY] = System.currentTimeMillis().toString()
        }
    }

    suspend fun hasFolderListing(path: String): Boolean {
        val json = context.treeMirrorStore.data.first()[COVERED_FOLDERS_KEY] ?: return false
        val covered = runCatching { gson.fromJson<List<String>>(json, pathListType).orEmpty() }.getOrDefault(emptyList())
        return covered.any { it.equals(path, ignoreCase = true) }
    }

    suspend fun getSnapshot(): List<TreeEntry> {
        val data = context.treeMirrorStore.data.first()
        val json = data[SNAPSHOT_KEY] ?: return emptyList()
        return try {
            gson.fromJson<List<TreeEntry>>(json, listType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getSyncBaseline(): List<TreeEntry> {
        val data = context.treeMirrorStore.data.first()
        val json = data[SYNC_BASELINE_KEY] ?: data[SNAPSHOT_KEY] ?: return emptyList()
        return runCatching { gson.fromJson<List<TreeEntry>>(json, listType) ?: emptyList() }.getOrDefault(emptyList())
    }

    /**
     * يحدّث baseline المزامنة بالتغييرات التي وافق عليها المستخدم؛ لا يغيّر
     * أحدث نسخة عرض محفوظة، لأنها تمثل آخر ما رآه التطبيق من الكمبيوتر.
     */
    suspend fun applyDiff(added: List<TreeEntry>, removedPaths: List<String>, modified: List<TreeEntry>) {
        val current = getSyncBaseline().associateBy { it.path }.toMutableMap()
        for (path in removedPaths) current.remove(path)
        for (entry in added) current[entry.path] = entry
        for (entry in modified) current[entry.path] = entry
        context.treeMirrorStore.edit { prefs ->
            prefs[SYNC_BASELINE_KEY] = gson.toJson(current.values.toList())
            prefs[SYNC_BASELINE_INITIALIZED_KEY] = true
        }
    }

    /** كل العناصر (ملفات وفولدرات) اللي مسارها الأب بالظبط يساوي parentPath - لعرض شاشة التصفح الأوفلاين. */
    suspend fun getChildrenOf(parentPath: String?): List<TreeEntry> {
        val all = getSnapshot()
        if (parentPath == null) {
            // الجذر: أقصر المسارات (الـ targets نفسها) - أي عنصر مفيش
            // عنصر تاني في الشجرة يعتبر أب مباشر له.
            val allPaths = all.map { it.path }.toSet()
            return all.filter { entry ->
                allPaths.none { other -> other != entry.path && isDirectParent(other, entry.path) }
            }
        }
        return all.filter { isDirectParent(parentPath, it.path) }
    }

    /**
     * تطبيق تفاؤلي (optimistic) لأمر مؤجل على شكل الشجرة المعروض فورًا -
     * قبل ما يتبعت للكمبيوتر أصلًا. الهدف: لو حذفت ملف وأنت أوفلاين،
     * يختفي من الشجرة على طول بدل ما يفضل ظاهر لحد ما تتصل وتوافق على
     * المراجعة. ده عرض بس - المصدر الحقيقي (الكمبيوتر) ميتغيرش غير بعد
     * إرسال الدفعة والموافقة الفعلية؛ لو الأمر اتلغى أو حصل فيه تعارض،
     * refreshFromServerSnapshot() (بعد استلام tree-diff/full-snapshot
     * جديد) بيرجّع الحالة الحقيقية.
     */
    suspend fun applyOptimisticDelete(path: String) {
        val current = getSnapshot().toMutableList()
        // حذف الملف/الفولدر نفسه، وأي عنصر تحته لو كان فولدر (حذف تكراري).
        val remaining = current.filterNot { it.path == path || it.path.startsWith(path + "\\") || it.path.startsWith(path + "/") }
        saveSnapshot(remaining)
    }

    suspend fun applyOptimisticRename(path: String, newName: String) {
        val current = getSnapshot()
        val target = current.find { it.path == path } ?: return
        val parent = target.path.let { p ->
            val trimmed = p.trimEnd('\\', '/')
            val i = maxOf(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'))
            if (i > 0) trimmed.substring(0, i) else ""
        }
        val separator = if (target.path.contains('\\')) "\\" else "/"
        val newPath = if (parent.isEmpty()) newName else "$parent$separator$newName"

        val updated = current.map { entry ->
            when {
                entry.path == path -> entry.copy(path = newPath, name = newName)
                entry.path.startsWith(path + separator) ->
                    entry.copy(path = newPath + entry.path.removePrefix(path))
                else -> entry
            }
        }
        saveSnapshot(updated)
    }

    private fun isDirectParent(parentPath: String, childPath: String): Boolean {
        if (!childPath.startsWith(parentPath, ignoreCase = true)) return false
        val remainder = childPath.removePrefix(parentPath).trimStart('\\', '/')
        return remainder.isNotEmpty() && !remainder.contains('\\') && !remainder.contains('/')
    }

    private fun isDescendant(parentPath: String, candidatePath: String): Boolean {
        val parent = parentPath.trimEnd('\\', '/')
        return candidatePath.startsWith("$parent\\", ignoreCase = true) ||
            candidatePath.startsWith("$parent/", ignoreCase = true)
    }

    private fun isSameOrDescendant(parentPath: String, candidatePath: String): Boolean =
        candidatePath.equals(parentPath, ignoreCase = true) || isDescendant(parentPath, candidatePath)

    val lastSyncedAt: Flow<Long?> = context.treeMirrorStore.data.map { prefs ->
        prefs[LAST_SYNCED_KEY]?.toLongOrNull()
    }
}
