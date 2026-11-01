package com.innova.diag.domain.profile.pack

import com.innova.diag.domain.profile.JsonValue
import com.innova.diag.domain.profile.ProfileFormatException
import com.innova.diag.domain.profile.parseJson

/** Thrown when a pack (or the registry as a whole) is not acceptable as safety data. */
class PackValidationException(
    message: String,
    val problems: List<String> = emptyList(),
    cause: Throwable? = null,
) : Exception(message, cause)

/** Thrown when the schema file itself cannot be used — a different failure from a bad pack. */
class PackSchemaException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A JSON Schema validator for vehicle packs, restricted to a documented keyword subset.
 *
 * ### Why not a full JSON Schema library
 * The domain must stay pure Kotlin with no Android imports, and no serialization/validation dependency
 * can be resolved in this environment (`docs/ASSUMPTIONS.md` A9). More importantly, "validated against
 * the schema" has to mean something: this validator **reads the shipped schema file** and enforces what
 * it says, rather than re-implementing the rules in Kotlin where the two could drift.
 *
 * ### Why an unsupported keyword is an error, not a no-op
 * If the schema used `$ref`, `oneOf`, `format` or anything else this class cannot enforce, silently
 * ignoring it would leave the pack *less* validated than the schema claims. [parse] therefore rejects
 * the schema at load time, which is the fail-closed direction: the schema may only say what the engine
 * can check.
 *
 * Supported keywords: `type`, `properties`, `required`, `additionalProperties`, `enum`, `const`,
 * `pattern`, `minLength`, `maxLength`, `minItems`, `maxItems`, `uniqueItems`, `items`, `minimum`,
 * `maximum`. Ignored (documentation only): `$schema`, `$id`, `title`, `description`.
 */
internal class PackSchema private constructor(private val root: JsonValue.Obj) {

    /**
     * Validates [document] against the schema.
     *
     * @return one human-readable problem per violation, each prefixed with a JSON pointer
     *   (`/families/0/variants/1/market: expected string`). Empty means valid.
     */
    fun validate(document: JsonValue): List<String> {
        val problems = mutableListOf<String>()
        checkNode(root, document, path = "", problems = problems)
        return problems
    }

    private fun checkNode(
        schema: JsonValue.Obj,
        node: JsonValue,
        path: String,
        problems: MutableList<String>,
    ) {
        val declaredType = schema.optionalString(KEYWORD_TYPE)
        if (declaredType != null && !matchesType(declaredType, node)) {
            problems += "${where(path)}: expected $declaredType, found ${describe(node)}"
            return
        }

        schema.optionalValue(KEYWORD_CONST)?.let { expected ->
            if (node != expected) {
                problems += "${where(path)}: expected the constant ${render(expected)}, found ${render(node)}"
            }
        }

        schema.optionalArray(KEYWORD_ENUM)?.let { allowed ->
            if (allowed.none { candidate -> candidate == node }) {
                problems += "${where(path)}: ${render(node)} is not one of ${allowed.joinToString { render(it) }}"
            }
        }

        when (node) {
            is JsonValue.Str -> checkString(schema, node.value, path, problems)
            is JsonValue.Num -> checkNumber(schema, node.literal, path, problems)
            is JsonValue.Arr -> checkArray(schema, node.items, path, problems)
            is JsonValue.Obj -> checkObject(schema, node, path, problems)
            JsonValue.Null -> Unit
            is JsonValue.Bool -> Unit
        }
    }

    private fun checkString(
        schema: JsonValue.Obj,
        text: String,
        path: String,
        problems: MutableList<String>,
    ) {
        schema.optionalString(KEYWORD_PATTERN)?.let { pattern ->
            if (!Regex(pattern).containsMatchIn(text)) {
                problems += "${where(path)}: \"$text\" does not match $pattern"
            }
        }
        schema.optionalInt(KEYWORD_MIN_LENGTH)?.let { minimum ->
            if (text.length < minimum) {
                problems += "${where(path)}: \"$text\" is shorter than $minimum characters"
            }
        }
        schema.optionalInt(KEYWORD_MAX_LENGTH)?.let { maximum ->
            if (text.length > maximum) {
                problems += "${where(path)}: \"$text\" is longer than $maximum characters"
            }
        }
    }

    private fun checkNumber(
        schema: JsonValue.Obj,
        literal: String,
        path: String,
        problems: MutableList<String>,
    ) {
        val value = literal.toDoubleOrNull()
        if (value == null) {
            problems += "${where(path)}: $literal is not a usable number"
            return
        }
        schema.optionalNumber(KEYWORD_MINIMUM)?.let { minimum ->
            if (value < minimum) problems += "${where(path)}: $literal is below the minimum $minimum"
        }
        schema.optionalNumber(KEYWORD_MAXIMUM)?.let { maximum ->
            if (value > maximum) problems += "${where(path)}: $literal is above the maximum $maximum"
        }
    }

    private fun checkArray(
        schema: JsonValue.Obj,
        items: List<JsonValue>,
        path: String,
        problems: MutableList<String>,
    ) {
        schema.optionalInt(KEYWORD_MIN_ITEMS)?.let { minimum ->
            if (items.size < minimum) {
                problems += "${where(path)}: needs at least $minimum item(s), found ${items.size}"
            }
        }
        schema.optionalInt(KEYWORD_MAX_ITEMS)?.let { maximum ->
            if (items.size > maximum) {
                problems += "${where(path)}: allows at most $maximum item(s), found ${items.size}"
            }
        }
        if (schema.optionalBoolean(KEYWORD_UNIQUE_ITEMS) == true) {
            val duplicates = items.groupBy { item -> item }.filterValues { group -> group.size > 1 }.keys
            if (duplicates.isNotEmpty()) {
                problems += "${where(path)}: repeats ${duplicates.joinToString { render(it) }}"
            }
        }
        val itemSchema = schema.optionalObject(KEYWORD_ITEMS)
        if (itemSchema != null) {
            items.forEachIndexed { index, item ->
                checkNode(itemSchema, item, "$path/$index", problems)
            }
        }
    }

    private fun checkObject(
        schema: JsonValue.Obj,
        node: JsonValue.Obj,
        path: String,
        problems: MutableList<String>,
    ) {
        schema.optionalArray(KEYWORD_REQUIRED)?.forEach { required ->
            val name = (required as? JsonValue.Str)?.value ?: return@forEach
            if (!node.fields.containsKey(name)) {
                problems += "${where(path)}: missing required field \"$name\""
            }
        }

        val properties = schema.optionalObject(KEYWORD_PROPERTIES)
        properties?.fields?.forEach { (name, propertySchema) ->
            val value = node.fields[name] ?: return@forEach
            if (propertySchema is JsonValue.Obj) {
                checkNode(propertySchema, value, "$path/$name", problems)
            }
        }

        when (val additional = schema.optionalValue(KEYWORD_ADDITIONAL_PROPERTIES)) {
            is JsonValue.Bool -> if (!additional.value) {
                val declared = properties?.fields?.keys ?: emptySet()
                val extra = node.fields.keys - declared
                if (extra.isNotEmpty()) {
                    problems += "${where(path)}: unknown field(s) ${extra.sorted()}"
                }
            }
            is JsonValue.Obj -> {
                val declared = properties?.fields?.keys ?: emptySet()
                node.fields.forEach { (name, value) ->
                    if (name !in declared) checkNode(additional, value, "$path/$name", problems)
                }
            }
            else -> Unit
        }
    }

    companion object {

        /** Keywords this validator enforces. A schema using any other keyword is refused. */
        private val SUPPORTED = setOf(
            KEYWORD_TYPE, KEYWORD_PROPERTIES, KEYWORD_REQUIRED, KEYWORD_ADDITIONAL_PROPERTIES,
            KEYWORD_ENUM, KEYWORD_CONST, KEYWORD_PATTERN, KEYWORD_MIN_LENGTH, KEYWORD_MAX_LENGTH,
            KEYWORD_MIN_ITEMS, KEYWORD_MAX_ITEMS, KEYWORD_UNIQUE_ITEMS, KEYWORD_ITEMS,
            KEYWORD_MINIMUM, KEYWORD_MAXIMUM,
        )

        /** Presentation-only keywords: they carry no validation and cannot hide a rule. */
        private val DOCUMENTATION = setOf("\$schema", "\$id", "title", "description")

        /**
         * Reads and self-checks a schema document.
         *
         * @throws PackSchemaException when the document is not JSON, is not a schema object, or uses a
         *   keyword this validator cannot enforce.
         */
        fun parse(schemaJson: String): PackSchema {
            val root = try {
                parseJson(schemaJson)
            } catch (invalid: ProfileFormatException) {
                throw PackSchemaException("schema is not valid JSON: ${invalid.message}", invalid)
            }
            if (root !is JsonValue.Obj) throw PackSchemaException("schema must be a JSON object")
            verifySchemaObject(root, path = "")
            return PackSchema(root)
        }

        /** Walks the schema and refuses anything it could not enforce. */
        private fun verifySchemaObject(schema: JsonValue.Obj, path: String) {
            val unsupported = schema.fields.keys - SUPPORTED - DOCUMENTATION
            if (unsupported.isNotEmpty()) {
                throw PackSchemaException(
                    "${where(path)}, schema uses unsupported keyword(s) ${unsupported.sorted()}; this " +
                        "validator would ignore them and the pack would be less checked than the schema claims",
                )
            }
            schema.optionalString(KEYWORD_TYPE)?.let { type ->
                if (type !in TYPES) throw PackSchemaException("${where(path)}: unknown type \"$type\"")
            }
            schema.optionalObject(KEYWORD_PROPERTIES)?.fields?.forEach { (name, property) ->
                if (property !is JsonValue.Obj) {
                    throw PackSchemaException(
                        "${where(path)}/properties/$name: a property schema must be an object",
                    )
                }
                verifySchemaObject(property, "$path/properties/$name")
            }
            requireShape(schema, KEYWORD_ENUM, "an array", path) { it is JsonValue.Arr }
            requireShape(schema, KEYWORD_PATTERN, "a string", path) { it is JsonValue.Str }
            requireShape(schema, KEYWORD_MIN_LENGTH, "an integer", path) { it.isInteger() }
            requireShape(schema, KEYWORD_MAX_LENGTH, "an integer", path) { it.isInteger() }
            requireShape(schema, KEYWORD_MIN_ITEMS, "an integer", path) { it.isInteger() }
            requireShape(schema, KEYWORD_MAX_ITEMS, "an integer", path) { it.isInteger() }
            requireShape(schema, KEYWORD_MINIMUM, "a number", path) { it is JsonValue.Num }
            requireShape(schema, KEYWORD_MAXIMUM, "a number", path) { it is JsonValue.Num }

            schema.optionalArray(KEYWORD_REQUIRED)?.forEach { required ->
                if (required !is JsonValue.Str) {
                    throw PackSchemaException("${where(path)}/required: entries must be strings")
                }
            }
            if (schema.optionalBoolean(KEYWORD_UNIQUE_ITEMS) == null &&
                schema.optionalValue(KEYWORD_UNIQUE_ITEMS) != null
            ) {
                throw PackSchemaException("${where(path)}/uniqueItems: must be a boolean")
            }
            when (val items = schema.optionalValue(KEYWORD_ITEMS)) {
                null -> Unit
                is JsonValue.Obj -> verifySchemaObject(items, "$path/items")
                else -> throw PackSchemaException("${where(path)}/items: must be an object")
            }
            when (val additional = schema.optionalValue(KEYWORD_ADDITIONAL_PROPERTIES)) {
                null -> Unit
                is JsonValue.Bool -> Unit
                is JsonValue.Obj -> verifySchemaObject(additional, "$path/additionalProperties")
                else -> throw PackSchemaException(
                    "${where(path)}/additionalProperties: must be a boolean or an object",
                )
            }
        }

        /** Throws when a keyword this validator claims to enforce is present in an unusable shape. */
        private fun requireShape(
            schema: JsonValue.Obj,
            keyword: String,
            expected: String,
            path: String,
            accepts: (JsonValue) -> Boolean,
        ) {
            val value = schema.optionalValue(keyword) ?: return
            if (!accepts(value)) {
                throw PackSchemaException(
                    "${path.ifEmpty { "<root>" }}/$keyword: must be $expected for this validator",
                )
            }
        }

        private fun JsonValue.isInteger(): Boolean =
            this is JsonValue.Num && literal.none { it == '.' || it == 'e' || it == 'E' }

        private fun matchesType(type: String, node: JsonValue): Boolean = when (type) {
            "object" -> node is JsonValue.Obj
            "array" -> node is JsonValue.Arr
            "string" -> node is JsonValue.Str
            "boolean" -> node is JsonValue.Bool
            "null" -> node is JsonValue.Null
            "number" -> node is JsonValue.Num
            "integer" -> node is JsonValue.Num && node.literal.none { it == '.' || it == 'e' || it == 'E' }
            else -> throw PackSchemaException("unknown type \"$type\"")
        }

        private fun describe(node: JsonValue): String = when (node) {
            is JsonValue.Obj -> "object"
            is JsonValue.Arr -> "array"
            is JsonValue.Str -> "string"
            is JsonValue.Num -> "number"
            is JsonValue.Bool -> "boolean"
            JsonValue.Null -> "null"
        }

        private fun render(node: JsonValue): String = when (node) {
            is JsonValue.Str -> "\"${node.value}\""
            is JsonValue.Num -> node.literal
            is JsonValue.Bool -> node.value.toString()
            is JsonValue.Arr -> "[${node.items.size} item(s)]"
            is JsonValue.Obj -> "{${node.fields.keys.joinToString()}}"
            JsonValue.Null -> "null"
        }

        private fun where(path: String): String = if (path.isEmpty()) "<root>" else path

        private const val KEYWORD_TYPE = "type"
        private const val KEYWORD_PROPERTIES = "properties"
        private const val KEYWORD_REQUIRED = "required"
        private const val KEYWORD_ADDITIONAL_PROPERTIES = "additionalProperties"
        private const val KEYWORD_ENUM = "enum"
        private const val KEYWORD_CONST = "const"
        private const val KEYWORD_PATTERN = "pattern"
        private const val KEYWORD_MIN_LENGTH = "minLength"
        private const val KEYWORD_MAX_LENGTH = "maxLength"
        private const val KEYWORD_MIN_ITEMS = "minItems"
        private const val KEYWORD_MAX_ITEMS = "maxItems"
        private const val KEYWORD_UNIQUE_ITEMS = "uniqueItems"
        private const val KEYWORD_ITEMS = "items"
        private const val KEYWORD_MINIMUM = "minimum"
        private const val KEYWORD_MAXIMUM = "maximum"

        private val TYPES = setOf("object", "array", "string", "integer", "number", "boolean", "null")
    }
}

// --- schema-document accessors ----------------------------------------------
//
// The schema is data too, so reading it is as strict as reading a pack: a missing keyword is `null`,
// but a keyword of the wrong shape is an exception rather than a quiet skip.

private fun JsonValue.Obj.optionalValue(key: String): JsonValue? = fields[key]

private fun JsonValue.Obj.optionalString(key: String): String? =
    (fields[key] as? JsonValue.Str)?.value

private fun JsonValue.Obj.optionalInt(key: String): Int? =
    (fields[key] as? JsonValue.Num)?.literal?.toIntOrNull()

private fun JsonValue.Obj.optionalNumber(key: String): Double? =
    (fields[key] as? JsonValue.Num)?.literal?.toDoubleOrNull()

private fun JsonValue.Obj.optionalBoolean(key: String): Boolean? =
    (fields[key] as? JsonValue.Bool)?.value

private fun JsonValue.Obj.optionalObject(key: String): JsonValue.Obj? =
    fields[key] as? JsonValue.Obj

private fun JsonValue.Obj.optionalArray(key: String): List<JsonValue>? =
    (fields[key] as? JsonValue.Arr)?.items
