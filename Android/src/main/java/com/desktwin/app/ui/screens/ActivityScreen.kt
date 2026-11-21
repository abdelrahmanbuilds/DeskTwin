package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.desktwin.app.network.ActivityEntry
import com.desktwin.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityScreen(
    entries: List<ActivityEntry>,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(tr("النشاط", "Activity")) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("رجوع", "Back")) } },
            actions = { IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, contentDescription = tr("تحديث", "Refresh")) } }
        )
        if (isLoading) Text(tr("جارٍ تحديث النشاط…", "Refreshing activity…"), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (errorMessage != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.errorContainer
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        errorMessage,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                    TextButton(onClick = onRefresh) {
                        Text(tr("إعادة المحاولة", "Retry"), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (isLoading && entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(tr("جارٍ تحميل سجل النشاط…", "Loading activity…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (!isLoading && entries.isEmpty() && errorMessage == null) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(tr("مفيش نشاط لسه", "No activity yet"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(tr("هيظهر هنا سجل الاتصال ونقل الملفات.", "Connections and file transfers will appear here."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (!isLoading && entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(36.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(tr("تعذر تحميل النشاط", "Activity could not be loaded"), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                    Text(tr("استخدم إعادة المحاولة بالأعلى.", "Use Retry above to try again."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries, key = { it.id }) { entry -> ActivityRow(entry) }
            }
        }
    }
}

@Composable
private fun ActivityRow(entry: ActivityEntry) {
    val icon = when {
        entry.activity_type.contains("delete", true) -> Icons.Default.Delete
        entry.activity_type.contains("upload", true) -> Icons.Default.Upload
        entry.activity_type.contains("download", true) -> Icons.Default.Download
        entry.activity_type.contains("connect", true) -> Icons.Default.Link
        entry.activity_type.contains("rename", true) -> Icons.Default.Edit
        else -> Icons.Default.History
    }
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(activityLabel(entry.activity_type), style = MaterialTheme.typography.bodyLarge)
                entry.detail?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
                entry.created_at?.let { Text(it.replace("T", " ").take(19), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun activityLabel(type: String): String = when (type) {
    "browsed_folder" -> tr("تصفح مجلد", "Browsed folder")
    "opened_file", "open" -> tr("فتح ملف", "Opened file")
    "downloaded_file" -> tr("تحميل ملف", "Downloaded file")
    "uploaded_file" -> tr("رفع ملف", "Uploaded file")
    "delete", "requested_delete" -> tr("حذف ملف", "Deleted file")
    "rename", "requested_rename" -> tr("إعادة تسمية ملف", "Renamed file")
    "connected" -> tr("اتصال بالكمبيوتر", "Connected to computer")
    "disconnected" -> tr("انقطاع الاتصال", "Disconnected")
    else -> type.replace('_', ' ')
}
