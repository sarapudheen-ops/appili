package com.innova.diag.domain.profile.pack

import java.io.File

/**
 * Pack documents built as Kotlin data, serialised to JSON for the registry to read.
 *
 * Every value here is deliberately **synthetic** (`test_brand`, `ENGINE-TEST`, `PN-TEST`): a test fixture
 * must not look like captured vehicle data, and no manufacturer-specific service bytes appear anywhere in
 * this file. The only real frames are the SAE J1979 generic ones already implemented in phase 5.
 */
internal object TestPacks {

    const val SCHEMA_RELATIVE_PATH = "src/main/assets/profiles/schema/vehicle_pack_schema_v2.json"
    const val BRANDS_RELATIVE_DIRECTORY = "src/main/assets/profiles/brands"

    /** The shipped schema, read from the module directory. */
    fun shippedSchema(): String = assetFile(SCHEMA_RELATIVE_PATH).readText()

    /** The shipped packs, file name → raw JSON. */
    fun shippedPacks(): Map<String, String> {
        val directory = assetFile(BRANDS_RELATIVE_DIRECTORY)
        val files = directory.listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: emptyList()
        return files.sortedBy { it.name }.associate { file -> file.name to file.readText() }
    }

    /** One operation; every parameter defaults to "not captured". */
    fun operation(
        id: String = "generic.read-engine-speed",
        description: String = "Mode 01 PID 0C engine speed",
        sourceReference: String = "SAE J1979",
        verificationStatus: String = "UNVERIFIED",
        riskClass: String = "READ_ONLY",
        scope: String = "GENERIC",
        serviceBytes: String = "UNKNOWN",
    ): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "description" to description,
        "sourceReference" to sourceReference,
        "verificationStatus" to verificationStatus,
        "riskClass" to riskClass,
        "scope" to scope,
        "serviceBytes" to serviceBytes,
    )

    /** One ECU definition; [verificationStatus] `VERIFIED` needs the captured fields to be set. */
    fun ecu(
        name: String = "Engine ECU",
        partNumber: String = "UNKNOWN",
        calibrationIds: List<String> = listOf("UNKNOWN"),
        network: String = "UNKNOWN",
        addressing: String = "UNKNOWN",
        verificationStatus: String = "UNVERIFIED",
    ): Map<String, Any?> = linkedMapOf(
        "name" to name,
        "partNumber" to partNumber,
        "calibrationIds" to calibrationIds,
        "network" to network,
        "addressing" to addressing,
        "verificationStatus" to verificationStatus,
    )

    /** A synthetic, fully captured ECU — for tests that need a VERIFIED variant. */
    fun capturedEcu(name: String = "Engine ECU"): Map<String, Any?> = ecu(
        name = name,
        partNumber = "PN-TEST",
        calibrationIds = listOf("CAL-TEST"),
        network = "NETWORK-TEST",
        addressing = "ADDRESSING-TEST",
        verificationStatus = "VERIFIED",
    )

    fun variant(
        variantId: String = "test_variant",
        market: String = "TEST-MARKET",
        yearFrom: Int = 2007,
        yearTo: Int = 2007,
        engineCode: String = "UNKNOWN",
        transmission: String = "UNKNOWN",
        verificationStatus: String = "UNVERIFIED",
        ecuDefinitions: List<Map<String, Any?>> = listOf(ecu()),
        supportedOperations: List<Map<String, Any?>> = listOf(operation()),
    ): Map<String, Any?> = linkedMapOf(
        "variantId" to variantId,
        "market" to market,
        "yearRange" to linkedMapOf("from" to yearFrom, "to" to yearTo),
        "engineCode" to engineCode,
        "transmission" to transmission,
        "verificationStatus" to verificationStatus,
        "ecuDefinitions" to ecuDefinitions,
        "supportedOperations" to supportedOperations,
    )

    /** A variant that satisfies every "verified ⇒ captured" invariant. */
    fun verifiedVariant(
        variantId: String = "verified_variant",
        supportedOperations: List<Map<String, Any?>> = listOf(verifiedGenericOperation()),
    ): Map<String, Any?> = variant(
        variantId = variantId,
        engineCode = "ENGINE-TEST",
        transmission = "TRANSMISSION-TEST",
        verificationStatus = "VERIFIED",
        ecuDefinitions = listOf(capturedEcu()),
        supportedOperations = supportedOperations,
    )

    /** A verified generic operation: captured bytes, cited source, classified scope. */
    fun verifiedGenericOperation(
        id: String = "generic.read-engine-speed",
        scope: String = "GENERIC",
        serviceBytes: String = "010C",
        riskClass: String = "READ_ONLY",
    ): Map<String, Any?> = operation(
        id = id,
        verificationStatus = "VERIFIED",
        riskClass = riskClass,
        scope = scope,
        serviceBytes = serviceBytes,
    )

    /**
     * A verified **enhanced** operation.
     *
     * The frame `7A10` is fictional on purpose: no such service exists, so a test can exercise tier
     * promotion without this repository ever containing a real manufacturer request. The executor still
     * refuses to transmit anything from an UNVERIFIED variant, and the shipped packs contain no enhanced
     * operation at all.
     */
    fun verifiedEnhancedOperation(id: String = "enhanced.read"): Map<String, Any?> =
        verifiedGenericOperation(id = id, scope = "ENHANCED", serviceBytes = "7A10")

    /**
     * A verified **active test**, which exists only to prove that tier promotion reads the risk class.
     *
     * `7A11` is fictional, exactly as `7A10` is: this fixture describes no real routine on any vehicle,
     * it is never part of the shipped assets, and [PackOperationExecutor] refuses to transmit it because
     * `ACTIVE_TEST` is forbidden outright. Without it `T1_FULL_ENHANCED` would be unreachable in a test.
     */
    fun verifiedActiveTestOperation(id: String = "enhanced.active-test"): Map<String, Any?> =
        verifiedGenericOperation(id = id, scope = "ENHANCED", serviceBytes = "7A11", riskClass = "ACTIVE_TEST")

    fun family(
        familyId: String = "test_family",
        familyName: String = "Synthetic Test Family",
        variantIds: List<String>? = null,
        variants: List<Map<String, Any?>> = listOf(variant()),
    ): Map<String, Any?> = linkedMapOf(
        "familyId" to familyId,
        "familyName" to familyName,
        "variantIds" to (variantIds ?: variants.map { it["variantId"] as String }),
        "variants" to variants,
    )

    fun brand(
        brandId: String = "test_brand",
        brandName: String = "Test Brand",
        commonDtcDictionary: String = "UNKNOWN",
        brandLevelProtocolDefaults: List<String> = listOf("UNKNOWN"),
        securityPolicyOverrides: List<String> = listOf("UNKNOWN"),
        families: List<Map<String, Any?>> = listOf(family()),
    ): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to 2,
        "brandId" to brandId,
        "brandName" to brandName,
        "commonDtcDictionary" to commonDtcDictionary,
        "brandLevelProtocolDefaults" to brandLevelProtocolDefaults,
        "securityPolicyOverrides" to securityPolicyOverrides,
        "families" to families,
    )

    /** Serialises [pack] to JSON text. Supports exactly the value shapes these fixtures use. */
    fun json(pack: Map<String, Any?>): String = encode(pack)

    /** Packs as the registry wants them: file name → JSON. */
    fun documents(vararg packs: Pair<String, Map<String, Any?>>): Map<String, String> =
        packs.associate { (fileName, pack) -> fileName to json(pack) }

    private fun encode(value: Any?): String = when (value) {
        null -> "null"
        is String -> "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        is Int -> value.toString()
        is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { entry ->
            "\"${entry.key}\":${encode(entry.value)}"
        }
        is List<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { item -> encode(item) }
        else -> throw IllegalArgumentException("the test encoder does not handle ${value::class.java}")
    }

    /** Finds a file relative to the module directory, working from either plausible root. */
    fun assetFile(relative: String): File {
        val candidates = listOf(File(relative), File("app/$relative"))
        return candidates.firstOrNull { candidate -> candidate.exists() }
            ?: throw AssertionError(
                "not found: $relative (tried ${candidates.joinToString { it.absolutePath }}; " +
                    "cwd is ${File(".").absolutePath})",
            )
    }
}
