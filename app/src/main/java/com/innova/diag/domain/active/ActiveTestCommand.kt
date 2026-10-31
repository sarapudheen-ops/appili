package com.innova.diag.domain.active

/**
 * One typed active test: the exact bytes used to take control, and the exact bytes used to give it
 * back.
 *
 * Nothing here is built at runtime from user input or from a profile string — both frames are
 * constants of the command, so the "no invented packets" rule (`AGENTS.md` Rule 5) holds structurally.
 *
 * ### Why return-control is part of the command
 * An active test that cannot name how to release the actuator is not an active test, it is a hazard.
 * Making [returnControlFrame] mandatory means a caller cannot add a new actuation without saying how
 * it ends, and the controller can always send it from a `finally` block (`docs/SAFETY_POLICY.md` §3.5).
 */
sealed interface ActiveTestCommand {

    /** Human label for the UI and the session log, e.g. `"Radiator Fan Relay Test"`. */
    val label: String

    /** The frame that starts or changes the actuation. Uppercase hex, no terminator. */
    fun activationFrame(): String

    /** The frame that hands control back to the ECU. Uppercase hex, no terminator. */
    fun returnControlFrame(): String

    /**
     * UDS InputOutputControlByIdentifier (`0x2F`) on [did], setting the control state to
     * short-term-adjustment (`0x03`) with [value].
     *
     * Return control is `0x2F <did> 00` — **ReturnControlToECU**, control option `0x00`, which is the
     * only way to tell the ECU "you drive this again".
     */
    data class IoControl(
        val did: Int,
        val value: Int,
        override val label: String,
    ) : ActiveTestCommand {

        init {
            require(did in 0..0xFFFF) { "DID must be a 16-bit identifier: $did" }
            require(value in 0x00..0xFF) { "control value must be a byte: $value" }
            require(label.isNotBlank()) { "an active test must be named" }
        }

        override fun activationFrame(): String = "2F${hex16(did)}03${hex8(value)}"

        override fun returnControlFrame(): String = "2F${hex16(did)}00"
    }

    /**
     * UDS RoutineControl (`0x31`) on [routineId]: `0x31 01` starts, `0x31 02` stops.
     *
     * For routines the "give control back" frame is the stop-routine sub-function, which is the routine
     * equivalent of ReturnControlToECU; both exist because the bench ECU must be able to simulate both
     * services, and the controller must have an ending for both.
     */
    data class Routine(
        val routineId: Int,
        override val label: String,
    ) : ActiveTestCommand {

        init {
            require(routineId in 0..0xFFFF) { "routine id must be 16 bits: $routineId" }
            require(label.isNotBlank()) { "an active test must be named" }
        }

        override fun activationFrame(): String = "3101${hex16(routineId)}"

        override fun returnControlFrame(): String = "3102${hex16(routineId)}"
    }

    companion object {

        /**
         * The one actuator the simulated screen offers.
         *
         * A bench DID (`0x2001`) that belongs to no real module: it cannot be mistaken for a captured
         * identifier from `docs/VEHICLE_MATRIX.md`, because no such capture exists.
         */
        val RADIATOR_FAN_RELAY: ActiveTestCommand = IoControl(
            did = 0x2001,
            value = 0x01,
            label = "Radiator Fan Relay Test",
        )
    }
}

/** 8-bit hex, uppercase, no prefix. */
internal fun hex8(value: Int): String =
    value.toString(16).uppercase().padStart(2, '0')

/** 16-bit hex, uppercase, no prefix. */
internal fun hex16(value: Int): String =
    value.toString(16).uppercase().padStart(4, '0')
