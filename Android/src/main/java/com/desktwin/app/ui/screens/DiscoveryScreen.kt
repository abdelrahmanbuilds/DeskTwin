package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.desktwin.app.discovery.DiscoveredDevice
import com.desktwin.app.ui.theme.*

/**
 * الشاشة الأولى اللي هتفتح للمستخدم: بتدور على أي DeskTwin شغال على
 * نفس الشبكة تلقائيًا (mDNS)، مع مسح QR أو إدخال العنوان يدويًا كبديل.
 */
@Composable
fun DiscoveryScreen(
    discoveredDevices: List<DiscoveredDevice>,
    isSearching: Boolean,
    scanError: String? = null,
    isQrScannerPreparing: Boolean = false,
    onDeviceSelected: (DiscoveredDevice) -> Unit,
    onRetry: () -> Unit,
    onScanQrClick: () -> Unit,
    onManualAddressSubmit: (String) -> Unit
) {
    var manualAddress by rememberSaveable { mutableStateOf("") }
    val addressParts = manualAddress.trim().split('.')
    val validAddress = addressParts.size == 4 && addressParts.all { part ->
        part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() }
            && part.toIntOrNull()?.let { it in 0..255 } == true
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "DeskTwin",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = tr("جهازك، معاك في أي مكان", "Your computer, within reach"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        TextButton(onClick = onScanQrClick, enabled = !isQrScannerPreparing, modifier = Modifier.padding(top = 6.dp)) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isQrScannerPreparing) tr("جارٍ تجهيز ماسح QR…", "Preparing QR scanner…") else tr("امسح QR من الكمبيوتر", "Scan QR from your computer"))
        }

        Spacer(modifier = Modifier.height(12.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tr("إعداد اتصال يدوي", "Manual connection"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(tr("أدخل IPv4 الكمبيوتر على نفس الشبكة. المنفذ 8420؛ اسمح لـDeskTwin في جدار حماية Windows.", "Enter the computer's IPv4 on the same network. Port 8420; allow DeskTwin through Windows Firewall."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = manualAddress,
                    onValueChange = { manualAddress = it.filter { character -> character in '0'..'9' || character == '.' }.take(15) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(tr("عنوان الكمبيوتر", "Computer IP address")) },
                    placeholder = { Text("192.168.1.20") },
                    singleLine = true,
                    // Keep the dot available on mobile keyboards; input is still
                    // restricted to ASCII digits and dots in onValueChange.
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Button(
                    onClick = { onManualAddressSubmit(manualAddress.trim()) },
                    enabled = validAddress,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(tr("متابعة وإدخال كود الاقتران", "Continue to pairing code"))
                }
            }
        }

        scanError?.let { message ->
            Surface(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp),
                color = ErrorRed.copy(alpha = 0.10f)
            ) {
                Text(
                    text = message,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (isSearching) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 16.dp)
            ) {
                Text(tr("جاري البحث…", "Searching…"), color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(12.dp))
                Text(tr("بيدور على كمبيوترك على نفس الشبكة...", "Looking for your computer on this network…"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        if (discoveredDevices.isEmpty() && !isSearching) {
            EmptyStateCard(onRetry = onRetry)
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            discoveredDevices.forEach { device ->
                DeviceCard(device = device, onClick = { onDeviceSelected(device) })
            }
        }
    }
}

@Composable
private fun DeviceCard(device: DiscoveredDevice, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Computer, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text("${device.ip}:${device.port}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(SuccessGreen)
            )
        }
    }
}

@Composable
private fun EmptyStateCard(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Wifi,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            tr("مفيش أجهزة اتلقت", "No computers found"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            tr("تأكد إن الكمبيوتر شغال وعلى نفس الواي فاي", "Make sure your computer is on and connected to this Wi‑Fi"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        DeskTwinPrimaryButton(text = tr("دور تاني", "Search again"), onClick = onRetry)
    }
}

