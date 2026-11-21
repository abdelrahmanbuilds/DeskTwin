package com.desktwin.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.desktwin.app.ui.theme.DeskTwinCard
import com.desktwin.app.ui.theme.tr

data class ChatMessage(val id: String, val text: String, val fromPhone: Boolean, val status: String = "")

@Composable
fun ChatScreen(
    messages: List<ChatMessage>,
    draft: String,
    isConnected: Boolean,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    onQuickActionsClick: (() -> Unit)? = null
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val isWideWidth = maxWidth >= 600.dp
        val isCompactWidth = maxWidth < 420.dp
        val bubbleMaxWidth = if (isWideWidth) (maxWidth - 72.dp).coerceAtMost(560.dp) else maxWidth * 0.86f
        val listState = rememberLazyListState()

        LaunchedEffect(messages.lastOrNull()?.id) {
            if (messages.isNotEmpty()) {
                val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                if (lastVisibleIndex < 0 || lastVisibleIndex >= messages.lastIndex - 1) {
                    listState.scrollToItem(messages.lastIndex)
                }
            }
        }

        Column(
            Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 800.dp)
                .fillMaxWidth()
                .fillMaxSize()
                .padding(horizontal = if (isWideWidth) 28.dp else 16.dp, vertical = 12.dp)
        ) {
            DeskTwinCard(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = tr("رجوع", "Back"))
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Icon(
                            Icons.Default.Computer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(tr("محادثة الكمبيوتر", "Computer chat"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            tr("رسائل مباشرة بين الجهازين", "Direct messages between your devices"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (isConnected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                                contentDescription = null,
                                modifier = Modifier.height(16.dp),
                                tint = if (isConnected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                if (isConnected) tr("متصل", "Online") else tr("غير متصل", "Offline"),
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isConnected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    if (onQuickActionsClick != null) {
                        IconButton(onClick = onQuickActionsClick) {
                            Icon(Icons.Default.Computer, contentDescription = tr("التحكم السريع", "Quick controls"))
                        }
                    }
                }
            }

            if (!isConnected) {
                Text(
                    tr("اتصل بالكمبيوتر لإرسال الرسائل واستقبالها.", "Connect to your computer to send and receive messages."),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (messages.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.widthIn(max = 320.dp).padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(
                                Icons.Default.ChatBubbleOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(18.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Text(tr("ابدأ المحادثة", "Start a conversation"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            tr("رسائلك مع الكمبيوتر هتظهر هنا. المحادثة محلية بين الجهازين.", "Messages with your computer will appear here. This chat stays between your devices."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
                    state = listState,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (message.fromPhone) Arrangement.End else Arrangement.Start
                        ) {
                            Card(
                                modifier = Modifier.widthIn(max = bubbleMaxWidth),
                                shape = RoundedCornerShape(
                                    topStart = 18.dp,
                                    topEnd = 18.dp,
                                    bottomStart = if (message.fromPhone) 18.dp else 5.dp,
                                    bottomEnd = if (message.fromPhone) 5.dp else 18.dp
                                ),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (message.fromPhone) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Text(
                                        if (message.fromPhone) tr("أنت", "You") else tr("الكمبيوتر", "Computer"),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.height(3.dp))
                                    Text(message.text, style = MaterialTheme.typography.bodyLarge)
                                    if (message.status.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            localizedMessageStatus(message.status),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MessageDraftField(
                    draft = draft,
                    onDraftChange = onDraftChange,
                    onSend = onSend,
                    enabled = isConnected,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = onSend, enabled = isConnected && draft.isNotBlank()) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("إرسال", "Send"))
                    if (!isCompactWidth) {
                        Spacer(Modifier.width(6.dp))
                        Text(tr("إرسال", "Send"))
                    }
                }
            }
        }
    }
}

@Composable
private fun localizedMessageStatus(status: String): String = when (status) {
    "قيد الإرسال" -> tr("قيد الإرسال", "Sending")
    "وصل للكمبيوتر" -> tr("وصل للكمبيوتر", "Delivered to computer")
    "تم الاستلام" -> tr("تم الاستلام", "Received")
    "فشل الإرسال" -> tr("فشل الإرسال", "Failed to send")
    else -> status
}

@Composable
private fun MessageDraftField(
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    enabled: Boolean,
    modifier: Modifier
) {
    OutlinedTextField(
        value = draft,
        onValueChange = onDraftChange,
        modifier = modifier,
        enabled = enabled,
        label = { Text(tr("اكتب رسالة", "Write a message")) },
        maxLines = 4,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { if (draft.isNotBlank() && enabled) onSend() })
    )
}
