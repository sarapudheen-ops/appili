package com.innova.diag.domain.profile.pack

import com.innova.diag.core.transport.assertThrowsSuspend
import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.profile.RecordingPort
import com.innova.diag.domain.profile.SafetyViolationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hierarchical gate.
 *
 * Each denial asserts three things, for the same reason as the v1 gate: the exception type, the
 * **reason**, and that the port received **nothing**. A gate that throws after transmitting is the one
 * failure mode that matters on a vehicle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PackOperationExecutorTest {

    private fun registry(
        variant: Map<String, Any?> = TestPacks.verifiedVariant(),
    ): BrandPackRegistry = BrandPackRegistry.fromDocuments(
        TestPacks.documents(
            "test_brand.json" to TestPacks.brand(families = listOf(TestPacks.family(variants = listOf(variant)))),
        ),
        TestPacks.shippedSchema(),
    )

    @Test
    fun `an UNVERIFIED write operation is refused and nothing is transmitted`() = runTest {
        val port = RecordingPort()
        val write = TestPacks.operation(
            id = "enhanced.actuator-test",
            verificationStatus = "UNVERIFIED",
            riskClass = "ACTIVE_TEST",
            scope = "ENHANCED",
            serviceBytes = "UNKNOWN",
        )
        val catalogue = registry(TestPacks.verifiedVariant(supportedOperations = listOf(write)))

        val error = assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(port).execute(catalogue, "test_brand", "verified_variant", "enhanced.actuator-test")
        }

        assertEquals(SafetyViolationException.Reason.RISK_CLASS_FORBIDDEN, error.reason)
        assertEquals(0, port.transmissions)
        assertEquals("no command may reach the port", emptyList<String>(), port.commands)
    }

    @Test
    fun `an UNVERIFIED read operation on a verified variant is refused`() = runTest {
        val port = RecordingPort()
        val unverified = TestPacks.operation(id = "generic.unverified", verificationStatus = "UNVERIFIED")
        val catalogue = registry(TestPacks.verifiedVariant(supportedOperations = listOf(unverified)))

        val error = assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(port).execute(catalogue, "test_brand", "verified_variant", "generic.unverified")
        }

        assertEquals(SafetyViolationException.Reason.OPERATION_NOT_VERIFIED, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `an UNVERIFIED variant refuses even a verified operation`() = runTest {
        val port = RecordingPort()
        val catalogue = registry(
            TestPacks.variant(supportedOperations = listOf(TestPacks.verifiedGenericOperation())),
        )

        val error = assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(port).execute(catalogue, "test_brand", "test_variant", "generic.read-engine-speed")
        }

        assertEquals(SafetyViolationException.Reason.VARIANT_NOT_VERIFIED, error.reason)
        assertEquals("nothing may leave an unverified variant", 0, port.transmissions)
    }

    @Test
    fun `a SIMULATOR_ONLY variant is refused with its own reason`() = runTest {
        val port = RecordingPort()
        val catalogue = registry(
            TestPacks.variant(
                verificationStatus = "SIMULATOR_ONLY",
                supportedOperations = listOf(TestPacks.verifiedGenericOperation()),
            ),
        )

        val error = assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(port).execute(catalogue, "test_brand", "test_variant", "generic.read-engine-speed")
        }

        assertEquals(SafetyViolationException.Reason.SIMULATOR_ONLY, error.reason)
        assertEquals(0, port.transmissions)
    }

    @Test
    fun `an unknown brand, variant or operation is refused as undescribed`() = runTest {
        val catalogue = registry()

        val unknownBrand = assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(RecordingPort()).execute(catalogue, "no_brand", "verified_variant", "generic.read-engine-speed")
        }
        assertEquals(SafetyViolationException.Reason.VARIANT_NOT_DESCRIBED, unknownBrand.reason)

        val unknownVariant = assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(RecordingPort()).execute(catalogue, "test_brand", "no_variant", "generic.read-engine-speed")
        }
        assertEquals(SafetyViolationException.Reason.VARIANT_NOT_DESCRIBED, unknownVariant.reason)

        val unknownOperation = assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(RecordingPort()).execute(catalogue, "test_brand", "verified_variant", "no_operation")
        }
        assertEquals(SafetyViolationException.Reason.OPERATION_NOT_DECLARED, unknownOperation.reason)
    }

    @Test
    fun `a fully verified generic operation transmits exactly its bytes, once`() = runTest {
        val port = RecordingPort()
        val catalogue = registry()

        val result = PackOperationExecutor(port).execute(
            catalogue,
            "test_brand",
            "verified_variant",
            "generic.read-engine-speed",
        )

        assertTrue("expected the port's response, got $result", result is DiagnosticResult.Success)
        assertEquals(listOf("010C"), port.commands)
        assertEquals(1, port.transmissions)
    }

    @Test
    fun `a refusal names the brand, the variant and the operation`() = runTest {
        // An UNVERIFIED variant that still declares a verified operation: the refusal has to name all
        // three levels, and it must be refused for the variant, not for the missing operation.
        val catalogue = registry(
            TestPacks.variant(supportedOperations = listOf(TestPacks.verifiedGenericOperation())),
        )

        val error = assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(RecordingPort()).execute(catalogue, "test_brand", "test_variant", "generic.read-engine-speed")
        }

        assertEquals(SafetyViolationException.Reason.VARIANT_NOT_VERIFIED, error.reason)
        assertEquals("test_brand", error.profileId)
        assertEquals("test_variant", error.variantId)
        assertEquals("generic.read-engine-speed", error.operationId)
        assertTrue(error.message!!, error.message!!.contains("UNVERIFIED"))
    }

    @Test
    fun `the tier is unaffected by a refusal - documentation and execution are different questions`() = runTest {
        val catalogue = registry(
            TestPacks.verifiedVariant(supportedOperations = listOf(TestPacks.verifiedActiveTestOperation())),
        )

        assertEquals(Tier.T1_FULL_ENHANCED, catalogue.getTier("test_brand"))
        assertThrowsSuspend<SafetyViolationException> {
            PackOperationExecutor(RecordingPort()).execute(
                catalogue,
                "test_brand",
                "verified_variant",
                "enhanced.active-test",
            )
        }
    }
}
