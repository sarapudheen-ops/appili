package com.innova.diag.adapter.elm

/**
 * Brings an ELM327-style adapter to a known, quiet operating state.
 *
 * The sequence is exactly five adapter-configuration commands, in this order:
 *
 * | Step | Command | Meaning |
 * |---|---|---|
 * | 1 | `ATZ` | Reset — the adapter reboots and prints its banner |
 * | 2 | `ATE0` | Echo off — responses stop repeating the command |
 * | 3 | `ATL0` | Linefeeds off — one carriage return per line |
 * | 4 | `ATS0` | Spaces off — raw hex without separators |
 * | 5 | `ATSP0` | Protocol **auto-detection** — the adapter picks the protocol itself |
 *
 * ### Deliberate omissions
 * - **No `ATSH`.** Vehicle headers are unknown for this pilot (`docs/VEHICLE_MATRIX.md` marks
 *   Header as `UNKNOWN - PENDING_PHYSICAL_LOG`), and guessing one would violate `AGENTS.md` Rule 1.
 *   The adapter must never be told a header that was not verified against a physical capture.
 * - **No protocol pinning.** Only `ATSP0` (automatic). Nothing here selects ISO 9141-2, KWP, CAN or
 *   any other protocol; that is a verified-matrix decision, not an initializer default.
 * - **No vehicle requests.** No PIDs, no Mode 01, no diagnostic session. After [initialize] the
 *   adapter is configured, and nothing more — which is precisely why this phase cannot read a
 *   vehicle.
 *
 * Failures abort the sequence: a step that does not answer becomes an [ElmException] and the caller
 * never sees a "partly initialized" adapter reported as success.
 */
class ElmInitializer(
    private val channel: ElmCommandChannel,
    private val resetTimeoutMillis: Long = DEFAULT_RESET_TIMEOUT_MILLIS,
) {

    /**
     * Runs the full sequence, returning each step's normalized response in order.
     *
     * The reset gets [resetTimeoutMillis] because `ATZ` reboots the adapter and legitimately takes
     * far longer than a normal command; the remaining steps use the channel's default timeout.
     *
     * @throws ElmException from the first step that fails; later steps are not attempted.
     */
    suspend fun initialize(): List<String> = SEQUENCE.map { command ->
        val timeout = if (command == RESET_COMMAND) resetTimeoutMillis else null
        channel.execute(command, timeoutMillis = timeout)
    }

    companion object {
        /** `ATZ` — adapter reset. */
        const val RESET_COMMAND: String = "ATZ"

        /** The full initialization sequence, in execution order. */
        val SEQUENCE: List<String> = listOf(
            RESET_COMMAND,
            "ATE0",
            "ATL0",
            "ATS0",
            "ATSP0",
        )

        /** `ATZ` reboots the adapter, so it needs a longer budget than a normal command. */
        const val DEFAULT_RESET_TIMEOUT_MILLIS: Long = 5_000L
    }
}
