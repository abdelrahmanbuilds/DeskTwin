package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import com.desktwin.app.network.FileMetadata
import com.desktwin.app.ui.theme.*

@Composable
fun SearchScreen(
    results: List<FileMetadata>,
    isSearching: Boolean,
    errorMessage: String?,
    onQueryChange: (String) -> Unit,
    onRetry: () -> Unit,
    onFileClick: (FileMetadata) -> Unit,
    onBackClick: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = MaterialTheme.colorScheme.onSurface)
            }
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                placeholder = { Text("دور على أي حاجة...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = ""; onQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح البحث")
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester)
            )
        }

        if (isSearching) {
            Text(
                "جارٍ البحث…",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }

        when {
            query.isBlank() -> SearchEmptyState(
                icon = Icons.AutoMirrored.Filled.ManageSearch,
                title = "ابحث في الكمبيوتر",
                subtitle = "اكتب اسم الملف أو جزءًا من المسار للوصول له بسرعة."
            )
            errorMessage != null -> SearchErrorState(errorMessage, onRetry)
            results.isEmpty() && !isSearching -> SearchEmptyState(
                icon = Icons.Default.SearchOff,
                title = "مفيش نتائج لـ \"$query\"",
                subtitle = "جرّب كلمة أقصر أو اسمًا مختلفًا."
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text("${results.size} نتيجة", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(results, key = { it.id }) { file ->
                    DeskTwinCard(modifier = Modifier.fillMaxWidth().clickable { onFileClick(file) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                                Icon(
                                    Icons.Default.Description,
                                    contentDescription = null,
                                    modifier = Modifier.padding(10.dp),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.bodyLarge)
                                Text(file.path, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@Composable
private fun SearchErrorState(message: String, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        DeskTwinCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.error)
                Text("تعذر إكمال البحث", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onRetry) { Text("إعادة المحاولة") }
            }
        }
    }
}

@Composable
private fun SearchEmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        DeskTwinCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

