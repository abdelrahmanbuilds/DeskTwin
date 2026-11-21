package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.desktwin.app.ui.theme.*

/**
 * أول مرة بس - المستخدم بيسجل اسمه، والتطبيق يترتبط بيه (bound_user في DB
 * الكمبيوتر). الهدف: توضيح "أنا مين اللي بستخدم الكمبيوتر ده من التليفون"،
 * مش قيد أمني إضافي (الأمان شغل الـ Pairing).
 */
@Composable
fun UserBindingScreen(
    computerName: String,
    isSaving: Boolean,
    onNameSubmit: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
    ) {
        val horizontalPadding = if (maxWidth >= 600.dp) 48.dp else 24.dp
        val topSpacing = if (maxHeight < 600.dp) 20.dp else 64.dp
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(topSpacing))

        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), shape = androidx.compose.foundation.shape.CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            tr("مين هيستخدم $computerName من هنا؟", "Who will use $computerName from this phone?"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            tr(
                "الاسم ده هيظهر على الكمبيوتر عشان يعرف مين متصل بيه",
                "This name will appear on the computer so it knows who is connecting."
            ),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = { Text(tr("اسمك", "Your name")) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = { onNameSubmit(name.trim()) },
            enabled = name.trim().isNotEmpty() && !isSaving,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(if (isSaving) tr("جارٍ الحفظ…", "Saving…") else tr("تأكيد", "Confirm"))
        }
    }
    }
}

