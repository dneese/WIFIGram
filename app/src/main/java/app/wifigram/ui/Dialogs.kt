package app.wifigram.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The name of the phone, the other phones see it in the station. */
@Composable
fun NameDialog(initial: String, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("Ваше ім'я") },
        text = {
            Column {
                Text("Це ім'я побачать інші телефони в мережі.")
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Ім'я") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name.trim().ifEmpty { "Телефон" }) },
                enabled = name.isNotBlank()
            ) {
                Text("Готово")
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
        title = { Text("Режим зв'язку") },
        text = {
            Column {
                Text("Мережа: усі телефони в одній мережі WiFi.")
                Text("WiFi Direct: два телефони напряму, без роутера.")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    FilterChip(
                        selected = !isDirect,
                        onClick = { isDirect = false },
                        label = { Text("Мережа") },
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
                            label = { Text("Створити") },
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        FilterChip(
                            selected = !isHost,
                            onClick = { isHost = false },
                            label = { Text("Приєднатися") }
                        )
                    }
                    Text(
                        text = "На одному телефоні натисніть «Створити», на іншому «Приєднатися».",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(isDirect, isHost) }) {
                Text("Готово")
            }
        }
    )
}
