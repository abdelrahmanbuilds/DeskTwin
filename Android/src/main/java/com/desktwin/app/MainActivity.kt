package com.desktwin.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import org.json.JSONObject
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.desktwin.app.data.*
import com.desktwin.app.diagnostics.DeskTwinDiagnostics
import com.desktwin.app.discovery.DeskTwinDiscovery
import com.desktwin.app.discovery.DiscoveredDevice
import com.desktwin.app.network.*
import com.desktwin.app.ui.screens.*
import com.desktwin.app.ui.theme.DeskTwinTheme
import com.desktwin.app.ui.theme.LocalDeskTwinLanguage
import com.desktwin.app.ui.theme.deskTwinThemePreset
import com.desktwin.app.ui.theme.deskTwinThemeIsDark
import com.desktwin.app.ui.theme.ThemeArtStyle
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.net.Inet4Address
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "باقي حوالي..." من عدد ثواني متبقية - بيقرّبها لأقرب وحدة مفهومة
 * (ثواني/دقايق/ساعات) بدل رقم دقيق مربك زي "127 ثانية".
 */
private fun formatEtaLabel(remainingSeconds: Long): String {
    return when {
        remainingSeconds < 60 -> "باقي حوالي $remainingSeconds ثانية"
        remainingSeconds < 3600 -> "باقي حوالي ${remainingSeconds / 60} دقيقة"
        else -> "باقي حوالي ${remainingSeconds / 3600} ساعة"
    }
}

private fun formatTransferSpeed(bytesPerSecond: Long): String {
    val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
    var value = bytesPerSecond.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return "%.1f %s".format(java.util.Locale.US, value, units[unit])
}

private fun classifyPairingError(error: Throwable): String {
    val text = error::class.java.simpleName
    return when {
        text.contains("CertificateNotPinned", true) ->
            "CERTIFICATE_NOT_PINNED: لازم تعيد Pairing مع الكمبيوتر قبل الاتصال."
        text.contains("SSL", true) || text.contains("Certificate", true) ->
            "TLS_CERTIFICATE_ERROR: شهادة الكمبيوتر غير متوافقة. الحل: امسح شهادة الاقتران وأعد الاكتشاف."
        text.contains("Timeout", true) ->
            "SOCKET_TIMEOUT: الكمبيوتر لم يرد في الوقت المحدد. تأكد أن الهاتف والكمبيوتر على الشبكة نفسها، وأوقف VPN أو عزل الأجهزة في الراوتر، واسمح لـDeskTwin بالاتصالات الواردة على TCP 8420 في جدار حماية Windows."
        text.contains("Connect", true) || text.contains("UnknownHost", true) ->
            "CONNECT_ERROR: الهاتف لا يستطيع الوصول للكمبيوتر. الحل: تأكد من نفس الشبكة والمنفذ 8420."
        else -> "PAIRING_NETWORK_ERROR: ${error.message ?: text}. الحل: أعد اكتشاف الكمبيوتر وحاول مرة أخرى."
    }
}

private data class PairingQrData(
    val code: String,
    val endpoints: List<DiscoveredDevice>,
    val certificateFingerprint: String,
)

private data class BrowseLoadRequest(
    val generation: Int,
    val token: String,
    val path: String,
)

private fun isUsableLanIpv4(value: String): Boolean {
    val octets = value.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] != 0 && octets[0] != 127 && octets[0] != 255
        && octets[0] !in 224..239
        && !(octets[0] == 169 && octets[1] == 254)
}

/** Parse only certificate-bound pairing QRs. */
private fun parsePairingQr(rawValue: String?): PairingQrData? {
    val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return null
    if (uri.scheme != "desktwin" || uri.host != "pair" || !uri.path.isNullOrEmpty()
        || uri.userInfo != null || uri.getQueryParameter("v") != "2") return null
    val code = uri.getQueryParameter("code")?.takeIf { it.matches(Regex("\\d{6}")) } ?: return null
    val port = uri.getQueryParameter("port")?.toIntOrNull()?.takeIf { it == DESKTWIN_PORT } ?: return null
    val fingerprint = uri.getQueryParameter("fp")?.lowercase()?.replace(":", "")
        ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) } ?: return null
    val endpoints = uri.getQueryParameters("host").mapNotNull { host ->
        val parts = host.split('.')
        if (parts.size != 4 || parts.any { it.toIntOrNull() == null }) return@mapNotNull null
        val octets = parts.map { it.toInt() }
        if (octets.any { it !in 0..255 }) return@mapNotNull null
        if (octets.all { it == 0 } || octets[0] == 0 || octets[0] == 127
            || (octets[0] == 169 && octets[1] == 254)
            || octets[0] == 255 || octets[0] in 224..239
        )
            return@mapNotNull null
        DiscoveredDevice("DeskTwin PC", host, port)
    }.distinctBy { it.ip }.take(8)

    // A QR without a directly usable address is still secure: discovery can
    // resolve the endpoint, while this QR remains the certificate authority.
    return PairingQrData(code, endpoints, fingerprint)
}

private data class LocalIpv4Subnet(val address: ByteArray, val prefixLength: Int) {
    fun contains(host: String): Boolean {
        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val fullBytes = prefixLength / 8
        for (index in 0 until fullBytes) {
            if ((address[index].toInt() and 0xFF) != octets[index]) return false
        }
        val remainingBits = prefixLength % 8
        if (remainingBits == 0) return true
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return ((address[fullBytes].toInt() and 0xFF) and mask) == (octets[fullBytes] and mask)
    }
}

/** Prefer QR endpoints sharing a connected Wi-Fi/Ethernet subnet with this phone. */
private fun prioritizePairingEndpoints(context: Context, endpoints: List<DiscoveredDevice>): List<DiscoveredDevice> {
    if (endpoints.size < 2) return endpoints
    val subnets: List<LocalIpv4Subnet> = runCatching {
        val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return@runCatching emptyList()
        val network = connectivity.activeNetwork ?: return@runCatching emptyList()
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@runCatching emptyList()
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return@runCatching emptyList()
        connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
            val address = link.address as? Inet4Address ?: return@mapNotNull null
            if (address.isLoopbackAddress || address.isLinkLocalAddress || link.prefixLength !in 1..32) null
            else LocalIpv4Subnet(address.address, link.prefixLength)
        }
    }.getOrDefault(emptyList())
    if (subnets.isEmpty()) return endpoints

    return endpoints.withIndex()
        .sortedWith(compareBy<IndexedValue<DiscoveredDevice>> { entry ->
            if (subnets.any { it.contains(entry.value.ip) }) 0 else 1
        }.thenBy { it.index })
        .map { it.value }
}

class MainActivity : ComponentActivity() {

    fun addFileShortcut(file: FileMetadata) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = getSystemService(ShortcutManager::class.java) ?: return
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.desktwin.app.OPEN_FILE_SHORTCUT"
            putExtra("shortcut_file_id", file.id)
            putExtra("shortcut_file_path", file.path)
        }
        val shortcut = ShortcutInfo.Builder(this, "file_${file.id}")
            .setShortLabel(file.name.take(25))
            .setLongLabel("فتح ${file.name}")
            .setIcon(Icon.createWithResource(this, android.R.drawable.ic_menu_view))
            .setIntent(intent)
            .build()
        manager.requestPinShortcut(shortcut, null)
    }

    private lateinit var preferences: DeskTwinPreferences
    private lateinit var discovery: DeskTwinDiscovery
    private lateinit var uploadQueue: UploadQueue
    private lateinit var localCache: LocalCache
    private lateinit var profileStore: ComputerProfileStore
    private lateinit var commandQueue: CommandQueue
    private lateinit var treeMirrorStore: TreeMirrorStore
    private lateinit var offlineCommandQueue: OfflineCommandQueue

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        DeskTwinDiagnostics.initialize(applicationContext)

        PhoneFileManager.initialize(applicationContext)

        preferences = DeskTwinPreferences(applicationContext)
        discovery = DeskTwinDiscovery(applicationContext)
        uploadQueue = UploadQueue(applicationContext)
        localCache = LocalCache(applicationContext)
        profileStore = ComputerProfileStore(applicationContext)
        commandQueue = CommandQueue(applicationContext)
        treeMirrorStore = TreeMirrorStore(applicationContext)
        offlineCommandQueue = OfflineCommandQueue(applicationContext)
        com.desktwin.app.notifications.TransferNotifications.ensureChannel(applicationContext)
        com.desktwin.app.notifications.ChatNotifications.ensureChannel(applicationContext)

        setContent {
            // Match the desktop's dark first frame while DataStore restores the
            // saved theme; starting as light caused a visible white flash.
            val themeMode by preferences.themeMode.collectAsState(initial = "dark")
            val themePresetId by preferences.themePreset.collectAsState(initial = "desktwin")
            val reduceMotion by preferences.reduceMotion.collectAsState(initial = false)
            val systemIsDark = isSystemInDarkTheme()
            val language by preferences.language.collectAsState(initial = "ar")
            val activeThemePreset = deskTwinThemePreset(themePresetId)
            val resolvedDarkTheme = deskTwinThemeIsDark(activeThemePreset, themeMode, systemIsDark)
            SideEffect {
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.isAppearanceLightStatusBars = !resolvedDarkTheme
                insetsController.isAppearanceLightNavigationBars = !resolvedDarkTheme
                if (Build.VERSION.SDK_INT < 35) {
                    val systemBarColor = (if (resolvedDarkTheme) activeThemePreset.darkBackground else activeThemePreset.lightBackground).toArgb()
                    // Keep themed bars on Android 10–14; Android 15 enforces edge-to-edge.
                    @Suppress("DEPRECATION")
                    run {
                        window.statusBarColor = systemBarColor
                        window.navigationBarColor = systemBarColor
                    }
                }
            }
            CompositionLocalProvider(
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalDeskTwinLanguage provides language
            ) {
                DeskTwinTheme(
                    darkTheme = resolvedDarkTheme,
                    presetId = themePresetId,
                    reduceMotion = reduceMotion
                ) {
                    DeskTwinApp(
                        preferences = preferences,
                        discovery = discovery,
                        uploadQueue = uploadQueue,
                        localCache = localCache,
                        profileStore = profileStore,
                        commandQueue = commandQueue,
                        treeMirrorStore = treeMirrorStore,
                        offlineCommandQueue = offlineCommandQueue
                    )
                }
            }
        }
    }

}

private fun requiredStoragePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO,
            // مطلوبة هنا كمان (مش بس وقت الرفع) - أول عملية نقل ملف
            // بتحصل غالبًا هي نفسها اللي هيظهر معاها إشعار تقدّم لأول
            // مرة، فأنسب لحظة نطلب فيها الصلاحية.
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

@Composable
fun DeskTwinApp(
    preferences: DeskTwinPreferences,
    discovery: DeskTwinDiscovery,
    uploadQueue: UploadQueue,
    localCache: LocalCache,
    profileStore: ComputerProfileStore,
    commandQueue: CommandQueue,
    treeMirrorStore: TreeMirrorStore,
    offlineCommandQueue: OfflineCommandQueue
) {
    val scope = rememberCoroutineScope()
    val uploadJobs = remember { mutableMapOf<String, Job>() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val selectedThemeMode by preferences.themeMode.collectAsState(initial = "dark")
    val selectedThemePreset by preferences.themePreset.collectAsState(initial = "desktwin")
    val themeSoundUris by preferences.themeSoundUris.collectAsState(initial = emptyMap())
    val reduceMotion by preferences.reduceMotion.collectAsState(initial = false)
    val selectedLanguage by preferences.language.collectAsState(initial = "ar")
    val introVideoUri by preferences.introVideoUri.collectAsState(initial = null)
    val introMode by preferences.introMode.collectAsState(initial = "auto")
    val selectedFileViewMode by preferences.fileViewMode.collectAsState(initial = "list")
    val favoriteFolders by preferences.favoriteFolders.collectAsState(initial = emptyList())
    var showIntro by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val selectedMode = preferences.introMode.first()
        val selectedVideo = preferences.introVideoUri.first()
        if (selectedMode == "auto" && selectedVideo != null) showIntro = true
    }

    // البداية دايمًا شاشة الملفات - مش شاشة اتصال إجبارية. لو فيه كاش
    // محلي (زيارة سابقة)، هيتحمّل فورًا هنا قبل أي محاولة اتصال. لو مفيش
    // كاش خالص (أول استخدام على الإطلاق)، الشاشة هتفضل فاضية لحد ما
    // المستخدم يعمل أول اتصال من الإعدادات - مش هيتحول لشاشة Discovery
    // تلقائيًا، القرار ده بقى بيده هو من زرار في الإعدادات.
    val screenStack = remember { mutableStateListOf<Screen>(Screen.FileBrowser()) }
    val screen: Screen = screenStack.last()
    var pageForward by remember { mutableStateOf(true) }

    fun pushScreen(s: Screen) {
        pageForward = true
        screenStack.add(s)
    }
    fun popScreen(): Boolean {
        return if (screenStack.size > 1) {
            pageForward = false
            screenStack.removeAt(screenStack.size - 1)
            true
        } else false
    }
    fun replaceStackWith(s: Screen) {
        pageForward = true
        screenStack.clear()
        screenStack.add(s)
    }

    BackHandler(enabled = screenStack.size > 1) { popScreen() }

    var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Disconnected) }
    var isReconnecting by remember { mutableStateOf(false) }
    var isDisconnecting by remember { mutableStateOf(false) }

    var discoveredDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var discoveryJob by remember { mutableStateOf<Job?>(null) }

    var selectedDevice by remember { mutableStateOf<DiscoveredDevice?>(null) }

    fun currentApi(): DeskTwinApi? {
        val device = selectedDevice ?: return null
        return DeskTwinConnection.connect(device.ip, device.port)
    }

    val pcThumbnailCache = remember {
        object : LruCache<String, Bitmap>(16 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
        }
    }
    val thumbnailRequestSlots = remember { kotlinx.coroutines.sync.Semaphore(3) }
    suspend fun loadPcThumbnail(entry: DirEntry): Bitmap? {
        val cacheKey = "${entry.file_id ?: entry.path}:${entry.size_bytes}:${entry.modified_at.orEmpty()}"
        pcThumbnailCache.get(cacheKey)?.let { return it }
        val connected = connectionState as? ConnectionState.Connected ?: return null
        return thumbnailRequestSlots.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    val id = entry.file_id ?: 0
                    val response = currentApi()?.getFileThumbnail(
                        connected.token,
                        id,
                        path = if (id == 0) entry.path else null
                    ) ?: return@withContext null
                    if (!response.isSuccessful) return@withContext null
                    val bitmap = response.body()?.use { body ->
                        val bytes = body.bytes()
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    }
                    if (bitmap != null) pcThumbnailCache.put(cacheKey, bitmap)
                    bitmap
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * نسخة خاصة بعمليات نقل الملفات الفعلية (تحميل/رفع) - بتستخدم client
     * بمهلة أطول بكثير (دقايق مش ثواني)، عشان ملف كبير ميفشلش بـ timeout
     * وهمي رغم إن الاتصال سليم تمامًا. استخدمها بدل currentApi() في أي
     * نداء فيه نقل bytes فعلي (getFileContent، uploadFile).
     */
    fun currentFileTransferApi(): DeskTwinApi? {
        val device = selectedDevice ?: return null
        return DeskTwinConnection.connectForFileTransfer(device.ip, device.port)
    }

    var isVerifyingCode by remember { mutableStateOf(false) }
    var pairingError by remember { mutableStateOf<String?>(null) }
    var pairingDiagnostics by remember { mutableStateOf<String?>(null) }
    var scannedPairingCode by remember { mutableStateOf<String?>(null) }
    var scannedPairingDevices by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var scannedPairingFingerprint by remember { mutableStateOf<String?>(null) }
    // الكود قد يكون نفسه عند إعادة المسح بعد فشل شبكة مؤقت؛ العداد يميّز
    // المحاولة الجديدة حتى لا تمنع Compose إعادة بدء الاقتران التلقائي.
    var pairingScanAttempt by remember { mutableStateOf(0) }
    var isQrScannerPreparing by remember { mutableStateOf(false) }

    var currentPath by remember { mutableStateOf("") }
    var isAtRoot by remember { mutableStateOf(true) }
    var homeData by remember { mutableStateOf<HomeResponse?>(null) }
    var browseData by remember { mutableStateOf<BrowseResponse?>(null) }
    var isLoadingBrowse by remember { mutableStateOf(false) }
    var pendingBrowseRequest by remember { mutableStateOf<BrowseLoadRequest?>(null) }
    var browseLoadWorker by remember { mutableStateOf<Job?>(null) }
    var browseRequestGeneration by remember { mutableStateOf(0) }
    val lastCacheSyncAt by localCache.lastSyncTime.collectAsState(initial = null)
    val pathHistory = remember { mutableStateListOf<String>() }

    var pickerCurrentPath by remember { mutableStateOf("") }
    var pickerIsAtRoot by remember { mutableStateOf(true) }
    var pickerBrowseData by remember { mutableStateOf<BrowseResponse?>(null) }
    val pickerPathHistory = remember { mutableStateListOf<String>() }
    // true = الـ FolderPicker اتفتح من شاشة الإعدادات لطلب نطاق إضافي،
    // false (الافتراضي) = اتفتح لاختيار مكان حفظ عند الرفع العادي.
    var isPickerForScopeRequest by remember { mutableStateOf(false) }

    var selectedFile by remember { mutableStateOf<FileMetadata?>(null) }
    var selectedEntry by remember { mutableStateOf<DirEntry?>(null) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }
    var fileClipboard by remember { mutableStateOf<Pair<DirEntry, Boolean>?>(null) }
    var bulkFileClipboard by remember { mutableStateOf<List<DirEntry>>(emptyList()) }
    var bulkClipboardIsCut by remember { mutableStateOf(false) }
    var isDownloadingFile by remember { mutableStateOf(false) }
    var showContextMenu by remember { mutableStateOf(false) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var chatDraft by remember { mutableStateOf("") }
    var chatMessages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var integrationHealth by remember { mutableStateOf<IntegrationHealthResponse?>(null) }
    var integrationHealthLoading by remember { mutableStateOf(false) }
    var activityEntries by remember { mutableStateOf<List<ActivityEntry>>(emptyList()) }
    var activityLoading by remember { mutableStateOf(false) }
    var activityError by remember { mutableStateOf<String?>(null) }
    var activityRequestGeneration by remember { mutableStateOf(0) }
    var conflicts by remember { mutableStateOf<List<ConflictEntry>>(emptyList()) }
    var conflictsLoading by remember { mutableStateOf(false) }
    var fileVersions by remember { mutableStateOf<List<FileVersionEntry>>(emptyList()) }
    var fileVersionsLoading by remember { mutableStateOf(false) }
    var actionableError by remember { mutableStateOf<DeskTwinUserError?>(null) }
    fun showUserError(error: DeskTwinUserError) {
        actionableError = error
        downloadError = error.message
    }
    fun showSuccess(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
    // أي مسار قديم ما زال يكتب نصًا في downloadError يدخل تلقائيًا
    // لنفس طبقة العرض الموحّدة بدل ظهور رسالة مختصرة بلا حل.
    LaunchedEffect(downloadError) {
        if (downloadError != null && actionableError == null) {
            actionableError = DeskTwinErrorPresenter.fromThrowable(
                IllegalStateException(downloadError!!)
            )
        }
    }
    var permanentSaveConfirmation by remember { mutableStateOf<String?>(null) }
    var scopeRequestConfirmation by remember { mutableStateOf<Boolean?>(null) }
    // قائمة كل الملفات المؤقتة اللي اتحملت خلال الجلسة الحالية - مش ملف
    // واحد بس، عشان لو المستخدم فتح أكتر من ملف مؤقت من غير قفل التطبيق،
    // كلهم يتمسحوا صح عند القفل، مش بس آخر واحد.
    val temporaryFilesToCleanup = remember { mutableStateListOf<java.io.File>() }

    // الموافقة ليست تنفيذًا بحد ذاتها: بعد تسجيل الطلب لازم ننتظر قرار
    // الكمبيوتر، وإلا كان التطبيق يحذف الطلب من طابوره من غير ما يعرف هل
    // نُفّذ فعلاً أم رُفض.
    suspend fun waitForApproval(token: String, approvalId: Int): Boolean {
        repeat(60) {
            val response = currentApi()?.getApprovalStatus(token, approvalId)
            val status = response?.body()?.status
            if (response?.isSuccessful == true && status != "pending") {
                return status == "approved" || status == "executed"
            }
            kotlinx.coroutines.delay(1000.milliseconds)
        }
        return false
    }

    // ---------- مسح الملفات المؤقتة فعليًا عند قفل/تجميد التطبيق ----------
    // "التحميل المؤقت" معناه حرفيًا: الملف موجود بس طول ما التطبيق شغال.
    // ON_STOP بيتنادى لما الـ Activity تروح الخلفية بالكامل (المستخدم قفل
    // التطبيق، بدّل لتطبيق تاني، أو قفل الشاشة) - دي اللحظة الصح للحذف،
    // مش onDestroy (اللي ممكن ميتنادوش في حالات كتير زي rotation بسيط).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                temporaryFilesToCleanup.forEach { file -> DownloadManager.deleteTemporaryFile(file) }
                temporaryFilesToCleanup.clear()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var searchResults by remember { mutableStateOf<List<FileMetadata>>(emptyList()) }
    var isSearching2 by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var searchRequestSequence by remember { mutableStateOf(0) }
    var searchQuery2 by remember { mutableStateOf("") }
    var searchError by remember { mutableStateOf<String?>(null) }

    var pendingUploadMode by remember { mutableStateOf<UploadMode?>(null) }
    var queuedUploads by remember { mutableStateOf<List<QueuedUpload>>(emptyList()) }
    // نسبة تقدّم كل رفع شغال دلوقتي - المفتاح هو id بتاع QueuedUpload.
    // Map عادي مش state واحدة لكل عنصر، عشان عدد الرفعات متغير ومش
    // معروف مسبقًا، ومعظمهم مش شغالين في نفس اللحظة أصلًا.
    var uploadProgress by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    var uploadEtaLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var uploadSpeedLabels by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // نفس الفكرة للتحميل من الكمبيوتر - مفتاح واحد بس لأن تحميل واحد
    // بيحصل في نفس اللحظة عمليًا (المستخدم بيدوس تحميل ملف واحد كل مرة).
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    // ملفات كبيرة اتقطع رفعها قبل الاكتمال - بنعرضها للمستخدم أول ما
    // نتصل بنجاح، عشان يعرف إن فيه رفع ناقص محتاج يكمل (مش لازم يعمل
    // حاجة يدويًا - الرفع هيكمل تلقائيًا لو الملف لسه في طابور الرفع،
    // ده بس تنبيه/شفافية).
    var incompleteTransfers by remember { mutableStateOf<List<IncompleteTransfer>>(emptyList()) }
    // نتيجة آخر مقارنة شجرة (tree-diff) بعد إعادة الاتصال - null يعني
    // لسه مفيش مقارنة حصلت أو المستخدم خلص المراجعة وقفلها.
    var pendingTreeDiff by remember { mutableStateOf<TreeDiffResponse?>(null) }
    // الأوامر المؤجلة المتراكمة وأنت أوفلاين - بتتجاب من التخزين المحلي
    // (مش شبكة) فور الاتصال، عشان تتعرض جنب الـ diff في نفس شاشة المراجعة.
    var pendingOfflineCommands by remember { mutableStateOf<List<QueuedOfflineCommand>>(emptyList()) }
    // نتيجة تنفيذ دفعة أوامر مؤجلة (بعد الموافقة والإرسال) - بيحتوي حالة
    // كل أمر (نجح/تعارض) عشان نعرضها للمستخدم ونسأله يقرر لو فيه تعارض.
    var activeBatchResult by remember { mutableStateOf<BatchExecutionResult?>(null) }
    var isSubmittingBatch by remember { mutableStateOf(false) }
    // ملخص محتوى كل فولدر (لشكل أيقونة الفولدر) - بيتبني من الشجرة المحفوظة
    // مرة واحدة وبيتحدث كل ما الشجرة تتغير (أول مزامنة، أو بعد تطبيق diff).
    var folderContents by remember { mutableStateOf<Map<String, com.desktwin.app.ui.icons.FolderContent>>(emptyMap()) }
    var uploadTargetFolder by remember { mutableStateOf("") }
    var trashItems by remember { mutableStateOf<List<TrashItem>>(emptyList()) }

    var boundUserName by remember { mutableStateOf<String?>(null) }
    var isSavingUserName by remember { mutableStateOf(false) }

    var hasStoragePermission by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> hasStoragePermission = results.values.all { it } }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    var notificationPermissionRequested by remember { mutableStateOf(false) }
    val activeConnectionToken = (connectionState as? ConnectionState.Connected)?.token
    LaunchedEffect(activeConnectionToken) {
        if (activeConnectionToken != null && !notificationPermissionRequested &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionRequested = true
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val mode = pendingUploadMode
        if (uri != null && mode != null) {
            scope.launch {
                val cacheFile = UploadManager.copyUriToCacheFile(context, uri)
                if (cacheFile != null) {
                    uploadQueue.add(QueuedUpload(
                        id = System.currentTimeMillis().toString() + "_" + cacheFile.name,
                        localFilePath = cacheFile.absolutePath,
                        fileName = cacheFile.name,
                        sizeBytes = cacheFile.length(),
                        targetFolder = uploadTargetFolder,
                        deleteAfterUpload = (mode == UploadMode.COPY_AND_DELETE),
                        addedAt = System.currentTimeMillis(),
                        setAsWallpaper = (mode == UploadMode.SET_AS_WALLPAPER)
                    ))
                }
            }
        }
        pendingUploadMode = null
    }

    val phoneTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { PhoneFileManager.setTreeUri(it) } }

    val introVideoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            scope.launch { preferences.saveIntroVideoUri(uri.toString()) }
        }
    }

    fun loadHome(token: String) {
        scope.launch {
            val api = currentApi() ?: return@launch
            try {
                val response = api.getHome(token)
                if (response.isSuccessful && response.body() != null) {
                    homeData = response.body()
                    uploadTargetFolder = response.body()!!.home_path
                    localCache.saveHomeSnapshot(response.body()!!)
                }
            } catch (_: Exception) {
                homeData = localCache.getHomeSnapshot()
            }
        }
    }

    /**
     * تصفح أوفلاين من أحدث قائمة محفوظة للمجلد، ثم من شجرة metadata للأماكن
     * التي لم تُفتح مباشرة من قبل. القائمة الفارغة المحفوظة تظل نتيجة صالحة.
     */
    suspend fun browseFromMirror(path: String): BrowseResponse? {
        val children = treeMirrorStore.getChildrenOf(path)
        if (children.isEmpty() && !treeMirrorStore.hasFolderListing(path)) return null
        val parent = path.trimEnd('\\', '/').let { p ->
            val i = maxOf(p.lastIndexOf('\\'), p.lastIndexOf('/'))
            if (i > 0) p.substring(0, i) else null
        }
        return BrowseResponse(
            current_path = path,
            parent_path = parent,
            entries = children
                .sortedWith(compareByDescending<TreeEntry> { it.is_folder }.thenBy { it.name.lowercase() })
                .map {
                    DirEntry(
                        name = it.name, path = it.path, is_folder = it.is_folder,
                        size_bytes = it.size_bytes, modified_at = it.modified_at,
                        extension = it.extension, file_id = it.file_id
                    )
                }
        )
    }

    fun loadBrowse(token: String, path: String) {
        val generation = browseRequestGeneration + 1
        browseRequestGeneration = generation
        // Keep only the newest requested folder/refresh. The active request is
        // allowed to finish, but its result is discarded if a newer request
        // arrived while it was in flight.
        pendingBrowseRequest = BrowseLoadRequest(generation, token, path)
        isLoadingBrowse = true

        if (browseLoadWorker?.isActive == true) return

        browseLoadWorker = scope.launch {
            try {
                while (true) {
                    val request = pendingBrowseRequest ?: break
                    pendingBrowseRequest = null

                    // Empty token means offline navigation: resolve from the
                    // mirror/cache without issuing a request that must fail.
                    val api = if (request.token.isNotEmpty()) currentApi() else null
                    var result: BrowseResponse? = null
                    var loadedFromServer = false

                    if (api != null) {
                        try {
                            val response = api.browsePath(request.token, request.path)
                            if (response.isSuccessful) {
                                result = response.body()
                                loadedFromServer = result != null
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // Fall back to the local mirror/cache below. A
                            // later periodic/manual request will retry server.
                        }
                    }

                    if (!loadedFromServer) {
                        result = try {
                            val mirrorListing = browseFromMirror(request.path)
                            val perFolderCache = localCache.getBrowseSnapshot(request.path)
                            if (treeMirrorStore.hasFolderListing(request.path)) {
                                mirrorListing ?: perFolderCache
                            } else {
                                perFolderCache ?: mirrorListing
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            null
                        }
                    }

                    if (loadedFromServer && result != null && request.generation == browseRequestGeneration) {
                        // Persist the exact listing observed online into both
                        // caches. Replacing the subtree removes deleted or
                        // moved items rather than only refreshing its timestamp.
                        try {
                            val listing = result!!
                            localCache.saveBrowseSnapshot(request.path, listing)
                            treeMirrorStore.replaceFolderContents(
                                request.path,
                                listing.entries.map { entry ->
                                    TreeEntry(
                                        path = entry.path,
                                        name = entry.name,
                                        is_folder = entry.is_folder,
                                        size_bytes = entry.size_bytes,
                                        modified_at = entry.modified_at,
                                        extension = entry.extension,
                                        file_id = entry.file_id
                                    )
                                }
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            // A cache write must not hide a successful listing.
                        }
                    }

                    // Do not let a slow response for an old path overwrite a
                    // newer navigation/refresh. A newer request remains
                    // pending and is drained by this same worker.
                    if (request.generation == browseRequestGeneration && result != null) {
                        browseData = result
                    }
                }
            } finally {
                browseLoadWorker = null
                isLoadingBrowse = false
            }
        }
    }


    fun navigateToFolder(path: String) {
        val connected = connectionState as? ConnectionState.Connected
        pathHistory.add(currentPath)
        currentPath = path
        isAtRoot = false
        // أونلاين: طلب حقيقي. أوفلاين: نفس الدالة بترجع للكاش ثم للشجرة
        // المحفوظة (loadBrowse بتتعامل مع الحالتين) بدل ما نسيب محتوى الفولدر القديم.
        loadBrowse(connected?.token ?: "", path)
    }

    fun navigateBackInBrowser() {
        if (pathHistory.isEmpty()) {
            isAtRoot = true
            currentPath = ""
            return
        }
        val previous = pathHistory.removeAt(pathHistory.size - 1)
        if (previous.isEmpty()) {
            isAtRoot = true
            currentPath = ""
        } else {
            currentPath = previous
            val connected = connectionState as? ConnectionState.Connected
            if (connected != null) loadBrowse(connected.token, previous)
        }
    }

    // Android system Back follows file navigation first; at the file root,
    // the existing screen-stack handler (or Android) handles Back normally.
    BackHandler(enabled = screen is Screen.FileBrowser && !isAtRoot) {
        navigateBackInBrowser()
    }

    fun loadPickerBrowse(token: String, path: String) {
        scope.launch {
            val api = currentApi() ?: return@launch
            try {
                val response = api.browsePath(token, path)
                if (response.isSuccessful) pickerBrowseData = response.body()
            } catch (_: Exception) { }
        }
    }

    fun navigateToPickerFolder(path: String) {
        val connected = connectionState as? ConnectionState.Connected
        pickerPathHistory.add(pickerCurrentPath)
        pickerCurrentPath = path
        pickerIsAtRoot = false
        if (connected != null) loadPickerBrowse(connected.token, path)
    }

    fun navigateBackInPicker(): Boolean {
        if (pickerPathHistory.isEmpty()) {
            if (!pickerIsAtRoot) { pickerIsAtRoot = true; pickerCurrentPath = ""; return true }
            return false
        }
        val previous = pickerPathHistory.removeAt(pickerPathHistory.size - 1)
        if (previous.isEmpty()) {
            pickerIsAtRoot = true
            pickerCurrentPath = ""
        } else {
            pickerCurrentPath = previous
            val connected = connectionState as? ConnectionState.Connected
            if (connected != null) loadPickerBrowse(connected.token, previous)
        }
        return true
    }

    fun afterSuccessfulConnection(token: String, ip: String, port: Int, computerName: String) {
        connectionState = ConnectionState.Connected(computerName, token)
        loadHome(token)

        scope.launch {
            profileStore.save(ComputerProfile(token, computerName, ip, port, boundUserName))
        }

        scope.launch {
            val api = DeskTwinConnection.connect(ip, port)
            try {
                val userResponse = api.getBoundUser(token)
                val existingUser = userResponse.body()?.user_name
                if (existingUser.isNullOrEmpty()) {
                    pushScreen(Screen.UserBinding)
                } else {
                    boundUserName = existingUser
                    replaceStackWith(Screen.FileBrowser())
                }
            } catch (_: Exception) {
                replaceStackWith(Screen.FileBrowser())
            }
        }
    }

    LaunchedEffect(Unit) {
        // الخطوة 1: الشاشة تتحمّل من الكاش المحلي فورًا - بغض النظر عن
        // وجود اتصال أو لأ. المستخدم شايف ملفاته على طول من غير ما يستنى
        // أي رد من الشبكة.
        homeData = localCache.getHomeSnapshot()

        val savedProfile = profileStore.getCurrent() ?: return@LaunchedEffect

        selectedDevice = DiscoveredDevice(savedProfile.computerName, savedProfile.lastKnownIp, savedProfile.lastKnownPort)
        boundUserName = savedProfile.boundUserName
        DeskTwinConnection.configurePinnedCertificateFingerprint(preferences.certificateFingerprint.first())
        // Keep the existing computer profile and offline tree, but never
        // silently reuse its bearer token after app restart. A user must
        // explicitly re-authorize through the computer's pairing code/QR.
    }

    LaunchedEffect(connectionState, selectedDevice?.ip, selectedDevice?.port) {
        val connected = connectionState as? ConnectionState.Connected ?: return@LaunchedEffect
        val device = selectedDevice
        try {
            // تبقى قناة المحادثة مفتوحة طوال الاتصال، لا فقط أثناء فتح صفحة
            // الدردشة، حتى تصل رسائل الكمبيوتر للتليفون مثل تطبيقات المحادثة.
            if (device != null) {
                ManualChatManager.onManualTextReceived = { text, messageId ->
                    scope.launch {
                        if (chatMessages.none { it.id == messageId }) {
                            chatMessages = chatMessages + ChatMessage(messageId, text, fromPhone = false, status = "تم الاستلام")
                            com.desktwin.app.notifications.ChatNotifications.showIncoming(
                                context.applicationContext, "الكمبيوتر", text, messageId
                            )
                        }
                    }
                }
                ManualChatManager.startManualChat(device.ip, device.port, connected.token)
                PhoneFilesSocketManager.start(device.ip, device.port, connected.token)
            }

            try {
                val commands = commandQueue.getForComputer(connected.token)
                commands.forEach { command ->
                    scope.launch {
                        val api = currentApi() ?: return@launch
                        try {
                            when (command.type) {
                                CommandType.DELETE_FILE -> {
                                    val approval = api.requestApproval(connected.token, ApprovalRequest(
                                        action_type = "delete_file", file_id = command.fileId, file_path = command.filePath
                                    ))
                                    if (approval.isSuccessful && approval.body()?.let { waitForApproval(connected.token, it.approval_id) } != true) return@launch
                                }
                                CommandType.RENAME_FILE -> {
                                    val approval = api.requestApproval(connected.token, ApprovalRequest(
                                        action_type = "rename_file", file_id = command.fileId,
                                        file_path = command.filePath, new_value = command.newValue
                                    ))
                                    if (approval.isSuccessful && approval.body()?.let { waitForApproval(connected.token, it.approval_id) } != true) return@launch
                                }
                                CommandType.SET_AVAILABILITY -> {
                                    command.fileId?.let { fid ->
                                        api.setFileAvailability(connected.token, fid, FileAvailabilityRequest(command.newValue ?: "temporary"))
                                    }
                                }
                            }
                            commandQueue.remove(command.id)
                        } catch (_: Exception) {
                            // هيتعاد المحاولة تاني في الاتصال الجاي
                        }
                    }
                }
            } catch (_: Exception) {
                // إعادة الاتصال القادمة تعيد محاولة الأوامر، ولا توقف مزامنة الحافظة.
            }
            kotlinx.coroutines.awaitCancellation()
        } finally {
            ManualChatManager.stop()
            PhoneFilesSocketManager.stop()
        }
    }

    // ---------- Auto-refresh دوري (القسم 9 - Live-Refresh) ----------
    // مطلوب إحساس "Live" فعلي زي أي تطبيق عادي، بالإضافة لـ pull-to-refresh
    // اليدوي الموجود بالفعل. بما إن الاتصال LAN محلي بحت، فاصل 5 ثواني
    // آمن (مفيش بينج ملحوظ ولا حمل شبكة حقيقي عند هذا المعدل لملف واحد
    // JSON خفيف زي /browse أو /home - مش نقل ملفات).
    //
    // شروط التشغيل:
    // - الاتصال لازم يكون Connected فعليًا (لا داعي نحاول على الكاش).
    // - الشاشة الظاهرة حاليًا لازم تكون FileBrowser تحديدًا (مش أي شاشة
    //   تانية زي Settings أو Upload) - مفيش داعي نطلب من السيرفر كل 5
    //   ثواني وإحنا مش حتى عارضين نتيجة الطلب.
    // - أثناء أي تحميل يدوي شغال بالفعل (isLoadingBrowse من pull-to-refresh
    //   أو تنقل)، نتخطى الدورة دي بدل ما نكرر نفس الطلب فوق بعضه.
    LaunchedEffect(connectionState, screen) {
        val connected = connectionState as? ConnectionState.Connected ?: return@LaunchedEffect
        if (screen !is Screen.FileBrowser) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(10.seconds)
            // إعادة التحقق داخل الحلقة: لو الاتصال قطع أو المستخدم غيّر
            // الشاشة أثناء الانتظار، اللوب هيتلغي تلقائيًا لأن الـ
            // LaunchedEffect بيتعاد تشغيله من جديد بمفتاح مختلف (connectionState
            // أو screen) - مفيش حاجة زيادة مطلوبة هنا غير الشرط الأساسي.
            if (!isLoadingBrowse) {
                if (isAtRoot) loadHome(connected.token) else loadBrowse(connected.token, currentPath)
            }
        }
    }

    // فحص مرة واحدة لكل اتصال ناجح: هل فيه ملفات كبيرة اتقطع رفعها من
    // قبل؟ بس تنبيه/شفافية للمستخدم - مفيش أي أمر يدوي مطلوب لإكمالها،
    // الرفع نفسه هيكمل تلقائيًا لو الملف لسه في طابور الرفع المحلي.
    LaunchedEffect(connectionState) {
        val connected = connectionState as? ConnectionState.Connected ?: return@LaunchedEffect
        val api = currentApi() ?: return@LaunchedEffect
        try {
            val response = api.listIncompleteResumableTransfers(connected.token)
            if (response.isSuccessful) {
                incompleteTransfers = response.body() ?: emptyList()
            }
        } catch (_: Exception) {
            // مفيش اتصال أو فشل الطلب - نتجاهل بصمت، ده تنبيه ثانوي
            // مش أساسي لعمل التطبيق.
        }
    }

    // عند الاتصال نقارن مع آخر baseline وافق عليه المستخدم، ونحفظ أحدث
    // نسخة metadata كاملة للعرض أوفلاين. نحدّث المرآة كل خمس دقائق أثناء
    // الاتصال، مع إبقاء baseline منفصلًا حتى لا تختفي تغييرات المراجعة.
    LaunchedEffect(connectionState) {
        val connected = connectionState as? ConnectionState.Connected ?: return@LaunchedEffect
        val api = currentApi() ?: return@LaunchedEffect
        treeMirrorStore.ensureSyncBaseline()
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var compareForReview = true
            while (currentCoroutineContext().isActive) {
                val queuedCommands = offlineCommandQueue.getAll()
                try {
                    val syncBaseline = treeMirrorStore.getSyncBaseline()
                    if (!treeMirrorStore.hasSyncBaseline()) {
                        // أول مرة: snapshot كامل كنقطة بداية للمقارنة والتصفح.
                        val fullResponse = api.getFullTreeSnapshot(connected.token)
                        if (fullResponse.isSuccessful && fullResponse.body() != null) {
                            val entries = fullResponse.body()!!
                            treeMirrorStore.saveFullSnapshot(entries, initializeSyncBaseline = true)
                            queuedCommands.sortedBy { it.addedAt }.forEach { command ->
                                when (command.commandType) {
                                    "delete" -> treeMirrorStore.applyOptimisticDelete(command.filePath)
                                    "rename" -> command.newValue?.let { treeMirrorStore.applyOptimisticRename(command.filePath, it) }
                                }
                            }
                        }
                        if (queuedCommands.isNotEmpty()) {
                            pendingOfflineCommands = queuedCommands
                        }
                    } else {
                        if (compareForReview) {
                            val snapshotEntries = syncBaseline.map {
                                TreeSnapshotEntry(it.path, it.is_folder, it.size_bytes, it.modified_at ?: "")
                            }
                            val diffResponse = api.computeTreeDiff(connected.token, TreeDiffRequest(snapshotEntries))
                            if (diffResponse.isSuccessful) {
                                val diff = diffResponse.body()
                                val hasChanges = diff != null && (diff.added.isNotEmpty() || diff.removed.isNotEmpty() || diff.modified.isNotEmpty())
                                if ((hasChanges || queuedCommands.isNotEmpty()) && pendingTreeDiff == null && pendingOfflineCommands.isEmpty()) {
                                    pendingTreeDiff = diff
                                    pendingOfflineCommands = queuedCommands
                                }
                            }
                        }

                        // Always refresh the visible offline tree from the latest
                        // server observation; review approval remains tracked by
                        // the independent sync baseline above.
                        val fullResponse = api.getFullTreeSnapshot(connected.token)
                        if (fullResponse.isSuccessful && fullResponse.body() != null) {
                            val entries = fullResponse.body()!!
                            treeMirrorStore.saveFullSnapshot(entries)
                            queuedCommands.sortedBy { it.addedAt }.forEach { command ->
                                when (command.commandType) {
                                    "delete" -> treeMirrorStore.applyOptimisticDelete(command.filePath)
                                    "rename" -> command.newValue?.let { treeMirrorStore.applyOptimisticRename(command.filePath, it) }
                                }
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Keep the last complete snapshot; retry on the next interval.
                }

                compareForReview = false
                kotlinx.coroutines.delay(5 * 60 * 1000L)
            }
        }
    }

    // أيقونات الفولدرات: نعيد حساب الخريطة كل ما الشجرة المحفوظة تتحدث.
    // lastSyncedAt بيتغير مع كل saveSnapshot، فده أنسب مؤشر لإعادة البناء.
    val treeSyncedAt by treeMirrorStore.lastSyncedAt.collectAsState(initial = null)
    LaunchedEffect(treeSyncedAt) {
        folderContents = com.desktwin.app.ui.icons.FolderContentIndex.build(treeMirrorStore.getSnapshot())
    }

    // افتح المراجعة من شاشة الملفات فقط؛ لا تقاطع شاشة نشطة مثل مشغل الفيديو
    // إذا وصلت تغييرات أثناء استخدام المستخدم لمسار آخر.
    LaunchedEffect(pendingTreeDiff, pendingOfflineCommands, screen) {
        val hasPending = pendingTreeDiff != null || pendingOfflineCommands.isNotEmpty()
        if (hasPending && screen is Screen.FileBrowser) {
            pushScreen(Screen.SyncReview)
        }
    }

    LaunchedEffect(connectionState) {
        uploadQueue.queue.collect { list ->
            queuedUploads = list
            val connected = connectionState as? ConnectionState.Connected
            if (connected != null && list.isNotEmpty()) {
                list.forEach { item ->
                    if (item.paused) return@forEach
                    uploadJobs[item.id] = scope.launch {
                        val api = currentFileTransferApi() ?: return@launch
                        // بنقيس السرعة الفعلية بمقارنة الوقت والحجم بين
                        // نداءين متتاليين لـ onProgress - مش رقم افتراضي
                        // أو ثابت، عشان التقدير يتغيّر مع سرعة الشبكة
                        // الحقيقية لحظة بلحظة.
                        var lastSampleTime = System.currentTimeMillis()
                        var lastSampleBytes = 0L
                        val localFile = java.io.File(item.localFilePath)
                        val progressCallback: (Long, Long) -> Unit = { sent, total ->
                            val fraction = if (total > 0) sent.toFloat() / total.toFloat() else 0f
                            uploadProgress = uploadProgress + (item.id to fraction)
                            com.desktwin.app.notifications.TransferNotifications.updateProgress(
                                context, item.id, item.fileName, isUpload = true, fraction = fraction
                            )

                            val now = System.currentTimeMillis()
                            val elapsedMs = now - lastSampleTime
                            if (elapsedMs >= 500) {  // نحدّث التقدير كل نص ثانية بس - مش كل جزء صغير
                                val bytesInInterval = sent - lastSampleBytes
                                val bytesPerSecond = bytesInInterval * 1000 / elapsedMs
                                if (bytesPerSecond > 0) {
                                    val remainingBytes = total - sent
                                    val remainingSeconds = remainingBytes / bytesPerSecond
                                    uploadEtaLabels = uploadEtaLabels + (item.id to formatEtaLabel(remainingSeconds))
                                    uploadSpeedLabels = uploadSpeedLabels + (item.id to formatTransferSpeed(bytesPerSecond))
                                }
                                lastSampleTime = now
                                lastSampleBytes = sent
                            }
                        }

                        // ملفات ≥1GB بتاخد مسار الرفع القابل للاستكمال
                        // (negotiate -> بعت الناقص بس -> complete) - لو
                        // الاتصال اتقطع في النص، المرة الجاية هيكمل من نفس
                        // النقطة تلقائيًا بدل ما يبدأ الرفع كله من الصفر.
                        val outcome = if (FileFingerprint.isLargeFile(localFile.length())) {
                            UploadManager.uploadLargeFileResumable(
                                api, connected.token, localFile, item.targetFolder,
                                onProgress = progressCallback
                            )
                        } else {
                            UploadManager.uploadToComputer(
                                api, connected.token, localFile, item.targetFolder,
                                onProgress = progressCallback
                            )
                        }

                        when (outcome) {
                            is UploadOutcome.Success -> {
                                if (item.setAsWallpaper) {
                                    try {
                                        currentApi()?.setWallpaper(connected.token, outcome.response.file_id)
                                    } catch (_: Exception) {
                                        downloadError = "تم الرفع لكن تعذر تعيين الخلفية"
                                    }
                                }
                                uploadQueue.remove(item.id)
                                uploadProgress = uploadProgress - item.id
                                uploadEtaLabels = uploadEtaLabels - item.id
                                uploadSpeedLabels = uploadSpeedLabels - item.id
                                com.desktwin.app.notifications.TransferNotifications.completeSuccess(
                                    context, item.id, item.fileName, isUpload = true
                                )
                                if (item.deleteAfterUpload) localFile.delete()
                                uploadJobs.remove(item.id)
                            }
                            is UploadOutcome.PendingApproval -> { }
                            is UploadOutcome.Failed -> {
                                uploadProgress = uploadProgress - item.id
                                uploadEtaLabels = uploadEtaLabels - item.id
                                uploadSpeedLabels = uploadSpeedLabels - item.id
                                com.desktwin.app.notifications.TransferNotifications.completeFailure(
                                    context, item.id, item.fileName, isUpload = true
                                )
                                uploadJobs.remove(item.id)
                            }
                        }
                    }
                }
            }
        }
    }

    fun startDiscovery() {
        discoveryJob?.cancel()
        isSearching = true
        discoveredDevices = emptyList()
        discoveryJob = scope.launch {
            try {
                withTimeoutOrNull(5_000L) {
                    discovery.discover().collect { device ->
                        if (discoveredDevices.none { it.ip == device.ip }) {
                            discoveredDevices = discoveredDevices + device
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                Log.w("DeskTwinDiscovery", "Discovery ended with an error", error)
                if (scannedPairingCode != null) {
                    pairingError = "تمت قراءة QR لكن تعذر البحث عن الكمبيوتر. تأكد أنه يعمل على الشبكة نفسها ثم أعد المحاولة."
                }
            } finally {
                if (currentCoroutineContext().isActive) {
                    isSearching = false
                    // QR carries the short-lived pairing code, not an IP address.
                    // Give mDNS a bounded window to resolve the matching computer;
                    // if several are found, keep the code and let the user choose.
                    if (scannedPairingCode != null && screen is Screen.Discovery && selectedDevice == null) {
                        when (discoveredDevices.size) {
                            1 -> {
                                selectedDevice = discoveredDevices.single()
                                pairingError = null
                                pushScreen(Screen.Pairing)
                            }
                            0 -> pairingError = "تمت قراءة الكود، لكن لم يظهر كمبيوتر DeskTwin. تأكد من تشغيل الخدمة وعلى نفس الشبكة ثم اضغط إعادة البحث."
                            else -> pairingError = "تمت قراءة الكود. اختر الكمبيوتر المقصود من القائمة لإكمال الاقتران."
                        }
                    }
                }
            }
        }
    }

    fun scanPairingQr() {
        if (isQrScannerPreparing) return
        isQrScannerPreparing = true
        pairingError = null
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
        val scanner = GmsBarcodeScanning.getClient(context, options)

        fun scanAfterModuleReady() {
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    isQrScannerPreparing = false
                    val payload = parsePairingQr(barcode.rawValue)
                    val device = selectedDevice ?: discoveredDevices.singleOrNull()
                    if (payload != null) {
                        val orderedEndpoints = prioritizePairingEndpoints(context, payload.endpoints)
                        scannedPairingCode = payload.code
                        scannedPairingDevices = orderedEndpoints
                        scannedPairingFingerprint = payload.certificateFingerprint
                        pairingScanAttempt += 1
                        pairingError = null
                        if (orderedEndpoints.isNotEmpty()) {
                            // QR endpoint lists come from the computer's advertised
                            // interfaces; pairing tries each, without waiting for mDNS.
                            discoveryJob?.cancel()
                            isSearching = false
                            selectedDevice = orderedEndpoints.first()
                            if (screen !is Screen.Pairing) pushScreen(Screen.Pairing)
                        } else if (device != null) {
                            selectedDevice = device
                            if (screen !is Screen.Pairing) pushScreen(Screen.Pairing)
                        } else {
                            pairingError = "تمت قراءة الكود؛ جاري البحث عن الكمبيوتر على الشبكة…"
                            startDiscovery()
                        }
                    } else {
                        scannedPairingCode = null
                        scannedPairingDevices = emptyList()
                        scannedPairingFingerprint = null
                        pairingError = "QR غير صالح أو قديم. امسح QR جديدًا من برنامج DeskTwin على الكمبيوتر ثم حاول مرة أخرى."
                    }
                }
                .addOnCanceledListener {
                    isQrScannerPreparing = false
                }
                .addOnFailureListener { error ->
                    isQrScannerPreparing = false
                    Log.e("DeskTwinQr", "Google Code Scanner failed", error)
                    val status = (error as? ApiException)?.statusCode?.let { "GMS $it" }
                    pairingError = "تعذر تشغيل ماسح QR${status?.let { " ($it)" } ?: ""}. أدخل عنوان الكمبيوتر والكود يدويًا، أو أعد المحاولة بعد اكتمال تحديث Google Play services."
                }
        }

        val moduleInstaller = ModuleInstall.getClient(context)
        moduleInstaller.areModulesAvailable(scanner)
            .addOnSuccessListener { availability ->
                if (availability.areModulesAvailable()) {
                    scanAfterModuleReady()
                } else {
                    pairingError = "جارٍ تجهيز ماسح QR من Google Play services…"
                    val scanStarted = AtomicBoolean(false)
                    val installListener = object : InstallStatusListener {
                        override fun onInstallStatusUpdated(update: ModuleInstallStatusUpdate) {
                            when (update.installState) {
                                ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED -> {
                                    if (scanStarted.compareAndSet(false, true)) scanAfterModuleReady()
                                }
                                ModuleInstallStatusUpdate.InstallState.STATE_FAILED,
                                ModuleInstallStatusUpdate.InstallState.STATE_CANCELED -> {
                                    isQrScannerPreparing = false
                                    pairingError = "تعذر تجهيز وحدة QR من Google Play services. استخدم إدخال عنوان الكمبيوتر والكود يدويًا ثم أعد المحاولة."
                                }
                            }
                        }
                    }
                    val installRequest = ModuleInstallRequest.newBuilder()
                        .addApi(scanner)
                        .setListener(installListener)
                        .build()
                    moduleInstaller.installModules(installRequest)
                        .addOnSuccessListener { response ->
                            if (response.areModulesAlreadyInstalled() && scanStarted.compareAndSet(false, true)) {
                                scanAfterModuleReady()
                            }
                        }
                        .addOnFailureListener { error ->
                            isQrScannerPreparing = false
                            Log.e("DeskTwinQr", "Google Code Scanner module install failed", error)
                            val status = (error as? ApiException)?.statusCode?.let { "GMS $it" }
                            pairingError = "فشل تجهيز ماسح QR${status?.let { " ($it)" } ?: ""}. استخدم إدخال عنوان الكمبيوتر والكود يدويًا."
                        }
                }
            }
            .addOnFailureListener { error ->
                // Try the scanner once even if availability checking failed; startScan
                // may still succeed and the failure handler records its real status.
                Log.w("DeskTwinQr", "Could not check scanner module availability", error)
                scanAfterModuleReady()
            }
    }

    suspend fun refreshActivity(token: String) {
        val generation = activityRequestGeneration + 1
        activityRequestGeneration = generation
        activityLoading = true
        activityError = null
        try {
            val response = currentApi()?.getActivity(token)
            if (generation != activityRequestGeneration) return
            if (response?.isSuccessful == true) {
                activityEntries = response.body().orEmpty()
            } else {
                activityError = if (response == null) {
                    "لم يصل رد من الكمبيوتر. تحقق من الاتصال ثم أعد المحاولة."
                } else {
                    "تعذر تحميل سجل النشاط (HTTP ${response.code()}). أعد المحاولة."
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == activityRequestGeneration) {
                activityError = "تعذر الاتصال لتحميل سجل النشاط: ${error.message ?: "خطأ غير معروف"}"
            }
        } finally {
            if (generation == activityRequestGeneration) activityLoading = false
        }
    }

    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val selectedMainTab = when (screen) {
        is Screen.FileBrowser -> DeskTwinMainTab.FILES
        is Screen.Upload -> DeskTwinMainTab.TRANSFERS
        is Screen.Chat -> DeskTwinMainTab.REMOTE
        is Screen.Settings -> DeskTwinMainTab.SETTINGS
        else -> null
    }
    Column(Modifier.fillMaxSize()) {
    AnimatedContent(
        targetState = screen,
        modifier = Modifier.weight(1f),
        transitionSpec = {
            val preset = deskTwinThemePreset(selectedThemePreset)
            if (reduceMotion) {
                fadeIn(animationSpec = tween(0)) togetherWith fadeOut(animationSpec = tween(0))
            } else {
            val durationScale = preset.motionScale
            val enterFromRight = pageForward != isRtl
            val enterDuration = (when (preset.artStyle) {
                ThemeArtStyle.ORBIT -> 420
                ThemeArtStyle.CODE -> 185
                ThemeArtStyle.EMBER -> 400
                ThemeArtStyle.ORCHARD -> 300
                ThemeArtStyle.APPLE -> 300
                ThemeArtStyle.LINK -> 260
            } * durationScale).toInt()
            val exitDuration = (enterDuration * 0.78f).toInt()
            val enter = when (preset.artStyle) {
                ThemeArtStyle.ORBIT -> fadeIn(tween(enterDuration, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(enterDuration, easing = FastOutSlowInEasing), initialScale = 0.975f) +
                    slideInVertically(tween(enterDuration, easing = FastOutSlowInEasing)) { it / 42 }
                ThemeArtStyle.CODE -> fadeIn(tween(enterDuration)) +
                    slideInHorizontally(tween(enterDuration)) { width -> (if (enterFromRight) width else -width) / 14 }
                ThemeArtStyle.EMBER -> fadeIn(tween(enterDuration, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(enterDuration, easing = FastOutSlowInEasing), initialScale = 0.99f) +
                    slideInVertically(tween(enterDuration, easing = FastOutSlowInEasing)) { it / 55 }
                ThemeArtStyle.ORCHARD -> fadeIn(tween(enterDuration, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(enterDuration, easing = FastOutSlowInEasing), initialScale = 0.985f)
                ThemeArtStyle.APPLE -> fadeIn(tween(enterDuration, easing = FastOutSlowInEasing)) +
                    scaleIn(tween(enterDuration, easing = FastOutSlowInEasing), initialScale = 0.985f)
                ThemeArtStyle.LINK -> fadeIn(tween(enterDuration, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(enterDuration, easing = FastOutSlowInEasing)) { width -> (if (enterFromRight) width else -width) / 18 } +
                    scaleIn(tween(enterDuration, easing = FastOutSlowInEasing), initialScale = 0.985f)
            }
            val exit = when (preset.artStyle) {
                ThemeArtStyle.ORBIT -> fadeOut(tween(exitDuration, easing = FastOutSlowInEasing)) +
                    scaleOut(tween(exitDuration, easing = FastOutSlowInEasing), targetScale = 1.015f) +
                    slideOutVertically(tween(exitDuration, easing = FastOutSlowInEasing)) { -it / 48 }
                ThemeArtStyle.CODE -> fadeOut(tween(exitDuration)) +
                    slideOutHorizontally(tween(exitDuration)) { width -> (if (enterFromRight) -width else width) / 14 }
                ThemeArtStyle.EMBER -> fadeOut(tween(exitDuration, easing = FastOutSlowInEasing)) +
                    scaleOut(tween(exitDuration, easing = FastOutSlowInEasing), targetScale = 0.995f) +
                    slideOutVertically(tween(exitDuration, easing = FastOutSlowInEasing)) { -it / 60 }
                ThemeArtStyle.ORCHARD -> fadeOut(tween(exitDuration, easing = FastOutSlowInEasing)) +
                    scaleOut(tween(exitDuration, easing = FastOutSlowInEasing), targetScale = 0.995f)
                ThemeArtStyle.APPLE -> fadeOut(tween(exitDuration, easing = FastOutSlowInEasing)) +
                    scaleOut(tween(exitDuration, easing = FastOutSlowInEasing), targetScale = 0.995f)
                ThemeArtStyle.LINK -> fadeOut(tween(exitDuration, easing = FastOutSlowInEasing)) +
                    slideOutHorizontally(tween(exitDuration, easing = FastOutSlowInEasing)) { width -> (if (enterFromRight) -width else width) / 24 } +
                    scaleOut(tween(exitDuration, easing = FastOutSlowInEasing), targetScale = 0.99f)
            }
            enter togetherWith exit
            }
        },
        label = "DeskTwin page transition"
    ) { currentScreen ->
    when (currentScreen) {

        is Screen.Discovery -> {
            LaunchedEffect(Unit) { startDiscovery() }
            DiscoveryScreen(
                discoveredDevices = discoveredDevices,
                isSearching = isSearching,
                scanError = pairingError,
                isQrScannerPreparing = isQrScannerPreparing,
                onDeviceSelected = { device ->
                    selectedDevice = device
                    // An explicit picker choice should override endpoints from a
                    // previous QR attempt if the direct route was unreachable.
                    scannedPairingDevices = emptyList()
                    if (scannedPairingCode != null) pairingScanAttempt += 1
                    pairingError = null
                    pushScreen(Screen.Pairing)
                },
                onRetry = { startDiscovery() },
                onScanQrClick = { scanPairingQr() },
                onManualAddressSubmit = { address ->
                    discoveryJob?.cancel()
                    isSearching = false
                    scannedPairingCode = null
                    scannedPairingDevices = emptyList()
                    selectedDevice = DiscoveredDevice("DeskTwin PC", address, DESKTWIN_PORT)
                    pairingError = null
                    pushScreen(Screen.Pairing)
                }
            )
        }

        is Screen.Pairing -> {
            val device = selectedDevice
            if (device != null) {
                PairingScreen(
                    computerName = device.name,
                    isVerifying = isVerifyingCode,
                    errorMessage = pairingError,
                    diagnostics = pairingDiagnostics,
                    scannedCode = scannedPairingCode,
                    scanAttempt = pairingScanAttempt,
                    isQrScannerPreparing = isQrScannerPreparing,
                    onQrScanClick = { scanPairingQr() },
                    onClearCertificateClick = {
                        scope.launch { preferences.saveCertificateFingerprint(""); DeskTwinConnection.clearPinnedCertificate() }
                    },
                    onBack = { popScreen() },
                    onCodeSubmit = { code, sessionMinutes ->
                        isVerifyingCode = true
                        pairingError = null
                        scope.launch {
                            try {
                                val usingDirectQr = scannedPairingCode == code && scannedPairingDevices.isNotEmpty()
                                val expectedFingerprint = scannedPairingFingerprint
                                    ?.takeIf { scannedPairingCode == code }
                                if (expectedFingerprint == null) {
                                    pairingError = "لأمان الاقتران، امسح QR الحالي من DeskTwin على الكمبيوتر. الإدخال اليدوي للكود وحده لم يعد مدعومًا."
                                    return@launch
                                }
                                val candidates = if (usingDirectQr) scannedPairingDevices else listOf(device)
                                var lastNetworkError: Exception? = null
                                for (candidate in candidates) {
                                    try {
                                        // The QR supplies the certificate identity out of
                                        // band, so the code is never sent over unpinned TLS.
                                        val api = DeskTwinConnection.connectForPairing(
                                            candidate.ip, candidate.port, expectedFingerprint
                                        )
                                        // Diagnostics are informational. Keep their separate HTTP round-trip
                                        // out of the pairing critical path so a slow diagnostics response
                                        // cannot consume the pairing attempt's timeout budget.
                                        pairingDiagnostics = null
                                        val response = api.verifyPairingCode(
                                            VerifyCodeRequest(code = code, device_name = "Android Phone", session_minutes = sessionMinutes)
                                        )
                                        val body = response.body()
                                        val returnedFingerprint = body?.certificate_fingerprint
                                            ?.lowercase()?.replace(":", "")
                                        if (response.isSuccessful && body?.success == true && body.device_token != null
                                            && returnedFingerprint == expectedFingerprint
                                        ) {
                                            selectedDevice = candidate
                                            preferences.saveToken(body.device_token)
                                            body.certificate_fingerprint?.let {
                                                preferences.saveCertificateFingerprint(it)
                                                DeskTwinConnection.configurePinnedCertificateFingerprint(it)
                                            }
                                            preferences.saveLastConnection(candidate.ip, candidate.port, candidate.name)
                                            afterSuccessfulConnection(body.device_token, candidate.ip, candidate.port, candidate.name)
                                        } else {
                                            if (response.isSuccessful && body?.success == true
                                                && returnedFingerprint != expectedFingerprint
                                            ) {
                                                pairingError = "تم رفض الاقتران: بصمة شهادة الكمبيوتر لا تطابق QR الممسوح."
                                                return@launch
                                            }
                                            if (usingDirectQr && response.code() in setOf(404, 405, 501)) {
                                                lastNetworkError = java.io.IOException(
                                                    "QR endpoint returned HTTP ${response.code()}"
                                                )
                                                DeskTwinConnection.disconnect()
                                                continue
                                            }
                                            pairingError = "${body?.error_code ?: "PAIRING_FAILED"}: ${body?.error ?: "فشل التوصيل"}\nالحل: ${body?.solution ?: "تأكد من الكود وأنشئ كودًا جديدًا"}"
                                        }
                                        return@launch
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        lastNetworkError = e
                                        DeskTwinConnection.disconnect()
                                    }
                                }
                                if (usingDirectQr) {
                                    // Network/IP changes can stale a QR endpoint while
                                    // the code remains valid. Keep the scanned code and
                                    // surface the actual network failure for a direct retry.
                                    scannedPairingDevices = emptyList()
                                    selectedDevice = candidates.firstOrNull() ?: device
                                    pairingError = lastNetworkError?.let(::classifyPairingError)
                                        ?: "تعذر الوصول لعناوين QR. تحقق من الشبكة وجدار حماية Windows ثم أعد المحاولة."
                                } else {
                                    pairingError = lastNetworkError?.let(::classifyPairingError)
                                        ?: "لم يصل الهاتف إلى أي عنوان أعلنه الكمبيوتر. تأكد من الشبكة نفسها والجدار الناري."
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e("DeskTwinPairing", "Pairing failed", e)
                                pairingError = classifyPairingError(e)
                            } finally {
                                isVerifyingCode = false
                            }
                        }
                    }
                )
            }
        }

        is Screen.UserBinding -> {
            val connected = connectionState as? ConnectionState.Connected
            UserBindingScreen(
                computerName = connected?.computerName ?: "الكمبيوتر",
                isSaving = isSavingUserName,
                onNameSubmit = { name ->
                    val token = connected?.token
                    if (token != null) {
                        isSavingUserName = true
                        scope.launch {
                            val api = currentApi()
                            try { api?.bindUser(token, BindUserRequest(name)) } catch (_: Exception) { }
                            boundUserName = name
                            selectedDevice?.let { d -> profileStore.save(ComputerProfile(token, d.name, d.ip, d.port, name)) }
                            isSavingUserName = false
                            replaceStackWith(Screen.FileBrowser())
                        }
                    }
                }
            )
        }

        is Screen.FileBrowser -> {
            val connected = connectionState as? ConnectionState.Connected
            FileBrowserScreen(
                currentPath = currentPath,
                parentPath = browseData?.parent_path,
                entries = browseData?.entries ?: emptyList(),
                specialFolders = homeData?.special_folders ?: emptyList(),
                drives = homeData?.drives ?: emptyList(),
                isAtRoot = isAtRoot,
                isLoading = isLoadingBrowse,
                isConnected = connected != null,
                isReconnecting = isReconnecting,
                isDisconnecting = isDisconnecting,
                fileViewMode = selectedFileViewMode,
                reduceMotion = reduceMotion,
                themeSoundUri = themeSoundUris[selectedThemePreset],
                onThumbnailLoad = { entry -> loadPcThumbnail(entry) },
                onFileViewModeChange = { mode -> scope.launch { preferences.saveFileViewMode(mode) } },
                onEntryClick = { entry ->
                    if (entry.is_folder) {
                        navigateToFolder(entry.path)
                    } else {
                        selectedFile = FileMetadata(
                            id = entry.file_id ?: 0, name = entry.name, path = entry.path,
                            extension = entry.extension, parent_folder = currentPath,
                            size_bytes = entry.size_bytes, modified_at = entry.modified_at,
                            created_at = null, hash = null, version = 1,
                            project = null, sync_state = "SYNCED", sync_mode = "METADATA_ONLY"
                        )
                        pushScreen(Screen.FileDetails(entry.file_id ?: 0))
                    }
                },
                onEntryLongClick = { entry ->
                    selectedEntry = entry
                    showContextMenu = true
                },
                onSpecialFolderClick = { folder -> navigateToFolder(folder.path) },
                onDriveClick = { drive -> navigateToFolder(drive.path) },
                onBackClick = { navigateBackInBrowser() },
                onBreadcrumbClick = { path -> navigateToFolder(path) },
                onSearchClick = { pushScreen(Screen.Search) },
                onSettingsClick = { pushScreen(Screen.Settings) },
                onUploadClick = {
                    uploadTargetFolder = if (isAtRoot) (homeData?.home_path ?: "") else currentPath
                    pushScreen(Screen.Upload)
                },
                onTransfersClick = {
                    uploadTargetFolder = if (isAtRoot) (homeData?.home_path ?: "") else currentPath
                    pushScreen(Screen.Upload)
                },
                transferCount = queuedUploads.size,
                favoriteFolders = favoriteFolders,
                isCurrentFolderFavorite = favoriteFolders.any { it.path == currentPath },
                onToggleCurrentFolderFavorite = {
                    if (!isAtRoot) {
                        val label = currentPath.trimEnd('\\', '/').substringAfterLast('\\').substringAfterLast('/')
                        scope.launch { preferences.toggleFavoriteFolder(currentPath, label.ifBlank { currentPath }) }
                    }
                },
                onFavoriteFolderClick = { favorite -> navigateToFolder(favorite.path) },
                hasClipboard = (fileClipboard != null || bulkFileClipboard.isNotEmpty()) && !isAtRoot,
                offlineLastSyncAt = treeSyncedAt ?: lastCacheSyncAt,
                selectedPaths = selectedPaths,
                onToggleSelection = { entry ->
                    selectedPaths = if (entry.path in selectedPaths) selectedPaths - entry.path else selectedPaths + entry.path
                },
                onClearSelection = { selectedPaths = emptySet() },
                onBulkDeleteClick = {
                    val token = connected?.token
                    if (token != null && selectedPaths.isNotEmpty()) {
                        scope.launch {
                            val response = currentApi()?.operateFileBatch(
                                token,
                                FileOperationBatchRequest(selectedPaths.map { FileOperationRequest("delete", it) })
                            )
                            if (response?.isSuccessful == true) {
                                selectedPaths = emptySet()
                                loadBrowse(token, currentPath)
                            } else {
                                downloadError = "فشل تنفيذ الحذف الجماعي؛ راجع الصلاحيات والتعارضات وحاول مرة أخرى"
                            }
                        }
                    }
                },
                onBulkCopyClick = {
                    val copied = browseData?.entries?.filter { it.path in selectedPaths }.orEmpty()
                    if (copied.isNotEmpty()) {
                        bulkFileClipboard = copied
                        bulkClipboardIsCut = false
                        fileClipboard = null
                        selectedPaths = emptySet()
                    }
                },
                onBulkCutClick = {
                    val cut = browseData?.entries?.filter { it.path in selectedPaths }.orEmpty()
                    if (cut.isNotEmpty()) {
                        bulkFileClipboard = cut
                        bulkClipboardIsCut = true
                        fileClipboard = null
                        selectedPaths = emptySet()
                    }
                },
                onPasteClick = {
                    val bulk = bulkFileClipboard
                    val bulkToken = connected?.token
                    if (bulk.isNotEmpty() && bulkToken != null) {
                        scope.launch {
                            val isMove = bulkClipboardIsCut
                            val separator = if (currentPath.contains('\\')) "\\" else "/"
                            val operations = bulk.map { entry ->
                                FileOperationRequest(
                                    operation = if (isMove) "move" else "copy",
                                    source = entry.path,
                                    destination = currentPath.trimEnd('\\', '/') + separator + entry.name,
                                    collision_mode = if (isMove) "reject" else "duplicate"
                                )
                            }
                            val response = currentApi()?.operateFileBatch(bulkToken, FileOperationBatchRequest(operations))
                            val result = response?.body()
                            if (response?.isSuccessful == true && result != null) {
                                // لا نفقد الـ Copy/Cut عند نجاح بعض العناصر فقط؛
                                // تبقى العناصر الفاشلة جاهزة لمحاولة أخرى.
                                val failedSources = result.results.filter { !it.success }.map { it.path }.toSet()
                                bulkFileClipboard = bulk.filter { it.path in failedSources }
                                if (bulkFileClipboard.isEmpty()) bulkClipboardIsCut = false
                                loadBrowse(bulkToken, currentPath)
                                if (result.failed == 0) {
                                    showSuccess("تم ${if (isMove) "نقل" else "نسخ"} ${result.succeeded} عنصر")
                                } else {
                                    val firstReason = result.results.firstOrNull { !it.success }?.error ?: "تعارض أو صلاحية"
                                    downloadError = "تم تنفيذ ${result.succeeded} عملية، وتعذرت ${result.failed}: $firstReason"
                                }
                            } else {
                                downloadError = "فشل اللصق الجماعي؛ راجع التعارضات والصلاحيات"
                            }
                        }
                    } else {
                    val clip = fileClipboard
                    val token = connected?.token
                    if (clip != null && token != null) {
                        scope.launch {
                            val source = clip.first.path
                            val separator = if (currentPath.contains('\\')) "\\" else "/"
                            val destination = currentPath.trimEnd('\\', '/') + separator + clip.first.name
                            try {
                                val operation = if (clip.second) "move" else "copy"
                                val response = currentApi()?.operateFile(
                                    token,
                                    FileOperationRequest(
                                        operation = operation,
                                        source = source,
                                        destination = destination,
                                        collision_mode = if (clip.second) "reject" else "duplicate"
                                    )
                                )
                                if (response?.isSuccessful == true && response.body()?.success == true) {
                                    fileClipboard = null
                                    loadBrowse(token, currentPath)
                                    val actualName = response.body()?.destination?.substringAfterLast('\\')?.substringAfterLast('/')
                                    showSuccess(
                                        if (clip.second) "تم نقل الملف"
                                        else if (actualName != null && actualName != clip.first.name) "تم النسخ باسم $actualName"
                                        else "تم نسخ الملف"
                                    )
                                } else {
                                    val reason = response?.body()?.error ?: "فشل تنفيذ اللصق في المسار المطلوب."
                                    showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), reason))
                                }
                            } catch (_: Exception) {
                                // نحفظ Copy/Cut كأمر مؤجل بدل فقدانه عند انقطاع
                                // الاتصال، ثم ينفذ بعد Reconnect مع نتيجة واضحة.
                                offlineCommandQueue.add(QueuedOfflineCommand(
                                    id = "${System.currentTimeMillis()}_file_${if (clip.second) "move" else "copy"}",
                                    commandType = if (clip.second) "move" else "copy",
                                    filePath = source,
                                    displayName = clip.first.name,
                                    newValue = destination,
                                    addedAt = System.currentTimeMillis()
                                ))
                                downloadError = "الكمبيوتر غير متاح - العملية محفوظة وستُعاد عند الاتصال"
                            }
                        }
                    }
                    }
                },
                folderContents = folderContents,
                onRefreshRequested = {
                    val token = connected?.token
                    if (token != null) {
                        if (isAtRoot) loadHome(token) else loadBrowse(token, currentPath)
                    }
                },
                onReconnectClick = reconnectClick@{
                    if (isReconnecting || isDisconnecting) return@reconnectClick
                    // Every explicit reconnect requires fresh, short-lived
                    // computer-authorized pairing; retain the saved profile.
                    pairingError = null
                    scannedPairingCode = null
                    scannedPairingDevices = emptyList()
                    selectedDevice = null
                    replaceStackWith(Screen.Discovery)
                },
                onDisconnectAndRefreshClick = disconnectClick@{
                    val active = connectionState as? ConnectionState.Connected ?: return@disconnectClick
                    if (isDisconnecting || isReconnecting) return@disconnectClick
                    isDisconnecting = true
                    scope.launch {
                        try {
                            val api = currentApi()
                            val response = api?.getFullTreeSnapshot(active.token)
                            val snapshot = response?.takeIf { it.isSuccessful }?.body()
                            if (snapshot == null) {
                                showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر تحديث الشجرة؛ أبقينا الاتصال قائمًا"))
                                return@launch
                            }

                            treeMirrorStore.saveFullSnapshot(snapshot)
                            offlineCommandQueue.getAll().sortedBy { it.addedAt }.forEach { command ->
                                when (command.commandType) {
                                    "delete" -> treeMirrorStore.applyOptimisticDelete(command.filePath)
                                    "rename" -> command.newValue?.let { treeMirrorStore.applyOptimisticRename(command.filePath, it) }
                                }
                            }
                            try {
                                val homeResponse = withTimeoutOrNull(1800L) { api?.getHome(active.token) }
                                if (homeResponse?.isSuccessful == true) {
                                    homeResponse.body()?.let { latestHome ->
                                        homeData = latestHome
                                        uploadTargetFolder = latestHome.home_path
                                        localCache.saveHomeSnapshot(latestHome)
                                    }
                                }
                            } catch (_: Exception) { }

                            connectionState = ConnectionState.Disconnected
                            if (isAtRoot) {
                                homeData = localCache.getHomeSnapshot() ?: homeData
                            } else {
                                loadBrowse("", currentPath)
                            }
                            showSuccess("تم تحديث الشجرة وحفظها ثم قطع الاتصال")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            showUserError(DeskTwinErrorPresenter.fromThrowable(error))
                        } finally {
                            isDisconnecting = false
                        }
                    }
                }
            )

            if (showContextMenu && selectedEntry != null) {
                val entry = selectedEntry!!
                EntryContextMenuSheet(
                    entry = entry,
                    onDismiss = { showContextMenu = false },
                    onOpenClick = {
                        showContextMenu = false
                        if (entry.is_folder) navigateToFolder(entry.path)
                    },
                    onInfoClick = { showContextMenu = false },
                    onRenameConfirmed = { newName ->
                        showContextMenu = false
                        val token = connected?.token
                        if (token == null) {
                            // أوفلاين: نسجّل النية في الطابور المحلي بدل ما نتجاهل
                            // الضغطة. هتتعرض للمراجعة وتتنفذ بالترتيب لما نتصل.
                            scope.launch {
                                offlineCommandQueue.add(QueuedOfflineCommand(
                                    id = "${System.currentTimeMillis()}_rename",
                                    commandType = "rename", filePath = entry.path,
                                    displayName = entry.name, newValue = newName,
                                    addedAt = System.currentTimeMillis()
                                ))
                                // تطبيق فوري على شكل الشجرة المعروض - الملف يظهر
                                // بالاسم الجديد على طول، مش لحد ما تتصل وتوافق.
                                treeMirrorStore.applyOptimisticRename(entry.path, newName)
                                loadBrowse("", currentPath)  // نحدّث القائمة المعروضة فورًا
                            }
                        } else {
                            scope.launch {
                                try {
                                    val approval = currentApi()?.requestApproval(token, ApprovalRequest(
                                        action_type = "rename_file", file_id = entry.file_id, file_path = entry.path,
                                        new_value = newName
                                    ))
                                    if (approval?.isSuccessful == true) {
                                        waitForApproval(token, approval.body()!!.approval_id)
                                        loadBrowse(token, currentPath)
                                    }
                                } catch (_: Exception) {
                                    commandQueue.add(QueuedCommand(
                                        id = System.currentTimeMillis().toString(), type = CommandType.RENAME_FILE,
                                        filePath = entry.path, fileId = entry.file_id, newValue = newName,
                                        computerToken = token, createdAt = System.currentTimeMillis()
                                    ))
                                }
                            }
                        }
                    },
                    onDeleteClick = {
                        showContextMenu = false
                        val token = connected?.token
                        if (token == null) {
                            scope.launch {
                                offlineCommandQueue.add(QueuedOfflineCommand(
                                    id = "${System.currentTimeMillis()}_delete",
                                    commandType = "delete", filePath = entry.path,
                                    displayName = entry.name, newValue = null,
                                    addedAt = System.currentTimeMillis()
                                ))
                                // تطبيق فوري: الملف/الفولدر يختفي من الشجرة المعروضة
                                // على طول - مش لحد ما تتصل وتوافق على المراجعة.
                                treeMirrorStore.applyOptimisticDelete(entry.path)
                                loadBrowse("", currentPath)  // نحدّث القائمة المعروضة فورًا
                            }
                        } else {
                            scope.launch {
                                try {
                                    val approval = currentApi()?.requestApproval(token, ApprovalRequest(
                                        action_type = "delete_file", file_id = entry.file_id, file_path = entry.path
                                    ))
                                    if (approval?.isSuccessful == true) {
                                        waitForApproval(token, approval.body()!!.approval_id)
                                        loadBrowse(token, currentPath)
                                    }
                                } catch (_: Exception) {
                                    commandQueue.add(QueuedCommand(
                                        id = System.currentTimeMillis().toString(), type = CommandType.DELETE_FILE,
                                        filePath = entry.path, fileId = entry.file_id,
                                        computerToken = token, createdAt = System.currentTimeMillis()
                                    ))
                                }
                            }
                        }
                    },
                    onCopyClick = {
                        fileClipboard = entry to false
                        showContextMenu = false
                    },
                    onCutClick = {
                        fileClipboard = entry to true
                        showContextMenu = false
                    },
                    onDownloadClick = {
                        showContextMenu = false
                        val token = connected?.token
                        if (token == null) {
                            // طلب تحميل مسبق: بيتنفذ فعليًا (نقل bytes) بعد الاتصال
                            // والموافقة في شاشة المراجعة، مش قبل كده.
                            scope.launch {
                                offlineCommandQueue.add(QueuedOfflineCommand(
                                    id = "${System.currentTimeMillis()}_download",
                                    commandType = "download", filePath = entry.path,
                                    displayName = entry.name, newValue = null,
                                    addedAt = System.currentTimeMillis()
                                ))
                            }
                        } else {
                            scope.launch {
                                val api = currentFileTransferApi()
                                if (api != null) {
                                    DownloadManager.downloadFile(
                                        context, api, token, entry.file_id ?: 0, entry.path, entry.name,
                                        DownloadManager.DownloadType.PERMANENT
                                    )
                                }
                            }
                        }
                    },
                    onUploadHereClick = {
                        showContextMenu = false
                        uploadTargetFolder = entry.path
                        pushScreen(Screen.Upload)
                    }
                )
            }
        }

        is Screen.Upload -> {
            val connected = connectionState as? ConnectionState.Connected
            UploadScreen(
                isConnected = connected != null,
                targetFolderLabel = uploadTargetFolder.ifEmpty { "الرئيسية" },
                pendingUploads = queuedUploads.map {
                    PendingUpload(
                        fileName = it.fileName, sizeBytes = it.sizeBytes,
                        mode = if (it.setAsWallpaper) UploadMode.SET_AS_WALLPAPER else if (it.deleteAfterUpload) UploadMode.COPY_AND_DELETE else UploadMode.COPY_KEEP_BOTH,
                        status = if (connected != null) UploadStatus.UPLOADING else UploadStatus.WAITING_FOR_CONNECTION,
                        progressFraction = uploadProgress[it.id] ?: 0f,
                        etaLabel = uploadEtaLabels[it.id],
                        paused = it.paused,
                        speedLabel = uploadSpeedLabels[it.id]
                    )
                },
                onPickFileClick = { mode ->
                    pendingUploadMode = mode
                    if (!hasStoragePermission) permissionLauncher.launch(requiredStoragePermissions())
                    filePickerLauncher.launch(arrayOf("*/*"))
                },
                onChangeTargetFolderClick = {
                    pickerIsAtRoot = true
                    pickerCurrentPath = ""
                    pickerPathHistory.clear()
                    pushScreen(Screen.FolderPicker)
                },
                onBackClick = { popScreen() }
                ,onTogglePause = { fileName ->
                    queuedUploads.firstOrNull { it.fileName == fileName }?.let { item ->
                        val pause = !item.paused
                        if (pause) uploadJobs[item.id]?.cancel()
                        scope.launch { uploadQueue.setPaused(item.id, pause) }
                    }
                }
            )
        }

        is Screen.FolderPicker -> {
            val connected = connectionState as? ConnectionState.Connected
            FileBrowserScreen(
                currentPath = pickerCurrentPath,
                parentPath = pickerBrowseData?.parent_path,
                entries = pickerBrowseData?.entries ?: emptyList(),
                specialFolders = homeData?.special_folders ?: emptyList(),
                drives = homeData?.drives ?: emptyList(),
                isAtRoot = pickerIsAtRoot,
                isLoading = false,
                isConnected = connected != null,
                fileViewMode = selectedFileViewMode,
                onFileViewModeChange = { mode -> scope.launch { preferences.saveFileViewMode(mode) } },
                isPickerMode = true,
                onEntryClick = { entry -> if (entry.is_folder) navigateToPickerFolder(entry.path) },
                onEntryLongClick = { },
                onSpecialFolderClick = { folder -> navigateToPickerFolder(folder.path) },
                onDriveClick = { drive -> navigateToPickerFolder(drive.path) },
                onBackClick = { if (!navigateBackInPicker()) popScreen() },
                onBreadcrumbClick = { path -> navigateToPickerFolder(path) },
                onSearchClick = { },
                onSettingsClick = { },
                onUploadClick = { },
                onRefreshRequested = {
                    val token = connected?.token
                    if (token != null) {
                        if (pickerIsAtRoot) loadHome(token) else loadPickerBrowse(token, pickerCurrentPath)
                    }
                },
                onPickCurrentFolder = {
                    val path = pickerCurrentPath
                    if (isPickerForScopeRequest) {
                        // طلب نطاق إضافي: نبعت /targets/request - ده مش تنفيذ
                        // فوري، هو طلب معلّق ينتظر موافقة صريحة من الكمبيوتر
                        // (نفس منطق السيرفر بعد إصلاح ثغرة التوسيع الذاتي).
                        val token = connected?.token
                        val label = path.substringAfterLast('/').substringAfterLast('\\').ifEmpty { path }
                        if (token != null) {
                            scope.launch {
                                try {
                                    val response = currentApi()?.requestNewTarget(
                                        token, TargetRequest(path = path, label = label, recursive = true)
                                    )
                                    scopeRequestConfirmation = response?.body()?.status == "pending"
                                } catch (_: Exception) {
                                    downloadError = "مفيش اتصال دلوقتي - جرّب تاني لما الكمبيوتر يبقى متاح"
                                }
                            }
                        }
                        isPickerForScopeRequest = false
                    } else {
                        uploadTargetFolder = path
                    }
                    popScreen()
                }
            )
        }

        is Screen.FileDetails -> {
            val file = selectedFile
            val connected = connectionState as? ConnectionState.Connected
            if (file != null) {
                LaunchedEffect(file.id, connected?.token) {
                    val token = connected?.token ?: return@LaunchedEffect
                    fileVersionsLoading = true
                    try {
                        val response = currentApi()?.listFileVersions(token, file.id)
                        if (response?.isSuccessful == true) fileVersions = response.body().orEmpty()
                    } finally {
                        fileVersionsLoading = false
                    }
                }
                FileDetailsScreen(
                    file = file,
                    isDownloading = isDownloadingFile,
                    downloadProgress = downloadProgress,
                    onBackClick = { popScreen() },
                    versions = fileVersions,
                    versionsLoading = fileVersionsLoading,
                    onRefreshVersions = {
                        val token = connected?.token
                        if (token != null) scope.launch {
                            fileVersionsLoading = true
                            try {
                                val response = currentApi()?.listFileVersions(token, file.id)
                                if (response?.isSuccessful == true) fileVersions = response.body().orEmpty()
                            } finally { fileVersionsLoading = false }
                        }
                    },
                    onCreateSavepoint = {
                        val token = connected?.token
                        if (token != null) scope.launch {
                            try {
                                val response = currentApi()?.createFileSavepoint(token, file.id)
                                if (response?.isSuccessful == true) {
                                    fileVersions = currentApi()?.listFileVersions(token, file.id)?.body().orEmpty()
                                    downloadError = "تم حفظ نسخة من الملف"
                                } else {
                                    showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر حفظ النسخة"))
                                }
                            } catch (e: Exception) { showUserError(DeskTwinErrorPresenter.fromThrowable(e)) }
                        }
                    },
                    onRestoreVersion = { version ->
                        val token = connected?.token
                        if (token != null) scope.launch {
                            try {
                                val response = currentApi()?.restoreFileVersion(token, file.id, version.id)
                                if (response?.isSuccessful == true) {
                                    fileVersions = currentApi()?.listFileVersions(token, file.id)?.body().orEmpty()
                                    downloadError = "تمت استعادة النسخة ${version.version_number}"
                                } else {
                                    showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر استعادة النسخة"))
                                }
                            } catch (e: Exception) { showUserError(DeskTwinErrorPresenter.fromThrowable(e)) }
                        }
                    },
                    onOpenTemporaryClick = {
                        val token = connected?.token
                        if (token != null) {
                            isDownloadingFile = true
                            downloadProgress = 0f
                            scope.launch {
                                val api = currentFileTransferApi()
                                if (api != null) {
                                    val result = DownloadManager.downloadFile(
                                        context, api, token, file.id, file.path, file.name,
                                        DownloadManager.DownloadType.TEMPORARY,
                                        onProgress = { read, total ->
                                            downloadProgress = if (total > 0) read.toFloat() / total.toFloat() else 0f
                                            com.desktwin.app.notifications.TransferNotifications.updateProgress(
                                                context, "download_${file.id}", file.name, isUpload = false,
                                                fraction = downloadProgress ?: 0f
                                            )
                                        }
                                    )
                                    result.onSuccess { downloadedFile ->
                                        temporaryFilesToCleanup.add(downloadedFile)
                                        DownloadManager.openDownloadedFile(context, downloadedFile)
                                        com.desktwin.app.notifications.TransferNotifications.completeSuccess(
                                            context, "download_${file.id}", file.name, isUpload = false
                                        )
                                        try {
                                            api.setFileAvailability(token, file.id, FileAvailabilityRequest("temporary"))
                                        } catch (_: Exception) {
                                            commandQueue.add(QueuedCommand(
                                                id = System.currentTimeMillis().toString(), type = CommandType.SET_AVAILABILITY,
                                                fileId = file.id, newValue = "temporary",
                                                computerToken = token, createdAt = System.currentTimeMillis()
                                            ))
                                        }
                                    }
                                    result.onFailure {
                                        downloadError = it.message ?: "فشل التحميل"
                                        com.desktwin.app.notifications.TransferNotifications.completeFailure(
                                            context, "download_${file.id}", file.name, isUpload = false
                                        )
                                    }
                                } else {
                                    downloadError = "مفيش اتصال بالكمبيوتر دلوقتي"
                                }
                                isDownloadingFile = false
                                downloadProgress = null
                            }
                        }
                    },
                    onDownloadPermanentClick = {
                        val token = connected?.token
                        if (token != null) {
                            isDownloadingFile = true
                            downloadProgress = 0f
                            scope.launch {
                                val api = currentFileTransferApi()
                                if (api != null) {
                                    // تحميل دائم = نسخة كاملة تفضل على التليفون للأبد
                                    // (حتى بعد قفل التطبيق)، بدون أي حذف من الكمبيوتر.
                                    // الملف الأصلي يفضل زي ما هو - ده نسخ عادي، مش نقل.
                                    val result = DownloadManager.downloadFile(
                                        context, api, token, file.id, file.path, file.name,
                                        DownloadManager.DownloadType.PERMANENT,
                                        onProgress = { read, total ->
                                            downloadProgress = if (total > 0) read.toFloat() / total.toFloat() else 0f
                                            com.desktwin.app.notifications.TransferNotifications.updateProgress(
                                                context, "download_${file.id}", file.name, isUpload = false,
                                                fraction = downloadProgress ?: 0f
                                            )
                                        }
                                    )
                                    result.onSuccess {
                                        permanentSaveConfirmation = file.name
                                        com.desktwin.app.notifications.TransferNotifications.completeSuccess(
                                            context, "download_${file.id}", file.name, isUpload = false
                                        )
                                    }
                                    result.onFailure {
                                        downloadError = it.message ?: "فشل التحميل الدائم"
                                        com.desktwin.app.notifications.TransferNotifications.completeFailure(
                                            context, "download_${file.id}", file.name, isUpload = false
                                        )
                                    }
                                } else {
                                    downloadError = "مفيش اتصال بالكمبيوتر دلوقتي"
                                }
                                isDownloadingFile = false
                                downloadProgress = null
                            }
                        }
                    },
                    onStreamVideoClick = {
                        if (connected != null && selectedDevice != null) {
                            pushScreen(Screen.VideoStream(file.id, file.name, file.path))
                        } else {
                            showUserError(DeskTwinErrorPresenter.fromThrowable(IllegalStateException("البث يحتاج اتصالًا مباشرًا بالكمبيوتر")))
                        }
                    },
                    onMakeLinkedClick = {
                        val token = connected?.token
                        if (token != null) {
                            scope.launch {
                                try {
                                    val availability = currentApi()?.setFileAvailability(token, file.id, FileAvailabilityRequest("shared"))
                                    val approvalId = availability?.body()?.approval_id
                                    if (approvalId != null) {
                                        waitForApproval(token, approvalId)
                                        loadHome(token)
                                    }
                                } catch (_: Exception) {
                                    commandQueue.add(QueuedCommand(
                                        id = System.currentTimeMillis().toString(), type = CommandType.SET_AVAILABILITY,
                                        fileId = file.id, newValue = "shared",
                                        computerToken = token, createdAt = System.currentTimeMillis()
                                    ))
                                }
                            }
                        }
                    },
                    onShowInExplorerClick = {
                        val token = connected?.token
                        if (token != null) {
                            scope.launch {
                                try {
                                    val response = currentApi()?.showInExplorer(token, file.id, file.path)
                                    if (response == null || !response.isSuccessful || response.body()?.success != true) {
                                        showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر فتح مكان الملف على الكمبيوتر."))
                                    } else {
                                        showSuccess("تم فتح مكان الملف وتحديده على الكمبيوتر")
                                    }
                                } catch (_: Exception) {
                                    // مفيش queueing هنا عن قصد - الأمر ده مفيدش لو
                                    // اتنفذ بعد وقت طويل من أوفلاين (المستخدم مش
                                    // قاعد قدام الكمبيوتر أصلاً وقتها)، عكس أوامر
                                    // زي SET_AVAILABILITY اللي نتيجتها تفضل صحيحة
                                    // مهما اتأخر التنفيذ.
                                    downloadError = "مفيش اتصال بالكمبيوتر دلوقتي"
                                }
                            }
                        }
                    },
                    onOpenOnComputerClick = {
                        val token = connected?.token
                        if (token != null) {
                            scope.launch {
                                try {
                                    val response = currentApi()?.openFileOnComputer(token, file.id, file.path)
                                    if (response == null || !response.isSuccessful || response.body()?.success != true) {
                                        showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر تشغيل الملف على الكمبيوتر."))
                                    } else {
                                        showSuccess("تم تشغيل الملف على الكمبيوتر")
                                    }
                                } catch (e: Exception) {
                                    showUserError(DeskTwinErrorPresenter.fromThrowable(e))
                                }
                            }
                        }
                    },
                    onSetWallpaperClick = {
                        val token = connected?.token
                        if (token != null) scope.launch {
                            try {
                                val response = currentApi()?.setWallpaper(token, file.id)
                                if (response?.isSuccessful == true && response.body()?.success == true) {
                                    showSuccess("تم تعيين الصورة كخلفية للكمبيوتر")
                                } else {
                                    showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر تعيين الصورة كخلفية"))
                                }
                            } catch (e: Exception) { downloadError = "تعذر تعيين الخلفية: ${e.message}" }
                        }
                    },
                    onAddShortcutClick = { (context as? MainActivity)?.addFileShortcut(file) }
                )
            }
        }

        is Screen.VideoStream -> {
            val connected = connectionState as? ConnectionState.Connected
            val device = selectedDevice
            if (connected != null && device != null) {
                VideoStreamScreen(device.ip, device.port, connected.token, currentScreen.fileId, currentScreen.path, currentScreen.name, onBack = { popScreen() })
            } else {
                LaunchedEffect(Unit) { showUserError(DeskTwinErrorPresenter.fromThrowable(IllegalStateException("انقطع اتصال البث بالكمبيوتر"))); popScreen() }
            }
        }

        is Screen.Search -> {
            val connected = connectionState as? ConnectionState.Connected
            val runSearch: (String) -> Unit = { query ->
                searchQuery2 = query
                searchJob?.cancel()
                searchRequestSequence += 1
                val requestSequence = searchRequestSequence
                val token = connected?.token
                searchResults = emptyList()
                searchError = null
                if (token != null && query.isNotBlank()) {
                    isSearching2 = true
                    searchJob = scope.launch {
                        try {
                            // Debounce keystrokes so each query does not start a network request.
                            kotlinx.coroutines.delay(280)
                            val api = currentApi()
                            val response = api?.listFiles(token, query = query)
                            if (requestSequence == searchRequestSequence) {
                                if (response?.isSuccessful == true) {
                                    searchResults = response.body() ?: emptyList()
                                } else {
                                    searchError = "تعذر البحث في الكمبيوتر (HTTP ${response?.code() ?: "لا توجد استجابة"})."
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            if (requestSequence == searchRequestSequence) {
                                searchError = "تعذر الاتصال أثناء البحث: ${error.message ?: "خطأ غير معروف"}"
                            }
                        } finally {
                            if (requestSequence == searchRequestSequence) isSearching2 = false
                        }
                    }
                } else {
                    isSearching2 = false
                    if (query.isNotBlank()) searchError = "اتصل بالكمبيوتر أولًا للبحث في ملفاته."
                }
            }
            SearchScreen(
                results = searchResults,
                isSearching = isSearching2,
                errorMessage = searchError,
                onQueryChange = runSearch,
                onRetry = { if (searchQuery2.isNotBlank()) runSearch(searchQuery2) },
                onFileClick = { file ->
                    selectedFile = file
                    pushScreen(Screen.FileDetails(file.id))
                },
                onBackClick = { popScreen() }
            )
        }

        is Screen.Settings -> {
            val connected = connectionState as? ConnectionState.Connected
            SettingsScreen(
                computerName = connected?.computerName ?: "غير متصل",
                isConnected = connected != null,
                onDisconnectClick = {
                    scope.launch {
                        preferences.clearAll()
                        profileStore.clear()
                        DeskTwinConnection.disconnect()
                        connectionState = ConnectionState.Disconnected
                        replaceStackWith(Screen.Discovery)
                    }
                },
                onRequestMoreAccessClick = {
                    pickerIsAtRoot = true
                    pickerCurrentPath = ""
                    pickerPathHistory.clear()
                    isPickerForScopeRequest = true
                    pushScreen(Screen.FolderPicker)
                },
                onQuickActionsClick = { pushScreen(Screen.QuickActions) },
                onChatClick = { pushScreen(Screen.Chat) }
                ,onIntegrationHealthClick = { pushScreen(Screen.IntegrationHealth) }
                ,onCommandDraftClick = { pushScreen(Screen.CommandDraft) }
                ,onTrashClick = {
                    val token = connected?.token
                    if (token != null) scope.launch { trashItems = currentApi()?.listTrash(token)?.body().orEmpty() }
                    pushScreen(Screen.Trash)
                }
                ,onActivityClick = { pushScreen(Screen.Activity) }
                ,onConflictsClick = { pushScreen(Screen.Conflicts) }
                ,onPhoneFolderClick = { phoneTreeLauncher.launch(null) }
                ,onPhoneFullAccessClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        }.onFailure { context.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                    } else {
                        phoneTreeLauncher.launch(null)
                    }
                }
                ,themeMode = selectedThemeMode
                ,language = selectedLanguage
                ,onThemeModeChange = { value -> scope.launch { preferences.saveThemeMode(value) } }
                ,themePresetId = selectedThemePreset
                ,reduceMotion = reduceMotion
                ,onReduceMotionChange = { value -> scope.launch { preferences.saveReduceMotion(value) } }
                ,onThemesClick = { pushScreen(Screen.ThemeLibrary) }
                ,onLanguageChange = { value -> scope.launch { preferences.saveLanguage(value) } }
                ,onPlayIntro = { showIntro = true }
                ,onChooseIntroVideo = { introVideoPicker.launch(arrayOf("video/*")) }
                ,onResetIntroVideo = { scope.launch { preferences.clearIntroVideoUri() } }
                ,hasCustomIntroVideo = introVideoUri != null
                ,introMode = introMode
                ,onIntroModeChange = { mode ->
                    scope.launch {
                        preferences.saveIntroMode(mode)
                        if (mode == "off") showIntro = false
                    }
                }
                ,onShareDiagnosticsClick = {
                    val file = DeskTwinDiagnostics.exportFile(context)
                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }, "مشاركة سجل DeskTwin"))
                }
                ,fileViewMode = selectedFileViewMode
                ,onFileViewModeChange = { mode -> scope.launch { preferences.saveFileViewMode(mode) } }
            )
        }

        is Screen.ThemeLibrary -> {
            ThemeLibraryScreen(
                selectedThemeId = selectedThemePreset,
                isDarkTheme = deskTwinThemeIsDark(
                    deskTwinThemePreset(selectedThemePreset), selectedThemeMode, isSystemInDarkTheme()
                ),
                reduceMotion = reduceMotion,
                onBackClick = { popScreen() },
                onApplyTheme = { id -> scope.launch { preferences.saveThemePreset(id) } },
                themeSoundUris = themeSoundUris,
                onSaveThemeSound = { id, uri -> scope.launch { preferences.saveThemeSoundUri(id, uri) } }
            )
        }

        is Screen.Activity -> {
            val connected = connectionState as? ConnectionState.Connected
            LaunchedEffect(connected?.token) {
                val token = connected?.token ?: return@LaunchedEffect
                refreshActivity(token)
            }
            ActivityScreen(
                entries = activityEntries,
                isLoading = activityLoading,
                errorMessage = activityError,
                onRefresh = {
                    val token = connected?.token
                    if (token != null) scope.launch { refreshActivity(token) }
                },
                onBack = { popScreen() }
            )
        }

        is Screen.Conflicts -> {
            val connected = connectionState as? ConnectionState.Connected
            LaunchedEffect(connected?.token) {
                val token = connected?.token ?: return@LaunchedEffect
                conflictsLoading = true
                try {
                    val response = currentApi()?.listConflicts(token)
                    if (response?.isSuccessful == true) conflicts = response.body().orEmpty()
                } finally { conflictsLoading = false }
            }
            ConflictsScreen(
                conflicts = conflicts,
                loading = conflictsLoading,
                onRefresh = {
                    val token = connected?.token
                    if (token != null) scope.launch {
                        conflictsLoading = true
                        try {
                            val response = currentApi()?.listConflicts(token)
                            if (response?.isSuccessful == true) conflicts = response.body().orEmpty()
                        } finally { conflictsLoading = false }
                    }
                },
                onResolve = { conflict, resolution ->
                    val token = connected?.token
                    if (token != null) scope.launch {
                        try {
                            val response = currentApi()?.resolveConflict(token, conflict.id, ResolveConflictRequest(resolution))
                            if (response?.isSuccessful == true) conflicts = currentApi()?.listConflicts(token)?.body().orEmpty()
                            else showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر حل التعارض"))
                        } catch (e: Exception) { showUserError(DeskTwinErrorPresenter.fromThrowable(e)) }
                    }
                },
                onBack = { popScreen() }
            )
        }

        is Screen.Chat -> {
            val connected = connectionState as? ConnectionState.Connected
            ChatScreen(
                messages = chatMessages,
                draft = chatDraft,
                isConnected = connected != null,
                onDraftChange = { chatDraft = it },
                onSend = {
                    val device = selectedDevice
                    val token = connected?.token
                    if (device != null && token != null && chatDraft.isNotBlank()) {
                        val text = chatDraft
                        val messageId = java.util.UUID.randomUUID().toString()
                        chatDraft = ""
                        chatMessages = chatMessages + ChatMessage(messageId, text, fromPhone = true, status = "قيد الإرسال")
                        scope.launch {
                            val sent = ManualChatManager.sendTextOnce(device.ip, device.port, token, text, messageId)
                            chatMessages = chatMessages.map {
                                if (it.id == messageId) it.copy(status = if (sent) "وصل للكمبيوتر" else "فشل الإرسال") else it
                            }
                        }
                    }
                },
                onBack = { popScreen() },
                onQuickActionsClick = { pushScreen(Screen.QuickActions) }
            )
        }

        is Screen.IntegrationHealth -> {
            val connected = connectionState as? ConnectionState.Connected
            LaunchedEffect(connected?.token) {
                val token = connected?.token ?: return@LaunchedEffect
                integrationHealthLoading = true
                integrationHealth = currentApi()?.integrationHealth(token)?.body()
                integrationHealthLoading = false
            }
            IntegrationHealthScreen(
                report = integrationHealth,
                loading = integrationHealthLoading,
                onRefresh = {
                    val token = connected?.token
                    if (token != null) scope.launch {
                        integrationHealthLoading = true
                        integrationHealth = currentApi()?.integrationHealth(token)?.body()
                        integrationHealthLoading = false
                    }
                },
                onBack = { popScreen() }
            )
        }

        is Screen.Trash -> {
            val connected = connectionState as? ConnectionState.Connected
            TrashScreen(
                items = trashItems,
                onRefresh = { connected?.let { c -> scope.launch { trashItems = currentApi()?.listTrash(c.token)?.body().orEmpty() } } },
                onRestore = { item ->
                    connected?.let { c -> scope.launch {
                        currentApi()?.restoreFromTrash(c.token, RestoreRequest(item.original_path))
                        trashItems = currentApi()?.listTrash(c.token)?.body().orEmpty()
                    } }
                },
                onPermanentDelete = { item ->
                    connected?.let { c -> scope.launch {
                        val response = currentApi()?.permanentlyDeleteFromTrash(c.token, RestoreRequest(item.original_path))
                        if (response?.isSuccessful == true) {
                            trashItems = currentApi()?.listTrash(c.token)?.body().orEmpty()
                            showSuccess("تم الحذف النهائي من سلة المحذوفات")
                        } else showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر الحذف النهائي"))
                    } }
                }
            )
        }

        is Screen.CommandDraft -> {
            val connected = connectionState as? ConnectionState.Connected
            CommandDraftScreen(isConnected = connected != null, onBack = { popScreen() }) { shell, command, path, userName ->
                val token = connected?.token ?: return@CommandDraftScreen
                scope.launch {
                    try {
                        val payload = JSONObject()
                            .put("shell", shell)
                            .put("command", command)
                            .put("working_directory", path)
                            .put("user_name", userName)
                            .toString()
                        // Free-form shell text is a draft only. The desktop can
                        // save it after local review but never executes it.
                        val response = currentApi()?.requestApproval(token, ApprovalRequest("command_draft", file_path = path, new_value = payload))
                        if (response?.isSuccessful == true) {
                            showSuccess("تم إرسال الأمر للمراجعة والموافقة على الكمبيوتر")
                        } else {
                            showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "تعذر إرسال طلب تنفيذ الأمر"))
                        }
                    } catch (e: Exception) { downloadError = "تعذر إرسال طلب تنفيذ الأمر: ${e.message}" }
                }
            }
        }

        is Screen.QuickActions -> {
            val connected = connectionState as? ConnectionState.Connected
            QuickActionsScreen(
                isConnected = connected != null,
                onAction = { action ->
                    val token = connected?.token
                    if (token != null) scope.launch {
                        try {
                            val response = currentApi()?.deviceAction(token, DeviceActionRequest(action))
                            if (response == null || !response.isSuccessful || response.body()?.success != true) {
                                showUserError(DeskTwinErrorPresenter.fromHttp(response?.code(), "أمر جهاز مرفوض: الهاتف يحتاج مستوى ثقة كامل."))
                            } else {
                                if (action == "refresh") {
                                    if (isAtRoot) loadHome(token) else loadBrowse(token, currentPath)
                                }
                                showSuccess(response.body()?.message ?: "تم تنفيذ أمر الكمبيوتر")
                            }
                        } catch (e: Exception) {
                            showUserError(DeskTwinErrorPresenter.fromThrowable(e))
                        }
                    }
                }
            )
        }

        is Screen.SyncReview -> {
            val connected = connectionState as? ConnectionState.Connected
            SyncReviewScreen(
                treeDiff = pendingTreeDiff,
                offlineCommands = pendingOfflineCommands,
                batchResult = activeBatchResult,
                isSubmitting = isSubmittingBatch,
                onApplyDiffAndSubmit = { addedPaths, removedPaths, modifiedPaths, commandIds ->
                    if (connected == null) return@SyncReviewScreen
                    scope.launch {
                        isSubmittingBatch = true
                        val diff = pendingTreeDiff

                        // الأوامر الموافق عليها فقط تتبعت، بنفس ترتيب الإنشاء الزمني.
                        val approved = pendingOfflineCommands
                            .filter { it.id in commandIds }
                            .sortedBy { it.addedAt }

                        if (approved.isEmpty()) {
                            // تطبيق تغييرات الشجرة مستقل عن وجود أوامر مؤجلة.
                            // قبل ذلك كان هذا المسار يمسح شاشة المراجعة فقط،
                            // فتظل المرآة القديمة وتظهر نفس العناصر مجددًا عند الاتصال التالي.
                            if (diff != null) {
                                treeMirrorStore.applyDiff(
                                    added = diff.added.filter { it.path in addedPaths },
                                    removedPaths = diff.removed.filter { it in removedPaths },
                                    modified = diff.modified.filter { it.path in modifiedPaths }
                                )
                            }
                            pendingTreeDiff = null
                            pendingOfflineCommands = emptyList()
                            isSubmittingBatch = false
                            popScreen()
                            return@launch
                        }

                        try {
                            val response = currentApi()?.submitOfflineBatch(
                                connected.token,
                                SubmitBatchRequest(approved.map {
                                    OfflineCommandItem(it.commandType, it.filePath, it.newValue)
                                })
                            )
                            val body = response?.body()
                            if (response != null && response.isSuccessful && body != null) {
                                // لا نمسح الأمر المحلي إلا لو نُفّذ فعلًا أو تم تخطيه.
                                // الأمر المتعارض يظل محفوظًا حتى يختار المستخدم حله.
                                body.commands.forEachIndexed { index, result ->
                                    if (result.status == "executed" || result.status == "skipped") {
                                        approved.getOrNull(index)?.let { offlineCommandQueue.remove(it.id) }
                                    }
                                }
                                // نحدّث المرآة بعد نجاح إرسال الدفعة، لا قبله؛ حتى لا
                                // يختفي تعديل محلي إذا فشل الاتصال أو رفض الكمبيوتر.
                                if (diff != null) {
                                    treeMirrorStore.applyDiff(
                                        added = diff.added.filter { it.path in addedPaths },
                                        removedPaths = diff.removed.filter { it in removedPaths },
                                        modified = diff.modified.filter { it.path in modifiedPaths }
                                    )
                                }
                                activeBatchResult = body

                                // الكمبيوتر بس "صرّح" بالتحميل (الملف لسه موجود). النقل
                                // الفعلي بيبدأه التليفون هنا لكل أمر download اتنفذ
                                // (executed)، بنفس DownloadManager العادي بالظبط، فيطلع
                                // له شريط تقدم وإشعار زي أي تحميل تاني.
                                body.commands
                                    .filter { it.command_type == "download" && it.status == "executed" }
                                    .forEach { cmd ->
                                        scope.launch {
                                            val transferApi = currentFileTransferApi() ?: return@launch
                                            val name = cmd.file_path.substringAfterLast('\\').substringAfterLast('/')
                                            DownloadManager.downloadFile(
                                                context, transferApi, connected.token, 0, cmd.file_path, name,
                                                DownloadManager.DownloadType.PERMANENT,
                                                onProgress = { read, total ->
                                                    val fraction = if (total > 0) read.toFloat() / total.toFloat() else 0f
                                                    com.desktwin.app.notifications.TransferNotifications.updateProgress(
                                                        context, "offline_dl_${cmd.id}", name, isUpload = false, fraction = fraction
                                                    )
                                                }
                                            ).onSuccess {
                                                com.desktwin.app.notifications.TransferNotifications.completeSuccess(
                                                    context, "offline_dl_${cmd.id}", name, isUpload = false
                                                )
                                            }.onFailure {
                                                com.desktwin.app.notifications.TransferNotifications.completeFailure(
                                                    context, "offline_dl_${cmd.id}", name, isUpload = false
                                                )
                                            }
                                        }
                                    }
                            } else {
                                downloadError = "فشل إرسال الأوامر للكمبيوتر - جرّب تاني"
                            }
                        } catch (_: Exception) {
                            downloadError = "مفيش اتصال بالكمبيوتر دلوقتي - الأوامر لسه محفوظة عندك"
                        }
                        isSubmittingBatch = false
                    }
                },
                onSkipConflictAndContinue = {
                    val batch = activeBatchResult
                    if (connected != null && batch != null) {
                        scope.launch {
                            try {
                                currentApi()?.skipCurrentConflictedCommand(connected.token, batch.batch_id)
                                val resumed = currentApi()?.resumeOfflineBatch(connected.token, batch.batch_id)
                                resumed?.body()?.let { activeBatchResult = it }
                            } catch (_: Exception) {
                                downloadError = "فشل استكمال التنفيذ"
                            }
                        }
                    }
                },
                onCancelRemaining = {
                    val batch = activeBatchResult
                    if (connected != null && batch != null) {
                        scope.launch {
                            try {
                                currentApi()?.cancelOfflineBatch(connected.token, batch.batch_id)
                            } catch (_: Exception) { }
                            activeBatchResult = null
                            pendingTreeDiff = null
                            pendingOfflineCommands = emptyList()
                            popScreen()
                        }
                    }
                },
                onDismiss = {
                    activeBatchResult = null
                    pendingTreeDiff = null
                    pendingOfflineCommands = emptyList()
                    popScreen()
                }
            )
        }
    }
    }
    if (screenStack.size == 1 && selectedMainTab != null) {
        DeskTwinBottomNavigation(selectedMainTab) { tab ->
            when (tab) {
                DeskTwinMainTab.FILES -> replaceStackWith(Screen.FileBrowser())
                DeskTwinMainTab.TRANSFERS -> {
                    uploadTargetFolder = if (isAtRoot) (homeData?.home_path ?: "") else currentPath
                    replaceStackWith(Screen.Upload)
                }
                DeskTwinMainTab.REMOTE -> replaceStackWith(Screen.Chat)
                DeskTwinMainTab.SETTINGS -> replaceStackWith(Screen.Settings)
            }
        }
    }
    }

    // ---------- رسائل تأكيد/خطأ عامة (تحميل مؤقت/دائم) ----------
    // Dialog واحد عام بدل ما نكرر نفس الشكل في كل مكان بيحصل فيه تحميل -
    // downloadError وpermanentSaveConfirmation بيتصفروا تلقائيًا بعد العرض.
    downloadError?.let { message ->
        val details = actionableError ?: DeskTwinErrorPresenter.fromThrowable(
            IllegalStateException(message)
        )
        AlertDialog(
            onDismissRequest = { downloadError = null; actionableError = null },
            title = { Text(details.title) },
            text = {
                Column {
                    Text(details.message)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("الحل المقترح: ${details.solution}", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    when (details.action) {
                        ErrorAction.RECONNECT -> {
                            connectionState = ConnectionState.Disconnected
                            replaceStackWith(Screen.Discovery)
                        }
                        ErrorAction.REFRESH -> {
                            val token = (connectionState as? ConnectionState.Connected)?.token ?: ""
                            loadBrowse(token, currentPath)
                        }
                        ErrorAction.RETRY -> {
                            val token = (connectionState as? ConnectionState.Connected)?.token ?: ""
                            loadBrowse(token, currentPath)
                        }
                        ErrorAction.PAIR_AGAIN -> replaceStackWith(Screen.Discovery)
                        ErrorAction.REQUEST_ACCESS -> replaceStackWith(Screen.Settings)
                        ErrorAction.NONE -> Unit
                    }
                    downloadError = null
                    actionableError = null
                }) {
                    Text(when (details.action) {
                        ErrorAction.RECONNECT -> "إعادة الاتصال"
                        ErrorAction.PAIR_AGAIN -> "إعادة الاقتران"
                        ErrorAction.REQUEST_ACCESS -> "طلب الوصول"
                        ErrorAction.REFRESH -> "تحديث القائمة"
                        ErrorAction.RETRY -> "إعادة المحاولة"
                        ErrorAction.NONE -> "تمام"
                    })
                }
            }
        )
    }

    permanentSaveConfirmation?.let { fileName ->
        AlertDialog(
            onDismissRequest = { permanentSaveConfirmation = null },
            title = { Text("تم الحفظ") },
            text = { Text("\"$fileName\" اتحفظ بشكل دائم على التليفون. النسخة الأصلية لسه موجودة على الكمبيوتر زي ما هي.") },
            confirmButton = {
                TextButton(onClick = { permanentSaveConfirmation = null }) { Text("تمام") }
            }
        )
    }

    scopeRequestConfirmation?.let {
        AlertDialog(
            onDismissRequest = { scopeRequestConfirmation = null },
            title = { Text("اتبعت الطلب") },
            text = { Text("طلب مشاركة المجلد اتبعت للكمبيوتر. المجلد هيظهر عندك بمجرد الموافقة من هناك.") },
            confirmButton = {
                TextButton(onClick = { scopeRequestConfirmation = null }) { Text("تمام") }
            }
        )
    }

    if (incompleteTransfers.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { incompleteTransfers = emptyList() },
            title = { Text("فيه ملفات كبيرة ناقصة") },
            text = {
                Column {
                    Text("رفع الملفات دي اتقطع قبل ما يكمل - هتكمل تلقائيًا من نفس النقطة أول ما تحاول ترفعها تاني:")
                    Spacer(modifier = Modifier.height(8.dp))
                    incompleteTransfers.forEach { t ->
                        val percent = if (t.total_size_bytes > 0) (t.bytes_received * 100 / t.total_size_bytes).toInt() else 0
                        val name = t.file_path.substringAfterLast('\\').substringAfterLast('/')
                        Text("• $name — $percent%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { incompleteTransfers = emptyList() }) { Text("تمام") }
            }
        )
    }

    val selectedIntroVideoUri = introVideoUri
    if (showIntro && introMode != "off" && selectedIntroVideoUri != null) {
        DeskTwinIntro(
            videoUri = selectedIntroVideoUri,
            onFinished = {
            showIntro = false
            },
            onPlaybackError = { scope.launch { preferences.clearIntroVideoUri() } }
        )
    }

}
