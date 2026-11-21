package com.desktwin.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.desktwin.app.network.IntegrationHealthResponse
import com.desktwin.app.ui.theme.tr

@Composable
fun IntegrationHealthScreen(
    report: IntegrationHealthResponse?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onBack: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Column(
        Modifier
            .align(androidx.compose.ui.Alignment.TopCenter)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (maxWidth >= 600.dp) 32.dp else 20.dp, vertical = 20.dp)
    ) {
        Text(tr("فحص توافق DeskTwin", "DeskTwin compatibility check"), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(12.dp))
        if (loading) Text(tr("جارٍ فحص الاتصال…", "Checking connection…"))
        report?.let { r ->
            val healthy = r.status == "healthy"
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Icon(if (healthy) Icons.Default.CheckCircle else Icons.Default.Error, contentDescription = null, tint = if (healthy) com.desktwin.app.ui.theme.SuccessGreen else com.desktwin.app.ui.theme.WarningOrange)
                        Spacer(Modifier.width(8.dp))
                        Text(if (healthy) tr("الاتصال يعمل بشكل جيد", "Connection looks good") else tr("يحتاج الاتصال إلى مراجعة", "Connection needs attention"), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(14.dp))
                    HealthRow(tr("البروتوكول", "Protocol"), r.protocol_version)
                    HealthRow(tr("السيرفر", "Server"), r.server_version)
                    HealthRow(tr("الثقة", "Trust"), trustLabel(r.trust_level))
                    HealthRow("WebSocket", connectionLabel(r.websocket))
                    HealthRow(tr("المحادثة", "Chat"), connectionLabel(r.chat))
                }
            }
            Spacer(Modifier.height(12.dp))
            r.hints.forEach { hint ->
                Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${tr("اقتراح", "Suggestion")}: $hint", modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRefresh) { Text(tr("فحص مرة أخرى", "Check again")) }
        TextButton(onClick = onBack) { Text(tr("رجوع", "Back")) }
    }
    }
}

@Composable
private fun connectionLabel(value: String): String = when (value.lowercase()) {
    "connected", "available" -> tr("متصل", "Connected")
    "offline" -> tr("غير متصل", "Offline")
    "missing" -> tr("غير متاح", "Unavailable")
    else -> value
}

@Composable
private fun trustLabel(value: String): String = when (value.lowercase()) {
    "full" -> tr("كاملة", "Full")
    "trusted", "standard" -> tr("قياسية", "Standard")
    else -> value
}

@Composable
private fun HealthRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
