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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.DiagnosticTroubleCode
import com.innova.diag.domain.obd.DtcSystem
import com.innova.diag.domain.obd.GenericPids
import com.innova.diag.domain.obd.GenericScanReport
import com.innova.diag.domain.obd.PidReading
import com.innova.diag.domain.obd.SupportedBank
import com.innova.diag.domain.obd.SupportedPids
import com.innova.diag.domain.obd.SupportedPidsReport
import com.innova.diag.domain.obd.Vin
import com.innova.diag.domain.session.SessionExportFormat

/**
 * The generic scan screen.
 *
 * The heading is deliberately narrow: this reads **generic powertrain data only** — mode 01 PIDs,
 * mode 03/07 codes and the mode 09 VIN. Manufacturer-specific blocks, freeze frames and any clearing
 * action are not part of it, and the screen says so rather than over-promising coverage.
 *
 * Every value is drawn from a [ReadingView], so `Not Supported` and `No Response` have their own
 * words and colours and a missing reading can never appear as `0`.
 */
@Composable
fun ScanScreen(
    state: DiagnosticsUiState,
    onScan: () -> Unit,
    onExport: (SessionExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Generic Powertrain Scan (Emissions/OBD-II only)",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    text = "Reads mode 01 PIDs, stored (mode 03) and pending (mode 07) codes, and the " +
                        "VIN (mode 09). Manufacturer-specific data is not requested, and nothing is " +
                        "cleared, reset or written.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onScan, enabled = state.canScan) {
                    Text(if (state.scan is ScanState.Running) "Scanning…" else "Run generic scan")
                }
            }
        }

        if (!state.sessionReady) {
            item {
                Text(
                    text = "Connect to an adapter first: a scan needs a configured session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        when (val scan = state.scan) {
            ScanState.Idle -> item {
                Text(
                    text = "No scan yet in this session.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ScanState.Running -> item {
                Text(
                    text = "Reading the vehicle. Each step is reported as it finishes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            is ScanState.Failed -> item {
                Text(
                    text = "Scan failed: ${scan.reason}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            is ScanState.Done -> {
                item { SupportedPidsCard(report = scan.report) }
                item { ReadingsCard(readings = scan.report.readings) }
                item {
                    DtcCard(
                        title = "STORED CODES (MODE 03)",
                        subtitle = "Emission-related codes the ECU has stored.",
                        result = scan.report.storedDtcs,
                    )
                }
                item {
                    DtcCard(
                        title = "PENDING CODES (MODE 07)",
                        subtitle = "Codes detected in the current or last drive cycle.",
                        result = scan.report.pendingDtcs,
                    )
                }
                item { VinCard(result = scan.report.vin) }
            }
        }

        item { ExportCard(state = state, onExport = onExport) }
    }
}

@Composable
private fun SupportedPidsCard(report: GenericScanReport) {
    val supported = report.supported
    SectionCard(title = "SUPPORTED PIDS (MODE 01)") {
        if (supported.unanswered) {
            ReadingText(view = ReadingView.NoResponse)
            Text(
                text = "No bank answered, so support is unknown. That is not the same as " +
                    "“nothing is supported”.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "${supported.knownPids.size} PIDs reported as supported.",
                style = MaterialTheme.typography.bodyMedium,
            )
            for (pid in supported.knownPids.sorted()) {
                val definition = GenericPids.definitionFor(pid)
                Text(
                    text = if (definition == null) {
                        Readings.hexPid(pid)
                    } else {
                        "${Readings.hexPid(pid)} · ${definition.label}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        for (bank in supported.banks) {
            if (bank.result.dataOrNull == null) {
                ReadingRow(
                    label = "Bank ${Readings.hexPid(bank.basePid)}",
                    view = Readings.supportedBank(bank.result),
                )
            }
        }
    }
}

@Composable
private fun ReadingsCard(readings: List<PidReading>) {
    SectionCard(title = "GENERIC READINGS") {
        for (reading in readings) {
            ReadingRow(
                label = reading.definition.label,
                view = Readings.pid(reading),
            )
            Text(
                text = if (reading.requested) {
                    "requested this scan"
                } else {
                    "not requested: the supported-PID bitmask excludes it"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DtcCard(
    title: String,
    subtitle: String,
    result: DiagnosticResult<List<DiagnosticTroubleCode>>,
) {
    SectionCard(title = title) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val codes = result.dataOrNull
        if (codes != null && codes.isNotEmpty()) {
            for (code in codes) {
                Text(
                    text = code.code,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                )
            }
            val raw = result.rawText
            if (raw != null) {
                Text(
                    text = "raw: $raw",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            ReadingText(view = Readings.dtcs(result))
        }
    }
}

@Composable
private fun VinCard(result: DiagnosticResult<Vin>) {
    SectionCard(title = "VIN (MODE 09 PID 02)") {
        ReadingText(view = Readings.vin(result), style = MaterialTheme.typography.titleMedium)
        Text(
            text = "The VIN is redacted from exported logs.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ExportCard(
    state: DiagnosticsUiState,
    onExport: (SessionExportFormat) -> Unit,
) {
    SectionCard(title = "SESSION EXPORT") {
        Text(
            text = "Every command sent and every response received in this session, as JSON or CSV. " +
                "VIN and Bluetooth addresses are redacted; the log states that it is read-only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "${state.sessionEntryCount} command(s) logged",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = { onExport(SessionExportFormat.JSON) },
                enabled = state.canExport,
                modifier = Modifier.weight(1f),
            ) {
                Text("Export JSON")
            }
            OutlinedButton(
                onClick = { onExport(SessionExportFormat.CSV) },
                enabled = state.canExport,
                modifier = Modifier.weight(1f),
            ) {
                Text("Export CSV")
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}

private val PreviewReport = GenericScanReport(
    supported = SupportedPidsReport(
        banks = listOf(
            SupportedBank(
                basePid = 0x00,
                result = DiagnosticResult.Success(
                    data = SupportedPids(basePid = 0x00, mask = 0xBE1FA813L),
                    rawHex = "4100BE1FA813",
                ),
            ),
            SupportedBank(basePid = 0x20, result = DiagnosticResult.NoResponse),
        ),
    ),
    readings = listOf(
        PidReading(GenericPids.COOLANT_TEMPERATURE, DiagnosticResult.NoResponse, requested = true),
        PidReading(GenericPids.ENGINE_RPM, DiagnosticResult.Success(1672.0, "410C1A20"), requested = true),
        PidReading(
            GenericPids.VEHICLE_SPEED,
            DiagnosticResult.Success(0.0, "410D00"),
            requested = true,
        ),
    ),
    storedDtcs = DiagnosticResult.Success(
        data = listOf(DiagnosticTroubleCode(DtcSystem.POWERTRAIN, "0133")),
        rawHex = "430201330000",
    ),
    pendingDtcs = DiagnosticResult.Success(data = emptyList(), rawHex = "4700"),
    vin = DiagnosticResult.Success(
        data = Vin("1M8GDM9AXKP042788"),
        rawHex = "490201314D3847444D3941584B50303432373838",
    ),
)

@Preview(showBackground = true, name = "Scan · mixed results")
@Composable
private fun ScanDonePreview() {
    AppTheme {
        ScanScreen(
            state = DiagnosticsUiState(
                sessionReady = true,
                scan = ScanState.Done(PreviewReport),
                sessionEntryCount = 12,
            ),
            onScan = {},
            onExport = {},
        )
    }
}
