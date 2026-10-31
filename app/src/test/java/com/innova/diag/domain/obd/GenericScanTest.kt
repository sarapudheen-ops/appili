package com.innova.diag.domain.obd

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The generic scan against a scripted vehicle.
 *
 * The behaviour worth pinning is the *silence*: a PID the bitmask excludes is never requested, an
 * unanswered mask makes support unknown rather than empty, and one bad section does not take the
 * others down with it.
 */
class GenericScanTest {

    @Test
    fun `a PID the bitmask excludes is reported as unsupported and never requested`() = runTest {
        val port = TranscriptPort(
            mapOf(
                // A = 0xB7: PID 0x05 (coolant) is NOT in the mask; B = 0x18: 0x0C and 0x0D are.
                "0100" to ok("4100B7180000"),
                "010C" to ok("410C1A20"),
                "010D" to ok("410D37"),
                "03" to ok("430201330000"),
                "07" to ok("NO DATA"),
                "0902" to ok("490201314D3847444D3941584B50303432373838"),
            ),
        )

        val report = GenericScanService(ObdService(port)).scan()

        assertEquals(
            "the coolant PID must not appear on the wire",
            listOf("0100", "010C", "010D", "03", "07", "0902"),
            port.commands,
        )

        val coolant = report.readings.first { reading -> reading.definition.pid == 0x05 }
        assertFalse(coolant.requested)
        assertTrue(coolant.result is DiagnosticResult.Unsupported)

        val rpm = report.readings.first { reading -> reading.definition.pid == 0x0C }
        assertTrue(rpm.requested)
        assertEquals(1672.0, rpm.result.dataOrNull!!, 0.0)

        assertEquals(listOf("P0133"), report.storedDtcs.dataOrNull?.map { code -> code.code })
        assertTrue(
            "NO DATA for pending codes must stay NoResponse, not become an empty list",
            report.pendingDtcs is DiagnosticResult.NoResponse,
        )
        assertEquals("1M8GDM9AXKP042788", report.vin.dataOrNull?.value)
    }

    @Test
    fun `the continuation bit drives the second bank`() = runTest {
        val port = TranscriptPort(
            mapOf(
                "0100" to ok("4100BE1FA813"),
                "0120" to ok("412000000000"),
                "0105" to ok("410546"),
                "010C" to ok("410C1A20"),
                "010D" to ok("410D00"),
                "03" to ok("4300"),
                "07" to ok("4700"),
                "0902" to ok("NO DATA"),
            ),
        )

        val report = GenericScanService(ObdService(port)).scan()

        assertTrue("bank 0x20 must be asked", port.commands.contains("0120"))
        assertFalse("bank 0x40 must not be asked", port.commands.contains("0140"))
        assertEquals(2, report.supported.banks.size)
        assertEquals(true, report.supported.support(0x0C))
        assertEquals(false, report.supported.support(0x02))
    }

    @Test
    fun `an unanswered mask means support is unknown, not empty`() = runTest {
        val port = TranscriptPort(
            mapOf(
                "010C" to ok("410C1A20"),
                "010D" to ok("410D00"),
                "0105" to ok("410546"),
            ),
        )

        val report = GenericScanService(ObdService(port)).scan()

        assertFalse(report.supported.anyAnswered)
        assertNull("unknown support is null, not false", report.supported.support(0x0C))
        assertTrue(report.supported.knownPids.isEmpty())
        assertTrue("with support unknown the PIDs are still polled", port.commands.contains("010C"))
        assertTrue(
            report.supported.banks.all { bank -> bank.result is DiagnosticResult.NoResponse },
        )
    }

    @Test
    fun `live polling reuses the mask instead of re-reading it`() = runTest {
        val port = TranscriptPort(
            mapOf(
                "0100" to ok("4100B7180000"),
                "010C" to ok("410C1A20"),
                "010D" to ok("410D37"),
            ),
        )
        val service = GenericScanService(ObdService(port))

        val supported = service.readSupported()
        service.readReadings(supported)
        service.readReadings(supported)

        assertEquals("the mask is read once per polling run", 1, port.commands.count { it == "0100" })
        assertEquals(2, port.commands.count { it == "010C" })
    }

    @Test
    fun `a NO DATA reading does not stop the DTC or VIN reads`() = runTest {
        val port = TranscriptPort(
            mapOf(
                "0100" to ok("4100B7180000"),
                "010C" to ok("NO DATA"),
                "010D" to ok("410D37"),
                "03" to ok("430201330000"),
                "07" to ok("47"),
                "0902" to ok("490201314D3847444D3941584B50303432373838"),
            ),
        )

        val report = GenericScanService(ObdService(port)).scan()

        val rpm = report.readings.first { reading -> reading.definition.pid == 0x0C }
        assertTrue(rpm.result is DiagnosticResult.NoResponse)
        assertEquals(listOf("P0133"), report.storedDtcs.dataOrNull?.map { code -> code.code })
        assertEquals(emptyList<String>(), report.pendingDtcs.dataOrNull?.map { code -> code.code })
        assertEquals("1M8GDM9AXKP042788", report.vin.dataOrNull?.value)
    }

    private fun ok(raw: String): DiagnosticResult<String> = DiagnosticResult.Success(raw, raw)
}

/** Answers from a fixed table and records what was asked. */
private class TranscriptPort(
    private val responses: Map<String, DiagnosticResult<String>>,
) : ObdCommandPort {

    val commands = mutableListOf<String>()

    override suspend fun execute(command: String): DiagnosticResult<String> {
        commands += command
        return responses[command] ?: DiagnosticResult.NoResponse
    }
}
