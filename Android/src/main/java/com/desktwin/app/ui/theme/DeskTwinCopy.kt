package com.desktwin.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalDeskTwinLanguage = compositionLocalOf { "ar" }

/** Keeps UI copy bilingual without coupling visual screens to business state. */
@Composable
fun tr(ar: String, en: String): String = if (LocalDeskTwinLanguage.current == "en") en else ar
