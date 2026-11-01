package com.innova.diag.ui

import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.obd.GenericScanReport
import com.innova.diag.domain.obd.PidReading
import com.innova.diag.platform.bluetooth.BluetoothPermissionState
import com.innova.diag.platform.bluetooth.PairedDevice

/** The screens. Plain state, not a navigation graph: there is nothing to deep-link. */
enum class DiagnosticsScreen(val tabLabel: String) {

    /**
     * The brand picker, and the first tab: "Any Vehicle — Generic OBD-II Only" is the fastest path to
     * a scan and needs no profile at all. It is a peer of the other screens rather than a gate in
     * front of them — nothing forces a brand choice before a generic scan can run.
     */
    BRANDS("Vehicles"),
    CONNECTION("Connection"),
    SCAN("Scan"),
    LIVE_DATA("Live data"),

    /** Bench-only: drives the in-process mock ECU, never a vehicle. */
    SIMULATED_TEST("Simulated test"),
}

/** State of the generic scan. Each state is a different truth, so none of them is "empty". */
sealed interface ScanState {

    /** Nothing has been scanned in this session. */
    data object Idle : ScanState

    /** A scan is in progress. Previous results are not shown, because they would look current. */
    data object Running : ScanState

    /** A scan finished; the report holds one outcome per section. */
    data class Done(val report: GenericScanReport) : ScanState

    /** The scan could not run at all (link failure, cancellation aside). */
    data class Failed(val reason: String) : ScanState
}

/** Live-data polling state. */
data class LiveState(
    val polling: Boolean = false,
    val readings: List<PidReading> = emptyList(),
) {
    fun readingFor(pid: Int): PidReading? = readings.firstOrNull { reading -> reading.definition.pid == pid }
}

/** Everything the UI renders. Immutable, so previews and tests can fabricate it exactly. */
data class DiagnosticsUiState(
    val screen: DiagnosticsScreen = DiagnosticsScreen.CONNECTION,
    val permissions: BluetoothPermissionState = BluetoothPermissionState.NONE,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val pairedDevicesError: String? = null,
    val selectedDevice: PairedDevice? = null,
    val transportState: TransportState = TransportState.DISCONNECTED,
    /** True once the adapter answered the init sequence: only then may requests be sent. */
    val sessionReady: Boolean = false,
    /** True while the init sequence / identity read is running. */
    val initializing: Boolean = false,
    val adapterIdentity: ReadingView? = null,
    val adapterProtocol: ReadingView? = null,
    val scan: ScanState = ScanState.Idle,
    val live: LiveState = LiveState(),
    val sessionEntryCount: Int = 0,
    /** Debug builds with a scripted adapter installed; release builds always see `false`. */
    val demoAdapterAvailable: Boolean = false,
    val usingDemoAdapter: Boolean = false,
    /** Debug-only raw AT console. `false` in release, which is also when the field is not drawn. */
    val rawConsoleAvailable: Boolean = false,
    val rawConsoleResult: ReadingView? = null,
    val message: String? = null,
) {
    val connected: Boolean get() = transportState == TransportState.CONNECTED

    val canConnect: Boolean
        get() = !connected &&
            !initializing &&
            selectedDevice != null &&
            permissions.canUseAdapter

    val canDisconnect: Boolean get() = connected || initializing

    /** Requests are allowed only with a ready session; a socket alone is not a diagnostic session. */
    val canScan: Boolean get() = sessionReady && scan !is ScanState.Running

    val canPollLiveData: Boolean get() = sessionReady

    val canExport: Boolean get() = sessionEntryCount > 0
}

/** A rendered export, ready to hand to a share sheet. */
data class SessionExportPayload(
    val fileName: String,
    val mimeType: String,
    val text: String,
)
