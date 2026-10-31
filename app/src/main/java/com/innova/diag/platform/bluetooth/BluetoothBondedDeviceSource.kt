package com.innova.diag.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter

/**
 * A device already paired with the phone, as shown to the user for selection.
 *
 * Only identity fields — no transport, no socket, no state. [address] is the MAC address and is
 * used solely to resolve the device again inside the platform layer at connect time.
 */
data class PairedDevice(
    val name: String?,
    val address: String,
)

/** Result of reading the bonded-device list; a refusal is explicit, never an empty list. */
sealed interface BondedDevicesResult {
    data class Available(val devices: List<PairedDevice>) : BondedDevicesResult

    /** Carries a user-facing reason: no adapter, Bluetooth off, or permission withheld. */
    data class Unavailable(val reason: String) : BondedDevicesResult
}

/** Reads the system's bonded-device list. */
interface BondedDeviceSource {
    fun bondedDevices(): BondedDevicesResult
}

/**
 * Lists paired devices only — never scans, never discovers, never filters by name.
 *
 * Bonded devices are the only ones this app may talk to: pairing is the user's explicit consent,
 * and a discovery scan would also require `BLUETOOTH_SCAN` for no benefit in this phase.
 */
class BluetoothBondedDeviceSource(
    private val adapter: BluetoothAdapter?,
    private val permissionState: BluetoothPermissionStateProvider,
) : BondedDeviceSource {

    @SuppressLint("MissingPermission") // Guarded by canUseAdapter; lint cannot see the injected state.
    override fun bondedDevices(): BondedDevicesResult {
        val bluetoothAdapter = adapter
            ?: return BondedDevicesResult.Unavailable("This device has no Bluetooth adapter.")

        if (!permissionState.state().value.canUseAdapter) {
            return BondedDevicesResult.Unavailable(
                "Bluetooth permission is required to list paired devices.",
            )
        }
        if (!bluetoothAdapter.isEnabled) {
            return BondedDevicesResult.Unavailable(
                "Bluetooth is turned off. Enable it to see paired devices.",
            )
        }

        return try {
            val paired = bluetoothAdapter.bondedDevices.orEmpty()
                .map { device -> PairedDevice(name = device.name, address = device.address) }
                .sortedWith(compareBy({ it.name == null }, { it.name ?: it.address }))
            BondedDevicesResult.Available(paired)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the call: fail closed, do not retry blindly.
            BondedDevicesResult.Unavailable("Bluetooth permission was denied.")
        }
    }
}
