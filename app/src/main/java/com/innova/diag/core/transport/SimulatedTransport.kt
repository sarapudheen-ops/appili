package com.innova.diag.core.transport

/**
 * A [ByteTransport] that talks to an in-process simulator instead of a vehicle.
 *
 * A capability marker, exactly like [PhysicalTransport] — and deliberately its opposite. The
 * simulator has no bus, so nothing it does can move a car; that is the whole reason `AGENTS.md`
 * Rule 2 lets actuator tests, routines and configuration changes exist there and nowhere else.
 *
 * ### Why "neither marker" is a third state
 * A transport that implements neither this interface nor [PhysicalTransport] is test scaffolding
 * held in memory. [com.innova.diag.domain.gate.TransportTypeGuard] classifies it as
 * `IN_MEMORY`, which permits reads and refuses writes: a new transport has to state what it *is*
 * before it is trusted with anything that changes a vehicle. Fail-closed, not guessed.
 */
interface SimulatedTransport : ByteTransport
