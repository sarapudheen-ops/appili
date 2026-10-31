package com.innova.diag.ui

import com.innova.diag.core.transport.TransportState
import com.innova.diag.platform.bluetooth.BluetoothPermissionState
import com.innova.diag.platform.bluetooth.PairedDevice

/** Everything the adapter screen renders. Immutable, so previews and tests can fabricate it. */
data class BluetoothShellUiState(
    val permissions: BluetoothPermissionState = BluetoothPermissionState.NONE,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val pairedDevicesError: String? = null,
    val selectedDevice: PairedDevice? = null,
    val transportState: TransportState = TransportState.DISCONNECTED,
) {
    /** Connect is offered only for an explicit selection, with permission, and no live link. */
    val canConnect: Boolean
        get() = selectedDevice != null &&
            permissions.canUseAdapter &&
            transportState != TransportState.CONNECTED &&
            transportState != TransportState.CONNECTING

    val canDisconnect: Boolean
        get() = transportState == TransportState.CONNECTED || transportState == TransportState.CONNECTING
}
