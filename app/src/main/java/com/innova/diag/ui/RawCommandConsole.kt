package com.innova.diag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Raw `AT…` console — **debug builds only**.
 *
 * The caller is responsible for the `BuildConfig.DEBUG` gate; in a release build this composable is
 * not part of the tree, so the text field does not exist to be typed into. Even in debug, only
 * adapter commands pass [AtCommandGuard]: vehicle modes are read-only and are not reachable from a
 * free-text field.
 */
@Composable
fun RawCommandConsole(
    result: ReadingView?,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var command by remember { mutableStateOf("") }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "RAW AT CONSOLE · DEBUG ONLY",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Adapter commands only (ATI, ATDP, ATRV…). Vehicle modes are refused: this " +
                    "app is read-only toward the vehicle, in every build type.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = command,
                    onValueChange = { typed -> command = typed },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    label = { Text("AT command") },
                )
                Button(
                    onClick = {
                        onSend(command)
                        command = ""
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text("Send")
                }
            }
            if (result != null) {
                ReadingText(view = result)
            }
        }
    }
}
