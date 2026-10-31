package com.innova.diag.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.innova.diag.BuildConfig
import com.innova.diag.core.transport.TransportState
import com.innova.diag.platform.bluetooth.BluetoothPermissionState
import com.innova.diag.platform.bluetooth.PairedDevice

/**
 * Connection screen: the link, the devices, and what the adapter claims to be.
 *
 * The three blocks answer three different questions and are never merged:
 * the **link** (is there a socket?), the **session** (did the adapter answer its configuration
 * sequence?) and the **adapter** (what does `ATI`/`ATDP` say?). A socket alone is not permission to
 * ask a vehicle anything, so Scan and Live data stay disabled until the session is ready.
 */
@Composable
fun ConnectionScreen(
    state: DiagnosticsUiState,
    onRequestPermissions: () -> Unit,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (PairedDevice) -> Unit,
    onConnect: () -> Unit,
    onConnectDemo: () -> Unit,
    onDisconnect: () -> Unit,
    onSendRawCommand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = "Adapter", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Bluetooth Classic (RFCOMM) to a bonded adapter. Connecting opens a " +
                        "socket; the adapter is then configured with ATZ, ATE0, ATL0, ATS0 and ATSP0 " +
                        "and asked who it is (ATI, ATDP). No headers are set and nothing is written " +
                        "to the vehicle.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { TransportStateCard(state.transportState) }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    state.initializing -> Text(
                        text = "Configuring adapter…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    state.sessionReady -> Text(
                        text = "Session ready — adapter configured. Scan and live data are unlocked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    else -> Text(
                        text = "No diagnostic session. A socket alone does not allow vehicle requests.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (!state.permissions.canUseAdapter) {
            item { PermissionCard(onRequestPermissions = onRequestPermissions) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "PAIRED DEVICES",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRefreshDevices) { Text("Refresh") }
            }
        }

        val listError = state.pairedDevicesError
        if (listError != null) {
            item {
                Text(
                    text = listError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (state.pairedDevices.isEmpty()) {
            item {
                Text(
                    text = "No paired devices. Pair your adapter in Android Bluetooth settings, " +
                        "then refresh.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(items = state.pairedDevices, key = { device -> device.address }) { device ->
            PairedDeviceRow(
                device = device,
                selected = device.address == state.selectedDevice?.address,
                onSelect = { onSelectDevice(device) },
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConnect, enabled = state.canConnect) { Text("Connect") }
                OutlinedButton(onClick = onDisconnect, enabled = state.canDisconnect) {
                    Text("Disconnect")
                }
            }
        }

        if (state.demoAdapterAvailable) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(onClick = onConnectDemo, enabled = !state.connected) {
                        Text("Use scripted adapter (debug)")
                    }
                    Text(
                        text = "Debug builds only: replays a fixed transcript (including a NO DATA " +
                            "coolant reading) so the screens can be checked without a vehicle.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { AdapterReportCard(state = state) }

        // Belt and braces: the state flag already comes from BuildConfig.DEBUG, and this second
        // check means a release build cannot render the field even if the state were fabricated.
        if (state.rawConsoleAvailable && BuildConfig.DEBUG) {
            item {
                RawCommandConsole(
                    result = state.rawConsoleResult,
                    onSend = onSendRawCommand,
                )
            }
        }
    }
}

/** What the adapter says about itself, kept visually separate from anything vehicle-derived. */
@Composable
private fun AdapterReportCard(state: DiagnosticsUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "ADAPTER REPORT",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Identity comes from ATI. The protocol is what ATDP reports as auto-detected: " +
                    "the adapter's claim, not a verified vehicle-matrix entry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ReadingRow(label = "Identity (ATI)", view = state.adapterIdentity ?: ReadingView.NotReadYet)
            ReadingRow(label = "Protocol (ATDP)", view = state.adapterProtocol ?: ReadingView.NotReadYet)
        }
    }
}

@Composable
private fun PairedDeviceRow(
    device: PairedDevice,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name ?: "Unnamed device",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = device.address,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionCard(onRequestPermissions: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Bluetooth permission required",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Text(
                text = "Android 12+ needs the Nearby devices permissions to list paired adapters " +
                    "and open an RFCOMM socket.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Button(onClick = onRequestPermissions) { Text("Grant permissions") }
        }
    }
}

private val PreviewDevices = listOf(
    PairedDevice(name = "Vgate vLinker MC+", address = "AA:BB:CC:DD:EE:01"),
    PairedDevice(name = "Car audio", address = "AA:BB:CC:DD:EE:02"),
)

@Preview(showBackground = true, name = "Connection · paired, nothing selected")
@Composable
private fun ConnectionIdlePreview() {
    AppTheme {
        ConnectionScreen(
            state = DiagnosticsUiState(
                permissions = BluetoothPermissionState.GRANTED,
                pairedDevices = PreviewDevices,
            ),
            onRequestPermissions = {},
            onRefreshDevices = {},
            onSelectDevice = {},
            onConnect = {},
            onConnectDemo = {},
            onDisconnect = {},
            onSendRawCommand = {},
        )
    }
}

@Preview(showBackground = true, name = "Connection · session ready")
@Composable
private fun ConnectionReadyPreview() {
    AppTheme {
        ConnectionScreen(
            state = DiagnosticsUiState(
                permissions = BluetoothPermissionState.GRANTED,
                pairedDevices = PreviewDevices,
                selectedDevice = PreviewDevices.first(),
                transportState = TransportState.CONNECTED,
                sessionReady = true,
                adapterIdentity = ReadingView.Measured("ELM327 v1.5", unit = "", rawHex = "ELM327 v1.5"),
                adapterProtocol = ReadingView.Measured(
                    "AUTO, ISO 15765-4 (CAN 11/500)",
                    unit = "",
                    rawHex = "AUTO, ISO 15765-4 (CAN 11/500)",
                ),
            ),
            onRequestPermissions = {},
            onRefreshDevices = {},
            onSelectDevice = {},
            onConnect = {},
            onConnectDemo = {},
            onDisconnect = {},
            onSendRawCommand = {},
        )
    }
}
