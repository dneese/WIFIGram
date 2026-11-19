package app.wifigram.ui

import android.content.Intent
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.wifigram.Locales
import app.wifigram.ProfileStore
import app.wifigram.Settings
import app.wifigram.R
import app.wifigram.call.VoiceTrace
import app.wifigram.call.VoiceTuning

/** The name of the phone, the other phones see it in the station. */
@Composable
fun NameDialog(initial: String, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val fallback = stringResource(R.string.phone_fallback)
    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.name_title)) },
        text = {
            Column {
                Text(stringResource(R.string.name_hint))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name_label)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim().ifEmpty { fallback }) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.ready))
            }
        }
    )
}

/**
 * The mode of the connection: through a router or directly between the
 * phones.
 */
@Composable
fun ModeDialog(
    direct: Boolean,
    host: Boolean,
    onSave: (Boolean, Boolean) -> Unit
) {
    var isDirect by remember { mutableStateOf(direct) }
    var isHost by remember { mutableStateOf(host) }

    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.mode_title)) },
        text = {
            Column {
                Text(stringResource(R.string.mode_network))
                Text(stringResource(R.string.mode_direct))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    FilterChip(
                        selected = !isDirect,
                        onClick = { isDirect = false },
                        label = { Text(stringResource(R.string.mode_network_chip)) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    FilterChip(
                        selected = isDirect,
                        onClick = { isDirect = true },
                        label = { Text("WiFi Direct") }
                    )
                }
                if (isDirect) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        FilterChip(
                            selected = isHost,
                            onClick = { isHost = true },
                            label = { Text(stringResource(R.string.mode_create)) },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        FilterChip(
                            selected = !isHost,
                            onClick = { isHost = false },
                            label = { Text(stringResource(R.string.mode_join)) }
                        )
                    }
                    Text(
                        text = stringResource(R.string.mode_hint),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(isDirect, isHost) }) {
                Text(stringResource(R.string.ready))
            }
        }
    )
}

/**
 * The settings of the phone: the language and the story of the app with the
 * link to its source.
 */
@Composable
fun SettingsDialog(
    language: String,
    onSaveLanguage: (String) -> Unit,
    onMore: () -> Unit = {},
    onDiagnostics: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val echoReady = remember { AcousticEchoCanceler.isAvailable() }
    var trace by remember { mutableStateOf(VoiceTrace.read(context)) }
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    AlertDialog(
        onDismissRequest = { },
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.settings_language),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    FilterChip(
                        selected = language == Locales.UKRAINIAN,
                        onClick = { onSaveLanguage(Locales.UKRAINIAN) },
                        label = { Text(stringResource(R.string.language_uk)) },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    FilterChip(
                        selected = language == Locales.ENGLISH,
                        onClick = { onSaveLanguage(Locales.ENGLISH) },
                        label = { Text(stringResource(R.string.language_en)) }
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = if (echoReady) {
                        stringResource(R.string.settings_echo_ok)
                    } else {
                        stringResource(R.string.settings_echo_none)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(
                    text = stringResource(R.string.voice_journal_title),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = trace?.lineSequence()?.toList()?.takeLast(8)?.joinToString("\n")
                        ?: stringResource(R.string.voice_journal_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                if (trace != null) {
                    TextButton(onClick = {
                        VoiceTrace.clear(context)
                        trace = null
                    }) {
                        Text(stringResource(R.string.voice_journal_clear))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                Text(stringResource(R.string.about_title))
                Text(
                    text = stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(
                    onClick = {
                        try {
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/dneese/WIFIGram")
                                )
                            )
                        } catch (_: Exception) {
                        }
                    },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Text("github.com/dneese/WIFIGram")
                }
                if (version.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.version_label, version),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
            }) {
                Text(stringResource(R.string.ready))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    onDiagnostics()
                }) {
                    Text(stringResource(R.string.diagnostics_open))
                }
                TextButton(onClick = {
                    onMore()
                }) {
                    Text(stringResource(R.string.tuning_more))
                }
            }
        }
    )
}


/**
 * The fine tuning of the phone: the voice, the sound and the way the words
 * travel. Every slider and every switch is checked by the phone itself.
 */
@Composable
fun TuningDialog(
    initial: Settings,
    initialTuning: VoiceTuning,
    onSave: (Settings) -> Unit,
    onSaveTuning: (VoiceTuning) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
    var tuning by remember { mutableStateOf(initialTuning) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.tuning_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.tuning_voice),
                    style = MaterialTheme.typography.titleSmall
                )
                TuningSlider(
                    title = stringResource(R.string.tuning_volume),
                    value = value.voiceVolume.toFloat(),
                    range = 0f..100f,
                    steps = 19,
                    text = "${value.voiceVolume}%",
                    onChange = { value = value.copy(voiceVolume = it.toInt()) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.tuning_sound),
                    style = MaterialTheme.typography.titleSmall
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_ring),
                    hint = stringResource(R.string.tuning_ring_hint),
                    checked = value.incomingSound,
                    onChange = { value = value.copy(incomingSound = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_speaker),
                    hint = stringResource(R.string.tuning_speaker_hint),
                    checked = value.speakerDefault,
                    onChange = { value = value.copy(speakerDefault = it) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.tuning_voice_engine),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = stringResource(R.string.tuning_voice_engine_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                TuningSlider(
                    title = stringResource(R.string.tuning_sample_rate),
                    value = tuning.sampleRate.toFloat(),
                    range = 8000f..48000f,
                    steps = 19,
                    text = "${tuning.sampleRate} Hz",
                    onChange = { tuning = tuning.copy(sampleRate = it.toInt()) }
                )
                TuningSlider(
                    title = stringResource(R.string.tuning_bitrate),
                    value = tuning.maxBitrate.toFloat(),
                    range = 8f..128f,
                    steps = 29,
                    text = "${tuning.maxBitrate} kbps",
                    onChange = { tuning = tuning.copy(maxBitrate = it.toInt()) }
                )
                TuningSlider(
                    title = stringResource(R.string.tuning_jitter_packets),
                    value = tuning.jitterPackets.toFloat(),
                    range = 10f..100f,
                    steps = 17,
                    text = "${tuning.jitterPackets}",
                    onChange = { tuning = tuning.copy(jitterPackets = it.toInt()) }
                )

                Text(
                    text = stringResource(R.string.tuning_microphone_source),
                    style = MaterialTheme.typography.titleSmall
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TuningChip(
                        selected = tuning.audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                        label = stringResource(R.string.tuning_source_voice),
                        onClick = {
                            tuning = tuning.copy(
                                audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
                            )
                        }
                    )
                    TuningChip(
                        selected = tuning.audioSource == MediaRecorder.AudioSource.MIC,
                        label = stringResource(R.string.tuning_source_mic),
                        onClick = {
                            tuning = tuning.copy(audioSource = MediaRecorder.AudioSource.MIC)
                        }
                    )
                    TuningChip(
                        selected = tuning.audioSource == MediaRecorder.AudioSource.DEFAULT,
                        label = stringResource(R.string.tuning_source_default),
                        onClick = {
                            tuning = tuning.copy(audioSource = MediaRecorder.AudioSource.DEFAULT)
                        }
                    )
                }

                TuningSwitch(
                    title = stringResource(R.string.tuning_low_latency),
                    hint = stringResource(R.string.tuning_low_latency_hint),
                    checked = tuning.useLowLatency,
                    onChange = { tuning = tuning.copy(useLowLatency = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_hardware_ns),
                    hint = stringResource(R.string.tuning_hardware_ns_hint),
                    checked = tuning.hardwareNoiseSuppress,
                    onChange = { tuning = tuning.copy(hardwareNoiseSuppress = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_software_echo),
                    hint = stringResource(R.string.tuning_software_echo_hint),
                    checked = tuning.softwareEcho,
                    onChange = { tuning = tuning.copy(softwareEcho = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_software_noise),
                    hint = stringResource(R.string.tuning_software_noise_hint),
                    checked = tuning.softwareNoise,
                    onChange = { tuning = tuning.copy(softwareNoise = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_agc),
                    hint = stringResource(R.string.tuning_agc_hint),
                    checked = tuning.autoGain,
                    onChange = { tuning = tuning.copy(autoGain = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_highpass),
                    hint = stringResource(R.string.tuning_highpass_hint),
                    checked = tuning.highpass,
                    onChange = { tuning = tuning.copy(highpass = it) }
                )

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = stringResource(R.string.tuning_ice),
                    style = MaterialTheme.typography.titleSmall
                )

                Text(
                    text = stringResource(R.string.tuning_ice_transport),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TuningChip(
                        selected = tuning.iceTransport == VoiceTuning.ICE_ALL,
                        label = stringResource(R.string.tuning_ice_all),
                        onClick = { tuning = tuning.copy(iceTransport = VoiceTuning.ICE_ALL) }
                    )
                    TuningChip(
                        selected = tuning.iceTransport == VoiceTuning.ICE_RELAY,
                        label = stringResource(R.string.tuning_ice_relay),
                        onClick = { tuning = tuning.copy(iceTransport = VoiceTuning.ICE_RELAY) }
                    )
                }

                Text(
                    text = stringResource(R.string.tuning_tcp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TuningChip(
                        selected = tuning.tcpCandidate == VoiceTuning.TCP_ENABLED,
                        label = stringResource(R.string.tuning_tcp_on),
                        onClick = { tuning = tuning.copy(tcpCandidate = VoiceTuning.TCP_ENABLED) }
                    )
                    TuningChip(
                        selected = tuning.tcpCandidate == VoiceTuning.TCP_DISABLED,
                        label = stringResource(R.string.tuning_tcp_off),
                        onClick = { tuning = tuning.copy(tcpCandidate = VoiceTuning.TCP_DISABLED) }
                    )
                }

                Text(
                    text = stringResource(R.string.tuning_policy),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TuningChip(
                        selected = tuning.candidatePolicy == VoiceTuning.CAND_ALL,
                        label = stringResource(R.string.tuning_policy_all),
                        onClick = { tuning = tuning.copy(candidatePolicy = VoiceTuning.CAND_ALL) }
                    )
                    TuningChip(
                        selected = tuning.candidatePolicy == VoiceTuning.CAND_LOW_COST,
                        label = stringResource(R.string.tuning_policy_low),
                        onClick = {
                            tuning = tuning.copy(candidatePolicy = VoiceTuning.CAND_LOW_COST)
                        }
                    )
                }

                Text(
                    text = stringResource(R.string.tuning_bundle),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TuningChip(
                        selected = tuning.bundle == VoiceTuning.BUNDLE_MAX,
                        label = stringResource(R.string.tuning_bundle_max),
                        onClick = { tuning = tuning.copy(bundle = VoiceTuning.BUNDLE_MAX) }
                    )
                    TuningChip(
                        selected = tuning.bundle == VoiceTuning.BUNDLE_BALANCED,
                        label = stringResource(R.string.tuning_bundle_balanced),
                        onClick = { tuning = tuning.copy(bundle = VoiceTuning.BUNDLE_BALANCED) }
                    )
                    TuningChip(
                        selected = tuning.bundle == VoiceTuning.BUNDLE_COMPAT,
                        label = stringResource(R.string.tuning_bundle_compat),
                        onClick = { tuning = tuning.copy(bundle = VoiceTuning.BUNDLE_COMPAT) }
                    )
                }

                Text(
                    text = stringResource(R.string.tuning_sdp),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    TuningChip(
                        selected = tuning.sdpSemantics == VoiceTuning.SDP_UNIFIED,
                        label = stringResource(R.string.tuning_sdp_unified),
                        onClick = { tuning = tuning.copy(sdpSemantics = VoiceTuning.SDP_UNIFIED) }
                    )
                    TuningChip(
                        selected = tuning.sdpSemantics == VoiceTuning.SDP_PLAN_B,
                        label = stringResource(R.string.tuning_sdp_plan_b),
                        onClick = { tuning = tuning.copy(sdpSemantics = VoiceTuning.SDP_PLAN_B) }
                    )
                }

                TuningSwitch(
                    title = stringResource(R.string.tuning_gather_continually),
                    hint = stringResource(R.string.tuning_gather_continually_hint),
                    checked = tuning.gatherContinually,
                    onChange = { tuning = tuning.copy(gatherContinually = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_trickle),
                    hint = stringResource(R.string.tuning_trickle_hint),
                    checked = tuning.trickle,
                    onChange = { tuning = tuning.copy(trickle = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_rtcp_mux),
                    hint = stringResource(R.string.tuning_rtcp_mux_hint),
                    checked = tuning.rtcpMux,
                    onChange = { tuning = tuning.copy(rtcpMux = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_fast_accelerate),
                    hint = stringResource(R.string.tuning_fast_accelerate_hint),
                    checked = tuning.fastAccelerate,
                    onChange = { tuning = tuning.copy(fastAccelerate = it) }
                )
                TuningSwitch(
                    title = stringResource(R.string.tuning_ice_restart),
                    hint = stringResource(R.string.tuning_ice_restart_hint),
                    checked = tuning.iceRestartOnFail,
                    onChange = { tuning = tuning.copy(iceRestartOnFail = it) }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ProfileStore.saveSettings(context, value)
                ProfileStore.saveTuning(context, tuning)
                onSave(value)
                onSaveTuning(tuning)
                onDismiss()
            }) {
                Text(stringResource(R.string.tuning_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    tuning = VoiceTuning()
                }) {
                    Text(stringResource(R.string.tuning_reset))
                }
                TextButton(onClick = { onDismiss() }) {
                    Text(stringResource(R.string.tuning_cancel))
                }
            }
        }
    )
}

/** A small button with the name of the choice. */
@Composable
private fun TuningChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = Modifier.padding(end = 8.dp)
    )
}

/** A switch with its name and a short explanation. */
@Composable
private fun TuningSwitch(
    title: String,
    hint: String,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title)
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A slider with its name and the value it stands for now. */
@Composable
private fun TuningSlider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    text: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, modifier = Modifier.weight(1f))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps
        )
    }
}
