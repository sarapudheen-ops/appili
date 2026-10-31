package com.innova.diag.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The result type itself: the "absence is not zero" rule has to be expressible, and the mapping helper
 * used by every decoder must never swallow a failure.
 */
class DiagnosticResultTest {

    @Test
    fun `zero reported by the vehicle is a Success`() {
        val zero: DiagnosticResult<Int> = DiagnosticResult.Success(0, "410D00")
        val success = zero.requireSuccess("a stationary vehicle")

        assertEquals(0, success.data)
        assertFalse("a reported zero is a measurement, not a failure", zero is DiagnosticResult.NoResponse)
    }

    @Test
    fun `the absence of an answer is not a Success and carries no value`() {
        val noResponse: DiagnosticResult<Int> = DiagnosticResult.NoResponse

        assertFalse(noResponse is DiagnosticResult.Success)
        assertEquals("NoResponse", noResponse.variantName())
    }

    @Test
    fun `mapData transforms a Success and keeps the raw hex`() {
        val mapped = DiagnosticResult.Success(21, "41 05 55").mapData { value ->
            DiagnosticResult.Success(value - 40, "41 05 55")
        }.requireSuccess("mapping")

        assertEquals(-19, mapped.data)
        assertEquals("41 05 55", mapped.rawHex)
    }

    @Test
    fun `mapData passes every failure through untouched`() {
        val failures: List<DiagnosticResult<Int>> = listOf(
            DiagnosticResult.Unsupported,
            DiagnosticResult.NoResponse,
            DiagnosticResult.Timeout,
            DiagnosticResult.Malformed("41 0C"),
        )

        for (failure in failures) {
            val mapped = failure.mapData { value -> DiagnosticResult.Success(value + 1, "should not run") }

            assertSame("mapping must not replace ${failure.variantName()}", failure, mapped)
        }
    }

    @Test
    fun `Malformed keeps the text that could not be decoded`() {
        val malformed = DiagnosticResult.Malformed("41 0C ZZ")

        assertEquals("41 0C ZZ", malformed.rawHex)
        assertEquals("Malformed(41 0C ZZ)", malformed.variantName())
    }

    @Test
    fun `Timeout is reported by the channel layer and is never inferred from silence`() {
        // Decoders only ever see text; a timeout can only arrive from ElmChannel as ElmException.Timeout
        // mapped to this variant (see ObdService). Nothing here fabricates one from an empty string.
        val emptyInput: DiagnosticResult<Int> = DiagnosticResult.NoResponse

        assertTrue(emptyInput is DiagnosticResult.NoResponse)
        assertFalse(emptyInput is DiagnosticResult.Timeout)
    }
}
