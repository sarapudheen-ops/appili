package com.innova.diag.domain.profile.pack

import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.ObdCommandPort
import com.innova.diag.domain.profile.ProfileStatus
import com.innova.diag.domain.profile.RiskClass
import com.innova.diag.domain.profile.SafetyViolationException

/**
 * The only way a brand pack can cause a byte to reach a transport.
 *
 * The v1 gate (`ProfileOperationExecutor`) protects a flat profile; this one protects the hierarchy, and
 * it is stricter in one specific way: **a variant-level `UNVERIFIED` stops everything**, so a pack can
 * list an operation before it is validated without ever becoming executable
 * (`docs/VEHICLE_MATRIX.md` promotion rules).
 *
 * ### The gate, in order
 * 1. the brand, variant and operation must exist ([SafetyViolationException.Reason.VARIANT_NOT_DESCRIBED],
 *    `VARIANT_NOT_DESCRIBED`, `OPERATION_NOT_DECLARED`);
 * 2. the **variant** must be verified (`VARIANT_NOT_VERIFIED`, or `SIMULATOR_ONLY` for a bench-only
 *    variant);
 * 3. the **risk class** must be `READ_ONLY` (`RISK_CLASS_FORBIDDEN`) — checked before verification so a
 *    pack cannot describe a write and have it refused for the wrong reason;
 * 4. the **operation** must be verified (`OPERATION_NOT_VERIFIED`);
 * 5. its service bytes must be captured (`SERVICE_BYTES_UNKNOWN`);
 * 6. at least one ECU of the variant must be verified (`ECU_NOT_VERIFIED`).
 *
 * Only after all six does [ObdCommandPort.execute] see the frame. Every refusal throws before that.
 */
class PackOperationExecutor(private val port: ObdCommandPort) {

    /**
     * Authorises and transmits [operationId] for the variant [variantId] of [brandId].
     *
     * @throws SafetyViolationException when the gate refuses — nothing has been transmitted.
     */
    suspend fun execute(
        registry: BrandPackRegistry,
        brandId: String,
        variantId: String,
        operationId: String,
    ): DiagnosticResult<String> {
        val brand = registry.getBrand(brandId)
            ?: throw SafetyViolationException(
                reason = SafetyViolationException.Reason.VARIANT_NOT_DESCRIBED,
                message = "the catalogue describes no brand \"$brandId\"",
                profileId = brandId,
                operationId = operationId,
                variantId = variantId,
            )
        val variant = brand.variant(variantId)
            ?: throw SafetyViolationException(
                reason = SafetyViolationException.Reason.VARIANT_NOT_DESCRIBED,
                message = "brand \"$brandId\" describes no variant \"$variantId\"",
                profileId = brandId,
                operationId = operationId,
                variantId = variantId,
            )
        val operation = variant.operation(operationId)
            ?: throw SafetyViolationException(
                reason = SafetyViolationException.Reason.OPERATION_NOT_DECLARED,
                message = "variant \"$variantId\" declares no operation \"$operationId\"",
                profileId = brandId,
                operationId = operationId,
                variantId = variantId,
            )

        val frame = authorise(brand, variant, operation)
        return port.execute(frame)
    }

    /**
     * Runs the gate and returns the frame to send.
     *
     * Exposed to tests so each refusal can be asserted without a coroutine; production callers go
     * through [execute], which cannot skip it.
     */
    internal fun authorise(
        brand: BrandPack,
        variant: VehicleVariantPack,
        operation: SupportedOperation,
    ): String {
        if (variant.verificationStatus != ProfileStatus.VERIFIED) {
            throw refusal(
                brand = brand,
                variant = variant,
                operation = operation,
                reason = if (variant.verificationStatus == ProfileStatus.SIMULATOR_ONLY) {
                    SafetyViolationException.Reason.SIMULATOR_ONLY
                } else {
                    SafetyViolationException.Reason.VARIANT_NOT_VERIFIED
                },
                detail = "the variant is ${variant.verificationStatus}",
            )
        }
        if (operation.riskClass != RiskClass.READ_ONLY) {
            throw refusal(
                brand = brand,
                variant = variant,
                operation = operation,
                reason = SafetyViolationException.Reason.RISK_CLASS_FORBIDDEN,
                detail = "${operation.riskClass} operations are simulator-only and are never transmitted",
            )
        }
        if (operation.verificationStatus != ProfileStatus.VERIFIED) {
            throw refusal(
                brand = brand,
                variant = variant,
                operation = operation,
                reason = SafetyViolationException.Reason.OPERATION_NOT_VERIFIED,
                detail = "the operation is ${operation.verificationStatus}",
            )
        }
        if (!operation.serviceBytes.isCapturedFrame()) {
            throw refusal(
                brand = brand,
                variant = variant,
                operation = operation,
                reason = SafetyViolationException.Reason.SERVICE_BYTES_UNKNOWN,
                detail = "the operation carries no captured service bytes",
            )
        }
        if (variant.ecuDefinitions.none { ecu -> ecu.isVerified }) {
            throw refusal(
                brand = brand,
                variant = variant,
                operation = operation,
                reason = SafetyViolationException.Reason.ECU_NOT_VERIFIED,
                detail = "no ECU of this variant is verified, so there is no captured target",
            )
        }
        return operation.serviceBytes
    }

    private fun refusal(
        brand: BrandPack,
        variant: VehicleVariantPack,
        operation: SupportedOperation,
        reason: SafetyViolationException.Reason,
        detail: String,
    ): SafetyViolationException = SafetyViolationException(
        reason = reason,
        message = "refusing \"${operation.id}\" on ${brand.brandId}/${
            variant.variantId
        } (tier ${brand.tier}): $detail",
        profileId = brand.brandId,
        operationId = operation.id,
        variantId = variant.variantId,
    )
}
