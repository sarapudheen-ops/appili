package com.innova.diag.domain.obd

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The request layer: which command goes out, and what the caller is told when it comes back.
 *
 * A fake [ObdCommandPort] stands in for the adapter, so these tests say nothing about sockets — they
 * pin the command strings (the read-only surface) and the rule that a failure is never upgraded into
 * a value.
 */
class ObdServiceTest {

    @Test
    fun `engine rpm is requested as 010C and decoded from the reply`() = runTest {
        val port = RecordingPort { DiagnosticResult.Success("410C1A20", "410C1A20") }

        val result = ObdService(port).engineRpm()

        assertEquals(1672.0, result.requireSuccess("rpm").data, 0.0)
        assertEquals(listOf("010C"), port.commands)
    }

    @Test
    fun `speed and coolant use their own PIDs`() = runTest {
        val speed = ObdService(RecordingPort { DiagnosticResult.Success("410D37", "410D37") }).vehicleSpeedKph()
        val coolant = ObdService(RecordingPort { DiagnosticResult.Success("410546", "410546") })
            .coolantTemperatureCelsius()

        assertEquals(55, speed.requireSuccess("speed").data)
        assertEquals(30, coolant.requireSuccess("coolant").data)
    }

    @Test
    fun `each request sends exactly the one command it needs`() = runTest {
        // Every method answers "nothing came back", which keeps the responses out of the way: this
        // test is about the commands on the wire.
        val port = RecordingPort { DiagnosticResult.NoResponse }
        val service = ObdService(port)

        service.engineRpm()
        service.vehicleSpeedKph()
        service.coolantTemperatureCelsius()
        service.supportedPids(Mode01Pids.SUPPORTED_PIDS_00)
        service.supportedPids(Mode01Pids.SUPPORTED_PIDS_20)
        service.storedDtcs()
        service.pendingDtcs()
        service.vin()

        assertEquals(
            listOf("010C", "010D", "0105", "0100", "0120", "03", "07", "0902"),
            port.commands,
        )
    }

    @Test
    fun `the engine can only send read requests`() = runTest {
        val port = RecordingPort { DiagnosticResult.NoResponse }
        val service = ObdService(port)

        service.engineRpm()
        service.vehicleSpeedKph()
        service.coolantTemperatureCelsius()
        service.supportedPids(0x00)
        service.supportedPids(0x20)
        service.supportedPids(0x40)
        service.supportedPids(0x60)
        service.storedDtcs()
        service.pendingDtcs()
        service.vin()

        val readOnlyPrefixes = listOf("01", "03", "07", "09")
        assertTrue(
            "every command must be a read request, got ${port.commands}",
            port.commands.all { command -> readOnlyPrefixes.any { command.startsWith(it) } },
        )
        assertTrue(
            "mode 04 (clear DTCs) and modes 21/22 must be unreachable, got ${port.commands}",
            port.commands.none { it.startsWith("04") || it.startsWith("21") || it.startsWith("22") },
        )
    }

    @Test
    fun `a link timeout is passed through untouched`() = runTest {
        val port = RecordingPort { DiagnosticResult.Timeout }

        val result = ObdService(port).engineRpm()

        assertSame(DiagnosticResult.Timeout, result)
    }

    @Test
    fun `a malformed response keeps its raw text`() = runTest {
        val port = RecordingPort { DiagnosticResult.Malformed("41 0C 1A") }

        val result = ObdService(port).engineRpm()

        assertEquals("41 0C 1A", (result as DiagnosticResult.Malformed).rawHex)
    }

    @Test
    fun `an adapter NO DATA answer is NoResponse, not a zero`() = runTest {
        // Belt and braces: the channel already maps NO DATA to a failure, but a replayed transcript
        // can hand the text straight to the decoder, and both paths must land on NoResponse.
        val port = RecordingPort { DiagnosticResult.Success("NO DATA", "NO DATA") }

        val result = ObdService(port).engineRpm()

        result.assertNoResponse("NO DATA handed over as text")
    }

    @Test
    fun `stored and pending DTCs decode through their own modes`() = runTest {
        val port = RecordingPort { command ->
            when (command) {
                "03" -> DiagnosticResult.Success("430201330000", "430201330000")
                "07" -> DiagnosticResult.Success("4700", "4700")
                else -> error("unexpected command $command")
            }
        }
        val service = ObdService(port)

        assertEquals(listOf("P0133"), service.storedDtcs().requireSuccess("mode 03").data.map { it.code })
        assertTrue(service.pendingDtcs().requireSuccess("mode 07").data.isEmpty())
    }

    @Test
    fun `the VIN is requested as 0902`() = runTest {
        val vin = "1M8GDM9AXKP042788"
        val port = RecordingPort { DiagnosticResult.Success("490201" + vin.asciiHex(), "payload") }

        val result = ObdService(port).vin()

        assertEquals(vin, result.requireSuccess("VIN").data.value)
        assertEquals(listOf("0902"), port.commands)
    }

    @Test
    fun `an unsupported bank index is rejected before anything is sent`() {
        val port = RecordingPort { DiagnosticResult.NoResponse }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { ObdService(port).supportedPids(0x04) }
        }
        assertTrue("no request may reach a vehicle for an invalid bank", port.commands.isEmpty())
    }

    @Test
    fun `mode 01 requests are built from the PID byte`() {
        assertEquals("0100", ObdRequests.mode01(0x00))
        assertEquals("0105", ObdRequests.mode01(0x05))
        assertEquals("010C", ObdRequests.mode01(0x0C))
        assertEquals("01FF", ObdRequests.mode01(0xFF))
        assertThrows(IllegalArgumentException::class.java) { ObdRequests.mode01(0x100) }
    }
}

/** Records the commands the service sends and answers them with [respond]. */
private class RecordingPort(
    private val respond: (String) -> DiagnosticResult<String>,
) : ObdCommandPort {

    val commands: MutableList<String> = mutableListOf()

    override suspend fun execute(command: String): DiagnosticResult<String> {
        commands += command
        return respond(command)
    }
}
