package com.innova.diag.domain.profile

/**
 * A minimal, strict, dependency-free JSON reader — enough to load a vehicle profile and nothing more.
 *
 * ### Why hand-written
 * The domain layer must stay pure Kotlin with no Android imports, and `org.json` is an Android class
 * (its JVM unit-test stubs throw). A serialization library would have to be resolved from a Maven
 * repository that the authoring environment cannot reach (`docs/ASSUMPTIONS.md` A9), which would make
 * the whole phase unbuildable rather than just ungated. So: about 200 lines with no dependencies and
 * no reflection.
 *
 * ### Why strict
 * A profile is a safety input. Anything that is not exactly RFC 8259 JSON is rejected rather than
 * coerced: no comments, no trailing commas, no single quotes, no `NaN`/`Infinity`, no leading zeros,
 * no duplicate keys, no non-JSON tokens. That is also what makes "profiles are data, never code"
 * verifiable — an expression such as `eval(...)` or `function(){}` is not a JSON value, so it fails to
 * parse instead of being quietly carried around as text.
 */
internal sealed interface JsonValue {

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    data class Arr(val items: List<JsonValue>) : JsonValue

    data class Str(val value: String) : JsonValue

    /** Keeps the source literal so integer-ness is exact: `1` is not `1.0`, and `1e3` is not `1000`. */
    data class Num(val literal: String) : JsonValue

    data class Bool(val value: Boolean) : JsonValue

    data object Null : JsonValue
}

/** Reads one JSON document. Callers should use [parseJson]; this class enforces the grammar. */
internal class JsonReader(private val text: String) {

    private var index = 0

    fun readDocument(): JsonValue {
        skipWhitespace()
        val value = readValue(depth = 0)
        skipWhitespace()
        if (index != text.length) fail("unexpected trailing content")
        return value
    }

    private fun readValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("nesting deeper than $MAX_DEPTH levels")
        skipWhitespace()
        if (index >= text.length) fail("unexpected end of document")
        return when (val character = text[index]) {
            '{' -> readObject(depth)
            '[' -> readArray(depth)
            '"' -> JsonValue.Str(readString())
            't' -> readLiteral("true", JsonValue.Bool(true))
            'f' -> readLiteral("false", JsonValue.Bool(false))
            'n' -> readLiteral("null", JsonValue.Null)
            else -> {
                if (character == '-' || character in '0'..'9') {
                    readNumber()
                } else {
                    fail("unexpected character '$character' (not a JSON value)")
                }
            }
        }
    }

    private fun readObject(depth: Int): JsonValue.Obj {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("expected a quoted object key")
            val key = readString()
            if (fields.containsKey(key)) fail("duplicate key \"$key\"")
            skipWhitespace()
            expect(':')
            fields[key] = readValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.Obj(fields)
                }
                else -> fail("expected ',' or '}' after a member of object")
            }
        }
    }

    private fun readArray(depth: Int): JsonValue.Arr {
        expect('[')
        val items = mutableListOf<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            items += readValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> {
                    index++
                    skipWhitespace()
                    if (peek() == ']') fail("trailing comma in array")
                }
                ']' -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> fail("expected ',' or ']' after an array element")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            if (index >= text.length) fail("unterminated string")
            when (val character = text[index]) {
                '"' -> {
                    index++
                    return builder.toString()
                }
                '\\' -> {
                    index++
                    if (index >= text.length) fail("unterminated escape")
                    when (val escape = text[index]) {
                        '"' -> builder.append('"')
                        '\\' -> builder.append('\\')
                        '/' -> builder.append('/')
                        'b' -> builder.append('\b')
                        'f' -> builder.append('\u000C')
                        'n' -> builder.append('\n')
                        'r' -> builder.append('\r')
                        't' -> builder.append('\t')
                        'u' -> {
                            index++
                            builder.append(readUnicodeEscape())
                            continue
                        }
                        else -> fail("invalid escape '\\$escape'")
                    }
                    index++
                }
                else -> {
                    if (character < ' ') fail("raw control character in string")
                    builder.append(character)
                    index++
                }
            }
        }
    }

    private fun readUnicodeEscape(): Char {
        if (index + 4 > text.length) fail("truncated \\u escape")
        val digits = text.substring(index, index + 4)
        if (digits.any { it !in HEX_ALPHABET }) fail("invalid \\u escape \"$digits\"")
        index += 4
        return digits.toInt(16).toChar()
    }

    private fun readNumber(): JsonValue.Num {
        val start = index
        if (peek() == '-') index++
        if (index >= text.length) fail("truncated number")
        when {
            peek() == '0' -> {
                index++
                if (index < text.length && text[index] in '0'..'9') fail("leading zero in number")
            }
            peek() in '1'..'9' -> while (index < text.length && text[index] in '0'..'9') index++
            else -> fail("expected a digit")
        }
        if (index < text.length && text[index] == '.') {
            index++
            if (index >= text.length || text[index] !in '0'..'9') fail("expected a digit after '.'")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index >= text.length || text[index] !in '0'..'9') fail("expected a digit in exponent")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        return JsonValue.Num(text.substring(start, index))
    }

    private fun readLiteral(literal: String, value: JsonValue): JsonValue {
        if (!text.startsWith(literal, index)) fail("expected \"$literal\"")
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\n', '\r' -> index++
                else -> return
            }
        }
    }

    private fun peek(): Char {
        if (index >= text.length) fail("unexpected end of document")
        return text[index]
    }

    private fun expect(character: Char) {
        if (index >= text.length || text[index] != character) {
            fail("expected '$character'")
        }
        index++
    }

    private fun fail(reason: String): Nothing {
        val line = text.take(index).count { it == '\n' } + 1
        throw ProfileFormatException("invalid JSON at line $line: $reason")
    }

    private companion object {
        const val MAX_DEPTH = 32
    }
}

/** Parses [json] into a tree, or throws [ProfileFormatException]. */
internal fun parseJson(json: String): JsonValue = JsonReader(json).readDocument()
