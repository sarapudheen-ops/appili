package com.innova.diag.platform.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import com.innova.diag.core.transport.PhysicalTransport
import com.innova.diag.core.transport.TransportState
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Bluetooth Classic RFCOMM (Serial Port Profile) implementation of [PhysicalTransport].
 *
 * ### What this class does
 * Opens an RFCOMM socket to a **user-selected** bonded device, adapts the blocking stream into a
 * `Flow<ByteArray>`, and reports connection state. That is all.
 *
 * ### What this class must never do
 * - **No adapter commands on connect.** No `ATZ`, `ATE0`, `ATSP…`, no protocol probing, no handshake
 *   beyond RFCOMM itself. Connecting here means "open a socket", not "initialise a diagnostic
 *   session". Per `docs/VEHICLE_MATRIX.md`, the protocol is still `UNKNOWN - PENDING_PHYSICAL_LOG`,
 *   so guessing init bytes would violate `AGENTS.md` Rule 1.
 * - **No automatic device choice.** The device is supplied by [selectDevice], which the UI calls
 *   only from an explicit user tap. Nothing here matches on device names.
 * - **No BLE.** Classic RFCOMM only; any BLE path is out of scope.
 * - **No interpretation.** Bytes in, bytes out. Framing and protocol live above this layer.
 *
 * ### Threading
 * Every blocking socket operation — `connect()`, `read()`, `write()`, `close()` — runs on
 * [ioDispatcher], which defaults to `Dispatchers.IO`. No socket call is ever made on the caller's
 * thread.
 *
 * ### Cancellation caveat
 * A blocking `InputStream.read()` cannot be interrupted by coroutine cancellation; it is unblocked
 * by closing the socket. [disconnect] therefore closes first and cancels second.
 */
class BluetoothClassicTransport(
    private val adapter: BluetoothAdapter?,
    private val permissionState: BluetoothPermissionStateProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val readBufferSize: Int = DEFAULT_READ_BUFFER_SIZE,
) : PhysicalTransport {

    private val gate = Mutex()
    private val writeGate = Mutex()

    private val currentState = MutableStateFlow<TransportState>(TransportState.DISCONNECTED)
    private val inbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    @Volatile
    private var socket: BluetoothSocket? = null

    @Volatile
    private var readJob: Job? = null

    /** Set while a local [disconnect] is in progress, so a resulting read failure is not an error. */
    @Volatile
    private var closing = false

    /**
     * Bumped on every connect/disconnect/link-loss. A read loop may only move the state if its
     * generation is still current, so a dying link can never overwrite a newer one's state.
     */
    @Volatile
    private var linkGeneration = 0

    @Volatile
    private var selectedAddress: String? = null

    /** Address of the explicitly selected adapter, or `null`. Never populated automatically. */
    val selectedDeviceAddress: String?
        get() = selectedAddress

    /**
     * Records the user's explicit choice. Passing `null` clears it.
     *
     * Called from a UI event handler only — never from a scan, a list load, or name matching.
     */
    fun selectDevice(address: String?) {
        selectedAddress = address?.trim()?.takeIf { it.isNotEmpty() }
    }

    override fun state(): StateFlow<TransportState> = currentState.asStateFlow()

    override fun incomingBytes(): Flow<ByteArray> = inbound.receiveAsFlow()

    /**
     * Opens the RFCOMM socket to the selected device.
     *
     * Fails closed, in order: no adapter, Bluetooth off, missing `BLUETOOTH_CONNECT`, no device
     * selected. Each refusal publishes [TransportState.ERROR] and is thrown.
     */
    @SuppressLint("MissingPermission") // Guarded by the permissionState check below; lint cannot see it.
    override suspend fun connect() {
        gate.withLock {
            if (currentState.value == TransportState.CONNECTED) return

            val address = selectedAddress ?: throw refuse(BluetoothTransportException.NoDeviceSelected)
            val bluetoothAdapter = adapter ?: throw refuse(BluetoothTransportException.AdapterUnavailable)

            val missing = permissionState.state().value.missing
            if (BluetoothPermission.CONNECT in missing) {
                throw refuse(BluetoothTransportException.PermissionDenied(missing))
            }
            if (!bluetoothAdapter.isEnabled) throw refuse(BluetoothTransportException.Disabled)

            currentState.value = TransportState.CONNECTING
            closing = false
            val generation = ++linkGeneration

            // Held only so a failure between creation and hand-over can close the socket.
            var pendingClose: BluetoothSocket? = null
            try {
                val openedSocket = withContext(ioDispatcher) {
                    bluetoothAdapter.getRemoteDevice(address)
                        .createRfcommSocketToServiceRecord(SPP_SERVICE_UUID)
                }
                pendingClose = openedSocket
                withContext(ioDispatcher) { openedSocket.connect() }

                socket = openedSocket
                pendingClose = null // Ownership transferred; disconnect() closes it from now on.
                currentState.value = TransportState.CONNECTED
                // No writes here on purpose: see the class documentation.
                readJob = scope.launch { readLoop(openedSocket, generation) }
            } catch (cancellation: CancellationException) {
                pendingClose?.let { closeQuietly(it) }
                currentState.value = TransportState.DISCONNECTED
                throw cancellation
            } catch (failure: Throwable) {
                pendingClose?.let { closeQuietly(it) }
                socket = null
                currentState.value = TransportState.ERROR(failure)
                throw failure
            }
        }
    }

    override suspend fun disconnect() {
        gate.withLock {
            closing = true
            linkGeneration++ // Any loop still running becomes stale and may not move the state.
            val activeSocket = socket
            socket = null
            val activeReadJob = readJob
            readJob = null

            withContext(NonCancellable + ioDispatcher) {
                runCatching { activeSocket?.close() }
            }
            activeReadJob?.cancel()

            currentState.value = TransportState.DISCONNECTED
        }
    }

    override suspend fun write(data: ByteArray) {
        writeGate.withLock {
            val state = currentState.value
            if (state != TransportState.CONNECTED) {
                throw IllegalStateException("write() requires a CONNECTED transport, but the state is $state")
            }
            val activeSocket = socket
                ?: throw IllegalStateException("write() requires an open RFCOMM socket, but none is held")

            withContext(ioDispatcher) {
                activeSocket.outputStream.write(data)
                activeSocket.outputStream.flush()
            }
        }
    }

    /**
     * Turns the blocking `InputStream.read()` into channel emissions.
     *
     * `read()` returning `-1` is an orderly remote close and maps to [TransportState.DISCONNECTED];
     * an exception is a broken link and maps to [TransportState.ERROR] — unless this link is being
     * torn down locally, in which case it is still a disconnect, not an error.
     */
    private suspend fun readLoop(activeSocket: BluetoothSocket, generation: Int) {
        val buffer = ByteArray(readBufferSize)
        try {
            while (currentCoroutineContext().isActive) {
                val read = withContext(ioDispatcher) { activeSocket.inputStream.read(buffer) }
                if (read < 0) {
                    closeQuietly(activeSocket)
                    onLinkEnded(generation, cause = null)
                    return
                }
                if (read > 0) {
                    // Transport fragments, not protocol frames: consumers reassemble.
                    inbound.send(buffer.copyOf(read))
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            closeQuietly(activeSocket)
            onLinkEnded(generation, cause = failure)
        }
    }

    private fun onLinkEnded(generation: Int, cause: Throwable?) {
        if (generation != linkGeneration) return // A newer connection (or a local close) owns the state.
        linkGeneration++
        socket = null
        currentState.value = when {
            cause == null -> TransportState.DISCONNECTED
            closing -> TransportState.DISCONNECTED
            else -> TransportState.ERROR(cause)
        }
    }

    private suspend fun closeQuietly(activeSocket: BluetoothSocket) {
        withContext(NonCancellable + ioDispatcher) {
            runCatching { activeSocket.close() }
        }
    }

    private fun refuse(refusal: BluetoothTransportException): BluetoothTransportException {
        currentState.value = TransportState.ERROR(refusal)
        return refusal
    }

    companion object {
        /**
         * Bluetooth Classic Serial Port Profile (SPP) UUID — the RFCOMM service every ELM327-style
         * adapter advertises.
         */
        val SPP_SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val DEFAULT_READ_BUFFER_SIZE = 1024
    }
}
