package com.innova.diag.platform.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the paired-device listing.
 *
 * The list is a *menu*, not a decision: this source can only ever report what Android already
 * considers bonded, and it exposes no notion of a selected device. Selection lives in the
 * transport, is set only from a UI tap, and is covered by
 * `BluetoothClassicTransportTest.connect is refused when no adapter was selected`.
 */
class BluetoothBondedDeviceSourceTest {

    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val permissions = FakeBluetoothPermissionStateProvider()

    @Test
    fun `lists paired devices sorted by name, preserving identity`() {
        val vgate = pairedDevice(name = "Vgate vLinker MC+", address = "AA:BB:CC:DD:EE:01")
        val headUnit = pairedDevice(name = "Car audio", address = "AA:BB:CC:DD:EE:02")
        every { adapter.isEnabled } returns true
        every { adapter.bondedDevices } returns linkedSetOf(vgate, headUnit)

        val result = newSource().bondedDevices()

        assertTrue(result is BondedDevicesResult.Available)
        assertEquals(
            listOf(
                PairedDevice(name = "Car audio", address = "AA:BB:CC:DD:EE:02"),
                PairedDevice(name = "Vgate vLinker MC+", address = "AA:BB:CC:DD:EE:01"),
            ),
            (result as BondedDevicesResult.Available).devices,
        )
    }

    @Test
    fun `an unnamed paired device is still listable by address`() {
        every { adapter.isEnabled } returns true
        every { adapter.bondedDevices } returns setOf(
            pairedDevice(name = null, address = "AA:BB:CC:DD:EE:03"),
        )

        val result = newSource().bondedDevices()

        assertEquals(
            listOf(PairedDevice(name = null, address = "AA:BB:CC:DD:EE:03")),
            (result as BondedDevicesResult.Available).devices,
        )
    }

    @Test
    fun `no paired devices is an empty list, not an error`() {
        every { adapter.isEnabled } returns true
        every { adapter.bondedDevices } returns emptySet()

        val result = newSource().bondedDevices()

        assertEquals(
            emptyList<PairedDevice>(),
            (result as BondedDevicesResult.Available).devices,
        )
    }

    @Test
    fun `without BLUETOOTH_CONNECT the list is unavailable and the adapter is untouched`() {
        permissions.current = BluetoothPermissionState(connectGranted = false, scanGranted = true)

        val result = newSource().bondedDevices()

        assertTrue(result is BondedDevicesResult.Unavailable)
        verify(exactly = 0) { adapter.bondedDevices }
        verify(exactly = 0) { adapter.isEnabled }
    }

    @Test
    fun `bluetooth off is reported as unavailable rather than an empty list`() {
        every { adapter.isEnabled } returns false

        val result = newSource().bondedDevices()

        assertTrue(result is BondedDevicesResult.Unavailable)
        verify(exactly = 0) { adapter.bondedDevices }
    }

    @Test
    fun `a device without a bluetooth adapter is reported as unavailable`() {
        val result = BluetoothBondedDeviceSource(
            adapter = null,
            permissionState = permissions,
        ).bondedDevices()

        assertTrue(result is BondedDevicesResult.Unavailable)
    }

    private fun newSource() = BluetoothBondedDeviceSource(adapter, permissions)

    private fun pairedDevice(name: String?, address: String): BluetoothDevice =
        mockk<BluetoothDevice>(relaxed = true).also { device ->
            every { device.name } returns name
            every { device.address } returns address
        }
}
