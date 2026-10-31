package com.innova.diag.core.transport

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Byte-level, framing-agnostic duplex link to an adapter.
 *
 * Deliberately free of Android, Bluetooth, and ELM327/STN concepts: implementations may wrap an
 * RFCOMM socket, a USB serial port, or an in-memory fake, and the layer above is identical in all
 * three cases. Despite the name, [ByteArray] chunks emitted by [incomingBytes] are **transport
 * fragments, not protocol frames** — nothing here guarantees that one emission equals one line or
 * one diagnostic message, so consumers must be prepared to reassemble across boundaries.
 *
 * ### Governance constraints (see `docs/` and `AGENTS.md`)
 * - **No raw terminal.** This interface is not a user-facing "send bytes" feature. It must not be
 *   exposed to the UI, scripting, or any dynamic configuration; commands are typed values emitted
 *   by the adapter engine only.
 * - **Writes only inside a confirmed transaction.** [write] may be called only from the
 *   `USER_CONFIRMED -> TRANSMITTING` transition of `docs/SAFETY_POLICY.md` §3.5. Level 3/4
 *   operations (active tests, coding) are simulator-only and must never reach a physical
 *   implementation of this interface (`docs/PRODUCT_SCOPE.md` §4.1).
 * - **This layer performs no interpretation.** It reports bytes and connection state. It never
 *   invents, synthesizes, or repairs a payload; an absent response stays absent.
 */
interface ByteTransport {

    /**
     * Opens the link.
     *
     * Suspends until the transport reaches [TransportState.CONNECTED] or fails. On failure the
     * state is [TransportState.ERROR] and the failure is thrown. Calling this while already
     * connected is a no-op.
     */
    suspend fun connect()

    /**
     * Closes the link and releases resources. Idempotent.
     *
     * After this returns, the state is [TransportState.DISCONNECTED]. Bytes buffered at the moment
     * of closing are lost; callers must not treat a disconnect as an acknowledgement.
     */
    suspend fun disconnect()

    /**
     * Upstream bytes as they arrive, in order, without coalescing or splitting guarantees.
     *
     * Single-consumer: implementations are not required to support more than one collector, and a
     * second collector must not be assumed to observe the same stream.
     */
    fun incomingBytes(): Flow<ByteArray>

    /**
     * Emits [data] to the peer.
     *
     * Fails rather than silently dropping data when the transport is not connected. Callers must
     * treat any failure after the first byte as indeterminate: see the `RESULT_UNKNOWN` rule in
     * `docs/SAFETY_POLICY.md` §4 — never "assume it worked".
     */
    suspend fun write(data: ByteArray)

    /**
     * Current connection state as an observable, replay-1 stream.
     *
     * The returned flow is hot and always has a current value, so a new collector immediately
     * receives the present state without having to wait for a change.
     */
    fun state(): StateFlow<TransportState>
}
