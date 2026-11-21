package com.desktwin.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalDeskTwinLanguage = compositionLocalOf { "en" }

fun localizedText(language: String, arabic: String, english: String): String =
    if (language == "ar") arabic else english

/** Keeps UI copy bilingual without coupling visual screens to business state. */
@Composable
fun tr(ar: String, en: String): String = localizedText(LocalDeskTwinLanguage.current, ar, en)
