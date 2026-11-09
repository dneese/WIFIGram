package app.wifigram.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifigram.Intercom
import app.wifigram.R
import kotlinx.coroutines.delay

/**
 * The screen of the call.
 *
 * The same screen serves for the incoming call, for the outgoing call and
 * for the running conversation, as in the usual phone app.
 */
@Composable
fun CallScreen(
    engine: Intercom,
    onAnswer: () -> Unit,
    onReject: () -> Unit,
    onCancel: () -> Unit,
    onEnd: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onAdd: (String) -> Unit
) {
    val call by engine.call.collectAsStateWithLifecycle()
    val waiting by engine.waiting.collectAsStateWithLifecycle()
    val current = call ?: return

    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(current.callId, current.phase) {
        seconds = 0
        while (true) {
            delay(1000)
            seconds++
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(72.dp))

            Text(
                text = when (current.phase) {
                    Intercom.Phase.DIALING -> stringResource(R.string.call_state_calling)
                    Intercom.Phase.INCOMING -> stringResource(R.string.call_state_incoming)
                    Intercom.Phase.WAITING -> stringResource(R.string.call_state_busy)
                    Intercom.Phase.ACTIVE -> stringResource(R.string.call_state_active)
                },
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(20.dp))

            Avatar(name = current.peerName, size = 108)

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = current.peerName,
                fontSize = 30.sp,
                fontWeight = FontWeight.Light,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = when (current.phase) {
                    Intercom.Phase.DIALING -> stringResource(R.string.call_calling)
                    Intercom.Phase.INCOMING -> stringResource(R.string.call_caller)
                    Intercom.Phase.WAITING -> stringResource(R.string.call_waiting_line)
                    Intercom.Phase.ACTIVE -> "%02d:%02d".format(seconds / 60, seconds % 60)
                },
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (current.signalLost) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.call_no_signal),
                    fontSize = 15.sp,
                    color = CallRed
                )
            }

            if (current.phase == Intercom.Phase.ACTIVE && current.participants.size > 1) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = stringResource(
                        R.string.call_in_conversation,
                        current.participants.joinToString(", ") { it.name }
                    ),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (waiting.isNotEmpty() && current.phase == Intercom.Phase.ACTIVE) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.call_waiting_list),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    waiting.forEach { participant ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = participant.name,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onAdd(participant.id) }) {
                                Text(stringResource(R.string.call_add))
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }

            when (current.phase) {
                Intercom.Phase.INCOMING -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        CallButton(
                            icon = Icons.Filled.CallEnd,
                            description = stringResource(R.string.call_reject),
                            color = CallRed,
                            onClick = onReject
                        )
                        CallButton(
                            icon = Icons.Filled.Call,
                            description = stringResource(R.string.call_answer),
                            color = CallGreen,
                            onClick = onAnswer
                        )
                    }
                }

                Intercom.Phase.DIALING, Intercom.Phase.WAITING -> {
                    CallButton(
                        icon = Icons.Filled.CallEnd,
                        description = stringResource(R.string.call_cancel),
                        color = CallRed,
                        onClick = onCancel
                    )
                }

                Intercom.Phase.ACTIVE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CallButton(
                            icon = if (current.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            description = stringResource(R.string.call_microphone),
                            color = if (current.muted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = onToggleMute
                        )
                        CallButton(
                            icon = if (current.speaker) Icons.Filled.VolumeUp else Icons.Filled.VolumeDown,
                            description = if (current.speaker) {
                                stringResource(R.string.call_speaker)
                            } else {
                                stringResource(R.string.call_earpiece)
                            },
                            color = if (current.speaker) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            onClick = onToggleSpeaker
                        )
                        CallButton(
                            icon = Icons.Filled.CallEnd,
                            description = stringResource(R.string.call_end),
                            color = CallRed,
                            onClick = onEnd
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(56.dp))
        }
    }
}

/** The round button of the call screen. */
@Composable
private fun CallButton(
    icon: ImageVector,
    description: String,
    color: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(68.dp)
                .clip(CircleShape)
                .background(color)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(30.dp)
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = description,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
