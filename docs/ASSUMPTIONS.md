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
| A8 | ~~`FakeByteTransport` lives in the main source set.~~ **RESOLVED in phase 3:** moved to `app/src/test/java/com/innova/diag/core/transport/fake/`. | The reason for the original placement was that the Compose shell needed it at runtime. Once `BluetoothClassicTransport` existed, the shell was replaced by the adapter screen and the fake became test-only, so it now sits where the phase-2 brief asked for it. | None — this is the desired end state. | **Resolved** |
| A9 | **The build gate is not yet executed.** `./gradlew testDebugUnitTest` (phase 4), `./gradlew lintDebug testDebugUnitTest` (phase 3) and `./gradlew lintDebug testDebugUnitTest assembleDebug` (repo-wide gate) could not run in the authoring environment: no JDK, no Android SDK, and no network route to `services.gradle.org`, `repo.maven.apache.org`, or `dl.google.com`. | AGENTS.md requires every PR to pass that command. Claiming a pass would be the exact dishonesty this repository is built to prevent. | If the project does not build in a networked environment, this phase is incomplete — treat the gate as **UNRUN**, not as passed. | **BLOCKING — must be run before merge** |
| A10 | Router/version choices: AGP 8.5.2, Gradle 8.7, Kotlin 2.0.21, compileSdk/targetSdk 34, minSdk 26, Compose BOM 2024.09.03. | The task specified minSdk 26 and "compileSdk 34+"; exact plugin versions were unspecified. | Version bumps only; no source changes needed unless Compose/Kotlin majors change. | Provisional |

## Phase 3 — Bluetooth Classic RFCOMM transport

| # | Assumption / decision | Why it was needed | Impact if wrong | Status |
|---|---|---|---|---|
| A11 | `BLUETOOTH_SCAN` is declared in the manifest and modelled in the permission provider, but **nothing depends on it**: this phase performs no discovery, so the transport gates only on `BLUETOOTH_CONNECT`. | The brief names both permissions; discovery is out of scope, and gating connection on `SCAN` would break a user who granted only "Nearby devices: connect". | If a later phase adds discovery, `SCAN` must become a hard gate at that point. | Open — deliberate |
| A12 | The transport resolves the selection by **MAC address** (`adapter.getRemoteDevice(address)`), and selection is set only from a UI tap. | The brief forbids choosing an adapter by name; an address is the only stable identity the platform gives us. | None expected. Address is display-only data. | Settled |
| A13 | `io.mockk:mockk` is added as a **test-only** dependency. | `BluetoothSocket`, `BluetoothDevice`, and `BluetoothAdapter` are final classes; MockK's inline instrumentation is the supported way to mock them on the JVM. | Alternative is Robolectric or a hand-rolled socket seam; both are heavier or less faithful. | Provisional |
| A14 | The permission provider is **snapshot-based** (`state()` + explicit `refresh()`), refreshed on activity resume and after the permission dialog returns. | A live permission listener does not exist on Android; polling or lifecycle-driven refresh is the available mechanism. | A revocation while the app is foregrounded is noticed at the next refresh, not instantly. Connect re-reads the state and fails closed regardless. | Open |
| A15 | `connect()` holds the transport mutex while the RFCOMM handshake runs, so `disconnect()` during `CONNECTING` waits for the attempt to finish or fail. | A blocking `socket.connect()` cannot be cancelled by coroutine cancellation; closing the socket mid-handshake is what actually aborts it. | A slow/hung handshake delays a user-initiated cancel. Worth revisiting with a timed connect + socket close on cancel. | Open — known limitation |
| A16 | A locally initiated `disconnect()` always lands in `DISCONNECTED`, never `ERROR`, even though closing the socket makes the pending `read()` throw. | Reporting a user's own action as a fault trains users to ignore error states. Link-generation guarding makes the stale read loop unable to publish `ERROR` over a newer state. | None — covered by a dedicated test. | Settled |

## Phase 4 — ELM adapter engine

| # | Assumption / decision | Why it was needed | Impact if wrong | Status |
|---|---|---|---|---|
| A17 | `ElmChannel` subscribes to `incomingBytes()` **per command** (inside the timeout window) rather than running one long-lived collector. | The transport contract guarantees only a single consumable stream; per-command subscription keeps the channel free of a background scope it would have to own and cancel. Bytes arriving between commands stay buffered in the transport. | If an adapter emits unsolicited chatter while idle, it becomes the prefix of the next response. Acceptable while only known AT commands run; must be revisited before OBD polling. | Open — known limitation |
| A18 | A response is returned as **one whitespace-free string**, with multi-line replies concatenated (`"41 00 BE 3F\r…"` → `"4100BE3F"`). | The brief specifies exactly this normalization, and it is all the initializer needs. | Multi-line PID/frame responses will need line-level access later; that belongs in the OBD layer, not here. | Open — deliberate |
| A19 | Added `ElmException.LinkClosed` beyond the brief's token list. | If the stream completes without a prompt, `Flow.first {}` throws `NoSuchElementException`; letting that escape would be an untyped crash and would blur "link died" with "adapter stayed silent". | None — additive; `Timeout` keeps its exact meaning. | Settled |
| A20 | Command strings are validated: blank or line-terminator-containing commands are rejected before any byte is written. | `"ATZ\rATE0"` would inject a second command and desynchronise the request/response pairing. | None — a caller building commands from user input is refused early. | Settled |
| A21 | `ATSP0` is sent as part of initialization even though the vehicle protocol is unverified. | The brief requires it, and it selects *automatic* detection: it pins nothing and presumes no protocol. Nothing downstream may issue a vehicle request until the matrix is verified. | If the pilot vehicle's protocol is later verified, auto-detect can stay or be replaced by an explicit `ATSP<n>` — a matrix-driven decision, not a default. | Open |
