package com.innova.diag.domain.gate

import com.innova.diag.core.transport.PhysicalTransport
import com.innova.diag.core.transport.SimulatedTransport
import com.innova.diag.core.transport.TransportState
import com.innova.diag.domain.profile.pack.BrandPackRegistry
import com.innova.diag.domain.profile.pack.TestPacks
import com.innova.diag.domain.profile.pack.VehicleVariantPack
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Transports with a marker and no hardware.
 *
 * The gate reads exactly two things off a transport — its class marker and its connection state — so
 * that is all these fakes implement. Nothing here can transmit, which means a test that accidentally
 * lets a frame through fails on the assertion, not on a socket.
 */
internal class PhysicalFake(connected: Boolean = true) : PhysicalTransport {

    private val currentState = MutableStateFlow<TransportState>(
        if (connected) TransportState.CONNECTED else TransportState.DISCONNECTED,
    )

    override fun state(): StateFlow<TransportState> = currentState.asStateFlow()

    override fun incomingBytes(): Flow<ByteArray> = emptyFlow()

    override suspend fun connect() {
        currentState.value = TransportState.CONNECTED
    }

    override suspend fun disconnect() {
        currentState.value = TransportState.DISCONNECTED
    }

    override suspend fun write(data: ByteArray) = Unit
}

/** The simulator marker without the mock ECU's protocol: the gate only needs the claim. */
internal class SimulatedFake(connected: Boolean = true) : SimulatedTransport {

    private val currentState = MutableStateFlow<TransportState>(
        if (connected) TransportState.CONNECTED else TransportState.DISCONNECTED,
    )

    override fun state(): StateFlow<TransportState> = currentState.asStateFlow()

    override fun incomingBytes(): Flow<ByteArray> = emptyFlow()

    override suspend fun connect() {
        currentState.value = TransportState.CONNECTED
    }

    override suspend fun disconnect() {
        currentState.value = TransportState.DISCONNECTED
    }

    override suspend fun write(data: ByteArray) = Unit
}

/** Shared fixtures: a gate with a frozen clock, and the two catalogues these tests need. */
internal object GateFixtures {

    /** A clock that never moves, so audit timestamps are predictable in assertions. */
    const val FIXED_TIMESTAMP: Long = 1_700_000_000_000L

    /** A new gate whose audit log starts empty. */
    fun gate(): OperationGate = OperationGate(AuditLog { FIXED_TIMESTAMP })

    /** The 12 packs that ship in `assets/profiles/brands`, through the real schema. */
    fun shippedCatalogue(): BrandPackRegistry =
        BrandPackRegistry.fromDocuments(TestPacks.shippedPacks(), TestPacks.shippedSchema())

    /** The pilot vehicle: the 2007 GCC Innova, still `UNVERIFIED`. */
    fun innova(): VehicleVariantPack = shippedCatalogue().getVariant("toyota", "innova_2007_gcc")
        ?: error("the shipped Toyota pack must define innova_2007_gcc")

    /**
     * A fully verified synthetic vehicle: captured ECU, captured enhanced frame.
     *
     * `7A10` is fictional (`TestPacks.verifiedEnhancedOperation`), so no real manufacturer request
     * enters this repository through a test.
     */
    fun verifiedVehicle(): VehicleVariantPack =
        catalogue(
            "verified_brand.json" to TestPacks.brand(
                brandId = "verified_brand",
                brandName = "Verified Brand",
                families = listOf(
                    TestPacks.family(
                        variants = listOf(
                            TestPacks.verifiedVariant(
                                variantId = "verified_variant",
                                supportedOperations = listOf(TestPacks.verifiedEnhancedOperation()),
                            ),
                        ),
                    ),
                ),
            ),
        ).getVariant("verified_brand", "verified_variant")
            ?: error("fixture must define verified_variant")

    /**
     * A verified synthetic vehicle that declares a verified **active test**.
     *
     * Exists to prove the gate compares the request's risk class with the pack's: `7A11` is the same
     * fictional frame `TestPacks.verifiedActiveTestOperation` uses, and it describes no real routine.
     */
    fun verifiedWriteVehicle(): VehicleVariantPack =
        catalogue(
            "verified_write_brand.json" to TestPacks.brand(
                brandId = "verified_write_brand",
                brandName = "Verified Write Brand",
                families = listOf(
                    TestPacks.family(
                        variants = listOf(
                            TestPacks.verifiedVariant(
                                variantId = "write_variant",
                                supportedOperations = listOf(TestPacks.verifiedActiveTestOperation()),
                            ),
                        ),
                    ),
                ),
            ),
        ).getVariant("verified_write_brand", "write_variant")
            ?: error("fixture must define write_variant")

    /** Loads [packs] through the shipped schema. */
    fun catalogue(vararg packs: Pair<String, Map<String, Any?>>): BrandPackRegistry =
        BrandPackRegistry.fromDocuments(TestPacks.documents(*packs), TestPacks.shippedSchema())
}
