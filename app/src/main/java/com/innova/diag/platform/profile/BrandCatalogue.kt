package com.innova.diag.platform.profile

import android.content.Context
import com.innova.diag.domain.profile.pack.BrandPackRegistry
import com.innova.diag.domain.profile.pack.PackSchemaException
import com.innova.diag.domain.profile.pack.PackValidationException
import com.innova.diag.domain.profile.pack.Tier

/**
 * The process-wide brand catalogue, loaded from assets once at startup.
 *
 * Loading is deliberately **non-fatal**: a pack that fails validation is a data defect, and crashing on
 * launch would hide it behind an ANR dialog. Instead the failure is kept in [lastError] — with the full
 * aggregated problem list, including JSON pointers — and [registry] stays `null`, which every consumer
 * must read as "no brand is supported", never as "all brands are generic".
 */
object BrandCatalogue {

    @Volatile
    private var loaded: BrandPackRegistry? = null

    /** Why loading failed, or `null` when it succeeded. Full problem list, one per line. */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Loads the catalogue if it has not been loaded yet.
     *
     * @return the registry, or `null` when a pack or the schema was rejected — see [lastError].
     */
    fun load(context: Context): BrandPackRegistry? {
        synchronized(this) {
            loaded?.let { existing -> return existing }
            return try {
                BrandPackRegistry.load(AssetPackSource(context.assets)).also { registry ->
                    loaded = registry
                    lastError = null
                }
            } catch (invalidPacks: PackValidationException) {
                lastError = invalidPacks.message
                null
            } catch (invalidSchema: PackSchemaException) {
                lastError = "schema rejected: ${invalidSchema.message}"
                null
            }
        }
    }

    /** The loaded catalogue, or `null` when it was never loaded or was rejected. */
    val registry: BrandPackRegistry? get() = loaded

    /**
     * The derived tier of [brandId].
     *
     * Fail-closed default: without a loaded catalogue nothing is verified, so the answer is
     * [Tier.T4_UNVERIFIED] rather than a hopeful one.
     */
    fun tierOf(brandId: String): Tier = loaded?.getTier(brandId) ?: Tier.T4_UNVERIFIED

    /** Brand id → derived tier, or an empty map when the catalogue is unavailable. */
    fun tierSummary(): Map<String, Tier> = loaded?.listTiers() ?: emptyMap()
}
