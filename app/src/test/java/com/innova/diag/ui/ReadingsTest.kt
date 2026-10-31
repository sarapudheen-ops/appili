package com.innova.diag.ui

import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.DiagnosticTroubleCode
import com.innova.diag.domain.obd.DtcSystem
import com.innova.diag.domain.obd.GenericPids
import com.innova.diag.domain.obd.PidDefinition
import com.innova.diag.domain.obd.Vin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule this file exists for: **a missing reading is never rendered as `0`.**
 *
 * Each failure variant gets its own words and its own tone, and a genuine zero keeps the value tone —
 * the two must never collide.
 */
class ReadingsTest {

    private val speed: PidDefinition = GenericPids.VEHICLE_SPEED
    private val rpm: PidDefinition = GenericPids.ENGINE_RPM

    @Test
    fun `an answered PID renders its value and unit`() {
        val view = Readings.pid(rpm, DiagnosticResult.Success(1672.0, "410C1A20"))

        assertEquals("1672.0 RPM", view.text)
        assertEquals(ReadingTone.VALUE, view.tone)
        assertTrue("raw bytes should be visible: ${view.detail}", view.detail!!.contains("410C1A20"))
    }

    @Test
    fun `a genuine zero is a value, not a failure`() {
        val view = Readings.pid(speed, DiagnosticResult.Success(0.0, "410D00"))

        assertEquals("0 km/h", view.text)
        assertEquals(ReadingTone.VALUE, view.tone)
    }

    @Test
    fun `no response is amber and never zero`() {
        val view = Readings.pid(speed, DiagnosticResult.NoResponse)

        assertEquals("No Response", view.text)
        assertEquals(ReadingTone.WARNING, view.tone)
        assertFalse("must not start with a number", view.text.trim().startsWith("0"))
    }

    @Test
    fun `unsupported is grey and says Not Supported`() {
        val view = Readings.pid(speed, DiagnosticResult.Unsupported)

        assertEquals("Not Supported", view.text)
        assertEquals(ReadingTone.MUTED, view.tone)
    }

    @Test
    fun `timeout is amber and distinct from no response`() {
        val view = Readings.pid(speed, DiagnosticResult.Timeout)

        assertEquals("Timeout", view.text)
        assertEquals(ReadingTone.WARNING, view.tone)
        assertNotEquals(Readings.pid(speed, DiagnosticResult.NoResponse).text, view.text)
    }

    @Test
    fun `malformed data is amber and keeps the raw text`() {
        val view = Readings.pid(speed, DiagnosticResult.Malformed("41 0D ZZ"))

        assertEquals("Unreadable data", view.text)
        assertEquals(ReadingTone.WARNING, view.tone)
        assertEquals("41 0D ZZ", view.detail)
    }

    @Test
    fun `no failure state of any PID can ever read as a number`() {
        val failures: List<DiagnosticResult<Double>> = listOf(
            DiagnosticResult.NoResponse,
            DiagnosticResult.Timeout,
            DiagnosticResult.Unsupported,
            DiagnosticResult.Malformed("41ZZ"),
        )

        for (definition in GenericPids.ALL) {
            for (failure in failures) {
                val view = Readings.pid(definition, failure)

                assertNotEquals(
                    "${definition.label} must not be a value when it is ${view.text}",
                    ReadingTone.VALUE,
                    view.tone,
                )
                assertFalse(
                    "${definition.label} rendered \"${view.text}\" for a failure",
                    view.text.trim().startsWith("0"),
                )
            }
        }
    }

    @Test
    fun `stored codes render as codes and an empty answer is None reported`() {
        val withCodes = Readings.dtcs(
            DiagnosticResult.Success(
                listOf(DiagnosticTroubleCode(DtcSystem.POWERTRAIN, "0133")),
                "430201330000",
            ),
        )
        val empty = Readings.dtcs(DiagnosticResult.Success(emptyList(), "4300"))

        assertEquals("P0133", withCodes.text)
        assertEquals(ReadingTone.VALUE, withCodes.tone)
        assertEquals("None reported", empty.text)
        assertEquals(ReadingTone.MUTED, empty.tone)
    }

    @Test
    fun `an unanswered DTC read is not the same as having no codes`() {
        val view = Readings.dtcs(DiagnosticResult.NoResponse)

        assertEquals("No Response", view.text)
        assertNotEquals("None reported", view.text)
    }

    @Test
    fun `a VIN renders as text and a missing VIN does not render as empty`() {
        val vin = Vin("1M8GDM9AXKP042788")
        val view = Readings.vin(DiagnosticResult.Success(vin, "490201" + "314D"))
        val missing = Readings.vin(DiagnosticResult.NoResponse)

        assertEquals("1M8GDM9AXKP042788", view.text)
        assertEquals(ReadingTone.VALUE, view.tone)
        assertEquals("No Response", missing.text)
    }

    @Test
    fun `free text answers and blank answers are distinguished`() {
        val identity = Readings.text(DiagnosticResult.Success("ELM327 v1.5", "ELM327 v1.5"))
        val blank = Readings.text(DiagnosticResult.Success("   ", "   "))

        assertEquals("ELM327 v1.5", identity.text)
        assertEquals("No Response", blank.text)
    }

    @Test
    fun `nothing read yet is muted and self-describing`() {
        assertEquals("Not read yet", ReadingView.NotReadYet.text)
        assertEquals(ReadingTone.MUTED, ReadingView.NotReadYet.tone)
    }
}
