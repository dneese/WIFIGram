package app.wifigram.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Badge
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wifigram.Intercom
import app.wifigram.R

/** The list of the conversations, one for every phone. */
@Composable
fun ChatsScreen(
    conversations: List<Intercom.Conversation>,
    peers: List<Intercom.Peer>,
    onOpen: (String) -> Unit
) {
    if (conversations.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.chats_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val online = peers.associateBy { it.id }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        itemsIndexed(conversations, key = { _, conversation -> conversation.peerId }) { index, conversation ->
            val last = conversation.last
            val unread = conversation.unread
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(conversation.peerId) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(
                    name = conversation.peerName,
                    size = 50,
                    online = online[conversation.peerId]?.online
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conversation.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (unread > 0) FontWeight.Bold else FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            last == null -> ""
                            last.fileName != null ->
                                stringResource(R.string.chat_file, last.fileName ?: "")
                            else -> last.text
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (unread > 0) FontWeight.Medium else FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.Center
                ) {
                    if (last != null) {
                        Text(
                            text = chatListTime(last.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (unread > 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (unread > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Badge(containerColor = MaterialTheme.colorScheme.primary) {
                            Text(if (unread > 99) "99+" else unread.toString())
                        }
                    }
                }
            }
            if (index < conversations.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 78.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                )
            }
        }
    }
}
