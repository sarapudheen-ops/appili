package com.innova.diag.domain.profile

import com.innova.diag.adapter.elm.ElmChannel
import com.innova.diag.adapter.elm.ElmObdCommandPort
import com.innova.diag.core.transport.assertThrowsSuspend
import com.innova.diag.core.transport.fake.FakeByteTransport
import com.innova.diag.domain.obd.DiagnosticResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fail-closed gate: **a refusal must be a refusal, not a quiet no-op.**
 *
 * Every denial test does three things, not one: it asserts the exception type, it asserts *why*, and
 * it asserts that the port received **nothing**. A gate that throws after transmitting would pass the
 * first two and fail the third, which is the only failure mode that matters on a vehicle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileOperationExecutorTest {

    @Test
    fun `an UNVERIFIED profile throws SafetyViolationException`() = runTest {
        val port = RecordingPort()

        val error = assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(
                profile = ProfileFixtures.profile(status = ProfileStatus.UNVERIFIED),
                ecuName = "Engine ECU",
                operationId = "read-engine-speed",
            )
        }

        assertEquals(SafetyViolationException.Reason.PROFILE_NOT_VERIFIED, error.reason)
        assertEquals("test-innova-2007-gcc", error.profileId)
        assertEquals("read-engine-speed", error.operationId)
        assertTrue(error.message!!.contains("UNVERIFIED"))
    }

    @Test
    fun `an UNVERIFIED profile does not transmit anything to the port`() = runTest {
        val port = RecordingPort()

        assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(
                ProfileFixtures.profile(status = ProfileStatus.UNVERIFIED),
                "Engine ECU",
                "read-engine-speed",
            )
        }

        assertEquals("no command may reach the port", emptyList<String>(), port.commands)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `an UNVERIFIED profile puts no bytes on the wire`() = runTest {
        // The strongest version of the previous test: a real channel over a recording transport.
        val transport = FakeByteTransport()
        transport.connect()
        val executor = ProfileOperationExecutor(ElmObdCommandPort(ElmChannel(transport)))

        assertThrowsSuspend<SafetyViolationException> {
            executor.execute(
                ProfileFixtures.profile(status = ProfileStatus.UNVERIFIED),
                "Engine ECU",
                "read-engine-speed",
            )
        }

        assertEquals("the transport must see zero writes", emptyList<ByteArray>(), transport.writes)
    }

    @Test
    fun `a SIMULATOR_ONLY profile is denied with its own reason`() = runTest {
        val port = RecordingPort()

        val error = assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(
                ProfileFixtures.profile(status = ProfileStatus.SIMULATOR_ONLY),
                "Engine ECU",
                "read-engine-speed",
            )
        }

        assertEquals(SafetyViolationException.Reason.SIMULATOR_ONLY, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `one UNVERIFIED ECU makes the whole profile unexecutable`() = runTest {
        val port = RecordingPort()
        val mixed = ProfileFixtures.profile(
            ecus = listOf(
                ProfileFixtures.capturedEcu(name = "Engine ECU"),
                ProfileFixtures.uncapturedEcu(name = "ABS ECU", status = ProfileStatus.UNVERIFIED),
            ),
        )

        assertEquals(ProfileStatus.UNVERIFIED, mixed.status)

        val error = assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(mixed, "Engine ECU", "read-engine-speed")
        }

        // The *asked-for* ECU is verified; the profile still refuses, because a partial verification
        // is not a verification.
        assertEquals(SafetyViolationException.Reason.PROFILE_NOT_VERIFIED, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `a VERIFIED profile with a declared read-only operation transmits exactly its bytes`() = runTest {
        val port = RecordingPort()

        val result = ProfileOperationExecutor(port).execute(
            ProfileFixtures.profile(),
            "Engine ECU",
            "read-engine-speed",
        )

        assertEquals(listOf("010C"), port.commands)
        assertEquals("410C1A20", result.dataOrNull)
    }

    @Test
    fun `an ACTIVE_TEST operation is refused even on a VERIFIED profile`() = runTest {
        val port = RecordingPort()
        val activeTest = DiagnosticOperation(
            id = "actuator-sweep",
            riskClass = RiskClass.ACTIVE_TEST,
            serviceBytes = ServiceBytes.require("0100"),
        )

        val error = assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(
                ProfileFixtures.profile(operations = listOf(activeTest)),
                "Engine ECU",
                "actuator-sweep",
            )
        }

        assertEquals(SafetyViolationException.Reason.RISK_CLASS_FORBIDDEN, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `a CONFIGURATION operation is refused even on a VERIFIED profile`() = runTest {
        val port = RecordingPort()
        val configuration = DiagnosticOperation(
            id = "configure-vin-write",
            riskClass = RiskClass.CONFIGURATION,
            serviceBytes = ServiceBytes.require("0100"),
        )

        val error = assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(
                ProfileFixtures.profile(operations = listOf(configuration)),
                "Engine ECU",
                "configure-vin-write",
            )
        }

        assertEquals(SafetyViolationException.Reason.RISK_CLASS_FORBIDDEN, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `an operation whose service the ECU never declared is refused`() = runTest {
        val port = RecordingPort()

        val error = assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(
                // The ECU declares no services: nothing about mode 01 has been captured.
                ProfileFixtures.profile(ecus = listOf(ProfileFixtures.capturedEcu(supportedServices = emptyList()))),
                "Engine ECU",
                "read-engine-speed",
            )
        }

        assertEquals(SafetyViolationException.Reason.SERVICE_NOT_DECLARED, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `an ECU the profile does not describe is refused`() = runTest {
        val port = RecordingPort()

        val error = assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(
                ProfileFixtures.profile(),
                "Transmission ECU",
                "read-engine-speed",
            )
        }

        assertEquals(SafetyViolationException.Reason.ECU_NOT_DESCRIBED, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `an operation the profile does not declare is refused`() = runTest {
        val port = RecordingPort()

        val error = assertThrowsSuspend<SafetyViolationException> {
            ProfileOperationExecutor(port).execute(
                ProfileFixtures.profile(),
                "Engine ECU",
                "read-fuel-trim",
            )
        }

        assertEquals(SafetyViolationException.Reason.OPERATION_NOT_DECLARED, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `a vehicle failure is a result, not a safety violation`() = runTest {
        val port = RecordingPort(response = DiagnosticResult.NoResponse)

        val result = ProfileOperationExecutor(port).execute(
            ProfileFixtures.profile(),
            "Engine ECU",
            "read-engine-speed",
        )

        assertEquals(listOf("010C"), port.commands)
        assertTrue("a silent ECU must stay a DiagnosticResult", result is DiagnosticResult.NoResponse)
    }
}
