package com.desktwin.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.desktwin.app.ui.theme.tr

enum class DeskTwinMainTab { FILES, TRANSFERS, REMOTE, SETTINGS }

@Composable
fun DeskTwinBottomNavigation(
    selectedTab: DeskTwinMainTab,
    onSelect: (DeskTwinMainTab) -> Unit
) {
    NavigationBar {
        NavigationBarItem(
            selected = selectedTab == DeskTwinMainTab.FILES,
            onClick = { onSelect(DeskTwinMainTab.FILES) },
            icon = { Icon(Icons.Default.Folder, contentDescription = null) },
            label = { Text(tr("الملفات", "Files")) },
            alwaysShowLabel = true
        )
        NavigationBarItem(
            selected = selectedTab == DeskTwinMainTab.TRANSFERS,
            onClick = { onSelect(DeskTwinMainTab.TRANSFERS) },
            icon = { Icon(Icons.Default.SwapVert, contentDescription = null) },
            label = { Text(tr("النقل", "Transfers")) },
            alwaysShowLabel = true
        )
        NavigationBarItem(
            selected = selectedTab == DeskTwinMainTab.REMOTE,
            onClick = { onSelect(DeskTwinMainTab.REMOTE) },
            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null) },
            label = { Text(tr("التحكم", "Remote")) },
            alwaysShowLabel = true
        )
        NavigationBarItem(
            selected = selectedTab == DeskTwinMainTab.SETTINGS,
            onClick = { onSelect(DeskTwinMainTab.SETTINGS) },
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text(tr("الإعدادات", "Settings")) },
            alwaysShowLabel = true
        )
    }
}
