package com.innova.diag.domain.profile

import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.ObdCommandPort

/**
 * The only way a profile can cause a byte to reach a vehicle.
 *
 * Every check runs **before** the transport port is touched, and every failed check throws
 * [SafetyViolationException] rather than returning a result: a caller must not be able to read a
 * refusal as a vehicle answer, and the transport must see nothing at all.
 *
 * ### The gate, in order
 * 1. the ECU must be described by the profile ([SafetyViolationException.Reason.ECU_NOT_DESCRIBED]);
 * 2. the operation must be declared by the profile ([SafetyViolationException.Reason.OPERATION_NOT_DECLARED]);
 * 3. the **profile** status must be [ProfileStatus.VERIFIED] — an `UNVERIFIED` profile is denied and
 *    a `SIMULATOR_ONLY` one is denied with its own reason, because "only works in the simulator" must
 *    never read as "untested";
 * 4. the **ECU** status must be [ProfileStatus.VERIFIED] (redundant with 3 today, because
 *    [VehicleProfile.status] is the weakest ECU; kept so a future per-ECU gate cannot be missed);
 * 5. the operation's [RiskClass] must be [RiskClass.READ_ONLY] — `ACTIVE_TEST` and `CONFIGURATION`
 *    are refused outright, even on a verified profile (AGENTS.md Rule 2);
 * 6. the ECU must declare the operation's service, i.e. someone captured that it exists
 *    ([SafetyViolationException.Reason.SERVICE_NOT_DECLARED]).
 *
 * Step 6 is why the candidate profile denies everything even in principle: its `supportedServices`
 * lists are empty until the physical bus dump arrives, and an empty list is not a claim of support.
 *
 * ### What it deliberately does not do
 * - It never sets a header (`ATSH`), never picks an address from a "likely" default, and never
 *   retries with another protocol: those are exactly the guesses AGENTS.md Rule 1 forbids.
 * - It does not interpret the response. Decoding stays in `domain.obd`.
 *
 * @param port the transmission seam; `ElmObdCommandPort` in production, a recorder in tests.
 */
class ProfileOperationExecutor(private val port: ObdCommandPort) {

    /**
     * Authorises and then transmits [operationId] against the ECU named [ecuName] in [profile].
     *
     * @return the adapter's answer as a [DiagnosticResult]; a vehicle failure is *not* a safety
     *   violation and is reported normally.
     * @throws SafetyViolationException when the gate above refuses — nothing has been transmitted.
     * @throws kotlinx.coroutines.CancellationException if the caller is cancelled.
     */
    suspend fun execute(
        profile: VehicleProfile,
        ecuName: String,
        operationId: String,
    ): DiagnosticResult<String> {
        val ecu = profile.ecu(ecuName)
            ?: throw SafetyViolationException(
                reason = SafetyViolationException.Reason.ECU_NOT_DESCRIBED,
                message = "profile \"${profile.id}\" does not describe an ECU named \"$ecuName\"",
                profileId = profile.id,
                operationId = operationId,
            )
        val operation = profile.operation(operationId)
            ?: throw SafetyViolationException(
                reason = SafetyViolationException.Reason.OPERATION_NOT_DECLARED,
                message = "profile \"${profile.id}\" does not declare an operation \"$operationId\"",
                profileId = profile.id,
                operationId = operationId,
            )
        val command = authorise(profile, ecu, operation)
        return port.execute(command)
    }

    /**
     * Runs the gate and returns the exact bytes to send.
     *
     * Exposed to tests so every refusal can be asserted without a coroutine; production callers use
     * [execute], which cannot skip it. Throws [SafetyViolationException] and never returns a "no".
     */
    internal fun authorise(
        profile: VehicleProfile,
        ecu: EcuDefinition,
        operation: DiagnosticOperation,
    ): String {
        val status = profile.status
        if (status != ProfileStatus.VERIFIED) {
            val reason = if (status == ProfileStatus.SIMULATOR_ONLY) {
                SafetyViolationException.Reason.SIMULATOR_ONLY
            } else {
                SafetyViolationException.Reason.PROFILE_NOT_VERIFIED
            }
            throw SafetyViolationException(
                reason = reason,
                message = "profile \"${profile.id}\" (${profile.displayName}) is $status; " +
                    "operation \"${operation.id}\" is not transmitted to a physical vehicle",
                profileId = profile.id,
                operationId = operation.id,
            )
        }
        if (ecu.status != ProfileStatus.VERIFIED) {
            throw safetyViolation(
                profile = profile,
                operation = operation,
                reason = SafetyViolationException.Reason.PROFILE_NOT_VERIFIED,
                detail = "ECU \"${ecu.name}\" is ${ecu.status}",
            )
        }
        if (operation.riskClass != RiskClass.READ_ONLY) {
            throw safetyViolation(
                profile = profile,
                operation = operation,
                reason = SafetyViolationException.Reason.RISK_CLASS_FORBIDDEN,
                detail = "${operation.riskClass} operations are wired to the simulator only",
            )
        }
        if (!ecu.supports(operation.service)) {
            throw safetyViolation(
                profile = profile,
                operation = operation,
                reason = SafetyViolationException.Reason.SERVICE_NOT_DECLARED,
                detail = "ECU \"${ecu.name}\" does not declare service 0x${operation.service.toString(16)}",
            )
        }
        return operation.serviceBytes.hex
    }

    private fun safetyViolation(
        profile: VehicleProfile,
        operation: DiagnosticOperation,
        reason: SafetyViolationException.Reason,
        detail: String,
    ): SafetyViolationException = SafetyViolationException(
        reason = reason,
        message = "refusing \"${operation.id}\" on profile \"${profile.id}\": $detail",
        profileId = profile.id,
        operationId = operation.id,
    )
}
