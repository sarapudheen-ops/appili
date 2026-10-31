package com.innova.diag.domain.obd

/** PIDs this phase decodes. Only mode 01 read-only PIDs appear here. */
object Mode01Pids {
    const val SUPPORTED_PIDS_00: Int = 0x00
    const val SUPPORTED_PIDS_20: Int = 0x20
    const val SUPPORTED_PIDS_40: Int = 0x40
    const val SUPPORTED_PIDS_60: Int = 0x60
    const val COOLANT_TEMPERATURE: Int = 0x05
    const val ENGINE_RPM: Int = 0x0C
    const val VEHICLE_SPEED: Int = 0x0D
}

/**
 * Decoders for the mode 01 PIDs in scope: engine RPM, vehicle speed, coolant temperature, and the
 * supported-PID bitmask banks.
 *
 * Every function takes the **normalized response text** produced by the adapter channel — e.g.
 * `"410C1A20"`, `"41 0C 1A 20"` or the adapter's `"NO DATA"` — and returns a
 * [DiagnosticResult]. Nothing throws for bad input: an unusable answer is a value, not an exception.
 *
 * Formulas are SAE J1979:
 * - PID `0x0C`: `((A * 256) + B) / 4` rpm
 * - PID `0x0D`: `A` km/h
 * - PID `0x05`: `A - 40` °C
 *
 * Out of scope and deliberately absent: mode 04 (clear DTCs) and manufacturer-specific mode 21/22.
 */
object Mode01Decoders {

    /**
     * PID `0x0C`, engine speed.
     *
     * @return rpm as a [Double]; the formula's resolution is 0.25 rpm, which is why the value is not
     *   an integer.
     */
    fun engineRpm(raw: String): DiagnosticResult<Double> =
        ObdFrames.modeResponse(raw, ObdFrames.SERVICE_MODE_01, Mode01Pids.ENGINE_RPM).mapData { data ->
            if (data.size != 2) {
                DiagnosticResult.Malformed(raw.trim())
            } else {
                val a = data[0].toInt() and 0xFF
                val b = data[1].toInt() and 0xFF
                DiagnosticResult.Success(((a * 256) + b) / 4.0, raw.trim())
            }
        }

    /** PID `0x0D`, vehicle speed in km/h. */
    fun vehicleSpeedKph(raw: String): DiagnosticResult<Int> =
        ObdFrames.modeResponse(raw, ObdFrames.SERVICE_MODE_01, Mode01Pids.VEHICLE_SPEED).mapData { data ->
            if (data.size != 1) {
                DiagnosticResult.Malformed(raw.trim())
            } else {
                DiagnosticResult.Success(data[0].toInt() and 0xFF, raw.trim())
            }
        }

    /** PID `0x05`, engine coolant temperature in °C. Negative values are normal when cold. */
    fun coolantTemperatureCelsius(raw: String): DiagnosticResult<Int> =
        ObdFrames.modeResponse(raw, ObdFrames.SERVICE_MODE_01, Mode01Pids.COOLANT_TEMPERATURE).mapData { data ->
            if (data.size != 1) {
                DiagnosticResult.Malformed(raw.trim())
            } else {
                DiagnosticResult.Success((data[0].toInt() and 0xFF) - 40, raw.trim())
            }
        }

    /**
     * Supported PIDs for the bank starting at [basePid] (`0x00`, `0x20`, `0x40`, `0x60`).
     *
     * @throws IllegalArgumentException if [basePid] is not a valid bank base — a programming error,
     *   not a vehicle condition, so it must not be swallowed into a [DiagnosticResult].
     */
    fun supportedPids(raw: String, basePid: Int): DiagnosticResult<SupportedPids> {
        require(basePid in SupportedPids.BASE_PIDS) {
            "supported-PID banks exist only at ${SupportedPids.BASE_PIDS.joinToString { "0x" + it.toString(16).uppercase().padStart(2, '0') }}"
        }
        return ObdFrames.modeResponse(raw, ObdFrames.SERVICE_MODE_01, basePid).mapData { data ->
            if (data.size != 4) {
                DiagnosticResult.Malformed(raw.trim())
            } else {
                DiagnosticResult.Success(SupportedPids(basePid, SupportedPids.maskOf(data)), raw.trim())
            }
        }
    }
}
