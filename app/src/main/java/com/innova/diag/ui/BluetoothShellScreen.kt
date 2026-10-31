package com.innova.diag.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Surface
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
import com.innova.diag.core.transport.TransportState
import com.innova.diag.platform.bluetooth.BluetoothPermissionState
import com.innova.diag.platform.bluetooth.PairedDevice

/**
 * Adapter screen: paired devices only, explicit selection, explicit connect.
 *
 * Design constraints this screen encodes:
 * - **Bonded devices only.** The list comes from the system's paired set; there is no scan button,
 *   because discovery is not in scope and would need `BLUETOOTH_SCAN` for nothing.
 * - **No default selection.** Nothing is selected until the user taps a row. The app never picks an
 *   adapter by name — a wrong pick would open an RFCOMM socket to an unknown device.
 * - **Honest copy.** Connecting opens a socket; it does not start a diagnostic session, and the
 *   screen says so rather than implying the vehicle is readable.
 */
@Composable
fun BluetoothShellScreen(
    state: BluetoothShellUiState,
    onRequestPermissions: () -> Unit,
    onRefreshDevices: () -> Unit,
    onSelectDevice: (PairedDevice) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = "Adapter", style = MaterialTheme.typography.headlineSmall)
            Text(
                text = "Bluetooth Classic (RFCOMM). Read-only socket: no AT/ELM commands are sent " +
                    "on connect, and no vehicle data is read until the vehicle matrix is verified.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TransportStateCard(state.transportState)

            if (!state.permissions.canUseAdapter) {
                PermissionCard(onRequestPermissions = onRequestPermissions)
            }

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

            PairedDeviceList(
                state = state,
                onSelectDevice = onSelectDevice,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onConnect, enabled = state.canConnect) { Text("Connect") }
                OutlinedButton(onClick = onDisconnect, enabled = state.canDisconnect) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun PairedDeviceList(
    state: BluetoothShellUiState,
    onSelectDevice: (PairedDevice) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = state.pairedDevicesError
    when {
        error != null -> Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )

        state.pairedDevices.isEmpty() -> Text(
            text = "No paired devices. Pair your adapter in Android Bluetooth settings, then refresh.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )

        else -> LazyColumn(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(items = state.pairedDevices, key = { it.address }) { device ->
                PairedDeviceRow(
                    device = device,
                    selected = device.address == state.selectedDevice?.address,
                    onSelect = { onSelectDevice(device) },
                )
            }
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

@Preview(showBackground = true, name = "Paired, nothing selected")
@Composable
private fun BluetoothShellIdlePreview() {
    AppTheme {
        BluetoothShellScreen(
            state = BluetoothShellUiState(
                permissions = BluetoothPermissionState.GRANTED,
                pairedDevices = PreviewDevices,
            ),
            onRequestPermissions = {},
            onRefreshDevices = {},
            onSelectDevice = {},
            onConnect = {},
            onDisconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Permission missing")
@Composable
private fun BluetoothShellNoPermissionPreview() {
    AppTheme {
        BluetoothShellScreen(
            state = BluetoothShellUiState(permissions = BluetoothPermissionState.NONE),
            onRequestPermissions = {},
            onRefreshDevices = {},
            onSelectDevice = {},
            onConnect = {},
            onDisconnect = {},
        )
    }
}

@Preview(showBackground = true, name = "Selected and connected")
@Composable
private fun BluetoothShellConnectedPreview() {
    AppTheme {
        BluetoothShellScreen(
            state = BluetoothShellUiState(
                permissions = BluetoothPermissionState.GRANTED,
                pairedDevices = PreviewDevices,
                selectedDevice = PreviewDevices.first(),
                transportState = TransportState.CONNECTED,
            ),
            onRequestPermissions = {},
            onRefreshDevices = {},
            onSelectDevice = {},
            onConnect = {},
            onDisconnect = {},
        )
    }
}
