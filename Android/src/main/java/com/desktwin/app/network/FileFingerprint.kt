package com.desktwin.app.network

import java.io.File
import java.security.MessageDigest

/**
 * نفس منطق transfer/resumable.py بالظبط على جهاز الكمبيوتر - بصمة الملف
 * هي SHA-256 لأول 4MB بس، مش الملف كامل. لازم تفضل القيمتين (هنا وهناك)
 * متطابقتين حرفيًا (نفس عدد البايتات، نفس الخوارزمية) عشان المقارنة
 * تشتغل صح.
 */
object FileFingerprint {

    const val SAMPLE_BYTES = 4 * 1024 * 1024L  // 4MB
    const val LARGE_FILE_THRESHOLD_BYTES = 1L * 1024 * 1024 * 1024  // 1GB

    fun isLargeFile(sizeBytes: Long): Boolean = sizeBytes >= LARGE_FILE_THRESHOLD_BYTES

    fun compute(file: File, sampleBytes: Long = SAMPLE_BYTES): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var remaining = sampleBytes
            while (remaining > 0) {
                val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                val read = input.read(buffer, 0, toRead)
                if (read == -1) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
