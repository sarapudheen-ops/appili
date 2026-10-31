package com.innova.diag.domain.session

/**
 * One exchanged command, exactly as it went over the link.
 *
 * [response] is `null` when nothing usable came back; [failure] then carries a stable machine token
 * (`"TIMEOUT"`, `"NO_DATA"`, `"LINK_CLOSED"`, …). The two are never both set and never both null for
 * a recorded command: an exchange either answered or it did not, and the log says which.
 */
data class SessionEntry(
    val index: Int,
    val sentAtMillis: Long,
    val receivedAtMillis: Long?,
    val command: String,
    val response: String?,
    val failure: String?,
) {
    /** True when a response was received, whatever its content. */
    val answered: Boolean get() = response != null
}

/** An immutable view of the session, taken at export time. */
data class SessionSnapshot(
    val startedAtMillis: Long,
    val exportedAtMillis: Long,
    val adapterIdentity: String?,
    val protocol: String?,
    val entries: List<SessionEntry>,
)

/**
 * The in-memory record of everything this session transmitted and received.
 *
 * It exists for two reasons: a technician needs to see what was actually asked, and a bug report
 * without the command log is a guess. It records **verbatim** — no interpretation, no rounding, no
 * synthesised "0" for a missing answer — and it is bounded only by the session's own length, because
 * truncating a diagnostic log is how evidence disappears.
 *
 * Thread-safety: recording happens from whatever coroutine is running the exchange, so every
 * mutation is guarded by a monitor. Entries are tiny and appends are O(1); the lock is never held
 * across I/O.
 */
class SessionLog(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private val entries = mutableListOf<SessionEntry>()
    private var adapterIdentity: String? = null
    private var protocol: String? = null

    /** When this session started, in epoch milliseconds. */
    val startedAtMillis: Long = clock()

    /** Appends one exchange and returns the stored entry. */
    fun record(
        command: String,
        sentAtMillis: Long,
        response: String?,
        failure: String?,
        receivedAtMillis: Long?,
    ): SessionEntry = synchronized(lock) {
        val entry = SessionEntry(
            index = entries.size + 1,
            sentAtMillis = sentAtMillis,
            receivedAtMillis = receivedAtMillis,
            command = command,
            response = response,
            failure = failure,
        )
        entries += entry
        entry
    }

    /** Remembers what the adapter claimed to be, for the export header. */
    fun recordAdapterInfo(identity: String?, protocol: String?) = synchronized(lock) {
        this.adapterIdentity = identity
        this.protocol = protocol
    }

    /** Number of recorded exchanges. */
    val size: Int get() = synchronized(lock) { entries.size }

    /** A defensive copy of the current session, stamped with the export time. */
    fun snapshot(): SessionSnapshot = synchronized(lock) {
        SessionSnapshot(
            startedAtMillis = startedAtMillis,
            exportedAtMillis = clock(),
            adapterIdentity = adapterIdentity,
            protocol = protocol,
            entries = entries.toList(),
        )
    }

    /** Drops every entry and the adapter header. Called when a new session starts. */
    fun clear() = synchronized(lock) {
        entries.clear()
        adapterIdentity = null
        protocol = null
    }
}
