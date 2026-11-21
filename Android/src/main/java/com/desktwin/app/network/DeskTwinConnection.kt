package com.desktwin.app.network

import com.desktwin.app.diagnostics.DeskTwinDiagnostics
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.MessageDigest
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

typealias ProgressListener = (bytesRead: Long, totalBytes: Long) -> Unit

/**
 * مدير اتصال شبكي مركزي للـ Local API.
 * بيضمن استخدام TLS مع تثبيت شهادة الكمبيوتر بعد أول اقتران ناجح.
 */
object DeskTwinConnection {

    const val SECURE_SCHEME = "https"

    private var currentApiBaseUrl: String? = null
    private var currentFileTransferBaseUrl: String? = null
    private var apiInstance: DeskTwinApi? = null
    private var fileTransferApiInstance: DeskTwinApi? = null
    private var wsClient: OkHttpClient? = null

    private var pinnedFingerprint: String? = null
    @Volatile private var certificateMismatch = false

    class CertificateChangedException : java.io.IOException("DeskTwin certificate changed; pairing is required")
    class CertificateNotPinnedException : java.io.IOException("No trusted certificate is configured; pairing is required")

    // This process-wide client cache is reached by independent coroutines.
    // Serialize configuration/client replacement so a reconnect cannot leave
    // the API and file-transfer Retrofit instances bound to different hosts.
    @Synchronized
    fun configurePinnedCertificateFingerprint(fingerprint: String?) {
        shutdownWebSocketClient()
        pinnedFingerprint = fingerprint?.lowercase()?.replace(":", "")
        certificateMismatch = false
        currentApiBaseUrl = null
        currentFileTransferBaseUrl = null
        apiInstance = null
        fileTransferApiInstance = null
    }

    // كل اتصال يحتاج بصمة صريحة: من QR في pairing الأول، ثم من الإعدادات
    // المحلية المحفوظة لباقي الطلبات ونقل الملفات.
    private fun secureClientBuilder(
        fingerprintOverride: String? = null
    ): OkHttpClient.Builder {
        val expectedFingerprint = fingerprintOverride ?: pinnedFingerprint
        if (expectedFingerprint.isNullOrBlank()) {
            throw CertificateNotPinnedException()
        }
        val pinnedTrustManager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val expected = expectedFingerprint
                if (expected.isNullOrBlank()) throw CertificateNotPinnedException()
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(chain.first().encoded).joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    certificateMismatch = true
                    throw CertificateChangedException()
                }
            }
        }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinnedTrustManager), SecureRandom()) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, pinnedTrustManager)
            .hostnameVerifier { _, session ->
                // بصمة الشهادة الواردة من QR أو الإعدادات المحلية هي هوية
                // الطرف المقابل؛ لا يوجد مسار يقبل شهادة غير مثبتة.
                val expected = expectedFingerprint
                if (expected.isNullOrBlank()) return@hostnameVerifier false
                val peer = session.peerCertificates.firstOrNull() as? X509Certificate ?: return@hostnameVerifier false
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(peer.encoded).joinToString("") { "%02x".format(it) }
                actual == expected
            }
    }

    @Synchronized
    fun connect(ip: String, port: Int): DeskTwinApi {
        val baseUrl = "$SECURE_SCHEME://$ip:$port/"

        if (baseUrl == currentApiBaseUrl && apiInstance != null) {
            return apiInstance!!
        }

        // record() appends synchronously to disk; keep it off the hot cached
        // path because currentApi() is called for each API operation.
        DeskTwinDiagnostics.record("network", "client_configured", "$ip:$port")

        val logging = HttpLoggingInterceptor().apply {
            // لا نسجل headers؛ الـ X-Device-Token مفتاح وصول حقيقي.
            level = HttpLoggingInterceptor.Level.NONE
        }

        val client = secureClientBuilder()
            .addInterceptor(logging)
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        currentApiBaseUrl = baseUrl
        apiInstance = retrofit.create(DeskTwinApi::class.java)
        currentFileTransferBaseUrl = null
        fileTransferApiInstance = null  // نجبر إعادة بناء client النقل بالـ baseUrl الجديد كمان
        return apiInstance!!
    }

    /** One-time pairing client pinned to the fingerprint carried by the scanned QR. */
    @Synchronized
    fun connectForPairing(ip: String, port: Int, expectedFingerprint: String): DeskTwinApi {
        val normalizedFingerprint = expectedFingerprint.lowercase().replace(":", "").trim()
        require(normalizedFingerprint.matches(Regex("[0-9a-f]{64}"))) {
            "Pairing QR must contain a SHA-256 certificate fingerprint"
        }
        val baseUrl = "$SECURE_SCHEME://$ip:$port/"
        val client = secureClientBuilder(fingerprintOverride = normalizedFingerprint)
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.NONE })
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(DeskTwinApi::class.java)
    }

    /** عميل Media3 يستخدم شهادة الكمبيوتر المثبتة نفسها، للبث بلا تنزيل. */
    @Synchronized
    fun streamingClient(): OkHttpClient = secureClientBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Client مخصص لتحميل/رفع الملفات - مهلة قراءة وكتابة طويلة (10 دقايق)
     * تكفي ملفات كبيرة نسبيًا عبر شبكة محلية عادية. استخدم الدالة دي بدل
     * connect() في أي عملية فيها نقل bytes فعلي (getFileContent، uploadFile).
     */
    @Synchronized
    fun connectForFileTransfer(ip: String, port: Int): DeskTwinApi {
        val baseUrl = "$SECURE_SCHEME://$ip:$port/"

        if (baseUrl == currentFileTransferBaseUrl && fileTransferApiInstance != null) {
            return fileTransferApiInstance!!
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.NONE
        }

        val client = secureClientBuilder()
            .addInterceptor(logging)
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val listener = request.tag(ProgressListener::class.java)
                val body = response.body
                if (listener != null && body != null) {
                    response.newBuilder()
                        .body(ProgressResponseBody(body, listener))
                        .build()
                } else {
                    response
                }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.MINUTES)
            .writeTimeout(10, TimeUnit.MINUTES)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        currentFileTransferBaseUrl = baseUrl
        fileTransferApiInstance = retrofit.create(DeskTwinApi::class.java)
        return fileTransferApiInstance!!
    }

    @Synchronized
    fun isConnected(): Boolean = apiInstance != null

    @Synchronized
    fun disconnect() {
        DeskTwinDiagnostics.record("network", "disconnected")
        currentApiBaseUrl = null
        currentFileTransferBaseUrl = null
        apiInstance = null
        fileTransferApiInstance = null
        shutdownWebSocketClient()
    }

    @Synchronized
    fun certificateChanged(): Boolean = certificateMismatch

    @Synchronized
    fun clearPinnedCertificate() {
        pinnedFingerprint = null
        certificateMismatch = false
        shutdownWebSocketClient()
        disconnect()
    }

    @Synchronized
    fun shutdownWebSocketClient() {
        wsClient?.dispatcher?.executorService?.shutdown()
        wsClient?.connectionPool?.evictAll()
        wsClient = null
    }

    @Synchronized
    fun openWebSocket(request: okhttp3.Request, listener: okhttp3.WebSocketListener): okhttp3.WebSocket {
        // Reuse one process-wide client: creating an OkHttpClient per chat
        // reconnect leaks a dispatcher and connection pool until process exit.
        val client = wsClient ?: secureClientBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
            .also { wsClient = it }
        return client.newWebSocket(request, listener)
    }
}
