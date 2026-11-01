package com.innova.diag.domain.gate

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.PhysicalTransport
import com.innova.diag.core.transport.SimulatedTransport
import com.innova.diag.domain.profile.RiskClass

/**
 * What kind of link a request would travel over.
 *
 * Three values, not two, because "not physical" is not the same as "simulator": a socket fake in a
 * unit test is neither, and it must not inherit the simulator's permission to actuate.
 */
enum class TransportType {

    /** A real adapter on a real bus. Reads only. */
    PHYSICAL,

    /** The in-process mock ECU. Reads and writes, because nobody is in the car. */
    SIMULATED,

    /** A test double: reads only, by default. */
    IN_MEMORY;

    val isPhysical: Boolean get() = this == PHYSICAL

    val isSimulated: Boolean get() = this == SIMULATED
}

/**
 * The classification rule behind [TransportType], kept in one place so the gate and its callers can
 * never disagree about what a given transport is.
 *
 * The two capability markers are checked before the fallback, in that order: a transport that
 * claimed to be both physical and simulated would be a defect, and reading it as physical is the
 * fail-closed choice.
 */
object TransportTypeGuard {

    /** How [transport] is classified. Marker interfaces only — no reflection, no name matching. */
    fun classify(transport: ByteTransport): TransportType = when (transport) {
        is PhysicalTransport -> TransportType.PHYSICAL
        is SimulatedTransport -> TransportType.SIMULATED
        else -> TransportType.IN_MEMORY
    }

    /** True for anything that could change the vehicle rather than report on it. */
    fun isWrite(riskClass: RiskClass): Boolean = riskClass != RiskClass.READ_ONLY

    /**
     * The lowest-layer rule of `AGENTS.md` Rule 2: a write-class operation on a physical link is
     * refused before any other fact — profile state, connection state, ECU identity — is looked at.
     */
    fun forbidsPhysicalWrite(transportType: TransportType, riskClass: RiskClass): Boolean =
        transportType.isPhysical && isWrite(riskClass)
}
