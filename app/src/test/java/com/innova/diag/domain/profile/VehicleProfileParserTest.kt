package com.innova.diag.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser is the place where a profile either becomes trustworthy data or does not become data at
 * all. These tests pin the second half: every malformed, misspelt, over-claimed or non-JSON document
 * is refused with a message that names the JSON path.
 */
class VehicleProfileParserTest {

    @Test
    fun `a profile with nothing captured parses with UNKNOWN everywhere`() {
        val profile = VehicleProfileParser.parse(profileJson())

        assertEquals("toyota-innova-2007-gcc", profile.id)
        assertEquals("Toyota", profile.make)
        assertEquals("Innova", profile.model)
        assertEquals("GCC", profile.market)
        assertEquals(YearRange(2007, 2007), profile.yearRange)
        assertEquals(ProfileStatus.UNVERIFIED, profile.status)

        val ecu = profile.ecuDefinitions.single()
        assertEquals("Engine ECU", ecu.name)
        assertEquals(EcuAddress.Unknown, ecu.txId)
        assertEquals(EcuAddress.Unknown, ecu.rxId)
        assertEquals(InitSequence.Unknown, ecu.initCommands)
        assertEquals("UNKNOWN", ecu.network)
        assertEquals(emptyList<Int>(), ecu.supportedServices)
        assertEquals(ProfileStatus.UNVERIFIED, ecu.status)
    }

    @Test
    fun `a fully captured and verified profile parses`() {
        val profile = VehicleProfileParser.parse(
            profileJson(
                profileStatus = "VERIFIED",
                ecuStatus = "VERIFIED",
                txId = "\"7E0\"",
                rxId = "\"7E8\"",
                network = "\"CAN 11/500\"",
                initCommands = "[\"ATSP0\", \"ATAL\"]",
                supportedServices = "[1, 3, 9]",
            ),
        )

        assertEquals(ProfileStatus.VERIFIED, profile.status)
        val ecu = profile.ecuDefinitions.single()
        assertEquals(EcuAddress.Known("7E0"), ecu.txId)
        assertEquals(EcuAddress.Known("7E8"), ecu.rxId)
        assertEquals(InitSequence.Known(listOf("ATSP0", "ATAL")), ecu.initCommands)
        assertTrue(ecu.supports(0x01))
        assertTrue(ecu.networkKnown)
    }

    @Test
    fun `an unknown field is refused, not ignored`() {
        rejects(
            profileJson(extraField = "\"staus\": \"VERIFIED\","),
            "unknown field",
            "staus",
        )
    }

    @Test
    fun `a profile that claims more verification than its ECUs have is refused`() {
        rejects(
            profileJson(profileStatus = "VERIFIED", ecuStatus = "UNVERIFIED"),
            "may not claim more verification",
            "UNVERIFIED",
        )
    }

    @Test
    fun `a missing status is refused`() {
        val json = profileJson().replace("\"status\": \"UNVERIFIED\",", "")
        rejects(json, "missing required field", "status")
    }

    @Test
    fun `an unknown status word is refused`() {
        rejects(profileJson(profileStatus = "PROBABLY_FINE"), "unknown status")
    }

    @Test
    fun `an ACTIVE_TEST operation is refused`() {
        rejects(
            profileJson(operations = operation("actuator-sweep", "ACTIVE_TEST", "0100")),
            "only READ_ONLY operations are allowed",
        )
    }

    @Test
    fun `a CONFIGURATION operation is refused`() {
        rejects(
            profileJson(operations = operation("configure", "CONFIGURATION", "0100")),
            "only READ_ONLY operations are allowed",
        )
    }

    @Test
    fun `a clear-diagnostic-information operation (mode 04) is refused`() {
        rejects(
            profileJson(operations = operation("clear-dtcs", "READ_ONLY", "04")),
            "not a read-only J1979 service",
        )
    }

    @Test
    fun `a write-by-identifier operation (mode 2E) is refused`() {
        rejects(
            profileJson(operations = operation("write-vin", "READ_ONLY", "2E")),
            "not a read-only J1979 service",
        )
    }

    @Test
    fun `a service byte string that is not hex is refused`() {
        rejects(
            profileJson(operations = operation("sneaky", "READ_ONLY", "eval('0100')")),
            "even-length hex",
        )
    }

    @Test
    fun `a verified ECU definition with UNKNOWN addresses is refused`() {
        rejects(
            profileJson(profileStatus = "VERIFIED", ecuStatus = "VERIFIED"),
            "must have captured txId and rxId",
        )
    }

    @Test
    fun `an init sequence that sets a header is refused`() {
        rejects(
            profileJson(
                profileStatus = "VERIFIED",
                ecuStatus = "VERIFIED",
                txId = "\"7E0\"",
                rxId = "\"7E8\"",
                network = "\"CAN 11/500\"",
                initCommands = "[\"ATSH7E0\"]",
            ),
            "ATSH",
        )
    }

    @Test
    fun `a non-integer year is refused`() {
        rejects(
            profileJson(yearFrom = "2007.0"),
            "expected an integer",
        )
    }

    @Test
    fun `a profile with no ECUs is refused`() {
        rejects(profileJson(ecus = ""), "at least one ECU")
    }

    @Test
    fun `duplicate operation ids are refused`() {
        rejects(
            profileJson(
                operations = operation("read", "READ_ONLY", "010C") + "," +
                    operation("read", "READ_ONLY", "010D"),
            ),
            "unique",
        )
    }

    @Test
    fun `a service listed as a string is refused`() {
        rejects(profileJson(supportedServices = "[\"01\"]"), "expected an integer")
    }

    @Test
    fun `an unsupported schema version is refused`() {
        rejects(profileJson(schemaVersion = "2"), "unsupported schemaVersion")
    }

    @Test
    fun `a document that is not JSON is refused rather than interpreted`() {
        rejects("function load() { return \"010C\"; }", "invalid JSON")
        rejects(profileJson().replace("\"id\"", "// a comment\n  \"id\""), "invalid JSON")
        rejects(profileJson().trimEnd().removeSuffix("}") + ",}", "invalid JSON")
    }

    @Test
    fun `a duplicate key is refused`() {
        rejects(profileJson(extraField = "\"id\": \"impostor\","), "duplicate key")
    }

    // --- fixtures ------------------------------------------------------------

    private fun operation(id: String, riskClass: String, serviceBytes: String): String =
        """{"id": "$id", "riskClass": "$riskClass", "serviceBytes": "$serviceBytes"}"""

    private fun ecuJson(
        ecuStatus: String,
        txId: String,
        rxId: String,
        network: String,
        initCommands: String,
        supportedServices: String,
    ): String = """
        {
          "name": "Engine ECU",
          "network": $network,
          "txId": $txId,
          "rxId": $rxId,
          "initCommands": $initCommands,
          "supportedServices": $supportedServices,
          "status": "$ecuStatus"
        }
    """.trimIndent()

    private fun profileJson(
        profileStatus: String = "UNVERIFIED",
        ecuStatus: String = "UNVERIFIED",
        txId: String = "\"UNKNOWN\"",
        rxId: String = "\"UNKNOWN\"",
        network: String = "\"UNKNOWN\"",
        initCommands: String = "\"UNKNOWN\"",
        supportedServices: String = "[]",
        ecus: String? = null,
        operations: String = operation("read-engine-speed", "READ_ONLY", "010C"),
        schemaVersion: String = "1",
        yearFrom: String = "2007",
        extraField: String = "",
    ): String {
        val ecuBlock = ecus ?: ecuJson(ecuStatus, txId, rxId, network, initCommands, supportedServices)
        return """
            {
              "schemaVersion": $schemaVersion,
              "status": "$profileStatus",
              $extraField
              "note": "Awaiting physical bus dump from authorized tool.",
              "id": "toyota-innova-2007-gcc",
              "make": "Toyota",
              "model": "Innova",
              "yearRange": { "from": $yearFrom, "to": 2007 },
              "market": "GCC",
              "ecuDefinitions": [$ecuBlock],
              "operations": [$operations]
            }
        """.trimIndent()
    }

    private fun rejects(json: String, vararg expectedParts: String): ProfileFormatException {
        val error = try {
            VehicleProfileParser.parse(json)
            null
        } catch (thrown: ProfileFormatException) {
            thrown
        }
        if (error == null) {
            throw AssertionError("expected a ProfileFormatException, but the profile was accepted")
        }
        for (part in expectedParts) {
            assertTrue(
                "expected \"$part\" in: ${error.message}",
                error.message.orEmpty().contains(part),
            )
        }
        return error
    }
}
