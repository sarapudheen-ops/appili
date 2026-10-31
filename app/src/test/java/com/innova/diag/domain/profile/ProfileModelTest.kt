package com.innova.diag.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The models police themselves, so the parser is not the only line of defence.
 *
 * Most of these constructors throw rather than accept a value the platform could not honour: a write
 * service, a header-setting init command, an address that is not an address, a "verified" claim with
 * nothing behind it.
 */
class ProfileModelTest {

    @Test
    fun `ServiceBytes canonicalizes and validates`() {
        assertEquals("010C", ServiceBytes.parse("010c")!!.hex)
        assertEquals("010C", ServiceBytes.parse("01 0C")!!.hex)
        assertEquals("010C", ServiceBytes.parse(" 010C ")!!.hex)
        assertEquals("03", ServiceBytes.parse("03")!!.hex)

        assertNull(ServiceBytes.parse(""))
        assertNull(ServiceBytes.parse("010"))          // odd length
        assertNull(ServiceBytes.parse("01ZZ"))         // not hex
        assertNull(ServiceBytes.parse("0102030405"))   // longer than a J1979 request
    }

    @Test
    fun `ServiceBytes exposes the service id and single-byte operations`() {
        assertEquals(0x01, ServiceBytes.require("010C").service)
        assertEquals(0x03, ServiceBytes.require("03").service)
        assertTrue(ServiceBytes.require("03").isSingleByte)
        assertFalse(ServiceBytes.require("010C").isSingleByte)
        assertEquals("03", ServiceBytes.of(0x03).hex)
    }

    @Test
    fun `an operation can never describe a write or a manufacturer service`() {
        // 04 clear DTCs, 2E write by identifier, 2F IO control, 31 routine, 21/22 manufacturer reads.
        for (service in listOf("04", "2E", "2F", "31", "21", "22", "27", "28", "3E")) {
            try {
                DiagnosticOperation("sneaky", RiskClass.READ_ONLY, ServiceBytes.require(service))
                throw AssertionError("service $service must not be representable in a profile")
            } catch (refused: IllegalArgumentException) {
                assertTrue(
                    "refusal for $service should explain itself: ${refused.message}",
                    refused.message.orEmpty().contains("read-only"),
                )
            }
        }
    }

    @Test
    fun `read-only operations are accepted`() {
        for (service in listOf("01", "02", "03", "05", "06", "07", "08", "09", "0A")) {
            val operation = DiagnosticOperation("read", RiskClass.READ_ONLY, ServiceBytes.require(service))
            assertEquals(service.toInt(16), operation.service)
        }
    }

    @Test
    fun `EcuAddress parses captured addresses and the UNKNOWN sentinel`() {
        assertEquals(EcuAddress.Known("7E0"), EcuAddress.parse("7e0"))
        assertEquals(EcuAddress.Known("18DAF110"), EcuAddress.parse("18DAF110"))
        assertEquals(EcuAddress.Unknown, EcuAddress.parse("UNKNOWN"))
        assertEquals(EcuAddress.Unknown, EcuAddress.parse("unknown"))

        assertNull(EcuAddress.parse("1234"))   // 4 digits is neither 11-bit nor 29-bit
        assertNull(EcuAddress.parse("ZZZ"))
        assertNull(EcuAddress.parse(""))

        assertEquals("UNKNOWN", EcuAddress.Unknown.text)
        assertEquals("7E0", EcuAddress.Known("7E0").text)
    }

    @Test
    fun `an init sequence cannot be empty, blank, multi-line or set a header`() {
        assertTrue(InitSequence.Known(listOf("ATSP0")).isKnown)
        assertFalse(InitSequence.Unknown.isKnown)

        rejects { InitSequence.Known(emptyList()) }
        rejects { InitSequence.Known(listOf(" ")) }
        rejects { InitSequence.Known(listOf("ATSP0\rATE0")) }
        rejects { InitSequence.Known(listOf("atsh7e0")) }
    }

    @Test
    fun `a year range validates and contains`() {
        val range = YearRange(from = 2005, to = 2010)
        assertTrue(range.contains(2007))
        assertFalse(range.contains(2011))
        rejects { YearRange(from = 2010, to = 2005) }
        rejects { YearRange(from = 1700, to = 1800) }
    }

    @Test
    fun `a profile is only VERIFIED when every ECU is`() {
        assertEquals(
            ProfileStatus.VERIFIED,
            ProfileFixtures.profile(status = ProfileStatus.VERIFIED).status,
        )
        assertEquals(
            ProfileStatus.UNVERIFIED,
            ProfileFixtures.profile(status = ProfileStatus.UNVERIFIED).status,
        )
        assertEquals(
            ProfileStatus.SIMULATOR_ONLY,
            ProfileFixtures.profile(status = ProfileStatus.SIMULATOR_ONLY).status,
        )

        val mixed = ProfileFixtures.profile(
            ecus = listOf(
                ProfileFixtures.capturedEcu(name = "Engine ECU"),
                ProfileFixtures.uncapturedEcu(name = "ABS ECU"),
            ),
        )
        assertEquals(ProfileStatus.UNVERIFIED, mixed.status)
        assertFalse(mixed.isVerified)
    }

    @Test
    fun `a simulator-only ECU makes the profile simulator-only`() {
        val profile = ProfileFixtures.profile(
            ecus = listOf(
                ProfileFixtures.capturedEcu(name = "Engine ECU"),
                ProfileFixtures.uncapturedEcu(name = "Simulator ECU", status = ProfileStatus.SIMULATOR_ONLY),
            ),
        )
        assertEquals(ProfileStatus.SIMULATOR_ONLY, profile.status)
    }

    @Test
    fun `a verified ECU definition cannot be hollow`() {
        rejects {
            ProfileFixtures.capturedEcu(txId = EcuAddress.Unknown)
        }
        rejects {
            ProfileFixtures.capturedEcu(initCommands = InitSequence.Unknown)
        }
        rejects {
            ProfileFixtures.capturedEcu(network = "UNKNOWN")
        }
    }

    @Test
    fun `a profile describes itself for humans`() {
        val profile = ProfileFixtures.profile()
        assertEquals("2007 Toyota Innova (GCC)", profile.displayName)
        assertTrue(profile.covers(2007))
        assertFalse(profile.covers(2008))
        assertEquals("Engine ECU", profile.ecu("Engine ECU")!!.name)
        assertNull(profile.ecu("ABS ECU"))
        assertEquals("read-engine-speed", profile.operation("read-engine-speed")!!.id)
        assertNull(profile.operation("write-something"))
    }

    private fun rejects(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected the model to refuse this value")
        } catch (refused: IllegalArgumentException) {
            assertTrue("a refusal should carry a reason", refused.message.orEmpty().isNotBlank())
        }
    }
}
