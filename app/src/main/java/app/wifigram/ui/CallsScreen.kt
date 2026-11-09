package app.wifigram.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallMade
import androidx.compose.material.icons.filled.CallMissed
import androidx.compose.material.icons.filled.CallReceived
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.wifigram.CallLogStore
import app.wifigram.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The journal of the calls, as in the usual phone app. */
@Composable
fun CallsScreen(
    entries: List<CallLogStore.Entry>,
    onClear: () -> Unit,
    onCall: (CallLogStore.Entry) -> Unit
) {
    val format = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

    if (entries.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.calls_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.calls_clear)) }
            }
        }
        itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(name = entry.name, size = 46)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (entry.missed) CallRed else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = when {
                                entry.missed -> Icons.Filled.CallMissed
                                entry.outgoing -> Icons.Filled.CallMade
                                else -> Icons.Filled.CallReceived
                            },
                            contentDescription = null,
                            tint = if (entry.missed) CallRed else CallGreen,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = format.format(Date(entry.timestamp)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (entry.dropped > 0 || entry.concealed > 0) {
                            Text(
                                text = " · " + stringResource(
                                    R.string.calls_quality,
                                    entry.dropped,
                                    entry.concealed
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { onCall(entry) },
                    enabled = entry.peerId != null
                ) {
                    Icon(
                        Icons.Filled.Call,
                        contentDescription = stringResource(R.string.calls_call_back),
                        tint = if (entry.peerId != null) {
                            CallGreen
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        }
                    )
                }
            }
            if (index < entries.size - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = 74.dp, end = 16.dp),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                )
            }
        }
    }
}
