package com.innova.diag.ui

import com.innova.diag.domain.profile.ProfileStatus
import com.innova.diag.domain.profile.RiskClass
import com.innova.diag.domain.profile.pack.BrandPack
import com.innova.diag.domain.profile.pack.BrandPackRegistry
import com.innova.diag.domain.profile.pack.OperationScope
import com.innova.diag.domain.profile.pack.SupportedOperation
import com.innova.diag.domain.profile.pack.Tier
import com.innova.diag.domain.profile.pack.VehicleVariantPack

/**
 * What the picker is showing.
 *
 * Two routes, not a wizard: either the operator is browsing the catalogue, or they have chosen the
 * universal path and the catalogue is out of the way. Back is always one tap.
 */
enum class BrandPickerRoute(private val title: String) {

    /** Brand → family → variant. */
    CATALOGUE("Brands"),

    /** "Any Vehicle": L1 generic OBD-II only, no profile. */
    GENERIC_ONLY("Any Vehicle");

    /** Label for the route, used by the screen's header and by tests. */
    val label: String get() = title
}

/**
 * One capability a variant might offer, and whether it may actually be used.
 *
 * [available] is the only thing a caller may branch on. When it is false the UI renders [status] as
 * **text**, never as a button: a control that cannot act is a lie told to a technician
 * (`docs/SAFETY_POLICY.md`, "no dead clickable buttons").
 *
 * @property status why it cannot be used — "Unavailable — Unverified Profile", "Simulator Only", …
 */
data class CapabilityRow(
    val label: String,
    val status: String,
    val available: Boolean,
)

/** One ECU row, kept for the variant detail card. */
data class EcuRow(
    val name: String,
    val partNumber: String,
    val network: String,
    val addressing: String,
    val statusLabel: String,
)

/** One variant: the leaf of the picker, and the unit a pack is promoted to verified. */
data class PickerVariantEntry(
    val brandId: String,
    val familyId: String,
    val variantId: String,
    val yearLabel: String,
    val market: String,
    val engineCode: String,
    val transmission: String,
    val statusLabel: String,
    val verified: Boolean,
    val capabilities: List<CapabilityRow>,
    val ecus: List<EcuRow>,
) {

    /** "2007 · GCC · engine UNKNOWN · transmission UNKNOWN" — every field honest, nothing invented. */
    val summary: String
        get() = "$yearLabel · $market · engine $engineCode · transmission $transmission"
}

/** One platform family. */
data class PickerFamilyEntry(
    val familyId: String,
    val familyName: String,
    val variants: List<PickerVariantEntry>,
)

/** One brand, with its badge and its families. */
data class PickerBrand(
    val brandId: String,
    val brandName: String,
    val tier: Tier,
    val families: List<PickerFamilyEntry>,
) {

    /** "Full Enhanced", "Partial Enhanced", "Generic OBD-II Only", "Unverified (Generic OBD-II Only)". */
    val tierLabel: String get() = BrandPickerModel.tierLabel(tier)

    /** Every variant of every family. */
    val variants: List<PickerVariantEntry> get() = families.flatMap { family -> family.variants }

    val variantCount: Int get() = variants.size

    val verifiedVariantCount: Int get() = variants.count { variant -> variant.verified }

    /** "0 of 1 variants verified" — the honest count, not a promise. */
    val verificationSummary: String get() = "$verifiedVariantCount of $variantCount variants verified"
}

/**
 * Everything the brand picker renders.
 *
 * The catalogue is carried in the state (`brands`) instead of being looked up from the registry on
 * every read. Consequence: the state is a snapshot — a pack reload needs [BrandPickerModel.summarize]
 * again — and every selection function below is pure, which is what makes the behaviour testable
 * without Android and without a registry double.
 */
data class BrandPickerState(
    val route: BrandPickerRoute = BrandPickerRoute.CATALOGUE,
    val brands: List<PickerBrand> = emptyList(),
    val selectedBrandId: String? = null,
    val selectedVariantId: String? = null,
    val message: String? = null,
) {

    /** True once "Any Vehicle — Generic OBD-II Only" has been chosen. */
    val genericOnly: Boolean get() = route == BrandPickerRoute.GENERIC_ONLY

    /** The brand whose families are open, or `null`. */
    val selectedBrand: PickerBrand? get() = brands.firstOrNull { brand -> brand.brandId == selectedBrandId }

    /** The variant whose capabilities are shown, or `null`. */
    val selectedVariant: PickerVariantEntry?
        get() = selectedBrand?.variants?.firstOrNull { variant -> variant.variantId == selectedVariantId }

    /** Nothing was loaded: the screen must say so rather than show an empty list. */
    val isEmpty: Boolean get() = brands.isEmpty()
}

/** What the screen can ask for. One type, so the host wires one callback. */
sealed interface BrandPickerEvent {

    /** "Any Vehicle — Generic OBD-II Only": skip brand selection entirely. */
    data object AnyVehicle : BrandPickerEvent

    /** Open a brand's families. Navigation, not an operation, so it is always permitted. */
    data class SelectBrand(val brandId: String) : BrandPickerEvent

    /** Open a variant's detail card. */
    data class SelectVariant(val variantId: String) : BrandPickerEvent

    /** Return from the "Any Vehicle" route to the catalogue. */
    data object BackToCatalogue : BrandPickerEvent
}

/**
 * The pure half of the brand picker: building the state and moving between selections.
 *
 * No Android, no registry lookup at render time, no mutation. The view model is a thin holder around
 * these functions, which is why the tests for sorting, selection and the "Any Vehicle" bypass need
 * neither Robolectric nor a coroutine dispatcher.
 */
object BrandPickerModel {

    /**
     * The exact label of the top action. It says what it is *and* what it will not do, because the
     * whole point of the entry is that no brand profile is involved.
     */
    const val ANY_VEHICLE_LABEL: String = "Any Vehicle — Generic OBD-II Only"

    /** Shown under the top action. Modes, not features: the four universal SAE J1979 reads. */
    const val ANY_VEHICLE_DETAIL: String =
        "Skips brand selection. Generic powertrain scan (emissions/OBD-II only): modes 01, 03, 07, 09."

    /** No packs survived schema validation — a data defect, stated plainly. */
    const val NO_CATALOGUE_MESSAGE: String =
        "No brand packs are available: the shipped catalogue did not pass schema validation. " +
            "Generic OBD-II is still available."

    const val OEM_ENHANCED: String = "OEM Enhanced"
    const val ACTIVE_TEST: String = "Active Test"
    const val CODING: String = "Coding"

    const val AVAILABLE: String = "Available"
    const val UNAVAILABLE_UNVERIFIED: String = "Unavailable — Unverified Profile"
    const val UNAVAILABLE_NOT_DEFINED: String = "Unavailable — Not Defined"
    const val SIMULATOR_ONLY: String = "Simulator Only"

    /** Tier badges, verbatim from `docs/VEHICLE_MATRIX.md`. */
    fun tierLabel(tier: Tier): String = when (tier) {
        Tier.T1_FULL_ENHANCED -> "Full Enhanced"
        Tier.T2_PARTIAL_ENHANCED -> "Partial Enhanced"
        Tier.T3_GENERIC_ONLY -> "Generic OBD-II Only"
        Tier.T4_UNVERIFIED -> "Unverified (Generic OBD-II Only)"
    }

    /** Builds the catalogue from a loaded registry; `null` means nothing loaded. */
    fun summarize(registry: BrandPackRegistry?): BrandPickerState {
        if (registry == null) {
            return BrandPickerState(brands = emptyList(), message = NO_CATALOGUE_MESSAGE)
        }
        val brands = registry.listBrands()
            .map { brand -> toBrand(brand) }
            .sortedWith(compareBy({ brand -> brand.tier.ordinal }, { brand -> brand.brandName.lowercase() }))
        return BrandPickerState(brands = brands)
    }

    /** The brand picker's opening state, before any registry is available. */
    fun empty(): BrandPickerState = BrandPickerState()

    /** Opens [brandId]; an unknown id is ignored rather than invented into the catalogue. */
    fun selectBrand(state: BrandPickerState, brandId: String): BrandPickerState {
        if (state.brands.none { brand -> brand.brandId == brandId }) return state
        return state.copy(
            route = BrandPickerRoute.CATALOGUE,
            selectedBrandId = brandId,
            selectedVariantId = null,
            message = null,
        )
    }

    /** Opens [variantId] inside the open brand; an unknown id is ignored. */
    fun selectVariant(state: BrandPickerState, variantId: String): BrandPickerState {
        val brand = state.selectedBrand ?: return state
        if (brand.variants.none { variant -> variant.variantId == variantId }) return state
        return state.copy(selectedVariantId = variantId, message = null)
    }

    /**
     * The "Any Vehicle" bypass: no brand, no variant, no profile. The generic scan tab is where the
     * host sends the operator next; nothing in this state touches a pack.
     */
    fun anyVehicle(state: BrandPickerState): BrandPickerState = state.copy(
        route = BrandPickerRoute.GENERIC_ONLY,
        selectedBrandId = null,
        selectedVariantId = null,
        message = null,
    )

    /** Back to the catalogue from the generic-only route. */
    fun backToCatalogue(state: BrandPickerState): BrandPickerState =
        state.copy(route = BrandPickerRoute.CATALOGUE, message = null)

    private fun toBrand(brand: BrandPack): PickerBrand = PickerBrand(
        brandId = brand.brandId,
        brandName = brand.brandName,
        tier = brand.tier,
        families = brand.families.map { family ->
            PickerFamilyEntry(
                familyId = family.familyId,
                familyName = family.familyName,
                variants = family.variants.map { variant -> toVariant(brand, family.familyId, variant) },
            )
        },
    )

    private fun toVariant(
        brand: BrandPack,
        familyId: String,
        variant: VehicleVariantPack,
    ): PickerVariantEntry = PickerVariantEntry(
        brandId = brand.brandId,
        familyId = familyId,
        variantId = variant.variantId,
        yearLabel = yearLabel(variant),
        market = variant.market,
        engineCode = variant.engineCode,
        transmission = variant.transmission,
        statusLabel = statusLabel(variant.verificationStatus),
        verified = variant.isVerified,
        capabilities = capabilities(variant),
        ecus = variant.ecuDefinitions.map { definition ->
            EcuRow(
                name = definition.name,
                partNumber = definition.partNumber,
                network = definition.network,
                addressing = definition.addressing,
                statusLabel = statusLabel(definition.verificationStatus),
            )
        },
    )

    /**
     * The three capabilities the picker shows, each with the reason it cannot be used.
     *
     * Derivation, not configuration: a capability is available only when the variant is verified *and*
     * declares a verified operation of that kind. Today every shipped pack is T4, so all three read
     * "Unavailable — Unverified Profile" — which is the truth, and the reason they are labels rather
     * than buttons.
     */
    fun capabilities(variant: VehicleVariantPack): List<CapabilityRow> = listOf(
        capability(OEM_ENHANCED, variant) { operation ->
            operation.scope == OperationScope.ENHANCED && operation.riskClass == RiskClass.READ_ONLY
        },
        capability(ACTIVE_TEST, variant) { operation -> operation.riskClass == RiskClass.ACTIVE_TEST },
        capability(CODING, variant) { operation -> operation.riskClass == RiskClass.CONFIGURATION },
    )

    private fun capability(
        label: String,
        variant: VehicleVariantPack,
        selector: (SupportedOperation) -> Boolean,
    ): CapabilityRow {
        val declared = variant.supportedOperations.any { operation -> selector(operation) }
        val verified = variant.supportedOperations.any { operation ->
            selector(operation) && operation.isVerified
        }
        val available = variant.isVerified && verified
        val status = when {
            available -> AVAILABLE
            !variant.isVerified -> UNAVAILABLE_UNVERIFIED
            !declared -> UNAVAILABLE_NOT_DEFINED
            else -> SIMULATOR_ONLY
        }
        return CapabilityRow(label = label, status = status, available = available)
    }

    private fun statusLabel(status: ProfileStatus): String = status.name

    private fun yearLabel(variant: VehicleVariantPack): String {
        val from = variant.yearRange.from
        val to = variant.yearRange.to
        return if (from == to) "$from" else "$from–$to"
    }
}
