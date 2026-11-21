package com.desktwin.app.ui.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.border

/** Shared visual contract for every new DeskTwin screen. */
@Composable
fun DeskTwinCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val preset = LocalDeskTwinThemePreset.current
    val appleStyle = preset.artStyle == ThemeArtStyle.APPLE
    val glassAlpha = if (preset.artStyle == ThemeArtStyle.ORBIT) 0.82f else 1f
    val cardModifier = if (appleStyle) {
        modifier.themeIdleMotion(amplitudeDp = 0.75f)
    } else {
        modifier.themeIdleMotion(amplitudeDp = 0.75f).border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.32f),
            shape = shape
        )
    }
    Card(
        modifier = cardModifier,
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = glassAlpha)),
        elevation = CardDefaults.cardElevation(defaultElevation = when {
            preset.artStyle == ThemeArtStyle.ORBIT -> 3.dp
            appleStyle -> 0.dp
            else -> 1.dp
        })
    ) { Box(Modifier.padding(if (appleStyle) 18.dp else 16.dp)) { content() } }
}

@Composable
fun DeskTwinScreenHeader(title: String, onBack: () -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("رجوع", "Back"))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

/** Idle sway was removed so cards and file rows stay visually still. */
@Composable
fun Modifier.themeIdleMotion(amplitudeDp: Float = 1f, phaseOffset: Float? = null): Modifier {
    return this
}

@Composable
fun DeskTwinPrimaryButton(text: String, loading: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val preset = LocalDeskTwinThemePreset.current
    val reduceMotion = LocalDeskTwinReduceMotion.current
    val pressedScale = when (preset.artStyle) {
        ThemeArtStyle.ORBIT -> 0.966f
        ThemeArtStyle.CODE -> 0.988f
        ThemeArtStyle.EMBER -> 0.972f
        ThemeArtStyle.ORCHARD -> 0.982f
        ThemeArtStyle.APPLE -> 0.982f
        ThemeArtStyle.LINK -> 0.975f
    }
    val springStiffness = when (preset.artStyle) {
        ThemeArtStyle.ORBIT -> 340f
        ThemeArtStyle.CODE -> 760f
        ThemeArtStyle.EMBER -> 280f
        ThemeArtStyle.ORCHARD -> 420f
        ThemeArtStyle.APPLE -> 420f
        ThemeArtStyle.LINK -> 520f
    }
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed && enabled && !loading && !reduceMotion) pressedScale else 1f,
        animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = springStiffness * preset.motionScale.coerceAtLeast(0.5f)
        ),
        label = "DeskTwin button press"
    )
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        interactionSource = interactionSource,
        modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).graphicsLayer {
            scaleX = pressScale
            scaleY = pressScale
        }.themeIdleMotion(amplitudeDp = if (isPressed) 0f else 1.2f),
        shape = MaterialTheme.shapes.small,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Text(if (loading) tr("جارٍ التنفيذ…", "Working…") else text)
    }
}

@Composable
fun DeskTwinInlineLoading(label: String? = null) {
    val visibleLabel = label ?: tr("جارٍ التحميل…", "Loading…")
    val base = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f)
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        repeat(4) { index ->
            Box(
                Modifier
                    .fillMaxWidth(if (index == 3) .62f else 1f)
                    .height(if (index == 0) 38.dp else 18.dp)
                    .padding(vertical = 4.dp)
                    .background(base, RoundedCornerShape(8.dp))
            )
        }
        Text(visibleLabel, modifier = Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
