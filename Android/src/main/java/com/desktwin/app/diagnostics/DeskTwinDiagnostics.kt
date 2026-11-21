package com.desktwin.app.diagnostics

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Lightweight diagnostic trail for field debugging.
 * It records what happened and where, never tokens or file contents.
 */
object DeskTwinDiagnostics {
    private const val tag = "DeskTwin"
    private const val maxLines = 500
    private val lock = Any()
    private val memory = ArrayDeque<String>()
    private var logFile: File? = null

    fun initialize(context: Context) {
        logFile = File(context.filesDir, "desktwin-diagnostics.log")
        record("diagnostics", "started", "app_open")
        runPreflight(context)
    }

    fun operationId(): String = UUID.randomUUID().toString().take(8)

    fun record(stage: String, event: String, detail: String = "", operationId: String? = null) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val secretPattern = Regex("(?i)((?:authorization|token|password|secret)\\s*[=:]\\s*(?:bearer\\s+)?)[^\\s,;]+")
        val safeDetail = secretPattern.replace(detail) { match ->
            match.groupValues[1] + "<redacted>"
        }
        val line = "$stamp | ${operationId ?: "-"} | $stage | $event | $safeDetail"
        synchronized(lock) {
            memory.addLast(line)
            while (memory.size > maxLines) memory.removeFirst()
            runCatching {
                logFile?.appendText(line + "\n")
                if (logFile?.length() ?: 0L > 512 * 1024) rotate()
            }
        }
        Log.d(tag, line)
    }

    fun recordThrowable(stage: String, error: Throwable, operationId: String? = null) {
        record(stage, "failure", "${error::class.java.simpleName}: ${error.message ?: "no message"}", operationId)
    }

    fun snapshot(): String = synchronized(lock) { memory.joinToString("\n") }

    fun clear() {
        synchronized(lock) {
            memory.clear()
            runCatching { logFile?.writeText("") }
        }
    }

    fun exportFile(context: Context): File {
        val export = File(context.cacheDir, "desktwin-diagnostics-export.log")
        export.writeText(snapshot())
        return export
    }

    private fun rotate() {
        val file = logFile ?: return
        val old = File(file.parentFile, "desktwin-diagnostics.previous.log")
        runCatching {
            if (old.exists()) old.delete()
            file.renameTo(old)
            file.createNewFile()
        }
    }

    private fun runPreflight(context: Context) {
        if (!context.filesDir.exists() || !context.filesDir.canWrite()) {
            record("preflight", "warning", "app_storage_not_writable")
        }
        if (!context.cacheDir.exists() || !context.cacheDir.canWrite()) {
            record("preflight", "warning", "cache_not_writable")
        }
        if (logFile == null) {
            record("preflight", "warning", "diagnostic_file_unavailable")
        }
        record("preflight", "completed")
    }
}
