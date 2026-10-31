package com.innova.diag.ui

/**
 * Decides whether the debug-only raw console may send a line.
 *
 * The console exists so an engineer can ask the adapter `ATI`, `ATDP`, `ATRV` and friends without a
 * rebuild. It is **not** a general command prompt:
 *
 * - only `AT…` adapter commands are allowed, so a vehicle mode cannot be typed into it. That keeps
 *   the debug path as read-only as the shipping one: there is no way to type `04`, `2E`, `31` or a
 *   manufacturer mode here and have it transmitted;
 * - one line per send, because a `\r` inside the text would inject a second command and desynchronise
 *   the request/response pairing;
 * - blank lines are refused so the adapter is never prodded for nothing.
 *
 * The rule is enforced twice on purpose: here (before anything is written) and by the composable's
 * `BuildConfig.DEBUG` gate (so the field does not exist in a release build at all).
 */
object AtCommandGuard {

    sealed interface Verdict {
        /** Safe to transmit. */
        data object Allowed : Verdict

        /** Refused, with a reason that can be shown to the person typing. */
        data class Rejected(val reason: String) : Verdict
    }

    /** Commands shorter than this cannot be a valid `AT…` command. */
    private const val MINIMUM_LENGTH = 2

    fun check(command: String): Verdict {
        val trimmed = command.trim()
        return when {
            trimmed.isEmpty() -> Verdict.Rejected("Enter a command, for example ATI.")
            trimmed.any { character -> character == '\r' || character == '\n' } ->
                Verdict.Rejected("One command per line: line breaks are refused.")
            !trimmed.uppercase().startsWith(PREFIX) || trimmed.length <= MINIMUM_LENGTH ->
                Verdict.Rejected(
                    "Only AT adapter commands are allowed here. Vehicle modes are read-only and " +
                        "go through the scan.",
                )
            else -> Verdict.Allowed
        }
    }

    private const val PREFIX = "AT"
}
