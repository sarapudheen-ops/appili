package com.innova.diag.domain.gate

import com.innova.diag.core.transport.fake.FakeByteTransport
import com.innova.diag.domain.profile.ProfileStatus
import com.innova.diag.domain.profile.RiskClass
import com.innova.diag.domain.profile.pack.Tier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate's own rules, as rules: every test here answers "may this reach a transport?" and nothing
 * else. No socket, no coroutine, no Android — the gate is a decision over values, so the tests are too.
 *
 * The four behaviours the platform depends on, in order of importance:
 * 1. a write on a physical link is refused, always;
 * 2. an L1 generic read is allowed on a physical link, at any brand tier;
 * 3. an OEM operation on an unverified pack is refused **and recorded**;
 * 4. a refusal transmits nothing, because the gate never transmits.
 */
class OperationGateTest {

    private val gate = GateFixtures.gate()
    private val physical = PhysicalFake()
    private val disconnectedPhysical = PhysicalFake(connected = false)
    private val simulated = SimulatedFake()

    // --- 1. The physical floor -------------------------------------------------

    @Test
    fun `an active test on a physical transport is denied with physical execution disabled`() {
        val decision = gate.evaluate(
            GateRequest(
                operationId = "bench.actuator.radiator-fan",
                riskClass = RiskClass.ACTIVE_TEST,
                service = 0x2F,
                target = OperationTarget.Bench,
            ),
            physical,
        )

        val denied = decision as GateDecision.Denied
        assertEquals(DenialReason.PHYSICAL_EXECUTION_DISABLED, denied.reason)
        assertTrue(denied.detail.contains("Physical execution disabled"))
        assertFalse(decision.allowed)
    }

    @Test
    fun `a configuration write on a physical transport is denied with physical execution disabled`() {
        val decision = gate.evaluate(
            GateRequest(
                operationId = "coding.write-variant",
                riskClass = RiskClass.CONFIGURATION,
                service = 0x2E,
                target = OperationTarget.Bench,
            ),
            physical,
        )

        val denied = decision as GateDecision.Denied
        assertEquals(DenialReason.PHYSICAL_EXECUTION_DISABLED, denied.reason)
        assertTrue(denied.detail.startsWith("Physical execution disabled"))
    }

    @Test
    fun `a write is refused on a physical transport even when the link is down`() {
        // The floor is checked before the link: "not connected" must never be the reason a write on
        // a vehicle link is refused, because a reconnect would then unlock it.
        val decision = gate.evaluate(
            GateRequest("coding.write-variant", RiskClass.CONFIGURATION, 0x2E, OperationTarget.Bench),
            disconnectedPhysical,
        )

        assertEquals(
            DenialReason.PHYSICAL_EXECUTION_DISABLED,
            (decision as GateDecision.Denied).reason,
        )
    }

    // --- 2. L1 is universal ---------------------------------------------------

    @Test
    fun `every L1 generic mode is allowed on a physical transport`() {
        for (service in listOf(0x01, 0x03, 0x07, 0x09)) {
            val decision = gate.evaluate(GateRequest.universalGeneric(service), physical)
            assertTrue(
                "mode ${service.toString(16)} must be allowed on a physical link",
                decision is GateDecision.Allowed,
            )
        }
        assertEquals(4, gate.audit.allows().size)
    }

    @Test
    fun `an L1 read is allowed against an unverified brand as well`() {
        val innova = GateFixtures.innova()

        val decision = gate.evaluate(GateRequest.universalGeneric(0x01, target = OperationTarget.Vehicle(innova)), physical)

        assertTrue(decision is GateDecision.Allowed)
        assertEquals(innova.variantId, decision.variantId)
    }

    @Test
    fun `mode 04 is not an L1 read and is refused`() {
        val decision = gate.evaluate(GateRequest.universalGeneric(0x04), physical)

        // Clearing codes is a write, and this build does not implement it: an unknown mode is not
        // "close enough" to a universal read.
        assertEquals(
            DenialReason.OPERATION_NOT_DEFINED,
            (decision as GateDecision.Denied).reason,
        )
    }

    // --- 3. Unverified packs, and the audit trail ----------------------------

    @Test
    fun `an OEM operation on an unverified variant is denied and recorded in the audit log`() {
        val innova = GateFixtures.innova()
        assertEquals(ProfileStatus.UNVERIFIED, innova.verificationStatus)
        assertEquals(Tier.T4_UNVERIFIED, GateFixtures.shippedCatalogue().getTier("toyota"))

        val decision = gate.evaluate(
            GateRequest(
                operationId = "oem.read-engine-data",
                riskClass = RiskClass.READ_ONLY,
                service = 0x22,
                target = OperationTarget.Vehicle(innova),
            ),
            physical,
        )

        assertEquals(DenialReason.PROFILE_UNVERIFIED, (decision as GateDecision.Denied).reason)

        val recorded = gate.audit.denials().single()
        assertEquals(1L, recorded.sequence)
        assertEquals(GateFixtures.FIXED_TIMESTAMP, recorded.timestampMillis)
        assertEquals("oem.read-engine-data", recorded.operationId)
        assertEquals("innova_2007_gcc", recorded.variantId)
        assertEquals(TransportType.PHYSICAL, recorded.transportType)
        assertEquals(DenialReason.PROFILE_UNVERIFIED, recorded.reason)
        assertFalse(recorded.allowed)
        assertTrue(gate.audit.wasDenied("oem.read-engine-data"))
    }

    @Test
    fun `every shipped variant refuses OEM operations`() {
        val variants = GateFixtures.shippedCatalogue().listVariants()
        assertTrue("the shipped catalogue must define variants", variants.isNotEmpty())

        variants.forEach { variant ->
            val decision = gate.evaluate(
                GateRequest("oem.read-engine-data", RiskClass.READ_ONLY, 0x22, OperationTarget.Vehicle(variant)),
                physical,
            )
            assertEquals(
                variant.variantId,
                DenialReason.PROFILE_UNVERIFIED,
                (decision as GateDecision.Denied).reason,
            )
        }
        assertEquals(variants.size, gate.audit.denials().size)
    }

    @Test
    fun `a fully verified vehicle may run a verified OEM read`() {
        // The positive control: the gate is not a blanket "no". Without this, every other assertion
        // here would pass on a gate that refuses everything.
        val variant = GateFixtures.verifiedVehicle()
        val operation = variant.supportedOperations.single()
        val ecu = EcuIdentity.of(variant.ecuDefinitions.first())

        val decision = gate.evaluate(
            GateRequest.forOperation(operation, OperationTarget.Vehicle(variant), ecu),
            physical,
        )

        assertTrue(decision is GateDecision.Allowed)
        assertEquals(1, gate.audit.allows().size)
    }

    @Test
    fun `a verified vehicle refuses an ECU it does not describe`() {
        val variant = GateFixtures.verifiedVehicle()
        val operation = variant.supportedOperations.single()
        val stranger = EcuIdentity("Transmission ECU", "PN-OTHER", "NETWORK-TEST", "ADDRESSING-TEST")

        val decision = gate.evaluate(
            GateRequest.forOperation(operation, OperationTarget.Vehicle(variant), stranger),
            physical,
        )

        assertEquals(
            DenialReason.ECU_IDENTITY_MISMATCH,
            (decision as GateDecision.Denied).reason,
        )
    }

    @Test
    fun `a request that mislabels a write as a read is refused`() {
        val variant = GateFixtures.verifiedWriteVehicle()
        val operation = variant.supportedOperations.single()
        assertEquals(RiskClass.ACTIVE_TEST, operation.riskClass)

        val decision = gate.evaluate(
            GateRequest(
                operationId = operation.id,
                riskClass = RiskClass.READ_ONLY,
                service = 0x7A,
                target = OperationTarget.Vehicle(variant),
                ecu = EcuIdentity.of(variant.ecuDefinitions.first()),
            ),
            physical,
        )

        // A caller that calls an actuation a "read" does not get to skip the floor: the pack's own
        // risk class is the one that counts.
        assertEquals(
            DenialReason.PRECONDITIONS_UNMET,
            (decision as GateDecision.Denied).reason,
        )
    }

    @Test
    fun `a verified write on a verified vehicle runs only inside the simulator`() {
        val variant = GateFixtures.verifiedWriteVehicle()
        val request = GateRequest.forOperation(
            variant.supportedOperations.single(),
            OperationTarget.Vehicle(variant),
            EcuIdentity.of(variant.ecuDefinitions.first()),
        )

        assertTrue(gate.evaluate(request, simulated) is GateDecision.Allowed)
        assertEquals(
            DenialReason.PHYSICAL_EXECUTION_DISABLED,
            (gate.evaluate(request, physical) as GateDecision.Denied).reason,
        )
    }

    // --- 4. Links, sessions and the bench ------------------------------------

    @Test
    fun `an L1 read is refused when the link is not connected`() {
        val decision = gate.evaluate(GateRequest.universalGeneric(0x01), disconnectedPhysical)

        assertEquals(
            DenialReason.TRANSPORT_NOT_CONNECTED,
            (decision as GateDecision.Denied).reason,
        )
    }

    @Test
    fun `an L1 read is refused before the session is initialised`() {
        val decision = gate.evaluate(GateRequest.universalGeneric(0x01, sessionReady = false), physical)

        assertEquals(
            DenialReason.PRECONDITIONS_UNMET,
            (decision as GateDecision.Denied).reason,
        )
    }

    @Test
    fun `the bench exists only inside the simulator`() {
        val bench = GateRequest("bench.actuator.radiator-fan", RiskClass.ACTIVE_TEST, 0x2F, OperationTarget.Bench)

        assertTrue(gate.evaluate(bench, simulated) is GateDecision.Allowed)
        assertEquals(
            DenialReason.PHYSICAL_EXECUTION_DISABLED,
            (gate.evaluate(bench, physical) as GateDecision.Denied).reason,
        )
    }

    @Test
    fun `an unmarked test transport may read but not actuate`() = runBlocking {
        val memory = FakeByteTransport().apply { connect() }

        assertTrue(gate.evaluate(GateRequest.universalGeneric(0x01), memory) is GateDecision.Allowed)

        // A read-only frame aimed at the bench on a transport that claims nothing: no.
        val decision = gate.evaluate(
            GateRequest("bench.actuator.radiator-fan", RiskClass.READ_ONLY, 0x2F, OperationTarget.Bench),
            memory,
        )
        assertEquals(DenialReason.PRECONDITIONS_UNMET, (decision as GateDecision.Denied).reason)
    }

    @Test
    fun `a frame that is not hex is refused rather than guessed at`() {
        val request = GateRequest.forFrame("ZZZZ")

        assertNull(request.service)
        assertEquals(
            DenialReason.OPERATION_NOT_DEFINED,
            (gate.evaluate(request, physical) as GateDecision.Denied).reason,
        )
    }

    @Test
    fun `the gate records exactly one entry per evaluation`() {
        gate.evaluate(GateRequest.universalGeneric(0x01), physical)
        gate.evaluate(GateRequest("coding.write-variant", RiskClass.CONFIGURATION, 0x2E), physical)
        gate.evaluate(GateRequest.universalGeneric(0x03), disconnectedPhysical)

        assertEquals(3, gate.audit.size)
        assertEquals(listOf(1L, 2L, 3L), gate.audit.snapshot().map { it.sequence })
        assertEquals(1, gate.audit.allows().size)
        assertEquals(2, gate.audit.denials().size)
        assertEquals(3L, gate.audit.last()?.sequence)
    }
}
