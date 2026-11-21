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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.desktwin.app.network.ConflictEntry
import com.desktwin.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    conflicts: List<ConflictEntry>,
    loading: Boolean,
    onRefresh: () -> Unit,
    onResolve: (ConflictEntry, String) -> Unit,
    onBack: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(title = { Text(tr("التعارضات", "Conflicts")) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("رجوع", "Back")) } }, actions = { IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, tr("تحديث", "Refresh")) } })
        if (loading) Text(tr("جارٍ تحميل التعارضات…", "Loading conflicts…"), modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!loading && conflicts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(tr("مفيش تعارضات محتاجة قرار", "There are no conflicts to resolve."), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(conflicts, key = { it.id }) { conflict ->
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(conflict.file_path.substringAfterLast('\\').substringAfterLast('/'), style = MaterialTheme.typography.titleMedium)
                            Text(conflict.file_path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            Spacer(Modifier.height(10.dp))
                            Text(tr("اختار النسخة التي تريد الاحتفاظ بها", "Choose which version to keep"), style = MaterialTheme.typography.bodyMedium)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { onResolve(conflict, "resolved_keep_pc") }, modifier = Modifier.weight(1f)) { Text(tr("نسخة الكمبيوتر", "Computer version")) }
                                Button(onClick = { onResolve(conflict, "resolved_keep_phone") }, modifier = Modifier.weight(1f)) { Text(tr("نسخة الهاتف", "Phone version")) }
                            }
                            TextButton(onClick = { onResolve(conflict, "resolved_manual") }, modifier = Modifier.align(androidx.compose.ui.Alignment.End)) { Text(tr("أقرر لاحقًا", "Decide later")) }
                        }
                    }
                }
            }
        }
    }
}
