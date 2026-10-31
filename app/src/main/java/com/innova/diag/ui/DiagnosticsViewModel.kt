package com.innova.diag.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.innova.diag.BuildConfig
import com.innova.diag.adapter.elm.AdapterInfoReader
import com.innova.diag.adapter.elm.ElmChannel
import com.innova.diag.adapter.elm.ElmCommandChannel
import com.innova.diag.adapter.elm.ElmInitializer
import com.innova.diag.adapter.elm.ElmObdCommandPort
import com.innova.diag.adapter.elm.RecordingCommandChannel
import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.GenericScanService
import com.innova.diag.domain.obd.ObdCommandPort
import com.innova.diag.domain.obd.ObdService
import com.innova.diag.domain.obd.Vin
import com.innova.diag.domain.session.SessionExportFormat
import com.innova.diag.domain.session.SessionExporter
import com.innova.diag.domain.session.SessionLog
import com.innova.diag.domain.session.SessionSanitizer
import com.innova.diag.platform.bluetooth.AndroidBluetoothPermissionStateProvider
import com.innova.diag.platform.bluetooth.BluetoothBondedDeviceSource
import com.innova.diag.platform.bluetooth.BluetoothClassicTransport
import com.innova.diag.platform.bluetooth.BondedDevicesResult
import com.innova.diag.platform.bluetooth.PairedDevice
import com.innova.diag.platform.demo.DemoMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns one diagnostic session: the link, the adapter, the command log and the three screens' state.
 *
 * ### Sequencing that matters
 * 1. **Socket first.** `connect()` opens RFCOMM. That alone proves nothing about the adapter.
 * 2. **Init sequence second.** `ATZ…ATSP0` brings the adapter to a known state; only after it answers
 *    does [DiagnosticsUiState.sessionReady] become true, which is what enables Scan and Live data.
 * 3. **Adapter facts third.** `ATI` / `ATDP` are read once and shown as the adapter's own claims.
 *
 * Nothing here retries a failed read into a success, and nothing substitutes `0` for a missing value:
 * every screen renders domain [DiagnosticResult]s through [Readings].
 */
class DiagnosticsViewModel(application: Application) : AndroidViewModel(application) {

    private val permissionState = AndroidBluetoothPermissionStateProvider(application)

    private val adapter: BluetoothAdapter? =
        application.getSystemService(BluetoothManager::class.java)?.adapter

    private val bondedSource = BluetoothBondedDeviceSource(adapter, permissionState)

    /** Every command and response of this session, in order. Survives disconnect so it can be exported. */
    private val sessionLog = SessionLog()

    private val _uiState = MutableStateFlow(
        DiagnosticsUiState(
            rawConsoleAvailable = BuildConfig.DEBUG,
            demoAdapterAvailable = BuildConfig.DEBUG && DemoMode.isAvailable,
        ),
    )

    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    private var transport: ByteTransport? = null
    private var session: AdapterSession? = null
    private var transportWatcher: Job? = null
    private var liveJob: Job? = null

    /** Known from a completed scan; used to redact the VIN from exports. */
    private var knownVin: String? = null

    init {
        onPermissionsChanged()
        viewModelScope.launch {
            permissionState.state().collect { permissions ->
                update { state -> state.copy(permissions = permissions) }
            }
        }
    }

    // --- Connection ---------------------------------------------------------

    /** Call after a permission dialog resolves or when returning to the screen. */
    fun onPermissionsChanged() {
        permissionState.refresh()
        refreshPairedDevices()
    }

    fun refreshPairedDevices() {
        update { state ->
            when (val result = bondedSource.bondedDevices()) {
                is BondedDevicesResult.Available ->
                    state.copy(pairedDevices = result.devices, pairedDevicesError = null)
                is BondedDevicesResult.Unavailable ->
                    state.copy(pairedDevices = emptyList(), pairedDevicesError = result.reason)
            }
        }
    }

    /** Explicit selection by tap. The transport is told an address, never a name. */
    fun selectDevice(device: PairedDevice) {
        update { state -> state.copy(selectedDevice = device) }
    }

    fun connect() {
        val selected = _uiState.value.selectedDevice
        if (selected == null) {
            showMessage("Select a paired adapter first.")
            return
        }
        val bluetoothTransport = BluetoothClassicTransport(
            adapter = adapter,
            permissionState = permissionState,
        )
        bluetoothTransport.selectDevice(selected.address)
        startSession(bluetoothTransport, usingDemoAdapter = false)
    }

    /**
     * Debug-only: connect to the scripted adapter so the screens can be exercised without hardware.
     *
     * Ignored outright in a release build, where no scripted transport exists to create.
     */
    fun connectDemoAdapter() {
        if (!BuildConfig.DEBUG) return
        val demoTransport = DemoMode.create()
        if (demoTransport == null) {
            showMessage("No scripted adapter in this build.")
            return
        }
        startSession(demoTransport, usingDemoAdapter = true)
    }

    fun disconnect() {
        stopLiveData()
        val current = transport
        transportWatcher?.cancel()
        transportWatcher = null
        transport = null
        session = null
        viewModelScope.launch {
            if (current != null) {
                try {
                    current.disconnect()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (ignored: Throwable) {
                    // The transport publishes its own failure state; a close failure changes nothing here.
                }
            }
            update { state ->
                state.copy(
                    sessionReady = false,
                    initializing = false,
                    live = LiveState(),
                    transportState = TransportState.DISCONNECTED,
                )
            }
        }
    }

    private fun startSession(transport: ByteTransport, usingDemoAdapter: Boolean) {
        stopLiveData()
        sessionLog.clear()
        knownVin = null
        this.transport = transport
        watchTransport(transport)
        update { state ->
            state.copy(
                initializing = true,
                sessionReady = false,
                usingDemoAdapter = usingDemoAdapter,
                adapterIdentity = null,
                adapterProtocol = null,
                rawConsoleResult = null,
                scan = ScanState.Idle,
                sessionEntryCount = 0,
                message = null,
            )
        }

        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { transport.connect() }

                // One recording wrapper, one channel: every later command is logged and arbitrated.
                val channel: ElmCommandChannel = RecordingCommandChannel(ElmChannel(transport), sessionLog)
                val port: ObdCommandPort = ElmObdCommandPort(channel)
                val adapterSession = AdapterSession(
                    channel = channel,
                    port = port,
                    info = AdapterInfoReader(channel),
                    scan = GenericScanService(ObdService(port)),
                )
                session = adapterSession

                val info = withContext(Dispatchers.IO) {
                    ElmInitializer(channel).initialize()
                    adapterSession.info.read()
                }
                sessionLog.recordAdapterInfo(textOrNull(info.identity), textOrNull(info.protocol))
                update { state ->
                    state.copy(
                        initializing = false,
                        sessionReady = true,
                        sessionEntryCount = sessionLog.size,
                        adapterIdentity = Readings.text(info.identity),
                        adapterProtocol = Readings.text(info.protocol),
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                session = null
                update { state ->
                    state.copy(
                        initializing = false,
                        sessionReady = false,
                        sessionEntryCount = sessionLog.size,
                        message = "Adapter session failed: ${describe(failure)}",
                    )
                }
            }
        }
    }

    private fun watchTransport(transport: ByteTransport) {
        transportWatcher?.cancel()
        transportWatcher = viewModelScope.launch {
            transport.state().collect { transportState ->
                update { state -> state.copy(transportState = transportState) }
            }
        }
    }

    // --- Scan ---------------------------------------------------------------

    fun scan() {
        val active = session
        if (active == null) {
            showMessage("Connect to an adapter first.")
            return
        }
        if (_uiState.value.scan is ScanState.Running) return

        update { state -> state.copy(scan = ScanState.Running, message = null) }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) { active.scan.scan() }
                knownVin = vinOrNull(report.vin)
                update { state ->
                    state.copy(
                        scan = ScanState.Done(report),
                        sessionEntryCount = sessionLog.size,
                    )
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                update { state ->
                    state.copy(
                        scan = ScanState.Failed(describe(failure)),
                        sessionEntryCount = sessionLog.size,
                    )
                }
            }
        }
    }

    // --- Live data ----------------------------------------------------------

    /**
     * Polls RPM, speed and coolant until stopped.
     *
     * The supported-PID mask is read **once** per polling run and reused, so a tick costs at most
     * three requests. Requests are sequential: the channel allows one command in flight, and firing
     * them concurrently would only queue behind the same mutex.
     */
    fun startLiveData() {
        val active = session
        if (active == null) {
            showMessage("Connect to an adapter first.")
            return
        }
        if (liveJob?.isActive == true) return

        update { state -> state.copy(live = state.live.copy(polling = true), message = null) }
        liveJob = viewModelScope.launch {
            try {
                val supported = withContext(Dispatchers.IO) { active.scan.readSupported() }
                while (isActive) {
                    val readings = withContext(Dispatchers.IO) { active.scan.readReadings(supported) }
                    update { state ->
                        state.copy(
                            live = LiveState(polling = true, readings = readings),
                            sessionEntryCount = sessionLog.size,
                        )
                    }
                    delay(LIVE_POLL_INTERVAL_MILLIS)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                update { state ->
                    state.copy(
                        live = state.live.copy(polling = false),
                        message = "Live data stopped: ${describe(failure)}",
                    )
                }
            }
        }
    }

    fun stopLiveData() {
        liveJob?.cancel()
        liveJob = null
        update { state -> state.copy(live = state.live.copy(polling = false)) }
    }

    /**
     * Called when the host is no longer visible.
     *
     * Polling must not continue unseen: it keeps the radio and the vehicle bus busy for numbers that
     * nobody is reading, and it is the sort of background chatter that makes a dongle misbehave in a
     * parked car. The last readings stay on screen; restarting is one tap.
     */
    fun onHostStopped() {
        if (liveJob?.isActive == true) stopLiveData()
    }

    // --- Debug console ------------------------------------------------------

    /**
     * Sends one `AT…` adapter command from the debug console.
     *
     * Refused in release builds, and refused for anything that is not an `AT` command — see
     * [AtCommandGuard]. Vehicle modes are never reachable from here.
     */
    fun sendRawCommand(command: String) {
        if (!BuildConfig.DEBUG) return
        val active = session
        if (active == null) {
            showMessage("Connect to an adapter first.")
            return
        }
        when (val verdict = AtCommandGuard.check(command)) {
            is AtCommandGuard.Verdict.Rejected ->
                update { state -> state.copy(rawConsoleResult = ReadingView.Malformed(verdict.reason)) }
            AtCommandGuard.Verdict.Allowed -> viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { active.port.execute(command.trim()) }
                update { state ->
                    state.copy(
                        rawConsoleResult = Readings.text(result),
                        sessionEntryCount = sessionLog.size,
                    )
                }
            }
        }
    }

    // --- Export -------------------------------------------------------------

    /** Builds a sanitized log for sharing, or `null` when there is nothing to export yet. */
    fun buildExport(format: SessionExportFormat): SessionExportPayload? {
        val snapshot = sessionLog.snapshot()
        if (snapshot.entries.isEmpty()) {
            showMessage("Nothing to export yet: no commands have been sent.")
            return null
        }
        return SessionExportPayload(
            fileName = SessionExporter.fileName(snapshot, format),
            mimeType = format.mimeType,
            text = SessionExporter.export(snapshot, format, SessionSanitizer(knownVin)),
        )
    }

    // --- Navigation ---------------------------------------------------------

    fun navigateTo(screen: DiagnosticsScreen) {
        update { state -> state.copy(screen = screen) }
    }

    fun dismissMessage() {
        update { state -> state.copy(message = null) }
    }

    // --- Plumbing -----------------------------------------------------------

    private fun update(transform: (DiagnosticsUiState) -> DiagnosticsUiState) {
        _uiState.value = transform(_uiState.value)
    }

    private fun showMessage(message: String) {
        update { state -> state.copy(message = message) }
    }

    private fun describe(failure: Throwable): String =
        failure.message?.takeIf { it.isNotBlank() } ?: failure::class.java.simpleName

    /** The text of a successful answer, or `null` — used only for the export header. */
    private fun textOrNull(result: DiagnosticResult<String>): String? = result.dataOrNull

    /** The VIN of a successful answer, or `null`. */
    private fun vinOrNull(result: DiagnosticResult<Vin>): String? = result.dataOrNull?.value

    /** The live pipeline for one link. Rebuilt on every connect; never shared across links. */
    private class AdapterSession(
        val channel: ElmCommandChannel,
        val port: ObdCommandPort,
        val info: AdapterInfoReader,
        val scan: GenericScanService,
    )

    private companion object {
        /** Delay between live-data cycles: three sequential requests plus headroom. */
        const val LIVE_POLL_INTERVAL_MILLIS = 600L
    }
}
