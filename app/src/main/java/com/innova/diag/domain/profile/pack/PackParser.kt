package com.innova.diag.domain.profile.pack

import com.innova.diag.domain.profile.JsonValue
import com.innova.diag.domain.profile.ProfileStatus
import com.innova.diag.domain.profile.RiskClass
import com.innova.diag.domain.profile.YearRange

/**
 * Maps a schema-validated pack document onto the immutable hierarchy.
 *
 * Two layers of checking, deliberately:
 * 1. the **schema** says a field exists, is a string, is not blank and is one of the allowed words;
 * 2. the **models** say the combination is meaningful — a `VERIFIED` operation must carry bytes and a
 *    source, a `VERIFIED` variant must know its engine, a family's `variantIds` must match its variants.
 *
 * Step 2 violations arrive as `IllegalArgumentException` from a constructor, and [guard] turns them into
 * a [PackValidationException] that names the JSON pointer, so a bad pack is a data error rather than a
 * crash at first use.
 */
internal object PackParser {

    /** Parses [document] (already schema-validated) into a [BrandPack]. */
    fun parse(document: JsonValue, sourceName: String): BrandPack {
        val root = document.asObject("/", sourceName)
        return guard("/", sourceName) {
            BrandPack(
                brandId = root.requireString("brandId", "/", sourceName),
                brandName = root.requireString("brandName", "/", sourceName),
                commonDtcDictionary = root.requireString("commonDtcDictionary", "/", sourceName),
                brandLevelProtocolDefaults = root.requireArray("brandLevelProtocolDefaults", "/", sourceName)
                    .mapIndexed { index, item ->
                        item.asString("/brandLevelProtocolDefaults/$index", sourceName)
                    },
                securityPolicyOverrides = root.requireArray("securityPolicyOverrides", "/", sourceName)
                    .mapIndexed { index, item ->
                        item.asString("/securityPolicyOverrides/$index", sourceName)
                    },
                families = root.requireArray("families", "/", sourceName).mapIndexed { index, item ->
                    parseFamily(item, "/families/$index", sourceName)
                },
            )
        }
    }

    private fun parseFamily(element: JsonValue, path: String, sourceName: String): FamilyPack {
        val family = element.asObject(path, sourceName)
        return guard(path, sourceName) {
            FamilyPack(
                familyId = family.requireString("familyId", path, sourceName),
                familyName = family.requireString("familyName", path, sourceName),
                variantIds = family.requireArray("variantIds", path, sourceName).mapIndexed { index, item ->
                    item.asString("$path/variantIds/$index", sourceName)
                },
                variants = family.requireArray("variants", path, sourceName).mapIndexed { index, item ->
                    parseVariant(item, "$path/variants/$index", sourceName)
                },
            )
        }
    }

    private fun parseVariant(element: JsonValue, path: String, sourceName: String): VehicleVariantPack {
        val variant = element.asObject(path, sourceName)
        return guard(path, sourceName) {
            VehicleVariantPack(
                variantId = variant.requireString("variantId", path, sourceName),
                market = variant.requireString("market", path, sourceName),
                yearRange = parseYearRange(variant.requireObject("yearRange", path, sourceName), "$path/yearRange", sourceName),
                engineCode = variant.requireString("engineCode", path, sourceName),
                transmission = variant.requireString("transmission", path, sourceName),
                verificationStatus = statusOf(variant.requireString("verificationStatus", path, sourceName), "$path/verificationStatus", sourceName),
                ecuDefinitions = variant.requireArray("ecuDefinitions", path, sourceName).mapIndexed { index, item ->
                    parseEcu(item, "$path/ecuDefinitions/$index", sourceName)
                },
                supportedOperations = variant.requireArray("supportedOperations", path, sourceName).mapIndexed { index, item ->
                    parseOperation(item, "$path/supportedOperations/$index", sourceName)
                },
            )
        }
    }

    private fun parseYearRange(element: JsonValue.Obj, path: String, sourceName: String): YearRange =
        guard(path, sourceName) {
            YearRange(
                from = element.requireInt("from", path, sourceName),
                to = element.requireInt("to", path, sourceName),
            )
        }

    private fun parseEcu(element: JsonValue, path: String, sourceName: String): VariantEcuDefinition {
        val ecu = element.asObject(path, sourceName)
        return guard(path, sourceName) {
            VariantEcuDefinition(
                name = ecu.requireString("name", path, sourceName),
                partNumber = ecu.requireString("partNumber", path, sourceName),
                calibrationIds = ecu.requireArray("calibrationIds", path, sourceName).mapIndexed { index, item ->
                    item.asString("$path/calibrationIds/$index", sourceName)
                },
                network = ecu.requireString("network", path, sourceName),
                addressing = ecu.requireString("addressing", path, sourceName),
                verificationStatus = statusOf(ecu.requireString("verificationStatus", path, sourceName), "$path/verificationStatus", sourceName),
            )
        }
    }

    private fun parseOperation(element: JsonValue, path: String, sourceName: String): SupportedOperation {
        val operation = element.asObject(path, sourceName)
        return guard(path, sourceName) {
            SupportedOperation(
                id = operation.requireString("id", path, sourceName),
                description = operation.requireString("description", path, sourceName),
                sourceReference = operation.requireString("sourceReference", path, sourceName),
                verificationStatus = statusOf(operation.requireString("verificationStatus", path, sourceName), "$path/verificationStatus", sourceName),
                riskClass = riskClassOf(operation.requireString("riskClass", path, sourceName), "$path/riskClass", sourceName),
                scope = scopeOf(operation.requireString("scope", path, sourceName), "$path/scope", sourceName),
                serviceBytes = operation.requireString("serviceBytes", path, sourceName),
            )
        }
    }

    private fun statusOf(text: String, path: String, sourceName: String): ProfileStatus =
        ProfileStatus.entries.firstOrNull { status -> status.name == text }
            ?: throw PackValidationException(
                "$sourceName: $path: \"$text\" is not ${
                    ProfileStatus.entries.joinToString("/") { status -> status.name }
                }",
            )

    private fun riskClassOf(text: String, path: String, sourceName: String): RiskClass =
        RiskClass.entries.firstOrNull { riskClass -> riskClass.name == text }
            ?: throw PackValidationException(
                "$sourceName: $path: \"$text\" is not ${RiskClass.entries.joinToString("/") { it.name }}",
            )

    private fun scopeOf(text: String, path: String, sourceName: String): OperationScope =
        OperationScope.entries.firstOrNull { scope -> scope.name == text }
            ?: throw PackValidationException(
                "$sourceName: $path: \"$text\" is not ${OperationScope.entries.joinToString("/") { it.name }}",
            )

    /** Runs a constructor, turning its invariant violations into a located data error. */
    private inline fun <T> guard(path: String, sourceName: String, block: () -> T): T = try {
        block()
    } catch (invalid: IllegalArgumentException) {
        throw PackValidationException("$sourceName: $path: ${invalid.message}", listOf("$path: ${invalid.message}"), invalid)
    }
}

// --- strict document accessors ----------------------------------------------
//
// These exist even though the schema has already run: the parser is also reachable from tests and from
// future call sites, and a missing field must be a located error rather than a NullPointerException.

private fun JsonValue.asObject(path: String, sourceName: String): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw PackValidationException("$sourceName: $path: expected an object")

private fun JsonValue.asString(path: String, sourceName: String): String =
    (this as? JsonValue.Str)?.value ?: throw PackValidationException("$sourceName: $path: expected a string")

private fun JsonValue.asInt(path: String, sourceName: String): Int {
    val literal = (this as? JsonValue.Num)?.literal
        ?: throw PackValidationException("$sourceName: $path: expected an integer")
    return literal.toIntOrNull()
        ?: throw PackValidationException("$sourceName: $path: expected an integer, found $literal")
}

private fun JsonValue.Obj.requireValue(key: String, path: String, sourceName: String): JsonValue =
    fields[key] ?: throw PackValidationException("$sourceName: $path: missing required field \"$key\"")

private fun JsonValue.Obj.requireString(key: String, path: String, sourceName: String): String =
    requireValue(key, path, sourceName).asString("$path/$key", sourceName)

private fun JsonValue.Obj.requireInt(key: String, path: String, sourceName: String): Int =
    requireValue(key, path, sourceName).asInt("$path/$key", sourceName)

private fun JsonValue.Obj.requireArray(key: String, path: String, sourceName: String): List<JsonValue> =
    (requireValue(key, path, sourceName) as? JsonValue.Arr)?.items
        ?: throw PackValidationException("$sourceName: $path: \"$key\" must be an array")

private fun JsonValue.Obj.requireObject(key: String, path: String, sourceName: String): JsonValue.Obj =
    requireValue(key, path, sourceName).asObject("$path/$key", sourceName)
