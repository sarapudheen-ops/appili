package com.innova.diag.adapter.elm

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.TransportState
import com.innova.diag.core.transport.fake.FakeByteTransport
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.requireSuccess
import com.innova.diag.domain.obd.variantName
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seam between the adapter and the domain: a real [ElmChannel] over a fake transport, mapped onto
 * [DiagnosticResult].
 *
 * These are the cases where a wrong mapping would be invisible until a vehicle is on the lift: a
 * timeout reported as silence, a `NO DATA` reported as an empty reading, or a rejected command
 * reported as a vehicle fault.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ElmObdCommandPortTest {

    @Test
    fun `a normal answer becomes Success carrying the normalized text`() = runTest {
        val transport = connectedTransport()
        transport.emit("41 0C 1A 20\r\r>".encodeToByteArray())
        val port = ElmObdCommandPort(ElmChannel(transport))

        val result = port.execute("010C")

        assertEquals("410C1A20", result.requireSuccess("a mode 01 answer").data)
        assertEquals("010C\r", transport.writes.single().decodeToString())
    }

    @Test
    fun `NO DATA becomes NoResponse, not an empty reading`() = runTest {
        val transport = connectedTransport()
        transport.emit("NO DATA\r\r>".encodeToByteArray())

        val result = ElmObdCommandPort(ElmChannel(transport)).execute("010C")

        assertTrue("expected NoResponse, got ${result.variantName()}", result is DiagnosticResult.NoResponse)
    }

    @Test
    fun `an unanswered prompt becomes NoResponse`() = runTest {
        val transport = connectedTransport()
        transport.emit("\r\r>".encodeToByteArray())

        val result = ElmObdCommandPort(ElmChannel(transport)).execute("010C")

        assertTrue("a prompt is not data: ${result.variantName()}", result is DiagnosticResult.NoResponse)
    }

    @Test
    fun `a rejected command becomes Malformed and keeps the adapter text`() = runTest {
        val transport = connectedTransport()
        transport.emit("?\r\r>".encodeToByteArray())

        val result = ElmObdCommandPort(ElmChannel(transport)).execute("010Z")

        val malformed = result as? DiagnosticResult.Malformed
        assertTrue("expected Malformed, got ${result.variantName()}", malformed != null)
        assertEquals("?", malformed?.rawHex)
    }

    @Test
    fun `a timeout stays a Timeout and is never reported as silence`() = runTest {
        val transport = connectedTransport()
        val port = ElmObdCommandPort(ElmChannel(transport, timeoutMillis = 200))

        val result = port.execute("010C")

        assertSame(DiagnosticResult.Timeout, result)
        assertTrue("a timeout is not a NoResponse", result !is DiagnosticResult.NoResponse)
    }

    @Test
    fun `a link that ends mid-exchange becomes NoResponse, not Timeout`() = runTest {
        val port = ElmObdCommandPort(ElmChannel(PortEndedStreamTransport()))

        val result = port.execute("010C")

        assertTrue("expected NoResponse, got ${result.variantName()}", result is DiagnosticResult.NoResponse)
        assertTrue("nothing is being waited for any more", result !is DiagnosticResult.Timeout)
    }

    private suspend fun connectedTransport(): FakeByteTransport =
        FakeByteTransport().also { it.connect() }
}

/** A link whose byte stream has already finished, used to prove `LinkClosed` is not read as silence. */
private class PortEndedStreamTransport : ByteTransport {

    private val currentState = MutableStateFlow<TransportState>(TransportState.CONNECTED)

    override suspend fun connect() = Unit

    override suspend fun disconnect() {
        currentState.value = TransportState.DISCONNECTED
    }

    override fun incomingBytes(): Flow<ByteArray> = emptyFlow()

    override suspend fun write(data: ByteArray) = Unit

    override fun state(): StateFlow<TransportState> = currentState
}
