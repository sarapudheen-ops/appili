package com.innova.diag.domain.profile

/** The hex alphabet, uppercase only, so a parsed address or service cannot be half-normalized. */
internal const val HEX_ALPHABET = "0123456789ABCDEF"

/**
 * The service ids a profile is allowed to declare *at all* while this repository is read-only.
 *
 * This is the SAE J1979 generic **read** set (modes 01, 02, 03, 05, 06, 07, 08, 09, 0A). Everything
 * else — 04 (clear diagnostic information), 2E/2F (write by identifier), 31 (routine control), 21/22
 * (manufacturer-specific reads), 27, 28, 3E — is refused when the [DiagnosticOperation] is
 * constructed, so a profile cannot *describe* a write, let alone run one.
 *
 * The same doctrine as `docs/ASSUMPTIONS.md` A30: absent, not disabled.
 */
internal val READ_ONLY_SERVICES: Set<Int> = setOf(0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A)

/** How dangerous an operation is if it ever ran. */
enum class RiskClass {

    /** Reads state and changes nothing. The only class a profile may currently carry. */
    READ_ONLY,

    /** Drives an actuator or a routine. Never transmitted to a physical vehicle (AGENTS.md Rule 2). */
    ACTIVE_TEST,

    /** Changes vehicle configuration. Never transmitted to a physical vehicle (AGENTS.md Rule 2). */
    CONFIGURATION,
}

/**
 * A validated, canonical hex string for an operation's bytes (`"010C"` for mode 01 PID 0C).
 *
 * Construction goes through [parse], which strips whitespace, uppercases and rejects anything that is
 * not an even-length hex string — so `"01 0C"`, `"010c"` and `"010C"` all become `010C`, and
 * `"eval(...)"` cannot become a command. There is deliberately no public constructor.
 */
@JvmInline
value class ServiceBytes private constructor(val hex: String) {

    /** The first byte: the service id (mode) of the operation. */
    val service: Int get() = hex.substring(0, 2).toInt(16)

    /** True when this is exactly one service byte and nothing else (e.g. `"03"`). */
    val isSingleByte: Boolean get() = hex.length == 2

    override fun toString(): String = hex

    companion object {

        /** Longest operation this model accepts: 4 bytes, the width of a J1979 generic request. */
        private const val MAX_HEX_LENGTH = 8

        /** Returns the canonical form of [raw], or `null` when it is not an even-length hex string. */
        fun parse(raw: String): ServiceBytes? {
            val cleaned = raw.filterNot { it.isWhitespace() }.uppercase()
            if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
            if (cleaned.length > MAX_HEX_LENGTH) return null
            if (cleaned.any { it !in HEX_ALPHABET }) return null
            return ServiceBytes(cleaned)
        }

        /** Like [parse] but throws; used by callers that hold already-trusted data. */
        fun require(raw: String): ServiceBytes = parse(raw)
            ?: throw IllegalArgumentException("not an even-length hex service string: \"$raw\"")

        /** Canonical hex for [service] alone, e.g. `03` for mode 03. */
        fun of(service: Int): ServiceBytes {
            require(service in 0..0xFF) { "service id out of range: $service" }
            val hex = HEX_ALPHABET[service / 16].toString() + HEX_ALPHABET[service % 16]
            return ServiceBytes(hex)
        }
    }
}

/**
 * One thing a profile says may be asked of an ECU.
 *
 * Deliberately tiny: an [id] to name it, a [riskClass] to classify it, and the exact [serviceBytes]
 * to send. There is no field for a header, an address, a timing parameter or a payload the caller
 * supplies — those come from the ECU definition or they do not come at all (AGENTS.md Rule 1).
 *
 * Construction fails for a service that is not read-only ([READ_ONLY_SERVICES]), so an
 * `ACTIVE_TEST` or `CONFIGURATION` entry can only ever describe a read; the executor refuses those
 * classes outright, and the parser refuses to load a profile that contains one.
 */
data class DiagnosticOperation(
    val id: String,
    val riskClass: RiskClass,
    val serviceBytes: ServiceBytes,
) {
    init {
        require(id.isNotBlank()) { "operation id must not be blank" }
        require(serviceBytes.service in READ_ONLY_SERVICES) {
            "operation \"$id\" declares service 0x${serviceBytes.hex.take(2)}, which is not a " +
                "read-only J1979 service; writes and manufacturer-specific modes are not " +
                "representable in a profile"
        }
    }

    /** The service id of [serviceBytes], e.g. `0x01` for mode 01. */
    val service: Int get() = serviceBytes.service
}
