package com.innova.diag.platform.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import com.innova.diag.core.transport.TransportState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests the RFCOMM transport against **mocked Android Bluetooth sockets**.
 *
 * The socket is a MockK double backed by a scripted stream, so the blocking `read()` contract can be
 * reproduced exactly: block until bytes arrive, return `-1` on an orderly close, or throw when the
 * link breaks. That is what makes "disconnect ends in DISCONNECTED, not ERROR" a real assertion
 * rather than a hope.
 */
class BluetoothClassicTransportTest {

    private val socket = mockk<BluetoothSocket>(relaxed = true)
    private val device = mockk<BluetoothDevice>(relaxed = true)
    private val adapter = mockk<BluetoothAdapter>(relaxed = true)
    private val permissions = FakeBluetoothPermissionStateProvider()
    private val input = ScriptedInputStream()
    private val output = RecordingOutputStream()
    private val readScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Before
    fun setUp() {
        every { socket.inputStream } returns input
        // Closing a real RFCOMM socket unblocks a pending read with an IOException; mirror that.
        every { socket.close() } answers {
            input.failWith(IOException("socket closed"))
            Unit
        }
        every { device.createRfcommSocketToServiceRecord(any()) } returns socket
        every { adapter.getRemoteDevice(TEST_DEVICE_ADDRESS) } returns device
        every { adapter.isEnabled } returns true
    }

    @After
    fun tearDown() {
        // Unblock any read still parked on the scripted stream, then drop the read scope.
        runCatching { input.failWith(IOException("test teardown")) }
        readScope.cancel()
    }

    // --- Disconnect ---------------------------------------------------------

    @Test
    fun `socket disconnect lands in DISCONNECTED and never in ERROR`() = runBlocking {
        val transport = newTransport()
        transport.selectDevice(TEST_DEVICE_ADDRESS)

        transport.connect()
        assertEquals(TransportState.CONNECTED, transport.state().value)
        assertTrue("read loop never reached the socket", input.awaitReadEntered())

        transport.disconnect()

        assertEquals(TransportState.DISCONNECTED, transport.state().value)
        verify { socket.close() }
        verify { socket.inputStream }

        // The read loop was unblocked by the close and must not publish ERROR afterwards.
        assertTrue("read failure was never delivered to the loop", input.awaitFailureDelivered())
        assertEquals(
            "a locally initiated disconnect must not be reported as an error",
            TransportState.DISCONNECTED,
            transport.state().value,
        )
    }

    @Test
    fun `disconnect without a connection is harmless`() = runBlocking {
        val transport = newTransport()

        transport.disconnect()

        assertEquals(TransportState.DISCONNECTED, transport.state().value)
        verify(exactly = 0) { socket.close() }
    }

    // --- No commands on connect --------------------------------------------

    @Test
    fun `connect opens the socket without sending a single byte`() = runBlocking {
        val transport = newTransport()
        transport.selectDevice(TEST_DEVICE_ADDRESS)

        transport.connect()

        assertEquals(TransportState.CONNECTED, transport.state().value)
        // No ATZ/ATE0/ATSP... and no writes of any kind: the output stream is never even touched.
        verify(exactly = 0) { socket.outputStream }
        assertTrue(output.writes.isEmpty())
        verify(exactly = 1) { device.createRfcommSocketToServiceRecord(BluetoothClassicTransport.SPP_SERVICE_UUID) }
    }

    // --- Fail-closed refusals ----------------------------------------------

    @Test
    fun `connect is refused without BLUETOOTH_CONNECT`() = runBlocking {
        permissions.current = BluetoothPermissionState(connectGranted = false, scanGranted = true)
        val transport = newTransport()
        transport.selectDevice(TEST_DEVICE_ADDRESS)

        val refusal = runCatching { transport.connect() }.exceptionOrNull()

        assertTrue(refusal is BluetoothTransportException.PermissionDenied)
        assertEquals(TransportState.ERROR(refusal as Throwable), transport.state().value)
        verify(exactly = 0) { adapter.getRemoteDevice(any<String>()) }
        verify(exactly = 0) { device.createRfcommSocketToServiceRecord(any()) }
        verify(exactly = 0) { socket.connect() }
    }

    @Test
    fun `connect is refused when no adapter was selected`() = runBlocking {
        val transport = newTransport()

        val refusal = runCatching { transport.connect() }.exceptionOrNull()

        assertTrue(refusal is BluetoothTransportException.NoDeviceSelected)
        // Nothing about the vehicle or the radio is touched before a choice exists.
        verify(exactly = 0) { adapter.isEnabled }
        verify(exactly = 0) { adapter.getRemoteDevice(any<String>()) }
    }

    @Test
    fun `connect is refused when bluetooth is off`() = runBlocking {
        every { adapter.isEnabled } returns false
        val transport = newTransport()
        transport.selectDevice(TEST_DEVICE_ADDRESS)

        val refusal = runCatching { transport.connect() }.exceptionOrNull()

        assertTrue(refusal is BluetoothTransportException.Disabled)
        verify(exactly = 0) { adapter.getRemoteDevice(any<String>()) }
    }

    // --- Read loop ----------------------------------------------------------

    @Test
    fun `read loop emits fragments and an orderly EOF is a disconnect`() = runBlocking {
        val transport = newTransport()
        transport.selectDevice(TEST_DEVICE_ADDRESS)
        transport.connect()

        val payload = "41 00\r".encodeToByteArray()
        input.send(payload)

        val fragment = withTimeout(READ_TIMEOUT_MILLIS) { transport.incomingBytes().first() }
        assertArrayEquals(payload, fragment)

        input.endOfStream()

        val state = withTimeout(READ_TIMEOUT_MILLIS) {
            transport.state().first { it == TransportState.DISCONNECTED }
        }
        assertEquals(TransportState.DISCONNECTED, state)
    }

    @Test
    fun `an unexpected read failure is reported as ERROR`() = runBlocking {
        val transport = newTransport()
        transport.selectDevice(TEST_DEVICE_ADDRESS)
        transport.connect()

        input.failWith(IOException("rfcomm link broken"))

        val state = withTimeout(READ_TIMEOUT_MILLIS) {
            transport.state().first { it is TransportState.ERROR }
        }
        assertTrue(state is TransportState.ERROR)
        assertTrue((state as TransportState.ERROR).throwable is IOException)
    }

    // --- Writes -------------------------------------------------------------

    @Test
    fun `write is refused unless the link is connected`() = runBlocking {
        val transport = newTransport()

        val failure = runCatching { transport.write(byteArrayOf(0x41)) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertTrue(output.writes.isEmpty())
    }

    @Test
    fun `write sends bytes on the injected io dispatcher`() = runBlocking {
        val executor = Executors.newFixedThreadPool(2) { runnable -> Thread(runnable, "test-io") }
        val dispatcher = executor.asCoroutineDispatcher()
        every { socket.outputStream } returns output
        val transport = newTransport(ioDispatcher = dispatcher)
        try {
            transport.selectDevice(TEST_DEVICE_ADDRESS)
            transport.connect()

            val payload = "0100\r".encodeToByteArray()
            transport.write(payload)

            assertEquals(1, output.writes.size)
            assertArrayEquals(payload, output.writes.single())
            assertTrue(
                "socket writes must run on the injected dispatcher, never the caller thread",
                output.writeThreads.single().startsWith("test-io"),
            )
        } finally {
            // Close the link while the dispatcher is still alive, and wait for the read loop to
            // observe the close, so no coroutine is left parked on a shut-down executor.
            transport.disconnect()
            input.awaitFailureDelivered()
            executor.shutdownNow()
        }
    }

    private fun newTransport(ioDispatcher: CoroutineDispatcher = Dispatchers.IO): BluetoothClassicTransport =
        BluetoothClassicTransport(
            adapter = adapter,
            permissionState = permissions,
            ioDispatcher = ioDispatcher,
            scope = readScope,
        )

    private companion object {
        const val READ_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * A blocking `InputStream` that behaves like a socket stream: `read` parks until an event arrives.
 */
private class ScriptedInputStream : InputStream() {

    private sealed interface Event {
        data class Bytes(val value: ByteArray) : Event
        data class Failure(val cause: IOException) : Event
        data object EndOfStream : Event
    }

    private val events = LinkedBlockingQueue<Event>()
    private val readEntered = CountDownLatch(1)
    private val failureDelivered = CountDownLatch(1)

    fun send(bytes: ByteArray) {
        events.put(Event.Bytes(bytes.copyOf()))
    }

    fun failWith(cause: IOException) {
        events.put(Event.Failure(cause))
    }

    fun endOfStream() {
        events.put(Event.EndOfStream)
    }

    fun awaitReadEntered(): Boolean = readEntered.await(5, TimeUnit.SECONDS)

    fun awaitFailureDelivered(): Boolean = failureDelivered.await(5, TimeUnit.SECONDS)

    override fun read(): Int = throw IOException("this fake only supports read(byte[], int, int)")

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        readEntered.countDown()
        return when (val event = events.take()) {
            is Event.Bytes -> {
                val count = minOf(len, event.value.size)
                event.value.copyInto(b, off, 0, count)
                count
            }
            is Event.Failure -> {
                failureDelivered.countDown()
                throw event.cause
            }
            Event.EndOfStream -> -1
        }
    }
}

/** Records what was written and on which thread, so dispatcher enforcement is assertable. */
private class RecordingOutputStream : OutputStream() {

    private val recorded = CopyOnWriteArrayList<ByteArray>()
    private val threadNames = CopyOnWriteArrayList<String>()

    val writes: List<ByteArray>
        get() = recorded.toList()

    val writeThreads: List<String>
        get() = threadNames.toList()

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray) = write(b, 0, b.size)

    override fun write(b: ByteArray, off: Int, len: Int) {
        threadNames += Thread.currentThread().name
        recorded += b.copyOfRange(off, off + len)
    }

    override fun flush() = Unit
}
