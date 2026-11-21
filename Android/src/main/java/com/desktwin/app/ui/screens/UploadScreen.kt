package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.desktwin.app.ui.theme.*

/**
 * السيناريو الكامل اللي بتحقق هنا (زي ما اتفقنا):
 *   1. المستخدم يختار ملف من تخزين التليفون
 *   2. يختار: يتنسخ ويفضل في التليفون / يتنسخ ويتمسح من التليفون (تحويل لـ metadata) / معلومات بس
 *   3. لو الكمبيوتر متصل دلوقتي -> يترفع فورًا
 *   4. لو مش متصل -> يتسجل في قائمة انتظار، ويترفع تلقائيًا أول ما يتصل (بند: "من غير ما استاهلك نت من الباقة")
 */

enum class UploadMode {
    COPY_KEEP_BOTH,   // يتنسخ للكمبيوتر ويفضل على التليفون
    COPY_AND_DELETE,  // يتنسخ للكمبيوتر ويتمسح من التليفون (يتحول metadata)
    SET_AS_WALLPAPER,
}

data class PendingUpload(
    val fileName: String,
    val sizeBytes: Long,
    val mode: UploadMode,
    val status: UploadStatus,
    val progressFraction: Float = 0f,  // 0.0 - 1.0، بيتحدث لحظيًا أثناء الرفع الفعلي
    val etaLabel: String? = null       // "باقي حوالي 30 ثانية" - null لو لسه مبيناش نقيس السرعة
    ,val paused: Boolean = false,
    val speedLabel: String? = null
)

enum class UploadStatus {
    WAITING_FOR_CONNECTION,  // مستني الكمبيوتر يبقى متاح على الشبكة
    UPLOADING,
    WAITING_APPROVAL,        // الاسم متكرر واختار المستخدم استبدال - مستني موافقة الكمبيوتر
    DONE,
    FAILED
}

@Composable
fun UploadScreen(
    isConnected: Boolean,
    targetFolderLabel: String,
    pendingUploads: List<PendingUpload>,
    onPickFileClick: (UploadMode) -> Unit,
    onChangeTargetFolderClick: () -> Unit,
    onBackClick: () -> Unit,
    onTogglePause: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
            Text("إرسال ملف للكمبيوتر", style = MaterialTheme.typography.titleMedium)
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (maxWidth >= 600.dp) 32.dp else 20.dp, vertical = 20.dp)
        ) {

            // حالة الاتصال - مهمة جدًا هنا عشان توضح للمستخدم هل الرفع هيحصل فورًا ولا لأ
            ConnectionBanner(isConnected)

            Spacer(modifier = Modifier.height(16.dp))

            // مكان الحفظ على الكمبيوتر
            DeskTwinCard(modifier = Modifier.fillMaxWidth().clickable { onChangeTargetFolderClick() }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null, tint = WarningOrange)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("هيتحفظ في", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(targetFolderLabel, style = MaterialTheme.typography.bodyLarge)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("اختار ملف من تخزين التليفون:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))

            // خيار 1: انسخ واحتفظ بنسخة في التليفون كمان
            UploadOptionCard(
                icon = Icons.Default.ContentCopy,
                title = "انسخ للكمبيوتر واحتفظ بالملف عندي",
                subtitle = "الملف هيفضل موجود على التليفون زي ما هو",
                onClick = { onPickFileClick(UploadMode.COPY_KEEP_BOTH) }
            )

            Spacer(modifier = Modifier.height(10.dp))

            // خيار 2: انقل (احذف من التليفون بعد الرفع، حوّله metadata)
            UploadOptionCard(
                icon = Icons.AutoMirrored.Filled.DriveFileMove,
                title = "انقل للكمبيوتر وفضّي مساحة عندي",
                subtitle = "بعد الرفع، هيتحول لمعلومات بس على التليفون - مساحته الحقيقية هتتفضى",
                onClick = { onPickFileClick(UploadMode.COPY_AND_DELETE) }
            )

            Spacer(modifier = Modifier.height(10.dp))
            UploadOptionCard(
                icon = Icons.Default.Wallpaper,
                title = "ارفع الصورة وعيّنها خلفية",
                subtitle = "ترفع للكمبيوتر ثم تصبح خلفية سطح المكتب تلقائيًا",
                onClick = { onPickFileClick(UploadMode.SET_AS_WALLPAPER) }
            )

            DeskTwinVisibility(visible = pendingUploads.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("مركز النقل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            "${pendingUploads.size}",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                pendingUploads.forEach { upload ->
                    PendingUploadRow(upload, onTogglePause)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
        }
    }
}

@Composable
private fun ConnectionBanner(isConnected: Boolean) {
    val (bgColor, icon, message) = if (isConnected) {
        Triple(SuccessGreen.copy(alpha = 0.1f), Icons.Default.CheckCircle, "الكمبيوتر متصل - الملف هيترفع فورًا")
    } else {
        Triple(WarningOrange.copy(alpha = 0.1f), Icons.Default.Schedule, "الكمبيوتر مش متصل دلوقتي - الملف هيترفع تلقائيًا أول ما تتصل بنفس شبكته")
    }

    Surface(shape = RoundedCornerShape(10.dp), color = bgColor, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (isConnected) SuccessGreen else WarningOrange, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun UploadOptionCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    DeskTwinCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PendingUploadRow(upload: PendingUpload, onTogglePause: (String) -> Unit) {
    val displayedProgress = upload.progressFraction.coerceIn(0f, 1f)

    DeskTwinCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(upload.fileName, style = MaterialTheme.typography.bodyMedium)
                    Text(statusLabel(upload.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when (upload.status) {
                    UploadStatus.WAITING_FOR_CONNECTION -> Icon(Icons.Default.Schedule, contentDescription = null, tint = WarningOrange)
                    UploadStatus.UPLOADING -> Text(
                        "${(displayedProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary
                    )
                    UploadStatus.WAITING_APPROVAL -> Icon(Icons.Default.HourglassEmpty, contentDescription = null, tint = WarningOrange)
                    UploadStatus.DONE -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                    UploadStatus.FAILED -> Icon(Icons.Default.Error, contentDescription = null, tint = ErrorRed)
                }
                if (upload.status == UploadStatus.UPLOADING || upload.paused) {
                    TextButton(onClick = { onTogglePause(upload.fileName) }) { Text(if (upload.paused) "استكمال" else "إيقاف") }
                }
            }
            if (upload.status == UploadStatus.UPLOADING) {
                Text("التقدم: ${(displayedProgress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(
                    progress = { displayedProgress },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                val transferStats = listOfNotNull(upload.speedLabel, upload.etaLabel).joinToString(" · ")
                if (transferStats.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(transferStats, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun statusLabel(status: UploadStatus): String = when (status) {
    UploadStatus.WAITING_FOR_CONNECTION -> "مستني اتصال بالكمبيوتر"
    UploadStatus.UPLOADING -> "بيترفع دلوقتي..."
    UploadStatus.WAITING_APPROVAL -> "مستني موافقتك على الاستبدال في الكمبيوتر"
    UploadStatus.DONE -> "اترفع بنجاح"
    UploadStatus.FAILED -> "فشل - هيتعاد المحاولة"
}
