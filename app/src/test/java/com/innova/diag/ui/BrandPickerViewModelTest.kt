package com.innova.diag.ui

import com.innova.diag.domain.profile.pack.BrandPackRegistry
import com.innova.diag.domain.profile.pack.TestPacks
import com.innova.diag.domain.profile.pack.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picker's behaviour, without a screen.
 *
 * Three things are load-bearing and all three are here: the catalogue is ordered by tier (T1 first,
 * so a full-enhanced brand is never buried under twelve unverified ones), selecting a brand is
 * navigation and not permission, and "Any Vehicle" bypasses the catalogue entirely.
 *
 * The view model is constructed directly — `ViewModel()` has no Android dependency — and the registry
 * is the real one, built from the same JSON the app loads.
 */
class BrandPickerViewModelTest {

    /**
     * Four synthetic brands, one per tier, in an order that is not the expected one.
     *
     * File names sort as a→d to (T4, T2, T1, T3), so a picker that echoed the registry's order would
     * fail the first test. Names are chosen so alphabetical order alone would give a different answer
     * too: Aardvark, Kiwi, Mango, Zebra.
     */
    private fun fourTiers(): BrandPackRegistry = BrandPackRegistry.fromDocuments(
        TestPacks.documents(
            "a.json" to brand("t4_brand", "Aardvark", TestPacks.variant(variantId = "t4_variant")),
            "b.json" to brand(
                "t2_brand",
                "Mango",
                TestPacks.verifiedVariant(
                    variantId = "t2_variant",
                    supportedOperations = listOf(TestPacks.verifiedEnhancedOperation()),
                ),
            ),
            "c.json" to brand(
                "t1_brand",
                "Kiwi",
                TestPacks.verifiedVariant(
                    variantId = "t1_variant",
                    supportedOperations = listOf(TestPacks.verifiedActiveTestOperation()),
                ),
            ),
            "d.json" to brand(
                "t3_brand",
                "Zebra",
                TestPacks.verifiedVariant(
                    variantId = "t3_variant",
                    supportedOperations = listOf(TestPacks.verifiedGenericOperation()),
                ),
            ),
        ),
        TestPacks.shippedSchema(),
    )

    private fun brand(brandId: String, brandName: String, variant: Map<String, Any?>): Map<String, Any?> =
        TestPacks.brand(
            brandId = brandId,
            brandName = brandName,
            families = listOf(TestPacks.family(variants = listOf(variant))),
        )

    private fun viewModel(registry: BrandPackRegistry?): BrandPickerViewModel =
        BrandPickerViewModel { registry }

    private fun shipped(): BrandPackRegistry =
        BrandPackRegistry.fromDocuments(TestPacks.shippedPacks(), TestPacks.shippedSchema())

    // --- Sorting -------------------------------------------------------------

    @Test
    fun `brands are sorted by tier from T1 to T4 regardless of registry order`() {
        val state = viewModel(fourTiers()).state.value

        assertEquals(
            listOf(Tier.T1_FULL_ENHANCED, Tier.T2_PARTIAL_ENHANCED, Tier.T3_GENERIC_ONLY, Tier.T4_UNVERIFIED),
            state.brands.map { brand -> brand.tier },
        )
        assertEquals(listOf("Kiwi", "Mango", "Zebra", "Aardvark"), state.brands.map { it.brandName })
    }

    @Test
    fun `tier badges use the wording of the vehicle matrix`() {
        val state = viewModel(fourTiers()).state.value

        assertEquals(
            listOf("Full Enhanced", "Partial Enhanced", "Generic OBD-II Only", "Unverified (Generic OBD-II Only)"),
            state.brands.map { brand -> brand.tierLabel },
        )
    }

    @Test
    fun `every shipped brand is present and unverified`() {
        val state = viewModel(shipped()).state.value

        assertEquals(12, state.brands.size)
        assertTrue(state.brands.all { brand -> brand.tier == Tier.T4_UNVERIFIED })
        assertEquals(
            "Unverified (Generic OBD-II Only)",
            state.brands.first { brand -> brand.brandId == "toyota" }.tierLabel,
        )
    }

    // --- Selection -----------------------------------------------------------

    @Test
    fun `selecting a brand opens its families and forgets the previous variant`() {
        val vm = viewModel(fourTiers())
        vm.selectBrand("t4_brand")
        vm.selectVariant("t4_variant")
        assertEquals("t4_variant", vm.state.value.selectedVariantId)

        vm.selectBrand("t2_brand")

        val state = vm.state.value
        assertEquals("t2_brand", state.selectedBrandId)
        assertNull(state.selectedVariantId)
        assertEquals(listOf("test_family"), state.selectedBrand?.families?.map { family -> family.familyId })
        assertEquals(listOf("t2_variant"), state.selectedBrand?.variants?.map { it.variantId })
    }

    @Test
    fun `an unknown brand or variant id is ignored instead of invented`() {
        val vm = viewModel(fourTiers())

        vm.selectBrand("not_a_brand")
        assertNull(vm.state.value.selectedBrandId)

        vm.selectBrand("t4_brand")
        vm.selectVariant("not_a_variant")

        assertEquals("t4_brand", vm.state.value.selectedBrandId)
        assertNull(vm.state.value.selectedVariantId)
    }

    @Test
    fun `the 2007 Innova GCC is reachable and its OEM capabilities are labels, not controls`() {
        val vm = viewModel(shipped())

        vm.selectBrand("toyota")
        val brand = vm.state.value.selectedBrand
        assertTrue(brand?.brandName?.contains("Toyota") == true)

        vm.selectVariant("innova_2007_gcc")
        val innova = requireNotNull(vm.state.value.selectedVariant) { "the Innova must be selectable" }

        assertEquals("innova_2007_gcc", innova.variantId)
        assertEquals("2007", innova.yearLabel)
        assertEquals("GCC", innova.market)
        assertEquals("UNVERIFIED", innova.statusLabel)
        assertFalse(innova.verified)

        assertEquals(
            listOf("OEM Enhanced", "Active Test", "Coding"),
            innova.capabilities.map { capability -> capability.label },
        )
        innova.capabilities.forEach { capability ->
            assertFalse("${capability.label} must not be offered", capability.available)
            assertEquals(BrandPickerModel.UNAVAILABLE_UNVERIFIED, capability.status)
        }
    }

    @Test
    fun `a brand with no declared vehicles offers no families`() {
        val vm = viewModel(shipped())
        val withoutVariants = vm.state.value.brands.filter { brand -> brand.variantCount == 0 }

        assertTrue("the shipped catalogue still has unpopulated brands", withoutVariants.isNotEmpty())
        withoutVariants.forEach { brand ->
            assertTrue(brand.families.isEmpty())
            assertEquals("0 of 0 variants verified", brand.verificationSummary)
        }
    }

    // --- The Any Vehicle bypass ---------------------------------------------

    @Test
    fun `any vehicle skips the catalogue and drops the selection`() {
        val vm = viewModel(fourTiers())
        vm.selectBrand("t1_brand")
        vm.selectVariant("t1_variant")

        vm.chooseAnyVehicle()

        val state = vm.state.value
        assertTrue(state.genericOnly)
        assertEquals(BrandPickerRoute.GENERIC_ONLY, state.route)
        assertNull(state.selectedBrandId)
        assertNull(state.selectedVariantId)
        // The catalogue is still there: the bypass is a route, not a deletion.
        assertEquals(4, state.brands.size)
    }

    @Test
    fun `returning from any vehicle restores the catalogue`() {
        val vm = viewModel(fourTiers())
        vm.chooseAnyVehicle()

        vm.backToCatalogue()

        val state = vm.state.value
        assertFalse(state.genericOnly)
        assertEquals(BrandPickerRoute.CATALOGUE, state.route)
        assertEquals(4, state.brands.size)
    }

    @Test
    fun `the any vehicle label states what it is and what it will not do`() {
        assertEquals("Any Vehicle — Generic OBD-II Only", BrandPickerModel.ANY_VEHICLE_LABEL)
        assertTrue(BrandPickerModel.ANY_VEHICLE_DETAIL.contains("emissions/OBD-II only"))
        assertFalse(BrandPickerModel.ANY_VEHICLE_LABEL.contains("Full Vehicle Scan"))
    }

    @Test
    fun `any vehicle works with no catalogue at all`() {
        val vm = viewModel(null)

        vm.chooseAnyVehicle()

        assertTrue(vm.state.value.genericOnly)
        assertTrue(vm.state.value.isEmpty)
    }

    // --- Fail-closed loading -------------------------------------------------

    @Test
    fun `a missing catalogue is stated rather than faked`() {
        val state = viewModel(null).state.value

        assertTrue(state.isEmpty)
        assertEquals(BrandPickerModel.NO_CATALOGUE_MESSAGE, state.message)
        assertNull(state.selectedBrand)
    }

    @Test
    fun `a catalogue that loads later can be picked up by refresh`() {
        var registry: BrandPackRegistry? = null
        val vm = BrandPickerViewModel { registry }
        assertTrue(vm.state.value.isEmpty)

        registry = fourTiers()
        vm.refresh()

        assertEquals(4, vm.state.value.brands.size)
        assertNull(vm.state.value.message)
    }
}
