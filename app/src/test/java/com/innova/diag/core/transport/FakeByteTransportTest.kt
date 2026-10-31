package com.innova.diag.core.transport

import com.innova.diag.core.transport.fake.FakeByteTransport
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for [FakeByteTransport], and by extension the shape of every [ByteTransport].
 *
 * Two properties matter most for the layers above:
 * - fragments arrive **in order and without coalescing**, so reassembly across chunk boundaries is
 *   exercised rather than assumed;
 * - connection state is **accurate at every step** (`CONNECTING` is observable, failures keep their
 *   cause, drops are visible) so higher layers never have to guess whether a link existed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeByteTransportTest {

    // --- State machine ------------------------------------------------------

    @Test
    fun `starts disconnected`() = runTest {
        val transport = FakeByteTransport()

        assertEquals(TransportState.DISCONNECTED, transport.state().value)
    }

    @Test
    fun `connect reports CONNECTING before CONNECTED`() = runTest {
        val transport = FakeByteTransport(connectDelayMillis = CONNECT_DELAY_MILLIS)

        val connecting = launch { transport.connect() }
        testScheduler.runCurrent()

        assertEquals(TransportState.CONNECTING, transport.state().value)

        testScheduler.advanceTimeBy(CONNECT_DELAY_MILLIS)
        testScheduler.runCurrent()

        assertEquals(TransportState.CONNECTED, transport.state().value)
        assertEquals(CONNECT_DELAY_MILLIS, testScheduler.currentTime)
        connecting.join()
    }

    @Test
    fun `state flow replays the current state and then every transition`() = runTest {
        val transport = FakeByteTransport(connectDelayMillis = CONNECT_DELAY_MILLIS)
        val observed = mutableListOf<TransportState>()

        backgroundScope.launch { transport.state().collect { observed += it } }
        testScheduler.runCurrent()

        val connecting = launch { transport.connect() }
        testScheduler.runCurrent()
        testScheduler.advanceTimeBy(CONNECT_DELAY_MILLIS)
        testScheduler.runCurrent()

        transport.disconnect()
        testScheduler.runCurrent()

        assertEquals(
            listOf(
                TransportState.DISCONNECTED,
                TransportState.CONNECTING,
                TransportState.CONNECTED,
                TransportState.DISCONNECTED,
            ),
            observed,
        )
        connecting.join()
    }

    @Test
    fun `connect failure surfaces ERROR carrying the same throwable instance`() = runTest {
        val transport = FakeByteTransport()
        val failure = IllegalStateException("rfcomm socket closed during handshake")
        transport.connectFailure = failure

        val thrown = assertThrowsSuspend<IllegalStateException> { transport.connect() }

        assertSame(failure, thrown)
        assertEquals(TransportState.ERROR(failure), transport.state().value)
    }

    @Test
    fun `connect is a no-op when already connected`() = runTest {
        val transport = FakeByteTransport(connectDelayMillis = CONNECT_DELAY_MILLIS)

        val first = launch { transport.connect() }
        testScheduler.advanceUntilIdle()
        assertEquals(CONNECT_DELAY_MILLIS, testScheduler.currentTime)

        transport.connect()

        assertEquals(CONNECT_DELAY_MILLIS, testScheduler.currentTime)
        assertEquals(TransportState.CONNECTED, transport.state().value)
        first.join()
    }

    @Test
    fun `disconnect returns to DISCONNECTED and rejects writes`() = runTest {
        val transport = FakeByteTransport()
        transport.connect()
        assertEquals(TransportState.CONNECTED, transport.state().value)

        transport.disconnect()

        assertEquals(TransportState.DISCONNECTED, transport.state().value)
        assertThrowsSuspend<IllegalStateException> {
            transport.write(FakeTransportFixtures.GENERIC_REQUEST)
        }
        assertTrue(transport.writes.isEmpty())
    }

    // --- Refusals (fail-closed) --------------------------------------------

    @Test
    fun `writes and emissions are refused before connect`() = runTest {
        val transport = FakeByteTransport()

        assertThrowsSuspend<IllegalStateException> { transport.write(byteArrayOf(0x41)) }
        assertThrowsSuspend<IllegalStateException> { transport.emit(byteArrayOf(0x41)) }
        assertTrue(transport.writes.isEmpty())
    }

    // --- Chunk boundaries ---------------------------------------------------

    @Test
    fun `emits a payload across chunk boundaries without coalescing`() = runTest {
        val transport = FakeByteTransport()
        transport.connect()

        transport.emitSplit(
            payload = FakeTransportFixtures.SPLIT_PAYLOAD,
            chunkSizes = FakeTransportFixtures.SPLIT_CHUNK_SIZES,
        )

        val fragments = transport.incomingBytes()
            .take(FakeTransportFixtures.SPLIT_CHUNK_SIZES.size)
            .toList()

        assertEquals(
            "each fragment must arrive separately with its own size",
            FakeTransportFixtures.SPLIT_CHUNK_SIZES,
            fragments.map { it.size },
        )
        assertEquals(
            FakeTransportFixtures.SPLIT_FRAGMENTS,
            fragments.map { it.decodeToString() },
        )
        assertArrayEquals(
            "reassembly across boundaries must reproduce the payload exactly",
            FakeTransportFixtures.SPLIT_PAYLOAD,
            FakeTransportFixtures.concat(fragments),
        )
    }

    @Test
    fun `honours the injected inter-chunk delay`() = runTest {
        val transport = FakeByteTransport()
        transport.connect()

        transport.emitSplit(
            payload = "41 00\r".encodeToByteArray(),
            chunkSizes = listOf(2, 2, 2),
            interChunkDelayMillis = INTER_CHUNK_DELAY_MILLIS,
        )

        assertEquals(2 * INTER_CHUNK_DELAY_MILLIS, testScheduler.currentTime)

        val fragments = transport.incomingBytes().take(3).toList()
        assertEquals(3, fragments.size)
        assertArrayEquals("41 00\r".encodeToByteArray(), FakeTransportFixtures.concat(fragments))
    }

    @Test
    fun `rejects chunk sizes that do not cover the payload`() = runTest {
        val transport = FakeByteTransport()
        transport.connect()

        assertThrowsSuspend<IllegalArgumentException> {
            transport.emitSplit("ABC".encodeToByteArray(), chunkSizes = listOf(1, 1))
        }
    }

    @Test
    fun `rejects non-positive chunk sizes`() = runTest {
        val transport = FakeByteTransport()
        transport.connect()

        assertThrowsSuspend<IllegalArgumentException> {
            transport.emitSplit("ABC".encodeToByteArray(), chunkSizes = listOf(0, 3))
        }
    }

    // --- Writes -------------------------------------------------------------

    @Test
    fun `write honours its injected delay and stores a defensive copy`() = runTest {
        val transport = FakeByteTransport(writeDelayMillis = WRITE_DELAY_MILLIS)
        transport.connect()

        val callerBuffer = FakeTransportFixtures.GENERIC_REQUEST
        transport.write(callerBuffer)
        callerBuffer.fill(0x00) // mutating the caller's array afterwards must not alter the record

        assertEquals(WRITE_DELAY_MILLIS, testScheduler.currentTime)
        assertEquals(1, transport.writes.size)
        assertArrayEquals(
            FakeTransportFixtures.GENERIC_REQUEST,
            transport.writes.single(),
        )
    }

    // --- Simulated disconnects ---------------------------------------------

    @Test
    fun `simulated disconnect drops in-flight fragments and blocks writes`() = runTest {
        val transport = FakeByteTransport()
        transport.connect()
        transport.emit("41 00".encodeToByteArray())

        transport.simulateDisconnect()

        assertEquals(TransportState.DISCONNECTED, transport.state().value)
        assertNull(
            "bytes buffered at the moment of the drop must not be delivered",
            withTimeoutOrNull(1_000) { transport.incomingBytes().first() },
        )
        assertThrowsSuspend<IllegalStateException> { transport.write(byteArrayOf(0x41)) }
    }

    @Test
    fun `simulated disconnect with a cause reports ERROR`() = runTest {
        val transport = FakeByteTransport()
        transport.connect()
        val cause = IOException("link supervision timeout")

        transport.simulateDisconnect(cause)

        assertEquals(TransportState.ERROR(cause), transport.state().value)
    }

    @Test
    fun `reconnecting after a disconnect delivers new fragments only`() = runTest {
        val transport = FakeByteTransport()
        transport.connect()
        transport.emit("41".encodeToByteArray())

        transport.disconnect()
        transport.connect()
        transport.emit("42".encodeToByteArray())

        val fragments = transport.incomingBytes().take(1).toList()

        assertEquals("42", fragments.single().decodeToString())
    }

    private companion object {
        const val CONNECT_DELAY_MILLIS = 500L
        const val WRITE_DELAY_MILLIS = 25L
        const val INTER_CHUNK_DELAY_MILLIS = 10L
    }
}
