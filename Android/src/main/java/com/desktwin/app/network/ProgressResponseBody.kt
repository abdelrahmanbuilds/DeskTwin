package com.desktwin.app.network

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer

/**
 * نفس فكرة ProgressRequestBody بس للاتجاه المعاكس (تحميل من الكمبيوتر
 * للتليفون) - بينادي callback بعد كل جزء يوصل فعليًا من الشبكة.
 *
 * بيتلف حول ResponseBody الأصلي عن طريق ForwardingSource - أرخص وأنضف
 * من إعادة كتابة القراءة يدويًا، وبيشتغل تلقائيًا مع أي حجم ملف من غير
 * ما نحمّل حاجة في الذاكرة كلها مرة واحدة.
 */
class ProgressResponseBody(
    private val delegate: ResponseBody,
    private val onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
) : ResponseBody() {

    companion object {
        private const val PROGRESS_INTERVAL_NANOS = 200_000_000L
    }

    private val bufferedSource: BufferedSource by lazy { trackedSource().buffer() }

    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()
    override fun source(): BufferedSource = bufferedSource

    private fun trackedSource(): Source {
        val totalBytes = delegate.contentLength()
        return object : ForwardingSource(delegate.source()) {
            var totalRead = 0L
            var lastReportedAt = System.nanoTime()

            override fun read(sink: Buffer, byteCount: Long): Long {
                val bytesRead = super.read(sink, byteCount)
                if (bytesRead != -1L) {
                    totalRead += bytesRead
                }
                val now = System.nanoTime()
                if (bytesRead == -1L || now - lastReportedAt >= PROGRESS_INTERVAL_NANOS) {
                    onProgress(totalRead, totalBytes)
                    lastReportedAt = now
                }
                return bytesRead
            }
        }
    }
}
