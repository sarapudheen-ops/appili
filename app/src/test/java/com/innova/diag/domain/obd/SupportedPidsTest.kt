package com.innova.diag.domain.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Supported-PID bitmask decoding for banks 0x00, 0x20, 0x40 and 0x60.
 *
 * SAE J1979 packs the mask most-significant-bit first: bit 31 of the payload is the lowest PID of the
 * bank, bit 0 flags that another bank exists. Getting the bit order backwards still produces a
 * well-formed-looking set, which is exactly why these cases are pinned.
 */
class SupportedPidsTest {

    @Test
    fun `4100BE1FA813 reports the PIDs its bits describe`() {
        val result = Mode01Decoders.supportedPids("4100BE1FA813", Mode01Pids.SUPPORTED_PIDS_00)
            .requireSuccess("bank 0x00")

        assertEquals(0xBE1FA813L, result.data.mask)
        assertEquals(0x00, result.data.basePid)
        assertEquals(
            setOf(0x01, 0x03, 0x04, 0x05, 0x06, 0x07, 0x0C, 0x0D, 0x0E, 0x0F,
                0x10, 0x11, 0x13, 0x15, 0x1C, 0x1F, 0x20),
            result.data.pids,
        )
        assertEquals(17, result.data.pids.size)
        assertEquals("4100BE1FA813", result.rawHex)
    }

    @Test
    fun `the lowest PID of a bank is the most significant bit`() {
        // 0x80000000 alone means "PID 0x01 supported", not "PID 0x20 supported".
        val result = Mode01Decoders.supportedPids("410080000000", Mode01Pids.SUPPORTED_PIDS_00)
            .requireSuccess("bank 0x00")

        assertEquals(setOf(0x01), result.data.pids)
        assertTrue(result.data.supports(0x01))
        assertFalse(result.data.supports(0x20))
    }

    @Test
    fun `the least significant bit asks for the next bank`() {
        assertTrue(
            Mode01Decoders.supportedPids("4100BE1FA813", 0x00).requireSuccess().data.continuesToNextBank,
        )
        assertFalse(
            Mode01Decoders.supportedPids("4120A0000000", 0x20).requireSuccess().data.continuesToNextBank,
        )
    }

    @Test
    fun `bank 0x20 reports PIDs offset by 0x20`() {
        val result = Mode01Decoders.supportedPids("4120A0000000", Mode01Pids.SUPPORTED_PIDS_20)
            .requireSuccess("bank 0x20")

        assertEquals(setOf(0x21, 0x23), result.data.pids)
        assertTrue(result.data.supports(0x21))
        assertFalse("0x01 belongs to bank 0x00", result.data.supports(0x01))
    }

    @Test
    fun `an all-bits mask lists all 32 PIDs and continues`() {
        val result = Mode01Decoders.supportedPids("4140FFFFFFFF", 0x40).requireSuccess("bank 0x40")

        assertEquals((0x41..0x60).toSet(), result.data.pids)
        assertTrue(result.data.continuesToNextBank)
    }

    @Test
    fun `an empty mask means nothing is supported in that bank`() {
        val result = Mode01Decoders.supportedPids("416000000000", 0x60).requireSuccess("bank 0x60")

        assertTrue(result.data.pids.isEmpty())
        assertFalse(result.data.continuesToNextBank)
    }

    @Test
    fun `a mask shorter or longer than four bytes is malformed`() {
        Mode01Decoders.supportedPids("4100BE1FA8", 0x00).assertMalformed("three data bytes")
        Mode01Decoders.supportedPids("4100BE1FA8130100", 0x00).assertMalformed("six data bytes")
    }

    @Test
    fun `a bank answered by a different PID is malformed`() {
        Mode01Decoders.supportedPids("4120A0000000", 0x00).assertMalformed("bank 0x20 payload offered to bank 0x00")
    }

    @Test
    fun `NO DATA for a bank is NoResponse, not an empty set`() {
        Mode01Decoders.supportedPids("NO DATA", 0x00).assertNoResponse("bank 0x00")

        assertFalse(
            "an unanswered bank must not look like 'nothing supported'",
            Mode01Decoders.supportedPids("NO DATA", 0x00) is DiagnosticResult.Success,
        )
    }

    @Test
    fun `only 0x00, 0x20, 0x40 and 0x60 are valid banks`() {
        assertThrows(IllegalArgumentException::class.java) {
            Mode01Decoders.supportedPids("4100BE1FA813", 0x04)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SupportedPids(basePid = 0x00, mask = 0x1_0000_0000L)
        }
    }
}
