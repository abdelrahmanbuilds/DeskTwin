package com.desktwin.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.desktwin.app.network.TrashItem
import com.desktwin.app.ui.theme.DeskTwinCard
import com.desktwin.app.ui.theme.tr

@Composable
fun TrashScreen(items: List<TrashItem>, onRestore: (TrashItem) -> Unit, onPermanentDelete: (TrashItem) -> Unit, onRefresh: () -> Unit) {
    var pendingDeletion by remember { mutableStateOf<TrashItem?>(null) }
    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(tr("سلة المحذوفات", "Recycle Bin"), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onRefresh) { Text(tr("تحديث", "Refresh")) }
        }
        if (items.isEmpty()) {
            Text(tr("لا توجد عناصر مسجلة للاستعادة", "There are no restorable items"))
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items, key = { it.original_path }) { item ->
                    DeskTwinCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(item.original_path, Modifier.weight(1f))
                            TextButton(onClick = { onRestore(item) }) { Text(tr("استعادة", "Restore")) }
                            TextButton(onClick = { pendingDeletion = item }) { Text(tr("حذف نهائي", "Delete permanently"), color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }
    pendingDeletion?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDeletion = null },
            title = { Text(tr("حذف نهائي؟", "Delete permanently?")) },
            text = { Text(tr("سيُحذف العنصر نهائيًا من سلة محذوفات Windows ولن يمكن استعادته.", "This item will be permanently removed from the Windows Recycle Bin and cannot be restored.")) },
            confirmButton = { TextButton(onClick = { onPermanentDelete(item); pendingDeletion = null }) { Text(tr("حذف نهائي", "Delete permanently")) } },
            dismissButton = { TextButton(onClick = { pendingDeletion = null }) { Text(tr("إلغاء", "Cancel")) } }
        )
    }
}
