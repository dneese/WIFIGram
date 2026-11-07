package app.wifigram.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifigram.Intercom
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The conversation with one phone. */
@Composable
fun ChatScreen(
    engine: Intercom,
    peerId: String,
    peerName: String,
    onBack: () -> Unit,
    onPickFile: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages by engine.messages.collectAsStateWithLifecycle()
    val list = messages[peerId] ?: emptyList()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val format = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    LaunchedEffect(list.size) {
        if (list.isNotEmpty()) listState.animateScrollToItem(list.size - 1)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Avatar(name = peerName, size = 40)
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = peerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(list, key = { it.id }) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start
                ) {
                    Column(
                        modifier = Modifier
                            .width(280.dp)
                            .clickable(enabled = message.filePath != null) {
                                message.filePath?.let { openFile(context, it) }
                            }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (!message.mine) {
                            Text(
                                text = message.peerName,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (message.fileName != null) {
                            Text(
                                text = "Файл: ${message.fileName}",
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                        } else if (message.text.isNotEmpty()) {
                            Text(
                                text = message.text,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Text(
                            text = format.format(Date(message.timestamp)),
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPickFile) {
                Icon(
                    Icons.Filled.AttachFile,
                    contentDescription = "Файл",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("Повідомлення") },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )
            IconButton(
                onClick = {
                    val text = input
                    input = ""
                    engine.sendText(peerId, text)
                },
                enabled = input.isNotBlank()
            ) {
                Icon(
                    Icons.Filled.Send,
                    contentDescription = "Надіслати",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Opens a file that was received in the conversation. */
private fun openFile(context: android.content.Context, path: String) {
    try {
        val file = File(path)
        if (!file.exists()) return
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (_: Exception) {
    }
}
