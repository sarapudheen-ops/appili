package com.innova.diag.domain.profile.pack

import com.innova.diag.domain.profile.parseJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The validator is deliberately narrow, and being narrow is the point: a schema that uses a keyword this
 * class cannot enforce would validate *less* than the schema claims, so it is refused instead.
 */
class PackSchemaTest {

    private fun schemaProblems(document: String, schema: String = TestPacks.shippedSchema()): List<String> =
        PackSchema.parse(schema).validate(parseJson(document))

    private fun rejectedSchema(schema: String): PackSchemaException {
        try {
            PackSchema.parse(schema)
        } catch (rejected: PackSchemaException) {
            return rejected
        }
        fail("the validator accepted a schema it cannot enforce")
        throw AssertionError("unreachable")
    }

    @Test
    fun `the shipped schema parses and a synthetic pack satisfies it`() {
        val schema = PackSchema.parse(TestPacks.shippedSchema())

        assertEquals(emptyList<String>(), schema.validate(parseJson(TestPacks.json(TestPacks.brand()))))
    }

    @Test
    fun `an unsupported keyword is refused rather than ignored`() {
        val error = rejectedSchema(
            """{"type":"object","properties":{"brandId":{"anyOf":[{"type":"string"}]}}}""",
        )

        assertTrue(error.message!!, error.message!!.contains("anyOf"))
    }

    @Test
    fun `a keyword with the wrong shape is refused`() {
        val error = rejectedSchema("""{"type":"object","properties":{"brandId":{"type":"string","enum":"brandId"}}}""")

        assertTrue(error.message!!, error.message!!.contains("enum"))
    }

    @Test
    fun `a non-object root schema is refused`() {
        rejectedSchema("[1, 2, 3]")
    }

    @Test
    fun `a missing required field is reported by name`() {
        val problems = schemaProblems("""{"brandId":"b"}""")

        assertTrue(
            problems.joinToString("\n"),
            problems.any { problem -> problem.contains("missing required field") && problem.contains("brandName") },
        )
    }

    @Test
    fun `an undeclared field is reported`() {
        val withScript = TestPacks.json(TestPacks.brand())
            .replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"script\":\"x\"")
        val problems = schemaProblems(withScript)

        assertTrue(problems.joinToString("\n"), problems.any { problem -> problem.contains("script") })
    }

    @Test
    fun `null is never valid, because no field is nullable`() {
        val withNull = TestPacks.json(TestPacks.brand())
            .replace("\"brandName\":\"Test Brand\"", "\"brandName\":null")
        val problems = schemaProblems(withNull)

        assertTrue(
            problems.joinToString("\n"),
            problems.any { problem -> problem.contains("expected string, found null") },
        )
    }

    @Test
    fun `an enum value outside the allowed set is reported`() {
        val withMaybe = TestPacks.json(TestPacks.brand()).replace("\"UNVERIFIED\"", "\"MAYBE\"")
        val problems = schemaProblems(withMaybe)

        assertTrue(problems.joinToString("\n"), problems.any { problem -> problem.contains("is not one of") })
    }

    @Test
    fun `a schemaVersion other than 2 is reported`() {
        val withV1 = TestPacks.json(TestPacks.brand()).replace("\"schemaVersion\":2", "\"schemaVersion\":1")
        val problems = schemaProblems(withV1)

        assertTrue(
            problems.joinToString("\n"),
            problems.any { problem -> problem.contains("expected the constant 2") },
        )
    }

    @Test
    fun `an empty array where at least one item is required is reported`() {
        val problems = schemaProblems(TestPacks.json(TestPacks.brand(brandLevelProtocolDefaults = emptyList())))

        assertTrue(
            problems.joinToString("\n"),
            problems.any { problem -> problem.contains("/brandLevelProtocolDefaults") },
        )
    }

    @Test
    fun `a number outside the allowed range is reported`() {
        val variant = TestPacks.variant(yearFrom = 1800, yearTo = 1800)
        val pack = TestPacks.brand(families = listOf(TestPacks.family(variants = listOf(variant))))
        val problems = schemaProblems(TestPacks.json(pack))

        assertTrue(problems.joinToString("\n"), problems.any { problem -> problem.contains("below the minimum") })
    }

    @Test
    fun `a repeated item is reported`() {
        val pack = TestPacks.brand(
            brandLevelProtocolDefaults = listOf("ISO 15765-4 CAN 11/500", "ISO 15765-4 CAN 11/500"),
        )
        val problems = schemaProblems(TestPacks.json(pack))

        assertTrue(problems.joinToString("\n"), problems.any { problem -> problem.contains("repeats") })
    }

    @Test
    fun `service bytes that are not a hex frame are reported`() {
        val pack = TestPacks.brand(
            families = listOf(
                TestPacks.family(
                    variants = listOf(
                        TestPacks.variant(supportedOperations = listOf(TestPacks.operation(serviceBytes = "01 0C"))),
                    ),
                ),
            ),
        )
        val problems = schemaProblems(TestPacks.json(pack))

        assertTrue(problems.joinToString("\n"), problems.any { problem -> problem.contains("does not match") })
    }
}
