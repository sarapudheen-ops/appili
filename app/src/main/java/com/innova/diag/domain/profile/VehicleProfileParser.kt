package com.innova.diag.domain.profile

/** Thrown when a profile file is not valid, or not acceptable, as a safety input. */
class ProfileFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Turns a JSON profile document into immutable [VehicleProfile] values.
 *
 * ### Fail-closed parsing
 * A profile decides whether bytes may reach a vehicle, so the parser refuses anything it cannot fully
 * understand instead of filling in a default:
 *
 * - unknown keys are an error, not noise — `"staus": "VERIFIED"` must not silently become an
 *   unverified-but-accepted file;
 * - the root `status` must equal the status derived from the ECUs ([VehicleProfile.status]); a file
 *   that claims `VERIFIED` while an ECU says `UNVERIFIED` is rejected;
 * - `UNKNOWN` is accepted only where the schema allows it (`txId`, `rxId`, `network`, `initCommands`);
 * - a `VERIFIED` ECU definition must carry real addresses, a real network and a real init sequence
 *   (the [EcuDefinition] constructor refuses the contradiction);
 * - operations must be `READ_ONLY` and use a generic read service; a profile that describes an
 *   `ACTIVE_TEST`, a `CONFIGURATION` change or a write service (`04`, `2E`, `31`, …) is rejected.
 *
 * ### Pure data, never code
 * The parser is a JSON reader followed by this file's explicit mapping. Nothing is evaluated, nothing
 * is reflected, and no value in the document can become a command unless it is a hex string that
 * [ServiceBytes.parse] accepts. A "script" in a profile is inert text in a rejected-or-ignored field,
 * or a parse error — never something that runs.
 */
object VehicleProfileParser {

    /** The only schema this parser understands. */
    const val SCHEMA_VERSION: Int = 1

    private val ROOT_KEYS = setOf(
        "schemaVersion", "status", "note", "id", "make", "model",
        "yearRange", "market", "ecuDefinitions", "operations",
    )
    private val YEAR_RANGE_KEYS = setOf("from", "to")
    private val ECU_KEYS = setOf(
        "name", "network", "txId", "rxId", "initCommands", "supportedServices", "status",
    )
    private val OPERATION_KEYS = setOf("id", "riskClass", "serviceBytes")

    /**
     * Parses [json] into a [VehicleProfile].
     *
     * @throws ProfileFormatException when the document is not JSON, is missing a required field, has
     *   an unknown field, or states anything the safety policy does not allow.
     */
    fun parse(json: String): VehicleProfile {
        val root = parseJson(json).asObject("profile")
        root.requireOnlyKeys(ROOT_KEYS, "profile")

        val version = root.optionalInt("schemaVersion", "profile")
        if (version != null && version != SCHEMA_VERSION) {
            fail("profile", "unsupported schemaVersion $version (this parser understands $SCHEMA_VERSION)")
        }
        // `note` is documentation for humans; validated as a string, deliberately not modelled.
        root.optionalString("note", "profile")

        val profile = guarded("profile") {
            VehicleProfile(
                id = root.string("id", "profile"),
                make = root.string("make", "profile"),
                model = root.string("model", "profile"),
                yearRange = root.objectAt("yearRange", "profile").let { range ->
                    range.requireOnlyKeys(YEAR_RANGE_KEYS, "profile.yearRange")
                    YearRange(
                        from = range.int("from", "profile.yearRange"),
                        to = range.int("to", "profile.yearRange"),
                    )
                },
                market = root.string("market", "profile"),
                ecuDefinitions = root.array("ecuDefinitions", "profile").mapIndexed { index, element ->
                    parseEcu(element, "profile.ecuDefinitions[$index]")
                },
                operations = root.optionalArray("operations", "profile").mapIndexed { index, element ->
                    parseOperation(element, "profile.operations[$index]")
                },
            )
        }

        // The document's own claim must agree with what its ECUs actually say.
        val declared = root.string("status", "profile").let { text ->
            statusOf(text, "profile.status")
        }
        val derived = profile.status
        if (declared != derived) {
            fail(
                "profile.status",
                "profile declares $declared but its ECU definitions resolve to $derived; " +
                    "a profile may not claim more verification than it has",
            )
        }
        return profile
    }

    private fun parseEcu(element: JsonValue, path: String): EcuDefinition {
        val ecu = element.asObject(path)
        ecu.requireOnlyKeys(ECU_KEYS, path)
        val name = ecu.string("name", path)
        val status = statusOf(ecu.string("status", path), "$path.status")
        return guarded(path) {
            EcuDefinition(
                name = name,
                network = ecu.string("network", path).trim(),
                txId = addressOf(ecu.string("txId", path), "$path.txId"),
                rxId = addressOf(ecu.string("rxId", path), "$path.rxId"),
                initCommands = initSequenceOf(ecu.field("initCommands", path), "$path.initCommands"),
                supportedServices = ecu.array("supportedServices", path).mapIndexed { index, service ->
                    service.asInt("$path.supportedServices[$index]")
                },
                status = status,
            )
        }
    }

    private fun parseOperation(element: JsonValue, path: String): DiagnosticOperation {
        val operation = element.asObject(path)
        operation.requireOnlyKeys(OPERATION_KEYS, path)
        val id = operation.string("id", path)
        val riskClass = when (val text = operation.string("riskClass", path).trim().uppercase()) {
            "READ_ONLY" -> RiskClass.READ_ONLY
            "ACTIVE_TEST", "CONFIGURATION" -> fail(
                "$path.riskClass",
                "a $text operation is not permitted in a profile: only READ_ONLY operations are " +
                    "allowed while this repository is read-only (AGENTS.md Rule 2)",
            )
            else -> fail("$path.riskClass", "unknown riskClass \"$text\"")
        }
        val serviceBytes = ServiceBytes.parse(operation.string("serviceBytes", path))
            ?: fail("$path.serviceBytes", "not an even-length hex byte string")
        return guarded(path) { DiagnosticOperation(id = id, riskClass = riskClass, serviceBytes = serviceBytes) }
    }

    private fun statusOf(text: String, path: String): ProfileStatus =
        when (val value = text.trim().uppercase()) {
            "VERIFIED" -> ProfileStatus.VERIFIED
            "UNVERIFIED" -> ProfileStatus.UNVERIFIED
            "SIMULATOR_ONLY" -> ProfileStatus.SIMULATOR_ONLY
            else -> fail(path, "unknown status \"$value\" (expected VERIFIED, UNVERIFIED or SIMULATOR_ONLY)")
        }

    private fun addressOf(text: String, path: String): EcuAddress =
        EcuAddress.parse(text) ?: fail(path, "expected UNKNOWN or a 3/8-digit hex address, got \"$text\"")

    private fun initSequenceOf(value: JsonValue, path: String): InitSequence = when (value) {
        is JsonValue.Str -> {
            if (!value.value.trim().equals(EcuAddress.UNKNOWN_VALUE, ignoreCase = true)) {
                fail(path, "expected UNKNOWN or an array of commands, got \"${value.value}\"")
            }
            InitSequence.Unknown
        }
        is JsonValue.Arr -> guarded(path) {
            InitSequence.Known(value.items.mapIndexed { index, item -> item.asString("$path[$index]") })
        }
        else -> fail(path, "expected UNKNOWN or an array of commands")
    }

    /**
     * Runs a model constructor, converting its `require` violations into a [ProfileFormatException].
     *
     * The models police themselves (a verified ECU without addresses cannot exist); this keeps that
     * knowledge reachable as a parse error instead of an `IllegalArgumentException` escaping to a UI.
     */
    private inline fun <T> guarded(path: String, block: () -> T): T = try {
        block()
    } catch (invalid: IllegalArgumentException) {
        throw ProfileFormatException("$path: ${invalid.message}", invalid)
    }

    private fun fail(path: String, reason: String): Nothing =
        throw ProfileFormatException("$path: $reason")
}

// --- Strict field access -----------------------------------------------------
//
// Every accessor names the exact JSON path in its error, because "profile.status" tells a maintainer
// where to look and a bare "invalid profile" does not. Missing and mistyped are never the same as
// absent-but-optional: only the `optional*` helpers tolerate absence.

private fun JsonValue.asObject(path: String): JsonValue.Obj =
    this as? JsonValue.Obj
        ?: throw ProfileFormatException("$path: expected a JSON object")

private fun JsonValue.asString(path: String): String =
    (this as? JsonValue.Str)?.value
        ?: throw ProfileFormatException("$path: expected a string")

private fun JsonValue.asInt(path: String): Int {
    val literal = (this as? JsonValue.Num)?.literal
        ?: throw ProfileFormatException("$path: expected an integer")
    if (literal.any { it == '.' || it == 'e' || it == 'E' }) {
        throw ProfileFormatException("$path: expected an integer, got $literal")
    }
    return literal.toIntOrNull()
        ?: throw ProfileFormatException("$path: integer out of range: $literal")
}

private fun JsonValue.Obj.field(key: String, path: String): JsonValue =
    fields[key] ?: throw ProfileFormatException("$path: missing required field \"$key\"")

private fun JsonValue.Obj.string(key: String, path: String): String =
    field(key, path).asString("$path.$key")

private fun JsonValue.Obj.optionalString(key: String, path: String): String? =
    fields[key]?.asString("$path.$key")

private fun JsonValue.Obj.int(key: String, path: String): Int =
    field(key, path).asInt("$path.$key")

private fun JsonValue.Obj.optionalInt(key: String, path: String): Int? =
    fields[key]?.asInt("$path.$key")

private fun JsonValue.Obj.array(key: String, path: String): List<JsonValue> =
    (field(key, path) as? JsonValue.Arr)?.items
        ?: throw ProfileFormatException("$path.$key: expected a JSON array")

private fun JsonValue.Obj.optionalArray(key: String, path: String): List<JsonValue> {
    val value = fields[key] ?: return emptyList()
    return (value as? JsonValue.Arr)?.items
        ?: throw ProfileFormatException("$path.$key: expected a JSON array")
}

private fun JsonValue.Obj.objectAt(key: String, path: String): JsonValue.Obj =
    field(key, path).asObject("$path.$key")

/** Rejects any key outside the documented schema; see the class comment for why this is strict. */
private fun JsonValue.Obj.requireOnlyKeys(allowed: Set<String>, path: String) {
    val unknown = fields.keys - allowed
    if (unknown.isNotEmpty()) {
        throw ProfileFormatException(
            "$path: unknown field(s) ${unknown.sorted()}; a profile may only contain the " +
                "documented schema (a misspelt field must not be silently ignored)",
        )
    }
}
