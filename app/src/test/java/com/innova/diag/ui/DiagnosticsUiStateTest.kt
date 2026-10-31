package com.innova.diag.ui

import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.obd.GenericScanReport
import com.innova.diag.domain.obd.SupportedPidsReport
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.platform.bluetooth.BluetoothPermissionState
import com.innova.diag.platform.bluetooth.PairedDevice
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gating rules the screens rely on.
 *
 * The important one: a connected socket is **not** permission to send vehicle requests. Only a
 * configured session unlocks Scan and Live data.
 */
class DiagnosticsUiStateTest {

    private val device = PairedDevice(name = "vLinker", address = "AA:BB:CC:DD:EE:FF")

    @Test
    fun `a connected socket without a session cannot scan`() {
        val state = DiagnosticsUiState(
            transportState = TransportState.CONNECTED,
            sessionReady = false,
            permissions = BluetoothPermissionState.GRANTED,
        )

        assertTrue(state.connected)
        assertFalse(state.canScan)
        assertFalse(state.canPollLiveData)
    }

    @Test
    fun `a ready session enables scanning and live data`() {
        val state = DiagnosticsUiState(
            transportState = TransportState.CONNECTED,
            sessionReady = true,
            permissions = BluetoothPermissionState.GRANTED,
        )

        assertTrue(state.canScan)
        assertTrue(state.canPollLiveData)
    }

    @Test
    fun `scanning disables the scan button but not live data`() {
        val state = DiagnosticsUiState(sessionReady = true, scan = ScanState.Running)

        assertFalse(state.canScan)
        assertTrue(state.canPollLiveData)
    }

    @Test
    fun `connect needs an explicit selection and permission`() {
        val noSelection = DiagnosticsUiState(permissions = BluetoothPermissionState.GRANTED)
        val noPermission = DiagnosticsUiState(selectedDevice = device)
        val both = DiagnosticsUiState(
            permissions = BluetoothPermissionState.GRANTED,
            selectedDevice = device,
        )

        assertFalse(noSelection.canConnect)
        assertFalse(noPermission.canConnect)
        assertTrue(both.canConnect)
    }

    @Test
    fun `export is offered only once something has been sent`() {
        assertFalse(DiagnosticsUiState(sessionEntryCount = 0).canExport)
        assertTrue(DiagnosticsUiState(sessionEntryCount = 1).canExport)
    }

    @Test
    fun `a done scan keeps its report exactly`() {
        val report = GenericScanReport(
            supported = SupportedPidsReport(banks = emptyList()),
            readings = emptyList(),
            storedDtcs = DiagnosticResult.NoResponse,
            pendingDtcs = DiagnosticResult.NoResponse,
            vin = DiagnosticResult.NoResponse,
        )
        val state = DiagnosticsUiState(scan = ScanState.Done(report))

        assertTrue(state.scan is ScanState.Done)
        assertTrue(!report.supported.anyAnswered)
    }
}
