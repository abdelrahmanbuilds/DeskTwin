package com.desktwin.app.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.PI
import com.desktwin.app.ui.theme.DeskTwinCard
import com.desktwin.app.ui.theme.DeskTwinThemePreset
import com.desktwin.app.ui.theme.DeskTwinThemePresets
import com.desktwin.app.ui.theme.ThemeAmbientArtwork
import com.desktwin.app.ui.theme.ThemeArtStyle
import com.desktwin.app.ui.theme.ThemeModePolicy
import com.desktwin.app.ui.theme.ThemeSoundPlayer
import com.desktwin.app.ui.theme.LocalDeskTwinLanguage
import com.desktwin.app.ui.theme.themeFontFamily
import com.desktwin.app.ui.theme.themeIdleMotion
import com.desktwin.app.ui.theme.tr
import com.desktwin.app.R

@Composable
fun ThemeLibraryScreen(
    selectedThemeId: String,
    isDarkTheme: Boolean,
    reduceMotion: Boolean,
    themeSoundUris: Map<String, String>,
    onBackClick: () -> Unit,
    onApplyTheme: (String) -> Unit,
    onSaveThemeSound: (String, String?) -> Unit
) {
    var surpriseTheme by remember { mutableStateOf<DeskTwinThemePreset?>(null) }
    var soundTargetTheme by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val audioPermissionError = tr("تعذر حفظ صلاحية ملف الصوت", "Could not keep access to this audio file")
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val themeId = soundTargetTheme
        soundTargetTheme = null
        if (uri != null && themeId != null) {
            val canPersist = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.isSuccess
            if (canPersist) {
                themeSoundUris[themeId]?.takeIf { it != uri.toString() }?.let { previous ->
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            android.net.Uri.parse(previous), Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                }
                onSaveThemeSound(themeId, uri.toString())
            }
            else Toast.makeText(context, audioPermissionError, Toast.LENGTH_LONG).show()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick, modifier = Modifier.themeIdleMotion(0.65f)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("رجوع", "Back"))
            }
            Column(Modifier.weight(1f)) {
                Text(tr("مكتبة التصميمات", "Theme library"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    tr("بعض التصميمات تتبع مظهر النظام؛ والبعض الآخر يحتفظ بألوانه.", "Some themes follow the system appearance; others keep their own palette."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(DeskTwinThemePresets, key = { it.id }) { preset ->
                val selected = preset.id == selectedThemeId
                DeskTwinCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tr(preset.nameAr, preset.nameEn), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(tr(preset.descriptionAr, preset.descriptionEn), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(
                                onClick = {
                                    ThemeSoundPlayer.play(preset.id, context, themeSoundUris[preset.id])
                                    surpriseTheme = preset
                                },
                                modifier = Modifier.themeIdleMotion(0.65f),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                            ) {
                                ThemeSignature(
                                    preset,
                                    Modifier.size(26.dp),
                                    accent = MaterialTheme.colorScheme.onSurface,
                                    cutoutColor = MaterialTheme.colorScheme.surface
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(tr("المشهد السري", "Easter Egg"), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        val previewIsDarkTheme = when (preset.modePolicy) {
                            ThemeModePolicy.FOLLOW_APP -> isDarkTheme
                            ThemeModePolicy.DARK -> true
                            ThemeModePolicy.LIGHT -> false
                        }
                        ThemePreview(preset, previewIsDarkTheme, reduceMotion)
                        Spacer(Modifier.height(10.dp))
                        ThemeSoundControls(
                            preset = preset,
                            resetAccent = if (preset.artStyle == ThemeArtStyle.APPLE && previewIsDarkTheme) preset.accentDark else preset.accent,
                            hasCustomSound = themeSoundUris.containsKey(preset.id),
                            onChoose = {
                                soundTargetTheme = preset.id
                                audioPicker.launch(arrayOf("audio/*"))
                            },
                            onPreview = { ThemeSoundPlayer.play(preset.id, context, themeSoundUris[preset.id]) },
                            onStop = { ThemeSoundPlayer.stop() },
                            onReset = {
                                ThemeSoundPlayer.stop()
                                themeSoundUris[preset.id]?.let { savedUri ->
                                    runCatching {
                                        context.contentResolver.releasePersistableUriPermission(
                                            android.net.Uri.parse(savedUri), Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        )
                                    }
                                }
                                onSaveThemeSound(preset.id, null)
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr("خط ${fontLabel(preset.id, true)} · حركة ${motionLabel(preset.id)}", "${fontLabel(preset.id, false)} type · ${motionLabel(preset.id)} motion"),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { onApplyTheme(preset.id) },
                                modifier = Modifier.themeIdleMotion(0.8f),
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = preset.accent, contentColor = Color.White)
                            ) {
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(17.dp))
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text(if (selected) tr("مفعّل", "Active") else tr("تفعيل", "Apply"))
                            }
                        }
                    }
                }
            }
        }
    }

    surpriseTheme?.let { preset ->
        ThemeEasterEggDialog(preset, reduceMotion, onDismiss = { ThemeSoundPlayer.stop(); surpriseTheme = null })
    }
}

@Composable
private fun ThemeSoundControls(
    preset: DeskTwinThemePreset,
    resetAccent: Color,
    hasCustomSound: Boolean,
    onChoose: () -> Unit,
    onPreview: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("صوت الثيم", "Theme sound"), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hasCustomSound) tr("مؤثر مخصص محفوظ", "Custom sound saved") else tr("المؤثر الأصلي", "Original sound effect"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = onPreview, modifier = Modifier.themeIdleMotion(0.65f)) { Text(tr("استماع", "Preview")) }
                if (hasCustomSound) TextButton(onClick = onStop, modifier = Modifier.themeIdleMotion(0.65f)) { Text(tr("إيقاف", "Stop")) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onChoose, modifier = Modifier.weight(1f).themeIdleMotion(0.65f)) {
                    Text(if (hasCustomSound) tr("تغيير الملف الصوتي", "Change sound file") else tr("اختيار ملف صوتي", "Choose sound file"), maxLines = 1)
                }
                if (hasCustomSound) {
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = onReset, modifier = Modifier.themeIdleMotion(0.65f)) { Text(tr("استعادة", "Reset"), color = resetAccent) }
                }
            }
        }
    }
}

@Composable
private fun ThemePreview(preset: DeskTwinThemePreset, isDarkTheme: Boolean, reduceMotion: Boolean) {
    val themeFont = themeFontFamily(preset, LocalDeskTwinLanguage.current)
    val background = if (isDarkTheme) preset.darkBackground else preset.lightBackground
    val surface = if (isDarkTheme) preset.darkSurface else preset.lightSurface
    val text = if (isDarkTheme) preset.darkText else preset.lightText
    val accent = if (isDarkTheme) preset.accentDark else preset.accent
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = background,
        modifier = Modifier.fillMaxWidth().height(124.dp)
    ) {
        Box {
            if (preset.artStyle != ThemeArtStyle.APPLE) {
                Image(
                    painter = painterResource(preset.backdropRes),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (preset.artStyle != ThemeArtStyle.APPLE) {
                Box(Modifier.fillMaxSize().background(background.copy(alpha = 0.58f)))
                ThemeAmbientArtwork(preset, isDarkTheme, reduceMotion, Modifier.fillMaxSize())
                Box(
                    Modifier.align(Alignment.TopEnd).size(130.dp).clip(CircleShape)
                        .background(preset.secondary.copy(alpha = 0.10f))
                )
            }
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("DeskTwin", color = text, style = MaterialTheme.typography.labelLarge.copy(fontFamily = themeFont), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    ThemeSignature(preset, Modifier.size(26.dp), text, cutoutColor = background)
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f).fillMaxWidth()) {
                    PreviewTile(preset, Modifier.weight(1f), title = tr("الملفات", "Files"), icon = "▰", surface = surface, text = text, accent = accent)
                    PreviewTile(preset, Modifier.weight(1f), title = tr("المحادثة", "Chat"), icon = "◌", surface = surface, text = text, accent = accent)
                    PreviewTile(preset, Modifier.weight(1f), title = tr("الأجهزة", "Devices"), icon = "⌁", surface = surface, text = text, accent = accent)
                }
            }
        }
    }
}

@Composable
private fun PreviewTile(
    preset: DeskTwinThemePreset,
    modifier: Modifier,
    title: String,
    icon: String,
    surface: Color,
    text: Color,
    accent: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(if (preset.artStyle == ThemeArtStyle.APPLE) 14.dp else 13.dp),
        color = if (preset.artStyle == ThemeArtStyle.ORBIT) surface.copy(alpha = 0.88f) else surface,
        border = if (preset.artStyle == ThemeArtStyle.APPLE) null else BorderStroke(1.dp, accent.copy(alpha = 0.12f))
    ) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 7.dp), verticalArrangement = Arrangement.Center) {
            Text(icon, color = accent, style = MaterialTheme.typography.titleMedium.copy(fontFamily = themeFontFamily(preset, LocalDeskTwinLanguage.current)), fontWeight = FontWeight.Bold)
            Text(title, color = text, style = MaterialTheme.typography.labelSmall.copy(fontFamily = themeFontFamily(preset, LocalDeskTwinLanguage.current)), maxLines = 1)
        }
    }
}

@Composable
private fun ThemeSignature(
    preset: DeskTwinThemePreset,
    modifier: Modifier = Modifier,
    accent: Color = preset.accent,
    cutoutColor: Color = Color.White
) {
    Canvas(modifier) {
        val unit = size.minDimension
        val center = Offset(size.width / 2f, size.height / 2f)
        when (preset.artStyle) {
            ThemeArtStyle.ORBIT -> {
                val star = Path().apply {
                    for (index in 0 until 12) {
                        val angle = (-PI / 2.0 + index * PI / 6.0)
                        val radius = unit * if (index % 3 == 0) 0.47f else 0.20f
                        val point = Offset(center.x + kotlin.math.cos(angle).toFloat() * radius, center.y + kotlin.math.sin(angle).toFloat() * radius)
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                    close()
                }
                drawCircle(accent.copy(alpha = 0.16f), unit * 0.47f, center, style = Stroke(unit * 0.025f))
                drawPath(star, accent.copy(alpha = 0.92f))
                drawCircle(Color.White.copy(alpha = 0.88f), unit * 0.045f, center)
            }
            ThemeArtStyle.CODE -> {
                val gold = Color(0xFFFFC247)
                repeat(6) { index ->
                    val angle = (index * 60f - 90f) * (PI / 180.0)
                    val petalCenter = Offset(
                        center.x + kotlin.math.cos(angle).toFloat() * unit * 0.16f,
                        center.y + kotlin.math.sin(angle).toFloat() * unit * 0.16f
                    )
                    rotate(index * 60f, petalCenter) {
                        drawOval(
                            accent.copy(alpha = 0.72f),
                            Offset(petalCenter.x - unit * 0.22f, petalCenter.y - unit * 0.12f),
                            androidx.compose.ui.geometry.Size(unit * 0.44f, unit * 0.24f),
                            style = Stroke(unit * 0.045f)
                        )
                    }
                }
                drawCircle(accent.copy(alpha = 0.18f), unit * 0.20f, center)
                drawCircle(gold.copy(alpha = 0.9f), unit * 0.045f, center)
                for (index in 0 until 4) {
                    val angle = (index * 90f - 45f) * (PI / 180.0)
                    drawCircle(gold.copy(alpha = 0.66f), unit * 0.035f, Offset(
                        center.x + kotlin.math.cos(angle).toFloat() * unit * 0.46f,
                        center.y + kotlin.math.sin(angle).toFloat() * unit * 0.46f
                    ))
                }
            }
            ThemeArtStyle.EMBER -> {
                for (index in 0 until 8) {
                    rotate(index * 45f, center) {
                        drawRoundRect(
                            accent.copy(alpha = 0.9f),
                            Offset(center.x - unit * 0.055f, center.y - unit * 0.45f),
                            androidx.compose.ui.geometry.Size(unit * 0.11f, unit * 0.19f),
                            androidx.compose.ui.geometry.CornerRadius(unit * 0.045f)
                        )
                    }
                }
                drawCircle(accent, unit * 0.10f, center)
                drawCircle(Color.White.copy(alpha = 0.86f), unit * 0.035f, center)
            }
            ThemeArtStyle.ORCHARD -> {
                val leaf = Path().apply {
                    moveTo(size.width * 0.16f, center.y + unit * 0.20f)
                    cubicTo(size.width * 0.22f, size.height * 0.23f, size.width * 0.65f, size.height * 0.12f, size.width * 0.83f, size.height * 0.23f)
                    cubicTo(size.width * 0.77f, size.height * 0.63f, size.width * 0.43f, size.height * 0.83f, size.width * 0.16f, center.y + unit * 0.20f)
                    close()
                }
                drawPath(leaf, accent)
                drawLine(accent.copy(alpha = 0.72f), Offset(size.width * 0.19f, center.y + unit * 0.18f), Offset(size.width * 0.77f, size.height * 0.25f), unit * 0.035f)
            }
            ThemeArtStyle.APPLE -> {
                val left = Offset(unit * 0.32f, center.y)
                val right = Offset(unit * 0.68f, center.y)
                val nodeRadius = unit * 0.18f
                drawCircle(accent, nodeRadius, left, style = Stroke(unit * 0.055f))
                drawCircle(accent, nodeRadius, right, style = Stroke(unit * 0.055f))
                drawLine(
                    accent,
                    Offset(left.x + nodeRadius * 0.64f, center.y),
                    Offset(right.x - nodeRadius * 0.64f, center.y),
                    strokeWidth = unit * 0.055f
                )
                drawCircle(cutoutColor, unit * 0.035f, center)
            }
            ThemeArtStyle.LINK -> {
                val deviceWidth = unit * 0.34f
                val deviceHeight = unit * 0.58f
                drawRoundRect(accent, Offset(center.x - unit * 0.43f, center.y - deviceHeight / 2f), androidx.compose.ui.geometry.Size(deviceWidth, deviceHeight), androidx.compose.ui.geometry.CornerRadius(unit * 0.06f), style = Stroke(unit * 0.055f))
                drawRoundRect(preset.secondary, Offset(center.x + unit * 0.09f, center.y - deviceHeight * 0.34f), androidx.compose.ui.geometry.Size(deviceWidth, deviceHeight * 0.68f), androidx.compose.ui.geometry.CornerRadius(unit * 0.045f), style = Stroke(unit * 0.055f))
                drawLine(preset.secondary, Offset(center.x - unit * 0.08f, center.y), Offset(center.x + unit * 0.08f, center.y), unit * 0.055f)
                drawCircle(Color.White, unit * 0.035f, center)
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun ThemeEasterEggDialog(preset: DeskTwinThemePreset, reduceMotion: Boolean, onDismiss: () -> Unit) {
    val isApple = preset.artStyle == ThemeArtStyle.APPLE
    val forceLandscape = preset.artStyle == ThemeArtStyle.ORBIT || preset.artStyle == ThemeArtStyle.CODE
    val context = LocalContext.current
    val hostActivity = remember(context) { context.findHostActivity() }
    val previousOrientation = remember(hostActivity) { hostActivity?.requestedOrientation }
    DisposableEffect(hostActivity, forceLandscape) {
        if (forceLandscape) hostActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            if (forceLandscape && previousOrientation != null) hostActivity?.requestedOrientation = previousOrientation
        }
    }
    val revealProgress = androidx.compose.runtime.remember(preset.id) { Animatable(0f) }
    val ambientTransition = rememberInfiniteTransition(label = "${preset.id}-scene-ambient")
    val ambientPhase: State<Float> = if (reduceMotion) {
        remember(preset.id) { mutableStateOf(0f) }
    } else {
        ambientTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(7_000, easing = LinearEasing), RepeatMode.Restart),
            label = "${preset.id}-scene-phase"
        )
    }
    LaunchedEffect(preset.id, reduceMotion) {
        if (reduceMotion) {
            revealProgress.snapTo(1f)
        } else {
            revealProgress.snapTo(0f)
            val duration = when (preset.artStyle) {
                ThemeArtStyle.ORBIT -> 2_500
                ThemeArtStyle.CODE -> 1_850
                ThemeArtStyle.EMBER -> 2_350
                ThemeArtStyle.ORCHARD -> 2_250
                ThemeArtStyle.APPLE -> 2_150
                ThemeArtStyle.LINK -> 2_000
            }
            revealProgress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
        }
    }
    val progress = revealProgress.value
    val transitionSurface = if (isApple) {
        lerp(Color.Black, Color.White, sceneProgress(progress, 0.08f, 0.48f))
    } else MaterialTheme.colorScheme.surface
    val markProgress = sceneProgress(progress, 0.10f, 0.66f)
    val copyProgress = sceneProgress(progress, 0.68f, 0.90f)
    val markScale = when (preset.artStyle) {
        ThemeArtStyle.APPLE -> 0.18f + 0.82f * markProgress
        ThemeArtStyle.CODE -> 0.62f + 0.38f * markProgress
        ThemeArtStyle.EMBER -> 0.35f + 0.65f * markProgress
        else -> 0.2f + 0.8f * markProgress
    }
    val markRotation = when (preset.artStyle) {
        ThemeArtStyle.ORBIT -> 360f * markProgress
        ThemeArtStyle.CODE -> -12f + 12f * markProgress
        ThemeArtStyle.EMBER -> 54f - 54f * markProgress
        ThemeArtStyle.LINK -> -24f + 24f * markProgress
        ThemeArtStyle.ORCHARD, ThemeArtStyle.APPLE -> 0f
    }
    val markShift = sceneProgress(progress, 0.56f, 0.79f)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RectangleShape,
            color = transitionSurface,
            tonalElevation = 0.dp
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val isWide = maxWidth > maxHeight
                ImmersiveSceneWindow()
                if (!isApple) {
                    ThemeSceneBackdrop(preset, Modifier.fillMaxSize())
                }
                ThemeEasterScene(preset, progress, ambientPhase, Modifier.fillMaxSize())
                if (isApple) {
                    AppleThemeEasterScene(isWide, preset, markScale, markShift, copyProgress, onDismiss)
                } else {
                    CinematicThemeScene(
                        preset = preset,
                        progress = progress,
                        ambientPhase = ambientPhase,
                        markScale = markScale,
                        markRotation = markRotation,
                        copyProgress = copyProgress,
                        onDismiss = onDismiss
                    )
                }
            }
        }
    }
}

@Composable
private fun AppleThemeEasterScene(
    isWide: Boolean,
    preset: DeskTwinThemePreset,
    markScale: Float,
    markShift: Float,
    copyProgress: Float,
    onDismiss: () -> Unit
) {
    val appleSize = if (isWide) 230.dp else 205.dp
    val phrase = tr("دي مجرد مزحة.", "It’s just a joke.")
    val font = themeFontFamily(preset, LocalDeskTwinLanguage.current)
    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp)) {
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(tr("تخطي المشهد", "Skip scene"), color = Color(0xFF6E6E73))
        }
        if (isWide) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1.1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    OrchardAppleMark(
                        modifier = Modifier.size(appleSize).graphicsLayer {
                            scaleX = markScale
                            scaleY = markScale
                            translationX = -110.dp.toPx() * markShift
                        },
                        cutoutColor = Color.White
                    )
                }
                Column(Modifier.weight(0.9f).padding(start = 18.dp), verticalArrangement = Arrangement.Center) {
                    Text("APPLE  /  MONOCHROME", color = Color(0xFF6E6E73), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Text(phrase, modifier = Modifier.alpha(copyProgress), color = Color(0xFF1D1D1F), style = MaterialTheme.typography.headlineMedium.copy(fontFamily = font), fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Spacer(Modifier.weight(0.16f))
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                OrchardAppleMark(
                    modifier = Modifier.size(appleSize).scale(markScale),
                    cutoutColor = Color.White
                )
            }
            Text("APPLE  /  MONOCHROME", modifier = Modifier.align(Alignment.CenterHorizontally), color = Color(0xFF6E6E73), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text(phrase, modifier = Modifier.align(Alignment.CenterHorizontally).alpha(copyProgress), color = Color(0xFF1D1D1F), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = font), fontWeight = FontWeight.Bold)
            Text(
                tr(preset.surpriseAr, preset.surpriseEn),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp).alpha(copyProgress),
                color = Color(0xFF6E6E73),
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = font)
            )
            Spacer(Modifier.weight(0.22f))
        }
    }
}

private fun sceneProgress(progress: Float, start: Float, end: Float): Float =
    ((progress - start) / (end - start)).coerceIn(0f, 1f)

@Composable
private fun ThemeSceneBackdrop(preset: DeskTwinThemePreset, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val landscape = maxWidth > maxHeight
        val imageResource = when (preset.artStyle) {
            ThemeArtStyle.ORBIT -> if (landscape) R.drawable.theme_cosmic_landscape else preset.backdropRes
            ThemeArtStyle.CODE -> if (landscape) R.drawable.theme_codex_landscape else preset.backdropRes
            else -> preset.backdropRes
        }
        Image(
            painter = painterResource(imageResource),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(0.42f)
        )
        Box(Modifier.fillMaxSize().background(preset.darkBackground.copy(alpha = 0.52f)))
    }
}

@Composable
private fun CinematicThemeScene(
    preset: DeskTwinThemePreset,
    progress: Float,
    ambientPhase: State<Float>,
    markScale: Float,
    markRotation: Float,
    copyProgress: Float,
    onDismiss: () -> Unit
) {
    val font = themeFontFamily(preset, LocalDeskTwinLanguage.current)
    val sceneName = when (preset.artStyle) {
        ThemeArtStyle.ORBIT -> tr("مدار زجاجي", "COSMIC GLASS")
        ThemeArtStyle.CODE -> tr("نبضة الذكاء", "CODEX SIGNAL")
        ThemeArtStyle.EMBER -> tr("شفق الأفكار", "EMBER THOUGHTS")
        ThemeArtStyle.LINK -> tr("إشارة الاتصال", "DESKTWIN LINK")
        ThemeArtStyle.ORCHARD -> "ORCHARD"
        ThemeArtStyle.APPLE -> "DESKTWIN / CLARITY"
    }
    val sceneSubtitle = when (preset.artStyle) {
        ThemeArtStyle.ORBIT -> tr("من نقطة ضوء… يتسع المدار", "A point of light becomes a living orbit.")
        ThemeArtStyle.CODE -> tr("الأفكار تتصل، ثم تتحول إلى شيء حقيقي.", "Ideas connect, then become something real.")
        ThemeArtStyle.EMBER -> tr("خذ نفسًا. الفكرة التالية في طريقها.", "Take a breath. The next idea is on its way.")
        ThemeArtStyle.LINK -> tr("إشارة واحدة. جهازان. مساحة واحدة.", "One signal. Two devices. One shared space.")
        ThemeArtStyle.ORCHARD -> tr("أوراق هادئة، ومساحة تتنفس.", "Quiet leaves in a space that can breathe.")
        ThemeArtStyle.APPLE -> ""
    }
    val chips = when (preset.artStyle) {
        ThemeArtStyle.ORBIT -> listOf("STAR MAP", "GLASS / 07", "ORBIT 02")
        ThemeArtStyle.CODE -> listOf("AI_CORE", "01 · 10", "{ link: true }")
        ThemeArtStyle.EMBER -> listOf("hmm…", "idea_04", "···")
        ThemeArtStyle.LINK -> listOf("PHONE", "PAIR OK", "DESKTOP")
        ThemeArtStyle.ORCHARD -> listOf("SAGE / 01", "LEAF · SOFT", "GARDEN")
        ThemeArtStyle.APPLE -> emptyList()
    }
    val accent = if (preset.artStyle == ThemeArtStyle.EMBER) Color(0xFFFFA04A) else preset.accent
    val foreground = Color(0xFFF7F8FC)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val isWide = maxWidth > maxHeight
        Column(Modifier.fillMaxSize().padding(horizontal = if (isWide) 34.dp else 22.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DESKTWIN  /  ${tr("مكتبة المشاهد", "THEME WORLDS")}", color = accent, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("SCENE  ·  ${preset.id.uppercase()}", color = foreground.copy(alpha = 0.56f), style = MaterialTheme.typography.labelSmall)
                }
                TextButton(onClick = onDismiss) {
                    Text(tr("تخطي المشهد", "Skip scene"), color = foreground.copy(alpha = 0.82f))
                }
            }
            Spacer(Modifier.height(if (isWide) 4.dp else 12.dp))
            if (isWide) {
                Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ThemeSceneVisual(preset, progress, ambientPhase, markScale, markRotation, chips, Modifier.weight(1.15f).fillMaxHeight())
                    Column(Modifier.weight(0.85f).padding(start = 20.dp), verticalArrangement = Arrangement.Center) {
                        ThemeSceneCopy(preset, sceneName, sceneSubtitle, copyProgress, font, accent, foreground)
                        Spacer(Modifier.height(25.dp))
                        ThemeSceneProgress(progress, accent, foreground)
                    }
                }
            } else {
                ThemeSceneVisual(preset, progress, ambientPhase, markScale, markRotation, chips, Modifier.weight(1.12f).fillMaxWidth())
                Column(Modifier.weight(0.88f).fillMaxWidth().padding(horizontal = 6.dp), verticalArrangement = Arrangement.Center) {
                    ThemeSceneCopy(preset, sceneName, sceneSubtitle, copyProgress, font, accent, foreground)
                    Spacer(Modifier.height(20.dp))
                    ThemeSceneProgress(progress, accent, foreground)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                tr("عالم صغير من DeskTwin", "A small world inside DeskTwin"),
                modifier = Modifier.align(Alignment.End),
                color = foreground.copy(alpha = 0.40f),
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun ThemeSceneVisual(
    preset: DeskTwinThemePreset,
    progress: Float,
    ambientPhase: State<Float>,
    markScale: Float,
    markRotation: Float,
    chips: List<String>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val markSize = if (maxWidth > maxHeight) 188.dp else 142.dp
        Box(Modifier.size(markSize * 1.54f).clip(CircleShape).background(preset.accent.copy(alpha = 0.13f)))
        if (preset.artStyle == ThemeArtStyle.EMBER) {
            Canvas(Modifier.size(markSize * 1.68f)) {
                val phase = ambientPhase.value
                val center = Offset(size.width / 2f, size.height / 2f)
                val innerRadius = size.minDimension * 0.34f
                repeat(10) { index ->
                    val pulse = (kotlin.math.sin(phase * 2f * PI.toFloat() + index * 0.65f) + 1f) / 2f
                    val rayLength = size.minDimension * (0.075f + 0.055f * pulse)
                    rotate(index * 36f + 18f, center) {
                        drawRoundRect(
                            Color(0xFFFFA04A).copy(alpha = 0.42f + pulse * 0.34f),
                            Offset(center.x - 2.dp.toPx(), center.y - innerRadius - rayLength),
                            androidx.compose.ui.geometry.Size(4.dp.toPx(), rayLength),
                            androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                        )
                    }
                }
            }
        }
        ThemeSignature(
            preset,
            Modifier.size(markSize).graphicsLayer {
                val breathing = kotlin.math.sin(ambientPhase.value * 2f * PI).toFloat()
                val spin = if (preset.artStyle == ThemeArtStyle.CODE) 3f else 1.2f
                rotationZ = markRotation + breathing * spin
                val pulse = if (preset.artStyle == ThemeArtStyle.CODE) 0.025f else 0.012f
                scaleX = markScale + breathing * pulse
                scaleY = markScale + breathing * pulse
            },
            if (preset.artStyle == ThemeArtStyle.EMBER) Color(0xFFFFA04A) else preset.accent
        )
        val placements = listOf(
            Alignment.TopStart to androidx.compose.ui.unit.IntOffset(6, 14),
            Alignment.TopEnd to androidx.compose.ui.unit.IntOffset(-6, 34),
            Alignment.BottomCenter to androidx.compose.ui.unit.IntOffset(0, -14)
        )
        chips.forEachIndexed { index, label ->
            val appear = sceneProgress(progress, 0.20f + index * 0.12f, 0.55f + index * 0.12f)
            val floatY = kotlin.math.sin(progress * PI + index).toFloat() * 8f
            SceneChip(
                label = label,
                preset = preset,
                modifier = Modifier.align(placements[index].first)
                    .offset(x = placements[index].second.x.dp, y = (placements[index].second.y + floatY).dp)
                    .alpha(appear)
            )
        }
        // The continuously moving particles are drawn in Canvas' draw phase, not by recomposing this scene.
        Canvas(Modifier.fillMaxSize()) {
            val phase = ambientPhase.value
            repeat(5) { index ->
                val x = size.width * ((index * 0.217f + phase * 0.13f) % 1f)
                val y = size.height * (0.18f + ((index * 0.173f + phase * 0.19f) % 0.68f))
                drawCircle(preset.accent.copy(alpha = 0.18f), (1.5f + index % 2).dp.toPx(), Offset(x, y))
            }
        }
    }
}

@Composable
private fun SceneChip(label: String, preset: DeskTwinThemePreset, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = preset.darkSurface.copy(alpha = 0.88f),
        border = BorderStroke(1.dp, preset.accent.copy(alpha = 0.30f))
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
            color = Color(0xFFF4F6FA),
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = themeFontFamily(preset, LocalDeskTwinLanguage.current)),
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ThemeSceneCopy(
    preset: DeskTwinThemePreset,
    sceneName: String,
    subtitle: String,
    progress: Float,
    font: androidx.compose.ui.text.font.FontFamily,
    accent: Color,
    foreground: Color
) {
    Text(sceneName, color = accent, style = MaterialTheme.typography.labelLarge.copy(fontFamily = font), fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    Text(
        tr(preset.nameAr, preset.nameEn),
        modifier = Modifier.alpha(progress),
        color = foreground,
        style = MaterialTheme.typography.headlineSmall.copy(fontFamily = font),
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    Text(
        subtitle,
        modifier = Modifier.alpha(progress),
        color = foreground.copy(alpha = 0.72f),
        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = font)
    )
    Spacer(Modifier.height(14.dp))
    Text(
        tr(preset.surpriseAr, preset.surpriseEn),
        modifier = Modifier.alpha(sceneProgress(progress, 0.55f, 0.90f)),
        color = foreground.copy(alpha = 0.52f),
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = font)
    )
}

@Composable
private fun ThemeSceneProgress(progress: Float, accent: Color, foreground: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val active = sceneProgress(progress, 0.18f + index * 0.18f, 0.46f + index * 0.18f)
            Box(Modifier.size(if (active > 0.7f) 20.dp else 6.dp, 6.dp).clip(RoundedCornerShape(50)).background(
                if (active > 0.7f) accent else foreground.copy(alpha = 0.26f)
            ))
        }
    }
}

private fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

@Composable
private fun ImmersiveSceneWindow() {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(dialogWindow) {
        val controller = dialogWindow?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val barsWereVisible = dialogWindow?.decorView?.rootWindowInsets?.let {
            WindowInsetsCompat.toWindowInsetsCompat(it).isVisible(WindowInsetsCompat.Type.systemBars())
        } ?: true
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (barsWereVisible) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun ThemeEasterScene(
    preset: DeskTwinThemePreset,
    progress: Float,
    ambientPhase: androidx.compose.runtime.State<Float>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier) {
        val ambient = ambientPhase.value
        val points = listOf(
            0.12f to 0.15f, 0.28f to 0.31f, 0.76f to 0.18f, 0.88f to 0.44f,
            0.18f to 0.68f, 0.72f to 0.74f, 0.43f to 0.88f, 0.91f to 0.82f
        )
        when (preset.artStyle) {
            ThemeArtStyle.ORBIT -> {
                val center = Offset(size.width * 0.5f, size.height * 0.5f)
                rotate(ambient * 360f, center) {
                    drawOval(
                        Color.White.copy(alpha = 0.16f),
                        Offset(size.width * 0.17f, size.height * 0.30f),
                        androidx.compose.ui.geometry.Size(size.width * 0.66f, size.height * 0.40f),
                        style = Stroke(1.dp.toPx())
                    )
                }
                rotate(-ambient * 220f, center) {
                    drawOval(
                        preset.secondary.copy(alpha = 0.20f),
                        Offset(size.width * 0.25f, size.height * 0.39f),
                        androidx.compose.ui.geometry.Size(size.width * 0.50f, size.height * 0.22f),
                        style = Stroke(1.dp.toPx())
                    )
                }
                points.forEachIndexed { index, (x, y) ->
                    val rise = sceneProgress(progress, 0.03f + index * 0.025f, 0.42f + index * 0.025f)
                    val twinkle = 0.28f + 0.5f * kotlin.math.abs(kotlin.math.sin(progress * 8f + index))
                    val driftX = (ambient * 2f - 1f) * (6f + index % 3 * 3f).dp.toPx()
                    val driftY = kotlin.math.sin(ambient * 2f * PI + index).toFloat() * 9.dp.toPx()
                    drawCircle(Color.White.copy(alpha = rise * twinkle), (1.3f + index % 3 * 0.45f).dp.toPx(), Offset(size.width * x + driftX, size.height * y + driftY))
                }
            }
            ThemeArtStyle.CODE -> {
                val drawIn = sceneProgress(progress, 0.04f, 0.50f)
                val grid = preset.accent.copy(alpha = 0.10f * drawIn)
                var x = 0f
                while (x <= size.width) {
                    drawLine(grid, Offset(x, 0f), Offset(x, size.height * drawIn), 0.8.dp.toPx())
                    x += 24.dp.toPx()
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(grid, Offset(0f, y), Offset(size.width * drawIn, y), 0.8.dp.toPx())
                    y += 24.dp.toPx()
                }
                val nodes = points.mapIndexed { index, (x, y) ->
                    val drift = kotlin.math.sin(ambient * 2f * PI + index).toFloat() * 8.dp.toPx()
                    Offset(size.width * x, size.height * y + drift)
                }
                val links = listOf(0 to 1, 0 to 3, 1 to 2, 1 to 4, 2 to 3, 2 to 5, 3 to 7, 4 to 5, 4 to 6, 5 to 7, 6 to 7)
                links.forEachIndexed { index, (from, to) ->
                    val reveal = sceneProgress(progress, 0.12f + index * 0.035f, 0.42f + index * 0.035f)
                    val pulseWave = kotlin.math.sin(ambient * 2f * PI + index * 0.7f).toFloat()
                    val pulse = 0.58f + 0.42f * ((pulseWave + 1f) / 2f)
                    val start = nodes[from]
                    val end = nodes[to]
                    drawLine(
                        preset.accent.copy(alpha = reveal * pulse * 0.34f),
                        start,
                        end,
                        strokeWidth = 1.dp.toPx()
                    )
                    val packet = (ambient * 1.25f + index * 0.29f) % 1f
                    val packetPosition = Offset(
                        start.x + (end.x - start.x) * packet,
                        start.y + (end.y - start.y) * packet
                    )
                    drawCircle(Color(0xFFFFC247).copy(alpha = reveal * 0.75f), 1.6.dp.toPx(), packetPosition)
                }
                points.forEachIndexed { index, _ ->
                    val reveal = sceneProgress(progress, 0.24f + index * 0.025f, 0.54f + index * 0.025f)
                    val point = nodes[index]
                    drawCircle(preset.accent.copy(alpha = reveal * 0.16f), 5.dp.toPx(), point)
                    drawCircle(Color(0xFFFFC247).copy(alpha = reveal * 0.72f), 1.8.dp.toPx(), point)
                }
            }
            ThemeArtStyle.EMBER -> points.forEachIndexed { index, (x, y) ->
                val reveal = sceneProgress(progress, 0.12f + index * 0.02f, 0.63f + index * 0.02f)
                val drift = kotlin.math.sin(ambient * 2f * PI + index * 0.9f).toFloat() * 13.dp.toPx()
                drawCircle(preset.accent.copy(alpha = reveal * 0.20f), (2f + index % 2).dp.toPx(), Offset(size.width * x, size.height * y + drift))
                if (index % 2 == 0) {
                    drawRoundRect(Color(0xFFFFB15C).copy(alpha = reveal * 0.16f), Offset(size.width * x + 6.dp.toPx(), size.height * y - 5.dp.toPx() + drift), androidx.compose.ui.geometry.Size(4.dp.toPx(), 4.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(1.dp.toPx()))
                }
            }
            ThemeArtStyle.ORCHARD -> points.take(6).forEachIndexed { index, (x, y) ->
                val reveal = sceneProgress(progress, 0.36f + index * 0.035f, 0.72f + index * 0.03f)
                val drift = kotlin.math.sin(ambient * 2f * PI + index).toFloat() * 6.dp.toPx()
                val center = Offset(size.width * x + drift, size.height * y - drift)
                rotate(kotlin.math.sin(ambient * 2f * PI + index * 0.7f).toFloat() * 24f, center) {
                    val leafColor = preset.accent.copy(alpha = reveal * 0.22f)
                    drawOval(leafColor, Offset(center.x - 5.dp.toPx(), center.y - 2.5.dp.toPx()), androidx.compose.ui.geometry.Size(10.dp.toPx(), 5.dp.toPx()))
                    drawLine(leafColor.copy(alpha = leafColor.alpha * 1.4f), Offset(center.x - 3.dp.toPx(), center.y), Offset(center.x + 3.dp.toPx(), center.y), 0.7.dp.toPx())
                }
            }
            ThemeArtStyle.APPLE -> points.take(6).forEachIndexed { index, (x, y) ->
                val reveal = sceneProgress(progress, 0.36f + index * 0.035f, 0.72f + index * 0.03f)
                val drift = kotlin.math.sin(ambient * 2f * PI + index).toFloat() * 6.dp.toPx()
                drawCircle(Color(0xFF1D1D1F).copy(alpha = reveal * 0.09f), (1.6f + index % 2).dp.toPx(), Offset(size.width * x, size.height * y + drift))
            }
            ThemeArtStyle.LINK -> {
                val connect = sceneProgress(progress, 0.18f, 0.68f)
                val left = Offset(size.width * 0.24f, size.height * 0.50f)
                val right = Offset(size.width * 0.76f, size.height * 0.50f)
                drawCircle(preset.accent.copy(alpha = 0.10f + connect * 0.06f), size.minDimension * (0.18f + connect * 0.06f), left, style = Stroke(1.dp.toPx()))
                drawCircle(preset.secondary.copy(alpha = 0.10f + connect * 0.06f), size.minDimension * (0.18f + connect * 0.06f), right, style = Stroke(1.dp.toPx()))
                drawLine(preset.secondary.copy(alpha = connect * (0.20f + ambient * 0.22f)), left, Offset(left.x + (right.x - left.x) * connect, left.y), 1.5.dp.toPx())
                val packet = (ambient * 1.7f) % 1f
                drawCircle(preset.accent.copy(alpha = connect), 4.dp.toPx(), Offset(left.x + (right.x - left.x) * packet, left.y))
            }
        }
    }
}

@Composable
private fun OrchardAppleMark(modifier: Modifier = Modifier, cutoutColor: Color = Color.White) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val body = Path().apply {
            moveTo(cx, size.height * 0.34f)
            cubicTo(size.width * 0.26f, size.height * 0.17f, size.width * 0.08f, size.height * 0.35f, size.width * 0.15f, size.height * 0.61f)
            cubicTo(size.width * 0.22f, size.height * 0.88f, size.width * 0.39f, size.height * 0.93f, cx, size.height * 0.82f)
            cubicTo(size.width * 0.62f, size.height * 0.94f, size.width * 0.79f, size.height * 0.87f, size.width * 0.86f, size.height * 0.61f)
            cubicTo(size.width * 0.94f, size.height * 0.34f, size.width * 0.73f, size.height * 0.17f, cx, size.height * 0.34f)
            close()
        }
        val leaf = Path().apply {
            moveTo(cx, size.height * 0.28f)
            cubicTo(cx, size.height * 0.11f, size.width * 0.69f, size.height * 0.04f, size.width * 0.72f, size.height * 0.18f)
            cubicTo(size.width * 0.68f, size.height * 0.28f, size.width * 0.56f, size.height * 0.31f, cx, size.height * 0.28f)
            close()
        }
        drawPath(body, Color(0xFF171717))
        drawPath(leaf, Color(0xFF171717))
        drawLine(Color(0xFF171717), Offset(cx, size.height * 0.34f), Offset(cx, size.height * 0.19f), strokeWidth = size.width * 0.045f)
        // ثلاث انحناءات سالبة ترسم عضة أصلية على الشمال، وليست نسخة من شعار جاهز.
        drawCircle(cutoutColor, size.width * 0.088f, Offset(size.width * 0.105f, size.height * 0.435f))
        drawCircle(cutoutColor, size.width * 0.074f, Offset(size.width * 0.158f, size.height * 0.535f))
        drawCircle(cutoutColor, size.width * 0.055f, Offset(size.width * 0.235f, size.height * 0.602f))
    }
}

@Composable
private fun fontLabel(id: String, isArabic: Boolean): String = when (id) {
    "cosmic" -> if (isArabic) "كُوفي · Space Grotesk" else "Space Grotesk · Kufi"
    "code-studio" -> if (isArabic) "JetBrains Mono · Noto Sans" else "JetBrains Mono · Noto Sans Arabic"
    "ember" -> if (isArabic) "لورا · نسخ" else "Lora · Naskh"
    "apple-monochrome" -> if (isArabic) "خط النظام · دعم العربية" else "Android system sans"
    "orchard" -> if (isArabic) "مانروب · نسخ عربي" else "Manrope · Noto Sans Arabic"
    else -> if (isArabic) "مانروب · نسخ عربي" else "Manrope · Noto Sans Arabic"
}

@Composable
private fun motionLabel(id: String): String = when (id) {
    "cosmic" -> tr("مدارية", "Orbital")
    "code-studio" -> tr("سريعة", "Snappy")
    "ember" -> tr("ناعمة", "Soft")
    else -> tr("هادئة", "Calm")
}
