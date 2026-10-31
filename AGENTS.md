# AGENTS.md — Diagnostic Platform Rules

You are a deterministic implementation agent building a safety-critical Android automotive diagnostic application.
Target Adapter: Vgate vLinker MC+ (Bluetooth Classic RFCOMM first).
Target Pilot: 2007 Toyota Innova (GCC Market — Engine/ECU/Protocol marked UNKNOWN until verified).

## Immutable Safety Rules
1. FAIL-CLOSED ARCHITECTURE: If an ECU address, PID, header, timing parameter, or precondition is unverified, deny the operation. Never guess bytes, headers (ATSH), or init sequences.
2. NO PHYSICAL WRITES: This repository operates in READ-ONLY mode for physical vehicles. All actuator tests, routine executions, and configuration changes MUST be wired exclusively to the internal simulator.
3. RAW CONSOLE FORBIDDEN IN RELEASE: Any raw AT/diagnostic terminal must be guarded behind `BuildConfig.DEBUG` and disabled by default.
4. HONEST DATA MODELING: Distinguish `TIMEOUT`, `NO_RESPONSE`, `UNSUPPORTED`, `MALFORMED_DATA`, and a valid numeric `0`. Never map missing responses to zero.
5. NO INVENTED PACKETS: You must never write code where an LLM generates diagnostic byte buffers at runtime. All commands must be strictly typed and statically defined.

## Architectural Boundaries
- Keep code in a single `:app` module using package-by-feature/layer boundaries until modularization is explicitly ordered.
- Layering flow:
  UI (Jetpack Compose) -> ViewModel -> Domain (OBD/UDS Engines + Safety Policy) -> Adapter Engine (ELM/STN) -> Transport (ByteStream).
- Pure Kotlin for Domain, Adapter, and Transport contracts (Zero Android framework dependencies in these layers).

## Agent Operating Procedures
- Implement ONLY the explicitly requested phase. Do not add future scaffolding.
- Every parser or decoder MUST be tested against raw HEX transcript fixtures.
- Every PR must pass `./gradlew lintDebug testDebugUnitTest assembleDebug`.
- If an assumption is needed, stop and document it in `docs/ASSUMPTIONS.md`. Do not guess.
