package com.innova.diag.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode 01 decoders against SAE J1979 formulas.
 *
 * "Vector" here means a response string paired with the value the published formula yields for it —
 * the arithmetic is the standard's, the fixtures are hand-built. None of these tests claims to have
 * been observed on a real vehicle; see `docs/ASSUMPTIONS.md` A22.
 */
class Mode01DecodersTest {

    // --- PID 0x0C, engine RPM -------------------------------------------------

    @Test
    fun `410C1A20 decodes to 1672 rpm`() {
        val result = Mode01Decoders.engineRpm("410C1A20").requireSuccess("PID 0C")

        assertEquals(1672.0, result.data, 0.0)
        assertEquals("410C1A20", result.rawHex)
    }

    @Test
    fun `rpm formula is ((A * 256) + B) divided by 4 at both ends of the range`() {
        assertEquals(0.0, Mode01Decoders.engineRpm("410C0000").requireSuccess().data, 0.0)
        assertEquals(16383.75, Mode01Decoders.engineRpm("410CFFFF").requireSuccess().data, 0.0)
    }

    @Test
    fun `rpm decoder tolerates spacing and lowercase hex from the adapter`() {
        assertEquals(1672.0, Mode01Decoders.engineRpm("41 0c 1a 20").requireSuccess().data, 0.0)
    }

    @Test
    fun `a response for a different PID is not silently decoded as rpm`() {
        // 41 0D is vehicle speed; reading its payload as RPM would invent a plausible wrong number.
        Mode01Decoders.engineRpm("410D37").assertMalformed("mode 01 PID 0D payload offered to PID 0C")
    }

    @Test
    fun `an rpm response with the wrong number of data bytes is malformed`() {
        Mode01Decoders.engineRpm("410C1A").assertMalformed("truncated PID 0C")
        Mode01Decoders.engineRpm("410C1A2000").assertMalformed("over-long PID 0C")
    }

    // --- The absence of data is not a measurement -----------------------------

    @Test
    fun `NO DATA decodes to NoResponse and never to zero`() {
        val result = Mode01Decoders.engineRpm("NO DATA")

        assertNull(
            "a silent ECU must not be reported as a measurement",
            result.dataOrNull,
        )
        result.assertNoResponse("the adapter's NO DATA")
    }

    @Test
    fun `every adapter failure string becomes NoResponse, not a value`() {
        val failures = listOf(
            "NO DATA",
            "UNABLE TO CONNECT",
            "BUS ERROR",
            "CAN ERROR",
            "BUFFER FULL",
            "STOPPED",
        )

        for (failure in failures) {
            assertNull("$failure must not decode to a value", Mode01Decoders.engineRpm(failure).dataOrNull)
            assertNull("$failure must not decode to a value", Mode01Decoders.vehicleSpeedKph(failure).dataOrNull)
            assertNull("$failure must not decode to a value", Mode01Decoders.coolantTemperatureCelsius(failure).dataOrNull)
        }
    }

    @Test
    fun `a whitespace-stripped NO DATA is still NoResponse`() {
        // A HEX-normalized response has already lost its spaces, so the guard must match "NODATA" too;
        // otherwise a silent ECU could only be caught by the channel, not by the decoder.
        Mode01Decoders.engineRpm("NODATA").assertNoResponse("hex-normalized NO DATA")
        Mode01Decoders.vehicleSpeedKph("BUSERROR").assertNoResponse("hex-normalized BUS ERROR")
    }

    @Test
    fun `a zero speed is a real measurement and is distinct from silence`() {
        val stationary = Mode01Decoders.vehicleSpeedKph("410D00").requireSuccess("PID 0D")

        assertEquals(0, stationary.data)
    }

    // --- PID 0x0D, vehicle speed ---------------------------------------------

    @Test
    fun `410D37 decodes to 55 km per hour`() {
        assertEquals(55, Mode01Decoders.vehicleSpeedKph("410D37").requireSuccess("PID 0D").data)
        assertEquals(255, Mode01Decoders.vehicleSpeedKph("410DFF").requireSuccess("PID 0D").data)
    }

    @Test
    fun `PID 0D with two data bytes is malformed`() {
        Mode01Decoders.vehicleSpeedKph("410D0000").assertMalformed("over-long PID 0D")
    }

    // --- PID 0x05, coolant temperature ---------------------------------------

    @Test
    fun `410546 decodes to 30 degrees Celsius and the byte offset is honoured at both ends`() {
        assertEquals(30, Mode01Decoders.coolantTemperatureCelsius("410546").requireSuccess("PID 05").data)
        assertEquals(-40, Mode01Decoders.coolantTemperatureCelsius("410500").requireSuccess("PID 05").data)
        assertEquals(215, Mode01Decoders.coolantTemperatureCelsius("4105FF").requireSuccess("PID 05").data)
    }

    @Test
    fun `a negative coolant temperature is data, not an error`() {
        val cold = Mode01Decoders.coolantTemperatureCelsius("410500").requireSuccess("PID 05")

        assertTrue("cold engines report below zero Celsius", cold.data < 0)
    }

    // --- Malformed input ------------------------------------------------------

    @Test
    fun `non-hex payload is malformed rather than reinterpreted`() {
        Mode01Decoders.engineRpm("41ZZ1A20").assertMalformed("non-hex characters")
        Mode01Decoders.engineRpm("41 0C 1A2").assertMalformed("odd number of hex digits")
        Mode01Decoders.engineRpm("").assertNoResponse("an empty response")
        Mode01Decoders.engineRpm("?").assertMalformed("the adapter's query-rejected marker")
    }

    @Test
    fun `a negative response with the wrong code is malformed, and an unsupported PID is Unsupported`() {
        // 7F 01 12: "service 01, PID not supported". 7F 01 22: conditions not correct — not the
        // same claim, and it must not be reported as a missing PID.
        assertTrue(
            Mode01Decoders.engineRpm("7F0112") is DiagnosticResult.Unsupported,
        )
        Mode01Decoders.engineRpm("7F0122").assertMalformed("negative response with response code 0x22")
    }
}
