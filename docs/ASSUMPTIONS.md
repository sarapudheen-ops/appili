# ASSUMPTIONS.md — Open Assumptions & Interpretations

Per [AGENTS.md](../AGENTS.md): *"If an assumption is needed, stop and document it here. Do not
guess."* This file records interpretations made while authoring governance documents. None of
these authorize a vehicle-specific byte value; those remain `UNKNOWN - PENDING_PHYSICAL_LOG`.

| # | Assumption | Why it was needed | Impact if wrong | Status |
|---|---|---|---|---|
| A1 | "Engine" in the required matrix fields means the **engine control module** (its identity/addressing), not engine displacement. | The field list in [VEHICLE_MATRIX.md](VEHICLE_MATRIX.md) §1.2 places Engine beside Protocol/ECU Address/Header/Init Sequence. | Matrix gains a row; no behavior change — both are currently unverified. | Open — confirm with owner |
| A2 | The `RECORDED` status (documented fact, display-only) is introduced alongside the required `UNKNOWN - PENDING_PHYSICAL_LOG`. | Make/model/year/market are known from the task statement but are not log-verified; conflating them with `VERIFIED` would violate fail-closed. | If the owner prefers only two statuses, `RECORDED` folds into `UNKNOWN` for all bus-access purposes. | Open |
| A3 | Level 2 may permit `0x10` DiagnosticSessionControl **only** to a session declared non-write-capable in the matrix. | Some OEM modules gate reads behind a non-default session; without this, Level 2 would be unusable on such modules. | If unacceptable, drop `0x10` from Level 2 entirely — Level 2 then limited to default-session reads. | Open — no OEM data yet |
| A4 | Default confirmation window of 60 s for the `USER_CONFIRMED` token. | [SAFETY_POLICY.md](SAFETY_POLICY.md) §3.4 requires an expiry; no value was specified. | Pure policy constant; per-operation overrides are allowed and must be statically defined. | Provisional |
| A5 | "Simulator" means an in-process internal module simulator, not an external hardware simulator or HIL rig. | Level 3/4 are defined as simulator-only; the binding mechanism differs per interpretation. | If an external rig is intended, the "unreachable by construction" argument (§4.1) must be re-derived. | Open — revisit before any simulator work |
| A6 | DTC clearing (`0x04`) is treated as a destructive write and excluded from Level 1. | It erases freeze frames and readiness monitors; AGENTS.md Rule 2 forbids physical writes. | If the owner wants read-only DTC clearing later, PRODUCT_SCOPE §2.2 must be amended explicitly. | Open — recommended to keep excluded |
| A7 | `docs/captures/` will hold raw physical-log evidence; no such files exist yet. | §4.1 of the matrix requires committed captures for promotion. | None today; the directory is created when the first capture lands, not in advance. | Pending |

## Non-assumptions (explicitly *not* decided here)

- No OBD-II protocol (ISO 9141-2, ISO 14230-4/KWP, ISO 15765-4/CAN, J1850) has been selected,
  suggested, or ranked for this vehicle.
- No ECU address, header (`ATSH`), init sequence, baud rate, or timing parameter has been chosen.
- No PID, DID, or routine identifier has been defined.
- No build tooling, module layout, or Gradle/Kotlin source has been created in this phase.

## Phase 2 — Android skeleton & transport contracts

| # | Assumption / decision | Why it was needed | Impact if wrong | Status |
|---|---|---|---|---|
| A8 | `FakeByteTransport` lives in the **main** source set (`app/src/main/java/com/innova/diag/core/transport/fake/`), not in `app/src/test/`. | The Compose shell must render a live `TransportState` at runtime in a phase that has no real adapter. A test-source-set class is not on the app's classpath, so placing it only in `app/src/test/` would make the shell uncompilable; duplicating it would create two divergent fakes. Unit tests, fixtures and the assertion helper remain in `app/src/test/java/com/innova/diag/core/transport/`. | Cosmetic only: move the class to a shared test-fixture module once the real adapter exists. No API or behavior change. | Open — trivial to relocate |
| A9 | **The build gate is not yet executed.** `./gradlew testDebugUnitTest assembleDebug` (and `lintDebug`) could not run in the authoring environment: no JDK, no Android SDK, and no network route to `services.gradle.org`, `repo.maven.apache.org`, or `dl.google.com`. | AGENTS.md requires every PR to pass that command. Claiming a pass would be the exact dishonesty this repository is built to prevent. | If the project does not build in a networked environment, this phase is incomplete — treat the gate as **UNRUN**, not as passed. | **BLOCKING — must be run before merge** |
| A10 | Router/version choices: AGP 8.5.2, Gradle 8.7, Kotlin 2.0.21, compileSdk/targetSdk 34, minSdk 26, Compose BOM 2024.09.03. | The task specified minSdk 26 and "compileSdk 34+"; exact plugin versions were unspecified. | Version bumps only; no source changes needed unless Compose/Kotlin majors change. | Provisional |
