package com.innova.diag.platform.demo

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.TransportState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A scripted adapter for emulator runs — **debug source set only**.
 *
 * It answers with a fixed transcript chosen to exercise every honest state the UI must render:
 *
 * | Request | Answer | What the screen shows |
 * |---|---|---|
 * | `0100` | `4100B7180000` | RPM + speed supported, **coolant not supported** |
 * | `010C` | `410C1A20` | `1672.0 RPM` |
 * | `010D` | `410D37` | `55 km/h` |
 * | `03` | `430201330000` | `P0133` |
 * | `07` | `NO DATA` | `No Response` (amber), never `0` |
 * | `0902` | 17 ASCII bytes | the VIN |
 *
 * Because the supported-PID mask excludes PID `0x05`, the coolant tile is reported as
 * `Not Supported` **without a request being sent** — which is exactly the behaviour worth eyeballing.
 */
class DemoByteTransport : ByteTransport {

    private val currentState = MutableStateFlow<TransportState>(TransportState.DISCONNECTED)
    private val inbound = Channel<ByteArray>(capacity = Channel.UNLIMITED)

    override suspend fun connect() {
        currentState.value = TransportState.CONNECTED
    }

    override suspend fun disconnect() {
        currentState.value = TransportState.DISCONNECTED
    }

    override fun incomingBytes(): Flow<ByteArray> = inbound.receiveAsFlow()

    override suspend fun write(data: ByteArray) {
        check(currentState.value == TransportState.CONNECTED) { "demo transport is not connected" }
        val command = data.decodeToString().trim()
        inbound.send(transcript[command.uppercase()]?.encodeToByteArray() ?: REJECTED.encodeToByteArray())
    }

    override fun state(): StateFlow<TransportState> = currentState.asStateFlow()

    private companion object {
        const val REJECTED = "?\r\r>"

        val transcript: Map<String, String> = mapOf(
            "ATZ" to "\r\rELM327 v1.5\r\r>",
            "ATI" to "ELM327 v1.5\r\r>",
            "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)\r\r>",
            "ATE0" to "OK\r\r>",
            "ATL0" to "OK\r\r>",
            "ATS0" to "OK\r\r>",
            "ATSP0" to "OK\r\r>",
            "0100" to "4100B7180000\r\r>",
            "010C" to "410C1A20\r\r>",
            "010D" to "410D37\r\r>",
            "03" to "430201330000\r\r>",
            "07" to "NO DATA\r\r>",
            "0902" to "490201314D3847444D3941584B50303432373838\r\r>",
        )
    }
}
