package com.desktwin.app.network

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream

/**
 * بيدير عملية رفع ملف اختاره المستخدم من تخزين التليفون (عبر Storage Access
 * Framework - SAF) إلى الكمبيوتر. السيناريو الكامل (بند: "لقيت ملف مهم
 * وأنا برة، حطيته في DeskTwin"):
 *
 *   1. المستخدم يختار ملف من تخزين التليفون (عبر ActivityResultContracts.OpenDocument)
 *   2. الملف يتحفظ مؤقتًا في cache التطبيق (لازم نسخة محلية قبل الرفع)
 *   3. لما الكمبيوتر يبقى متاح على الشبكة، الرفع يحصل تلقائيًا من غير تدخل يدوي
 *   4. بعد نجاح الرفع، النسخة المؤقتة بتتمسح والملف الأصلي (لو المستخدم
 *      اختار "انقله" مش "خليه") بيتمسح من التليفون برضه
 */
/**
 * نتيجة محاولة الرفع - بتفرّق بوضوح بين النجاح الفوري وبين الحالة
 * اللي محتاجة موافقة الكمبيوتر (ملف بنفس الاسم موجود واختار المستخدم Replace).
 */
sealed class UploadOutcome {
    data class Success(val response: UploadResponse) : UploadOutcome()
    data class PendingApproval(val approvalId: Int, val destination: String) : UploadOutcome()
    data class Failed(val message: String) : UploadOutcome()
}

object UploadManager {

    /**
     * بياخد Uri (من الـ picker) وبيرجع ملف مؤقت حقيقي على القرص -
     * الـ multipart upload محتاج File حقيقي مش Uri مباشرة.
     */
    fun copyUriToCacheFile(context: Context, uri: Uri): File? {
        val fileName = getFileName(context, uri) ?: ("upload_" + System.currentTimeMillis())
        val cacheFile = File(context.cacheDir, fileName)

        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }
            cacheFile
        } catch (e: Exception) {
            null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && nameIndex >= 0) {
                name = cursor.getString(nameIndex)
            }
        }
        return name
    }

    /**
     * الخطوة الكاملة زي ما اتفقنا: التليفون بيسأل الكمبيوتر الأول "الاسم ده
     * موجود؟" قبل ما يبعت أي bytes. لو duplicate، الكمبيوتر بيرجع اسم بديل
     * جاهز فورًا. لو replace وفيه تعارض، الكمبيوتر بيرجع approval_id والرفع
     * الفعلي بيستنى موافقة صريحة من واجهة الكمبيوتر قبل ما يكمل.
     */
    suspend fun uploadToComputer(
        api: DeskTwinApi,
        token: String,
        file: File,
        targetFolder: String,
        collisionMode: String = "duplicate",
        approvalId: Int? = null,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): UploadOutcome {
        return try {
            // لو approvalId مبعوت يبقى إحنا في محاولة ثانية بعد موافقة سابقة -
            // نتخطى prepare ونروح للرفع الفعلي على طول.
            if (approvalId == null) {
                val prepareResponse = api.prepareUpload(
                    token, UploadPreparationRequest(targetFolder, file.name, collisionMode)
                )
                val prepareBody = prepareResponse.body()
                if (!prepareResponse.isSuccessful || prepareBody == null) {
                    return UploadOutcome.Failed("فشل التحضير للرفع: " + prepareResponse.code())
                }
                if (prepareBody.status == "pending_approval" && prepareBody.approval_id != null) {
                    return UploadOutcome.PendingApproval(prepareBody.approval_id, prepareBody.destination)
                }
            }

            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            val requestBody = ProgressRequestBody(file, mediaType, onProgress)
            val part = MultipartBody.Part.createFormData("file", file.name, requestBody)

            val response = api.uploadFile(token, targetFolder, collisionMode, approvalId, part)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                UploadOutcome.Success(body)
            } else if (response.code() == 409) {
                UploadOutcome.Failed("محتاج موافقة الكمبيوتر على الاستبدال")
            } else {
                UploadOutcome.Failed("فشل الرفع: " + response.code())
            }
        } catch (e: Exception) {
            UploadOutcome.Failed(e.message ?: "خطأ غير معروف")
        }
    }

    /** بعد ما الموافقة توصل approved من الكمبيوتر، نعيد محاولة الرفع بنفس approvalId. */
    suspend fun retryUploadAfterApproval(
        api: DeskTwinApi,
        token: String,
        file: File,
        targetFolder: String,
        approvalId: Int,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): UploadOutcome {
        return uploadToComputer(api, token, file, targetFolder, "replace", approvalId, onProgress)
    }

    /**
     * رفع الملفات الكبيرة (≥1GB) القابل للاستكمال - بسيط عن قصد، مفيش
     * نظام تورنت حقيقي ولا تقسيم لأجزاء منفصلة بيتلحقوا بعدين:
     *
     *   1. نحسب بصمة أول 4MB من الملف (FileFingerprint.compute)
     *   2. نسأل الكمبيوتر "عندك من الملف ده قد إيه؟" (negotiate)
     *   3. نبعت بس الجزء الناقص - من نقطة الاستئناف لحد آخر الملف -
     *      في نداء واحد (مش لفة أجزاء صغيرة)، عن طريق RandomAccessFile
     *      عشان نقرا بدءًا من نقطة معينة جوه نفس الملف من غير ما ننسخه
     *   4. لو الرفع فشل في النص (الاتصال اتقطع)، الملف الناقص فضل على
     *      الكمبيوتر زي ما هو - المرة الجاية negotiate هيرجّع نفس نقطة
     *      الاستئناف (أو أبعد لو جزء إضافي وصل قبل الانقطاع)
     *   5. لما آخر بايت يوصل، نأكد بـ /complete
     */
    suspend fun uploadLargeFileResumable(
        api: DeskTwinApi,
        token: String,
        file: File,
        targetFolder: String,
        onProgress: (bytesSent: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): UploadOutcome {
        return try {
            val totalSize = file.length()
            val fingerprint = FileFingerprint.compute(file)

            val negotiateResponse = api.negotiateResumableUpload(
                token, NegotiateResumableRequest(targetFolder, file.name, totalSize, fingerprint)
            )
            val negotiateBody = negotiateResponse.body()
            if (!negotiateResponse.isSuccessful || negotiateBody == null) {
                return UploadOutcome.Failed("فشل التفاوض على الرفع: " + negotiateResponse.code())
            }

            val destination = negotiateBody.destination
            val resumeOffset = negotiateBody.resume_offset

            if (resumeOffset >= totalSize) {
                // نادر لكن ممكن: الملف كان مكتمل فعلاً على الكمبيوتر (مثلًا
                // اتكتب كامل قبل كده لكن /complete ماتنادتش) - نأكد ونخلص.
                val completeResponse = api.completeResumableUpload(token, destination)
                val completeBody = completeResponse.body()
                return if (completeResponse.isSuccessful && completeBody != null) {
                    onProgress(totalSize, totalSize)
                    UploadOutcome.Success(completeBody)
                } else {
                    UploadOutcome.Failed("فشل تأكيد اكتمال الملف: " + completeResponse.code())
                }
            }

            onProgress(resumeOffset, totalSize)  // نبلّغ الجزء اللي أصلاً موجود كتقدّم مبدئي فورًا

            val remainingBytes = totalSize - resumeOffset
            val mediaType = "application/octet-stream".toMediaTypeOrNull()
            val requestBody = ResumableTailRequestBody(file, resumeOffset, remainingBytes, mediaType) { sentInThisCall ->
                onProgress(resumeOffset + sentInThisCall, totalSize)
            }
            val part = MultipartBody.Part.createFormData("file", file.name, requestBody)

            val chunkResponse = api.uploadResumableChunk(token, destination, resumeOffset, part)
            if (!chunkResponse.isSuccessful) {
                // فشل الرفع (زي انقطاع الشبكة) - الجزء اللي كان اتبعت قبل
                // الانقطاع فعليًا موجود على القرص عند الكمبيوتر (اتكتب أول
                // بأول)، فمفيش داعي نمسح حاجة أو نرجع نبدأ من الصفر. المستخدم
                // أو التطبيق يقدر ينادي نفس الدالة دي تاني بعدين، وnegotiate
                // هيكتشف الجزء الموجود ويكمل منه تلقائيًا.
                return UploadOutcome.Failed("انقطع الرفع - هيكمل تلقائيًا من نفس النقطة المرة الجاية")
            }

            val completeResponse = api.completeResumableUpload(token, destination)
            val completeBody = completeResponse.body()
            if (completeResponse.isSuccessful && completeBody != null) {
                UploadOutcome.Success(completeBody)
            } else {
                UploadOutcome.Failed("الملف اترفع لكن التأكيد فشل: " + completeResponse.code())
            }
        } catch (e: Exception) {
            // استثناء شبكة عادي (قطع اتصال أثناء الرفع) - نفس المنطق فوق:
            // الجزء اللي اترفع قبل الانقطاع محفوظ على الكمبيوتر بالفعل.
            UploadOutcome.Failed(e.message ?: "انقطع الاتصال أثناء الرفع - هيكمل تلقائيًا لاحقًا")
        }
    }
}
