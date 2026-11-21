package com.desktwin.app.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Static visibility/content helpers; DeskTwin intentionally has no UI animation. */
@Composable
fun DeskTwinVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (visible) Box(modifier = modifier) { content() }
}

@Composable
fun <T> DeskTwinContent(
    targetState: T,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit
) {
    Box(modifier = modifier) { content(targetState) }
}
