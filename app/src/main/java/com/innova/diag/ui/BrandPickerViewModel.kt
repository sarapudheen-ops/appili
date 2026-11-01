package com.innova.diag.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.innova.diag.domain.profile.pack.BrandPackRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Holds the brand picker's selection.
 *
 * ### Why a provider instead of a registry
 * The catalogue is loaded from `assets/` through the schema, which needs an Android `Context` and is
 * therefore the host's job (`BrandCatalogue`). Taking a `() -> BrandPackRegistry?` keeps this class
 * free of Android APIs and lets a test hand it a registry built from JSON strings — the same registry
 * the app uses, not a stub. A `null` answer is the fail-closed case: the picker says "no brand packs"
 * and the generic path is still offered.
 *
 * ### What this class does not do
 * It transmits nothing and decides nothing: every operation still goes through
 * `OperationGate`, and every shipped pack is T4, so selecting a brand changes what is *shown* and not
 * what is *allowed*.
 */
class BrandPickerViewModel(
    private val registryProvider: () -> BrandPackRegistry? = { null },
) : ViewModel() {

    private val mutableState = MutableStateFlow(BrandPickerModel.summarize(registryProvider()))

    /** The picker's current view of the catalogue and the selection. */
    val state: StateFlow<BrandPickerState> = mutableState.asStateFlow()

    /** Re-reads the registry. Called by the host after a reload; a no-op when nothing changed. */
    fun refresh() {
        mutableState.value = BrandPickerModel.summarize(registryProvider())
    }

    /** One entry point for the screen's four actions. */
    fun onEvent(event: BrandPickerEvent) {
        mutableState.update { state ->
            when (event) {
                BrandPickerEvent.AnyVehicle -> BrandPickerModel.anyVehicle(state)
                is BrandPickerEvent.SelectBrand -> BrandPickerModel.selectBrand(state, event.brandId)
                is BrandPickerEvent.SelectVariant -> BrandPickerModel.selectVariant(state, event.variantId)
                BrandPickerEvent.BackToCatalogue -> BrandPickerModel.backToCatalogue(state)
            }
        }
    }

    /** Opens a brand by id. Equivalent to [onEvent] with [BrandPickerEvent.SelectBrand]. */
    fun selectBrand(brandId: String) = onEvent(BrandPickerEvent.SelectBrand(brandId))

    /** Opens a variant by id. */
    fun selectVariant(variantId: String) = onEvent(BrandPickerEvent.SelectVariant(variantId))

    /** The "Any Vehicle — Generic OBD-II Only" bypass. */
    fun chooseAnyVehicle() = onEvent(BrandPickerEvent.AnyVehicle)

    /** Back from the generic-only route to the catalogue. */
    fun backToCatalogue() = onEvent(BrandPickerEvent.BackToCatalogue)

    companion object {

        /** A factory for the host, which is the only place that knows how to load the packs. */
        fun factory(provider: () -> BrandPackRegistry?): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    BrandPickerViewModel(provider) as T

                @Suppress("UNCHECKED_CAST", "DEPRECATION")
                override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
                    BrandPickerViewModel(provider) as T
            }
    }
}
