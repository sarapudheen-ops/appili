package com.innova.diag.domain.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The audit log is evidence, so the properties that matter are the ones that keep it honest:
 * every decision is kept, order is stable, and nothing hands out a mutable handle to history.
 */
class AuditLogTest {

    private fun log(clock: () -> Long = { GateFixtures.FIXED_TIMESTAMP }): AuditLog = AuditLog(clock)

    private fun AuditLog.permit(operationId: String = "generic.read-mode01"): AuditEntry = append(
        operationId = operationId,
        variantId = OperationTarget.UNIVERSAL_VARIANT_ID,
        transportType = TransportType.PHYSICAL,
        allowed = true,
        reason = null,
        detail = "L1 generic read allowed.",
    )

    private fun AuditLog.refuse(operationId: String = "oem.read-engine-data"): AuditEntry = append(
        operationId = operationId,
        variantId = "innova_2007_gcc",
        transportType = TransportType.PHYSICAL,
        allowed = false,
        reason = DenialReason.PROFILE_UNVERIFIED,
        detail = "Variant innova_2007_gcc is UNVERIFIED.",
    )

    @Test
    fun `entries keep every field of the decision`() {
        val audit = log { 42L }

        val entry = audit.refuse()

        assertEquals(1L, entry.sequence)
        assertEquals(42L, entry.timestampMillis)
        assertEquals("oem.read-engine-data", entry.operationId)
        assertEquals("innova_2007_gcc", entry.variantId)
        assertEquals(TransportType.PHYSICAL, entry.transportType)
        assertFalse(entry.allowed)
        assertEquals(DenialReason.PROFILE_UNVERIFIED, entry.reason)
        assertEquals("Variant innova_2007_gcc is UNVERIFIED.", entry.detail)
    }

    @Test
    fun `sequence numbers are 1-based and follow append order`() {
        val audit = log()

        repeat(3) { index -> audit.permit("generic.read-mode0${index + 1}") }

        assertEquals(listOf(1L, 2L, 3L), audit.snapshot().map { it.sequence })
        assertEquals(
            listOf("generic.read-mode01", "generic.read-mode02", "generic.read-mode03"),
            audit.snapshot().map { it.operationId },
        )
        assertEquals(3L, audit.last()?.sequence)
    }

    @Test
    fun `the clock is read per entry`() {
        var now = 1_000L
        val audit = log { now }

        audit.permit()
        now = 2_000L
        audit.permit()

        assertEquals(listOf(1_000L, 2_000L), audit.snapshot().map { it.timestampMillis })
    }

    @Test
    fun `a snapshot is a copy and history cannot be trimmed`() {
        val audit = log()
        audit.permit()

        val first = audit.snapshot()
        audit.permit()

        assertEquals(1, first.size)
        assertEquals(2, audit.size)
        // The returned list is a plain `List`, so a caller cannot remove an inconvenient refusal.
        assertTrue(first is List<AuditEntry>)
    }

    @Test
    fun `permits and refusals can be read separately`() {
        val audit = log()
        audit.permit()
        audit.refuse()
        audit.permit("generic.read-mode03")

        assertEquals(3, audit.size)
        assertEquals(2, audit.allows().size)
        assertEquals(1, audit.denials().size)
        assertEquals("oem.read-engine-data", audit.denials().single().operationId)
        assertTrue(audit.wasDenied("oem.read-engine-data"))
        assertFalse(audit.wasDenied("generic.read-mode01"))
    }

    @Test
    fun `an empty log has no last entry`() {
        val audit = log()

        assertEquals(0, audit.size)
        assertNull(audit.last())
        assertEquals(emptyList<AuditEntry>(), audit.snapshot())
    }

    @Test
    fun `recording a gate decision keeps its reason and transport`() {
        val audit = log()
        val denied = GateDecision.Denied(
            operationId = "coding.write-variant",
            variantId = OperationTarget.BENCH_VARIANT_ID,
            reason = DenialReason.PHYSICAL_EXECUTION_DISABLED,
            detail = "Physical execution disabled — CONFIGURATION is a write-class operation.",
        )

        val entry = audit.record(TransportType.PHYSICAL, denied)

        assertEquals(DenialReason.PHYSICAL_EXECUTION_DISABLED, entry.reason)
        assertEquals(TransportType.PHYSICAL, entry.transportType)
        assertFalse(entry.allowed)
    }

    @Test
    fun `rendered lines name the verdict, the operation, the variant and the transport`() {
        val audit = log()

        val line = audit.refuse().render()

        assertTrue(line.contains("DENIED(PROFILE_UNVERIFIED)"))
        assertTrue(line.contains("op=oem.read-engine-data"))
        assertTrue(line.contains("variant=innova_2007_gcc"))
        assertTrue(line.contains("transport=PHYSICAL"))
        assertTrue(line.contains("1@${GateFixtures.FIXED_TIMESTAMP}"))
    }

    @Test
    fun `an allowed decision renders as allowed and carries no reason`() {
        val audit = log()

        val entry = audit.permit()

        assertTrue(entry.render().contains("ALLOWED"))
        assertNull(entry.reason)
    }
}
