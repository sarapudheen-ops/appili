package com.innova.diag.domain.gate

import com.innova.diag.core.transport.ByteTransport
import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.profile.RiskClass
import com.innova.diag.domain.profile.pack.SupportedOperation
import com.innova.diag.domain.profile.pack.VariantEcuDefinition
import com.innova.diag.domain.profile.pack.VehicleVariantPack

/**
 * The two facts about a link that the gate needs, copied out of the transport.
 *
 * Copying rather than holding the transport lets a caller that has no `ByteTransport` in hand — a
 * profile executor driven by a command port, a test — still ask the gate a complete question.
 */
data class TransportSnapshot(val type: TransportType, val connected: Boolean) {

    companion object {

        /** Reads the class and the connection state of [transport] once, at this instant. */
        fun of(transport: ByteTransport): TransportSnapshot = TransportSnapshot(
            type = TransportTypeGuard.classify(transport),
            connected = transport.state().value == TransportState.CONNECTED,
        )
    }
}

/**
 * The ECU an OEM operation is aimed at.
 *
 * Identity is compared as a whole — name, part number, network and addressing — because "the same
 * ECU on a different bus" is a different ECU, and addressing the wrong one is how a test actuates the
 * wrong component (`AGENTS.md` Rule 1: fail closed on an unverified header).
 */
data class EcuIdentity(
    val name: String,
    val partNumber: String,
    val network: String,
    val addressing: String,
) {

    companion object {

        /** The identity a pack declares for one ECU. */
        fun of(definition: VariantEcuDefinition): EcuIdentity = EcuIdentity(
            name = definition.name,
            partNumber = definition.partNumber,
            network = definition.network,
            addressing = definition.addressing,
        )
    }
}

/**
 * What an operation is aimed at — and therefore which rules apply.
 *
 * The three cases are genuinely different questions. "Any Vehicle" has no profile at all, so only the
 * universal L1 layer can be reached through it. A [Bench] command belongs to the simulator and to
 * nothing else. A [Vehicle] is the only case where a pack can grant anything, and even then only for
 * operations it has captured and verified.
 */
sealed interface OperationTarget {

    /** What the audit log records in the variant column. */
    val variantId: String

    /** No profile: the universal "Any Vehicle" path (L1 generic OBD-II only). */
    data object UniversalGeneric : OperationTarget {
        override val variantId: String get() = OperationTarget.UNIVERSAL_VARIANT_ID
    }

    /** A described vehicle. */
    data class Vehicle(val variant: VehicleVariantPack) : OperationTarget {
        override val variantId: String get() = variant.variantId
    }

    /** The in-process mock ECU. Never a vehicle. */
    data object Bench : OperationTarget {
        override val variantId: String get() = OperationTarget.BENCH_VARIANT_ID
    }

    companion object {

        /** The pseudo-variant of the "Any Vehicle — Generic OBD-II Only" entry. */
        const val UNIVERSAL_VARIANT_ID: String = "any_vehicle"

        /** The pseudo-variant of the bench simulator. */
        const val BENCH_VARIANT_ID: String = "simulator_bench"
    }
}

/**
 * One question put to the gate.
 *
 * [service] is the OBD/UDS service byte (the first byte of the request frame) when the caller knows
 * it. It is what separates the universal L1 modes from everything else: SAE J1979 modes 01, 03, 07 and
 * 09 mean the same thing on every OBD-II car, so they need no profile.
 *
 * @property sessionReady whether the adapter finished its init sequence. A socket alone is not a
 *   diagnostic session, and the gate refuses to authorise work in one.
 */
data class GateRequest(
    val operationId: String,
    val riskClass: RiskClass,
    val service: Int?,
    val target: OperationTarget = OperationTarget.UniversalGeneric,
    val ecu: EcuIdentity? = null,
    val sessionReady: Boolean = true,
) {

    /** Mirrors [OperationTarget.variantId] for the audit log. */
    val variantId: String get() = target.variantId

    companion object {

        private val HEX_ONLY = Regex("[0-9A-Fa-f]+")

        /**
         * The SAE J1979 modes this platform may request: current data, stored DTCs, pending DTCs and
         * vehicle information (which carries the VIN). Mode 04 does not appear — clearing codes is a
         * write, and it is not implemented (`docs/PRODUCT_SCOPE.md`).
         */
        val L1_GENERIC_SERVICES: Set<Int> = setOf(0x01, 0x03, 0x07, 0x09)

        /**
         * The universal read: no brand, no profile, no ECU identity.
         *
         * [target] exists for the case where the operator *has* picked a vehicle but the request is
         * still an L1 read — the answer must not depend on the pack's tier, so the default is the
         * profile-less target and passing a vehicle changes nothing about the verdict.
         */
        fun universalGeneric(
            service: Int,
            sessionReady: Boolean = true,
            target: OperationTarget = OperationTarget.UniversalGeneric,
        ): GateRequest = GateRequest(
            operationId = "generic.read-mode${service.toString(16).uppercase().padStart(2, '0')}",
            riskClass = RiskClass.READ_ONLY,
            service = service,
            target = target,
            sessionReady = sessionReady,
        )

        /**
         * Infers a request from a raw frame about to be transmitted.
         *
         * The risk class is [RiskClass.READ_ONLY] because the request is only a *claim* about the
         * frame: the gate still has to recognise the service, and a mode it does not know is refused
         * as `OPERATION_NOT_DEFINED`. A non-hex frame gets `service = null`, which no L1 mode matches,
         * so it is refused too — fail-closed on anything the gate cannot read.
         */
        fun forFrame(
            frame: String,
            target: OperationTarget = OperationTarget.UniversalGeneric,
            sessionReady: Boolean = true,
        ): GateRequest {
            val trimmed = frame.trim()
            val head = trimmed.take(2)
            val service = if (head.length == 2 && HEX_ONLY.matches(trimmed)) head.toInt(16) else null
            return GateRequest(
                operationId = "frame." + trimmed.lowercase().ifEmpty { "empty" },
                riskClass = RiskClass.READ_ONLY,
                service = service,
                target = target,
                sessionReady = sessionReady,
            )
        }

        /** A [SupportedOperation] as a question, with the service byte read out of its frame. */
        fun forOperation(
            operation: SupportedOperation,
            target: OperationTarget,
            ecu: EcuIdentity? = null,
            sessionReady: Boolean = true,
        ): GateRequest = GateRequest(
            operationId = operation.id,
            riskClass = operation.riskClass,
            service = serviceOf(operation),
            target = target,
            ecu = ecu,
            sessionReady = sessionReady,
        )

        /** The service byte of a captured frame, or `null` when the frame was never captured. */
        private fun serviceOf(operation: SupportedOperation): Int? {
            val frame = operation.serviceBytes
            val head = frame.take(2)
            return if (head.length == 2 && HEX_ONLY.matches(frame)) head.toIntOrNull(16) else null
        }
    }
}

/** Why the gate refused. Exactly one reason is recorded per denial; the first one that applies wins. */
enum class DenialReason {

    /** The variant, the operation or the ECU is not `VERIFIED`; only somebody else's capture says it works. */
    PROFILE_UNVERIFIED,

    /** The request names an ECU the selected variant does not describe. */
    ECU_IDENTITY_MISMATCH,

    /** The pack does not declare this operation, or the target cannot express it at all. */
    OPERATION_NOT_DEFINED,

    /** A write-class operation on a physical link. Simulator only, without exception. */
    PHYSICAL_EXECUTION_DISABLED,

    /** No open link: there is nothing to transmit on. */
    TRANSPORT_NOT_CONNECTED,

    /** The link is fine but the session is not: not initialised, or the operation carries no frame. */
    PRECONDITIONS_UNMET,
}

/** The answer. Two shapes, so a caller cannot read a reason off a permit or forget to handle a denial. */
sealed interface GateDecision {

    val operationId: String
    val variantId: String

    /** True only for [Allowed]. */
    val allowed: Boolean

    /** Human-readable sentence, suitable for a log line or a message to the operator. */
    val detail: String

    /** The operation may proceed. */
    data class Allowed(
        override val operationId: String,
        override val variantId: String,
        override val detail: String,
    ) : GateDecision {
        override val allowed: Boolean get() = true
    }

    /** The operation may not proceed, and nothing was transmitted. */
    data class Denied(
        override val operationId: String,
        override val variantId: String,
        val reason: DenialReason,
        override val detail: String,
    ) : GateDecision {
        override val allowed: Boolean get() = false
    }
}

/** Thrown when a gated call is refused. Carries the decision so a caller can show the reason. */
class GateDeniedException(val decision: GateDecision.Denied) : Exception(decision.detail)

/**
 * The single place that decides whether an operation may reach a transport.
 *
 * ### Order matters
 * The physical-write rule is checked **first**, before the link, the session and the profile. It is
 * not a policy that a better profile, a connected adapter or a passed precondition could unlock: it is
 * the floor of the platform (`AGENTS.md` Rule 2, `docs/SAFETY_POLICY.md`). `ActiveTestController`
 * applies the same rule by refusing to be constructed over a physical transport; the gate expresses it
 * once for every operation, not one operation at a time.
 *
 * ### What is deliberately not here
 * No transmission, no I/O, no coroutines. The gate is a pure decision over values it was handed, which
 * is what makes it testable exhaustively and impossible to bypass by cancelling it.
 *
 * Every evaluation — permit or refusal — is appended to [audit]. A gate whose refusals are invisible
 * is a gate nobody can review.
 */
class OperationGate(private val auditLog: AuditLog = AuditLog()) {

    /** The append-only trail of every decision this gate has taken. */
    val audit: AuditLog get() = auditLog

    /** Decides [request] over [transport], reading the link's type and connection state now. */
    fun evaluate(request: GateRequest, transport: ByteTransport): GateDecision =
        evaluate(request, TransportSnapshot.of(transport))

    /** Decides [request] over facts the caller already holds. Always records the outcome. */
    fun evaluate(request: GateRequest, snapshot: TransportSnapshot): GateDecision {
        val decision = decide(request, snapshot)
        auditLog.record(snapshot.type, decision)
        return decision
    }

    private fun decide(request: GateRequest, snapshot: TransportSnapshot): GateDecision {
        // 1. The floor: physical link + write-class operation. Nothing below can permit this.
        if (TransportTypeGuard.forbidsPhysicalWrite(snapshot.type, request.riskClass)) {
            return denied(
                request,
                DenialReason.PHYSICAL_EXECUTION_DISABLED,
                "Physical execution disabled — ${request.riskClass} is a write-class operation and " +
                    "this link is ${snapshot.type}; it exists only inside the simulator.",
            )
        }

        // 2. There has to be a link, and an initialised session on it.
        if (!snapshot.connected) {
            return denied(
                request,
                DenialReason.TRANSPORT_NOT_CONNECTED,
                "No adapter session is open on this ${snapshot.type} transport.",
            )
        }
        if (!request.sessionReady) {
            return denied(
                request,
                DenialReason.PRECONDITIONS_UNMET,
                "The adapter session is not initialised; no request may be sent yet.",
            )
        }

        // 3. L1: the four universal SAE J1979 modes are allowed on any link, at any brand tier.
        val service = request.service
        if (request.riskClass == RiskClass.READ_ONLY &&
            service != null &&
            service in GateRequest.L1_GENERIC_SERVICES
        ) {
            return GateDecision.Allowed(
                operationId = request.operationId,
                variantId = request.variantId,
                detail = "L1 generic read (SAE J1979) allowed on ${snapshot.type}.",
            )
        }

        // 4. Everything else depends on what the request is aimed at.
        return when (val target = request.target) {
            OperationTarget.UniversalGeneric -> denied(
                request,
                DenialReason.OPERATION_NOT_DEFINED,
                "Any Vehicle (${target.variantId}) defines only the universal L1 modes (01, 03, 07, 09); " +
                    "${describe(request)} is not one of them.",
            )

            OperationTarget.Bench -> if (snapshot.type.isSimulated) {
                GateDecision.Allowed(
                    operationId = request.operationId,
                    variantId = request.variantId,
                    detail = "Bench command ${request.operationId} allowed inside the simulator.",
                )
            } else {
                denied(
                    request,
                    DenialReason.PRECONDITIONS_UNMET,
                    "Bench commands exist only inside the simulator; this link is ${snapshot.type}.",
                )
            }

            is OperationTarget.Vehicle -> decideForVehicle(request, target.variant, snapshot)
        }
    }

    /** The profile rules: verified variant, declared operation, right risk class, known ECU, captured frame. */
    private fun decideForVehicle(
        request: GateRequest,
        variant: VehicleVariantPack,
        snapshot: TransportSnapshot,
    ): GateDecision {
        if (!variant.isVerified) {
            return denied(
                request,
                DenialReason.PROFILE_UNVERIFIED,
                "Variant ${variant.variantId} is ${variant.verificationStatus} with no physical " +
                    "confirmation; no OEM operation may run against it.",
            )
        }

        val operation = variant.operation(request.operationId)
            ?: return denied(
                request,
                DenialReason.OPERATION_NOT_DEFINED,
                "Variant ${variant.variantId} does not declare operation ${request.operationId}.",
            )

        if (!operation.isVerified) {
            return denied(
                request,
                DenialReason.PROFILE_UNVERIFIED,
                "Operation ${operation.id} is ${operation.verificationStatus}; only a verified " +
                    "operation may run.",
            )
        }

        // A caller that mislabels a write as a read must not slip past the physical rule above.
        if (operation.riskClass != request.riskClass) {
            return denied(
                request,
                DenialReason.PRECONDITIONS_UNMET,
                "Request declares ${request.riskClass} but the pack declares ${operation.riskClass}.",
            )
        }

        if (operation.isWrite && !snapshot.type.isSimulated) {
            return denied(
                request,
                DenialReason.PRECONDITIONS_UNMET,
                "Operation ${operation.id} changes vehicle state; it exists only inside the simulator.",
            )
        }

        val ecu = request.ecu ?: return denied(
            request,
            DenialReason.PRECONDITIONS_UNMET,
            "An OEM operation must name the ECU it addresses; none was supplied.",
        )

        val definition = variant.ecuDefinitions.firstOrNull { candidate -> EcuIdentity.of(candidate) == ecu }
            ?: return denied(
                request,
                DenialReason.ECU_IDENTITY_MISMATCH,
                "Variant ${variant.variantId} does not describe ECU ${ecu.name} " +
                    "(${ecu.partNumber}/${ecu.network}/${ecu.addressing}).",
            )

        if (!definition.isVerified) {
            return denied(
                request,
                DenialReason.PRECONDITIONS_UNMET,
                "ECU ${definition.name} is ${definition.verificationStatus}; its addressing is not confirmed.",
            )
        }

        if (!operation.isExecutable) {
            return denied(
                request,
                DenialReason.PRECONDITIONS_UNMET,
                "Operation ${operation.id} carries no captured frame; there is nothing to transmit.",
            )
        }

        return GateDecision.Allowed(
            operationId = request.operationId,
            variantId = request.variantId,
            detail = "OEM operation ${request.operationId} allowed on ${variant.variantId} via ${ecu.name}.",
        )
    }

    private fun denied(request: GateRequest, reason: DenialReason, detail: String): GateDecision.Denied =
        GateDecision.Denied(
            operationId = request.operationId,
            variantId = request.variantId,
            reason = reason,
            detail = detail,
        )

    private fun describe(request: GateRequest): String =
        "operation ${request.operationId} (risk ${request.riskClass}, service " +
            (request.service?.let { service -> service.toString(16).uppercase().padStart(2, '0') } ?: "none") + ")"
}
