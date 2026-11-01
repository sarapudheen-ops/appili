package com.innova.diag.domain.profile.pack

import com.innova.diag.domain.profile.ProfileStatus
import com.innova.diag.domain.profile.RiskClass
import com.innova.diag.domain.profile.YearRange

/**
 * The one permitted way to say "not captured" in a pack.
 *
 * Duplicated in code rather than re-exported so `pack` does not depend on the v1 vocabulary at
 * runtime; a test asserts it equals `EcuAddress.UNKNOWN_VALUE`, so the two can never drift.
 */
const val UNKNOWN_VALUE: String = "UNKNOWN"

/** Letters, digits, `_`, `.` and `-`: safe in a file name, a JSON pointer, a DTC dictionary key and a log
 * line. Hyphens are allowed so operation ids can read as slugs (`generic.read-engine-speed`). */
private val IDENTIFIER = Regex("[a-z0-9][a-z0-9._-]*")

/** A UDS/OBD request frame: 1–4 bytes, uppercase hex, even length. */
private val HEX_FRAME = Regex("([0-9A-F]{2}){1,4}")

/** True when [this] is the explicit "not captured" sentinel. */
fun String.isUnknown(): Boolean = this == UNKNOWN_VALUE

/** True when [this] is a usable free-text field: present, not blank, no control characters. */
internal fun String.isSafeText(): Boolean =
    isNotBlank() && none { character -> character < ' ' || character == '\u007F' }

/** True when [this] is a captured request frame rather than [UNKNOWN_VALUE]. */
internal fun String.isCapturedFrame(): Boolean = !isUnknown() && HEX_FRAME.matches(this)

/**
 * How broad an operation is.
 *
 * The distinction drives tier promotion: a verified **generic** operation is SAE J1979 (works on any
 * OBD-II car), a verified **enhanced** one is OEM-specific and is what separates T3 from T2
 * (`docs/VEHICLE_MATRIX.md`).
 */
enum class OperationScope {

    /** Standardised OBD-II (SAE J1979 / ISO 15031-5). */
    GENERIC,

    /** Manufacturer-specific (enhanced) access. */
    ENHANCED,

    /** Nobody has classified it yet; a verified operation may not stay here. */
    UNKNOWN,
}

/**
 * One operation a variant claims to support.
 *
 * Every safety-relevant field is either captured or saying so: [serviceBytes] is a hex frame or
 * [UNKNOWN_VALUE], [sourceReference] is a citation or [UNKNOWN_VALUE]. A `VERIFIED` operation must have
 * all three captured, because "verified" without the bytes and the source is a claim with no evidence —
 * and `docs/VEHICLE_MATRIX.md` promotion rule 3 requires a source reference anyway.
 *
 * @property sourceReference where the claim comes from: a document, a bench capture, a transcript.
 * @property riskClass what running it would do; the executor refuses anything but `READ_ONLY`.
 */
data class SupportedOperation(
    val id: String,
    val description: String,
    val sourceReference: String,
    val verificationStatus: ProfileStatus,
    val riskClass: RiskClass,
    val scope: OperationScope,
    val serviceBytes: String,
) {
    init {
        require(IDENTIFIER.matches(id)) { "operation id must match $IDENTIFIER: \"$id\"" }
        require(description.isSafeText()) { "operation \"$id\" needs a description" }
        require(sourceReference.isSafeText()) { "operation \"$id\" needs a source reference or UNKNOWN" }
        require(serviceBytes.isUnknown() || HEX_FRAME.matches(serviceBytes)) {
            "operation \"$id\" serviceBytes must be UNKNOWN or uppercase hex (1-4 bytes): \"$serviceBytes\""
        }
        if (verificationStatus == ProfileStatus.VERIFIED) {
            require(serviceBytes.isCapturedFrame()) {
                "operation \"$id\" is VERIFIED but has no captured service bytes; " +
                    "verification without the frame is not evidence"
            }
            require(sourceReference.isUnknown().not()) {
                "operation \"$id\" is VERIFIED but cites no source (promotion rule 3)"
            }
            require(scope != OperationScope.UNKNOWN) {
                "operation \"$id\" is VERIFIED but has no scope; generic and enhanced must be told apart"
            }
        }
    }

    /** True only for a fully captured, physically confirmed operation. */
    val isVerified: Boolean get() = verificationStatus == ProfileStatus.VERIFIED

    /** True when this may only ever be exercised against the internal simulator. */
    val isSimulatorOnly: Boolean get() = verificationStatus == ProfileStatus.SIMULATOR_ONLY

    /** True when running it could change vehicle state; the executor refuses these outright. */
    val isWrite: Boolean get() = riskClass != RiskClass.READ_ONLY

    /** Verified *and* carrying the bytes to send: the only shape that can ever reach a transport. */
    val isExecutable: Boolean get() = isVerified && serviceBytes.isCapturedFrame()
}

/**
 * One ECU as a variant describes it.
 *
 * A `VERIFIED` definition must have its part number, network and addressing captured and at least one
 * real calibration id — the same rule the v1 profile model applies to a hollow "verified" claim.
 */
data class VariantEcuDefinition(
    val name: String,
    val partNumber: String,
    val calibrationIds: List<String>,
    val network: String,
    val addressing: String,
    val verificationStatus: ProfileStatus,
) {
    init {
        require(name.isSafeText()) { "ECU name must not be blank" }
        require(partNumber.isSafeText()) { "ECU \"$name\" partNumber must be UNKNOWN or a value" }
        require(network.isSafeText()) { "ECU \"$name\" network must be UNKNOWN or a value" }
        require(addressing.isSafeText()) { "ECU \"$name\" addressing must be UNKNOWN or a value" }
        require(calibrationIds.isNotEmpty()) {
            "ECU \"$name\" must list at least one calibration id, or UNKNOWN"
        }
        require(calibrationIds.all { it.isSafeText() }) { "ECU \"$name\" has a blank calibration id" }
        require(calibrationIds.distinct().size == calibrationIds.size) {
            "ECU \"$name\" repeats a calibration id"
        }
        if (verificationStatus == ProfileStatus.VERIFIED) {
            require(!partNumber.isUnknown()) {
                "ECU \"$name\" is VERIFIED but its part number is UNKNOWN"
            }
            require(!network.isUnknown()) {
                "ECU \"$name\" is VERIFIED but its network is UNKNOWN"
            }
            require(!addressing.isUnknown()) {
                "ECU \"$name\" is VERIFIED but its addressing is UNKNOWN"
            }
            require(calibrationIds.none { it.isUnknown() }) {
                "ECU \"$name\" is VERIFIED but its calibration ids are UNKNOWN"
            }
        }
    }

    /** True only for a fully captured definition. */
    val isVerified: Boolean get() = verificationStatus == ProfileStatus.VERIFIED
}

/**
 * One vehicle variant: a market, a year span, an engine, a transmission, its ECUs and its operations.
 *
 * `variantId` is the leaf of the `brandId → familyId → variantId` path and the only thing a caller ever
 * needs to name a vehicle. A `VERIFIED` variant must have its engine and transmission captured and at
 * least one verified ECU — a verified vehicle with an unknown engine is not a verified vehicle.
 */
data class VehicleVariantPack(
    val variantId: String,
    val market: String,
    val yearRange: YearRange,
    val engineCode: String,
    val transmission: String,
    val verificationStatus: ProfileStatus,
    val ecuDefinitions: List<VariantEcuDefinition>,
    val supportedOperations: List<SupportedOperation>,
) {
    init {
        require(IDENTIFIER.matches(variantId)) { "variantId must match $IDENTIFIER: \"$variantId\"" }
        require(market.isSafeText()) { "variant \"$variantId\" needs a market" }
        require(engineCode.isSafeText()) { "variant \"$variantId\" engineCode must be UNKNOWN or a value" }
        require(transmission.isSafeText()) { "variant \"$variantId\" transmission must be UNKNOWN or a value" }
        require(ecuDefinitions.isNotEmpty()) { "variant \"$variantId\" must describe at least one ECU" }
        require(ecuDefinitions.distinctBy { it.name }.size == ecuDefinitions.size) {
            "variant \"$variantId\" repeats an ECU name"
        }
        require(supportedOperations.distinctBy { it.id }.size == supportedOperations.size) {
            "variant \"$variantId\" repeats an operation id"
        }
        if (verificationStatus == ProfileStatus.VERIFIED) {
            require(!engineCode.isUnknown()) { "a VERIFIED variant must have its engine code captured" }
            require(!transmission.isUnknown()) { "a VERIFIED variant must have its transmission captured" }
            require(ecuDefinitions.any { it.isVerified }) {
                "a VERIFIED variant must have at least one verified ECU definition"
            }
        }
    }

    /** True only for a physically confirmed variant. */
    val isVerified: Boolean get() = verificationStatus == ProfileStatus.VERIFIED

    /** True when every declared operation is verified — the fullest possible state for this variant. */
    val isFullyVerified: Boolean
        get() = isVerified &&
            ecuDefinitions.all { it.isVerified } &&
            supportedOperations.isNotEmpty() &&
            supportedOperations.all { it.isVerified }

    /** The operation named [operationId], or `null` when this variant does not declare it. */
    fun operation(operationId: String): SupportedOperation? =
        supportedOperations.firstOrNull { operation -> operation.id == operationId }

    /** "GCC 2007 Toyota Innova" style identity for logs and errors. */
    val label: String
        get() = "$variantId ($market ${yearRange.from}" +
            (if (yearRange.to != yearRange.from) "-${yearRange.to}" else "") + ", " +
            "engine $engineCode, transmission $transmission)"
}

/**
 * A platform family: the level that groups variants that share wiring and diagnostics.
 *
 * [variantIds] is declared explicitly *and* must exactly match the nested [variants]. The duplication is
 * deliberate: the manifest is what a reviewer reads first, so a variant that exists in the body but not
 * in the manifest (or the reverse) is a data error, not a convenience.
 */
data class FamilyPack(
    val familyId: String,
    val familyName: String,
    val variantIds: List<String>,
    val variants: List<VehicleVariantPack>,
) {
    init {
        require(IDENTIFIER.matches(familyId)) { "familyId must match $IDENTIFIER: \"$familyId\"" }
        require(familyName.isSafeText()) { "family \"$familyId\" needs a name" }
        require(variantIds.isNotEmpty()) { "family \"$familyId\" must list at least one variant" }
        require(variantIds.distinct().size == variantIds.size) {
            "family \"$familyId\" repeats a variant id"
        }
        require(variants.distinctBy { it.variantId }.size == variants.size) {
            "family \"$familyId\" defines a variant twice"
        }
        require(variantIds.toSet() == variants.map { it.variantId }.toSet()) {
            "family \"$familyId\" declared variantIds $variantIds but defines " +
                variants.map { it.variantId }
        }
    }

    /** The variant named [variantId], or `null`. */
    fun variant(variantId: String): VehicleVariantPack? =
        variants.firstOrNull { variant -> variant.variantId == variantId }
}

/**
 * A brand: the root of the hierarchy, and the unit a tier is awarded to.
 *
 * ### `securityPolicyOverrides`
 * The field exists so the schema can carry future policy vocabulary, but **only [UNKNOWN_VALUE] is
 * accepted today**: the safety rules in `AGENTS.md` are not data-configurable, so a pack cannot grant
 * itself a relaxation. Anything else is a validation error, which is what "default is fail-closed"
 * means in practice.
 *
 * ### Tier
 * [tier] is derived from the data every time it is asked for — there is no stored tier and no override
 * (`docs/VEHICLE_MATRIX.md` promotion rules). See [Tier.of].
 */
data class BrandPack(
    val brandId: String,
    val brandName: String,
    val commonDtcDictionary: String,
    val brandLevelProtocolDefaults: List<String>,
    val securityPolicyOverrides: List<String>,
    val families: List<FamilyPack>,
) {
    init {
        require(IDENTIFIER.matches(brandId)) { "brandId must match $IDENTIFIER: \"$brandId\"" }
        require(brandName.isSafeText()) { "brand \"$brandId\" needs a name" }
        require(commonDtcDictionary.isSafeText()) {
            "brand \"$brandId\" commonDtcDictionary must be UNKNOWN or a reference"
        }
        require(brandLevelProtocolDefaults.isNotEmpty()) {
            "brand \"$brandId\" must list its allowed protocols, or UNKNOWN"
        }
        require(brandLevelProtocolDefaults.all { it.isSafeText() }) {
            "brand \"$brandId\" has a blank protocol default"
        }
        require(brandLevelProtocolDefaults.distinct().size == brandLevelProtocolDefaults.size) {
            "brand \"$brandId\" repeats a protocol default"
        }
        require(securityPolicyOverrides.isNotEmpty()) {
            "brand \"$brandId\" must state its security policy overrides, or UNKNOWN"
        }
        require(securityPolicyOverrides.all { it.isUnknown() }) {
            "brand \"$brandId\" declares a security policy override " +
                "${securityPolicyOverrides.filterNot { it.isUnknown() }}; the platform policy is not " +
                "configurable by data and remains fail-closed"
        }
        require(families.distinctBy { it.familyId }.size == families.size) {
            "brand \"$brandId\" defines a family twice"
        }
    }

    /** Every variant of every family, in declaration order. */
    val variants: List<VehicleVariantPack> get() = families.flatMap { family -> family.variants }

    /** The variants whose identity has been physically confirmed. */
    val verifiedVariants: List<VehicleVariantPack>
        get() = variants.filter { variant -> variant.isVerified }

    /** True when nobody has captured this brand's protocol behaviour yet. */
    val protocolsAreCaptured: Boolean
        get() = brandLevelProtocolDefaults.none { protocol -> protocol.isUnknown() }

    /** Derived from [variants] on every read; see [Tier.of]. */
    val tier: Tier get() = Tier.of(this)

    /** The variant named [variantId] in any family, or `null`. */
    fun variant(variantId: String): VehicleVariantPack? =
        families.firstNotNullOfOrNull { family -> family.variant(variantId) }

    /** The family that declares [variantId], or `null`. */
    fun familyOf(variantId: String): FamilyPack? =
        families.firstOrNull { family -> family.variant(variantId) != null }
}

/**
 * The four tiers of `docs/VEHICLE_MATRIX.md`, derived from pack data and never stored.
 *
 * | Tier | Matrix definition | Data condition |
 * | --- | --- | --- |
 * | [T1_FULL_ENHANCED] | verified packs, read + active + coding where validated | a verified variant has a verified non-read-only operation |
 * | [T2_PARTIAL_ENHANCED] | some OEM read-only packs verified | a verified variant has a verified `ENHANCED` read-only operation |
 * | [T3_GENERIC_ONLY] | generic OBD works, no OEM profile validated | a verified variant exists, but no verified OEM operation |
 * | [T4_UNVERIFIED] | brand listed, no physical testing | no verified variant at all (including: no variants) |
 *
 * Promotion is therefore a data change — capture a field, mark it `VERIFIED`, re-read the tier — and
 * demotion happens automatically if a claim is withdrawn. Nothing in the app may set a tier by hand.
 */
enum class Tier {

    T1_FULL_ENHANCED,
    T2_PARTIAL_ENHANCED,
    T3_GENERIC_ONLY,
    T4_UNVERIFIED;

    companion object {

        /** Derives a brand's tier; an unknown brand has no verified variants, so it is [T4_UNVERIFIED]. */
        fun of(brand: BrandPack): Tier = of(brand.variants)

        /** Derives a tier from variants alone. */
        fun of(variants: List<VehicleVariantPack>): Tier {
            val verifiedVariants = variants.filter { variant -> variant.isVerified }
            if (verifiedVariants.isEmpty()) return T4_UNVERIFIED

            val verifiedOperations = verifiedVariants.flatMap { variant ->
                variant.supportedOperations.filter { operation -> operation.isExecutable }
            }
            if (verifiedOperations.any { operation -> operation.isWrite }) return T1_FULL_ENHANCED
            if (verifiedOperations.any { operation -> operation.scope == OperationScope.ENHANCED }) {
                return T2_PARTIAL_ENHANCED
            }
            return T3_GENERIC_ONLY
        }
    }
}
