package com.innova.diag.platform.bluetooth

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** MAC address used by the tests; arbitrary and non-routable in spirit. */
internal const val TEST_DEVICE_ADDRESS = "AA:BB:CC:DD:EE:FF"

/**
 * Permission provider double.
 *
 * Tests mutate [current] to simulate a dialog result, which is how the real provider changes too.
 */
internal class FakeBluetoothPermissionStateProvider(
    initial: BluetoothPermissionState = BluetoothPermissionState.GRANTED,
) : BluetoothPermissionStateProvider {

    private val stateFlow = MutableStateFlow(initial)

    var current: BluetoothPermissionState
        get() = stateFlow.value
        set(value) {
            stateFlow.value = value
        }

    override fun state(): StateFlow<BluetoothPermissionState> = stateFlow

    override fun refresh() = Unit
}
