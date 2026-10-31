package com.innova.diag.core.transport.fake

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.TransportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory [ByteTransport] for tests and for the UI shell that ships before a real adapter exists.
 *
 * Supports the three behaviours the transport layer must be tested against:
 *
 * 1. **Injected delays** — [connectDelayMillis] and [writeDelayMillis] suspend on `kotlinx`
 *    virtual time, so tests can assert intermediate states (`CONNECTING`) and timing without
 *    sleeping.
 * 2. **Split byte streams** — [emitSplit] hands one payload to the stream as several fragments, so
 *    consumers are tested against chunk boundaries that do not align with protocol frames.
 * 3. **Simulated disconnects** — [simulateDisconnect] drops bytes still in flight and moves the
 *    transport to [TransportState.DISCONNECTED] (or [TransportState.ERROR] when a cause is given).
 *
 * Nothing here parses, frames, or validates protocol content: it moves opaque bytes, exactly like
 * the real transport must.
 *
 * ### Contract notes
 * - [incomingBytes] is single-consumer. Fragments produced while nobody is collecting are buffered
 *   in order (unbounded), so a collector started later still sees them.
 * - The stream is closed only when the fake itself is discarded; this fake models one link
 *   lifetime and does not close the flow on disconnect.
 * - Not thread-safe for concurrent producers: sequencing is enforced with a [Mutex], and tests are
 *   expected to drive one operation at a time.
 */
class FakeByteTransport(
    private val connectDelayMillis: Long = 0L,
    private val writeDelayMillis: Long = 0L,
) : ByteTransport {

    private val gate = Mutex()
    private val currentState = MutableStateFlow<TransportState>(TransportState.DISCONNECTED)
    private val inbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val writeLog = mutableListOf<ByteArray>()

    /** When non-null, the next [connect] fails with this exact instance. */
    var connectFailure: Throwable? = null

    /** Every payload accepted by [write], in order, as defensive copies. */
    val writes: List<ByteArray>
        get() = synchronized(writeLog) { writeLog.map { it.copyOf() } }

    override fun state(): StateFlow<TransportState> = currentState.asStateFlow()

    override fun incomingBytes(): Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun connect() {
        gate.withLock {
            if (currentState.value == TransportState.CONNECTED) return

            currentState.value = TransportState.CONNECTING
            try {
                delay(connectDelayMillis)
                val failure = connectFailure
                if (failure != null) {
                    currentState.value = TransportState.ERROR(failure)
                    throw failure
                }
                currentState.value = TransportState.CONNECTED
            } catch (cancellation: CancellationException) {
                currentState.value = TransportState.DISCONNECTED
                throw cancellation
            }
        }
    }

    override suspend fun disconnect() {
        gate.withLock {
            currentState.value = TransportState.DISCONNECTED
            discardInFlightFrames()
        }
    }

    override suspend fun write(data: ByteArray) {
        gate.withLock {
            requireConnected("write")
            val snapshot = data.copyOf()
            delay(writeDelayMillis)
            synchronized(writeLog) { writeLog += snapshot }
        }
    }

    /** Emits [frame] as a single fragment. */
    suspend fun emit(frame: ByteArray) = emitSplit(frame, chunkSizes = listOf(frame.size))

    /**
     * Emits [payload] as one fragment per entry in [chunkSizes], optionally pausing
     * [interChunkDelayMillis] between fragments.
     *
     * Sizes are validated up front: every size must be positive and they must sum to the payload
     * length. A silent mismatch would make chunk-boundary tests lie about what was transmitted.
     *
     * @throws IllegalArgumentException if [chunkSizes] is empty, contains a non-positive value, or
     *   does not sum to `payload.size`.
     * @throws IllegalStateException if the transport is not connected.
     */
    suspend fun emitSplit(
        payload: ByteArray,
        chunkSizes: List<Int>,
        interChunkDelayMillis: Long = 0L,
    ) {
        require(chunkSizes.isNotEmpty()) { "chunkSizes must not be empty" }
        require(chunkSizes.all { it > 0 }) { "every chunk size must be > 0, got $chunkSizes" }
        require(chunkSizes.sum() == payload.size) {
            "chunkSizes $chunkSizes sum to ${chunkSizes.sum()} but the payload is ${payload.size} bytes"
        }

        gate.withLock {
            requireConnected("emitSplit")

            // A `for` loop, not forEachIndexed: the body suspends (delay/send), and the lambda
            // passed to forEachIndexed is not a suspend function type.
            var offset = 0
            for ((index, size) in chunkSizes.withIndex()) {
                if (index > 0 && interChunkDelayMillis > 0L) delay(interChunkDelayMillis)
                inbound.send(payload.copyOfRange(offset, offset + size))
                offset += size
            }
        }
    }

    /**
     * Simulates losing the link mid-conversation.
     *
     * Bytes already queued but not yet consumed are discarded, because that is what a real link
     * loss does — it does not politely deliver its backlog first. With [cause], the state becomes
     * [TransportState.ERROR] carrying that exact instance; without it, [TransportState.DISCONNECTED].
     */
    suspend fun simulateDisconnect(cause: Throwable? = null) {
        gate.withLock {
            currentState.value = cause?.let { TransportState.ERROR(it) } ?: TransportState.DISCONNECTED
            discardInFlightFrames()
        }
    }

    private fun requireConnected(operation: String) {
        check(currentState.value == TransportState.CONNECTED) {
            "$operation() requires a CONNECTED transport, but the state is ${currentState.value}"
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun discardInFlightFrames() {
        while (inbound.tryReceive().isSuccess) {
            // Each drained fragment represents bytes that died with the link.
        }
    }
}
