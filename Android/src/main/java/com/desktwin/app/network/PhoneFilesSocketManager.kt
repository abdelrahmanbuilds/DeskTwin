package com.desktwin.app.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/** Isolated TLS-pinned WebSocket for phone file browsing and transfers. */
object PhoneFilesSocketManager {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    @Volatile private var generation = 0
    @Volatile private var scope: CoroutineScope? = null
    private var socket: WebSocket? = null
    private var reconnect: Runnable? = null
    private var ip = ""
    private var port = 0
    private var token = ""
    private val thumbnailSlots = Semaphore(3)
    private val downloadSlots = Semaphore(1)
    private val rangeSlots = Semaphore(3)
    @Volatile private var binaryTransferEnabled = false

    fun start(serverIp: String, serverPort: Int, deviceToken: String) {
        stop()
        active = true
        ip = serverIp
        port = serverPort
        token = deviceToken
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        connect()
    }

    private fun connect() {
        if (!active || ip.isBlank() || port <= 0 || token.isBlank()) return
        binaryTransferEnabled = false
        val currentGeneration = generation
        // Gallery navigation can enqueue the visible thumbnail batch plus a
        // page request. Leave room for that burst instead of dropping the
        // whole dedicated phone-file connection.
        val messages = Channel<String>(capacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        scope?.launch {
            for (text in messages) {
                if (!active || currentGeneration != generation) break
                val parsed = runCatching { JSONObject(text) }
                if (parsed.isFailure) {
                    Log.w(TAG, "Ignoring malformed phone-file message", parsed.exceptionOrNull())
                    continue
                }
                val request = parsed.getOrNull() ?: continue
                val currentSocket = socket ?: continue
                runCatching {
                    when {
                        request.optString("type") == "phone_fs_request" && request.optString("operation") == "thumbnail" ->
                            scope?.launch {
                                runCatching {
                                    thumbnailSlots.withPermit {
                                        if (active && currentGeneration == generation)
                                            sendJson(currentSocket, PhoneFileManager.handle(request))
                                    }
                                }.onFailure {
                                    Log.w(TAG, "Phone thumbnail request failed", it)
                                    currentSocket.close(1011, "phone thumbnail request failed")
                                }
                            }
                        request.optString("type") == "phone_fs_request" && request.optString("operation") == "download" ->
                            scope?.launch {
                                runCatching {
                                    downloadSlots.withPermit {
                                        PhoneFileManager.stream(request, { sendJson(currentSocket, it) },
                                            if (binaryTransferEnabled) ({ frame -> sendBinary(currentSocket, frame) }) else null)
                                    }
                                }.onFailure {
                                    Log.e(TAG, "Phone-file download stream failed", it)
                                    currentSocket.close(1011, "phone-file download failed")
                                }
                            }
                        request.optString("type") == "phone_fs_request" && request.optString("operation") == "read_range" ->
                            // Several bounded reads may overlap so video seeks do not queue
                            // behind each other or behind a full-file phone download.
                            scope?.launch {
                                runCatching {
                                    rangeSlots.withPermit {
                                        PhoneFileManager.streamRange(request, { sendJson(currentSocket, it) },
                                            if (binaryTransferEnabled) ({ frame -> sendBinary(currentSocket, frame) }) else null)
                                    }
                                }.onFailure {
                                    Log.e(TAG, "Phone-file range stream failed", it)
                                    currentSocket.close(1011, "phone-file range failed")
                                }
                            }
                        request.optString("type") == "phone_fs_request" ->
                            sendJson(currentSocket, PhoneFileManager.handle(request))
                        request.optString("type").startsWith("phone_fs_upload_") ->
                            PhoneFileManager.handleUpload(request) { sendJson(currentSocket, it) }
                    }
                }.onFailure {
                    Log.e(TAG, "Phone-file operation or socket send failed", it)
                    currentSocket.close(1011, "phone file operation failed")
                }
            }
        }
        val request = Request.Builder()
            .url("wss://$ip:$port/ws/phone-files")
            .header("X-Device-Token", token)
            .header("X-DeskTwin-Transfer", "binary-v1")
            .build()
        socket = DeskTwinConnection.openWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                binaryTransferEnabled = response.header("X-DeskTwin-Transfer-Protocol") == "binary-v1"
                Log.i(TAG, "Phone-file channel connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!active || currentGeneration != generation) return
                val queued = messages.trySend(text)
                if (queued.isFailure) {
                    Log.w(TAG, "Phone-file request queue full; dropping message")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                if (!active || currentGeneration != generation) return
                if (!binaryTransferEnabled) {
                    Log.w(TAG, "Ignoring an unnegotiated binary phone-file message")
                    webSocket.close(1003, "binary transfer was not negotiated")
                    return
                }
                try {
                    // This runs on OkHttp's socket callback thread, never the UI thread.
                    // Writing here preserves strict ordering with the following finish message.
                    PhoneFileManager.handleUploadBinary(bytes.toByteArray())
                } catch (error: Exception) {
                    Log.e(TAG, "Phone-file binary upload chunk failed", error)
                    webSocket.close(1002, "invalid binary transfer chunk")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "Phone-file channel failed (HTTP ${response?.code ?: "no response"})", t)
                messages.close()
                scheduleReconnect(currentGeneration)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Phone-file channel closed ($code): $reason")
                messages.close()
                scheduleReconnect(currentGeneration)
            }
        })
    }

    private fun scheduleReconnect(currentGeneration: Int) {
        if (!active || currentGeneration != generation) return
        handler.post {
            if (!active || currentGeneration != generation) return@post
            reconnect?.let(handler::removeCallbacks)
            val retry = Runnable {
                reconnect = null
                if (active && currentGeneration == generation) connect()
            }
            reconnect = retry
            handler.postDelayed(retry, 2_000)
        }
    }

    fun stop() {
        generation++
        active = false
        scope?.cancel()
        scope = null
        reconnect?.let(handler::removeCallbacks)
        reconnect = null
        socket?.close(1000, "disconnect")
        socket = null
    }

    private suspend fun sendJson(webSocket: WebSocket, message: JSONObject) {
        // OkHttp queues sends asynchronously. Pause only while its outbound
        // queue is backed up so large downloads do not accumulate in RAM.
        while (webSocket.queueSize() > MAX_QUEUED_BYTES) delay(5)
        check(webSocket.send(message.toString())) { "WebSocket rejected phone-file response" }
    }

    private suspend fun sendBinary(webSocket: WebSocket, frame: ByteArray) {
        while (webSocket.queueSize() > MAX_QUEUED_BYTES) delay(5)
        check(webSocket.send(frame.toByteString())) { "WebSocket rejected binary file chunk" }
    }

    private const val MAX_QUEUED_BYTES = 4L * 1024 * 1024
    private const val TAG = "PhoneFilesSocket"
}
