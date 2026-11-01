package com.innova.diag.domain.profile.pack

import com.innova.diag.domain.profile.ProfileFormatException
import com.innova.diag.domain.profile.parseJson

/**
 * Where brand packs come from: a directory of JSON files plus the schema that governs them.
 *
 * Deliberately not `android.content.res.AssetManager` — the domain stays Android-free and the registry
 * stays testable with a map of strings (`docs/ASSUMPTIONS.md` A27). The Android implementation is
 * `platform.profile.AssetPackSource`.
 */
interface PackSource {

    /** Human-readable origin, used in error messages: `assets/profiles/brands`. */
    val location: String

    /** File names of the packs to load, e.g. `toyota.json`. Order is not significant. */
    fun listPackFiles(): List<String>

    /** The raw JSON of one pack. */
    fun readPack(fileName: String): String

    /** The raw JSON of the schema that every pack is validated against. */
    fun readSchema(): String
}

/**
 * The multi-brand catalogue: every brand pack, validated against the schema, loaded once.
 *
 * ### Fail-closed loading
 * Anything that is not a complete, schema-valid, internally consistent pack rejects the **whole**
 * registry: a catalogue that silently dropped `toyota.json` would report "Toyota is not supported",
 * which reads like a capability statement rather than a data error. Every problem from every file is
 * collected and reported together, each with its JSON pointer, so one load shows the whole picture.
 *
 * ### Tiers are never stored
 * [getTier] recomputes from the pack data on every call ([Tier.of]), so a claim cannot be outrun by its
 * evidence: withdraw a `VERIFIED` marker and the tier falls on the next read.
 */
class BrandPackRegistry private constructor(private val brands: List<BrandPack>) {

    /** Every brand, ordered by [BrandPack.brandId] for a deterministic UI and log. */
    fun listBrands(): List<BrandPack> = brands

    /** The brand named [brandId], or `null` when the catalogue does not describe it. */
    fun getBrand(brandId: String): BrandPack? = brands.firstOrNull { brand -> brand.brandId == brandId }

    /**
     * The variant named [variantId] under [brandId], or `null`.
     *
     * `null` is the honest answer for "this catalogue does not describe that vehicle"; it is not an
     * empty pack and must never be substituted by a default one.
     */
    fun getVariant(brandId: String, variantId: String): VehicleVariantPack? =
        getBrand(brandId)?.variant(variantId)

    /**
     * The tier of [brandId], derived from its variants.
     *
     * An unknown brand has no verified variant, so it is [Tier.T4_UNVERIFIED] — the same answer the
     * data would give for a brand with nothing captured, and never a promoted tier by accident.
     */
    fun getTier(brandId: String): Tier = getBrand(brandId)?.tier ?: Tier.T4_UNVERIFIED

    /** Every brand and its derived tier, ordered by brand id. */
    fun listTiers(): Map<String, Tier> = brands.associate { brand -> brand.brandId to brand.tier }

    /** Every variant in the catalogue, across brands, in brand order. */
    fun listVariants(): List<VehicleVariantPack> = brands.flatMap { brand -> brand.variants }

    companion object {

        /**
         * Reads every pack from [source] and validates it against the schema the source supplies.
         *
         * @throws PackSchemaException when the schema itself cannot be used.
         * @throws PackValidationException when any pack is unreadable, not JSON, not schema-valid, or
         *   contradicts itself (duplicate brand id, manifest/body mismatch, hollow `VERIFIED` claim).
         */
        fun load(source: PackSource): BrandPackRegistry {
            val schema = PackSchema.parse(source.readSchema())
            val documents = source.listPackFiles().sorted().associateWith { fileName ->
                try {
                    source.readPack(fileName)
                } catch (unreadable: Exception) {
                    throw PackValidationException(
                        "${source.location}/$fileName: cannot be read: ${unreadable.message}",
                        listOf("$fileName: ${unreadable.message}"),
                        unreadable,
                    )
                }
            }
            if (documents.isEmpty()) {
                throw PackValidationException(
                    "${source.location}: no brand packs found; an empty catalogue is not a valid state",
                )
            }
            return fromDocuments(documents, schema)
        }

        /** Convenience for tests and future code paths: schema as text rather than a parsed object. */
        fun fromDocuments(documents: Map<String, String>, schemaJson: String): BrandPackRegistry =
            fromDocuments(documents, PackSchema.parse(schemaJson))

        /**
         * Validates and parses [documents] (file name → raw JSON) without touching a file system.
         *
         * Problems are aggregated: the thrown [PackValidationException] carries one entry per violation
         * so a single load reports everything that is wrong.
         */
        fun fromDocuments(documents: Map<String, String>, schema: PackSchema): BrandPackRegistry {
            if (documents.isEmpty()) {
                throw PackValidationException("no brand packs found; an empty catalogue is not a valid state")
            }

            val problems = mutableListOf<String>()
            val loaded = mutableListOf<Pair<String, BrandPack>>()

            documents.toSortedMap().forEach { (fileName, text) ->
                val document = try {
                    parseJson(text)
                } catch (notJson: ProfileFormatException) {
                    problems += "$fileName: not valid JSON: ${notJson.message}"
                    return@forEach
                }

                val schemaProblems = schema.validate(document)
                if (schemaProblems.isNotEmpty()) {
                    problems += schemaProblems.map { problem -> "$fileName: $problem" }
                    return@forEach
                }

                try {
                    loaded += fileName to PackParser.parse(document, fileName)
                } catch (invalid: PackValidationException) {
                    problems += if (invalid.problems.isEmpty()) {
                        listOf("$fileName: ${invalid.message}")
                    } else {
                        invalid.problems.map { problem ->
                            if (problem.startsWith(fileName)) problem else "$fileName: $problem"
                        }
                    }
                }
            }

            loaded.groupBy { (_, pack) -> pack.brandId }
                .filterValues { entries -> entries.size > 1 }
                .forEach { (brandId, entries) ->
                    problems += "brandId \"$brandId\" is declared by ${entries.map { it.first }.sorted()}"
                }

            if (problems.isNotEmpty()) {
                throw PackValidationException(
                    "${problems.size} problem(s) in brand packs:\n" +
                        problems.joinToString(separator = "\n") { problem -> "  - $problem" },
                    problems,
                )
            }

            return BrandPackRegistry(loaded.map { (_, pack) -> pack }.sortedBy { pack -> pack.brandId })
        }
    }
}
