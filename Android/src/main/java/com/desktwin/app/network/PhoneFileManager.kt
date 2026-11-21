package com.desktwin.app.network

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.util.Base64
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.util.UUID

/** Handles the phone-side SAF tree exposed to the paired computer. */
object PhoneFileManager {
    // Binary WebSocket chunks avoid the Base64 expansion while keeping phone RAM bounded.
    private const val TRANSFER_CHUNK_SIZE = 4 * 1024 * 1024
    private const val BINARY_CHUNK_SIZE = 1024 * 1024
    private const val BINARY_HEADER_SIZE = 44
    private val BINARY_MAGIC = byteArrayOf('D'.code.toByte(), 'T'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    private const val TAG = "PhoneFileManager"
    private const val PREFS = "phone_file_mirror"
    private const val ROOT = "tree_uri"
    private var context: Context? = null
    private val uploads = java.util.concurrent.ConcurrentHashMap<String, File>()
    private val uploadSizes = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val uploadMetadata = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()
    private data class CatalogEntry(
        val name: String, val path: String, val mime: String, val size: Long, val modified: Long,
        val duration: Long? = null, val artist: String? = null, val title: String? = null,
    )
    private data class CatalogSnapshot(val createdAt: Long, val entries: List<CatalogEntry>)
    private val catalogLock = Any()
    private val catalogCache = LinkedHashMap<String, CatalogSnapshot>()
    private var appCatalogCacheAt = 0L
    private var appCatalogCache = emptyList<JSONObject>()
    private val mediaMetadataCache = mutableMapOf<String, Pair<Long, MediaMetadata?>>()

    fun initialize(appContext: Context) { context = appContext.applicationContext }

    fun setTreeUri(uri: Uri) {
        val c = context ?: return
        c.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ROOT, uri.toString()).apply()
    }

    fun rootUri(): Uri? = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(ROOT, null)?.let(Uri::parse)

    private fun hasFullStorageAccess(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun externalRoot(): File = Environment.getExternalStorageDirectory().canonicalFile

    private fun externalFile(path: String): File {
        require(hasFullStorageAccess()) { "يجب السماح لـ DeskTwin بالوصول إلى كل ملفات الهاتف" }
        require(path == "@fs" || path.startsWith("@fs/")) { "مسار التخزين غير صالح" }
        val root = externalRoot()
        val relative = path.removePrefix("@fs").trimStart('/')
        val target = File(root, relative).canonicalFile
        require(target == root || target.path.startsWith(root.path + File.separator)) { "المسار يتجاوز تخزين الهاتف" }
        return target
    }

    private fun externalPath(file: File): String {
        val relative = externalRoot().toPath().relativize(file.canonicalFile.toPath()).toString().replace(File.separatorChar, '/')
        return if (relative.isBlank()) "@fs" else "@fs/$relative"
    }

    suspend fun handleUpload(request: JSONObject, emit: suspend (JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val id = request.optString("request_id")
        val c = context ?: return@withContext
        try {
            require(isValidRequestId(id)) { "معرّف النقل غير صالح" }
            when (request.optString("type")) {
                "phone_fs_upload_start" -> {
                    val expectedSize = request.optLong("size", -1L)
                    require(expectedSize >= 0) { "حجم الملف غير صالح" }
                    val file = File(c.cacheDir, "phone_upload_$id.part"); file.parentFile?.mkdirs()
                    if (file.length() > expectedSize) file.delete()
                    uploads[id] = file
                    uploadSizes[id] = expectedSize
                    uploadMetadata[id] = cleanUploadName(request.optString("name", id)) to clean(request.optString("destination"))
                    emit(success(id).put("type", "phone_fs_upload_ack").put("offset", file.length()))
                }
                "phone_fs_upload_chunk" -> {
                    val file = uploads[id] ?: File(c.cacheDir, "phone_upload_$id.part")
                    val offset = request.optLong("offset", -1L)
                    require(offset >= 0) { "موضع الملف غير صالح" }
                    if (file.length() != offset) throw IllegalStateException("offset غير متطابق")
                    val bytes = Base64.decode(request.optString("data"), Base64.DEFAULT)
                    val expectedSize = uploadSizes[id] ?: throw IllegalStateException("جلسة الرفع غير موجودة")
                    if (offset > expectedSize || bytes.size > expectedSize - offset) throw IllegalStateException("حجم الرفع تجاوز المتوقع")
                    java.io.RandomAccessFile(file, "rw").use { output -> output.seek(offset); output.write(bytes) }
                    uploads[id] = file
                }
                "phone_fs_upload_cancel" -> {
                    uploads.remove(id)?.delete()
                    uploadSizes.remove(id)
                    uploadMetadata.remove(id)
                }
                "phone_fs_upload_finish" -> {
                    val staged = uploads[id] ?: throw IllegalStateException("ملف الرفع غير موجود")
                    if (staged.length() != uploadSizes[id]) throw IllegalStateException("حجم الملف المستلم غير مكتمل")
                    val (name, destination) = uploadMetadata[id] ?: throw IllegalStateException("بيانات جلسة الرفع غير موجودة")
                    val uploadedSize = staged.length()
                    var uploadedName = name
                    if (destination == "@fs" || destination.startsWith("@fs/")) {
                        val parent = externalFile(destination)
                        require(parent.isDirectory) { "مجلد الرفع غير موجود" }
                        val target = uniqueFile(parent, name)
                        uploadedName = target.name
                        staged.inputStream().use { input -> target.outputStream().use { input.copyTo(it, bufferSize = 1024 * 1024) } }
                    } else {
                        val root = rootUri() ?: throw IllegalStateException("لم يتم اختيار مجلد الهاتف")
                        val parent = childUri(root, docId(root, destination))
                        val extension = name.substringAfterLast('.', "").lowercase()
                        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                        val target = DocumentsContract.createDocument(resolver(), parent, mime, name) ?: throw IllegalStateException("تعذر إنشاء الوجهة")
                        staged.inputStream().use { input -> resolver().openOutputStream(target)!!.use { input.copyTo(it, bufferSize = 1024 * 1024) } }
                    }
                    invalidateCatalog()
                    uploads.remove(id)?.delete()
                    uploadSizes.remove(id)
                    uploadMetadata.remove(id)
                    emit(success(id).put("type", "phone_fs_response").put("event", "uploaded")
                        .put("name", uploadedName).put("destination", destination).put("size", uploadedSize))
                }
            }
        } catch (e: Exception) { emit(failure(id, e.message ?: "فشل رفع الملف")) }
    }

    /** Writes one versioned raw WebSocket upload frame to its staged file. */
    fun handleUploadBinary(frame: ByteArray) {
        require(frame.size in (BINARY_HEADER_SIZE + 1)..(BINARY_HEADER_SIZE + BINARY_CHUNK_SIZE)) {
            "حجم كتلة النقل الثنائي غير صالح"
        }
        require(frame.copyOfRange(0, 4).contentEquals(BINARY_MAGIC)) { "بروتوكول النقل الثنائي غير معروف" }
        val id = String(frame, 4, 32, Charsets.US_ASCII)
        require(id.matches(Regex("[0-9a-f]{32}"))) { "معرّف النقل الثنائي غير صالح" }
        val offset = ByteBuffer.wrap(frame, 36, 8).order(ByteOrder.BIG_ENDIAN).long
        require(offset >= 0) { "موضع كتلة النقل الثنائي غير صالح" }
        val file = uploads[id] ?: throw IllegalStateException("جلسة الرفع غير موجودة")
        if (file.length() != offset) throw IllegalStateException("offset غير متطابق")
        val bytesLength = frame.size - BINARY_HEADER_SIZE
        val expectedSize = uploadSizes[id] ?: throw IllegalStateException("جلسة الرفع غير موجودة")
        if (offset > expectedSize || bytesLength > expectedSize - offset)
            throw IllegalStateException("حجم الرفع تجاوز المتوقع")
        RandomAccessFile(file, "rw").use { output ->
            output.seek(offset)
            output.write(frame, BINARY_HEADER_SIZE, bytesLength)
        }
    }

    suspend fun handle(request: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = request.optString("request_id")
        try {
            require(isValidRequestId(id)) { "معرّف الطلب غير صالح" }
            val operation = request.optString("operation")
            val path = clean(request.optString("path", ""))
            if (operation == "refresh") {
                invalidateCatalog()
                return@withContext success(id).put("event", "refreshed")
            }
            if (operation == "catalog")
                return@withContext catalog(id, request)
            if (operation == "media_info") {
                if (path.startsWith("@fs") && !hasFullStorageAccess())
                    return@withContext failure(id, "يجب السماح لـ DeskTwin بالوصول إلى كل ملفات الهاتف")
                if (!path.startsWith("@fs") && rootUri() == null)
                    return@withContext failure(id, "لم يتم اختيار مجلد للهاتف بعد")
                val metadata = mediaMetadata(path)
                val response = success(id)
                metadata?.duration?.let { response.put("duration", it) }
                metadata?.artist?.let { response.put("artists", it) }
                metadata?.title?.let { response.put("title", it) }
                return@withContext response
            }
            if (operation == "list" && request.optBoolean("recursive") && request.optString("category") == "apps")
                return@withContext appList(id, request)
            if (path.startsWith("@app/")) {
                return@withContext when (operation) {
                    "thumbnail" -> appThumbnail(id, path.removePrefix("@app/"))
                    "stat" -> appStat(id, path.removePrefix("@app/"))
                    else -> failure(id, "عملية التطبيق غير مدعومة")
                }
            }
            if (operation == "roots" && hasFullStorageAccess()) {
                val root = externalRoot()
                val stats = StatFs(root.absolutePath)
                val info = JSONObject().put("path", "@fs").put("name", "Internal storage")
                    .put("storage", JSONObject().put("total_bytes", stats.totalBytes)
                        .put("free_bytes", stats.availableBytes).put("scope", "full_storage"))
                return@withContext success(id).put("roots", JSONArray().put(info))
            }
            if (operation == "trash_list") return@withContext trashList(id)
            if (operation == "trash_delete") return@withContext permanentDelete(id, request.optString("trash_id"))
            if (operation == "restore") return@withContext restore(id, request.optString("trash_id"))
            if ((path == "@fs" || path.startsWith("@fs/")) && hasFullStorageAccess()) {
                if (operation == "list" && request.optBoolean("recursive"))
                    return@withContext recursiveExternalList(id, request, request.optString("category", ""))
                return@withContext fullStorageHandle(id, operation, path, request)
            }
            val root = rootUri() ?: return@withContext failure(id, "لم يتم اختيار مجلد للهاتف بعد")
            if (operation == "list" && request.optBoolean("recursive")) {
                val category = request.optString("category", "")
                return@withContext recursiveList(id, root, request, category)
            }
            when (operation) {
                "roots" -> {
                    val rootInfo = JSONObject().put("path", "").put("name", "الهاتف")
                    storageStats(root)?.let { rootInfo.put("storage", it) }
                    success(id).put("roots", JSONArray().put(rootInfo))
                }
                "list" -> list(id, root, path, request)
                "stat" -> stat(id, root, path)
                "thumbnail" -> thumbnail(id, root, path)
                "rename" -> rename(id, root, path, request.optString("new_name"))
                "delete" -> trash(id, root, path)
                "restore" -> restore(id, request.optString("trash_id"))
                "trash_list" -> trashList(id)
                "trash_delete" -> permanentDelete(id, request.optString("trash_id"))
                "copy", "move" -> copyOrMove(id, root, path, clean(request.optString("destination")), operation == "move")
                "open" -> open(id, root, path)
                else -> failure(id, "عملية غير مدعومة: $operation")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Phone-file request failed (${request.optString("operation")})", e)
            failure(id, e.message ?: "فشل تنفيذ العملية")
        }
    }

    /** One phone round-trip returns both the storage root and the first listing page. */
    private fun catalog(id: String, request: JSONObject): JSONObject {
        val fullStorage = hasFullStorageAccess()
        val root = if (fullStorage) null else rootUri()
            ?: return failure(id, "لم يتم اختيار مجلد للهاتف بعد")
        val path = if (fullStorage) "@fs" else ""
        val rootInfo = if (fullStorage) {
            val stats = StatFs(externalRoot().absolutePath)
            JSONObject().put("path", path).put("name", "Internal storage")
                .put("storage", JSONObject().put("total_bytes", stats.totalBytes)
                    .put("free_bytes", stats.availableBytes).put("scope", "full_storage"))
        } else {
            JSONObject().put("path", path).put("name", "الهاتف").also { info ->
                storageStats(root!!)?.let { info.put("storage", it) }
            }
        }

        val category = request.optString("category", "")
        val listing = when {
            category == "transfer" -> success(id).put("entries", JSONArray()).put("total_count", 0)
            category == "apps" -> appList(id, JSONObject(request.toString()).put("operation", "list"))
            request.optBoolean("recursive") && fullStorage -> recursiveExternalList(id, request, category)
            request.optBoolean("recursive") -> recursiveList(id, root!!, request, category)
            fullStorage -> fullStorageHandle(id, "list", path, request)
            else -> list(id, root!!, path, request)
        }
        if (listing.optBoolean("success")) listing.put("roots", JSONArray().put(rootInfo))
        return listing
    }

    suspend fun stream(
        request: JSONObject,
        emit: suspend (JSONObject) -> Unit,
        emitBinary: (suspend (ByteArray) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val id = request.optString("request_id")
        try {
            require(isValidRequestId(id)) { "معرّف النقل غير صالح" }
            val requestedPath = clean(request.optString("path", ""))
            if (requestedPath.startsWith("@app/")) {
                val export = appExport(requestedPath.removePrefix("@app/"))
                var sequence = 0
                var offset = 0L
                try {
                    export.inputStream().use { input ->
                        val buffer = ByteArray(if (emitBinary == null) TRANSFER_CHUNK_SIZE else BINARY_CHUNK_SIZE)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            emitFileChunk(id, sequence++, offset, buffer, count, emit, emitBinary)
                            offset += count
                        }
                    }
                    emit(success(id).put("mode", "chunked").put("name", export.name).put("size", export.length()))
                } finally { export.delete() }
                return@withContext
            }
            if (requestedPath == "@fs" || requestedPath.startsWith("@fs/")) {
                val file = externalFile(requestedPath)
                require(file.isFile) { "المسار ليس ملفًا" }
                var sequence = 0
                var offset = 0L
                file.inputStream().buffered().use { input ->
                    val buffer = ByteArray(if (emitBinary == null) TRANSFER_CHUNK_SIZE else BINARY_CHUNK_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        emitFileChunk(id, sequence++, offset, buffer, count, emit, emitBinary)
                        offset += count
                    }
                }
                emit(success(id).put("mode", "chunked").put("name", file.name).put("size", file.length()))
                return@withContext
            }
            val root = rootUri() ?: run { emit(failure(id, "لم يتم اختيار مجلد للهاتف بعد")); return@withContext }
            val path = requestedPath
            val uri = childUri(root, docId(root, path))
            val size = resolver().query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L } ?: -1L
            var sequence = 0
            var offset = 0L
            resolver().openInputStream(uri)!!.use { input ->
                val buffer = ByteArray(if (emitBinary == null) TRANSFER_CHUNK_SIZE else BINARY_CHUNK_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    emitFileChunk(id, sequence++, offset, buffer, count, emit, emitBinary)
                    offset += count
                }
            }
            emit(success(id).put("mode", "chunked").put("name", path.substringAfterLast('/')).put("size", size))
        } catch (e: Exception) {
            Log.w(TAG, "Phone-file download failed", e)
            emit(failure(id, e.message ?: "فشل نقل الملف"))
        }
    }

    /** Stream one bounded byte range for seekable desktop video playback. */
    suspend fun streamRange(
        request: JSONObject,
        emit: suspend (JSONObject) -> Unit,
        emitBinary: (suspend (ByteArray) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        val id = request.optString("request_id")
        try {
            require(isValidRequestId(id)) { "معرّف الطلب غير صالح" }
            val path = clean(request.optString("path", ""))
            val offset = request.optLong("offset", -1)
            val requestedLength = request.optInt("length", 0)
            require(offset >= 0 && requestedLength in 1..(2 * 1024 * 1024)) { "نطاق الفيديو غير صالح" }

            var totalSize: Long
            var input: InputStream
            var alreadyPositioned = false
            when {
                path == "@fs" || path.startsWith("@fs/") -> {
                    val file = externalFile(path)
                    require(file.isFile) { "المسار ليس ملفًا" }
                    totalSize = file.length()
                    val fileInput = FileInputStream(file)
                    fileInput.channel.position(offset)
                    input = fileInput
                    alreadyPositioned = true
                }
                path.startsWith("@app/") -> {
                    val file = appExport(path.removePrefix("@app/"))
                    totalSize = file.length()
                    val fileInput = FileInputStream(file)
                    fileInput.channel.position(offset)
                    input = fileInput
                    alreadyPositioned = true
                }
                else -> {
                    val root = rootUri() ?: throw IllegalStateException("لم يتم اختيار مجلد للهاتف بعد")
                    val uri = childUri(root, docId(root, path))
                    totalSize = resolver().query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
                        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L } ?: -1L
                    val descriptor = resolver().openFileDescriptor(uri, "r")
                    if (descriptor != null) {
                        val fileInput = FileInputStream(descriptor.fileDescriptor)
                        try {
                            fileInput.channel.position(offset)
                            input = object : FilterInputStream(fileInput) {
                                override fun close() {
                                    try { super.close() } finally { descriptor.close() }
                                }
                            }
                            alreadyPositioned = true
                        } catch (_: java.io.IOException) {
                            fileInput.close()
                            descriptor.close()
                            input = resolver().openInputStream(uri)
                                ?: throw IllegalStateException("تعذرت قراءة ملف الهاتف")
                        }
                    } else {
                        input = resolver().openInputStream(uri)
                            ?: throw IllegalStateException("تعذرت قراءة ملف الهاتف")
                    }
                }
            }
            require(totalSize >= 0 && offset < totalSize) { "موضع القراءة خارج الملف" }
            val rangeLength = minOf(requestedLength.toLong(), totalSize - offset).toInt()
            input.use { stream ->
                if (!alreadyPositioned) {
                    var skipped = 0L
                    while (skipped < offset) {
                        val count = stream.skip(offset - skipped)
                        if (count > 0) skipped += count
                        else {
                            if (stream.read() < 0) throw IllegalStateException("تعذر الوصول لموضع القراءة المطلوب")
                            skipped++
                        }
                    }
                }
                var remaining = rangeLength
                var sequence = 0
                val buffer = ByteArray(256 * 1024)
                var position = offset
                while (remaining > 0) {
                    val count = stream.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) break
                    if (count == 0) continue
                    emitFileChunk(id, sequence++, position, buffer, count, emit, emitBinary)
                    position += count
                    remaining -= count
                }
                emit(success(id).put("mode", "range").put("size", totalSize)
                    .put("bytes_sent", rangeLength - remaining))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Phone-file range read failed", e)
            emit(failure(id, e.message ?: "فشل قراءة جزء الفيديو"))
        }
    }

    private suspend fun emitFileChunk(
        requestId: String,
        sequence: Int,
        offset: Long,
        buffer: ByteArray,
        count: Int,
        emit: suspend (JSONObject) -> Unit,
        emitBinary: (suspend (ByteArray) -> Unit)?,
    ) {
        if (emitBinary != null) {
            emitBinary(encodeBinaryChunk(requestId, offset, buffer, count))
        } else {
            emit(JSONObject().put("type", "phone_fs_chunk").put("request_id", requestId)
                .put("sequence", sequence)
                .put("data", Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP)))
        }
    }

    private fun encodeBinaryChunk(requestId: String, offset: Long, buffer: ByteArray, count: Int): ByteArray {
        require(requestId.matches(Regex("[0-9a-f]{32}")) && offset >= 0 && count in 1..BINARY_CHUNK_SIZE) {
            "بيانات كتلة النقل الثنائي غير صالحة"
        }
        val frame = ByteArray(BINARY_HEADER_SIZE + count)
        BINARY_MAGIC.copyInto(frame)
        requestId.toByteArray(Charsets.US_ASCII).copyInto(frame, 4)
        ByteBuffer.wrap(frame, 36, 8).order(ByteOrder.BIG_ENDIAN).putLong(offset)
        System.arraycopy(buffer, 0, frame, BINARY_HEADER_SIZE, count)
        return frame
    }

    private fun clean(path: String): String {
        val segments = path.replace('\\', '/').trim('/').split('/')
        require(segments.none { it == ".." }) { "المسار يتجاوز مجلد الهاتف" }
        return segments.filter { it.isNotBlank() && it != "." }.joinToString("/")
    }
    private fun isValidRequestId(id: String) = id.length in 1..128 && id.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_'
    }
    private fun cleanUploadName(name: String): String {
        val normalized = name.trim()
        require(normalized.isNotEmpty() && normalized != "." && normalized != "..") { "اسم الملف غير صالح" }
        require('/' !in normalized && '\\' !in normalized && normalized.none { Character.isISOControl(it) }) { "اسم الملف غير صالح" }
        return normalized
    }
    private fun success(id: String) = JSONObject().put("type", "phone_fs_response").put("request_id", id).put("success", true)
    private fun failure(id: String, message: String) = success(id).put("success", false).put("error", message)
    private fun resolver() = context!!.contentResolver
    private fun storageStats(tree: Uri): JSONObject? {
        return runCatching {
            val c = context ?: return null
            val volumeId = DocumentsContract.getTreeDocumentId(tree).substringBefore(':')
            val volumeRoot = if (volumeId.equals("primary", ignoreCase = true)) {
                Environment.getExternalStorageDirectory()
            } else {
                val marker = "${File.separator}Android${File.separator}data${File.separator}${c.packageName}${File.separator}files"
                c.getExternalFilesDirs(null)
                    .asSequence()
                    .filterNotNull()
                    .mapNotNull { appDirectory ->
                        val path = appDirectory.absolutePath
                        val markerIndex = path.lastIndexOf(marker, ignoreCase = true)
                        if (markerIndex <= 0) null else File(path.substring(0, markerIndex))
                    }
                    .firstOrNull { it.name.equals(volumeId, ignoreCase = true) }
            } ?: return null
            if (!volumeRoot.exists() || !volumeRoot.isDirectory) return null
            val stats = StatFs(volumeRoot.absolutePath)
            val total = stats.totalBytes
            val available = stats.availableBytes
            if (total <= 0 || available < 0 || available > total) return null
            JSONObject()
                .put("volume_id", volumeId)
                .put("total_bytes", total)
                .put("free_bytes", available)
                .put("scope", "storage_volume")
        }.getOrNull()
    }

    private fun docId(root: Uri, path: String): String {
        var current = DocumentsContract.getTreeDocumentId(root)
        if (path.isBlank()) return current
        path.split('/').forEach { part ->
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, current)
            val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            var resolvedId: String? = null
            resolver().query(children, columns, "${DocumentsContract.Document.COLUMN_DISPLAY_NAME} = ?", arrayOf(part), null).use { cursor ->
                while (cursor != null && cursor.moveToNext()) {
                    if (cursor.getString(1) == part) {
                        resolvedId = cursor.getString(0)
                        break
                    }
                }
            }
            // SAF providers vary in how they implement selection arguments.
            // Recheck the child names without a filter before calling a listed path missing.
            if (resolvedId == null) {
                resolver().query(children, columns, null, null, null).use { cursor ->
                    while (cursor != null && cursor.moveToNext()) {
                        if (cursor.getString(1) == part) {
                            resolvedId = cursor.getString(0)
                            break
                        }
                    }
                }
            }
            current = resolvedId ?: throw IllegalArgumentException("المسار غير موجود")
        }
        return current
    }
    private fun childUri(root: Uri, id: String) = DocumentsContract.buildDocumentUriUsingTree(root, id)

    private fun list(id: String, root: Uri, path: String, request: JSONObject): JSONObject {
        val parent = docId(root, path)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(root, parent)
        val items = JSONArray()
        val offset = request.optInt("offset", 0).coerceAtLeast(0)
        val requestedLimit = request.optInt("limit", 0).coerceAtLeast(0)
        val limit = if (requestedLimit == 0) 0 else requestedLimit.coerceIn(1, 500)
        val search = request.optString("search", "").trim()
        val directoriesFirst = "CASE WHEN ${DocumentsContract.Document.COLUMN_MIME_TYPE} = '${DocumentsContract.Document.MIME_TYPE_DIR}' THEN 0 ELSE 1 END ASC"
        val sortOrder = when (request.optString("sort", "name")) {
            "modified" -> "$directoriesFirst, ${DocumentsContract.Document.COLUMN_LAST_MODIFIED} DESC, ${DocumentsContract.Document.COLUMN_DISPLAY_NAME} COLLATE NOCASE"
            "size" -> "$directoriesFirst, ${DocumentsContract.Document.COLUMN_SIZE} DESC, ${DocumentsContract.Document.COLUMN_DISPLAY_NAME} COLLATE NOCASE"
            else -> "$directoriesFirst, ${DocumentsContract.Document.COLUMN_DISPLAY_NAME} COLLATE NOCASE"
        }
        var matchingIndex = 0
        var hasMore = false
        resolver().query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, sortOrder).use { c ->
            while (c != null && c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (search.isNotEmpty() && !name.contains(search, ignoreCase = true)) continue
                if (matchingIndex++ < offset) continue
                if (limit > 0 && items.length() >= limit) {
                    hasMore = true
                    break
                }
                val childPath = if (path.isBlank()) name else "$path/$name"
                items.put(JSONObject().put("name", name).put("path", childPath).put("directory", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR).put("mime", c.getString(2) ?: "application/octet-stream").put("size", c.getLong(3)).put("modified", c.getLong(4)))
            }
        }
        val response = success(id).put("path", path).put("entries", items)
        if (limit > 0) {
            response.put("has_more", hasMore)
                .put("next_offset", offset + items.length())
                .put("limit", limit)
        }
        return response
    }

    private fun recursiveList(id: String, root: Uri, request: JSONObject, category: String): JSONObject {
        require(category in setOf("photos", "videos", "music", "docs")) { "تصنيف الملفات غير مدعوم" }
        val entries = catalogFor("saf:${root}") { scanAuthorizedTree(root) }
        return pageCatalog(id, request, category, entries)
    }

    private fun recursiveExternalList(id: String, request: JSONObject, category: String): JSONObject {
        require(category in setOf("photos", "videos", "music", "docs")) { "تصنيف الملفات غير مدعوم" }
        val key = "@fs:${externalRoot().path}:$category"
        val entries = catalogFor(key) { scanExternalStorage(category) }
        return pageCatalog(id, request, category, entries)
    }

    private fun catalogFor(key: String, scan: () -> List<CatalogEntry>): List<CatalogEntry> {
        val now = System.currentTimeMillis()
        synchronized(catalogLock) {
            catalogCache[key]?.takeIf { now - it.createdAt < 5 * 60_000L }?.let { return it.entries }
        }
        val entries = scan()
        synchronized(catalogLock) {
            if (catalogCache.size >= 5 && key !in catalogCache) {
                val oldest = catalogCache.minByOrNull { it.value.createdAt }?.key
                if (oldest != null) catalogCache.remove(oldest)
            }
            catalogCache[key] = CatalogSnapshot(now, entries)
        }
        return entries
    }

    private fun pageCatalog(id: String, request: JSONObject, category: String, entries: List<CatalogEntry>): JSONObject {
        val query = request.optString("search", "").trim()
        val matches = entries.asSequence()
            .filter { entry -> entry.name.contains(query, ignoreCase = true) }
            .filter { entry -> when (category) {
                "photos" -> catalogMime(entry).startsWith("image/")
                "videos" -> catalogMime(entry).startsWith("video/")
                "music" -> catalogMime(entry).startsWith("audio/")
                else -> !catalogMime(entry).startsWith("image/") && !catalogMime(entry).startsWith("video/") && !catalogMime(entry).startsWith("audio/")
            } }
            .let { sequence -> when (request.optString("sort", "name")) {
                "modified" -> sequence.sortedWith(compareByDescending<CatalogEntry> { it.modified }.thenBy { it.name.lowercase() })
                "size" -> sequence.sortedWith(compareByDescending<CatalogEntry> { it.size }.thenBy { it.name.lowercase() })
                else -> sequence.sortedBy { it.name.lowercase() }
            } }.toList()
        val offset = request.optInt("offset", 0).coerceAtLeast(0).coerceAtMost(matches.size)
        val limit = request.optInt("limit", 0).coerceIn(0, 500)
        val end = if (limit == 0) matches.size else (offset + limit).coerceAtMost(matches.size)
        val items = JSONArray()
        for (entry in matches.subList(offset, end)) {
            val mime = catalogMime(entry)
            val item = JSONObject().put("name", entry.name).put("path", entry.path).put("directory", false)
                .put("mime", mime).put("size", entry.size).put("modified", entry.modified)
                .put("format", entry.name.substringAfterLast('.', "").uppercase(Locale.ROOT))
            if (mime.startsWith("audio/") || mime.startsWith("video/")) {
                entry.duration?.let { item.put("duration", it) }
                entry.artist?.let { item.put("artists", it) }
                entry.title?.let { item.put("title", it) }
                // SAF video metadata extraction can take seconds per file.
                // Keep the catalogue response quick; visible video thumbnails
                // populate duration lazily instead.
                val needsFallback = !mime.startsWith("video/") &&
                    (entry.duration == null || mime.startsWith("audio/") && (entry.artist == null || entry.title == null))
                if (needsFallback) mediaMetadata(entry.path)?.let { metadata ->
                    if (entry.duration == null) metadata.duration?.let { item.put("duration", it) }
                    if (entry.artist == null) metadata.artist?.let { item.put("artists", it) }
                    if (entry.title == null) metadata.title?.let { item.put("title", it) }
                }
            }
            items.put(item)
        }
        return success(id).put("path", "").put("entries", items).put("total_count", matches.size)
            .put("has_more", end < matches.size).put("next_offset", end).put("limit", limit)
    }

    private fun catalogMime(entry: CatalogEntry): String {
        val declared = entry.mime.lowercase(Locale.ROOT)
        if (declared.startsWith("image/") || declared.startsWith("video/") || declared.startsWith("audio/")) return declared
        val inferred = mimeFor(entry.name)
        return if (inferred.startsWith("image/") || inferred.startsWith("video/") || inferred.startsWith("audio/")) inferred else declared
    }

    private fun scanExternalStorage(category: String): List<CatalogEntry> {
        scanExternalMedia(category)?.let { return it }
        val root = externalRoot()
        return root.walkTopDown().maxDepth(256).filter { file -> file.isFile && (category == "docs" || matchesCategory(mimeFor(file.name), category)) }
            .map { file ->
                val extension = file.extension.lowercase(Locale.ROOT)
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
                CatalogEntry(file.name, externalPath(file), mime, file.length(), file.lastModified())
            }.toList()
    }

    private fun scanExternalMedia(category: String): List<CatalogEntry>? {
        val c = context ?: return null
        val root = externalRoot()
        val collection = when (category) {
            "photos" -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            "videos" -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            "music" -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> return null
        }
        val extraColumns: Array<String> = when (category) {
            "photos" -> emptyArray()
            "videos" -> arrayOf(MediaStore.Video.Media.DURATION)
            "music" -> arrayOf(
                MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.TITLE,
            )
            else -> return null
        }
        val columns = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
        ) + extraColumns
        val cursor = c.contentResolver.query(collection, columns, null, null, null) ?: return null
        val rootPrefix = root.path + File.separator
        return cursor.use { rows ->
            val pathColumn = rows.getColumnIndex(MediaStore.MediaColumns.DATA)
            val nameColumn = rows.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = rows.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = rows.getColumnIndex(MediaStore.MediaColumns.SIZE)
            val modifiedColumn = rows.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
            val durationColumn = rows.getColumnIndex(MediaStore.MediaColumns.DURATION)
            val artistColumn = rows.getColumnIndex(MediaStore.Audio.AudioColumns.ARTIST)
            val titleColumn = rows.getColumnIndex(MediaStore.Audio.AudioColumns.TITLE)
            val entries = ArrayList<CatalogEntry>(rows.count)
            while (rows.moveToNext()) {
                val rawPath = if (pathColumn >= 0) rows.getString(pathColumn) else null
                if (rawPath.isNullOrBlank()) continue
                val file = File(rawPath).canonicalFile
                if (!file.isFile || !file.path.startsWith(rootPrefix)) continue
                val name: String = (if (nameColumn >= 0) rows.getString(nameColumn) else null)
                    ?.takeIf { it.isNotBlank() } ?: file.name
                val mime: String = (if (mimeColumn >= 0) rows.getString(mimeColumn) else null)
                    ?.takeIf { it.isNotBlank() } ?: mimeFor(name)
                val modifiedSeconds = if (modifiedColumn >= 0) rows.getLong(modifiedColumn) else 0L
                val size = if (sizeColumn >= 0) rows.getLong(sizeColumn).takeIf { it >= 0 } else null
                val duration = if (durationColumn >= 0) rows.getLong(durationColumn).takeIf { it > 0 } else null
                val artist = if (artistColumn >= 0) rows.getString(artistColumn)?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) } else null
                val title = if (titleColumn >= 0) rows.getString(titleColumn)?.takeIf { it.isNotBlank() && !it.equals("<unknown>", true) } else null
                entries.add(CatalogEntry(name, externalPath(file), mime, size ?: file.length(),
                    if (modifiedSeconds > 0) modifiedSeconds * 1000 else file.lastModified(), duration, artist, title))
            }
            entries
        }
    }

    private fun matchesCategory(mime: String, category: String): Boolean = when (category) {
        "photos" -> mime.startsWith("image/")
        "videos" -> mime.startsWith("video/")
        "music" -> mime.startsWith("audio/")
        else -> !mime.startsWith("image/") && !mime.startsWith("video/") && !mime.startsWith("audio/")
    }

    private data class MediaMetadata(val duration: Long?, val artist: String?, val title: String?)

    private fun mediaMetadata(path: String): MediaMetadata? {
        val now = System.currentTimeMillis()
        synchronized(catalogLock) {
            mediaMetadataCache[path]?.takeIf { now - it.first < 5 * 60_000L }?.let { return it.second }
        }
        val retriever = MediaMetadataRetriever()
        val metadata = try {
            if (path == "@fs" || path.startsWith("@fs/")) retriever.setDataSource(externalFile(path).absolutePath)
            else {
                val root = rootUri() ?: return null
                retriever.setDataSource(context, childUri(root, docId(root, path)))
            }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) }
            MediaMetadata(duration, artist, title)
        } catch (_: Exception) { null }
        finally { retriever.release() }
        synchronized(catalogLock) {
            if (mediaMetadataCache.size >= 512) mediaMetadataCache.clear()
            mediaMetadataCache[path] = now to metadata
        }
        return metadata
    }

    private fun fullStorageHandle(id: String, operation: String, path: String, request: JSONObject): JSONObject {
        val file = externalFile(path)
        return when (operation) {
            "list" -> externalDirectoryList(id, file, path, request)
            "stat" -> JSONObject().put("type", "phone_fs_response").put("request_id", id).put("success", true)
                .put("path", path).put("name", file.name.ifBlank { "Internal storage" }).put("directory", file.isDirectory)
                .put("size", if (file.isFile) file.length() else 0L).put("mime", if (file.isDirectory) "vnd.android.document/directory" else mimeFor(file.name))
            "thumbnail" -> externalThumbnail(id, file)
            "open" -> externalOpen(id, file)
            "rename" -> {
                val name = request.optString("new_name").trim()
                require(name.isNotBlank() && name !in setOf(".", "..") && !name.contains('/') && !name.contains('\\')) { "اسم غير صالح" }
                val target = File(file.parentFile, name)
                require(!target.exists() && file.renameTo(target)) { "تعذرت إعادة التسمية" }
                invalidateCatalog()
                success(id).put("path", externalPath(target)).put("event", "changed")
            }
            "delete" -> externalTrash(id, file, path)
            "copy", "move" -> externalCopyOrMove(id, file, externalFile(clean(request.optString("destination"))), path, operation == "move")
            "roots" -> success(id).put("roots", JSONArray().put(JSONObject().put("path", "@fs").put("name", "Internal storage")))
            "trash_list" -> trashList(id)
            "restore" -> restore(id, request.optString("trash_id"))
            "trash_delete" -> permanentDelete(id, request.optString("trash_id"))
            else -> failure(id, "عملية غير مدعومة: $operation")
        }
    }

    private fun externalDirectoryList(id: String, directory: File, path: String, request: JSONObject): JSONObject {
        require(directory.isDirectory) { "المسار ليس مجلدًا" }
        val search = request.optString("search", "").trim()
        val children = directory.listFiles().orEmpty().filter { it.name.contains(search, ignoreCase = true) }
        val sorted = when (request.optString("sort", "name")) {
            "modified" -> children.sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.lastModified() }.thenBy { it.name.lowercase() })
            "size" -> children.sortedWith(compareByDescending<File> { it.isDirectory }.thenByDescending { it.length() }.thenBy { it.name.lowercase() })
            else -> children.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
        val offset = request.optInt("offset", 0).coerceAtLeast(0).coerceAtMost(sorted.size)
        val limit = request.optInt("limit", 0).coerceIn(0, 500)
        val end = if (limit == 0) sorted.size else (offset + limit).coerceAtMost(sorted.size)
        val items = JSONArray()
        for (child in sorted.subList(offset, end)) items.put(JSONObject()
            .put("name", child.name).put("path", externalPath(child)).put("directory", child.isDirectory)
            .put("mime", if (child.isDirectory) "vnd.android.document/directory" else mimeFor(child.name))
            .put("size", if (child.isFile) child.length() else 0L).put("modified", child.lastModified()))
        return success(id).put("path", path).put("entries", items).put("has_more", end < sorted.size)
            .put("next_offset", end).put("limit", limit)
    }

    private fun mimeFor(name: String): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT)) ?: "application/octet-stream"

    private fun externalThumbnail(id: String, file: File): JSONObject {
        require(file.isFile) { "الملف غير موجود" }
        val mime = mimeFor(file.name)
        require(mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) { "المعاينة غير متاحة لهذا النوع" }
        var durationMillis: Long? = null
        val bitmap = when {
            mime.startsWith("video/") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    scaledVideoFrame(retriever)
                } finally { retriever.release() }
            }
            mime.startsWith("audio/") -> {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(file.absolutePath)
                    embeddedArtwork(retriever)
                } finally { retriever.release() }
            }
            else -> {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 360 || bounds.outHeight / sample > 360) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            })
            }
        }
            ?: throw IllegalStateException("تعذر تجهيز معاينة الملف")
        return thumbnailResponse(id, bitmap, durationMillis)
    }

    private fun externalOpen(id: String, file: File): JSONObject {
        require(file.isFile) { "الملف غير موجود" }
        if (file.length() > 8L * 1024L * 1024L)
            return success(id).put("mode", "chunked_required").put("size", file.length()).put("name", file.name)
        val bytes = file.readBytes()
        return success(id).put("mode", "temporary").put("name", file.name).put("size", bytes.size)
            .put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun scaledVideoFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            runCatching {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
                    ?: retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 360, 360)
            }.getOrNull()
        } else null
        return fitThumbnail(frame
            ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC))
    }

    private fun embeddedArtwork(retriever: MediaMetadataRetriever): Bitmap? {
        val bytes = retriever.embeddedPicture ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 360 || bounds.outHeight / sample > 360) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    private fun fitThumbnail(bitmap: Bitmap?): Bitmap? {
        bitmap ?: return null
        if (bitmap.width <= 360 && bitmap.height <= 360) return bitmap
        val scale = minOf(360f / bitmap.width, 360f / bitmap.height)
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun thumbnailResponse(id: String, bitmap: Bitmap, durationMillis: Long? = null): JSONObject {
        val bytes = ByteArrayOutputStream().use { output ->
            try { bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output); output.toByteArray() }
            finally { bitmap.recycle() }
        }
        val response = success(id).put("mime", "image/jpeg").put("thumbnail_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        durationMillis?.takeIf { it > 0 }?.let { response.put("duration", it) }
        return response
    }

    private fun externalTrash(id: String, file: File, path: String): JSONObject {
        require(file != externalRoot() && file.exists()) { "لا يمكن حذف جذر التخزين" }
        val source = File(context!!.filesDir, "phone_trash/${UUID.randomUUID()}")
        if (file.isDirectory) file.copyRecursively(source, overwrite = false)
        else file.copyTo(source, overwrite = false)
        if (!file.deleteRecursively()) { source.deleteRecursively(); throw IllegalStateException("تعذر نقل الملف إلى السلة") }
        val trashId = source.name
        context!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("trash_$trashId", path).apply()
        invalidateCatalog()
        return success(id).put("trash_id", trashId).put("event", "deleted")
    }

    private fun externalCopyOrMove(id: String, source: File, destination: File, path: String, move: Boolean): JSONObject {
        require(source != externalRoot() && source.exists() && destination.isDirectory) { "مسار النسخ غير صالح" }
        require(!(source.isDirectory && destination.canonicalPath.startsWith(source.canonicalPath + File.separator))) { "لا يمكن نسخ مجلد داخل نفسه" }
        if (move && source.parentFile == destination) return success(id).put("path", path).put("event", "changed")
        val target = uniqueFile(destination, source.name)
        if (move && source.renameTo(target)) {
            invalidateCatalog()
            return success(id).put("path", externalPath(target)).put("event", "changed")
        }
        if (source.isDirectory) source.copyRecursively(target, overwrite = false) else source.copyTo(target, overwrite = false)
        if (move && !source.deleteRecursively()) { target.deleteRecursively(); throw IllegalStateException("تعذر نقل الملف") }
        invalidateCatalog()
        return success(id).put("path", externalPath(target)).put("event", "changed")
    }

    private fun uniqueFile(parent: File, originalName: String): File {
        val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: originalName.length
        val stem = originalName.substring(0, dot)
        val extension = originalName.substring(dot)
        var candidate = File(parent, originalName)
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(parent, if (suffix == 1) "$stem (copy)$extension" else "$stem (copy $suffix)$extension")
            suffix++
        }
        return candidate
    }

    private fun invalidateCatalog() = synchronized(catalogLock) {
        catalogCache.clear()
        appCatalogCacheAt = 0L
        mediaMetadataCache.clear()
    }

    private fun scanAuthorizedTree(root: Uri): List<CatalogEntry> {
        val result = ArrayList<CatalogEntry>()
        val visited = HashSet<String>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(DocumentsContract.getTreeDocumentId(root) to "")
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        while (queue.isNotEmpty()) {
            val (parentId, parentPath) = queue.removeFirst()
            if (!visited.add(parentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(root, parentId)
            resolver().query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1) ?: continue
                    val mime = cursor.getString(2) ?: "application/octet-stream"
                    val childPath = if (parentPath.isEmpty()) name else "$parentPath/$name"
                    val childId = cursor.getString(0) ?: continue
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) queue.addLast(childId to childPath)
                    else result.add(CatalogEntry(name, childPath, mime, cursor.getLong(3), cursor.getLong(4)))
                }
            }
        }
        return result
    }

    private fun appList(id: String, request: JSONObject): JSONObject {
        val c = context ?: throw IllegalStateException("التطبيق غير جاهز")
        val manager = c.packageManager
        val query = request.optString("search", "").trim()
        val now = System.currentTimeMillis()
        val cachedApps = synchronized(catalogLock) {
            appCatalogCache.takeIf { now - appCatalogCacheAt < 60_000L }
        }
        val allApps = cachedApps ?: manager.getInstalledApplications(0)
            .map { info ->
                val label = manager.getApplicationLabel(info).toString()
                JSONObject().put("name", "$label.apks").put("label", label).put("package_name", info.packageName)
                    .put("path", "@app/${info.packageName}")
                    .put("directory", false).put("category", "apps").put("mime", "application/vnd.android.package-archive")
            }.also { scanned ->
                synchronized(catalogLock) {
                    appCatalogCacheAt = now
                    appCatalogCache = scanned
                }
            }
        val apps = allApps
            .filter { it.optString("label").contains(query, ignoreCase = true) || it.optString("package_name").contains(query, ignoreCase = true) }
            .sortedBy { it.optString("label").lowercase() }
        val offset = request.optInt("offset", 0).coerceAtLeast(0).coerceAtMost(apps.size)
        val limit = request.optInt("limit", 0).coerceIn(0, 500)
        val end = if (limit == 0) apps.size else (offset + limit).coerceAtMost(apps.size)
        val page = apps.subList(offset, end)
        page.forEach { app ->
            if (app.has("size")) return@forEach
            val packageName = app.optString("package_name")
            val info = runCatching { appInfo(packageName) }.getOrNull() ?: return@forEach
            val size = (listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty())
                .sumOf { apk -> runCatching { File(apk).length() }.getOrDefault(0L) }
            val packageInfo = runCatching {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(packageName, 0)
            }.getOrNull()
            app.put("size", size).put("version", packageInfo?.versionName ?: "")
                .put("modified", packageInfo?.lastUpdateTime ?: 0L)
        }
        return success(id).put("path", "").put("entries", JSONArray(page))
            .put("total_count", apps.size).put("has_more", end < apps.size).put("next_offset", end).put("limit", limit)
    }

    private fun appInfo(packageName: String): android.content.pm.ApplicationInfo {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+"))) { "معرّف التطبيق غير صالح" }
        val manager = context!!.packageManager
        return manager.getApplicationInfo(packageName, 0)
    }

    private fun appStat(id: String, packageName: String): JSONObject {
        val manager = context!!.packageManager
        val info = appInfo(packageName)
        val name = manager.getApplicationLabel(info).toString()
        val size = (listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()).sumOf { path -> File(path).length() }
        return success(id).put("path", "@app/$packageName").put("name", "$name.apks").put("directory", false)
            .put("size", size).put("mime", "application/vnd.android.package-archive")
    }

    private fun appThumbnail(id: String, packageName: String): JSONObject {
        val c = context!!
        val drawable: Drawable = c.packageManager.getApplicationIcon(appInfo(packageName))
        val width = drawable.intrinsicWidth.coerceIn(1, 256)
        val height = drawable.intrinsicHeight.coerceIn(1, 256)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().use { output ->
            try {
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                output.toByteArray()
            } finally { bitmap.recycle() }
        }
        return success(id).put("mime", "image/png").put("thumbnail_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun appExport(packageName: String): File {
        val c = context!!
        val info = appInfo(packageName)
        val label = c.packageManager.getApplicationLabel(info).toString()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80).ifBlank { packageName }
        val export = File(c.cacheDir, "phone_app_${UUID.randomUUID()}.apks")
        val apkPaths = listOfNotNull(info.sourceDir) + info.splitSourceDirs.orEmpty()
        require(apkPaths.isNotEmpty() && apkPaths.all { File(it).isFile }) { "ملفات تثبيت التطبيق غير مكتملة" }
        ZipOutputStream(export.outputStream().buffered()).use { zip ->
            apkPaths.forEachIndexed { index, apkPath ->
                val entryName = if (index == 0) "$packageName/base.apk" else "$packageName/split_$index.apk"
                zip.putNextEntry(ZipEntry(entryName))
                File(apkPath).inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val metadata = JSONObject().put("package_name", packageName).put("label", label)
            zip.putNextEntry(ZipEntry("$packageName/metadata.json"))
            zip.write(metadata.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        export.renameTo(File(export.parentFile, "$label-$packageName.apks")).let { renamed ->
            if (renamed) return File(export.parentFile, "$label-$packageName.apks")
        }
        return export
    }
    private fun stat(id: String, root: Uri, path: String): JSONObject {
        val uri = childUri(root, docId(root, path))
        resolver().query(uri, null, null, null, null).use { c ->
            if (c == null || !c.moveToFirst()) return failure(id, "الملف غير موجود")
            val name = c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME))
            return success(id).put("path", path).put("name", name).put("directory", c.getString(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)) == DocumentsContract.Document.MIME_TYPE_DIR).put("size", c.getLong(c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)))
        }
    }
    private fun thumbnail(id: String, root: Uri, path: String): JSONObject {
        val uri = childUri(root, docId(root, path))
        val declaredMime = resolver().query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: throw IllegalArgumentException("الملف غير موجود")
        val inferredMime = mimeFor(path.substringAfterLast('/'))
        val mime = if (declaredMime == "application/octet-stream" &&
            (inferredMime.startsWith("image/") || inferredMime.startsWith("video/") || inferredMime.startsWith("audio/"))) inferredMime else declaredMime
        if (mime.startsWith("audio/")) {
            val retriever = MediaMetadataRetriever()
            val artwork = try {
                retriever.setDataSource(context, uri)
                embeddedArtwork(retriever)
            } finally { retriever.release() }
            return thumbnailResponse(id, artwork ?: throw IllegalStateException("المقطع الصوتي لا يحتوي على صورة غلاف"))
        }
        require(mime.startsWith("image/") || mime.startsWith("video/")) { "المعاينة متاحة للصور والفيديو والصوت ذي الغلاف فقط" }
        var durationMillis: Long? = null
        val resolvedBitmap = if (mime.startsWith("video/")) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    runCatching { resolver().loadThumbnail(uri, Size(360, 360), null) }.getOrNull()
                        ?: scaledVideoFrame(retriever)
                else scaledVideoFrame(retriever)
            } finally { retriever.release() }
        } else {
            val thumbnail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                runCatching { resolver().loadThumbnail(uri, Size(360, 360), null) }.getOrNull()
            else null
            thumbnail ?: run {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver().openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                require(bounds.outWidth > 0 && bounds.outHeight > 0) { "تعذر قراءة أبعاد الصورة" }
                var sample = 1
                while (bounds.outWidth / sample > 360 || bounds.outHeight / sample > 360) sample *= 2
                resolver().openInputStream(uri)?.use { input -> BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.RGB_565
                }) }
            }
        } ?: throw IllegalStateException("مزود الملفات لم يُرجع معاينة")
        val bytes = ByteArrayOutputStream().use { output ->
            try {
                resolvedBitmap.compress(Bitmap.CompressFormat.JPEG, 68, output)
                output.toByteArray()
            } finally {
                resolvedBitmap.recycle()
            }
        }
        val response = success(id).put("mime", "image/jpeg").put("thumbnail_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
        durationMillis?.takeIf { it > 0 }?.let { response.put("duration", it) }
        return response
    }
    private fun rename(id: String, root: Uri, path: String, name: String): JSONObject {
        require(name.isNotBlank() && !name.contains('/')) { "اسم غير صالح" }
        val out = DocumentsContract.renameDocument(resolver(), childUri(root, docId(root, path)), name) ?: throw IllegalStateException("تعذرت إعادة التسمية")
        invalidateCatalog()
        return success(id).put("path", path.substringBeforeLast('/', "") + if (path.contains('/')) "/$name" else name).put("event", "changed")
    }
    private fun trash(id: String, root: Uri, path: String): JSONObject {
        val source = childUri(root, docId(root, path)); val c = context!!
        val sourceType = resolver().query(source, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        if (sourceType == DocumentsContract.Document.MIME_TYPE_DIR) throw IllegalArgumentException("حذف المجلدات من المرآة غير مدعوم حاليًا")
        val trash = File(c.filesDir, "phone_trash").apply { mkdirs() }
        val trashId = UUID.randomUUID().toString(); val target = File(trash, trashId)
        resolver().openInputStream(source)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
        if (!DocumentsContract.deleteDocument(resolver(), source)) throw IllegalStateException("تعذر نقل الملف إلى سلة الهاتف")
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("trash_$trashId", path).apply()
        invalidateCatalog()
        return success(id).put("trash_id", trashId).put("event", "deleted")
    }
    private fun trashList(id: String): JSONObject {
        val c = context!!; val prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); val items = JSONArray()
        prefs.all.keys.filter { it.startsWith("trash_") }.forEach { key -> items.put(JSONObject().put("trash_id", key.removePrefix("trash_")).put("path", prefs.getString(key, ""))) }
        return success(id).put("items", items)
    }
    private fun permanentDelete(id: String, trashId: String): JSONObject {
        val c = context!!; File(c.filesDir, "phone_trash/$trashId").delete()
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("trash_$trashId").apply()
        return success(id).put("event", "trash_deleted")
    }
    private fun restore(id: String, trashId: String): JSONObject {
        val c = context!!; val path = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("trash_$trashId", null) ?: return failure(id, "عنصر السلة غير موجود")
        if (path == "@fs" || path.startsWith("@fs/")) {
            if (!hasFullStorageAccess()) return failure(id, "يجب السماح لـ DeskTwin بالوصول إلى كل ملفات الهاتف")
            val destination = externalFile(path)
            val trash = File(c.filesDir, "phone_trash/$trashId")
            val target = uniqueFile(destination, path.substringAfterLast('/').ifBlank { "Restored file" })
            if (trash.isDirectory) trash.copyRecursively(target, overwrite = false) else trash.copyTo(target, overwrite = false)
            trash.deleteRecursively()
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("trash_$trashId").apply()
            invalidateCatalog()
            return success(id).put("event", "restored").put("path", externalPath(target))
        }
        val root = rootUri() ?: return failure(id, "المجلد غير متاح")
        val parent = docId(root, path.substringBeforeLast('/', "")); val name = path.substringAfterLast('/')
        val uri = DocumentsContract.createDocument(resolver(), childUri(root, parent), "application/octet-stream", name) ?: return failure(id, "تعذر إنشاء الملف")
        File(c.filesDir, "phone_trash/$trashId").inputStream().use { input -> resolver().openOutputStream(uri)!!.use { input.copyTo(it) } }
        File(c.filesDir, "phone_trash/$trashId").delete(); c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("trash_$trashId").apply()
        invalidateCatalog()
        return success(id).put("event", "restored")
    }
    private fun copyOrMove(id: String, root: Uri, path: String, destination: String, move: Boolean): JSONObject {
        require(path.isNotBlank()) { "לא يمكن نسخ المجلد الرئيسي" }
        val source = childUri(root, docId(root, path))
        val parentId = docId(root, destination)
        val parent = childUri(root, parentId)
        val name = path.substringAfterLast('/')
        val sourceType = resolver().query(
            source,
            arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: throw IllegalArgumentException("العنصر غير موجود")
        val isDirectory = sourceType == DocumentsContract.Document.MIME_TYPE_DIR

        if (isDirectory && destination.startsWith("$path/", ignoreCase = true)) {
            throw IllegalArgumentException("لا يمكن نسخ مجلد داخل نفسه أو داخل أحد مجلداته")
        }
        if (move) {
            if (path.substringBeforeLast('/', "") == destination) {
                return success(id).put("path", path).put("event", "changed")
            }
            val sourceParent = childUri(root, docId(root, path.substringBeforeLast('/', "")))
            val moved = DocumentsContract.moveDocument(resolver(), source, sourceParent, parent)
                ?: throw IllegalStateException("مزود ملفات الهاتف لا يدعم نقل هذا العنصر")
            val movedName = resolver().query(
                moved, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else name } ?: name
            val movedPath = if (destination.isBlank()) movedName else "$destination/$movedName"
            invalidateCatalog()
            return success(id).put("path", movedPath).put("event", "changed")
        }

        val targetName = uniqueCopyName(root, parent, name)
        copyDocumentTree(root, path, parent, targetName)
        val copiedPath = if (destination.isBlank()) targetName else "$destination/$targetName"
        invalidateCatalog()
        return success(id).put("path", copiedPath).put("event", "changed")
    }

    private fun uniqueCopyName(root: Uri, parent: Uri, originalName: String): String {
        val existing = mutableSetOf<String>()
        resolver().query(
            DocumentsContract.buildChildDocumentsUriUsingTree(root, DocumentsContract.getDocumentId(parent)),
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) cursor.getString(0)?.let(existing::add)
        }
        fun exists(candidate: String) = existing.any { it.equals(candidate, ignoreCase = true) }
        if (!exists(originalName)) return originalName

        val dot = originalName.lastIndexOf('.').takeIf { it > 0 } ?: originalName.length
        val stem = originalName.substring(0, dot)
        val extension = originalName.substring(dot)
        var suffix = 1
        var candidate: String
        do {
            candidate = if (suffix == 1) "$stem (copy)$extension" else "$stem (copy $suffix)$extension"
            suffix++
        } while (exists(candidate))
        return candidate
    }

    private fun copyDocumentTree(root: Uri, sourcePath: String, destinationParent: Uri, targetName: String): Uri {
        val source = childUri(root, docId(root, sourcePath))
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val (mimeType, displayName) = resolver().query(source, columns, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) throw IllegalArgumentException("العنصر غير موجود: $sourcePath")
            (cursor.getString(0) ?: "application/octet-stream") to (cursor.getString(1) ?: targetName)
        } ?: throw IllegalArgumentException("تعذر قراءة العنصر: $sourcePath")

        val output = DocumentsContract.createDocument(resolver(), destinationParent, mimeType, targetName)
            ?: throw IllegalStateException("تعذر إنشاء النسخة: $targetName")
        if (mimeType != DocumentsContract.Document.MIME_TYPE_DIR) {
            try {
                resolver().openInputStream(source)?.use { input ->
                    resolver().openOutputStream(output)?.use { out -> input.copyTo(out) }
                        ?: throw IllegalStateException("تعذر الكتابة إلى: $targetName")
                } ?: throw IllegalStateException("تعذر قراءة: $sourcePath")
            } catch (error: Exception) {
                runCatching { DocumentsContract.deleteDocument(resolver(), output) }
                throw error
            }
            return output
        }

        val outputId = DocumentsContract.getDocumentId(output)
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, docId(root, sourcePath))
            resolver().query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childName = cursor.getString(0) ?: continue
                    copyDocumentTree(root, "$sourcePath/$childName", childUri(root, outputId), childName)
                }
            } ?: throw IllegalStateException("تعذر قراءة محتويات المجلد: $displayName")
        } catch (error: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver(), output) }
            throw error
        }
        return output
    }
    private fun open(id: String, root: Uri, path: String): JSONObject {
        val uri = childUri(root, docId(root, path)); val c = context!!; val cache = File(c.cacheDir, "phone_open").apply { mkdirs() }; val file = File(cache, path.substringAfterLast('/'))
        val size = resolver().query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L } ?: -1L
        if (size > 8L * 1024L * 1024L) return success(id).put("mode", "chunked_required").put("size", size).put("name", path.substringAfterLast('/'))
        resolver().openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val bytes = file.readBytes()
        return success(id).put("mode", "temporary").put("name", file.name).put("size", bytes.size).put("content_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }
}
