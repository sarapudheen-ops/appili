package com.innova.diag.domain.gate

/**
 * One gate decision, exactly as it was taken.
 *
 * Every field is a fact recorded at decision time, not a reference to live state: a reader must be
 * able to reconstruct what the gate saw an hour later, when the link is gone and the pack has been
 * reloaded. That is why [transportType] and [variantId] are copies rather than lookups.
 *
 * @property sequence 1-based arrival order. Monotonic and never reused.
 * @property timestampMillis wall clock at append; the default clock is [System.currentTimeMillis].
 * @property reason `null` for an allowed decision, always set for a denial.
 */
data class AuditEntry(
    val sequence: Long,
    val timestampMillis: Long,
    val operationId: String,
    val variantId: String,
    val transportType: TransportType,
    val allowed: Boolean,
    val reason: DenialReason?,
    val detail: String,
) {

    /** One log line: stable enough to grep, readable enough to quote in a bug report. */
    fun render(): String {
        val verdict = if (allowed) "ALLOWED" else "DENIED(${reason ?: DenialReason.PRECONDITIONS_UNMET})"
        return "$sequence@$timestampMillis $verdict op=$operationId variant=$variantId " +
            "transport=$transportType — $detail"
    }
}

/**
 * The append-only record of every decision the gate has taken.
 *
 * ### Why append-only
 * A safety log that can be edited is not evidence. There is no `remove`, no `clear` and no setter:
 * [snapshot] hands out a copy, so a caller cannot mutate history through the returned list either.
 *
 * ### Why in memory
 * No file, no permission, no database. The log lives as long as the process, which is what makes it
 * shareable without a privacy question: it never outlives the session it describes, and it holds
 * operation ids and variant ids — never a VIN, never a Bluetooth address.
 */
class AuditLog(private val clock: () -> Long = System::currentTimeMillis) {

    private val lock = Any()
    private val entries = mutableListOf<AuditEntry>()

    /** How many decisions have been recorded. */
    val size: Int get() = synchronized(lock) { entries.size }

    /**
     * Appends one decision.
     *
     * Written under a lock because the gate is consulted from the scan coroutine, the live-data
     * coroutine and the debug console; sequence numbers are what make the log order meaningful.
     */
    fun append(
        operationId: String,
        variantId: String,
        transportType: TransportType,
        allowed: Boolean,
        reason: DenialReason?,
        detail: String,
    ): AuditEntry = synchronized(lock) {
        val entry = AuditEntry(
            sequence = entries.size + 1L,
            timestampMillis = clock(),
            operationId = operationId,
            variantId = variantId,
            transportType = transportType,
            allowed = allowed,
            reason = reason,
            detail = detail,
        )
        entries.add(entry)
        entry
    }

    /** Records [decision] as taken over [transportType]. */
    fun record(transportType: TransportType, decision: GateDecision): AuditEntry = append(
        operationId = decision.operationId,
        variantId = decision.variantId,
        transportType = transportType,
        allowed = decision.allowed,
        reason = (decision as? GateDecision.Denied)?.reason,
        detail = decision.detail,
    )

    /** A copy of everything recorded, oldest first. */
    fun snapshot(): List<AuditEntry> = synchronized(lock) { entries.toList() }

    /** Only the refusals — the list a reviewer reads when asking "what did we block?". */
    fun denials(): List<AuditEntry> = snapshot().filter { entry -> !entry.allowed }

    /** Only the permits. */
    fun allows(): List<AuditEntry> = snapshot().filter { entry -> entry.allowed }

    /** True when [operationId] was refused at least once. */
    fun wasDenied(operationId: String): Boolean =
        denials().any { entry -> entry.operationId == operationId }

    /** The most recent decision, or `null` before the first one. */
    fun last(): AuditEntry? = snapshot().lastOrNull()
}
