package com.innova.diag.domain.gate

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.SimulatedTransport
import com.innova.diag.core.transport.fake.FakeByteTransport
import com.innova.diag.domain.active.MockEcuSimulator
import com.innova.diag.domain.profile.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Classification is the gate's only input about the link, so it gets its own tests: the three
 * categories must stay distinguishable, and the marker interfaces must stay the only authority.
 */
class TransportTypeGuardTest {

    @Test
    fun `a physical transport is classified as physical`() {
        assertEquals(TransportType.PHYSICAL, TransportTypeGuard.classify(PhysicalFake()))
    }

    @Test
    fun `a simulator is classified as simulated`() {
        assertEquals(TransportType.SIMULATED, TransportTypeGuard.classify(SimulatedFake()))
    }

    @Test
    fun `an unmarked test double is classified as in memory`() {
        assertEquals(TransportType.IN_MEMORY, TransportTypeGuard.classify(FakeByteTransport()))
    }

    @Test
    fun `the shipped mock ECU claims the simulated marker, not the physical one`() {
        val simulator: ByteTransport = MockEcuSimulator()

        assertTrue(simulator is SimulatedTransport)
        assertEquals(TransportType.SIMULATED, TransportTypeGuard.classify(simulator))
    }

    @Test
    fun `only read-only is not a write`() {
        assertFalse(TransportTypeGuard.isWrite(RiskClass.READ_ONLY))
        assertTrue(TransportTypeGuard.isWrite(RiskClass.ACTIVE_TEST))
        assertTrue(TransportTypeGuard.isWrite(RiskClass.CONFIGURATION))
    }

    @Test
    fun `a physical link forbids exactly the write classes`() {
        assertFalse(TransportTypeGuard.forbidsPhysicalWrite(TransportType.PHYSICAL, RiskClass.READ_ONLY))
        assertTrue(TransportTypeGuard.forbidsPhysicalWrite(TransportType.PHYSICAL, RiskClass.ACTIVE_TEST))
        assertTrue(TransportTypeGuard.forbidsPhysicalWrite(TransportType.PHYSICAL, RiskClass.CONFIGURATION))
    }

    @Test
    fun `a simulated or in-memory link does not invoke the physical floor`() {
        for (type in listOf(TransportType.SIMULATED, TransportType.IN_MEMORY)) {
            RiskClass.entries.forEach { riskClass ->
                assertFalse(
                    "$type must not be held to the physical rule",
                    TransportTypeGuard.forbidsPhysicalWrite(type, riskClass),
                )
            }
        }
    }

    @Test
    fun `the transport type knows what it is`() {
        assertTrue(TransportType.PHYSICAL.isPhysical)
        assertFalse(TransportType.PHYSICAL.isSimulated)
        assertTrue(TransportType.SIMULATED.isSimulated)
        assertFalse(TransportType.SIMULATED.isPhysical)
        assertFalse(TransportType.IN_MEMORY.isPhysical)
        assertFalse(TransportType.IN_MEMORY.isSimulated)
    }
}
