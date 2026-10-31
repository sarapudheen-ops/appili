package com.innova.diag.adapter.elm

import com.innova.diag.core.transport.assertThrowsSuspend
import com.innova.diag.core.transport.fake.FakeByteTransport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the adapter initialization sequence.
 *
 * Beyond "does it send five commands", these tests pin the governance constraints of this phase:
 * no vehicle header is ever set, and no diagnostic request is ever issued.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElmInitializerTest {

    @Test
    fun `initialize sends the five adapter commands in order`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)
        queuePrompts(transport, ElmInitializer.SEQUENCE.size)

        val responses = ElmInitializer(channel).initialize()

        assertEquals(ElmInitializer.SEQUENCE.size, responses.size)
        assertEquals(
            listOf("ATZ\r", "ATE0\r", "ATL0\r", "ATS0\r", "ATSP0\r"),
            transport.writes.map { it.decodeToString() },
        )
    }

    @Test
    fun `initialize returns each step's response`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport)
        // Echo is on at ATZ (factory default), so the command comes back before the banner.
        transport.emit("ATZ\r\rELM327 v1.5\r\r>".encodeToByteArray())
        transport.emit("ATE0\rOK\r\r>".encodeToByteArray())
        transport.emit("ATL0\rOK\r\r>".encodeToByteArray())
        transport.emit("ATS0\rOK\r\r>".encodeToByteArray())
        transport.emit("ATSP0\rAUTO\r\r>".encodeToByteArray())

        val responses = ElmInitializer(channel).initialize()

        assertEquals(listOf("ELM327v1.5", "OK", "OK", "OK", "AUTO"), responses)
    }

    @Test
    fun `initialization never sets a vehicle header`() = runTest {
        val transport = connectedTransport()
        queuePrompts(transport, ElmInitializer.SEQUENCE.size)

        ElmInitializer(ElmChannel(transport)).initialize()

        val commands = transport.writes.map { it.decodeToString() }
        assertTrue(
            "ATSH must never be sent: the vehicle header is unverified",
            commands.none { it.contains("ATSH", ignoreCase = true) },
        )
    }

    @Test
    fun `initialization issues no diagnostic requests`() = runTest {
        val transport = connectedTransport()
        queuePrompts(transport, ElmInitializer.SEQUENCE.size)

        ElmInitializer(ElmChannel(transport)).initialize()

        val commands = transport.writes.map { it.decodeToString().trim() }
        assertTrue("only adapter commands are allowed here", commands.all { it.startsWith("AT") })
        assertTrue(
            "no bare hex request (PID / mode) may be issued in this phase",
            commands.none { Regex("^[0-9A-Fa-f]").containsMatchIn(it) },
        )
    }

    @Test
    fun `protocol detection is automatic and never pinned`() {
        assertTrue(ElmInitializer.SEQUENCE.contains("ATSP0"))

        val protocolCommands = ElmInitializer.SEQUENCE.filter { it.startsWith("ATSP") }
        assertEquals("exactly one protocol command, and it must be auto-detect", listOf("ATSP0"), protocolCommands)
    }

    @Test
    fun `a rejected command aborts the sequence immediately`() = runTest {
        val transport = connectedTransport()
        transport.emit("\r\r>".encodeToByteArray()) // ATZ succeeds
        transport.emit("?\r\r>".encodeToByteArray()) // ATE0 rejected
        queuePrompts(transport, 3) // spares that must stay unused

        val error = assertThrowsSuspend<ElmException.UnknownCommand> {
            ElmInitializer(ElmChannel(transport)).initialize()
        }

        assertEquals("ATE0", error.command)
        assertEquals("initialization must stop at the failing step", 2, transport.writes.size)
        assertFalse(transport.writes.any { it.decodeToString().startsWith("ATL0") })
    }

    @Test
    fun `the reset step uses the longer reset timeout`() = runTest {
        val transport = connectedTransport()
        val channel = ElmChannel(transport, timeoutMillis = 2_000)

        val error = assertThrowsSuspend<ElmException.Timeout> {
            ElmInitializer(channel, resetTimeoutMillis = 7_500).initialize()
        }

        assertEquals(ElmInitializer.RESET_COMMAND, error.command)
        assertEquals(7_500L, error.timeoutMillis)
        assertEquals(7_500L, testScheduler.currentTime)
    }

    private suspend fun connectedTransport(): FakeByteTransport =
        FakeByteTransport().also { it.connect() }

    private suspend fun queuePrompts(transport: FakeByteTransport, count: Int) {
        repeat(count) { transport.emit("\r\r>".encodeToByteArray()) }
    }
}
