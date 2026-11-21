package com.desktwin.app.network

import android.os.Handler
import android.os.Looper
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID

/** قناة رسائل نصية ثنائية الاتجاه؛ لا تقرأ الحافظة ولا تكتب فيها. */
object ManualChatManager {
    var onManualTextReceived: ((String, String) -> Unit)? = null
    private var socket: WebSocket? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var chatActive = false
    @Volatile private var chatGeneration = 0
    @Volatile private var chatScope: CoroutineScope? = null
    private var reconnectRunnable: Runnable? = null
    private var chatIp = ""
    private var chatPort = 0
    private var chatToken = ""
    suspend fun sendTextOnce(ip: String, port: Int, token: String, text: String, messageId: String = UUID.randomUUID().toString()): Boolean =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder().url("wss://$ip:$port/ws").header("X-Device-Token", token).build()
            var socket: WebSocket? = null
            var timeoutRunnable: Runnable? = null
            val completionClaimed = AtomicBoolean(false)
            fun complete(result: Boolean, activeSocket: WebSocket? = socket) {
                if (!completionClaimed.compareAndSet(false, true)) return
                timeoutRunnable?.let(handler::removeCallbacks)
                if (!result) activeSocket?.cancel()
                if (continuation.isActive) continuation.resume(result)
            }
            val sendTimeout = Runnable { complete(false) }
            timeoutRunnable = sendTimeout
            socket = DeskTwinConnection.openWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    if (!webSocket.send(JSONObject().put("type", "manual_text").put("id", messageId).put("text", text).toString()))
                        complete(false, webSocket)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val acknowledged = runCatching {
                        val json = JSONObject(text)
                        json.optString("type") == "manual_ack" && json.optString("id") == messageId
                    }.getOrDefault(false)
                    if (acknowledged) {
                        complete(true)
                        webSocket.close(1000, "delivered")
                    }
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                    complete(false, webSocket)
                }
            })
            if (!completionClaimed.get()) handler.postDelayed(sendTimeout, 6_000)
            continuation.invokeOnCancellation {
                completionClaimed.set(true)
                timeoutRunnable?.let(handler::removeCallbacks)
                socket?.cancel()
            }
        }

    fun startManualChat(ip: String, port: Int, token: String) {
        val receiver = onManualTextReceived
        stop()
        onManualTextReceived = receiver
        chatActive = true
        chatScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        chatIp = ip
        chatPort = port
        chatToken = token
        openManualChat()
    }

    private fun openManualChat() {
        if (!chatActive || chatIp.isBlank() || chatPort <= 0 || chatToken.isBlank()) return
        val generation = chatGeneration
        val request = Request.Builder().url("wss://$chatIp:$chatPort/ws").header("X-Device-Token", chatToken).build()
        socket = DeskTwinConnection.openWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!chatActive || generation != chatGeneration) return
                runCatching {
                    val json = JSONObject(text)
                    if (json.optString("type") == "manual_text") {
                        val messageId = json.optString("id")
                        onManualTextReceived?.invoke(json.optString("text"), messageId)
                        webSocket.send(JSONObject().put("type", "manual_delivered").put("id", messageId).toString())
                    }
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                if (chatActive && generation == chatGeneration) handler.post {
                    if (!chatActive || generation != chatGeneration) return@post
                    reconnectRunnable?.let(handler::removeCallbacks)
                    val retry = Runnable {
                        reconnectRunnable = null
                        if (chatActive && generation == chatGeneration) openManualChat()
                    }
                    reconnectRunnable = retry
                    handler.postDelayed(retry, 2_000)
                }
            }
        })
    }

    fun stop() {
        chatGeneration += 1
        chatActive = false
        chatScope?.cancel()
        chatScope = null
        reconnectRunnable?.let(handler::removeCallbacks)
        reconnectRunnable = null
        socket?.close(1000, "disconnect")
        socket = null
        onManualTextReceived = null
    }
}
