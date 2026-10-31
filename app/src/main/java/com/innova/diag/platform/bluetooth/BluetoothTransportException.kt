package com.innova.diag.platform.bluetooth

/**
 * Refusals raised by [BluetoothClassicTransport] before or during a connection attempt.
 *
 * Sealed so callers must handle every reason explicitly instead of string-matching a message. Each
 * instance is also published as [com.innova.diag.core.transport.TransportState.ERROR] and thrown
 * from `connect()`, so a caller that ignores the return value cannot miss the refusal.
 */
sealed class BluetoothTransportException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** No Bluetooth adapter on this device (or the service was unavailable). */
    data object AdapterUnavailable : BluetoothTransportException(
        "This device has no Bluetooth adapter.",
    )

    /** Adapter present but switched off. */
    data object Disabled : BluetoothTransportException(
        "Bluetooth is turned off. Enable it before connecting.",
    )

    /** `connect()` was called before the user explicitly picked a bonded adapter. */
    data object NoDeviceSelected : BluetoothTransportException(
        "No adapter selected. Choose your paired adapter first.",
    )

    /** Android 12+ runtime permissions are missing, so no socket may be opened. */
    class PermissionDenied(
        val missing: Set<BluetoothPermission>,
    ) : BluetoothTransportException(
        "Missing Bluetooth runtime permissions: " +
            missing.joinToString { it.androidPermission },
    )
}
