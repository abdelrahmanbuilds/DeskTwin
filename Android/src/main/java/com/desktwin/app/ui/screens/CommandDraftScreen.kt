package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.desktwin.app.ui.theme.DeskTwinScreenHeader
import com.desktwin.app.ui.theme.tr

@Composable
fun CommandDraftScreen(
    isConnected: Boolean,
    onBack: () -> Unit = {},
    onSubmit: (String, String, String, String) -> Unit
) {
    var shell by remember { mutableStateOf("cmd") }
    var command by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var userName by remember { mutableStateOf("") }
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(
        Modifier
            .align(Alignment.TopCenter)
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (maxWidth >= 600.dp) 40.dp else 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        DeskTwinScreenHeader(tr("مسودة أمر", "Command draft"), onBack)
        Text(tr("طلب تنفيذ أمر", "Request command execution"), style = MaterialTheme.typography.titleLarge)
        Text(tr(
            "سيُعرض الأمر على الكمبيوتر لمراجعته قبل التنفيذ. عند ترك مجلد العمل فارغًا سيُستخدم المجلد الافتراضي على الكمبيوتر. التنفيذ يحتاج موافقتك هناك ويعمل بصلاحيات حساب Windows الحالي.",
            "The computer will show the command for review before execution. Leave the working folder empty to use the computer's default. It runs only after approval there, under the current Windows account."
        ))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = shell == "cmd", onClick = { shell = "cmd" }, label = { Text("CMD") })
            FilterChip(selected = shell == "powershell", onClick = { shell = "powershell" }, label = { Text("PowerShell") })
        }
        OutlinedTextField(
            command, { command = it }, Modifier.fillMaxWidth(),
            label = { Text(tr("الأمر", "Command")) }, minLines = 3, maxLines = 8
        )
        OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text(tr("مجلد العمل (اختياري)", "Working folder (optional)")) })
        OutlinedTextField(userName, { userName = it }, Modifier.fillMaxWidth(), label = { Text(tr("اسم المستخدم للعرض", "Display name")) })
        Button(
            onClick = { onSubmit(shell, command, path, userName) },
            modifier = Modifier.fillMaxWidth(),
            enabled = isConnected && command.isNotBlank()
        ) {
            Text(tr("إرسال للتنفيذ بعد الموافقة", "Request execution"))
        }
    }
    }
}
