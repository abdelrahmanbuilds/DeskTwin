package com.desktwin.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.desktwin.app.network.DirEntry
import com.desktwin.app.ui.theme.*

/**
 * قائمة الخيارات اللي بتظهر بعد Long-press على ملف أو فولدر -
 * بديل الكليك يمين على الكمبيوتر. العمليات الحساسة (حذف/تسمية) بتحتاج
 * موافقة الكمبيوتر بعدين - القائمة دي بس بتبدأ الطلب.
 *
 * onRenameConfirmed بتاخد الاسم الجديد فعليًا (مش نفس الاسم القديم) -
 * حوار إدخال الاسم بيظهر جوه الشيت نفسه قبل ما ينادي الـ callback،
 * عشان الطلب اللي بيتبعت للكمبيوتر يحمل قيمة حقيقية غير اسم الملف الحالي.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntryContextMenuSheet(
    entry: DirEntry,
    onDismiss: () -> Unit,
    onOpenClick: () -> Unit,
    onInfoClick: () -> Unit,
    onRenameConfirmed: (newName: String) -> Unit,
    onDeleteClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onUploadHereClick: () -> Unit,
    onCopyClick: () -> Unit = {},
    onCutClick: () -> Unit = {}
) {
    var showRenameDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (entry.is_folder) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                    contentDescription = null,
                    tint = if (entry.is_folder) WarningOrange else MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(entry.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outline)

            MenuOption(Icons.AutoMirrored.Filled.OpenInNew, if (entry.is_folder) tr("افتح", "Open") else tr("افتح مؤقتًا", "Open temporarily"), onOpenClick)
            MenuOption(Icons.Default.Info, tr("المعلومات", "Details"), onInfoClick)

            if (!entry.is_folder) {
                MenuOption(Icons.Default.CloudDownload, tr("نزّله دايمًا على التليفون", "Keep on phone"), onDownloadClick)
            } else {
                MenuOption(Icons.Default.Upload, tr("ارفع ملف هنا", "Upload here"), onUploadHereClick)
            }
            MenuOption(Icons.Default.ContentCopy, tr("نسخ", "Copy"), onCopyClick)
            MenuOption(Icons.Default.ContentCut, tr("قص", "Cut"), onCutClick)

            MenuOption(Icons.Default.Edit, tr("إعادة تسمية", "Rename"), { showRenameDialog = true })
            MenuOption(Icons.Default.Delete, tr("حذف", "Delete"), onDeleteClick, tint = ErrorRed)
        }
    }

    if (showRenameDialog) {
        RenameDialog(
            currentName = entry.name,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newName ->
                showRenameDialog = false
                onRenameConfirmed(newName)
            }
        )
    }
}

/**
 * حوار بسيط بياخد الاسم الجديد فعليًا من المستخدم. الاسم الجديد لازم
 * يختلف عن القديم ويكون غير فاضي قبل ما زرار "تأكيد" يتفعّل - منعًا
 * لإرسال طلب موافقة للكمبيوتر باسم مطابق أو فاضي بلا فايدة.
 */
@Composable
private fun RenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName) }
    val isValid = newName.isNotBlank() && newName != currentName

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("إعادة تسمية", "Rename")) },
        text = {
            Column {
                Text(tr("الاسم الحالي: $currentName", "Current name: $currentName"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text(tr("الاسم الجديد", "New name")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(newName) }, enabled = isValid) {
                Text(tr("تأكيد", "Confirm"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(tr("إلغاء", "Cancel")) }
        }
    )
}

@Composable
private fun MenuOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: androidx.compose.ui.graphics.Color? = null
) {
    val contentTint = tint ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = contentTint, modifier = Modifier.size(22.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = contentTint)
    }
}


