@file:OptIn(
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.desktwin.app.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Palette
import kotlin.OptIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.desktwin.app.ui.theme.*

@Composable
fun SettingsScreen(
    computerName: String,
    isConnected: Boolean,
    onDisconnectClick: () -> Unit,
    onRequestMoreAccessClick: () -> Unit,
    onQuickActionsClick: () -> Unit
    ,onChatClick: () -> Unit
    ,onIntegrationHealthClick: () -> Unit
    ,onCommandDraftClick: () -> Unit,
    onTrashClick: () -> Unit,
    onActivityClick: () -> Unit
    ,onConflictsClick: () -> Unit
    ,onPhoneFolderClick: () -> Unit,
    onPhoneFullAccessClick: () -> Unit,
    themeMode: String,
    language: String,
    onThemeModeChange: (String) -> Unit,
    themePresetId: String,
    reduceMotion: Boolean,
    onReduceMotionChange: (Boolean) -> Unit,
    onThemesClick: () -> Unit,
    onLanguageChange: (String) -> Unit,
    onPlayIntro: () -> Unit,
    onChooseIntroVideo: () -> Unit,
    onResetIntroVideo: () -> Unit,
    hasCustomIntroVideo: Boolean,
    introMode: String,
    onIntroModeChange: (String) -> Unit,
    onShareDiagnosticsClick: () -> Unit,
    fileViewMode: String = "list",
    onFileViewModeChange: (String) -> Unit = {}
) {
    val activeThemePreset = deskTwinThemePreset(themePresetId)
    val appleStyle = activeThemePreset.artStyle == ThemeArtStyle.APPLE
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        val isWide = maxWidth >= 600.dp
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 760.dp)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (isWide) 32.dp else 20.dp, vertical = 20.dp)
        ) {
        Text(tr("الإعدادات", "Settings"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(24.dp))

        DeskTwinCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("سجل التشخيص", "Diagnostics log"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Text(tr("شارك آخر سجل عند ظهور مشكلة", "Share the latest log when something goes wrong"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onShareDiagnosticsClick) { Icon(Icons.Default.Share, contentDescription = tr("مشاركة السجل", "Share log")) }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(if (appleStyle) 16.dp else 14.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = if (appleStyle) 0.dp else 1.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(tr("الكمبيوتر المتصل", "Connected computer"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Text(computerName, style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    if (isConnected) tr("● متصل الآن", "● Connected") else tr("○ غير متصل", "○ Offline"),
                    color = if (isConnected) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(tr("المظهر واللغة", "Appearance & language"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        DeskTwinCard(Modifier.fillMaxWidth()) {
            Column {
                Text(tr("عرض الملفات", "File layout"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(tr("اختر بين قائمة واضحة وشبكة بطاقات", "Choose a readable list or a visual tile grid"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = fileViewMode == "list",
                        onClick = { onFileViewModeChange("list") },
                        label = { Text(tr("قائمة مستطيلة", "List rows")) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewList, null) }
                    )
                    FilterChip(
                        selected = fileViewMode == "grid",
                        onClick = { onFileViewModeChange("grid") },
                        label = { Text(tr("شبكة بطاقات", "Tile grid")) },
                        leadingIcon = { Icon(Icons.Default.GridView, null) }
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text(tr("المظهر", "Appearance"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(tr("اختر تجربة هادئة وواضحة تناسبك", "Choose a calm, clear experience that suits you"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = themeMode == "system", onClick = { onThemeModeChange("system") }, label = { Text(tr("النظام", "System")) }, leadingIcon = { Icon(Icons.Default.BrightnessAuto, null) })
                    FilterChip(selected = themeMode == "light", onClick = { onThemeModeChange("light") }, label = { Text(tr("فاتح", "Light")) }, leadingIcon = { Icon(Icons.Default.Build, null) })
                    FilterChip(selected = themeMode == "dark", onClick = { onThemeModeChange("dark") }, label = { Text(tr("داكن", "Dark")) }, leadingIcon = { Icon(Icons.Default.DarkMode, null) })
                }
                Text(
                    tr("بعض التصميمات تتبع مظهر النظام؛ والبعض الآخر يحتفظ بألوانه.", "Some themes follow the system appearance; others keep their own palette."),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                SettingsAction(
                    icon = Icons.Default.Palette,
                    title = tr("مكتبة التصميمات", "Theme library"),
                    subtitle = tr("${activeThemePreset.nameAr} · ألوان وخلفيات وخطوط وحركة", "${activeThemePreset.nameEn} · colors, backgrounds, fonts and motion"),
                    onClick = onThemesClick,
                    enabled = true
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("تقليل الحركة", "Reduce motion"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            tr("إيقاف الانتقالات والحركة الزخرفية", "Turn off transitions and ambient motion"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = reduceMotion, onCheckedChange = onReduceMotionChange)
                }
                Spacer(Modifier.height(14.dp))
                Text(tr("اللغة", "Language"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = language == "ar", onClick = { onLanguageChange("ar") }, label = { Text("العربية") }, leadingIcon = { Icon(Icons.Default.Language, null) })
                    FilterChip(selected = language == "en", onClick = { onLanguageChange("en") }, label = { Text("English") }, leadingIcon = { Icon(Icons.Default.Language, null) })
                }
                Spacer(Modifier.height(14.dp))
                Text(tr("تشغيل المقدمة", "Intro playback"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    tr("اختر تشغيلها تلقائيًا، يدويًا، أو إيقافها تمامًا", "Choose automatic, manual, or fully disabled playback"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(selected = introMode == "auto", onClick = { onIntroModeChange("auto") }, label = { Text(tr("تلقائي", "Automatic")) })
                    FilterChip(selected = introMode == "manual", onClick = { onIntroModeChange("manual") }, label = { Text(tr("يدوي", "Manual")) })
                    FilterChip(selected = introMode == "off", onClick = { onIntroModeChange("off") }, label = { Text(tr("إيقاف", "Off")) })
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = onPlayIntro, enabled = introMode != "off" && hasCustomIntroVideo, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("تشغيل فيديو المقدمة", "Play intro video"))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onChooseIntroVideo, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("اختيار فيديو مقدمة", "Choose intro video"))
                }
                if (hasCustomIntroVideo) {
                    TextButton(onClick = onResetIntroVideo, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("إزالة فيديو المقدمة", "Remove intro video"))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(tr("استخدام الكمبيوتر", "Use your computer"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        SettingsAction(Icons.Default.PowerSettingsNew, tr("التحكم السريع", "Quick controls"), tr("الصوت، السطوع، القفل والطاقة", "Sound, brightness, lock and power"), onQuickActionsClick, isConnected)
        SettingsAction(Icons.AutoMirrored.Filled.Chat, tr("المحادثة", "Chat"), tr("إرسال رسائل إلى الكمبيوتر", "Send messages to your computer"), onChatClick, isConnected)
        SettingsAction(Icons.Default.History, tr("النشاط", "Activity"), tr("آخر ما حدث على الكمبيوتر", "What happened recently on your computer"), onActivityClick, isConnected)
        SettingsAction(Icons.Default.Warning, tr("التعارضات", "Conflicts"), tr("اختيار النسخة الصحيحة عند اختلاف التعديلات", "Choose the correct version when edits differ"), onConflictsClick, isConnected)
        SettingsAction(Icons.Default.Delete, tr("سلة المحذوفات", "Trash"), tr("استعادة الملفات المحذوفة", "Restore deleted files"), onTrashClick, isConnected)
        SettingsAction(Icons.Default.Build, tr("مجلد ملفات الهاتف", "Phone files folder"), tr("اختيار المجلد الذي يراه الكمبيوتر", "Choose the phone folder your computer can see"), onPhoneFolderClick, true)
        Spacer(Modifier.height(8.dp))
        SettingsAction(
            Icons.Default.Build,
            tr("السماح بكل ملفات الهاتف", "Allow access to all phone files"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                tr("إظهار ملفات التخزين المشترك؛ سيفتح إعداد إذن الوصول في أندرويد", "Show shared-storage files; opens Android's access setting")
            else
                tr("على إصدار أندرويد ده، اختار مجلد الهاتف اللي الكمبيوتر يقدر يشوفه", "On this Android version, choose the phone folder your computer can access"),
            onPhoneFullAccessClick,
            true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(tr("متقدم", "Advanced"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        SettingsAction(Icons.Default.HealthAndSafety, tr("فحص الاتصال", "Connection check"), tr("حالة التوافق والصلاحيات", "Compatibility and permissions status"), onIntegrationHealthClick, isConnected)
        SettingsAction(Icons.Default.Build, tr("أوامر الكمبيوتر", "Computer commands"), tr("تنفيذ بعد مراجعتك وموافقتك على الكمبيوتر", "Runs only after your review and approval on the computer"), onCommandDraftClick, isConnected)

        // طلب توسيع النطاق - بيفتح شجرة الفولدرات لاختيار مجلد جديد،
        // والطلب بيتبعت للكمبيوتر كـ approval معلّق (مش تنفيذ فوري -
        // النطاق الآمن السابق أصلًا مبني على منع أي توسيع تلقائي).
        OutlinedButton(
            onClick = onRequestMoreAccessClick,
            enabled = isConnected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("اطلب إضافة مجلد من الكمبيوتر", "Request another computer folder"))
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = onDisconnectClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(tr("قطع الاتصال بهذا الكمبيوتر", "Disconnect this computer"))
        }
    }
    }
}

@Composable
private fun SettingsAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(if (LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE) 14.dp else 12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = if (LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        val appleStyle = LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE
        Row(modifier = Modifier.defaultMinSize(minHeight = 56.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (appleStyle) {
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(9.dp).size(22.dp))
                }
            } else {
                Icon(icon, contentDescription = title, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
