package com.innova.diag.adapter.elm

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.TransportState
import com.innova.diag.core.transport.assertThrowsSuspend
import com.innova.diag.core.transport.fake.FakeByteTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour of [ElmChannel] against replayed adapter transcripts.
 *
 * These tests deliberately feed the channel the way a socket would: fragmented, noisy, sometimes
 * silent. The channel is the first place where "we received some bytes" turns into "we received a
 * response", so it is where chunk-boundary bugs, echo-stripping bugs and prompt-detection bugs must
 * die.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElmChannelTest {

    // --- Reassembly ---------------------------------------------------------

    @Test
    fun `fragmented transcript is reassembled into a single response`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        // Transcript exactly as given in the brief. The command string is opaque test data: the
        // production engine issues no PIDs (see ElmInitializerTest), and this channel sends only
        // what it is handed.
        val replay = TranscriptReplay(transport)
            .chunk("41 00", delayAfterMillis = 50)
            .chunk(" BE 3F", delayAfterMillis = 20)
            .chunk("\r\r>")
        backgroundScope.launch { replay.play() }

        val response = channel.execute("0100")

        assertEquals("4100BE3F", response)
    }

    @Test
    fun `a payload split mid-token still arrives whole`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        val replay = TranscriptReplay(transport)
            .chunk("41 0")
            .chunk("0 BE")
            .chunk(" 3F\r")
            .chunk("\r>")
        backgroundScope.launch { replay.play() }

        assertEquals("4100BE3F", channel.execute("ATDPN"))
    }

    @Test
    fun `prompt detection ends the response even when more bytes follow in the same fragment`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        transport.emit("41 00\r\r>trailing noise".encodeToByteArray())

        assertEquals("4100", channel.execute("ATDPN"))
    }

    // --- Normalization ------------------------------------------------------

    @Test
    fun `echoed command is stripped`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        transport.emit("ATZ\rELM327 v1.5\r\r>".encodeToByteArray())

        assertEquals("ELM327v1.5", channel.execute("ATZ"))
    }

    @Test
    fun `echo stripping is case insensitive and only applies to the leading line`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        transport.emit("atz\rATE0\r\r>".encodeToByteArray())

        // The echo is dropped; a later line that happens to repeat the text is data.
        assertEquals("ATE0", channel.execute("ATZ"))
    }

    @Test
    fun `line endings, blank lines and whitespace are normalized away`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        transport.emit("41 00\r\n\r\nBE 3F\r\r>".encodeToByteArray())

        assertEquals("4100BE3F", channel.execute("ATDPN"))
    }

    @Test
    fun `the searching banner is noise, not data`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        transport.emit("SEARCHING...\r41 00 BE 3F\r\r>".encodeToByteArray())

        assertEquals("4100BE3F", channel.execute("ATSP0"))
    }

    @Test
    fun `a bare prompt yields an empty response rather than a failure`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        transport.emit("\r\r>".encodeToByteArray())

        assertEquals("", channel.execute("ATDPN"))
    }

    // --- Adapter error tokens ----------------------------------------------

    @Test
    fun `a question mark maps to UnknownCommand`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        transport.emit("?\r\r>".encodeToByteArray())

        val error = assertThrowsSuspend<ElmException.UnknownCommand> { channel.execute("ATFOO") }

        assertEquals("ATFOO", error.command)
        assertTrue(
            "the raw adapter answer must be preserved for diagnostics",
            error.rawResponse.contains("?"),
        )
    }

    @Test
    fun `every documented adapter error token maps to its own exception type`() = runTest {
        val cases: List<Pair<String, Class<out ElmException>>> = listOf(
            "NO DATA" to ElmException.NoData::class.java,
            "UNABLE TO CONNECT" to ElmException.UnableToConnect::class.java,
            "BUS ERROR" to ElmException.BusError::class.java,
            "CAN ERROR" to ElmException.CanError::class.java,
            "BUFFER FULL" to ElmException.BufferFull::class.java,
            "STOPPED" to ElmException.Stopped::class.java,
            "?" to ElmException.UnknownCommand::class.java,
        )

        for ((token, expectedType) in cases) {
            val transport = connectedTransport()
            val channel = ElmChannel(transport)
            transport.emit("$token\r\r>".encodeToByteArray())

            val thrown = runCatching { channel.execute("ATDPN") }.exceptionOrNull()

            assertNotNull("token \"$token\" produced no exception", thrown)
            assertTrue(
                "token \"$token\" mapped to ${thrown?.javaClass?.simpleName} instead of ${expectedType.simpleName}",
                expectedType.isInstance(thrown),
            )
            assertEquals("ATDPN", (thrown as ElmException).command)
        }
    }

    @Test
    fun `an error token later in the response is still detected`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        transport.emit("41 00\rNO DATA\r\r>".encodeToByteArray())

        assertThrowsSuspend<ElmException.NoData> { channel.execute("ATDPN") }
    }

    // --- Timeouts -----------------------------------------------------------

    @Test
    fun `a missing prompt is a Timeout after the configured budget`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport, timeoutMillis = 500)

        transport.emit("41 00\r".encodeToByteArray()) // data, but never the prompt

        val error = assertThrowsSuspend<ElmException.Timeout> { channel.execute("ATDPN") }

        assertEquals("ATDPN", error.command)
        assertEquals(500L, error.timeoutMillis)
        assertEquals("the budget must be measured per command", 500L, testScheduler.currentTime)
    }

    @Test
    fun `per-call timeout overrides the channel default`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport, timeoutMillis = 2_000)

        val error = assertThrowsSuspend<ElmException.Timeout> {
            channel.execute("ATZ", timeoutMillis = 250)
        }

        assertEquals(250L, error.timeoutMillis)
        assertEquals(250L, testScheduler.currentTime)
    }

    @Test
    fun `a prompt that arrives just inside the budget is accepted`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport, timeoutMillis = 200)

        val replay = TranscriptReplay(transport)
            .chunk("41 00\r", delayAfterMillis = 190)
            .chunk("\r>")
        backgroundScope.launch { replay.play() }

        assertEquals("4100", channel.execute("ATDPN"))
    }

    // --- Serialization ------------------------------------------------------

    @Test
    fun `only one command is in flight at a time`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        val first = launch { channel.execute("ATZ") }
        testScheduler.runCurrent()
        assertEquals(1, transport.writes.size)

        val second = launch { channel.execute("ATE0") }
        testScheduler.runCurrent()
        assertEquals(
            "the second command must not reach the adapter while the first is pending",
            1,
            transport.writes.size,
        )

        transport.emit("\r\r>".encodeToByteArray()) // completes ATZ
        testScheduler.runCurrent()
        assertEquals(2, transport.writes.size)
        assertEquals("ATE0\r", transport.writes[1].decodeToString())

        transport.emit("\r\r>".encodeToByteArray()) // completes ATE0
        testScheduler.runCurrent()

        assertFalse(first.isActive)
        assertFalse(second.isActive)
    }

    // --- Input validation ---------------------------------------------------

    @Test
    fun `a command containing a line terminator is rejected before anything is written`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        val error = assertThrowsSuspend<IllegalArgumentException> {
            channel.execute("ATZ\rATE0")
        }

        assertTrue(error.message.orEmpty().contains("line terminator"))
        assertTrue("nothing may reach the adapter", transport.writes.isEmpty())
    }

    @Test
    fun `a blank command is rejected`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)

        assertThrowsSuspend<IllegalArgumentException> { channel.execute("   ") }
        assertTrue(transport.writes.isEmpty())
    }

    // --- Link loss ----------------------------------------------------------

    @Test
    fun `a stream that ends before the prompt is inconclusive, not an empty response`() = runTest {
        val channel = ElmChannel(EndedStreamTransport())

        assertThrowsSuspend<ElmException.LinkClosed> { channel.execute("ATZ") }
    }

    private suspend fun connectedTransport(): FakeByteTransport =
        FakeByteTransport().also { it.connect() }
}

/**
 * Minimal [ByteTransport] whose incoming stream is already finished, used to prove the channel never
 * mistakes "no more bytes" for "an empty response".
 */
private class EndedStreamTransport : ByteTransport {

    private val currentState = MutableStateFlow<TransportState>(TransportState.CONNECTED)

    override suspend fun connect() = Unit

    override suspend fun disconnect() {
        currentState.value = TransportState.DISCONNECTED
    }

    override fun incomingBytes(): Flow<ByteArray> = emptyFlow()

    override suspend fun write(data: ByteArray) = Unit

    override fun state(): StateFlow<TransportState> = currentState
}
