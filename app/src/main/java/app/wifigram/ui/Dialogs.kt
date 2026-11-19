package app.wifigram.ui

import android.content.Intent
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
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val echoReady = remember { AcousticEchoCanceler.isAvailable() }
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
            TextButton(onClick = {
                onMore()
            }) {
                Text(stringResource(R.string.tuning_more))
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
    onSave: (Settings) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initial) }
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ProfileStore.saveSettings(context, value)
                onSave(value)
                onDismiss()
            }) {
                Text(stringResource(R.string.tuning_save))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text(stringResource(R.string.tuning_cancel))
            }
        }
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
