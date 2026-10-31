package com.innova.diag.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode 03 / mode 07 DTC decoding.
 *
 * Two wire formats reach the same decoder: ISO 15765 (CAN) prefixes the payload with a DTC count,
 * ISO 9141-2 and ISO 14230 (K-line) do not. Both must produce identical codes for identical faults,
 * and `0x0000` padding must never surface as `P0000`.
 */
class DtcDecodersTest {

    // --- The brief's stored-DTC fixture --------------------------------------

    @Test
    fun `430201330000 decodes to the single stored code P0133`() {
        val result = DtcDecoders.stored("430201330000").requireSuccess("mode 03")

        assertEquals(listOf("P0133"), result.data.map { it.code })
        assertEquals(DtcSystem.POWERTRAIN, result.data.single().system)
        assertEquals("0133", result.data.single().number)
        assertEquals("430201330000", result.rawHex)
    }

    @Test
    fun `the same fault from a K-line ECU without the count byte decodes identically`() {
        val can = DtcDecoders.stored("430201330000").requireSuccess("CAN form")
        val kLine = DtcDecoders.stored("4301330000").requireSuccess("K-line form")

        assertEquals(can.data, kLine.data)
    }

    @Test
    fun `padding blocks are discarded rather than reported as P0000`() {
        val result = DtcDecoders.stored("4303013300000000").requireSuccess("padded payload")

        assertEquals(listOf("P0133"), result.data.map { it.code })
    }

    @Test
    fun `several stored codes keep their wire order`() {
        val result = DtcDecoders.stored("4303013301340420").requireSuccess("mode 03")

        assertEquals(listOf("P0133", "P0134", "P0420"), result.data.map { it.code })
    }

    @Test
    fun `pending codes decode through mode 07`() {
        val result = DtcDecoders.pending("470201330000").requireSuccess("mode 07")

        assertEquals(listOf("P0133"), result.data.map { it.code })
    }

    @Test
    fun `a stored-DTC answer is not accepted as a pending-DTC answer`() {
        DtcDecoders.pending("430201330000").assertMalformed("mode 03 payload offered to mode 07")
    }

    // --- The four system letters ---------------------------------------------

    @Test
    fun `chassis, body and network codes use the top two bits of the first byte`() {
        val result = DtcDecoders.stored("430341008123C100").requireSuccess("mode 03")

        assertEquals(listOf("C0100", "B0123", "U0100"), result.data.map { it.code })
        assertEquals(
            listOf(DtcSystem.CHASSIS, DtcSystem.BODY, DtcSystem.NETWORK),
            result.data.map { it.system },
        )
    }

    @Test
    fun `the first digit after the letter comes from bits 5 and 4 of the first byte`() {
        assertEquals("P0000", DiagnosticTroubleCode.fromBytes(0x00, 0x00).code)
        assertEquals("P0133", DiagnosticTroubleCode.fromBytes(0x01, 0x33).code)
        assertEquals("P3000", DiagnosticTroubleCode.fromBytes(0x30, 0x00).code)
        assertEquals("U3FFF", DiagnosticTroubleCode.fromBytes(0xFF, 0xFF).code)
    }

    @Test
    fun `a DTC renders as its canonical text`() {
        val code = DiagnosticTroubleCode.fromBytes(0x01, 0x33)

        assertEquals("P0133", code.toString())
        assertEquals("P0133", code.code)
    }

    // --- Absence of codes -----------------------------------------------------

    @Test
    fun `a clean ECU answers with an empty list, not a failure`() {
        val can = DtcDecoders.stored("4300").requireSuccess("mode 03 with no codes")
        val padded = DtcDecoders.stored("43000000").requireSuccess("mode 03 with only padding")

        assertTrue(can.data.isEmpty())
        assertTrue(padded.data.isEmpty())
    }

    @Test
    fun `NO DATA is NoResponse and not an empty list`() {
        val result = DtcDecoders.stored("NO DATA")

        result.assertNoResponse("the adapter's NO DATA")
        assertFalse(
            "'we were not told' must not be reported as 'there are no fault codes'",
            result is DiagnosticResult.Success,
        )
    }

    // --- Rejected responses ---------------------------------------------------

    @Test
    fun `an unsupported mode 03 is Unsupported while other negative codes are malformed`() {
        assertTrue(DtcDecoders.stored("7F0312") is DiagnosticResult.Unsupported)
        DtcDecoders.stored("7F0322").assertMalformed("negative response with response code 0x22")
    }

    @Test
    fun `non-hex DTC payload is malformed`() {
        DtcDecoders.stored("43013G").assertMalformed("non-hex characters")
        DtcDecoders.stored("4301").assertMalformed("a lone count byte with no DTC block")
    }

    @Test
    fun `DTC byte values outside a byte are rejected at construction`() {
        val rejected = runCatching { DiagnosticTroubleCode.fromBytes(0x100, 0x00) }.isFailure

        assertTrue(rejected)
    }
}
