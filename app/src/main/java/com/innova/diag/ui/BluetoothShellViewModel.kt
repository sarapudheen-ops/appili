package com.innova.diag.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.innova.diag.platform.bluetooth.AndroidBluetoothPermissionStateProvider
import com.innova.diag.platform.bluetooth.BluetoothBondedDeviceSource
import com.innova.diag.platform.bluetooth.BluetoothClassicTransport
import com.innova.diag.platform.bluetooth.BondedDevicesResult
import com.innova.diag.platform.bluetooth.PairedDevice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Wires the adapter screen to the real transport.
 *
 * Selection is a verb here, not an inference: [selectDevice] runs only from a user tap on a listed
 * paired device. There is no initial selection, no "first device" default, and no name matching.
 */
class BluetoothShellViewModel(application: Application) : AndroidViewModel(application) {

    private val permissionState = AndroidBluetoothPermissionStateProvider(application)

    private val adapter: BluetoothAdapter? =
        application.getSystemService(BluetoothManager::class.java)?.adapter

    private val bondedSource = BluetoothBondedDeviceSource(adapter, permissionState)

    private val transport = BluetoothClassicTransport(
        adapter = adapter,
        permissionState = permissionState,
    )

    private val bondedDevices = MutableStateFlow(BondedDevices(devices = emptyList(), error = null))
    private val selectedDevice = MutableStateFlow<PairedDevice?>(null)

    val uiState: StateFlow<BluetoothShellUiState> = combine(
        permissionState.state(),
        bondedDevices,
        selectedDevice,
        transport.state(),
    ) { permissions, bonded, selected, transportState ->
        BluetoothShellUiState(
            permissions = permissions,
            pairedDevices = bonded.devices,
            pairedDevicesError = bonded.error,
            selectedDevice = selected,
            transportState = transportState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = BluetoothShellUiState(),
    )

    init {
        onPermissionsChanged()
    }

    /** Call after a permission dialog resolves or when returning to the screen. */
    fun onPermissionsChanged() {
        permissionState.refresh()
        refreshPairedDevices()
    }

    fun refreshPairedDevices() {
        bondedDevices.value = when (val result = bondedSource.bondedDevices()) {
            is BondedDevicesResult.Available -> BondedDevices(devices = result.devices, error = null)
            is BondedDevicesResult.Unavailable -> BondedDevices(devices = emptyList(), error = result.reason)
        }
    }

    /** Explicit selection. The transport is told the address, never a name. */
    fun selectDevice(device: PairedDevice) {
        selectedDevice.value = device
        transport.selectDevice(device.address)
    }

    fun connect() {
        viewModelScope.launch {
            try {
                transport.connect()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                // Published as TransportState.ERROR already; the screen reads it from there.
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { transport.disconnect() }
    }

    private data class BondedDevices(
        val devices: List<PairedDevice>,
        val error: String?,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
