package com.innova.diag.domain.profile.pack

import com.innova.diag.domain.profile.ProfileStatus
import com.innova.diag.domain.profile.RiskClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The registry is where a pack either becomes data or is refused, so every test here is about
 * **rejection being loud**: no pack is ever "partially loaded", and one bad file fails the whole load
 * with every problem listed.
 */
class BrandPackRegistryTest {

    private val schema = TestPacks.shippedSchema()

    private fun load(vararg packs: Pair<String, Map<String, Any?>>): BrandPackRegistry =
        BrandPackRegistry.fromDocuments(TestPacks.documents(*packs), schema)

    private fun failure(vararg packs: Pair<String, Map<String, Any?>>): PackValidationException {
        try {
            load(*packs)
        } catch (invalid: PackValidationException) {
            return invalid
        }
        fail("the registry accepted packs it should have refused")
        throw AssertionError("unreachable")
    }

    @Test
    fun `a valid synthetic pack is listed and its variant is reachable`() {
        val registry = load("test_brand.json" to TestPacks.brand())

        val brands = registry.listBrands()
        assertEquals(1, brands.size)
        assertEquals("test_brand", brands.single().brandId)
        assertEquals("Test Brand", brands.single().brandName)

        val variant = registry.getVariant("test_brand", "test_variant")
        assertNotNull(variant)
        assertEquals("test_variant", variant!!.variantId)
        assertEquals(ProfileStatus.UNVERIFIED, variant.verificationStatus)
        assertEquals("an unverified variant never promotes", Tier.T4_UNVERIFIED, registry.getTier("test_brand"))
    }

    @Test
    fun `a pack missing a required field is refused with the field named`() {
        val broken = TestPacks.brand().toMutableMap()
        broken.remove("brandName")

        val error = failure("broken.json" to broken)

        val problems = error.problems.joinToString("\n")
        assertTrue(problems, problems.contains("broken.json"))
        assertTrue(problems, problems.contains("missing required field"))
        assertTrue(problems, problems.contains("brandName"))
    }

    @Test
    fun `a NULL value is refused - the schema has no nullable field`() {
        val broken = TestPacks.brand() + mapOf("commonDtcDictionary" to null)

        val error = failure("null.json" to broken)

        val problems = error.problems.joinToString("\n")
        assertTrue(problems, problems.contains("/commonDtcDictionary"))
        assertTrue(problems, problems.contains("null"))
    }

    @Test
    fun `a NULL deep inside a variant is refused too`() {
        val variant = TestPacks.variant() + mapOf("engineCode" to null)
        val broken = TestPacks.brand(families = listOf(TestPacks.family(variants = listOf(variant))))

        val error = failure("null_variant.json" to broken)

        val problems = error.problems.joinToString("\n")
        assertTrue(problems, problems.contains("/families/0/variants/0/engineCode"))
    }

    @Test
    fun `an empty string is refused`() {
        val variant = TestPacks.variant() + mapOf("market" to "")
        val broken = TestPacks.brand(families = listOf(TestPacks.family(variants = listOf(variant))))

        val error = failure("empty.json" to broken)

        assertTrue(
            error.problems.joinToString("\n"),
            error.problems.any { problem -> problem.contains("/families/0/variants/0/market") },
        )
    }

    @Test
    fun `an undeclared field is refused - profiles are not open documents`() {
        val broken = TestPacks.brand() + mapOf("script" to "while (true) {}")

        val error = failure("extra.json" to broken)

        val problems = error.problems.joinToString("\n")
        assertTrue(problems, problems.contains("script"))
    }

    @Test
    fun `a security policy override is refused - the default is fail-closed`() {
        val broken = TestPacks.brand(securityPolicyOverrides = listOf("disable-dtc-check"))

        val error = failure("override.json" to broken)

        assertTrue(
            error.problems.joinToString("\n"),
            error.problems.any { problem -> problem.contains("securityPolicyOverrides") },
        )
    }

    @Test
    fun `two brands are both listed, each with its own derived tier`() {
        val registry = load(
            "first.json" to TestPacks.brand(brandId = "first_brand", brandName = "First Brand"),
            "second.json" to TestPacks.brand(
                brandId = "second_brand",
                brandName = "Second Brand",
                families = listOf(
                    TestPacks.family(
                        variants = listOf(TestPacks.verifiedVariant(supportedOperations = listOf(TestPacks.verifiedGenericOperation()))),
                    ),
                ),
            ),
        )

        assertEquals(listOf("first_brand", "second_brand"), registry.listBrands().map { brand -> brand.brandId })
        assertEquals(Tier.T4_UNVERIFIED, registry.getTier("first_brand"))
        assertEquals(Tier.T3_GENERIC_ONLY, registry.getTier("second_brand"))
        assertEquals(
            mapOf("first_brand" to Tier.T4_UNVERIFIED, "second_brand" to Tier.T3_GENERIC_ONLY),
            registry.listTiers(),
        )
    }

    @Test
    fun `every tier is derived from the operations, never stored`() {
        val registry = load(
            "t1.json" to TestPacks.brand(
                brandId = "t1_brand",
                families = listOf(
                    TestPacks.family(
                        variants = listOf(TestPacks.verifiedVariant(supportedOperations = listOf(TestPacks.verifiedActiveTestOperation()))),
                    ),
                ),
            ),
            "t2.json" to TestPacks.brand(
                brandId = "t2_brand",
                families = listOf(
                    TestPacks.family(
                        variants = listOf(TestPacks.verifiedVariant(supportedOperations = listOf(TestPacks.verifiedEnhancedOperation()))),
                    ),
                ),
            ),
            "t3.json" to TestPacks.brand(
                brandId = "t3_brand",
                families = listOf(
                    TestPacks.family(
                        variants = listOf(TestPacks.verifiedVariant(supportedOperations = listOf(TestPacks.verifiedGenericOperation()))),
                    ),
                ),
            ),
            "t4.json" to TestPacks.brand(brandId = "t4_brand"),
        )

        assertEquals(Tier.T1_FULL_ENHANCED, registry.getTier("t1_brand"))
        assertEquals(Tier.T2_PARTIAL_ENHANCED, registry.getTier("t2_brand"))
        assertEquals(Tier.T3_GENERIC_ONLY, registry.getTier("t3_brand"))
        assertEquals(Tier.T4_UNVERIFIED, registry.getTier("t4_brand"))
    }

    @Test
    fun `a brand with no families at all is T4 and describes no variant`() {
        val registry = load("stub.json" to TestPacks.brand(brandId = "stub_brand", families = emptyList()))

        assertEquals(Tier.T4_UNVERIFIED, registry.getTier("stub_brand"))
        assertNull(registry.getVariant("stub_brand", "any_variant"))
        assertEquals(Tier.T4_UNVERIFIED, registry.getTier("no_such_brand"))
    }

    @Test
    fun `a VERIFIED variant claiming UNKNOWN fields is refused - verified cannot be hollow`() {
        val hollow = TestPacks.variant(
            variantId = "hollow_variant",
            verificationStatus = "VERIFIED",
            engineCode = "UNKNOWN",
            transmission = "UNKNOWN",
            ecuDefinitions = listOf(TestPacks.capturedEcu()),
            supportedOperations = listOf(TestPacks.verifiedGenericOperation()),
        )

        val error = failure("hollow.json" to TestPacks.brand(families = listOf(TestPacks.family(variants = listOf(hollow)))))

        assertTrue(
            error.problems.joinToString("\n"),
            error.problems.any { problem -> problem.contains("VERIFIED") && problem.contains("engine code") },
        )
    }

    @Test
    fun `a VERIFIED operation with UNKNOWN service bytes is refused`() {
        val hollow = TestPacks.operation(id = "gap", verificationStatus = "VERIFIED", serviceBytes = "UNKNOWN")
        val pack = TestPacks.brand(
            families = listOf(
                TestPacks.family(
                    variants = listOf(TestPacks.verifiedVariant(supportedOperations = listOf(hollow))),
                ),
            ),
        )

        val error = failure("hollow_op.json" to pack)

        assertTrue(
            error.problems.joinToString("\n"),
            error.problems.any { problem -> problem.contains("gap") },
        )
    }

    @Test
    fun `a family whose variantIds disagree with its variants is refused`() {
        val lying = TestPacks.family(variantIds = listOf("test_variant", "never_written"), variants = listOf(TestPacks.variant()))

        val error = failure("lying.json" to TestPacks.brand(families = listOf(lying)))

        assertTrue(
            error.problems.joinToString("\n"),
            error.problems.any { problem -> problem.contains("never_written") },
        )
    }

    @Test
    fun `the same brandId in two files is refused`() {
        val error = failure(
            "a.json" to TestPacks.brand(),
            "b.json" to TestPacks.brand(),
        )

        val problems = error.problems.joinToString("\n")
        assertTrue(problems, problems.contains("test_brand"))
        assertTrue(problems, problems.contains("is declared by"))
        assertTrue(problems, problems.contains("a.json"))
        assertTrue(problems, problems.contains("b.json"))
    }

    @Test
    fun `all problems are reported at once, not just the first`() {
        val broken = TestPacks.brand(brandName = "") + mapOf("script" to "x")

        val error = failure("many.json" to broken)

        assertTrue("expected several problems, got ${error.problems}", error.problems.size >= 2)
    }

    @Test
    fun `a document that is not JSON is refused with the file name`() {
        val error = packFailure(mapOf("garbage.json" to "{ not json"))

        assertEquals(1, error.problems.size)
        assertTrue(error.problems.single(), error.problems.single().contains("garbage.json"))
    }

    @Test
    fun `an empty catalogue is refused rather than loading silently`() {
        val error = packFailure(emptyMap())

        assertTrue(error.message!!, error.message!!.contains("empty catalogue"))
    }

    @Test
    fun `a schema using an unsupported keyword is refused - the validator is closed`() {
        val schemaWithRef = TestPacks.shippedSchema().replace("\"type\": \"object\",", "\"oneOf\": [], \"type\": \"object\",")

        try {
            BrandPackRegistry.fromDocuments(TestPacks.documents("test_brand.json" to TestPacks.brand()), schemaWithRef)
            fail("the validator accepted an unsupported keyword")
        } catch (unsupported: PackSchemaException) {
            assertTrue(unsupported.message!!, unsupported.message!!.contains("oneOf"))
        }
    }

    @Test
    fun `a status outside the three known values is refused`() {
        val variant = TestPacks.variant() + mapOf("verificationStatus" to "PROBABLY")
        val pack = TestPacks.brand(families = listOf(TestPacks.family(variants = listOf(variant))))

        val error = failure("status.json" to pack)

        assertTrue(
            error.problems.joinToString("\n"),
            error.problems.any { problem -> problem.contains("PROBABLY") },
        )
    }

    @Test
    fun `the parsed pack keeps every captured value and its classification`() {
        val registry = load(
            "test_brand.json" to TestPacks.brand(
                brandLevelProtocolDefaults = listOf("ISO 15765-4 CAN 11/500"),
                families = listOf(
                    TestPacks.family(
                        variants = listOf(TestPacks.verifiedVariant(supportedOperations = listOf(TestPacks.verifiedGenericOperation()))),
                    ),
                ),
            ),
        )

        val variant = registry.getVariant("test_brand", "verified_variant")!!
        assertEquals("ENGINE-TEST", variant.engineCode)
        assertEquals(2007, variant.yearRange.from)
        assertEquals(ProfileStatus.VERIFIED, variant.verificationStatus)
        assertEquals(listOf("ISO 15765-4 CAN 11/500"), registry.getBrand("test_brand")!!.brandLevelProtocolDefaults)

        val operation = variant.operation("generic.read-engine-speed")!!
        assertEquals(RiskClass.READ_ONLY, operation.riskClass)
        assertEquals(OperationScope.GENERIC, operation.scope)
        assertEquals("010C", operation.serviceBytes)
        assertTrue("the fixture's ECU is captured", variant.ecuDefinitions.single().isVerified)
    }

    private fun packFailure(documents: Map<String, String>): PackValidationException =
        try {
            BrandPackRegistry.fromDocuments(documents, schema)
            fail("the registry accepted documents it should have refused")
            throw AssertionError("unreachable")
        } catch (invalid: PackValidationException) {
            invalid
        }
}
