package app.wifigram.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifigram.Intercom
import app.wifigram.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/** The words of one sender that belong together. */
private const val GROUP_MS = 5 * 60 * 1000L

/** The conversation with one phone, as in Telegram. */
@Composable
fun ChatScreen(
    engine: Intercom,
    peerId: String,
    peerName: String,
    peer: Intercom.Peer?,
    onBack: () -> Unit,
    onPickFile: () -> Unit,
    onCall: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val messages by engine.messages.collectAsStateWithLifecycle()
    val list = messages[peerId] ?: emptyList()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val timeFormat = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val colors = chatColors()

    LaunchedEffect(list.size) {
        if (list.isNotEmpty()) listState.animateScrollToItem(list.size - 1)
    }

    // The conversation on the screen is read at once.
    LaunchedEffect(peerId, list.size) {
        engine.markRead(peerId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(start = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.chat_back),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                Avatar(name = peerName, size = 40, online = peer?.online)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = headerStatus(peer),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (peer?.online == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1
                    )
                }
                IconButton(onClick = onCall) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = stringResource(R.string.chat_call),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (list.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.Chat,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.chat_empty_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    itemsIndexed(list, key = { _, message -> message.id }) { index, message ->
                        val previous = list.getOrNull(index - 1)
                        val newDay = previous == null || !sameDay(previous.timestamp, message.timestamp)
                        val grouped = previous != null && !newDay &&
                            previous.mine == message.mine &&
                            message.timestamp - previous.timestamp in 0L..GROUP_MS
                        if (newDay) DayChip(message.timestamp)
                        MessageBubble(
                            message = message,
                            grouped = grouped,
                            timeFormat = timeFormat,
                            colors = colors,
                            onOpenFile = { path -> openFile(context, path) },
                            onCopy = { text ->
                                clipboard.setText(AnnotatedString(text))
                                Toast.makeText(context, R.string.chat_copied, Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }

                val showDown = listState.firstVisibleItemIndex < list.size - 4
                if (showDown) {
                    SmallFloatingActionButton(
                        onClick = {
                            scope.launch { listState.animateScrollToItem(list.size - 1) }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = stringResource(R.string.chat_scroll_down),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 1.dp,
            shadowElevation = 3.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                Surface(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = onPickFile) {
                            Icon(
                                Icons.Filled.AttachFile,
                                contentDescription = stringResource(R.string.chat_file_button),
                                modifier = Modifier.size(22.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            maxLines = 5,
                            decorationBox = { inner ->
                                Box {
                                    if (input.isEmpty()) {
                                        Text(
                                            text = stringResource(R.string.chat_input),
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    inner()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
                Spacer(modifier = Modifier.width(6.dp))
                FilledIconButton(
                    onClick = {
                        val text = input
                        input = ""
                        engine.sendText(peerId, text)
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(46.dp)
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = stringResource(R.string.chat_send),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/** The line under the name: online or the state of the call. */
@Composable
private fun headerStatus(peer: Intercom.Peer?): String {
    if (peer == null || !peer.online) return stringResource(R.string.status_lost)
    return when (peer.status) {
        Intercom.STATUS_BUSY -> stringResource(R.string.status_active)
        Intercom.STATUS_CALLING -> stringResource(R.string.status_calling)
        else -> stringResource(R.string.chat_online)
    }
}

/** The day between the messages. */
@Composable
private fun DayChip(timestamp: Long) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)
        ) {
            Text(
                text = chatDayLabel(timestamp),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One word of the conversation. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Intercom.ChatMessage,
    grouped: Boolean,
    timeFormat: SimpleDateFormat,
    colors: ChatColors,
    onOpenFile: (String) -> Unit,
    onCopy: (String) -> Unit
) {
    val bubble = if (message.mine) colors.mine else colors.theirs
    val textColor = if (message.mine) colors.mineText else colors.theirsText
    val shape = RoundedCornerShape(
        topStart = 16.dp,
        topEnd = 16.dp,
        bottomStart = if (message.mine || grouped) 16.dp else 4.dp,
        bottomEnd = if (!message.mine || grouped) 16.dp else 4.dp
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (grouped) 1.dp else 3.dp),
        horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(shape)
                .background(bubble)
                .combinedClickable(
                    onClick = { message.filePath?.let(onOpenFile) },
                    onLongClick = { if (message.text.isNotEmpty()) onCopy(message.text) }
                )
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            if (message.fileName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = message.fileName,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (message.fileSize > 0) {
                            Text(
                                text = fileSizeText(message.fileSize),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else if (message.text.isNotEmpty()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = message.text,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        color = textColor
                    )
                }
            }
            Row(
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = timeFormat.format(Date(message.timestamp)),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (message.mine) {
                    Spacer(modifier = Modifier.width(4.dp))
                    val icon = when {
                        message.queued -> Icons.Filled.Schedule
                        message.delivered -> Icons.Filled.DoneAll
                        else -> Icons.Filled.Done
                    }
                    val description = when {
                        message.queued -> R.string.chat_queued
                        message.delivered -> R.string.chat_delivered
                        else -> R.string.chat_sent
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(description),
                        modifier = Modifier.size(14.dp),
                        tint = if (message.delivered) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

/** Bytes in a short form for the file message. */
private fun fileSizeText(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", size / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0))
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
