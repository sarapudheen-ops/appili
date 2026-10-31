package com.innova.diag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.innova.diag.core.transport.TransportState
import com.innova.diag.core.transport.fake.FakeByteTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Hosts the transport for the shell screen.
 *
 * The transport is injected so that the real adapter can replace the fake without touching the UI.
 * Defaulting to [FakeByteTransport] is a deliberate statement: this phase has no physical
 * connection path at all (`docs/VEHICLE_MATRIX.md` §3 keeps every protocol field unverified).
 */
class TransportShellViewModel(
    private val transport: FakeByteTransport =
        FakeByteTransport(connectDelayMillis = CONNECT_DELAY_MILLIS),
) : ViewModel() {

    val state: StateFlow<TransportState> = transport.state()

    init {
        connect()
    }

    fun connect() {
        viewModelScope.launch {
            try {
                transport.connect()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // Failure is already published as TransportState.ERROR; the view reads it from there.
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch { transport.disconnect() }
    }

    private companion object {
        const val CONNECT_DELAY_MILLIS = 600L
    }
}
