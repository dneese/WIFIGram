package app.wifigram.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.wifigram.R
import app.wifigram.call.VoiceReport

/**
 * The window of the diagnosis: what the phone did with the voice, what it
 * heard and what it could not do.
 *
 * Everything here can be copied or sent away: the user may send the text to
 * the developer, so the trouble of one phone becomes the trouble of many.
 */
@Composable
fun VoiceDiagnosticsDialog(
    report: String,
    journal: String,
    onRefresh: () -> Unit,
    onClearJournal: () -> Unit,
    onSaveFile: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var journalCleared by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = { Text(stringResource(R.string.diagnostics_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_report),
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = report.ifBlank { stringResource(R.string.diagnostics_empty) },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )

                Text(
                    text = stringResource(R.string.diagnostics_journal),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    text = if (journalCleared) {
                        stringResource(R.string.diagnostics_empty)
                    } else {
                        journal.ifBlank { stringResource(R.string.diagnostics_empty) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                journalCleared = false
                onRefresh()
            }) {
                Text(stringResource(R.string.diagnostics_refresh))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(report))
                }) {
                    Text(stringResource(R.string.diagnostics_copy_report))
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(journal))
                }) {
                    Text(stringResource(R.string.diagnostics_copy_journal))
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(report + "\n" + journal))
                    shareText(context, report + "\n" + journal)
                }) {
                    Text(stringResource(R.string.diagnostics_share))
                }
                TextButton(onClick = {
                    onClearJournal()
                    journalCleared = true
                }) {
                    Text(stringResource(R.string.diagnostics_clear))
                }
                TextButton(onClick = {
                    onSaveFile(report + "\n" + journal)
                }) {
                    Text(stringResource(R.string.diagnostics_save))
                }
            }
        }
    )
}

/** Sends the text away: to the chat, to the mail, to anything. */
private fun shareText(context: android.content.Context, text: String) {
    try {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "WIFIGram voice report")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "WIFIGram"))
    } catch (_: Exception) {
    }
}
