package app.wifigram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.wifigram.Intercom
import app.wifigram.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** The round icon with the first letter of the name. */
@Composable
fun Avatar(name: String, size: Int = 48, color: Color? = null, online: Boolean? = null) {
    val tint = color ?: MaterialTheme.colorScheme.primaryContainer
    val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(modifier = Modifier.size(size.dp)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(tint),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = letter,
                fontSize = (size / 2.4).sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        if (online != null) {
            val dot = (size / 4).coerceAtLeast(10)
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(dot.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding((dot / 6).coerceAtLeast(2).dp)
                        .clip(CircleShape)
                        .background(if (online) CallGreen else Color(0xFF9AA4B2))
                )
            }
        }
    }
}

/** The colored dot that shows the state of a phone. */
@Composable
fun StatusDot(status: String, online: Boolean) {
    val color = when {
        !online -> Color(0xFF9AA4B2)
        status == Intercom.STATUS_BUSY -> Color(0xFFE8A33D)
        status == Intercom.STATUS_CALLING -> Color(0xFF2E9E5B)
        else -> Color(0xFF2E9E5B)
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
    )
}

/** The state of a phone in a readable form. */
@Composable
fun statusText(peer: Intercom.Peer): String {
    if (!peer.online) return stringResource(R.string.status_lost)
    return when (peer.status) {
        Intercom.STATUS_BUSY -> stringResource(R.string.status_active)
        Intercom.STATUS_CALLING -> stringResource(R.string.status_calling)
        else -> stringResource(R.string.status_free)
    }
}

/** The words of the direct connection. */
@Composable
fun directStatusText(key: String): String = when (key) {
    "wifi-direct-group" -> stringResource(R.string.wifi_direct_group)
    "wifi-direct-group-ready" -> stringResource(R.string.wifi_direct_group_ready)
    "wifi-direct-search" -> stringResource(R.string.wifi_direct_search)
    "wifi-direct-connecting" -> stringResource(R.string.wifi_direct_connecting)
    "wifi-direct-failed" -> stringResource(R.string.wifi_direct_failed)
    "wifi-direct-unavailable" -> stringResource(R.string.wifi_direct_unavailable)
    "wifi-direct-channel-lost" -> stringResource(R.string.wifi_direct_channel_lost)
    else -> ""
}

// ── the days of the chat ──

/** The first moment of the day that the time belongs to. */
internal fun startOfDay(timestamp: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = timestamp
    calendar.set(Calendar.HOUR_OF_DAY, 0)
    calendar.set(Calendar.MINUTE, 0)
    calendar.set(Calendar.SECOND, 0)
    calendar.set(Calendar.MILLISECOND, 0)
    return calendar.timeInMillis
}

private fun dayBefore(start: Long): Long {
    val calendar = Calendar.getInstance()
    calendar.timeInMillis = start
    calendar.add(Calendar.DAY_OF_YEAR, -1)
    return calendar.timeInMillis
}

internal fun sameDay(first: Long, second: Long): Boolean =
    startOfDay(first) == startOfDay(second)

/** Сьогодні, вчора or the date, for the separator in the chat. */
@Composable
fun chatDayLabel(timestamp: Long): String {
    val today = startOfDay(System.currentTimeMillis())
    val day = startOfDay(timestamp)
    return when (day) {
        today -> stringResource(R.string.chat_today)
        dayBefore(today) -> stringResource(R.string.chat_yesterday)
        else -> SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(timestamp))
    }
}

/** The time in the list of the conversations: today the hour, older the day. */
@Composable
fun chatListTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val today = startOfDay(now)
    val day = startOfDay(timestamp)
    return when (day) {
        today -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
        dayBefore(today) -> stringResource(R.string.chat_yesterday)
        else -> {
            val nowYear = Calendar.getInstance().apply { timeInMillis = now }.get(Calendar.YEAR)
            val thenYear = Calendar.getInstance().apply { timeInMillis = timestamp }.get(Calendar.YEAR)
            val pattern = if (nowYear == thenYear) "dd.MM" else "dd.MM.yy"
            SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
        }
    }
}
