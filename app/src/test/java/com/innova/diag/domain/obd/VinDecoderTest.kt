package com.innova.diag.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mode 09 PID 0x02 (VIN) decoding.
 *
 * A VIN is 17 ASCII characters carried as hex, usually behind a "number of data items" byte and often
 * padded to a full CAN frame. Both shapes must decode; a truncated or non-VIN payload must fail loudly
 * rather than yield a 17-character string that merely looks like a VIN.
 */
class VinDecoderTest {

    private val vin = "1M8GDM9AXKP042788"

    @Test
    fun `a VIN behind the data-item prefix decodes to text`() {
        val result = VinDecoder.decode("4902" + "01" + vin.asciiHex()).requireSuccess("mode 09 PID 02")

        assertEquals(vin, result.data.value)
        assertEquals(vin, result.data.toString())
        assertTrue(result.rawHex.endsWith(vin.asciiHex()))
    }

    @Test
    fun `a VIN without the prefix decodes to the same text`() {
        val withPrefix = VinDecoder.decode("4902" + "01" + vin.asciiHex()).requireSuccess("prefixed")
        val withoutPrefix = VinDecoder.decode("4902" + vin.asciiHex()).requireSuccess("bare")

        assertEquals(withPrefix.data, withoutPrefix.data)
    }

    @Test
    fun `CAN frame padding after the VIN is ignored`() {
        // Multi-frame responses are commonly padded with 0x55 so every frame is full length.
        val padded = VinDecoder.decode("4902" + "01" + vin.asciiHex() + "555555").requireSuccess("padded")

        assertEquals(vin, padded.data.value)
    }

    @Test
    fun `a truncated VIN is malformed`() {
        VinDecoder.decode("490201" + "1M8GDM9AXKP".asciiHex()).assertMalformed("a 11-character VIN")
    }

    @Test
    fun `a payload containing I, O or Q is not a VIN`() {
        // SAE J1979 excludes I, O and Q; a string using them is not a VIN even if it is 17 long.
        val lookalike = "1M8GDM9AXKP04278I"

        VinDecoder.decode("490201" + lookalike.asciiHex()).assertMalformed("a VIN lookalike containing I")
    }

    @Test
    fun `non-printable payload bytes are malformed`() {
        VinDecoder.decode("490201" + "00".repeat(17)).assertMalformed("NUL bytes")
    }

    @Test
    fun `a VIN answered by a different PID is malformed`() {
        VinDecoder.decode("4901" + "01" + vin.asciiHex()).assertMalformed("PID 0x01 payload offered to PID 0x02")
    }

    @Test
    fun `NO DATA for the VIN is NoResponse`() {
        val result = VinDecoder.decode("NO DATA")

        result.assertNoResponse("the adapter's NO DATA")
        assertFalse(result is DiagnosticResult.Success)
    }

    @Test
    fun `a VIN cannot be constructed from an invalid string`() {
        assertFalse(Vin.isValid("SHORT"))
        assertFalse(Vin.isValid("1M8GDM9AXKP04278O"))
        assertTrue(Vin.isValid(vin))
        assertThrows(IllegalArgumentException::class.java) { Vin("1M8GDM9AXKP04278O") }
    }
}
