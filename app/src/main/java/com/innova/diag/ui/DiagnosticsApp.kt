package com.innova.diag.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.GenericPids
import com.innova.diag.domain.obd.PidReading
import com.innova.diag.domain.session.SessionExportFormat
import com.innova.diag.platform.bluetooth.BluetoothPermissionState
import com.innova.diag.platform.bluetooth.PairedDevice

/**
 * Root of the app: three screens, one session.
 *
 * The screens are peers rather than a wizard, because a technician moves between them freely: fix the
 * link, scan, then watch live values. Nothing is hidden behind a step that pretends to be complete.
 *
 * The banner at the top is the app's standing claim: **read-only, generic OBD-II**. It is stated once,
 * in the chrome, rather than repeated on every card.
 */
@Composable
fun DiagnosticsApp(
    state: DiagnosticsUiState,
    onRequestPermissions: () -> Unit,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (PairedDevice) -> Unit,
    onConnect: () -> Unit,
    onConnectDemo: () -> Unit,
    onDisconnect: () -> Unit,
    onScan: () -> Unit,
    onToggleLiveData: () -> Unit,
    onSendRawCommand: (String) -> Unit,
    onExport: (SessionExportFormat) -> Unit,
    onSelectScreen: (DiagnosticsScreen) -> Unit,
    onDismissMessage: () -> Unit,
    brandPicker: BrandPickerState,
    onBrandPickerEvent: (BrandPickerEvent) -> Unit,
    activeTest: ActiveTestUiState,
    onActiveTestHoldStart: () -> Unit,
    onActiveTestHoldEnd: () -> Unit,
    onActiveTestDropLink: () -> Unit,
    onActiveTestReconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = "appili", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Read-only diagnostics · generic OBD-II only · nothing is written to the vehicle",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            ScreenTabs(selected = state.screen, onSelectScreen = onSelectScreen)

            val message = state.message
            if (message != null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onDismissMessage) { Text("Dismiss") }
                    }
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                when (state.screen) {
                    DiagnosticsScreen.BRANDS -> BrandPickerScreen(
                        state = brandPicker,
                        onEvent = onBrandPickerEvent,
                    )

                    DiagnosticsScreen.CONNECTION -> ConnectionScreen(
                        state = state,
                        onRequestPermissions = onRequestPermissions,
                        onRefreshDevices = onRefreshDevices,
                        onSelectDevice = onSelectDevice,
                        onConnect = onConnect,
                        onConnectDemo = onConnectDemo,
                        onDisconnect = onDisconnect,
                        onSendRawCommand = onSendRawCommand,
                    )

                    DiagnosticsScreen.SCAN -> ScanScreen(
                        state = state,
                        onScan = onScan,
                        onExport = onExport,
                    )

                    DiagnosticsScreen.LIVE_DATA -> LiveDataScreen(
                        state = state,
                        onTogglePolling = onToggleLiveData,
                    )

                    DiagnosticsScreen.SIMULATED_TEST -> SimulatedActuatorTestScreen(
                        state = activeTest,
                        onHoldStart = onActiveTestHoldStart,
                        onHoldEnd = onActiveTestHoldEnd,
                        onDropLink = onActiveTestDropLink,
                        onReconnect = onActiveTestReconnect,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenTabs(
    selected: DiagnosticsScreen,
    onSelectScreen: (DiagnosticsScreen) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (screen in DiagnosticsScreen.entries) {
            val isSelected = screen == selected
            TextButton(
                onClick = { onSelectScreen(screen) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = screen.tabLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

private val PreviewDevices = listOf(
    PairedDevice(name = "Vgate vLinker MC+", address = "AA:BB:CC:DD:EE:01"),
)

@Preview(showBackground = true, name = "App shell · scan screen")
@Composable
private fun DiagnosticsAppPreview() {
    AppTheme {
        DiagnosticsApp(
            state = DiagnosticsUiState(
                screen = DiagnosticsScreen.SCAN,
                permissions = BluetoothPermissionState.GRANTED,
                pairedDevices = PreviewDevices,
                selectedDevice = PreviewDevices.first(),
                transportState = TransportState.CONNECTED,
                sessionReady = true,
                adapterIdentity = ReadingView.Measured("ELM327 v1.5", unit = "", rawHex = "ELM327 v1.5"),
                adapterProtocol = ReadingView.Measured(
                    "AUTO, ISO 15765-4 (CAN 11/500)",
                    unit = "",
                    rawHex = null,
                ),
                live = LiveState(
                    polling = false,
                    readings = listOf(
                        PidReading(GenericPids.ENGINE_RPM, DiagnosticResult.Success(1672.0, "410C1A20"), true),
                    ),
                ),
                sessionEntryCount = 9,
            ),
            onRequestPermissions = {},
            onRefreshDevices = {},
            onSelectDevice = {},
            onConnect = {},
            onConnectDemo = {},
            onDisconnect = {},
            onScan = {},
            onToggleLiveData = {},
            onSendRawCommand = {},
            onExport = {},
            onSelectScreen = {},
            onDismissMessage = {},
            brandPicker = BrandPickerState(),
            onBrandPickerEvent = {},
            activeTest = ActiveTestUiState(transportState = TransportState.CONNECTED),
            onActiveTestHoldStart = {},
            onActiveTestHoldEnd = {},
            onActiveTestDropLink = {},
            onActiveTestReconnect = {},
        )
    }
}
