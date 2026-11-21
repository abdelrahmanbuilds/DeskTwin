package com.desktwin.app.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.sink
import okio.source
import java.io.File

/**
 * نفس RequestBody عادي بيبعت ملف، لكن بينادي callback بعد كل جزء يتبعت
 * فعليًا عبر الشبكة - ده اللي بيدّينا شريط تقدّم حقيقي (نسبة مئوية +
 * تقدير الوقت المتبقي) بدل ما الرفع يظهر "شغال" لحد ما يخلص فجأة.
 *
 * نقرأ على دفعات 1 MiB لتقليل استدعاءات التقدم والكتابة الصغيرة، مع
 * إبقاء استهلاك الذاكرة ثابتًا حتى مع الملفات الكبيرة.
 */
class ProgressRequestBody(
    private val file: File,
    private val mediaType: MediaType?,
    private val onProgress: (bytesSent: Long, totalBytes: Long) -> Unit
) : RequestBody() {

    companion object {
        private const val BUFFER_SIZE = 1024 * 1024L
        private const val PROGRESS_INTERVAL_NANOS = 200_000_000L
    }

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        val totalBytes = file.length()
        var bytesWritten = 0L
        var lastReportedBytes = 0L
        var lastReportedAt = System.nanoTime()

        file.source().use { source ->
            val bufferedSource = source.buffer()
            val buffer = okio.Buffer()
            var read: Long
            while (bufferedSource.read(buffer, BUFFER_SIZE).also { read = it } != -1L) {
                sink.write(buffer, read)
                bytesWritten += read
                val now = System.nanoTime()
                if (bytesWritten == totalBytes || now - lastReportedAt >= PROGRESS_INTERVAL_NANOS) {
                    onProgress(bytesWritten, totalBytes)
                    lastReportedBytes = bytesWritten
                    lastReportedAt = now
                }
            }
        }
        if (lastReportedBytes != bytesWritten) onProgress(bytesWritten, totalBytes)
    }
}
