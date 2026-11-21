package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.desktwin.app.network.BatchExecutionResult
import com.desktwin.app.network.TreeDiffResponse
import com.desktwin.app.data.QueuedOfflineCommand
import com.desktwin.app.ui.theme.tr

/**
 * شاشة مراجعة واحدة بعد كل إعادة اتصال، بتجمع نوعين مختلفين من التغييرات:
 *   1. Tree diff - إيه اتضاف/اتمسح/اتغيّر على الكمبيوتر وأنت أوفلاين
 *      (اكتشاف سلبي - حاجات حصلت من غير ما تطلبها)
 *   2. Offline commands - أوامر طلبتها إنت بنفسك وأنت أوفلاين (حذف/تسمية/
 *      تحميل/رفع) ولسه مستنية تتنفذ (طلبات إيجابية منك)
 *
 * المستخدم يقدر يوافق على الكل، يرفض الكل، أو يحدد عنصر عنصر. لو فيه
 * تعارض بعد الإرسال، الشاشة بتعرضه وتوقف لحد ما المستخدم يقرر.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncReviewScreen(
    treeDiff: TreeDiffResponse?,
    offlineCommands: List<QueuedOfflineCommand>,
    batchResult: BatchExecutionResult?,   // نتيجة آخر إرسال - null لسه ما بعتناش
    isSubmitting: Boolean,
    onApplyDiffAndSubmit: (
        approvedAddedPaths: Set<String>,
        approvedRemovedPaths: Set<String>,
        approvedModifiedPaths: Set<String>,
        approvedCommandIds: Set<String>
    ) -> Unit,
    onSkipConflictAndContinue: () -> Unit,
    onCancelRemaining: () -> Unit,
    onDismiss: () -> Unit
) {
    // لو فيه نتيجة دفعة شغالة (اتبعتت خلاص)، الشاشة تعرض حالة التنفيذ
    // بدل قائمة الاختيار العادية.
    if (batchResult != null) {
        BatchResultView(
            result = batchResult,
            onSkipConflictAndContinue = onSkipConflictAndContinue,
            onCancelRemaining = onCancelRemaining,
            onDone = onDismiss
        )
        return
    }

    var selectedAdded by remember(treeDiff) { mutableStateOf(treeDiff?.added?.map { it.path }?.toSet() ?: emptySet()) }
    var selectedRemoved by remember(treeDiff) { mutableStateOf(treeDiff?.removed?.toSet() ?: emptySet()) }
    var selectedModified by remember(treeDiff) { mutableStateOf(treeDiff?.modified?.map { it.path }?.toSet() ?: emptySet()) }
    var selectedCommands by remember(offlineCommands) { mutableStateOf(offlineCommands.map { it.id }.toSet()) }

    val totalItems = (treeDiff?.added?.size ?: 0) + (treeDiff?.removed?.size ?: 0) +
        (treeDiff?.modified?.size ?: 0) + offlineCommands.size
    val totalSelected = selectedAdded.size + selectedRemoved.size + selectedModified.size + selectedCommands.size

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(tr("مراجعة التحديثات", "Review updates")) },
            actions = {
                TextButton(onClick = {
                    // تحديد الكل
                    selectedAdded = treeDiff?.added?.map { it.path }?.toSet() ?: emptySet()
                    selectedRemoved = treeDiff?.removed?.toSet() ?: emptySet()
                    selectedModified = treeDiff?.modified?.map { it.path }?.toSet() ?: emptySet()
                    selectedCommands = offlineCommands.map { it.id }.toSet()
                }) { Text(tr("الكل", "All")) }
                TextButton(onClick = {
                    selectedAdded = emptySet(); selectedRemoved = emptySet()
                    selectedModified = emptySet(); selectedCommands = emptySet()
                }) { Text(tr("لا شيء", "None")) }
            }
        )

        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (totalItems == 0) tr("كل شيء متزامن", "Everything is in sync") else tr("راجع التغييرات قبل إرسالها للكمبيوتر", "Review changes before sending them to your computer"),
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground
                )
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            if (!treeDiff?.added.isNullOrEmpty()) {
                item { SectionHeader(tr("ملفات جديدة على الكمبيوتر", "New files on your computer"), Icons.Default.AddCircle, Color(0xFF4CAF50)) }
                items(treeDiff!!.added, key = { it.path }) { entry ->
                    CheckableRow(
                        title = entry.name, subtitle = entry.path,
                        checked = entry.path in selectedAdded,
                        onCheckedChange = { checked ->
                            selectedAdded = if (checked) selectedAdded + entry.path else selectedAdded - entry.path
                        }
                    )
                }
            }
            if (!treeDiff?.removed.isNullOrEmpty()) {
                item { SectionHeader(tr("ملفات اتمسحت من الكمبيوتر", "Files deleted from your computer"), Icons.Default.RemoveCircle, Color(0xFFE53935)) }
                items(treeDiff!!.removed, key = { it }) { path ->
                    CheckableRow(
                        title = path.substringAfterLast('\\').substringAfterLast('/'), subtitle = path,
                        checked = path in selectedRemoved,
                        onCheckedChange = { checked ->
                            selectedRemoved = if (checked) selectedRemoved + path else selectedRemoved - path
                        }
                    )
                }
            }
            if (!treeDiff?.modified.isNullOrEmpty()) {
                item { SectionHeader(tr("ملفات اتغيّرت على الكمبيوتر", "Files changed on your computer"), Icons.Default.Edit, Color(0xFFFFA000)) }
                items(treeDiff!!.modified, key = { it.path }) { entry ->
                    CheckableRow(
                        title = entry.name, subtitle = entry.path,
                        checked = entry.path in selectedModified,
                        onCheckedChange = { checked ->
                            selectedModified = if (checked) selectedModified + entry.path else selectedModified - entry.path
                        }
                    )
                }
            }
            if (offlineCommands.isNotEmpty()) {
                item { SectionHeader(tr("أوامر طلبتها وانت أوفلاين", "Commands requested while offline"), Icons.Default.PendingActions, Color(0xFF1E88E5)) }
                items(offlineCommands, key = { it.id }) { command ->
                    CheckableRow(
                        title = commandTypeLabel(command.commandType) + ": " + command.displayName,
                        subtitle = command.filePath,
                        checked = command.id in selectedCommands,
                        onCheckedChange = { checked ->
                            selectedCommands = if (checked) selectedCommands + command.id else selectedCommands - command.id
                        }
                    )
                }
            }
            if (totalItems == 0) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(tr("مفيش تحديثات محتاجة مراجعة", "No updates need review"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Surface(shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(tr("$totalSelected من $totalItems محدد", "$totalSelected of $totalItems selected"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    enabled = !isSubmitting && totalItems > 0,
                    onClick = {
                        onApplyDiffAndSubmit(selectedAdded, selectedRemoved, selectedModified, selectedCommands)
                    }
                ) {
                    Text(if (isSubmitting) tr("جارٍ التطبيق…", "Applying…") else tr("تطبيق", "Apply"))
                }
            }
        }
    }
}

@Composable
private fun BatchResultView(
    result: BatchExecutionResult,
    onSkipConflictAndContinue: () -> Unit,
    onCancelRemaining: () -> Unit,
    onDone: () -> Unit
) {
    val conflicted = result.commands.firstOrNull { it.status == "conflict" }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            if (result.status == "stopped_on_conflict") tr("حصل تعارض أثناء التنفيذ", "A conflict occurred during execution") else tr("خلصت كل التحديثات", "All updates are complete"),
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(result.commands, key = { it.id }) { command ->
                val (icon, color) = when (command.status) {
                    "executed" -> Icons.Default.CheckCircle to Color(0xFF4CAF50)
                    "conflict" -> Icons.Default.Error to Color(0xFFE53935)
                    "skipped" -> Icons.Default.SkipNext to Color.Gray
                    else -> Icons.Default.Schedule to Color.Gray
                }
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = color)
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(commandTypeLabel(command.command_type) + ": " + command.file_path.substringAfterLast('\\').substringAfterLast('/'))
                        if (command.status == "conflict" && command.conflict_reason != null) {
                            Text(command.conflict_reason, color = Color(0xFFE53935), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (conflicted != null) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancelRemaining, modifier = Modifier.weight(1f)) { Text(tr("إلغاء الباقي", "Cancel remaining")) }
                Button(onClick = onSkipConflictAndContinue, modifier = Modifier.weight(1f)) { Text(tr("تخطي وكمل", "Skip and continue")) }
            }
        } else {
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text(tr("تمام", "Done")) }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun CheckableRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
        }
    }
}

@Composable
private fun commandTypeLabel(type: String): String = when (type) {
    "delete" -> tr("حذف", "Delete")
    "rename" -> tr("إعادة تسمية", "Rename")
    "download" -> tr("تحميل", "Download")
    "upload" -> tr("رفع", "Upload")
    else -> type
}
