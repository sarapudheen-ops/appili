package com.innova.diag.domain.obd

import java.util.Locale

/**
 * A generic (emissions/OBD-II) PID this app is allowed to display, with its presentation.
 *
 * [decimals] is presentation only: the decoder's value keeps its full precision, and only the string
 * a technician reads is rounded. Rounding *into* the model would lose the response's resolution.
 */
data class PidDefinition(
    val pid: Int,
    val label: String,
    val unit: String,
    val decimals: Int,
) {
    init {
        require(pid in 0x00..0xFF) { "PID must be a single byte, got $pid" }
        require(decimals in 0..3) { "decimals must be within 0..3, got $decimals" }
    }

    /**
     * Formats [value] for display, e.g. `1672.0` with `decimals = 1`.
     *
     * `Locale.ROOT` on purpose: a diagnostic readout is technical output and must not switch its
     * decimal separator with the device language (an exported log and the screen would disagree).
     */
    fun format(value: Double): String = String.format(Locale.ROOT, "%.${decimals}f", value)
}

/** The generic PIDs in scope. Nothing manufacturer-specific, nothing writable. */
object GenericPids {

    val COOLANT_TEMPERATURE: PidDefinition = PidDefinition(
        pid = Mode01Pids.COOLANT_TEMPERATURE,
        label = "Coolant temperature",
        unit = "°C",
        decimals = 0,
    )

    val ENGINE_RPM: PidDefinition = PidDefinition(
        pid = Mode01Pids.ENGINE_RPM,
        label = "Engine speed",
        unit = "RPM",
        decimals = 1,
    )

    val VEHICLE_SPEED: PidDefinition = PidDefinition(
        pid = Mode01Pids.VEHICLE_SPEED,
        label = "Vehicle speed",
        unit = "km/h",
        decimals = 0,
    )

    /** Every generic PID the app displays, in a stable order. */
    val ALL: List<PidDefinition> = listOf(COOLANT_TEMPERATURE, ENGINE_RPM, VEHICLE_SPEED)

    fun definitionFor(pid: Int): PidDefinition? = ALL.firstOrNull { definition -> definition.pid == pid }
}

/** One supported-PID bank, together with whether it could be read at all. */
data class SupportedBank(
    val basePid: Int,
    val result: DiagnosticResult<SupportedPids>,
)

/**
 * What the vehicle said about which generic PIDs it supports.
 *
 * [support] is deliberately three-valued: `true` (a bank listed it), `false` (a bank answered and did
 * not list it) and `null` (no bank answered, so we do not know). Collapsing `null` into `false` would
 * turn "we could not ask" into "your car does not have it", which is the kind of confident lie this
 * codebase exists to avoid.
 */
data class SupportedPidsReport(val banks: List<SupportedBank>) {

    /** Every PID listed by a bank that answered. */
    val knownPids: Set<Int> = banks.fold(emptySet<Int>()) { acc, bank ->
        val pids = bank.result.dataOrNull?.pids ?: emptySet()
        acc + pids
    }

    /** True when at least one bank answered, i.e. support is known at all. */
    val anyAnswered: Boolean get() = banks.any { bank -> bank.result.dataOrNull != null }

    /** True when every bank that was asked failed, so the support list is unknown, not empty. */
    val unanswered: Boolean get() = !anyAnswered

    fun support(pid: Int): Boolean? = when {
        !anyAnswered -> null
        else -> pid in knownPids
    }
}

/**
 * One generic PID as it came back: never a bare number, always the honest [DiagnosticResult].
 *
 * [requested] is `false` when the supported-PID bitmask already said the vehicle does not have it —
 * no request was sent, and the reading says `Unsupported` for that reason rather than because the
 * ECU refused it. The distinction matters when auditing the log: nothing was asked.
 */
data class PidReading(
    val definition: PidDefinition,
    val result: DiagnosticResult<Double>,
    val requested: Boolean,
)

/** Everything one generic scan gathered. Each field keeps its own outcome. */
data class GenericScanReport(
    val supported: SupportedPidsReport,
    val readings: List<PidReading>,
    val storedDtcs: DiagnosticResult<List<DiagnosticTroubleCode>>,
    val pendingDtcs: DiagnosticResult<List<DiagnosticTroubleCode>>,
    val vin: DiagnosticResult<Vin>,
)

/**
 * Runs the generic scan: supported-PID banks, the generic live PIDs, stored/pending DTCs and the VIN.
 *
 * ### Rules this class enforces
 * - **Generic only.** Every request goes through [ObdService], whose command surface is a fixed,
 *   read-only list. There is no path to a manufacturer-specific mode, and none to mode 04.
 * - **No invented requests.** A PID the bitmask excludes is not polled; it is reported as
 *   unsupported with [PidReading.requested] `false`.
 * - **No short-circuiting of bad news.** Each section is read and reported independently: a `NO DATA`
 *   coolant reading does not stop the DTC read, and a timeout is not retried into a success.
 */
class GenericScanService(private val service: ObdService) {

    suspend fun scan(): GenericScanReport {
        val supported = readSupported()
        return GenericScanReport(
            supported = supported,
            readings = readReadings(supported),
            storedDtcs = service.storedDtcs(),
            pendingDtcs = service.pendingDtcs(),
            vin = service.vin(),
        )
    }

    /** Reads the supported-PID banks only. Polling reuses this so the mask is not re-read per tick. */
    suspend fun readSupported(): SupportedPidsReport = SupportedPidsReport(readSupportedBanks())

    /**
     * Reads the generic PIDs, skipping any the mask already excluded.
     *
     * @param supported the mask from [readSupported]; when omitted it is read here first.
     */
    suspend fun readReadings(supported: SupportedPidsReport? = null): List<PidReading> {
        val report = supported ?: readSupported()
        return GenericPids.ALL.map { definition -> read(definition, report) }
    }

    /**
     * Reads bank `0x00`, then follows the continuation bit through `0x20`, `0x40`, `0x60`.
     *
     * Stops at the first bank that does not say "there is more", and at the first failure: a bank
     * whose answer was `NO DATA` cannot tell us whether a later bank exists.
     */
    private suspend fun readSupportedBanks(): List<SupportedBank> {
        val banks = mutableListOf<SupportedBank>()
        for (basePid in SupportedPids.BASE_PIDS) {
            val result = service.supportedPids(basePid)
            banks += SupportedBank(basePid, result)
            val continues = result.dataOrNull?.continuesToNextBank ?: false
            if (!continues) break
        }
        return banks
    }

    private suspend fun read(
        definition: PidDefinition,
        supported: SupportedPidsReport,
    ): PidReading {
        if (supported.support(definition.pid) == false) {
            return PidReading(definition, DiagnosticResult.Unsupported, requested = false)
        }
        val result: DiagnosticResult<Double> = when (definition.pid) {
            Mode01Pids.COOLANT_TEMPERATURE -> service.coolantTemperatureCelsius().mapSuccess { value ->
                value.toDouble()
            }
            Mode01Pids.ENGINE_RPM -> service.engineRpm()
            Mode01Pids.VEHICLE_SPEED -> service.vehicleSpeedKph().mapSuccess { value -> value.toDouble() }
            else -> return PidReading(definition, DiagnosticResult.Unsupported, requested = false)
        }
        return PidReading(definition, result, requested = true)
    }
}
