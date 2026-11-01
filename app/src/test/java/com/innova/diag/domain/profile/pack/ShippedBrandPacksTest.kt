package com.innova.diag.domain.profile.pack

import com.innova.diag.core.transport.assertThrowsSuspend
import com.innova.diag.domain.profile.EcuAddress
import com.innova.diag.domain.profile.ProfileStatus
import com.innova.diag.domain.profile.RecordingPort
import com.innova.diag.domain.profile.RiskClass
import com.innova.diag.domain.profile.SafetyViolationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The packs that actually ship.
 *
 * This class is the honesty check on the catalogue: it asserts that no shipped pack claims anything it
 * cannot back, that no manufacturer command exists anywhere in `assets/profiles/brands`, and that every
 * single shipped operation is refused at runtime. The tests run against the real files, not fixtures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShippedBrandPacksTest {

    private val packs: Map<String, String> = TestPacks.shippedPacks()

    private val catalogue: BrandPackRegistry by lazy {
        BrandPackRegistry.fromDocuments(packs, TestPacks.shippedSchema())
    }

    /** The generic modes the app implements; a shipped frame must be one of these or UNKNOWN. */
    private val genericFrames = setOf("0100", "010C", "010D", "03", "07", "0A", "0902")

    @Test
    fun `every shipped pack loads through the real schema`() {
        assertEquals("expected 12 brand packs", 12, packs.size)
        assertEquals(12, catalogue.listBrands().size)
    }

    @Test
    fun `all twelve brands are listed in a stable order and all are T4`() {
        assertEquals(
            listOf(
                "bmw", "ford", "gm", "honda", "hyundai", "mazda",
                "mercedes_benz", "mitsubishi", "nissan", "subaru", "toyota", "volkswagen",
            ),
            catalogue.listBrands().map { brand -> brand.brandId },
        )
        assertTrue(
            "no brand has physical evidence yet: ${catalogue.listTiers()}",
            catalogue.listTiers().values.all { tier -> tier == Tier.T4_UNVERIFIED },
        )
    }

    @Test
    fun `the pilot variant is described exactly as far as it is known`() {
        val variant = catalogue.getVariant("toyota", "innova_2007_gcc")
        assertNotNull("the Innova pilot variant must exist", variant)
        variant!!

        assertEquals("GCC", variant.market)
        assertEquals(2007, variant.yearRange.from)
        assertEquals(2007, variant.yearRange.to)
        assertEquals("the engine code was never captured on the pilot car", UNKNOWN_VALUE, variant.engineCode)
        assertEquals(UNKNOWN_VALUE, variant.transmission)
        assertEquals(ProfileStatus.UNVERIFIED, variant.verificationStatus)
        assertEquals("Engine ECU", variant.ecuDefinitions.single().name)
        assertEquals(UNKNOWN_VALUE, variant.ecuDefinitions.single().partNumber)
        assertEquals(
            "the brand level protocol list is not a guess either",
            listOf(UNKNOWN_VALUE),
            catalogue.getBrand("toyota")!!.brandLevelProtocolDefaults,
        )
    }

    @Test
    fun `the pilot brand declares the IMV family`() {
        val family = catalogue.getBrand("toyota")!!.families.single()

        assertEquals("imv_platform", family.familyId)
        assertEquals(listOf("innova_2007_gcc"), family.variantIds)
        assertEquals(listOf("innova_2007_gcc"), family.variants.map { variant -> variant.variantId })
    }

    @Test
    fun `the pilot variant offers exactly the seven generic reads`() {
        val variant = catalogue.getVariant("toyota", "innova_2007_gcc")!!

        assertEquals(
            listOf(
                "generic.read-supported-pids",
                "generic.read-engine-speed",
                "generic.read-vehicle-speed",
                "generic.read-stored-dtcs",
                "generic.read-pending-dtcs",
                "generic.read-permanent-dtcs",
                "generic.read-vin",
            ),
            variant.supportedOperations.map { operation -> operation.id },
        )
        assertTrue(
            variant.supportedOperations.all { operation ->
                operation.riskClass == RiskClass.READ_ONLY &&
                    operation.scope == OperationScope.GENERIC &&
                    operation.verificationStatus == ProfileStatus.UNVERIFIED &&
                    operation.serviceBytes in genericFrames
            },
        )
    }

    @Test
    fun `no shipped operation claims a manufacturer command`() {
        val offenders = catalogue.listBrands().flatMap { brand ->
            brand.variants.flatMap { variant ->
                variant.supportedOperations
                    .filter { operation -> operation.serviceBytes != UNKNOWN_VALUE && operation.serviceBytes !in genericFrames }
                    .map { operation -> "${brand.brandId}:${operation.id}=${operation.serviceBytes}" }
            }
        }

        assertEquals("manufacturer frames are forbidden in shipped packs", emptyList<String>(), offenders)
    }

    @Test
    fun `no shipped pack contains null, an empty string, or a script`() {
        packs.forEach { (fileName, text) ->
            assertTrue("$fileName contains null", !text.contains(": null"))
            assertTrue("$fileName contains an empty string", !text.contains(": \"\""))
            assertTrue("$fileName contains an empty array item", !text.contains("\"\", "))
            assertTrue("$fileName contains a script", !text.contains("<script"))
        }
    }

    @Test
    fun `every shipped operation is refused and nothing is transmitted`() = runTest {
        val port = RecordingPort()
        val executor = PackOperationExecutor(port)
        var refusals = 0

        catalogue.listBrands().forEach { brand ->
            brand.variants.forEach { variant ->
                variant.supportedOperations.forEach { operation ->
                    assertThrowsSuspend<SafetyViolationException> {
                        executor.execute(catalogue, brand.brandId, variant.variantId, operation.id)
                    }
                    refusals++
                }
            }
        }

        assertEquals("seven generic reads on the pilot, nothing else", 7, refusals)
        assertEquals("no command may reach the port", emptyList<String>(), port.commands)
    }

    @Test
    fun `an unlisted brand and variant are absent, not defaulted`() {
        assertNull(catalogue.getVariant("toyota", "hilux_2007_gcc"))
        assertNull(catalogue.getBrand("tesla"))
        assertEquals(Tier.T4_UNVERIFIED, catalogue.getTier("tesla"))
    }

    @Test
    fun `the UNKNOWN sentinel matches the v1 profile token`() {
        assertEquals(EcuAddress.UNKNOWN_VALUE, UNKNOWN_VALUE)
    }

    @Test
    fun `stub brands claim nothing at all`() {
        catalogue.listBrands()
            .filter { brand -> brand.brandId != "toyota" }
            .forEach { brand ->
                assertEquals(emptyList<Any>(), brand.families)
                assertTrue(brand.brandLevelProtocolDefaults.all { protocol -> protocol.isUnknown() })
                assertTrue(brand.securityPolicyOverrides.all { override -> override.isUnknown() })
                assertEquals(UNKNOWN_VALUE, brand.commonDtcDictionary)
            }
    }
}
