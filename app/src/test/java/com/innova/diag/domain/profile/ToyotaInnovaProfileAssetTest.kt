package com.innova.diag.domain.profile

import com.innova.diag.core.transport.assertThrowsSuspend
import com.innova.diag.core.transport.fake.FakeByteTransport
import com.innova.diag.adapter.elm.ElmChannel
import com.innova.diag.adapter.elm.ElmObdCommandPort
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped candidate profile: `app/src/main/assets/profiles/toyota_innova_2007_gcc.json`.
 *
 * This test exists because a profile is only worth what the *file in the APK* says. It reads the asset
 * from disk, parses it the way production would, and then proves the part that matters: every
 * operation the file declares is refused, and the refusal writes nothing to a transport.
 *
 * The file is read from the module directory (Gradle runs unit tests with the module as the working
 * directory); both plausible roots are tried so the test does not depend on that detail.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToyotaInnovaProfileAssetTest {

    private val assetText: String by lazy { assetFile().readText() }

    @Test
    fun `the candidate profile parses and claims nothing it has not verified`() {
        val profile = VehicleProfileParser.parse(assetText)

        assertEquals("toyota-innova-2007-gcc", profile.id)
        assertEquals("Toyota", profile.make)
        assertEquals("Innova", profile.model)
        assertEquals("GCC", profile.market)
        assertEquals(YearRange(from = 2007, to = 2007), profile.yearRange)

        assertEquals(ProfileStatus.UNVERIFIED, profile.status)
        assertFalse(profile.isVerified)
        assertTrue(
            "every ECU must be unverified until the bus dump arrives",
            profile.ecuDefinitions.all { it.status == ProfileStatus.UNVERIFIED },
        )
    }

    @Test
    fun `addresses and init commands are UNKNOWN`() {
        val ecu = VehicleProfileParser.parse(assetText).ecuDefinitions.single()

        assertEquals("Engine ECU", ecu.name)
        assertEquals(EcuAddress.Unknown, ecu.txId)
        assertEquals(EcuAddress.Unknown, ecu.rxId)
        assertEquals("UNKNOWN", ecu.network)
        assertEquals(InitSequence.Unknown, ecu.initCommands)
        assertEquals(emptyList<Int>(), ecu.supportedServices)
    }

    @Test
    fun `the file carries the required note verbatim`() {
        assertTrue(
            "the candidate profile must state why it is not verified",
            assetText.contains("\"note\": \"Awaiting physical bus dump from authorized tool.\""),
        )
    }

    @Test
    fun `the file declares no active tests and no writes`() {
        val profile = VehicleProfileParser.parse(assetText)

        assertTrue("a candidate profile may only contain reads", profile.operations.isNotEmpty())
        assertTrue(
            profile.operations.all { it.riskClass == RiskClass.READ_ONLY },
        )
        // Pure data: no evaluable expression anywhere, and nothing that looks like a script tag.
        assertFalse(assetText.contains("function", ignoreCase = true))
        assertFalse(assetText.contains("eval", ignoreCase = true))
        assertFalse(assetText.contains("<script", ignoreCase = true))
    }

    @Test
    fun `every declared operation on the candidate profile is refused`() = runTest {
        val profile = VehicleProfileParser.parse(assetText)

        for (operation in profile.operations) {
            val port = RecordingPort()
            val error = assertThrowsSuspend<SafetyViolationException> {
                ProfileOperationExecutor(port).execute(
                    profile = profile,
                    ecuName = "Engine ECU",
                    operationId = operation.id,
                )
            }
            assertEquals(
                "operation ${operation.id} must be refused for the documented reason",
                SafetyViolationException.Reason.PROFILE_NOT_VERIFIED,
                error.reason,
            )
            assertEquals("operation ${operation.id} must not transmit", 0, port.transmissions)
        }
    }

    @Test
    fun `the candidate profile writes nothing to a real transport`() = runTest {
        val profile = VehicleProfileParser.parse(assetText)
        val transport = FakeByteTransport()
        transport.connect()
        val executor = ProfileOperationExecutor(ElmObdCommandPort(ElmChannel(transport)))

        assertThrowsSuspend<SafetyViolationException> {
            executor.execute(profile, "Engine ECU", "generic.read-vin")
        }

        assertEquals("no bytes may reach the socket", emptyList<ByteArray>(), transport.writes)
    }

    private fun assetFile(): File {
        val relative = "src/main/assets/profiles/toyota_innova_2007_gcc.json"
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { it.isFile }
            ?: throw AssertionError(
                "candidate profile not found relative to ${File(".").absolutePath} " +
                    "(looked in ${candidates.joinToString { it.path }})",
            )
    }
}
