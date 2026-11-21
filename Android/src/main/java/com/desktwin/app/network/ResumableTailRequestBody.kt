package com.desktwin.app.network

import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink
import okio.buffer
import okio.source
import java.io.File
import java.io.RandomAccessFile

/**
 * زي ProgressRequestBody بالظبط، لكن بيبدأ القراءة من نقطة معينة جوه
 * الملف (startOffset) مش من أوله - عشان نبعت بس "الجزء الناقص" في نداء
 * واحد، من غير ما ننسخ الملف كامل أو نقسمه لملفات منفصلة على القرص.
 *
 * بنستخدم RandomAccessFile.seek() عشان نوصل لنقطة البداية مباشرة من
 * غير ما نقرا ونرمي البايتات اللي قبلها - أهم بكثير مع ملفات جيجا لو
 * كان resumeOffset كبير (لو استخدمنا file.inputStream() عادي كان
 * هيضطر يقرا كل حاجة من الأول لحد ما يوصل للنقطة المطلوبة).
 */
class ResumableTailRequestBody(
    private val file: File,
    private val startOffset: Long,
    private val lengthToSend: Long,
    private val mediaType: MediaType?,
    private val onProgress: (bytesSentInThisCall: Long) -> Unit
) : RequestBody() {

    companion object {
        private const val BUFFER_SIZE = 1024L * 1024
        private const val PROGRESS_INTERVAL_NANOS = 200_000_000L
    }

    override fun contentType(): MediaType? = mediaType

    override fun contentLength(): Long = lengthToSend

    override fun writeTo(sink: BufferedSink) {
        var bytesSent = 0L
        var lastReportedBytes = 0L
        var lastReportedAt = System.nanoTime()
        RandomAccessFile(file, "r").use { randomAccessFile ->
            randomAccessFile.seek(startOffset)
            val fileDescriptorStream = java.io.FileInputStream(randomAccessFile.fd)
            val source = fileDescriptorStream.source().buffer()
            val buffer = okio.Buffer()

            while (bytesSent < lengthToSend) {
                val toRead = minOf(BUFFER_SIZE, lengthToSend - bytesSent)
                val read = source.read(buffer, toRead)
                if (read == -1L) break
                sink.write(buffer, read)
                bytesSent += read
                val now = System.nanoTime()
                if (bytesSent == lengthToSend || now - lastReportedAt >= PROGRESS_INTERVAL_NANOS) {
                    onProgress(bytesSent)
                    lastReportedBytes = bytesSent
                    lastReportedAt = now
                }
            }
            // ملحوظة: مبنقفلش fileDescriptorStream/source صراحة هنا عشان
            // ميقفلش الـ file descriptor الأصلي بتاع randomAccessFile قبل
            // ما الـ use{} بره يخلص وهو لسه محتاجه - use{} على
            // randomAccessFile كفاية لغلق كل حاجة في الآخر.
        }
        if (lastReportedBytes != bytesSent) onProgress(bytesSent)
    }
}
