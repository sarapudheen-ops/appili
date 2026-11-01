package com.innova.diag.domain.profile

/**
 * Thrown when a caller asks for something this repository's safety policy forbids from happening.
 *
 * This is **not** a vehicle condition (those are [com.innova.diag.domain.obd.DiagnosticResult] values)
 * and not a formatting problem with the profile — it is a refusal. It is thrown *before* any byte is
 * handed to a transport, so catching it can never mean "the command ran and failed".
 *
 * @property reason why the transmission was refused; stable enough to assert on and to show a user.
 * @property operationId the operation that was refused, when one had been resolved.
 */
class SafetyViolationException(
    val reason: Reason,
    message: String,
    val profileId: String? = null,
    val operationId: String? = null,
    val variantId: String? = null,
) : Exception(message) {

    /** Why a physical transmission was denied. Every value means "nothing was sent". */
    enum class Reason {

        /** The profile as a whole is `UNVERIFIED` (or one of its ECUs is). */
        PROFILE_NOT_VERIFIED,

        /** The profile is `SIMULATOR_ONLY`, so it may only ever be exercised against the simulator. */
        SIMULATOR_ONLY,

        /** The requested ECU is not described by this profile — nothing to verify against. */
        ECU_NOT_DESCRIBED,

        /** The requested operation is not declared by this profile — nothing to verify against. */
        OPERATION_NOT_DECLARED,

        /** The operation is `ACTIVE_TEST` or `CONFIGURATION`: physical writes are forbidden outright. */
        RISK_CLASS_FORBIDDEN,

        /** The ECU definition does not declare the operation's service, so it was never captured. */
        SERVICE_NOT_DECLARED,

        /** The requested variant is not described by this brand pack — nothing to verify against. */
        VARIANT_NOT_DESCRIBED,

        /** The variant is `UNVERIFIED` (or simulator-only): no physical execution may leave from it. */
        VARIANT_NOT_VERIFIED,

        /** An ECU definition in the variant is not verified, so the request has no captured target. */
        ECU_NOT_VERIFIED,

        /** The operation exists but is still `UNVERIFIED` — no captured bytes may be transmitted. */
        OPERATION_NOT_VERIFIED,

        /** The operation is verified but carries no captured service bytes, so there is nothing to send. */
        SERVICE_BYTES_UNKNOWN,
    }
}
