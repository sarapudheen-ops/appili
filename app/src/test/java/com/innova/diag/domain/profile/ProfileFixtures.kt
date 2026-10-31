package com.innova.diag.domain.profile

import com.innova.diag.domain.obd.DiagnosticResult
import com.innova.diag.domain.obd.ObdCommandPort

/**
 * Profiles and a port that exists only to be observed.
 *
 * The point of these fixtures is the *difference* between the two ECU shapes: a verified one carrying
 * captured addresses and a captured init sequence, and an uncaptured one that says `UNKNOWN` in every
 * field. Everything the executor refuses hangs off that difference.
 */
internal object ProfileFixtures {

    /** A generic mode 01 read, the only kind of operation a profile may currently declare. */
    fun readEngineSpeed(): DiagnosticOperation =
        DiagnosticOperation("read-engine-speed", RiskClass.READ_ONLY, ServiceBytes.require("010C"))

    /** An ECU definition with nothing captured: the shape every new profile starts in. */
    fun uncapturedEcu(
        name: String = "Engine ECU",
        status: ProfileStatus = ProfileStatus.UNVERIFIED,
    ): EcuDefinition = EcuDefinition(
        name = name,
        network = EcuAddress.UNKNOWN_VALUE,
        txId = EcuAddress.Unknown,
        rxId = EcuAddress.Unknown,
        initCommands = InitSequence.Unknown,
        supportedServices = emptyList(),
        status = status,
    )

    /** An ECU definition that claims verification and therefore must have real contents. */
    fun capturedEcu(
        name: String = "Engine ECU",
        status: ProfileStatus = ProfileStatus.VERIFIED,
        supportedServices: List<Int> = listOf(0x01),
        txId: EcuAddress = EcuAddress.Known("7E0"),
        rxId: EcuAddress = EcuAddress.Known("7E8"),
        initCommands: InitSequence = InitSequence.Known(listOf("ATSP0")),
        network: String = "CAN 11/500",
    ): EcuDefinition = EcuDefinition(
        name = name,
        network = network,
        txId = txId,
        rxId = rxId,
        initCommands = initCommands,
        supportedServices = supportedServices,
        status = status,
    )

    /** A profile whose overall status follows its ECUs; override either for the case under test. */
    fun profile(
        status: ProfileStatus = ProfileStatus.VERIFIED,
        operations: List<DiagnosticOperation> = listOf(readEngineSpeed()),
        ecus: List<EcuDefinition> = listOf(
            if (status == ProfileStatus.VERIFIED) capturedEcu() else uncapturedEcu(status = status),
        ),
    ): VehicleProfile = VehicleProfile(
        id = "test-innova-2007-gcc",
        make = "Toyota",
        model = "Innova",
        yearRange = YearRange(from = 2007, to = 2007),
        market = "GCC",
        ecuDefinitions = ecus,
        operations = operations,
    )
}

/**
 * An [ObdCommandPort] that records what it was asked to transmit.
 *
 * It answers with a canned success so a test that *should* have been refused fails loudly on
 * [transmissions] rather than on an unexpected exception.
 */
internal class RecordingPort(
    private val response: DiagnosticResult<String> = DiagnosticResult.Success("410C1A20", "410C1A20"),
) : ObdCommandPort {

    private val log = mutableListOf<String>()

    /** Every command handed to the port, in order. */
    val commands: List<String> get() = synchronized(log) { log.toList() }

    /** How many times the port was asked to transmit. Zero is the only acceptable value for a denial. */
    val transmissions: Int get() = commands.size

    override suspend fun execute(command: String): DiagnosticResult<String> {
        synchronized(log) { log += command }
        return response
    }
}
