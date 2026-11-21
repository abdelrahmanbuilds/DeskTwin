package com.desktwin.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.desktwin.app.ui.theme.*

@Composable
fun QuickActionsScreen(
    isConnected: Boolean,
    onAction: (String) -> Unit
) {
    var pendingDangerousAction by remember { mutableStateOf<ActionItem?>(null) }

    val sections = listOf(
        ActionSection(tr("الوصول السريع", "Quick access"), Icons.AutoMirrored.Filled.Launch, listOf(
            ActionItem("this_pc", tr("هذا الكمبيوتر", "This PC"), Icons.Default.Computer),
            ActionItem("recycle_bin", tr("سلة المحذوفات", "Recycle Bin"), Icons.Default.Delete),
            ActionItem("control_panel", tr("لوحة التحكم", "Control Panel"), Icons.Default.Tune),
            ActionItem("settings", tr("إعدادات Windows", "Windows Settings"), Icons.Default.Settings),
            ActionItem("microsoft_store", tr("المتجر", "Microsoft Store"), Icons.Default.Store)
        )),
        ActionSection(tr("عناصر التحكم", "Controls"), Icons.Default.Tune, listOf(
            ActionItem("volume_up", tr("رفع الصوت", "Volume up"), Icons.AutoMirrored.Filled.VolumeUp),
            ActionItem("volume_down", tr("خفض الصوت", "Volume down"), Icons.AutoMirrored.Filled.VolumeDown),
            ActionItem("volume_mute", tr("كتم الصوت", "Mute"), Icons.AutoMirrored.Filled.VolumeOff),
            ActionItem("brightness_up", tr("رفع السطوع", "Brightness up"), Icons.Default.BrightnessHigh),
            ActionItem("brightness_down", tr("خفض السطوع", "Brightness down"), Icons.Default.BrightnessLow),
            ActionItem("refresh", tr("تحديث الملفات", "Refresh files"), Icons.Default.Refresh)
        )),
        ActionSection(tr("جلسة الكمبيوتر", "Computer session"), Icons.Default.PowerSettingsNew, listOf(
            ActionItem("lock", tr("قفل الكمبيوتر", "Lock computer"), Icons.Default.Lock, dangerous = true),
            ActionItem("logout", tr("تسجيل الخروج", "Sign out"), Icons.AutoMirrored.Filled.Logout, dangerous = true),
            ActionItem("restart", tr("إعادة التشغيل", "Restart"), Icons.Default.RestartAlt, dangerous = true),
            ActionItem("shutdown", tr("إيقاف التشغيل", "Shut down"), Icons.Default.PowerSettingsNew, dangerous = true)
        ))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            tr("التحكم في الكمبيوتر", "Control your computer"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))
        Text(
            tr("إجراءات سريعة للكمبيوتر المتصل.", "Quick actions for your paired computer."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        ConnectionStatus(isConnected)
        Spacer(Modifier.height(18.dp))

        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 900.dp -> 4
                    maxWidth >= 600.dp -> 3
                    else -> 2
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    sections.forEach { section ->
                        ActionSectionCard(
                            section = section,
                            columns = columns,
                            enabled = isConnected,
                            onAction = { item ->
                                if (item.dangerous) pendingDangerousAction = item else onAction(item.action)
                            }
                        )
                    }
                }
            }
        }
    }

    pendingDangerousAction?.let { item ->
        val consequence = when (item.action) {
            "lock" -> tr("سيُقفل الكمبيوتر، وستحتاج إلى فتحه من جديد.", "The computer will lock and need to be unlocked again.")
            "logout" -> tr("ستنتهي جلسة Windows الحالية وقد تُغلق التطبيقات المفتوحة.", "Your Windows session will end and open apps may close.")
            "restart" -> tr("سيُعاد تشغيل الكمبيوتر وقد تفقد العمل غير المحفوظ.", "The computer will restart and unsaved work may be lost.")
            else -> tr("سيُغلق الكمبيوتر وقد تفقد العمل غير المحفوظ.", "The computer will shut down and unsaved work may be lost.")
        }
        val confirmationQuestion = when (item.action) {
            "lock" -> tr("هل تريد قفل الكمبيوتر؟", "Do you want to lock the computer?")
            "logout" -> tr("هل تريد تسجيل الخروج؟", "Do you want to sign out?")
            "restart" -> tr("هل تريد إعادة تشغيل الكمبيوتر؟", "Do you want to restart the computer?")
            else -> tr("هل تريد إيقاف تشغيل الكمبيوتر؟", "Do you want to shut down the computer?")
        }
        AlertDialog(
            onDismissRequest = { pendingDangerousAction = null },
            icon = { Icon(item.icon, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(tr("تأكيد على الكمبيوتر", "Confirm on computer")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(confirmationQuestion, fontWeight = FontWeight.SemiBold)
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            consequence,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { pendingDangerousAction = null; onAction(item.action) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) { Text(item.confirmLabel) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDangerousAction = null }) { Text(tr("رجوع للأمان", "Keep computer on")) }
            }
        )
    }
}

@Composable
private fun ConnectionStatus(isConnected: Boolean) {
    val container = if (isConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val content = if (isConnected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val statusDesc = if (isConnected) tr("حالة الاتصال: الكمبيوتر متصل", "Connection status: computer connected")
    else tr("حالة الاتصال: الكمبيوتر غير متصل", "Connection status: computer disconnected")
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = container,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = statusDesc
        }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                if (isConnected) Icons.Default.CheckCircle else Icons.Default.LinkOff,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(20.dp)
            )
            Text(
                if (isConnected) tr("متصل — الإجراءات جاهزة", "Connected — actions are ready")
                else tr("غير متصل — اتصل بالكمبيوتر لاستخدام الإجراءات", "Not connected — connect to use these actions"),
                color = content,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ActionSectionCard(
    section: ActionSection,
    columns: Int,
    enabled: Boolean,
    onAction: (ActionItem) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Icon(
                        section.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(8.dp).size(18.dp)
                    )
                }
                Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            section.items.chunked(columns).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    rowItems.forEach { item ->
                        ActionTile(
                            item = item,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                            onClick = { onAction(item) }
                        )
                    }
                    repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
                Spacer(Modifier.height(9.dp))
            }
        }
    }
}

private data class ActionSection(val title: String, val icon: ImageVector, val items: List<ActionItem>)
private data class ActionItem(
    val action: String,
    val label: String,
    val icon: ImageVector,
    val dangerous: Boolean = false
) {
    val confirmLabel: String
        @Composable
        get() = when (action) {
            "lock" -> tr("قفل الكمبيوتر", "Lock computer")
            "logout" -> tr("تسجيل الخروج", "Sign out")
            "restart" -> tr("إعادة التشغيل", "Restart")
            else -> tr("إيقاف التشغيل", "Shut down")
        }
}

@Composable
private fun ActionTile(item: ActionItem, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val container = when {
        !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        item.dangerous -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.52f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    }
    val foreground = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.62f)
        item.dangerous -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val actionDesc = if (item.dangerous) tr("${item.label}، إجراء مهم", "${item.label}, important action") else item.label
    val stateDesc = if (enabled) tr("متاح", "Available") else tr("يتطلب الاتصال بالكمبيوتر", "Requires computer connection")
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        color = container,
        border = BorderStroke(
            1.dp,
            if (item.dangerous) MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 0.24f else 0.10f)
            else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
        ),
        modifier = modifier
            .heightIn(min = 92.dp)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = actionDesc
                stateDescription = stateDesc
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(item.icon, contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                item.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                textAlign = TextAlign.Center
            )
        }
    }
}
