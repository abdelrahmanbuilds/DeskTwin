package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.desktwin.app.ui.theme.*

/**
 * المستخدم بيكتب هنا الكود المكوّن من 6 أرقام اللي ظاهر على شاشة الكمبيوتر.
 * ده بيحصل مرة واحدة بس لكل جهاز - بعدها التوكن بيتخزن ومحتاجش يتكرر.
 */
@Composable
fun PairingScreen(
    computerName: String,
    isVerifying: Boolean,
    errorMessage: String?,
    diagnostics: String? = null,
    scannedCode: String? = null,
    scanAttempt: Int = 0,
    isQrScannerPreparing: Boolean = false,
    onQrScanClick: () -> Unit = {},
    onClearCertificateClick: () -> Unit = {},
    onBack: () -> Unit = {},
    onCodeSubmit: (String, Int?) -> Unit
) {
    var code by remember(scannedCode) { mutableStateOf(scannedCode.orEmpty()) }
    var temporary by remember { mutableStateOf(false) }
    var minutes by remember { mutableStateOf(30) }

    // مسح QR يملأ الكود ويبدأ الاقتران فورًا؛ الكود اليدوي يظل بنفس الزر.
    LaunchedEffect(scannedCode, scanAttempt) {
        if (scannedCode?.matches(Regex("\\d{6}")) == true && !isVerifying) {
            onCodeSubmit(scannedCode, if (temporary) minutes else null)
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 56.dp else 24.dp
        val topSpacing = if (maxHeight < 600.dp) 16.dp else 42.dp
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 600.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
        DeskTwinScreenHeader(tr("ربط جهاز", "Pair device"), onBack)
        Spacer(modifier = Modifier.height(topSpacing))

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Icon(
                Icons.Default.Computer,
                contentDescription = null,
                modifier = Modifier.padding(18.dp).size(42.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            tr("توصيل بـ $computerName", "Connect to $computerName"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        diagnostics?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            tr("هتلاقي كود مكوّن من 6 أرقام ظاهر على شاشة الكمبيوتر دلوقتي", "Enter the six-digit code currently shown on your computer"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(22.dp))

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = SuccessGreen.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Lock, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    tr("اتصال محلي آمن — لا يتم رفع ملفاتك إلى cloud.", "Secure local connection — your files do not go to the cloud."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = temporary, onCheckedChange = { temporary = it })
            Text(tr("جلسة مؤقتة", "Temporary session"))
            if (temporary) {
                Spacer(Modifier.width(8.dp))
                DropdownMenuBox(minutes) { minutes = it }
            }
        }
        if (temporary) {
            Text(
                tr("سينتهي الاقتران تلقائيًا بعد المدة المحددة.", "Pairing will expire automatically after the selected duration."),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, bottom = 8.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = code,
            onValueChange = { if (it.length <= 6) code = it.filter { c -> c.isDigit() } },
            placeholder = { Text("000000") },
            textStyle = MaterialTheme.typography.titleLarge.copy(fontSize = 32.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = errorMessage != null,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        TextButton(onClick = onQrScanClick, enabled = !isQrScannerPreparing, modifier = Modifier.align(Alignment.End)) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isQrScannerPreparing) tr("جارٍ تجهيز ماسح QR…", "Preparing QR scanner…") else tr("امسح QR بدل كتابة الكود", "Scan QR instead"))
        }

        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMessage, color = ErrorRed, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onClearCertificateClick) { Text(tr("مسح شهادة الاقتران وإعادة المحاولة", "Clear the pairing certificate and try again")) }
        }

        Spacer(modifier = Modifier.height(24.dp))

        DeskTwinPrimaryButton(
            text = tr("توصيل آمن", "Connect securely"),
            loading = isVerifying,
            enabled = code.length == 6,
            onClick = { onCodeSubmit(code, if (temporary) minutes else null) }
        )
        }
    }
}

@Composable
private fun DropdownMenuBox(value: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(tr("$value دقيقة", "$value min")) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(15, 30, 60, 180, 1440).forEach { item ->
                DropdownMenuItem(text = { Text(tr("$item دقيقة", "$item min")) }, onClick = { onChange(item); expanded = false })
            }
        }
    }
}

