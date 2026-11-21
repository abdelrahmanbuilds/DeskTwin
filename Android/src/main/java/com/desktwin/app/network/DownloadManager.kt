package com.desktwin.app.network

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * بيدير تحميل ملف من الكمبيوتر لمساحة التليفون - نمطين:
 *   - مؤقت: يتحط في cache التطبيق، يتفتح فورًا بتطبيق مناسب لنوعه،
 *     وبيتمسح لما المستخدم يقفل التطبيق (دورة "افتحه وامسحه" الكاملة،
 *     مش بس تحميل بدون استخدام فعلي).
 *   - دائم: يتحط في مساحة التخزين العامة (Downloads) ويفضل موجود
 *     لحد ما المستخدم نفسه يمسحه يدويًا. القرار بحذف نسخة الكمبيوتر
 *     (نقل دائم حقيقي) مسؤولية الطرف المستدعي - DownloadManager نفسه
 *     بينزّل بس، ومايقرّرش حذف حاجة من الكمبيوتر.
 */
object DownloadManager {

    enum class DownloadType { TEMPORARY, PERMANENT }

    suspend fun downloadFile(
        context: Context,
        api: DeskTwinApi,
        token: String,
        fileId: Int,
        filePath: String,
        fileName: String,
        type: DownloadType,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): Result<File> = withContext(Dispatchers.IO) {
        // بيفرض إن كل عملية شبكة أو قرص هنا تتنفذ على IO thread بغض
        // النظر عن الـ dispatcher بتاع الطرف المستدعي (rememberCoroutineScope
        // شغالة افتراضيًا على Main.immediate، ومناسبة لاستدعاءات Retrofit
        // العادية، لكن FileOutputStream و copyTo عمليات I/O خام مش
        // بتاخد نفس الحماية - فكان بيبوظ بـ NetworkOnMainThreadException).
        // الدالة بقت مسؤولة عن أمان الـ threading بتاعها بنفسها، فأي مكان
        // تاني في التطبيق يستدعيها هيبقى آمن تلقائيًا من غير ما نغيّر حاجة
        // في مكان الاستدعاء.
        try {
            val response = if (fileId > 0) {
                api.getFileContent(token, fileId, onProgress)
            } else {
                api.getFileContentByPath(token, filePath, onProgress)
            }

            if (!response.isSuccessful) {
                // نرجّع رسالة واضحة فيها كود الخطأ الفعلي من السيرفر
                // (401 توكن غير صالح، 404 الملف اتمسح، 403 برّه النطاق
                // المسموح، إلخ) - بدل ما نسيب المستخدم قدام "فشل التحميل"
                // من غير أي تفاصيل تساعده يفهم المشكلة.
                val errorBody = response.errorBody()?.string()
                return@withContext Result.failure(Exception(
                    "الكمبيوتر رفض الطلب (كود ${response.code()})" +
                    if (!errorBody.isNullOrBlank()) ": $errorBody" else ""
                ))
            }

            val body = response.body()
                ?: return@withContext Result.failure(Exception("الكمبيوتر رد بنجاح لكن من غير أي محتوى فعلي للملف"))

            if (type == DownloadType.PERMANENT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Scoped Storage (Android 10+): لازم نكتب عن طريق MediaStore
                // عشان الملف يتحط فعليًا في مجلد Downloads العام المشترك
                // (زي أي تطبيق تحميل عادي)، مش في مساحة خاصة بالتطبيق محدش
                // شايفها غير التطبيق نفسه - ده كان سبب إحساس "اتحمّل بس مش
                // عارف راح فين".
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val itemUri = resolver.insert(collection, values)
                    ?: return@withContext Result.failure(Exception("مقدرناش نجهّز مكان الحفظ في Downloads"))

                resolver.openOutputStream(itemUri)?.use { output ->
                    body.byteStream().use { input -> input.copyTo(output, bufferSize = 1024 * 1024) }
                } ?: return@withContext Result.failure(Exception("مقدرناش نفتح ملف الحفظ في Downloads"))

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)

                // بعد الحفظ في MediaStore، بنرجّع نسخة File محلية للتوافق مع
                // باقي التدفق (رسالة "اتحفظ باسم..." وواجهة FileDetailsScreen)
                // - الملف الحقيقي المرئي للمستخدم هو اللي في MediaStore فعلاً.
                return@withContext Result.success(File(fileName))
            }

            val destinationFile = if (type == DownloadType.TEMPORARY) {
                File(context.cacheDir, fileName)
            } else {
                // Fallback لـ Android 8/9 (API 26-28) بس - قبل إلزامية
                // Scoped Storage، الوصول المباشر للمسار العام ده كان
                // الطريقة الرسمية المتاحة أصلًا.
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                File(downloadsDir, fileName)
            }

            body.byteStream().use { input ->
                FileOutputStream(destinationFile).use { output ->
                    input.copyTo(output, bufferSize = 1024 * 1024)
                }
            }

            Result.success(destinationFile)
        } catch (e: SocketTimeoutException) {
            Result.failure(Exception("الاتصال استغرق وقت طويل جدًا - الملف كبير أو الشبكة بطيئة. جرّب تاني"))
        } catch (e: UnknownHostException) {
            Result.failure(Exception("مقدرناش نوصل للكمبيوتر - تأكد إنك على نفس شبكة الواي فاي"))
        } catch (e: Exception) {
            // آخر حل: نطبع اسم نوع الاستثناء نفسه لو مالوش message واضح،
            // عشان الرسالة النهائية ماتفضلش فاضية أو "فشل التحميل" جنيريك
            // من غير أي معلومة مفيدة للتشخيص.
            val detail = e.message ?: e.javaClass.simpleName
            Result.failure(Exception("فشل التحميل: $detail"))
        }
    }

    /**
     * بيفتح الملف اللي اتحمّل بتطبيق مناسب لنوعه (viewer, PDF reader, إلخ)
     * عن طريق FileProvider - محتاج <provider> معرّف في AndroidManifest.xml
     * وملف file_paths.xml (راجع res/xml/file_paths.xml المرفق).
     */
    fun openDownloadedFile(context: Context, file: File): Result<Unit> {
        return try {
            val uri: Uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val mimeType = context.contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception("مفيش تطبيق على التليفون يقدر يفتح النوع ده من الملفات"))
        }
    }

    /** بيمسح النسخة المؤقتة - يُستدعى لما المستخدم يرجع من شاشة تفاصيل الملف بعد الفتح المؤقت. */
    fun deleteTemporaryFile(file: File) {
        if (file.exists()) {
            file.delete()
        }
    }
}
