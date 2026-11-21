package com.desktwin.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.detectTapGestures
import com.desktwin.app.network.DirEntry
import com.desktwin.app.network.DriveEntry
import com.desktwin.app.network.SpecialFolderEntry
import com.desktwin.app.data.FavoriteFolder
import com.desktwin.app.ui.icons.FileIcon
import com.desktwin.app.ui.icons.FileKindClassifier
import com.desktwin.app.ui.icons.FolderContent
import com.desktwin.app.ui.icons.FolderIcon
import com.desktwin.app.ui.theme.*

/**
 * الشاشة الأساسية: شجرة فولدرات حقيقية زي File Explorer -
 * تدخل فولدر تشوف اللي جواه، تدوس رجوع ترجع للي فوقه، مش قائمة مسطحة.
 *
 * Tap عادي = فتح (فولدر يفتح، ملف يوديك لتفاصيله)
 * Long press = قائمة خيارات (زي كليك يمين على الكمبيوتر)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    currentPath: String,
    parentPath: String?,
    entries: List<DirEntry>,
    specialFolders: List<SpecialFolderEntry>,
    drives: List<DriveEntry>,
    isAtRoot: Boolean,
    isLoading: Boolean,
    isConnected: Boolean,
    isReconnecting: Boolean = false,
    isDisconnecting: Boolean = false,
    onEntryClick: (DirEntry) -> Unit,
    onEntryLongClick: (DirEntry) -> Unit,
    onSpecialFolderClick: (SpecialFolderEntry) -> Unit,
    onDriveClick: (DriveEntry) -> Unit,
    onBackClick: () -> Unit,
    onBreadcrumbClick: (String) -> Unit = {},
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onUploadClick: () -> Unit,
    onTransfersClick: () -> Unit = {},
    transferCount: Int = 0,
    favoriteFolders: List<FavoriteFolder> = emptyList(),
    isCurrentFolderFavorite: Boolean = false,
    onToggleCurrentFolderFavorite: () -> Unit = {},
    onFavoriteFolderClick: (FavoriteFolder) -> Unit = {},
    onRefreshRequested: () -> Unit,          // Pull-to-refresh اليدوي (سحب لتحت)
    onReconnectClick: () -> Unit = {},       // ضغطة "إعادة الاتصال" لما يكون أوف لاين
    onDisconnectAndRefreshClick: () -> Unit = {},
    folderContents: Map<String, FolderContent> = emptyMap(),  // مسار فولدر -> ملخص محتواه (لشكل الأيقونة)
    isPickerMode: Boolean = false,          // true = شاشة اختيار مجلد وجهة، مش تصفح عادي
    onPickCurrentFolder: () -> Unit = {},    // "اختار المجلد ده" - يظهر بس في وضع الاختيار
    hasClipboard: Boolean = false,
    onPasteClick: () -> Unit = {},
    offlineLastSyncAt: Long? = null,
    selectedPaths: Set<String> = emptySet(),
    onToggleSelection: (DirEntry) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onBulkDeleteClick: () -> Unit = {},
    onBulkCopyClick: () -> Unit = {},
    onBulkCutClick: () -> Unit = {},
    fileViewMode: String = "list",
    onFileViewModeChange: (String) -> Unit = {},
    reduceMotion: Boolean = false,
    themeSoundUri: String? = null,
    onThumbnailLoad: suspend (DirEntry) -> Bitmap? = { null }
) {
    val gridView = fileViewMode == "grid"
    val activeTheme = LocalDeskTwinThemePreset.current
    val context = LocalContext.current
    var showBulkDeleteConfirmation by rememberSaveable { mutableStateOf(false) }
    var showThemeEasterEgg by remember { mutableStateOf(false) }
    val revealThemeTouch = {
        ThemeSoundPlayer.play(activeTheme.id, context, themeSoundUri)
        showThemeEasterEgg = true
    }
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

            if (isPickerMode) {
                PickerTopBar(currentPath = if (isAtRoot) tr("الرئيسية", "Home") else currentPath, canGoBack = !isAtRoot, onBackClick = onBackClick)
            } else {
                TopBar(isConnected = isConnected, isReconnecting = isReconnecting, isDisconnecting = isDisconnecting, onSearchClick = onSearchClick, onSettingsClick = onSettingsClick, onReconnectClick = onReconnectClick, onDisconnectAndRefreshClick = onDisconnectAndRefreshClick, onTransfersClick = onTransfersClick, onRefreshRequested = onRefreshRequested, transferCount = transferCount, showFavoriteAction = !isAtRoot, isCurrentFolderFavorite = isCurrentFolderFavorite, onToggleCurrentFolderFavorite = onToggleCurrentFolderFavorite, hasClipboard = hasClipboard, onPasteClick = onPasteClick, selectedCount = selectedPaths.size, onClearSelection = onClearSelection, onBulkDeleteClick = { showBulkDeleteConfirmation = true }, onBulkCopyClick = onBulkCopyClick, onBulkCutClick = onBulkCutClick, gridView = gridView, onToggleView = { onFileViewModeChange(if (gridView) "list" else "grid") }, themeArtStyle = activeTheme.artStyle, onThemeEasterEgg = revealThemeTouch)
                Breadcrumb(path = if (isAtRoot) tr("الرئيسية", "Home") else currentPath, canGoBack = !isAtRoot, onBackClick = onBackClick, onPathClick = onBreadcrumbClick)
                DeskTwinVisibility(visible = !isConnected) {
                    Text(
                        text = offlineLastSyncAt?.let { tr("أوفلاين — آخر تحديث للشجرة: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}", "Offline — last folder update: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))}") }
                            ?: tr("أوفلاين — لا يوجد تحديث محفوظ للشجرة", "Offline — no saved folder update"),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                DeskTwinContent(targetState = Triple(isAtRoot, isLoading && entries.isEmpty(), entries.size)) {
                    if (isAtRoot && !isConnected && specialFolders.isEmpty() && drives.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            DeskTwinCard(modifier = Modifier.fillMaxWidth().padding(24.dp)) {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(22.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                    Text(tr("اربط الكمبيوتر لعرض الملفات", "Connect your computer to browse files"), style = MaterialTheme.typography.titleMedium)
                                    Text(tr("امسح QR أو أدخل عنوان الكمبيوتر لإعداد الاتصال.", "Scan the QR code or enter your computer address to set up the connection."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    DeskTwinPrimaryButton(text = tr("إعداد الاتصال", "Set up connection"), onClick = onReconnectClick)
                                }
                            }
                        }
                    } else if (isAtRoot) {
                        AnimatedContent(
                            targetState = gridView,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                if (reduceMotion) {
                                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                                } else {
                                    val duration = (240 * activeTheme.motionScale).toInt().coerceAtLeast(120)
                                    fadeIn(tween(duration, easing = FastOutSlowInEasing)) +
                                        scaleIn(tween(duration, easing = FastOutSlowInEasing), initialScale = 0.985f) togetherWith
                                        fadeOut(tween((duration * 0.8f).toInt(), easing = FastOutSlowInEasing)) +
                                        scaleOut(tween((duration * 0.8f).toInt(), easing = FastOutSlowInEasing), targetScale = 0.99f)
                                }
                            },
                            label = "Root list and grid transition"
                        ) { showGrid ->
                            RootView(specialFolders, drives, favoriteFolders, onSpecialFolderClick, onDriveClick, onFavoriteFolderClick, showGrid)
                        }
                    } else if (isLoading && entries.isEmpty()) {
                        DeskTwinInlineLoading()
                    } else if (entries.isEmpty() && !isLoading) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            DeskTwinCard(
                                modifier = Modifier.padding(24.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = CircleShape,
                                        color = if (!isConnected && offlineLastSyncAt == null) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Icon(
                                            if (!isConnected && offlineLastSyncAt == null) Icons.Default.Computer else Icons.Default.FolderOpen,
                                            contentDescription = null,
                                            modifier = Modifier.padding(14.dp).size(28.dp),
                                            tint = if (!isConnected && offlineLastSyncAt == null) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSecondaryContainer
                                        )
                                    }
                                    Text(
                                        if (!isConnected && offlineLastSyncAt == null)
                                            tr("اربط الكمبيوتر لعرض ملفاتك", "Connect your computer to see your files")
                                        else tr("المجلد فاضي", "This folder is empty"),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        if (!isConnected && offlineLastSyncAt == null)
                                            tr("امسح QR أو أدخل عنوان الكمبيوتر لبدء الاتصال.", "Scan a QR code or enter the computer address to connect.")
                                        else tr("حدّث المحتوى أو جرّب مرة أخرى.", "Refresh the contents or try again."),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!isConnected && offlineLastSyncAt == null) {
                                        DeskTwinPrimaryButton(
                                            text = tr("إعداد الاتصال", "Set up connection"),
                                            onClick = onReconnectClick
                                        )
                                    } else {
                                        TextButton(onClick = onRefreshRequested) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(tr("تحديث", "Refresh"))
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        val listModifier = Modifier.fillMaxSize().pointerInput(hasClipboard, currentPath) {
                            detectTapGestures(onLongPress = { if (hasClipboard) onPasteClick() })
                        }
                        AnimatedContent(
                            targetState = gridView && !isPickerMode,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                if (reduceMotion) {
                                    fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                                } else {
                                    val duration = (240 * activeTheme.motionScale).toInt().coerceAtLeast(120)
                                    fadeIn(tween(duration, easing = FastOutSlowInEasing)) +
                                        scaleIn(tween(duration, easing = FastOutSlowInEasing), initialScale = 0.985f) togetherWith
                                        fadeOut(tween((duration * 0.8f).toInt(), easing = FastOutSlowInEasing)) +
                                        scaleOut(tween((duration * 0.8f).toInt(), easing = FastOutSlowInEasing), targetScale = 0.99f)
                                }
                            },
                            label = "Folder list and grid transition"
                        ) { showGrid ->
                            if (showGrid) {
                                LazyVerticalGrid(
                                    columns = GridCells.Adaptive(minSize = 148.dp),
                                    modifier = listModifier,
                                    contentPadding = PaddingValues(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    items(entries, key = { it.path }) { entry ->
                                        EntryGridTile(
                                            entry = entry,
                                            folderContent = if (entry.is_folder) contentForFolder(entry.path, folderContents) else null,
                                            selected = entry.path in selectedPaths,
                                            selectionMode = selectedPaths.isNotEmpty(),
                                            onClick = { if (selectedPaths.isNotEmpty()) onToggleSelection(entry) else onEntryClick(entry) },
                                            onLongClick = { onToggleSelection(entry) },
                                            onThumbnailLoad = onThumbnailLoad
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(modifier = listModifier, contentPadding = PaddingValues(vertical = 4.dp)) {
                                    items(entries, key = { it.path }) { entry ->
                                        if (!isPickerMode || entry.is_folder) {
                                            EntryRow(
                                                entry = entry,
                                                folderContent = if (entry.is_folder) contentForFolder(entry.path, folderContents) else null,
                                                selected = entry.path in selectedPaths,
                                                selectionMode = selectedPaths.isNotEmpty(),
                                                onClick = { if (selectedPaths.isNotEmpty()) onToggleSelection(entry) else onEntryClick(entry) },
                                                onLongClick = if (!isPickerMode) ({ onToggleSelection(entry) }) else null,
                                                onThumbnailLoad = onThumbnailLoad
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (isPickerMode && !isAtRoot) {
            Button(
                onClick = onPickCurrentFolder,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(50.dp)
            ) {
                Text("اختار هذا المجلد")
            }
        } else if (!isPickerMode) {
            Row(
                modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasClipboard) {
                    FloatingActionButton(
                        onClick = onPasteClick,
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "لصق هنا")
                    }
                }
                if (activeTheme.artStyle == ThemeArtStyle.APPLE) {
                    ExtendedFloatingActionButton(
                        onClick = onUploadClick,
                        icon = { Icon(Icons.Default.CloudUpload, contentDescription = null) },
                        text = { Text(tr("إرسال ملف", "Upload file")) },
                        shape = RoundedCornerShape(16.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 3.dp, pressedElevation = 2.dp)
                    )
                } else {
                    FloatingActionButton(
                        onClick = onUploadClick,
                        shape = RoundedCornerShape(20.dp),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 3.dp)
                    ) {
                        Icon(Icons.Default.CloudUpload, contentDescription = tr("إرسال ملف للكمبيوتر", "Send file to computer"))
                    }
                }
            }
        }
        if (showBulkDeleteConfirmation && selectedPaths.isNotEmpty()) {
            AlertDialog(
                onDismissRequest = { showBulkDeleteConfirmation = false },
                title = { Text(tr("تأكيد الحذف", "Confirm deletion")) },
                text = { Text(tr("هل تريد حذف ${selectedPaths.size} عنصرًا؟", "Delete ${selectedPaths.size} selected items?")) },
                confirmButton = {
                    TextButton(onClick = {
                        showBulkDeleteConfirmation = false
                        onBulkDeleteClick()
                    }) { Text(tr("حذف", "Delete"), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showBulkDeleteConfirmation = false }) { Text(tr("إلغاء", "Cancel")) }
                }
            )
        }
        if (!isPickerMode && showThemeEasterEgg) {
            ThemeEasterEggDialog(activeTheme, reduceMotion) {
                ThemeSoundPlayer.stop()
                showThemeEasterEgg = false
            }
        }
    }
}

@Composable
private fun PickerTopBar(currentPath: String, canGoBack: Boolean, onBackClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canGoBack) {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
        } else {
            Spacer(modifier = Modifier.width(48.dp))
        }
        Column {
            Text("اختار مكان الحفظ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(currentPath, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun RootView(
    specialFolders: List<SpecialFolderEntry>,
    drives: List<DriveEntry>,
    favoriteFolders: List<FavoriteFolder>,
    onSpecialFolderClick: (SpecialFolderEntry) -> Unit,
    onDriveClick: (DriveEntry) -> Unit,
    onFavoriteFolderClick: (FavoriteFolder) -> Unit,
    gridView: Boolean
) {
    val appleStyle = LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE
    if (gridView) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 148.dp),
            contentPadding = PaddingValues(horizontal = if (appleStyle) 20.dp else 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (favoriteFolders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { RootSectionTitle(tr("الوصول السريع", "Quick access")) }
                items(favoriteFolders, key = { "favorite:${it.path}" }) { folder ->
                    RootTile(Icons.Default.Star, folder.name) { onFavoriteFolderClick(folder) }
                }
            }
            if (specialFolders.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { RootSectionTitle(tr("مجلدات الكمبيوتر", "Computer folders")) }
                items(specialFolders, key = { "special:${it.path}" }) { folder ->
                    RootTile(iconForSpecialFolder(folder.name), translateSpecialFolder(folder.name)) { onSpecialFolderClick(folder) }
                }
            }
            if (drives.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { RootSectionTitle(tr("الأقراص", "Drives")) }
                items(drives, key = { "drive:${it.name}" }) { drive ->
                    RootTile(Icons.Default.Storage, drive.name) { onDriveClick(drive) }
                }
            }
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = if (appleStyle) 20.dp else 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(if (appleStyle) 8.dp else 4.dp)
    ) {

        if (favoriteFolders.isNotEmpty()) {
            item { RootSectionTitle(tr("الوصول السريع", "Quick access")) }
            items(favoriteFolders, key = { it.path }) { folder ->
                ShortcutRow(
                    icon = Icons.Default.Star,
                    name = folder.name,
                    onClick = { onFavoriteFolderClick(folder) }
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        if (specialFolders.isNotEmpty()) {
            item { RootSectionTitle(tr("مجلدات الكمبيوتر", "Computer folders")) }
            items(specialFolders, key = { it.path }) { folder ->
                ShortcutRow(
                    icon = iconForSpecialFolder(folder.name),
                    name = translateSpecialFolder(folder.name),
                    onClick = { onSpecialFolderClick(folder) }
                )
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }

        if (drives.isNotEmpty()) {
            item { RootSectionTitle(tr("الأقراص", "Drives")) }
            items(drives, key = { it.name }) { drive ->
                ShortcutRow(
                    icon = Icons.Default.Storage,
                    name = drive.name,
                    onClick = { onDriveClick(drive) }
                )
            }
        }
    }
}

@Composable
private fun RootSectionTitle(title: String) {
    Text(
        title,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun RootTile(icon: ImageVector, name: String, onClick: () -> Unit) {
    val appleStyle = LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(if (appleStyle) 16.dp else 18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (appleStyle) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        tonalElevation = if (appleStyle) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().heightIn(min = if (appleStyle) 112.dp else 126.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp).size(28.dp)
                )
            }
            Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutRow(icon: ImageVector, name: String, onClick: () -> Unit) {
    val appleStyle = LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(if (appleStyle) 14.dp else 20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = if (appleStyle) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
        tonalElevation = if (appleStyle) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (appleStyle) 12.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(shape = RoundedCornerShape(15.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(12.dp).size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(
                    if (appleStyle) tr("مجلد الكمبيوتر", "Computer folder") else tr("مجلد الكمبيوتر · اضغط للاستعراض", "Computer folder · Tap to browse"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun iconForSpecialFolder(name: String): ImageVector = when (name) {
    "Desktop" -> Icons.Default.DesktopWindows
    "Downloads" -> Icons.Default.Download
    "Documents" -> Icons.Default.Description
    "Pictures" -> Icons.Default.Image
    "Videos" -> Icons.Default.Movie
    "Music" -> Icons.Default.MusicNote
    else -> Icons.Default.Folder
}

private fun translateSpecialFolder(name: String): String = when (name) {
    "Desktop" -> "سطح المكتب"
    "Downloads" -> "التنزيلات"
    "Documents" -> "المستندات"
    "Pictures" -> "الصور"
    "Videos" -> "الفيديوهات"
    "Music" -> "الموسيقى"
    else -> name
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(isConnected: Boolean, onSearchClick: () -> Unit, onSettingsClick: () -> Unit, onReconnectClick: () -> Unit, onDisconnectAndRefreshClick: () -> Unit, onTransfersClick: () -> Unit, onRefreshRequested: () -> Unit, transferCount: Int, showFavoriteAction: Boolean, isCurrentFolderFavorite: Boolean, onToggleCurrentFolderFavorite: () -> Unit, hasClipboard: Boolean = false, onPasteClick: () -> Unit = {}, selectedCount: Int = 0, onClearSelection: () -> Unit = {}, onBulkDeleteClick: () -> Unit = {}, onBulkCopyClick: () -> Unit = {}, onBulkCutClick: () -> Unit = {}, gridView: Boolean = false, onToggleView: () -> Unit = {}, isReconnecting: Boolean = false, isDisconnecting: Boolean = false, themeArtStyle: ThemeArtStyle = ThemeArtStyle.LINK, onThemeEasterEgg: () -> Unit = {}) {
    val appleStyle = themeArtStyle == ThemeArtStyle.APPLE
    val barShape = RoundedCornerShape(if (appleStyle) 18.dp else 22.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (appleStyle) 16.dp else 12.dp, vertical = if (appleStyle) 6.dp else 10.dp),
        shape = barShape,
        color = if (appleStyle) MaterialTheme.colorScheme.surface.copy(alpha = 0.94f) else MaterialTheme.colorScheme.surface,
        border = if (appleStyle) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.30f)),
        tonalElevation = if (appleStyle) 0.dp else 1.dp
    ) { Box(
        modifier = Modifier
            .clip(barShape)
            .then(if (appleStyle) Modifier else Modifier.background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                        MaterialTheme.colorScheme.secondary.copy(alpha = 0.06f)
                    )
                )
            ))
    ) { Column(
        modifier = Modifier.padding(horizontal = if (appleStyle) 10.dp else 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val brandModifier = if (themeArtStyle == ThemeArtStyle.LINK) {
                Modifier.weight(1f).themeIdleMotion(0.65f).combinedClickable(
                    role = Role.Button,
                    onLongClickLabel = tr("لمسة مخفية", "Hidden touch"),
                    onClick = {},
                    onLongClick = onThemeEasterEgg
                )
            } else Modifier.weight(1f)
            Text(
                "DeskTwin",
                modifier = brandModifier,
                maxLines = 1,
                style = if (appleStyle) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            val statusColor = when {
                isDisconnecting -> MaterialTheme.colorScheme.primary
                isConnected -> SuccessGreen
                isReconnecting -> MaterialTheme.colorScheme.primary
                appleStyle -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.error
            }
            Surface(
                onClick = { if (!isConnected) onReconnectClick() },
                enabled = !isConnected && !isReconnecting && !isDisconnecting,
                modifier = Modifier.themeIdleMotion(0.6f),
                shape = RoundedCornerShape(50),
                color = statusColor.copy(alpha = 0.11f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isReconnecting || isDisconnecting) {
                        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = statusColor)
                    } else {
                        Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(statusColor))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            isDisconnecting -> tr("حفظ الشجرة…", "Saving tree…")
                            isConnected -> tr("متصل", "Online")
                            isReconnecting -> tr("جارٍ الاتصال…", "Connecting…")
                            else -> tr("إعداد الاتصال", "Connect")
                        },
                        style = MaterialTheme.typography.labelSmall, color = statusColor,
                        maxLines = 1
                    )
                }
            }
            IconButton(
                onClick = onDisconnectAndRefreshClick,
                enabled = isConnected && !isDisconnecting,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(
                    Icons.Default.PowerSettingsNew,
                    contentDescription = if (isConnected) tr("تحديث الشجرة ثم قطع الاتصال", "Refresh tree and disconnect")
                        else tr("قطع الاتصال غير متاح الآن", "Disconnect unavailable while offline"),
                    tint = if (isConnected && !isDisconnecting) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (appleStyle) 2.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
        if (selectedCount > 0) {
            Text(tr("محدد: $selectedCount", "Selected: $selectedCount"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onBulkDeleteClick) {
                Icon(Icons.Default.Delete, contentDescription = tr("حذف المحدد", "Delete selection"), tint = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onBulkCopyClick) {
                Icon(Icons.Default.ContentCopy, contentDescription = tr("نسخ المحدد", "Copy selection"), tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onBulkCutClick) {
                Icon(Icons.Default.ContentCut, contentDescription = tr("قص المحدد", "Cut selection"), tint = MaterialTheme.colorScheme.onSurface)
            }
            IconButton(onClick = onClearSelection) {
                Icon(Icons.Default.Close, contentDescription = tr("إلغاء التحديد", "Clear selection"), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
        ToolbarActionButton(
            icon = if (gridView) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView,
            description = if (gridView) tr("عرض قائمة", "List view") else tr("عرض شبكي", "Grid view"),
            active = gridView,
            onClick = onToggleView,
            onLongClick = if (themeArtStyle == ThemeArtStyle.APPLE) onThemeEasterEgg else null
        )
        ToolbarActionButton(Icons.Default.Refresh, tr("تحديث الملفات", "Refresh files"), onClick = onRefreshRequested, rotationOnClick = 360f, onLongClick = if (themeArtStyle == ThemeArtStyle.ORBIT) onThemeEasterEgg else null)
        ToolbarActionButton(Icons.Default.Search, tr("بحث", "Search"), onClick = onSearchClick, onLongClick = if (themeArtStyle == ThemeArtStyle.CODE) onThemeEasterEgg else null)
        BadgedBox(
            badge = {
                if (transferCount > 0) {
                    Badge { Text(if (transferCount > 9) "9+" else transferCount.toString()) }
                }
            }
        ) {
            ToolbarActionButton(Icons.Default.SwapVert, tr("مركز النقل", "Transfer center"), onClick = onTransfersClick, rotationOnClick = 180f, onLongClick = if (themeArtStyle == ThemeArtStyle.EMBER) onThemeEasterEgg else null)
        }
        if (showFavoriteAction) {
            ToolbarActionButton(
                if (isCurrentFolderFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                if (isCurrentFolderFavorite) tr("إزالة من الوصول السريع", "Remove from quick access") else tr("إضافة للوصول السريع", "Add to quick access"),
                active = isCurrentFolderFavorite,
                onClick = onToggleCurrentFolderFavorite
            )
        }
        if (hasClipboard) {
            ToolbarActionButton(Icons.Default.ContentPaste, tr("لصق", "Paste"), onClick = onPasteClick)
        }
        ToolbarActionButton(Icons.Default.Settings, tr("إعدادات", "Settings"), onClick = onSettingsClick, rotationOnClick = 30f)
        }
    } } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToolbarActionButton(
    icon: ImageVector,
    description: String,
    active: Boolean = false,
    rotationOnClick: Float = 0f,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null
) {
    val appleStyle = LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE
    val reduceMotion = LocalDeskTwinReduceMotion.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed && !reduceMotion && appleStyle -> 0.96f
            pressed && !reduceMotion -> 0.92f
            else -> 1f
        },
        animationSpec = spring(stiffness = 520f),
        label = "toolbar press"
    )
    var rotationCount by remember { mutableIntStateOf(0) }
    val rotation by animateFloatAsState(
        targetValue = if (appleStyle || reduceMotion) 0f else rotationCount * rotationOnClick,
        animationSpec = tween(430, easing = FastOutSlowInEasing),
        label = "toolbar refresh"
    )
    val activeContainer = if (appleStyle) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else MaterialTheme.colorScheme.secondaryContainer
    Surface(
        shape = if (appleStyle) CircleShape else RoundedCornerShape(15.dp),
        color = when {
            active -> activeContainer
            appleStyle -> androidx.compose.ui.graphics.Color.Transparent
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f)
        },
        border = if (LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.ORBIT)
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)) else null,
        modifier = Modifier
            .themeIdleMotion(amplitudeDp = if (pressed) 0f else 0.85f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = androidx.compose.foundation.LocalIndication.current,
                role = Role.Button,
                onClickLabel = description,
                onLongClickLabel = if (onLongClick != null) tr("لمسة مخفية", "Hidden touch") else null,
                onClick = {
                    if (rotationOnClick != 0f && !appleStyle && !reduceMotion) rotationCount++
                    onClick()
                },
                onLongClick = onLongClick
            )
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = description,
                tint = when {
                    active && appleStyle -> MaterialTheme.colorScheme.primary
                    active -> MaterialTheme.colorScheme.onSecondaryContainer
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = if (rotationOnClick != 0f) Modifier.graphicsLayer { rotationZ = rotation } else Modifier
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileThumbnailOrIcon(
    entry: DirEntry,
    size: androidx.compose.ui.unit.Dp,
    onThumbnailLoad: suspend (DirEntry) -> Bitmap?
) {
    val extension = entry.extension.orEmpty().lowercase()
    val canPreview = extension in setOf(
        ".png", ".jpg", ".jpeg", ".webp", ".bmp", ".gif", ".tif", ".tiff",
        ".mp4", ".mkv", ".webm", ".mov", ".m4v", ".avi", ".wmv"
    )
    val latestLoader by rememberUpdatedState(onThumbnailLoad)
    var bitmap by remember(entry.path, entry.size_bytes, entry.modified_at) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(entry.path, entry.size_bytes, entry.modified_at, canPreview) {
        if (canPreview) {
            bitmap = try {
                latestLoader(entry)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
        }
    }

    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image.asImageBitmap(),
            contentDescription = entry.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp))
        )
    } else {
        FileIcon(kind = FileKindClassifier.classify(entry.extension), size = size)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryGridTile(
    entry: DirEntry,
    folderContent: FolderContent?,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onThumbnailLoad: suspend (DirEntry) -> Bitmap?
) {
    val appleStyle = LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE
    val clickLabel = when {
        selectionMode && selected -> tr("إلغاء تحديد ${entry.name}", "Deselect ${entry.name}")
        selectionMode -> tr("تحديد ${entry.name}", "Select ${entry.name}")
        entry.is_folder -> tr("فتح المجلد ${entry.name}", "Open folder ${entry.name}")
        else -> tr("فتح الملف ${entry.name}", "Open file ${entry.name}")
    }
    val longClickLabel = if (selected)
        tr("إلغاء تحديد ${entry.name}", "Deselect ${entry.name}")
    else
        tr("تحديد ${entry.name}", "Select ${entry.name}")
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 136.dp)
            .themeIdleMotion(if (selected) 0.35f else 0.75f)
            .combinedClickable(
                onClickLabel = clickLabel,
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(if (appleStyle) 16.dp else 14.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = if (appleStyle) null else androidx.compose.foundation.BorderStroke(
            1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
        ),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            if (entry.is_folder) {
                FolderIcon(content = folderContent ?: FolderContent.Empty, size = 48.dp)
            } else {
                FileThumbnailOrIcon(entry, 48.dp, onThumbnailLoad)
            }
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.is_folder) folderSubtitle(folderContent) else fileTypeLabel(entry.extension),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun Breadcrumb(path: String, canGoBack: Boolean, onBackClick: () -> Unit, onPathClick: (String) -> Unit) {
    val appleStyle = LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (appleStyle) Modifier else Modifier.background(MaterialTheme.colorScheme.surface))
            .padding(horizontal = if (appleStyle) 20.dp else 8.dp, vertical = if (appleStyle) 10.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (canGoBack) {
            IconButton(onClick = onBackClick, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("رجوع", "Back"), modifier = Modifier.size(20.dp))
            }
            Spacer(modifier = Modifier.width(4.dp))
        } else if (!appleStyle) {
            Spacer(modifier = Modifier.width(36.dp))
        }
        val normalized = path.trimEnd('\\', '/')
        val separator = if (normalized.contains('\\')) "\\" else "/"
        val parts = normalized.split('\\', '/').filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            Text(
                tr("الرئيسية", "Home"),
                style = if (appleStyle) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = if (appleStyle) FontWeight.Bold else FontWeight.Normal
            )
        } else {
            var built = ""
            Row(modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                parts.forEachIndexed { index, part ->
                    built = if (index == 0) part else built + separator + part
                    val target = built + if (index == 0 && part.endsWith(":")) separator else ""
                    if (index > 0) Text("  ›  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = part,
                        style = if (appleStyle) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        color = if (index == parts.lastIndex) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier.clickable { onPathClick(target) }
                    )
                }
            }
        }
    }
}

/**
 * صف بنمط File Explorer (Details): أيقونة على الشمال، الاسم، وعلى الجنب
 * تاريخ التعديل والحجم، وتحته خط رفيع. مفيش "علامة مزامنة" زي ما طلبت.
 *
 * الأيقونة: فولدر أصفر (شكله بيتغير حسب محتواه) أو ورقة عليها علامة النوع.
 * كله من الـ metadata (الامتداد + ملخص المحتوى)، مفيش thumbnail حقيقي.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: DirEntry,
    folderContent: FolderContent?,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    onThumbnailLoad: suspend (DirEntry) -> Bitmap?
) {
    val appleStyle = LocalDeskTwinThemePreset.current.artStyle == ThemeArtStyle.APPLE
    val clickLabel = when {
        selectionMode && selected -> tr("إلغاء تحديد ${entry.name}", "Deselect ${entry.name}")
        selectionMode -> tr("تحديد ${entry.name}", "Select ${entry.name}")
        entry.is_folder -> tr("فتح المجلد ${entry.name}", "Open folder ${entry.name}")
        else -> tr("فتح الملف ${entry.name}", "Open file ${entry.name}")
    }
    val longClickLabel = if (selected)
        tr("إلغاء تحديد ${entry.name}", "Deselect ${entry.name}")
    else
        tr("تحديد ${entry.name}", "Select ${entry.name}")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (appleStyle) 16.dp else 12.dp, vertical = if (appleStyle) 2.dp else 4.dp)
            .themeIdleMotion(if (selected) 0.35f else 0.75f)
            .clip(RoundedCornerShape(if (appleStyle) 12.dp else 17.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.13f) else MaterialTheme.colorScheme.surface)
            .combinedClickable(
                onClickLabel = clickLabel,
                role = Role.Button,
                onLongClickLabel = if (onLongClick != null) longClickLabel else null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = if (appleStyle) 10.dp else 12.dp, horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selected) {
            Checkbox(checked = true, onCheckedChange = null)
        }
        if (entry.is_folder) {
            FolderIcon(content = folderContent ?: FolderContent.Empty, size = 44.dp)
        } else {
            FileThumbnailOrIcon(entry, 44.dp, onThumbnailLoad)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.name, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = if (entry.is_folder) folderSubtitle(folderContent) else fileTypeLabel(entry.extension),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        // عمود المعلومات على الجنب (تاريخ + حجم) - زي أعمدة Details
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatModified(entry.modified_at),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!entry.is_folder) {
                Text(
                    formatFileSize(entry.size_bytes),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * بيدوّر على ملخص الفولدر في الخريطة. المسار ممكن يجي من /browse بـ "E:\"
 * والخريطة مسجّلة بـ "E:" (من غير الشرطة الأخيرة)، فبنوحّد الاتنين.
 */
private fun contentForFolder(path: String, map: Map<String, FolderContent>): FolderContent {
    val key = path.trimEnd('\\', '/')
    return map[key] ?: map[path] ?: FolderContent.Empty
}

private fun folderSubtitle(content: FolderContent?): String = when (content) {
    null, FolderContent.Empty -> "مجلد"
    is FolderContent.Single -> "مجلد • " + kindLabel(content.kind)
    is FolderContent.Mixed -> "مجلد • ملفات متنوعة"
}

private fun kindLabel(kind: com.desktwin.app.ui.icons.FileKind): String = when (kind) {
    com.desktwin.app.ui.icons.FileKind.IMAGE -> "صور"
    com.desktwin.app.ui.icons.FileKind.VIDEO -> "فيديوهات"
    com.desktwin.app.ui.icons.FileKind.AUDIO -> "صوتيات"
    com.desktwin.app.ui.icons.FileKind.DOCUMENT -> "مستندات"
    com.desktwin.app.ui.icons.FileKind.SPREADSHEET -> "جداول"
    com.desktwin.app.ui.icons.FileKind.PRESENTATION -> "عروض"
    com.desktwin.app.ui.icons.FileKind.ARCHIVE -> "ملفات مضغوطة"
    com.desktwin.app.ui.icons.FileKind.CODE -> "أكواد"
    com.desktwin.app.ui.icons.FileKind.PDF -> "PDF"
    com.desktwin.app.ui.icons.FileKind.OTHER -> "ملفات"
}

private fun fileTypeLabel(extension: String?): String {
    val clean = extension?.trim()?.trimStart('.')?.uppercase().orEmpty()
    return if (clean.isEmpty()) "ملف" else "ملف $clean"
}

/** "2026-04-01T00:19:..." -> "2026-04-01 00:19". لو التنسيق مش متوقع نرجّع أول 16 حرف. */
private fun formatModified(iso: String?): String {
    if (iso.isNullOrBlank()) return "-"
    val cleaned = iso.replace('T', ' ')
    return if (cleaned.length >= 16) cleaned.substring(0, 16) else cleaned
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.size - 1) {
        size /= 1024
        unitIndex++
    }
    return "%.1f %s".format(size, units[unitIndex])
}
