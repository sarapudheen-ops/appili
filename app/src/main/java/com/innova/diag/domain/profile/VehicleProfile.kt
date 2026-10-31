package com.innova.diag.domain.profile

/**
 * How much real-world evidence stands behind a profile, an ECU definition, or the profile as a whole.
 *
 * The ordering matters and is not alphabetical: [VERIFIED] is the only value that permits a physical
 * transmission, and a profile is only as verified as its weakest ECU (see [VehicleProfile.status]).
 */
enum class ProfileStatus {

    /** Confirmed against a physical bus dump from an authorized tool. The only executable value. */
    VERIFIED,

    /** Recorded from documentation or expectation, never confirmed on a bus. Denied. */
    UNVERIFIED,

    /** Only meaningful against the internal simulator. Denied on a physical vehicle. */
    SIMULATOR_ONLY,
}

/**
 * An ECU address on the vehicle bus, or an explicit statement that nobody has captured one.
 *
 * There is no "default" and no `0x7E0` fallback: [Unknown] is a first-class value so a profile can say
 * "not captured yet" without the model ever inventing a number (AGENTS.md Rule 1, Rule 5).
 */
sealed interface EcuAddress {

    /** A captured address, canonical uppercase hex: 3 digits (11-bit, e.g. `7E0`) or 8 (29-bit). */
    data class Known(val hex: String) : EcuAddress {
        init {
            require(hex.length == 3 || hex.length == 8) {
                "ECU address must be 3 or 8 hex digits, got \"$hex\""
            }
            require(hex.all { it in HEX_ALPHABET }) {
                "ECU address must be uppercase hex, got \"$hex\""
            }
        }
    }

    /** No address has been captured. Any operation that needs one is denied. */
    data object Unknown : EcuAddress

    /** The value written in JSON and shown in diagnostics: `UNKNOWN`. */
    val text: String
        get() = when (this) {
            is Known -> hex
            Unknown -> UNKNOWN_VALUE
        }

    companion object {

        /** The literal a profile uses for "not captured"; also what the UI displays. */
        const val UNKNOWN_VALUE: String = "UNKNOWN"

        /** Reads `"7E0"` or `"UNKNOWN"`; anything else is `null` (the parser then rejects the file). */
        fun parse(raw: String): EcuAddress? {
            val text = raw.trim()
            if (text.equals(UNKNOWN_VALUE, ignoreCase = true)) return Unknown
            val hex = text.uppercase()
            if (hex.length != 3 && hex.length != 8) return null
            if (hex.any { it !in HEX_ALPHABET }) return null
            return Known(hex)
        }
    }
}

/**
 * The adapter commands needed to reach an ECU, or an explicit statement that they were never captured.
 *
 * Unknown is not the same as empty: an empty list would mean "send nothing", which is a claim, while
 * [Unknown] means "nobody has measured this". `ATSH` is rejected outright — this repository never sets
 * a header (AGENTS.md Rule 1).
 */
sealed interface InitSequence {

    /** Captured commands, in order. Never empty: that would be a claim, not a capture. */
    data class Known(val commands: List<String>) : InitSequence {
        init {
            require(commands.isNotEmpty()) { "a known init sequence must contain at least one command" }
            for (command in commands) {
                require(command.isNotBlank()) { "init command must not be blank" }
                require(command.none { it == '\r' || it == '\n' }) {
                    "init command must not contain a line terminator: \"$command\""
                }
                require(!command.trim().uppercase().startsWith("ATSH")) {
                    "init command must not set a header (ATSH is forbidden): \"$command\""
                }
            }
        }
    }

    /** No init sequence has been captured. Any operation that needs one is denied. */
    data object Unknown : InitSequence

    /** True only for a captured, non-empty sequence. */
    val isKnown: Boolean get() = this is Known
}

/** The model years a profile claims to cover, inclusive on both ends. */
data class YearRange(val from: Int, val to: Int) {

    init {
        require(from in 1886..2100) { "yearRange.from out of range: $from" }
        require(to in 1886..2100) { "yearRange.to out of range: $to" }
        require(from <= to) { "yearRange.from ($from) must not be after yearRange.to ($to)" }
    }

    /** True when [year] falls inside the range. */
    fun contains(year: Int): Boolean = year in from..to
}

/**
 * One electronic control unit as a profile describes it.
 *
 * Every field is either captured or explicitly [EcuAddress.Unknown]/[InitSequence.Unknown]; there is
 * no way to express "probably 7E0". [status] governs whether anything here may be used: a
 * [ProfileStatus.VERIFIED] definition must have real addresses and a real init sequence, because a
 * verified claim with unknown contents is a contradiction the constructor refuses.
 */
data class EcuDefinition(
    val name: String,
    val network: String,
    val txId: EcuAddress,
    val rxId: EcuAddress,
    val initCommands: InitSequence,
    val supportedServices: List<Int>,
    val status: ProfileStatus,
) {
    init {
        require(name.isNotBlank()) { "ECU name must not be blank" }
        require(network.isNotBlank()) { "ECU network must not be blank; use UNKNOWN when not captured" }
        require(supportedServices.all { it in 0..0xFF }) {
            "supportedServices must be byte values (0..255): $supportedServices"
        }
        require(supportedServices.distinct().size == supportedServices.size) {
            "supportedServices must not repeat a service: $supportedServices"
        }
        if (status == ProfileStatus.VERIFIED) {
            require(txId is EcuAddress.Known && rxId is EcuAddress.Known) {
                "a VERIFIED ECU definition must have captured txId and rxId for \"$name\""
            }
            require(network != EcuAddress.UNKNOWN_VALUE) {
                "a VERIFIED ECU definition must name its network for \"$name\""
            }
            require(initCommands.isKnown) {
                "a VERIFIED ECU definition must have a captured init sequence for \"$name\""
            }
        }
    }

    /** True when the network was captured rather than left as `UNKNOWN`. */
    val networkKnown: Boolean get() = network != EcuAddress.UNKNOWN_VALUE

    /** True when this definition may be used for a physical transmission. */
    val isVerified: Boolean get() = status == ProfileStatus.VERIFIED

    /** True when [service] is declared as supported by this ECU. */
    fun supports(service: Int): Boolean = service in supportedServices
}

/**
 * A whole vehicle model, market and year span, with the ECUs and operations a profile knows about.
 *
 * Profiles are **pure data**. They are parsed from JSON into these values and never interpreted as
 * code: no expressions, no scripts, no generated byte buffers (AGENTS.md Rule 5). Everything the
 * platform can do with them is decided here and by [ProfileOperationExecutor], both fail-closed.
 *
 * @param id stable identifier, e.g. `toyota-innova-2007-gcc`.
 * @param yearRange the model years this profile claims; a VIN/year outside it does not match.
 * @param ecuDefinitions at least one; names must be unique.
 * @param operations the named, read-only operations a caller may ask for; ids must be unique.
 */
data class VehicleProfile(
    val id: String,
    val make: String,
    val model: String,
    val yearRange: YearRange,
    val market: String,
    val ecuDefinitions: List<EcuDefinition>,
    val operations: List<DiagnosticOperation> = emptyList(),
) {
    init {
        require(id.isNotBlank()) { "profile id must not be blank" }
        require(make.isNotBlank()) { "profile make must not be blank" }
        require(model.isNotBlank()) { "profile model must not be blank" }
        require(market.isNotBlank()) { "profile market must not be blank" }
        require(ecuDefinitions.isNotEmpty()) { "a profile must define at least one ECU" }
        require(ecuDefinitions.distinctBy { it.name }.size == ecuDefinitions.size) {
            "ECU names must be unique: ${ecuDefinitions.map { it.name }}"
        }
        require(operations.distinctBy { it.id }.size == operations.size) {
            "operation ids must be unique: ${operations.map { it.id }}"
        }
    }

    /**
     * The profile's own status: **the weakest claim among its ECUs**.
     *
     * Any unverified ECU makes the profile unverified, because a read aimed at *that* ECU is exactly
     * as unsafe as the ECU definition itself, and nothing in this layer may predict which ECU a future
     * operation targets. A profile is [ProfileStatus.VERIFIED] only when every ECU is.
     */
    val status: ProfileStatus
        get() = when {
            ecuDefinitions.isEmpty() -> ProfileStatus.UNVERIFIED
            ecuDefinitions.any { it.status == ProfileStatus.UNVERIFIED } -> ProfileStatus.UNVERIFIED
            ecuDefinitions.any { it.status == ProfileStatus.SIMULATOR_ONLY } -> ProfileStatus.SIMULATOR_ONLY
            else -> ProfileStatus.VERIFIED
        }

    /** True only when every ECU definition is verified. */
    val isVerified: Boolean get() = status == ProfileStatus.VERIFIED

    /** The ECU definition named [name], or `null` when this profile does not describe it. */
    fun ecu(name: String): EcuDefinition? = ecuDefinitions.firstOrNull { it.name == name }

    /** The operation with [operationId], or `null` when this profile does not declare it. */
    fun operation(operationId: String): DiagnosticOperation? =
        operations.firstOrNull { it.id == operationId }

    /** True when [year] is inside [yearRange]. */
    fun covers(year: Int): Boolean = yearRange.contains(year)

    /** "2007 Toyota Innova (GCC)" — for headers and error messages. */
    val displayName: String
        get() = "${yearRange.from}" + (if (yearRange.to != yearRange.from) "-${yearRange.to}" else "") +
            " $make $model ($market)"
}
