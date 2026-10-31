package com.innova.diag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.GenericPids
import com.innova.diag.domain.obd.PidDefinition
import com.innova.diag.domain.obd.PidReading

/**
 * Live generic data: RPM, vehicle speed and coolant temperature, each with its unit.
 *
 * The three tiles are independent. One PID answering `NO DATA` shows `No Response` in amber and the
 * other two keep updating — a partial dashboard is honest, a dashboard that substitutes `0` for the
 * missing one is not.
 */
@Composable
fun LiveDataScreen(
    state: DiagnosticsUiState,
    onTogglePolling: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Live data", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Generic PIDs only (mode 01): engine speed, vehicle speed and coolant " +
                        "temperature. Requests are sequential and read-only; each value keeps its own " +
                        "status, so a missing reading never appears as 0.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (state.live.polling) {
                    OutlinedButton(onClick = onTogglePolling, enabled = state.canPollLiveData) {
                        Text("Stop polling")
                    }
                } else {
                    Button(onClick = onTogglePolling, enabled = state.canPollLiveData) {
                        Text("Start polling")
                    }
                }
            }
        }

        if (!state.sessionReady) {
            item {
                Text(
                    text = "Connect to an adapter first: live data needs a configured session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.sessionReady && state.live.readings.isEmpty()) {
            item {
                Text(
                    text = if (state.live.polling) {
                        "Waiting for the first cycle…"
                    } else {
                        "Not polling. Press start to read the generic PIDs."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(count = GenericPids.ALL.size) { index ->
            val definition = GenericPids.ALL[index]
            LiveValueTile(
                definition = definition,
                reading = state.live.readingFor(definition.pid),
            )
        }

        item {
            Text(
                text = "Values are shown exactly as decoded. A PID the vehicle does not support is " +
                    "reported as Not Supported and is not polled at all.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LiveValueTile(
    definition: PidDefinition,
    reading: PidReading?,
) {
    val view = if (reading == null) ReadingView.NotReadYet else Readings.pid(reading)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = definition.label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReadingText(
                view = view,
                style = MaterialTheme.typography.headlineMedium,
            )
        }
    }
}

@Preview(showBackground = true, name = "Live data · mixed results")
@Composable
private fun LiveDataPreview() {
    AppTheme {
        LiveDataScreen(
            state = DiagnosticsUiState(
                sessionReady = true,
                live = LiveState(
                    polling = true,
                    readings = listOf(
                        PidReading(GenericPids.COOLANT_TEMPERATURE, DiagnosticResult.NoResponse, requested = true),
                        PidReading(
                            GenericPids.ENGINE_RPM,
                            DiagnosticResult.Success(1672.0, "410C1A20"),
                            requested = true,
                        ),
                        PidReading(
                            GenericPids.VEHICLE_SPEED,
                            DiagnosticResult.Success(0.0, "410D00"),
                            requested = true,
                        ),
                    ),
                ),
            ),
            onTogglePolling = {},
        )
    }
}
