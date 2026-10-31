package com.innova.diag.platform.bluetooth

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Runtime permissions this app needs for Bluetooth Classic.
 *
 * Android 12 (API 31) replaced the install-time `BLUETOOTH` / `BLUETOOTH_ADMIN` pair with runtime
 * permissions. The manifest declares both the legacy pair (capped at API 30) and the modern pair,
 * so exactly one set applies per device.
 */
enum class BluetoothPermission(val androidPermission: String) {
    /** Required to list bonded devices and to open an RFCOMM socket. */
    CONNECT(Manifest.permission.BLUETOOTH_CONNECT),

    /**
     * Declared and modelled because Android groups the two permissions for "Nearby devices".
     * This phase performs **no discovery**, so [SCAN] is never required to connect; it is reported
     * for transparency and reserved for a future explicit-pairing phase.
     */
    SCAN(Manifest.permission.BLUETOOTH_SCAN),
}

/** Android 12+ permissions to request together, in declaration order. */
val BLUETOOTH_RUNTIME_PERMISSIONS: List<String> = BluetoothPermission.entries.map { it.androidPermission }

/**
 * Snapshot of Bluetooth permission state, as seen by the transport and the UI.
 *
 * A data class rather than a sealed hierarchy because the two permissions have genuinely different
 * consequences: [CONNECT] gates everything this phase does, [SCAN] gates nothing yet.
 */
data class BluetoothPermissionState(
    val connectGranted: Boolean,
    val scanGranted: Boolean,
) {
    val missing: Set<BluetoothPermission>
        get() = buildSet {
            if (!connectGranted) add(BluetoothPermission.CONNECT)
            if (!scanGranted) add(BluetoothPermission.SCAN)
        }

    /** Whether bonded devices may be read and an RFCOMM socket may be opened. */
    val canUseAdapter: Boolean get() = connectGranted

    companion object {
        val GRANTED = BluetoothPermissionState(connectGranted = true, scanGranted = true)
        val NONE = BluetoothPermissionState(connectGranted = false, scanGranted = false)
    }
}

/**
 * Injected permission source.
 *
 * The transport never asks Android for permissions itself and never assumes they are held: it reads
 * this state at connect time and fails closed when [BluetoothPermission.CONNECT] is missing. Keeping
 * it behind an interface is also what makes the permission gate testable without a device.
 */
interface BluetoothPermissionStateProvider {

    /** Current state, observable so the UI can react to a permission dialog result. */
    fun state(): StateFlow<BluetoothPermissionState>

    /** Re-reads platform permission state. Call after a permission dialog resolves or on resume. */
    fun refresh()
}

/**
 * Real provider backed by `checkSelfPermission`.
 *
 * Below API 31 the legacy permissions are granted at install time, so the state is always
 * [BluetoothPermissionState.GRANTED] there. The API check is the only Android-version branch: the
 * transport itself has none.
 */
class AndroidBluetoothPermissionStateProvider(
    context: Context,
) : BluetoothPermissionStateProvider {

    private val appContext = context.applicationContext
    private val current = MutableStateFlow(evaluate())

    override fun state(): StateFlow<BluetoothPermissionState> = current.asStateFlow()

    override fun refresh() {
        current.value = evaluate()
    }

    private fun evaluate(): BluetoothPermissionState {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return BluetoothPermissionState.GRANTED
        }
        fun isGranted(permission: BluetoothPermission): Boolean =
            ContextCompat.checkSelfPermission(appContext, permission.androidPermission) ==
                PackageManager.PERMISSION_GRANTED

        return BluetoothPermissionState(
            connectGranted = isGranted(BluetoothPermission.CONNECT),
            scanGranted = isGranted(BluetoothPermission.SCAN),
        )
    }
}
