package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.desktwin.app.network.FileMetadata
import com.desktwin.app.network.FileVersionEntry
import com.desktwin.app.ui.icons.FileIcon
import com.desktwin.app.ui.icons.FileKindClassifier
import com.desktwin.app.ui.theme.*

/**
 * هنا بالظبط بنطبّق القاعدة الذهبية بصريًا:
 * - المعلومات (metadata) معروضة فورًا ومجانية - مفيش تحميل.
 * - زرار "افتح الملف" منفصل تمامًا، وواضح إنه هيحمّل الملف الفعلي على مساحة التليفون.
 * الفصل ده مش تفصيلة تصميم - هو انعكاس مباشر لقرار المنتج (SYNC_SPEC بند 0).
 */
@Composable
fun FileDetailsScreen(
    file: FileMetadata,
    isDownloading: Boolean,
    downloadProgress: Float? = null,  // null = مفيش رقم تقدّم لسه (بداية التحميل قبل أول جزء يوصل)
    onOpenTemporaryClick: () -> Unit,
    onDownloadPermanentClick: () -> Unit,
    onMakeLinkedClick: () -> Unit,
    onShowInExplorerClick: () -> Unit,
    onOpenOnComputerClick: () -> Unit
    ,onStreamVideoClick: () -> Unit = {}
    ,onSetWallpaperClick: () -> Unit,
    onAddShortcutClick: () -> Unit,
    versions: List<FileVersionEntry> = emptyList(),
    versionsLoading: Boolean = false,
    onCreateSavepoint: () -> Unit = {},
    onRefreshVersions: () -> Unit = {},
    onRestoreVersion: (FileVersionEntry) -> Unit = {},
    onBackClick: () -> Unit = {}
) {
    var versionsExpanded by remember { mutableStateOf(false) }
    val temporaryActionLabel = temporaryActionLabel(file.extension)
    val temporaryActionHint = temporaryActionHint(file.extension)
    val isImage = file.extension.orEmpty().lowercase() in setOf(".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif")
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // على التابلت/الشاشات العريضة يظل المحتوى مقروءًا بدل تمدد البطاقات
        // بعرض الشاشة بالكامل، بينما يظل الهاتف مدمجًا كما هو.
        val horizontalPadding = if (maxWidth >= 600.dp) 32.dp else 20.dp
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 20.dp)
        ) {
        DeskTwinScreenHeader(tr("تفاصيل الملف", "File details"), onBackClick)
        Spacer(modifier = Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
            tonalElevation = 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    FileIcon(kind = FileKindClassifier.classify(file.extension), size = 48.dp)
                }
                Text(
                    file.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Text(
                    file.parent_folder ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        MetadataCard(file)

        Spacer(modifier = Modifier.height(20.dp))

        if (file.sync_mode == "METADATA_ONLY") {
            InfoBanner()
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (file.extension.orEmpty().lowercase() in setOf(".mp4", ".mkv", ".webm", ".mov", ".m4v")) {
            val largeVideo = file.size_bytes >= 500L * 1024L * 1024L
            Button(
                onClick = onStreamVideoClick,
                enabled = !isDownloading,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.PlayCircle, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(tr("مشاهدة على الهاتف — بلا تنزيل", "Watch on phone — no download"))
            }
            Text(
                if (largeVideo) tr("مناسب للفيديوهات الكبيرة؛ لا يحجز مساحة على الهاتف.", "Recommended for large videos; it does not use phone storage.")
                else tr("تشغيل عبر الشبكة دون حفظ نسخة على الهاتف.", "Stream over the network without saving a copy to the phone."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // خيار 1: فتح مؤقت - يتحمل، يتفتح، وبيتمسح بعدين تلقائيًا
        Button(
            onClick = onOpenTemporaryClick,
            enabled = !isDownloading,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            if (isDownloading) {
                Text(downloadProgress?.let { tr("جارٍ التحميل ${(it * 100).toInt()}%", "Downloading ${(it * 100).toInt()}%") } ?: tr("جارٍ التحميل…", "Downloading…"), color = androidx.compose.ui.graphics.Color.White)
            } else {
                Icon(Icons.Default.Visibility, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(temporaryActionLabel)
            }
        }

        Text(
            temporaryActionHint,
            modifier = Modifier.padding(top = 6.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (isDownloading && downloadProgress != null) {
            Text(tr("التقدم: ${(downloadProgress * 100).toInt()}%", "Progress: ${(downloadProgress * 100).toInt()}%"), modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }

        Spacer(modifier = Modifier.height(10.dp))

        // خيار 2: تحميل دائم - يفضل على التليفون فعليًا
        OutlinedButton(
            onClick = onDownloadPermanentClick,
            enabled = !isDownloading,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Default.CloudDownload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(tr("نزّله دايمًا على التليفون", "Keep on phone"))
        }

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = onOpenOnComputerClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) { Text(tr("شغّله على الكمبيوتر", "Open on computer")) }

        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onMakeLinkedClick) {
                        Icon(Icons.Default.Share, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("مشاركة", "Share"))
                    }
                    TextButton(onClick = onShowInExplorerClick) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("مكانه بالكمبيوتر", "Show on computer"))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onClick = onAddShortcutClick) {
                        Icon(Icons.Default.Home, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(tr("اختصار", "Shortcut"))
                    }
                    if (isImage) {
                        TextButton(onClick = onSetWallpaperClick) {
                            Icon(Icons.Default.Wallpaper, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(tr("خلفية", "Wallpaper"))
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            onClick = { versionsExpanded = !versionsExpanded },
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(tr("النسخ السابقة", "Previous versions"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (versionsExpanded) tr("إخفاء", "Hide") else tr("عرض", "View"), color = MaterialTheme.colorScheme.primary)
            }
        }
        if (versionsExpanded) {
            VersionHistoryCard(
                versions = versions,
                loading = versionsLoading,
                onCreateSavepoint = onCreateSavepoint,
                onRefresh = onRefreshVersions,
                onRestore = onRestoreVersion
            )
        }
        }
    }
}

@Composable
private fun VersionHistoryCard(
    versions: List<FileVersionEntry>,
    loading: Boolean,
    onCreateSavepoint: () -> Unit,
    onRefresh: () -> Unit,
    onRestore: (FileVersionEntry) -> Unit
) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(tr("نسخ سابقة", "Previous versions"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = tr("تحديث النسخ", "Refresh versions")) }
            }
            if (loading) Text(tr("جارٍ تحميل النسخ السابقة…", "Loading previous versions…"), modifier = Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!loading && versions.isEmpty()) {
                Text(tr("مفيش نسخ محفوظة لهذا الملف", "No saved versions for this file"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                versions.take(3).forEach { version ->
                    Column(Modifier.padding(vertical = 5.dp)) {
                        Text(tr("نسخة ${version.version_number} · ${formatSize(version.size_bytes)}", "Version ${version.version_number} · ${formatSize(version.size_bytes)}"), style = MaterialTheme.typography.bodyMedium)
                        Text(version.created_at.replace("T", " ").take(19), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onRestore(version) }) { Text(tr("استعادة هذه النسخة", "Restore this version")) }
                    }
                }
                if (versions.size > 3) Text(tr("+ ${versions.size - 3} نسخ أخرى", "+ ${versions.size - 3} more versions"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onCreateSavepoint, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text(tr("احفظ نسخة الآن", "Save a version now")) }
        }
    }
}

/** المعاينة هي طلب محتوى صريح، لكنها لا تترك نسخة دائمة في مساحة الهاتف. */
@Composable
private fun temporaryActionLabel(extension: String?): String = when (extension.orEmpty().lowercase()) {
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp" -> tr("عاين الصورة مؤقتًا", "Preview image temporarily")
    ".pdf" -> tr("عاين PDF مؤقتًا", "Preview PDF temporarily")
    ".txt", ".md", ".json", ".csv", ".xml", ".html", ".kt", ".py", ".java" -> tr("عاين الملف مؤقتًا", "Preview temporarily")
    else -> tr("افتح مؤقتًا", "Open temporarily")
}

@Composable
private fun temporaryActionHint(extension: String?): String = when (extension.orEmpty().lowercase()) {
    ".jpg", ".jpeg", ".png", ".webp", ".gif", ".bmp", ".pdf" ->
        tr("سيُفتح في عارض الهاتف دون حفظ نسخة دائمة.", "Opens in your phone viewer without saving a permanent copy.")
    else -> tr(
        "ينزّل نسخة مؤقتة ويفتحها بتطبيق مناسب، ثم لا يحتفظ بها كتنزيل دائم.",
        "Downloads a temporary copy for a compatible app without keeping a permanent download."
    )
}

@Composable
private fun MetadataCard(file: FileMetadata) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            MetadataRow(tr("الحجم", "Size"), formatSize(file.size_bytes))
            MetadataRow(tr("آخر تعديل", "Modified"), file.modified_at?.take(16)?.replace("T", " ") ?: "-")
            MetadataRow(tr("الإصدار", "Version"), "v${file.version}")
            MetadataRow(tr("المشروع", "Project"), file.project ?: "-")
            MetadataRow(tr("الحالة", "Status"), syncModeLabel(file.sync_state))
            MetadataRow(tr("طريقة المزامنة", "Sync mode"), syncModeLabel(file.sync_mode))
        }
    }
}

@Composable
private fun MetadataRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun InfoBanner() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp)) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                tr("الملف موجود على الكمبيوتر بس دلوقتي. المعلومات اللي فوق مجانية، لكن فتحه هيحمّله مؤقتًا.", "This file is currently only on the computer. The details above are available now; opening it downloads a temporary copy."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun syncModeLabel(mode: String): String = when (mode) {
    "METADATA_ONLY" -> tr("معلومات فقط", "Details only")
    "LINKED" -> tr("متاح على الجهازين", "Available on both devices")
    "PHONE_ONLY" -> tr("على التليفون فقط", "Phone only")
    "PC_ONLY" -> tr("على الكمبيوتر فقط", "Computer only")
    else -> mode
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var i = 0
    while (size >= 1024 && i < units.size - 1) { size /= 1024; i++ }
    return "%.1f %s".format(size, units[i])
}
