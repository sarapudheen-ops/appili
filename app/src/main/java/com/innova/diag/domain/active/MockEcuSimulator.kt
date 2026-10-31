package com.innova.diag.domain.active

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.TransportState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A bench ECU that answers UDS `0x2F` (InputOutputControlByIdentifier) and `0x31` (RoutineControl)
 * over the same line-oriented, prompt-terminated link an ELM327 present.
 *
 * It exists so the active-test state machine can be exercised end to end — bytes in, bytes out, a link
 * that can drop — without a vehicle, a dongle or a Bluetooth stack. It is **not** an emulator of any
 * real module: the responses are the ISO 14229 *shapes* (positive response = SID + 0x40, negative
 * response = `7F <SID> <NRC>`), and it is explicitly **not** a [com.innova.diag.core.transport.PhysicalTransport],
 * so the hardware lock in [ActiveTestController] does not fire.
 *
 * ### What it answers
 * | Request | Response | Meaning |
 * | --- | --- | --- |
 * | `2F <DID> <option> [value]` | `6F <DID> <option> [value]` | echoes the control option record it was given |
 * | `31 01 <routine>` | `71 01 <routine>` | routine started |
 * | `31 02 <routine>` | `71 02 <routine>` | routine stopped |
 * | any other service | `7F <SID> 11` | serviceNotSupported |
 * | a malformed `2F`/`31` | `7F <SID> 13` | incorrectMessageLength |
 * | not hex at all | `?` | what a real adapter prints for a command it does not know |
 *
 * Every answer is terminated with the `>` prompt, because that is what the layer above frames on.
 *
 * ### Scripting a failure
 * [script] overrides the answer for one exact request — the way to make the ECU refuse an activation,
 * stay silent ([SILENT], which produces a timeout above) or answer with something unusable.
 *
 * @param responseDelayMillis delay before each answer is emitted; `0` answers during [write], which is
 *   what a same-tick bench test wants. A positive value lets a test observe the in-flight window.
 */
class MockEcuSimulator(
    private val responseDelayMillis: Long = 0L,
) : ByteTransport {

    private val currentState = MutableStateFlow<TransportState>(TransportState.DISCONNECTED)
    private val inbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val receiveBuffer = StringBuilder()
    private val commandLog = mutableListOf<String>()
    private val overrides = mutableMapOf<String, String>()

    /** Every complete request the mock received, normalized (uppercase, no spaces), in order. */
    val frames: List<String> get() = synchronized(commandLog) { commandLog.toList() }

    /** The most recent request, or `null` when nothing has been sent. */
    val lastFrame: String? get() = frames.lastOrNull()

    override fun state(): StateFlow<TransportState> = currentState.asStateFlow()

    override fun incomingBytes(): Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun connect() {
        currentState.value = TransportState.CONNECTED
    }

    override suspend fun disconnect() {
        currentState.value = TransportState.DISCONNECTED
    }

    /**
     * Drops the link the way a Bluetooth connection drops: the transport state changes, nothing is
     * negotiated, nothing is flushed politely.
     *
     * Non-suspending so a test (or the debug UI) can pull the plug from anywhere.
     */
    fun simulateDisconnect() {
        currentState.value = TransportState.DISCONNECTED
    }

    /** Fails the link with a cause, for the `ERROR` branch of the abort listener. */
    fun simulateLinkError(cause: Throwable) {
        currentState.value = TransportState.ERROR(cause)
    }

    /** Replaces the answer for [command] (normalized on both sides). [SILENT] means "say nothing". */
    fun script(command: String, response: String) {
        synchronized(overrides) { overrides[normalize(command)] = response }
    }

    /** Forgets every scripted answer. */
    fun clearScript() {
        synchronized(overrides) { overrides.clear() }
    }

    override suspend fun write(data: ByteArray) {
        check(currentState.value == TransportState.CONNECTED) { "mock ECU is not connected" }
        receiveBuffer.append(data.decodeToString())
        while (true) {
            val terminator = receiveBuffer.indexOfFirst { character -> character == '\r' || character == '\n' }
            if (terminator < 0) return
            val line = normalize(receiveBuffer.substring(0, terminator))
            receiveBuffer.delete(0, terminator + 1)
            if (line.isEmpty()) continue
            synchronized(commandLog) { commandLog += line }
            answer(line)
        }
    }

    private suspend fun answer(command: String) {
        val scripted = synchronized(overrides) { overrides[command] }
        if (scripted == SILENT) return
        if (responseDelayMillis > 0L) delay(responseDelayMillis)
        val response = scripted ?: defaultResponse(command)
        inbound.send("$response\r\r>".toByteArray(Charsets.US_ASCII))
    }

    private fun defaultResponse(command: String): String {
        if (command.length < 2 || command.any { it !in HEX_DIGITS }) return "?"
        return when (command.substring(0, 2)) {
            IO_CONTROL -> ioControl(command)
            ROUTINE_CONTROL -> routineControl(command)
            else -> negative(command.substring(0, 2), NRC_SERVICE_NOT_SUPPORTED)
        }
    }

    /** `2F <DID:4> <option:2> [value...]` → `6F <same payload>`, or a length error. */
    private fun ioControl(command: String): String {
        if (command.length < 8 || command.length % 2 != 0) {
            return negative("2F", NRC_INCORRECT_LENGTH)
        }
        val option = command.substring(6, 8)
        val expectedLength = if (option == RETURN_CONTROL_OPTION) 8 else 10
        if (command.length != expectedLength) return negative("2F", NRC_INCORRECT_LENGTH)
        return POSITIVE_IO_CONTROL + command.substring(2)
    }

    /** `31 <sub:2> <routine:4>` → `71 <same payload>`, or a length error. */
    private fun routineControl(command: String): String {
        if (command.length != 8) return negative("31", NRC_INCORRECT_LENGTH)
        return POSITIVE_ROUTINE_CONTROL + command.substring(2)
    }

    private fun negative(service: String, code: String): String = "7F$service$code"

    private fun normalize(text: String): String =
        text.filterNot { it.isWhitespace() }.uppercase()

    companion object {

        /** Scripted answer meaning "emit nothing": the layer above sees a timeout, as it should. */
        const val SILENT: String = "<silent>"

        private const val IO_CONTROL = "2F"
        private const val ROUTINE_CONTROL = "31"
        private const val POSITIVE_IO_CONTROL = "6F"
        private const val POSITIVE_ROUTINE_CONTROL = "71"

        /** UDS control option `0x00`: ReturnControlToECU. */
        private const val RETURN_CONTROL_OPTION = "00"

        /** UDS NRC `0x11`: serviceNotSupported. */
        private const val NRC_SERVICE_NOT_SUPPORTED = "11"

        /** UDS NRC `0x13`: incorrectMessageLengthOrInvalidFormat. */
        private const val NRC_INCORRECT_LENGTH = "13"

        private const val HEX_DIGITS = "0123456789ABCDEF"
    }
}
