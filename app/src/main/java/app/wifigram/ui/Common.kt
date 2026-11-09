package app.wifigram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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

/** The round icon with the first letter of the name. */
@Composable
fun Avatar(name: String, size: Int = 48, color: Color? = null) {
    val tint = color ?: MaterialTheme.colorScheme.primaryContainer
    val letter = name.trim().firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = Modifier
            .size(size.dp)
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
