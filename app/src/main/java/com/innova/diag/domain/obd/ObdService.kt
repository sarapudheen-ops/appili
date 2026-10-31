package com.innova.diag.domain.obd

/**
 * The request strings this engine is allowed to send.
 *
 * Kept in one place so the read-only surface is auditable: every string below is a *read* request,
 * and there is no builder that accepts a caller-supplied mode. Mode 04 (clear DTCs) and
 * manufacturer-specific modes 21/22 are absent by design — this repository does not write to
 * vehicles (`docs/PRODUCT_SCOPE.md` §2.2, `docs/SAFETY_POLICY.md`).
 */
object ObdRequests {

    /** Mode 03, stored emission-related DTCs. Read-only. */
    const val MODE_03_STORED_DTCS: String = "03"

    /** Mode 07, pending DTCs. Read-only. */
    const val MODE_07_PENDING_DTCS: String = "07"

    /** Mode 09 PID 02, VIN. Read-only. */
    const val MODE_09_VIN: String = "0902"

    /** Mode 01 request for [pid], e.g. `0x0C` → `"010C"`. */
    fun mode01(pid: Int): String {
        require(pid in 0x00..0xFF) { "PID must be a single byte, got 0x${pid.toString(16)}" }
        return "01" + pid.toString(16).uppercase().padStart(2, '0')
    }
}

/**
 * Read-only OBD-II access: one method per request in scope, each returning a [DiagnosticResult].
 *
 * Every method sends exactly one command through [ObdCommandPort] and hands the text to the matching
 * decoder. Nothing is cached, retried or smoothed over: an unanswered PID is reported as
 * [DiagnosticResult.NoResponse] and a silent link as [DiagnosticResult.Timeout], because a plausible
 * `0` is worse than an honest gap (`AGENTS.md` Rule 4).
 *
 * ### Deliberately absent
 * - **Mode 04 (clear DTCs)** and **modes 21/22 (manufacturer-specific)** — no method exists, so no
 *   caller can reach them.
 * - **No arbitrary command entry point.** There is no `execute(command)` here: the read-only
 *   surface is a fixed list of functions, which is what makes "this build cannot write" checkable.
 * - **No supported-PID bank walking.** [supportedPids] answers for one bank; the caller decides
 *   whether to follow [SupportedPids.continuesToNextBank].
 */
class ObdService(private val port: ObdCommandPort) {

    /** Mode 01 PID `0x0C` — engine speed in rpm. */
    suspend fun engineRpm(): DiagnosticResult<Double> =
        request(ObdRequests.mode01(Mode01Pids.ENGINE_RPM), Mode01Decoders::engineRpm)

    /** Mode 01 PID `0x0D` — vehicle speed in km/h. */
    suspend fun vehicleSpeedKph(): DiagnosticResult<Int> =
        request(ObdRequests.mode01(Mode01Pids.VEHICLE_SPEED), Mode01Decoders::vehicleSpeedKph)

    /** Mode 01 PID `0x05` — engine coolant temperature in °C. */
    suspend fun coolantTemperatureCelsius(): DiagnosticResult<Int> =
        request(ObdRequests.mode01(Mode01Pids.COOLANT_TEMPERATURE), Mode01Decoders::coolantTemperatureCelsius)

    /**
     * Mode 01 supported-PID bitmask for the bank starting at [basePid].
     *
     * @throws IllegalArgumentException if [basePid] is not one of `0x00`, `0x20`, `0x40`, `0x60`.
     */
    suspend fun supportedPids(basePid: Int): DiagnosticResult<SupportedPids> {
        // Checked before anything is sent: a bad bank index is a programming error, and it must not
        // put a pointless request on the vehicle bus first.
        require(basePid in SupportedPids.BASE_PIDS) {
            "supported-PID banks exist only at 0x00, 0x20, 0x40 and 0x60; got 0x${basePid.toString(16)}"
        }
        return request(ObdRequests.mode01(basePid)) { raw -> Mode01Decoders.supportedPids(raw, basePid) }
    }

    /** Mode 03 — stored diagnostic trouble codes. */
    suspend fun storedDtcs(): DiagnosticResult<List<DiagnosticTroubleCode>> =
        request(ObdRequests.MODE_03_STORED_DTCS, DtcDecoders::stored)

    /** Mode 07 — pending diagnostic trouble codes. */
    suspend fun pendingDtcs(): DiagnosticResult<List<DiagnosticTroubleCode>> =
        request(ObdRequests.MODE_07_PENDING_DTCS, DtcDecoders::pending)

    /** Mode 09 PID `0x02` — the vehicle identification number. */
    suspend fun vin(): DiagnosticResult<Vin> =
        request(ObdRequests.MODE_09_VIN, VinDecoder::decode)

    /**
     * One command, one decode. A port failure is returned unchanged — a [DiagnosticResult.Timeout]
     * from the link must not be re-interpreted as "the ECU had nothing to say".
     */
    private suspend fun <T> request(
        command: String,
        decode: (String) -> DiagnosticResult<T>,
    ): DiagnosticResult<T> = port.execute(command).mapData(decode)
}
