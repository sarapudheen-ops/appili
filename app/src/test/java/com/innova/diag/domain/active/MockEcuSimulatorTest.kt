package com.innova.diag.domain.active

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bench ECU's framing, pinned as raw text.
 *
 * These read the transport's byte stream directly instead of going through the ELM channel, so a
 * failure points at the simulator's answer rather than at the framing above it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockEcuSimulatorTest {

    @Test
    fun `an IO-control activation is answered positively`() = runTest {
        val simulator = connected()

        assertEquals("6F20010301\r\r>", simulator.exchange("2F20010301"))
        assertEquals(listOf("2F20010301"), simulator.frames)
    }

    @Test
    fun `ReturnControlToECU is answered with the control option echoed`() = runTest {
        val simulator = connected()

        assertEquals("6F200100\r\r>", simulator.exchange("2F200100"))
    }

    @Test
    fun `routine control start and stop are answered positively`() = runTest {
        val simulator = connected()

        assertEquals("71010203\r\r>", simulator.exchange("31010203"))
        assertEquals("71020203\r\r>", simulator.exchange("31020203"))
    }

    @Test
    fun `an unknown service gets serviceNotSupported`() = runTest {
        val simulator = connected()

        assertEquals("7F2711\r\r>", simulator.exchange("2701"))
    }

    @Test
    fun `a malformed IO-control request gets incorrectMessageLength`() = runTest {
        val simulator = connected()

        // Missing the control option byte entirely.
        assertEquals("7F2F13\r\r>", simulator.exchange("2F2001"))
        // Odd number of hex digits is not a UDS frame either.
        assertEquals("7F2F13\r\r>", simulator.exchange("2F20010"))
    }

    @Test
    fun `a request that is not hex gets the adapter's question mark`() = runTest {
        val simulator = connected()

        assertEquals("?\r\r>", simulator.exchange("ATZ"))
    }

    @Test
    fun `requests are recorded normalized and in order`() = runTest {
        val simulator = connected()

        simulator.exchange("2f 20 01 03 01")
        simulator.exchange("2F200100")

        assertEquals(listOf("2F20010301", "2F200100"), simulator.frames)
        assertEquals("2F200100", simulator.lastFrame)
    }

    @Test
    fun `a scripted answer overrides the default`() = runTest {
        val simulator = connected()
        simulator.script("2F20010301", "7F2F31")

        assertEquals("7F2F31\r\r>", simulator.exchange("2F20010301"))
    }

    @Test
    fun `a silent script emits nothing at all`() = runTest {
        val simulator = connected()
        simulator.script("2F20010301", MockEcuSimulator.SILENT)

        simulator.write("2F20010301\r".toByteArray(Charsets.US_ASCII))

        // Nothing queued: the channel above would time out, which is the honest outcome.
        assertEquals(listOf("2F20010301"), simulator.frames)
        assertNull(simulator.pendingBytesOrNull())
    }

    @Test
    fun `a dropped link refuses further writes`() = runTest {
        val simulator = connected()
        simulator.simulateDisconnect()

        val error = runCatching {
            simulator.write("2F20010301\r".toByteArray(Charsets.US_ASCII))
        }.exceptionOrNull()

        assertTrue("expected IllegalStateException, got $error", error is IllegalStateException)
        assertNull("a refused write is not a received frame", simulator.lastFrame)
    }

    @Test
    fun `the simulator starts disconnected and connect opens it`() = runTest {
        val simulator = MockEcuSimulator()
        assertEquals(com.innova.diag.core.transport.TransportState.DISCONNECTED, simulator.state().value)

        simulator.connect()

        assertEquals(com.innova.diag.core.transport.TransportState.CONNECTED, simulator.state().value)
    }

    // --- helpers -------------------------------------------------------------

    private suspend fun connected(): MockEcuSimulator = MockEcuSimulator().apply { connect() }

    /** Sends [command] and returns the mock's raw answer. */
    private suspend fun MockEcuSimulator.exchange(command: String): String {
        write("$command\r".toByteArray(Charsets.US_ASCII))
        return pendingBytesOrNull() ?: ""
    }

    /** The next queued answer, or `null` when the mock said nothing. */
    private suspend fun MockEcuSimulator.pendingBytesOrNull(): String? =
        kotlinx.coroutines.withTimeoutOrNull(50L) {
            incomingBytes().first().toString(Charsets.US_ASCII)
        }
}
