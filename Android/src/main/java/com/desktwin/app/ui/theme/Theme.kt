package com.desktwin.app.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.desktwin.app.R
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class ThemeArtStyle { ORBIT, CODE, EMBER, ORCHARD, LINK, APPLE }
enum class ThemeModePolicy { FOLLOW_APP, LIGHT, DARK }

data class DeskTwinThemePreset(
    val id: String,
    val nameAr: String,
    val nameEn: String,
    val descriptionAr: String,
    val descriptionEn: String,
    val accent: Color,
    val accentDark: Color,
    val secondary: Color,
    val lightBackground: Color,
    val darkBackground: Color,
    val lightSurface: Color,
    val darkSurface: Color,
    val lightVariant: Color,
    val darkVariant: Color,
    val lightText: Color,
    val darkText: Color,
    val mutedLight: Color,
    val mutedDark: Color,
    val latinFontFamily: FontFamily,
    val arabicFontFamily: FontFamily,
    val backdropRes: Int,
    val artStyle: ThemeArtStyle,
    val modePolicy: ThemeModePolicy,
    val motionScale: Float,
    val mark: String,
    val surpriseAr: String,
    val surpriseEn: String
)

val DeskTwinThemePresets = listOf(
    DeskTwinThemePreset(
        id = "desktwin",
        nameAr = "DeskTwin الأزرق",
        nameEn = "DeskTwin Blue",
        descriptionAr = "أزرق هادئ، مساحات واضحة، وربط بصري بين الجهازين.",
        descriptionEn = "Calm blue, clear surfaces, and a visual link between devices.",
        accent = Color(0xFF2563EB), accentDark = Color(0xFF1D4ED8), secondary = Color(0xFF0E7490),
        lightBackground = Color(0xFFF2F6FC), darkBackground = Color(0xFF0D1524),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF141F31),
        lightVariant = Color(0xFFEAF0F8), darkVariant = Color(0xFF1D2B40),
        lightText = Color(0xFF172033), darkText = Color(0xFFF2F6FC),
        mutedLight = Color(0xFF667085), mutedDark = Color(0xFFB0BDCF),
        latinFontFamily = FontFamily(Font(R.font.desktwin_manrope)), arabicFontFamily = FontFamily(Font(R.font.arabic_noto_sans)),
        backdropRes = R.drawable.theme_desktwin, artStyle = ThemeArtStyle.LINK, modePolicy = ThemeModePolicy.FOLLOW_APP, motionScale = 1f,
        mark = "↗", surpriseAr = "كل اتصال حلو يبدأ بخطوة صغيرة.", surpriseEn = "Every good connection starts with a small step."
    ),
    DeskTwinThemePreset(
        id = "cosmic",
        nameAr = "مدار زجاجي",
        nameEn = "Cosmic Glass",
        descriptionAr = "وهج سماوي ونجوم هادئة فوق أسطح زجاجية خفيفة.",
        descriptionEn = "Soft starlight and orbital glow over translucent surfaces.",
        accent = Color(0xFF6D5CE8), accentDark = Color(0xFF5142C8), secondary = Color(0xFF0E9AA7),
        lightBackground = Color(0xFFF7F6FF), darkBackground = Color(0xFF101321),
        lightSurface = Color(0xF7FFFFFF), darkSurface = Color(0xE91B2033),
        lightVariant = Color(0xFFECEBFA), darkVariant = Color(0xFF252A40),
        lightText = Color(0xFF1C2033), darkText = Color(0xFFF5F4FF),
        mutedLight = Color(0xFF676A7C), mutedDark = Color(0xFFB7B8CC),
        latinFontFamily = FontFamily(Font(R.font.cosmic_space_grotesk)), arabicFontFamily = FontFamily(Font(R.font.arabic_noto_kufi)),
        backdropRes = R.drawable.theme_cosmic, artStyle = ThemeArtStyle.ORBIT, modePolicy = ThemeModePolicy.DARK, motionScale = 1.08f,
        mark = "✦", surpriseAr = "وجدتَ مدارًا مخفيًا. الكون أوسع من نافذتك.", surpriseEn = "Hidden orbit found. The universe is wider than this window."
    ),
    DeskTwinThemePreset(
        id = "code-studio",
        nameAr = "Codex · ChatGPT",
        nameEn = "Codex / ChatGPT",
        descriptionAr = "حبر أخضر وشبكة دقيقة وعقدة ضوئية متشابكة مستوحاة من الذكاء الاصطناعي.",
        descriptionEn = "Ink green, a quiet grid, and an original interwoven AI-inspired light mark.",
        accent = Color(0xFF087F5B), accentDark = Color(0xFF10B981), secondary = Color(0xFF167D9A),
        lightBackground = Color(0xFFF2F8F5), darkBackground = Color(0xFF101916),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF19231F),
        lightVariant = Color(0xFFE2EEE8), darkVariant = Color(0xFF24342D),
        lightText = Color(0xFF17241E), darkText = Color(0xFFE8F5ED),
        mutedLight = Color(0xFF5C7065), mutedDark = Color(0xFFABC0B2),
        latinFontFamily = FontFamily(Font(R.font.codex_jetbrains_mono)), arabicFontFamily = FontFamily(Font(R.font.arabic_noto_sans)),
        backdropRes = R.drawable.theme_codex, artStyle = ThemeArtStyle.CODE, modePolicy = ThemeModePolicy.DARK, motionScale = 0.84f,
        mark = "{ }", surpriseAr = "اكتشفتَ تعليقًا مخفيًا: // أنت من يكتب القصة.", surpriseEn = "Hidden comment found: // You write the story."
    ),
    DeskTwinThemePreset(
        id = "ember",
        nameAr = "Claude · شفق دافئ",
        nameEn = "Claude Ember",
        descriptionAr = "دفء برتقالي كريمي مستوحى من واجهات الكتابة الهادئة، بخط تحريري ناعم.",
        descriptionEn = "Calm writing-space inspiration in cream and ember, with a softer editorial type style.",
        accent = Color(0xFFB45309), accentDark = Color(0xFF9A3412), secondary = Color(0xFFB54762),
        lightBackground = Color(0xFFFFF8F0), darkBackground = Color(0xFF201713),
        lightSurface = Color(0xFFFFFEFC), darkSurface = Color(0xFF2A201A),
        lightVariant = Color(0xFFF5E8DA), darkVariant = Color(0xFF3A2B23),
        lightText = Color(0xFF2E211A), darkText = Color(0xFFFFF3E8),
        mutedLight = Color(0xFF79675A), mutedDark = Color(0xFFCAB4A1),
        latinFontFamily = FontFamily(Font(R.font.ember_lora)), arabicFontFamily = FontFamily(Font(R.font.arabic_noto_naskh)),
        backdropRes = R.drawable.theme_ember, artStyle = ThemeArtStyle.EMBER, modePolicy = ThemeModePolicy.LIGHT, motionScale = 1.04f,
        mark = "☼", surpriseAr = "الشفق يترك لك رسالة: خذ وقتك، ملفاتك في أمان.", surpriseEn = "A note from the dusk: take your time; your files are safe."
    ),
    DeskTwinThemePreset(
        id = "orchard",
        nameAr = "بستان هادئ",
        nameEn = "Orchard Minimal",
        descriptionAr = "أخضر المريمية، ضوء طبيعي، وأوراق تتحرك بهدوء.",
        descriptionEn = "Sage greens, natural light, and gently drifting leaves.",
        accent = Color(0xFF66856B), accentDark = Color(0xFFA4C5A7), secondary = Color(0xFF9A8062),
        lightBackground = Color(0xFFF2F6F0), darkBackground = Color(0xFF111A14),
        lightSurface = Color(0xFFFCFDFB), darkSurface = Color(0xFF1B281E),
        lightVariant = Color(0xFFE5EDE3), darkVariant = Color(0xFF26372A),
        lightText = Color(0xFF202A21), darkText = Color(0xFFEAF2E9),
        mutedLight = Color(0xFF68766A), mutedDark = Color(0xFFB4C4B5),
        latinFontFamily = FontFamily(Font(R.font.desktwin_manrope)), arabicFontFamily = FontFamily(Font(R.font.arabic_noto_sans)),
        backdropRes = R.drawable.theme_orchard, artStyle = ThemeArtStyle.ORCHARD, modePolicy = ThemeModePolicy.FOLLOW_APP, motionScale = 0.9f,
        mark = "❧", surpriseAr = "بين الأوراق الهادئة، وجدتَ لحظتك الخاصة.", surpriseEn = "Between quiet leaves, you found a moment of your own."
    ),
    DeskTwinThemePreset(
        id = "apple-monochrome",
        nameAr = "DeskTwin · وضوح",
        nameEn = "DeskTwin · Clarity",
        descriptionAr = "أزرق واضح، أسطح هادئة، ومساحة أكبر لملفاتك واتصال الكمبيوتر.",
        descriptionEn = "Clear blue, quiet surfaces, and more room for files and your computer connection.",
        accent = Color(0xFF0066CC), accentDark = Color(0xFF0A84FF), secondary = Color(0xFF6E6E73),
        lightBackground = Color(0xFFF2F2F7), darkBackground = Color(0xFF000000),
        lightSurface = Color(0xFFFFFFFF), darkSurface = Color(0xFF1C1C1E),
        lightVariant = Color(0xFFE5E5EA), darkVariant = Color(0xFF2C2C2E),
        lightText = Color(0xFF1C1C1E), darkText = Color(0xFFF5F5F7),
        mutedLight = Color(0xFF5E5E63), mutedDark = Color(0xFFAEAEB2),
        latinFontFamily = FontFamily.Default, arabicFontFamily = FontFamily.Default,
        backdropRes = R.drawable.theme_orchard, artStyle = ThemeArtStyle.APPLE, modePolicy = ThemeModePolicy.FOLLOW_APP, motionScale = 0.9f,
        mark = "↗", surpriseAr = "اتصال واحد، ومكتبك كله أقرب.", surpriseEn = "One connection. Your whole desk, closer."
    )
)

fun deskTwinThemePreset(id: String): DeskTwinThemePreset =
    DeskTwinThemePresets.firstOrNull { it.id == id } ?: DeskTwinThemePresets.first()

fun themeFontFamily(preset: DeskTwinThemePreset, language: String): FontFamily =
    if (language == "en") preset.latinFontFamily else preset.arabicFontFamily

fun deskTwinThemeIsDark(preset: DeskTwinThemePreset, selectedMode: String, systemIsDark: Boolean): Boolean =
    when (preset.modePolicy) {
        ThemeModePolicy.FOLLOW_APP -> selectedMode == "dark" || (selectedMode == "system" && systemIsDark)
        ThemeModePolicy.DARK -> true
        ThemeModePolicy.LIGHT -> false
    }

val LocalDeskTwinThemePreset = staticCompositionLocalOf { DeskTwinThemePresets.first() }
val LocalDeskTwinReduceMotion = staticCompositionLocalOf { false }
val LocalThemeMotionPhase = staticCompositionLocalOf<State<Float>?> { null }

private val DeskTwinTypography = Typography()

private val ClarityTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 30.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
)

private fun typographyFor(family: FontFamily, appleStyle: Boolean): Typography {
    val typography = if (appleStyle) ClarityTypography else DeskTwinTypography
    return typography.copy(
        displayLarge = typography.displayLarge.copy(fontFamily = family),
        displayMedium = typography.displayMedium.copy(fontFamily = family),
        displaySmall = typography.displaySmall.copy(fontFamily = family),
        headlineLarge = typography.headlineLarge.copy(fontFamily = family),
        headlineMedium = typography.headlineMedium.copy(fontFamily = family),
        headlineSmall = typography.headlineSmall.copy(fontFamily = family),
        titleLarge = typography.titleLarge.copy(fontFamily = family),
        titleMedium = typography.titleMedium.copy(fontFamily = family),
        titleSmall = typography.titleSmall.copy(fontFamily = family),
        bodyLarge = typography.bodyLarge.copy(fontFamily = family),
        bodyMedium = typography.bodyMedium.copy(fontFamily = family),
        bodySmall = typography.bodySmall.copy(fontFamily = family),
        labelLarge = typography.labelLarge.copy(fontFamily = family),
        labelMedium = typography.labelMedium.copy(fontFamily = family),
        labelSmall = typography.labelSmall.copy(fontFamily = family)
    )
}

private fun shapesFor(preset: DeskTwinThemePreset): Shapes {
    val radius = when (preset.id) {
        "code-studio" -> 12.dp
        "ember" -> 24.dp
        "orchard" -> 17.dp
        "apple-monochrome" -> 16.dp
        else -> 20.dp
    }
    return Shapes(
        small = RoundedCornerShape(radius * 0.65f),
        medium = RoundedCornerShape(radius),
        large = RoundedCornerShape(radius * 1.25f)
    )
}

@Composable
fun DeskTwinTheme(
    darkTheme: Boolean = false,
    presetId: String = "desktwin",
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit
) {
    val preset = deskTwinThemePreset(presetId)
    val appleStyle = preset.artStyle == ThemeArtStyle.APPLE
    val language = LocalDeskTwinLanguage.current
    val background = if (darkTheme) preset.darkBackground else preset.lightBackground
    val surface = if (darkTheme) preset.darkSurface else preset.lightSurface
    val surfaceVariant = if (darkTheme) preset.darkVariant else preset.lightVariant
    val onSurface = if (darkTheme) preset.darkText else preset.lightText
    val muted = if (darkTheme) preset.mutedDark else preset.mutedLight
    val themeMotionPhase = if (reduceMotion || appleStyle) null else {
        rememberInfiniteTransition(label = "${preset.id} shared motion").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(
                    durationMillis = when (preset.artStyle) {
                        ThemeArtStyle.ORBIT -> 22_000
                        ThemeArtStyle.CODE -> 12_000
                        ThemeArtStyle.EMBER -> 9_000
                        ThemeArtStyle.ORCHARD -> 16_000
                        ThemeArtStyle.APPLE -> 18_000
                        ThemeArtStyle.LINK -> 7_000
                    },
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "${preset.id} shared phase"
        )
    }
    val colorScheme = if (darkTheme) {
        darkColorScheme(
            primary = preset.accentDark,
            onPrimary = if (appleStyle) Color.Black else Color(0xFF101318),
            primaryContainer = preset.accentDark.copy(alpha = 0.22f),
            onPrimaryContainer = preset.darkText,
            secondary = preset.secondary,
            onSecondary = if (appleStyle) Color.White else Color(0xFF071B1D),
            background = if (appleStyle) background else Color.Transparent,
            surface = surface,
            surfaceVariant = surfaceVariant,
            secondaryContainer = preset.accentDark.copy(alpha = 0.22f),
            onSecondaryContainer = preset.darkText,
            onBackground = onSurface,
            onSurface = onSurface,
            onSurfaceVariant = muted,
            error = Color(0xFFFFB4AB),
            outline = if (appleStyle) Color(0xFF38383A) else preset.accentDark.copy(alpha = 0.36f)
        )
    } else {
        lightColorScheme(
            primary = preset.accent,
            onPrimary = Color.White,
            primaryContainer = preset.accent.copy(alpha = 0.12f),
            onPrimaryContainer = preset.lightText,
            secondary = preset.secondary,
            onSecondary = Color.White,
            background = if (appleStyle) background else Color.Transparent,
            surface = surface,
            surfaceVariant = surfaceVariant,
            secondaryContainer = preset.accent.copy(alpha = 0.12f),
            onSecondaryContainer = preset.lightText,
            onBackground = onSurface,
            onSurface = onSurface,
            onSurfaceVariant = muted,
            error = ErrorRed,
            outline = if (appleStyle) Color(0xFFD1D1D6) else preset.accent.copy(alpha = 0.25f)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typographyFor(themeFontFamily(preset, language), appleStyle),
        shapes = shapesFor(preset)
    ) {
        CompositionLocalProvider(
            LocalContentColor provides colorScheme.onBackground,
            LocalDeskTwinReduceMotion provides reduceMotion,
            LocalThemeMotionPhase provides themeMotionPhase
        ) {
            Box(Modifier.fillMaxSize().background(background)) {
                if (preset.artStyle != ThemeArtStyle.APPLE) {
                    Image(
                        painter = painterResource(preset.backdropRes),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(0.94f)
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        background.copy(alpha = if (darkTheme) 0.48f else 0.58f)
                    )
                )
                if (!appleStyle) {
                    ThemeAmbientArtwork(preset, darkTheme, reduceMotion, Modifier.fillMaxSize(), themeMotionPhase)
                }
                CompositionLocalProvider(LocalDeskTwinThemePreset provides preset) {
                    content()
                }
            }
        }
    }
}

@Composable
fun ThemeAmbientArtwork(
    preset: DeskTwinThemePreset,
    darkTheme: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    sharedPhase: State<Float>? = null
) {
    val phaseAnimation = if (reduceMotion) null else sharedPhase ?: run {
        rememberInfiniteTransition(label = "theme ambient motion").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(
                    durationMillis = when (preset.artStyle) {
                        ThemeArtStyle.ORBIT -> 22_000
                        ThemeArtStyle.CODE -> 12_000
                        ThemeArtStyle.EMBER -> 9_000
                        ThemeArtStyle.ORCHARD -> 16_000
                        ThemeArtStyle.APPLE -> 18_000
                        ThemeArtStyle.LINK -> 7_000
                    },
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "theme ambient phase"
        )
    }

    Canvas(modifier) {
        // Read animated state during drawing so the whole screen is not recomposed every frame.
        val phase = phaseAnimation?.value ?: 0f
        val accent = preset.accent.copy(alpha = if (darkTheme) 0.12f else 0.06f)
        val secondary = preset.secondary.copy(alpha = if (darkTheme) 0.09f else 0.045f)
        val center = Offset(size.width * (0.82f - phase * 0.035f), size.height * 0.10f)
        drawCircle(
            brush = Brush.radialGradient(listOf(accent, accent.copy(alpha = 0f)), center, size.minDimension * 0.58f),
            radius = size.minDimension * 0.58f,
            center = center
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(secondary, secondary.copy(alpha = 0f)), Offset(size.width * 0.08f, size.height * 0.88f), size.minDimension * 0.52f),
            radius = size.minDimension * 0.52f,
            center = Offset(size.width * 0.08f, size.height * 0.88f)
        )

        when (preset.artStyle) {
            ThemeArtStyle.ORBIT -> {
                val starColor = if (darkTheme) Color.White else preset.accent
                val stars = listOf(0.12f to 0.12f, 0.28f to 0.27f, 0.79f to 0.33f, 0.91f to 0.52f, 0.18f to 0.72f, 0.69f to 0.83f, 0.42f to 0.91f, 0.88f to 0.13f, 0.52f to 0.44f, 0.34f to 0.58f)
                stars.forEachIndexed { index, (x, y) ->
                    val driftY = (y + phase * (0.10f + (index % 3) * 0.035f)) % 1f
                    val pulse = if (reduceMotion) 0.6f else (0.42f + 0.28f * sin((phase * 2f * PI + index).toFloat()).toFloat())
                    val radius = if (index % 3 == 0) 1.8.dp.toPx() else 1.15.dp.toPx()
                    drawCircle(starColor.copy(alpha = (if (darkTheme) 0.42f else 0.20f) * pulse), radius, Offset(size.width * x, size.height * driftY))
                }
                drawCircle(preset.accent.copy(alpha = 0.055f), size.minDimension * 0.42f, center, style = Stroke(1.dp.toPx()))
                drawCircle(preset.secondary.copy(alpha = 0.045f), size.minDimension * 0.56f, center, style = Stroke(1.dp.toPx()))
            }
            ThemeArtStyle.CODE -> {
                val gridColor = preset.accent.copy(alpha = if (darkTheme) 0.065f else 0.04f)
                val spacing = 28.dp.toPx()
                var x = 0f
                while (x <= size.width) {
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), 0.7.dp.toPx())
                    x += spacing
                }
                var y = 0f
                while (y <= size.height) {
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.7.dp.toPx())
                    y += spacing
                }
                // A quiet, original interwoven rosette: slow rotation and a gentle breathing glow.
                val core = Offset(size.width * 0.82f, size.height * 0.22f)
                val radius = size.minDimension * 0.105f
                val breath = 0.5f + 0.5f * sin((phase * 2f * PI).toFloat())
                val knotColor = (if (darkTheme) preset.accentDark else preset.accent)
                    .copy(alpha = (if (darkTheme) 0.18f else 0.095f) + breath * 0.035f)
                rotate(degrees = sin((phase * 2f * PI).toFloat()) * 4f, pivot = core) {
                    repeat(6) { index ->
                        val angle = (index * 60f - 90f) * (PI / 180.0)
                        val orbitX = cos(angle).toFloat() * radius * 0.30f
                        val orbitY = sin(angle).toFloat() * radius * 0.30f
                        val petalWidth = radius * 0.92f
                        val petalHeight = radius * 0.58f
                        rotate(degrees = index * 60f, pivot = Offset(core.x + orbitX, core.y + orbitY)) {
                            drawOval(
                                color = knotColor,
                                topLeft = Offset(core.x + orbitX - petalWidth / 2f, core.y + orbitY - petalHeight / 2f),
                                size = androidx.compose.ui.geometry.Size(petalWidth, petalHeight),
                                style = Stroke(width = 1.35.dp.toPx())
                            )
                        }
                    }
                }
                drawCircle(knotColor.copy(alpha = knotColor.alpha * 0.72f), radius * 0.12f, core)
            }
            ThemeArtStyle.EMBER -> {
                val sun = Offset(size.width * 0.88f, size.height * 0.18f)
                val breath = 0.5f + 0.5f * sin((phase * 2f * PI).toFloat())
                val warmColor = Color(0xFFF28B32)
                val haloRadius = size.minDimension * (0.105f + breath * 0.012f)
                drawCircle(warmColor.copy(alpha = 0.035f + breath * 0.018f), haloRadius * 1.65f, sun)
                drawCircle(warmColor.copy(alpha = 0.055f + breath * 0.025f), haloRadius, sun, style = Stroke(1.dp.toPx()))
                // Claude-inspired sunburst: fine orange strokes gently extend and retract in sequence.
                repeat(24) { index ->
                    val angle = (index * 15f - 90f) * (PI / 180.0)
                    val wave = 0.5f + 0.5f * sin((phase * 2f * PI + index * 0.42f).toFloat())
                    val inner = haloRadius * (1.18f + wave * 0.08f)
                    val outer = haloRadius * (1.38f + wave * (0.28f + (index % 3) * 0.035f))
                    val start = Offset(sun.x + cos(angle).toFloat() * inner, sun.y + sin(angle).toFloat() * inner)
                    val end = Offset(sun.x + cos(angle).toFloat() * outer, sun.y + sin(angle).toFloat() * outer)
                    drawLine(
                        color = warmColor.copy(alpha = 0.12f + wave * 0.11f),
                        start = start,
                        end = end,
                        strokeWidth = (0.85f + (index % 3) * 0.18f).dp.toPx(),
                        cap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
                drawLine(preset.accent.copy(alpha = 0.06f), Offset(0f, size.height * 0.72f), Offset(size.width, size.height * 0.64f), 1.dp.toPx())
                val embers = listOf(0.16f to 0.78f, 0.37f to 0.91f, 0.58f to 0.72f, 0.74f to 0.86f, 0.91f to 0.68f, 0.27f to 0.58f)
                embers.forEachIndexed { index, (x, y) ->
                    val wave = sin((phase * 2f * PI + index).toFloat())
                    val emberY = y + wave * 0.018f
                    val emberX = x + cos((phase * 2f * PI + index).toFloat()) * 0.009f
                    val visibility = 0.08f + 0.12f * kotlin.math.abs(wave)
                    drawCircle(preset.accent.copy(alpha = visibility), (1.4f + (index % 2) * 0.5f).dp.toPx(), Offset(size.width * emberX, size.height * emberY))
                }
            }
            ThemeArtStyle.ORCHARD -> {
                val leafColor = (if (darkTheme) Color(0xFFA4C5A7) else Color(0xFF66856B)).copy(alpha = 0.12f)
                listOf(0.16f to 0.26f, 0.82f to 0.34f, 0.27f to 0.72f, 0.76f to 0.83f, 0.53f to 0.14f).forEachIndexed { index, (x, y) ->
                    val wave = sin((phase * 2f * PI + index * 1.3f).toFloat())
                    val center = Offset(size.width * x + wave * 10.dp.toPx(), size.height * y + cos((phase * 2f * PI + index).toFloat()) * 8.dp.toPx())
                    rotate(wave * 18f, center) {
                        drawOval(leafColor, Offset(center.x - 5.dp.toPx(), center.y - 2.5.dp.toPx()), androidx.compose.ui.geometry.Size(10.dp.toPx(), 5.dp.toPx()))
                        drawLine(leafColor.copy(alpha = leafColor.alpha * 1.5f), Offset(center.x - 3.dp.toPx(), center.y), Offset(center.x + 3.dp.toPx(), center.y), 0.7.dp.toPx())
                    }
                }
            }
            ThemeArtStyle.APPLE -> {
                val ink = (if (darkTheme) Color.White else Color(0xFF1D1D1F)).copy(alpha = 0.035f)
                val highlight = (if (darkTheme) Color.White else Color(0xFF6E6E73)).copy(alpha = 0.06f)
                val appleCenter = Offset(size.width * 0.86f, size.height * 0.17f)
                drawCircle(ink, size.minDimension * 0.115f, appleCenter, style = Stroke(1.dp.toPx()))
                drawCircle(highlight, size.minDimension * 0.075f, appleCenter, style = Stroke(1.dp.toPx()))
                drawLine(ink, Offset(size.width * 0.08f, size.height * 0.82f), Offset(size.width * 0.92f, size.height * 0.82f), 1.dp.toPx())
            }
            ThemeArtStyle.LINK -> {
                val left = Offset(size.width * 0.70f, size.height * 0.22f)
                val right = Offset(size.width * 0.94f, size.height * 0.22f)
                drawCircle(preset.accent.copy(alpha = 0.04f), size.minDimension * 0.31f, center, style = Stroke(1.dp.toPx()))
                drawCircle(preset.secondary.copy(alpha = 0.045f), size.minDimension * 0.48f, center, style = Stroke(1.dp.toPx()))
                drawLine(preset.accent.copy(alpha = 0.09f), left, right, 1.dp.toPx())
                val pulseX = left.x + (right.x - left.x) * phase
                drawCircle(preset.secondary.copy(alpha = 0.30f), 3.dp.toPx(), Offset(pulseX, left.y))
                drawCircle(preset.accent.copy(alpha = 0.16f), 7.dp.toPx(), Offset(pulseX, left.y))
            }
        }
    }
}
