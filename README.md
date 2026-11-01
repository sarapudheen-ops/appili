# appili

[![Android CI](https://github.com/sarapudheen-ops/appili/actions/workflows/build.yml/badge.svg)](https://github.com/sarapudheen-ops/appili/actions/workflows/build.yml)

Safety-critical Android OBD-II diagnostics for the Vgate vLinker MC+ adapter.
Pilot vehicle: 2007 Toyota Innova (GCC). **Read-only toward physical vehicles** — see
[`docs/PRODUCT_SCOPE.md`](docs/PRODUCT_SCOPE.md).

## Status

| Phase | State |
|---|---|
| Governance & safety docs | Complete — [`docs/`](docs) |
| Android skeleton + transport contracts | Complete; gate green in CI (A74) |
| Bluetooth Classic RFCOMM transport | Implemented: socket only, no commands sent on connect |
| ELM adapter engine (channel + init) | Implemented: AT configuration only — **no PIDs, no headers** |
| OBD-II read-only decode (modes 01/03/07/09) | Implemented: decoders + request port; **no writes**; gate green in CI (A74) |
| Compose UI: connection, generic scan, live data, session export | Implemented; gate green in CI (A74); emulator run and screenshots still unrun (A42) |
| Vehicle profiles (JSON) + fail-closed matching gate | Implemented: parser, models, executor, candidate profile; gate green in CI (A74) |
| Active-test state machine + bench ECU + simulator screen | Implemented: dead-man switch, 10 s ceiling, hardware lock; gate green in CI (A74) |
| Multi-brand hierarchical packs (brand → family → variant) | Implemented: schema v2, registry, derived tiers, 12 packs (all T4); gate green in CI (A74) |
| UDS / manufacturer-specific modes | Not started — blocked by unverified vehicle matrix |

Opening an RFCOMM socket is transport plumbing: it sends zero bytes and assumes no protocol. Every
vehicle-specific field in [`docs/VEHICLE_MATRIX.md`](docs/VEHICLE_MATRIX.md) is still
`UNKNOWN - PENDING_PHYSICAL_LOG`. Since phase 6 the app *does* send generic mode 01/03/07/09 reads on
an explicit tap, but it still claims no per-vehicle result — see A31.

## Layout

```
app/src/main/java/com/innova/diag/
  core/transport/          # pure Kotlin: ByteTransport contract + TransportState (no Android imports)
  domain/obd/              # pure Kotlin: DiagnosticResult, mode 01/03/07/09 decoders, ObdService, port
  adapter/elm/             # ElmChannel (command/response) + ElmInitializer + ElmObdCommandPort
  platform/bluetooth/      # RFCOMM transport, paired-device listing, permission provider
  domain/session/          # pure Kotlin: session log, sanitiser, JSON/CSV exporter
  domain/profile/          # pure Kotlin: profile schema, strict JSON parser, fail-closed gate
  domain/profile/pack/     # pure Kotlin: brand/family/variant packs, schema validator, tier derivation
  domain/active/           # pure Kotlin: dead-man switch, bench ECU simulator, UDS frames
  platform/demo/           # demo-mode switch; the scripted transport itself lives in src/debug
  platform/profile/        # AssetManager-backed pack source + the startup catalogue (A69)
  ui/                      # Compose: connection, generic scan, live data, raw console (debug only)
app/src/test/java/com/innova/diag/
  core/transport/          # FakeByteTransport fixtures + contract tests
  adapter/elm/             # transcript-replay tests: chunking, noise, error tokens, timeouts
  domain/obd/              # decoder tests: SAE J1979 vectors, DTC/VIN, absence-is-not-zero
  platform/bluetooth/      # transport/socket tests with mocked Android Bluetooth classes
  ui/                      # presentation tests: "never renders 0 for an absent value" and friends
  domain/session/          # export tests: well-formed JSON/CSV, VIN redaction, escaping
  domain/profile/          # parser rejections, model invariants, "refusal transmits nothing"
  domain/profile/pack/     # schema, load, tier and shipped-asset tests: nothing ships that can transmit
  domain/active/           # release sends ReturnControlToECU, disconnect aborts, 10 s ceiling
app/src/main/assets/profiles/  # toyota_innova_2007_gcc.json — UNVERIFIED candidate, no captured bytes
app/src/main/assets/profiles/brands/  # 12 brand packs: 11 UNKNOWN stubs + the UNVERIFIED Innova pilot
app/src/main/assets/profiles/schema/  # vehicle_pack_schema_v2.json — the contract the packs are checked against
app/src/debug/java/com/innova/diag/
  platform/demo/           # DemoByteTransport: a scripted, authored ELM327 transcript (never shipped)
```

## Bluetooth behaviour

- **Classic RFCOMM only**, SPP UUID `00001101-0000-1000-8000-00805F9B34FB`. BLE is out of scope.
- **Paired devices only.** No discovery, no scan list, no name matching, no default selection.
- **No commands on connect.** No `ATZ`/`ATE0`/`ATSP…`, no handshake beyond RFCOMM itself.
- **Fail-closed.** Missing `BLUETOOTH_CONNECT`, Bluetooth off, no adapter, or no selection each
  refuse the connect with a specific reason; nothing touches the radio before a user choice.
- All blocking socket work runs on an injected IO dispatcher (default `Dispatchers.IO`).

Android 12+ runtime permissions are requested at the activity boundary and read by the transport
through an injected `BluetoothPermissionStateProvider`; the transport never calls the permission API.

## Adapter engine

`ElmChannel` sits above `ByteTransport` and turns an undelimited byte stream into discrete
exchanges: one command in flight (mutex), accumulate until the `>` prompt, strip echo and
whitespace, and map adapter tokens (`NO DATA`, `UNABLE TO CONNECT`, `BUS ERROR`, `CAN ERROR`,
`?`, `BUFFER FULL`, `STOPPED`) to typed `ElmException`s. A missing prompt is a timeout
(2 s default, configurable), never an empty success.

`ElmInitializer` sends exactly five adapter commands — `ATZ`, `ATE0`, `ATL0`, `ATS0`, `ATSP0` —
and nothing else. It never sets a header (`ATSH`) and never issues a PID or Mode 01 request; the
vehicle matrix is still unverified, so the engine is not yet allowed to ask the car anything.

## Read-only OBD-II engine

`com.innova.diag.domain.obd` decodes what the adapter printed. It is pure Kotlin — no Android, no
`ElmChannel`, no sockets — so every decoder is testable from a string.

- **Mode 01:** supported-PID bitmask banks `00/20/40/60`, RPM `0C` (`((A*256)+B)/4`), speed `0D`
  (`A`), coolant `05` (`A-40`).
- **Modes 03 / 07:** stored and pending DTCs, 2-byte blocks to `P/C/B/U` codes (`01 33` to `P0133`),
  with `0x0000` padding discarded rather than reported as `P0000`.
- **Mode 09 PID 02:** VIN, assembled from ASCII and validated (17 characters, no `I`/`O`/`Q`).
- **Mode 04 and modes 21/22 do not exist here.** There is no command builder that can produce them.

Every call returns `DiagnosticResult<T>`: `Success(data, rawHex)`, `Unsupported`, `NoResponse`,
`Timeout` or `Malformed(rawHex)`. A silent ECU is `NoResponse`, a missing prompt is `Timeout`, and
`Success(0)` is the *only* way to say "the vehicle reported zero" — nothing is defaulted to `0`.

Requests cross the `ObdCommandPort` seam: the domain declares it, `ElmObdCommandPort` binds it to
`ElmChannel` and maps adapter failures (`NO DATA` or a closed link to `NoResponse`, a prompt timeout
to `Timeout`, a rejected command to `Malformed`). Tests use replay strings and fake transports only —
never a vehicle. `docs/ASSUMPTIONS.md` A22-A30 records what the fixtures do and do not prove.

## Scan & live data UI

Three screens, one `DiagnosticsViewModel`, and one rule: **an absent value is never a number.**

| Screen | Shows |
|---|---|
| Connection | transport state, adapter identity (`ATI`), negotiated protocol (`ATDP`) — labelled as the adapter's claim |
| **Generic Powertrain Scan (Emissions/OBD-II only)** | supported PIDs, stored DTCs (`03`), pending DTCs (`07`), VIN (`09 02`) |
| Live data | engine speed `RPM`, vehicle speed `km/h`, coolant temperature `°C` |

Presentation is a total function over `DiagnosticResult` (`ui/Readings.kt`): `Success` becomes a
measured value with its unit, `Unsupported` becomes **Not Supported** in grey, `NoResponse`/`Timeout`
become **No Response**/**Timeout** in orange, `Malformed` becomes **Unreadable data**. There is no
branch anywhere that renders `0` for a PID that did not answer — `Success(0)` is the only way to print
a zero, and it means the ECU really said zero. A PID the supported-PID bitmask excludes is never even
requested (A34); if no mask bank answers, support is *unknown*, not *empty* (A35).

Live data polls sequentially at 600 ms, reading the mask once per run (A36), and stops on pause so a
backgrounded app is not chatting to the car.

## Session export

Every transmitted command and every received response is recorded in `domain/session/SessionLog`
(sent/received timestamps, the command, the raw response, the failure token, 1-based index). The
export button hands a **sanitised** log to the share sheet as text — JSON (schema `appili.session.v1`)
or CSV — with the VIN replaced by a placeholder in **both** its ASCII and hex form (A38), no
`FileProvider`, no storage permission, nothing left on disk (A37). The CSV is one rectangular table so
a spreadsheet can read it (A39).

## Demo mode (debug builds only)

`DemoByteTransport` answers with an authored ELM327 transcript — `ATI` → `ELM327 v1.5`,
`ATDP` → `AUTO, ISO 15765-4 (CAN 11/500)`, `0100` → `4100B7180000`, `010C` → `1672.0`, `010D` → `55`,
`03` → `P0133`, `07` → `NO DATA`, `0902` → `1M8GDM9AXKP042788`. It lives in the **debug** source set
so no release build can reach it (A40), and the screen says the responses are scripted (A41).

The transcript doubles as the truthfulness demo: `0x05` (coolant) is *excluded* by that mask, so the
scan screen shows **Not Supported** and never sends `0105`, while `07` answering `NO DATA` shows
**No Response** — two different kinds of "nothing", displayed two different ways.

## Vehicle profiles and the matching gate

A profile is a JSON description of a vehicle model, market and year span, the ECUs on its bus, and the
operations that may be asked of them. It is **pure data**: it is parsed, never evaluated, and no value
in it can become a command unless it is a hex string the parser accepts (AGENTS.md Rule 5).

```
VehicleProfile  id, make, model, yearRange, market, ecuDefinitions, operations
  EcuDefinition name, network, txId, rxId, initCommands, supportedServices, status
  DiagnosticOperation  id, riskClass (READ_ONLY | ACTIVE_TEST | CONFIGURATION), serviceBytes
ProfileStatus   VERIFIED | UNVERIFIED | SIMULATOR_ONLY
```

`ProfileOperationExecutor` is the only way a profile can cause a byte to reach a vehicle, and it is
**fail-closed**: it throws `SafetyViolationException` *before* touching the transport, with a reason —
`PROFILE_NOT_VERIFIED`, `SIMULATOR_ONLY`, `ECU_NOT_DESCRIBED`, `OPERATION_NOT_DECLARED`,
`RISK_CLASS_FORBIDDEN`, `SERVICE_NOT_DECLARED`. A refusal is never returned as a vehicle answer, so a
caller cannot read it as `NoResponse`.

Three things are unrepresentable rather than merely disallowed:

- **write services** — `04`, `2E`, `2F`, `31`, `21`, `22` and the rest fail in the `DiagnosticOperation`
  constructor (read-only J1979 services only: A46);
- **a header** — an init command beginning `ATSH` is rejected by `InitSequence.Known` (A44);
- **an invented address** — `txId`/`rxId` are `Known(hex)` or `Unknown`, never a default (`7E0` is not a
  fallback for anything).

The candidate profile `toyota_innova_2007_gcc.json` is `UNVERIFIED`, with `UNKNOWN` addresses, network
and init sequence, an empty `supportedServices`, and the note *"Awaiting physical bus dump from
authorized tool."* Every operation it declares is refused, and a test asserts that a real channel over
a recording transport sees **zero bytes**.

The flat v1 profile is still not loaded by the app (A50): that phase added the schema, the parser and the
gate, not a new way for the UI to talk to a car. The phase-9 brand packs *are* loaded at startup (A69),
again only to describe what exists — every operation in them is refused.

## Active tests (bench only)

`domain.active.ActiveTestController` is the one place in the app that takes control of anything, and it
cannot reach a vehicle:

- **Hardware lock.** A controller constructed over a `PhysicalTransport` throws
  `IllegalStateException("Physical execution disabled")` — at construction, not at run time.
  `BluetoothClassicTransport` carries that marker, so the real adapter is locked out by type.
- **Dead-man switch.** There is no start/stop pair: one suspend `hold(command)` runs while the caller's
  coroutine lives. The press-and-hold gesture *is* the coroutine, so letting go ends the test.
- **Hard ceiling.** 10.0 s maximum, configurable only downward; a 100 ms countdown is published for the
  UI and the cut-off is decided by the controller, not the screen.
- **One exit.** Release, ceiling and abort all leave through the same `finally`, which sends
  `ReturnControlToECU` (`2F <DID> 00`) under `NonCancellable`. A cancelled coroutine still runs it.
- **Abort listener.** Any `TransportState` other than `CONNECTED` aborts immediately, not on the next
  tick. If the link is already gone the release frame cannot be delivered, and the state says so
  (`controlReturned == false`) rather than claiming control was returned (A58).

`MockEcuSimulator : ByteTransport` is the bench ECU: it answers UDS `0x2F` (InputOutputControl) with
`6F ...`, `0x31` (RoutineControl) with `71 ...`, unknown services with `7F <SID> 11`, malformed frames
with `7F <SID> 13`, and terminates everything with the ELM `>` prompt. It is not a `PhysicalTransport`,
which is what makes the locked path reachable at all.

The fourth tab, **Simulated test**, carries the banner `SIMULATOR MODE — NO PHYSICAL COMMANDS`, a
press-and-hold control for the Radiator Fan Relay Test, and the live countdown to the automatic cut-off.
Its view model can only construct the mock, so the Bluetooth transport the other screens use is not in
its object graph (A60).

## Brand packs (brand → family → variant)

Phase 9 replaces the single flat profile with a three-level catalogue under
`app/src/main/assets/profiles/brands/`. Each pack is still **pure data** — parsed, never evaluated.

```
BrandPack          brandId, brandName, commonDtcDictionary,
                   brandLevelProtocolDefaults, securityPolicyOverrides, families
  FamilyPack       familyId, familyName, variantIds, variants
    VehicleVariantPack  variantId, market, yearRange, engineCode, transmission,
                        ecuDefinitions, supportedOperations, verificationStatus
      VariantEcuDefinition  name, partNumber, calibrationIds, network, addressing, verificationStatus
      SupportedOperation    id, description, sourceReference, verificationStatus,
                            riskClass, scope (GENERIC | ENHANCED | UNKNOWN), serviceBytes
Tier               T1_FULL_ENHANCED | T2_PARTIAL_ENHANCED | T3_GENERIC_ONLY | T4_UNVERIFIED
```

**Unknown is a value, not a hole.** Every field that was not captured on a bus is the literal string
`UNKNOWN`, and every status that was not earned is `UNVERIFIED`. `null` and `""` are invalid in every
field, at three levels: the schema (`minLength: 1`, no nullable property, `additionalProperties: false`),
the model constructors, and a test that scans the shipped files for `: null` and `: ""`.

`BrandPackRegistry` loads all packs at startup (`platform.profile.BrandCatalogue`), validating each
against `assets/profiles/schema/vehicle_pack_schema_v2.json` first. Problems are **aggregated**, with a
JSON pointer each, and one bad file fails the whole load — there is no partially loaded catalogue. The
schema validator is hand-written over `domain.profile.JsonValue` (A64) and refuses any keyword it cannot
enforce, so "validated against the schema" cannot quietly mean less than it says.

**Tiers are derived from data, never stored** (A66): `T4` when no variant is verified, `T3` when verified
variants exist but no OEM operation does, `T2` when a verified executable operation is `ENHANCED`, `T1`
when one is a write. Nothing in a pack can set a tier — only evidence can move it.

`PackOperationExecutor` is the only path from a pack to a transport, and it is stricter than the v1 gate:
**an `UNVERIFIED` variant stops everything**, even if it describes a verified operation. The gate runs in
order — undescribed brand/variant/operation, variant status, risk class, operation status, service bytes,
ECU status — and every refusal throws `SafetyViolationException` with a reason before a byte is sent.

What ships today (A68): twelve brands, all **T4**, and only `toyota` has a variant — the pilot
`innova_2007_gcc` of `imv_platform`, `UNVERIFIED`, engine code and transmission `UNKNOWN`, one
`UNVERIFIED` Engine ECU, and the seven generic SAE J1979 reads the app already implements
(`0100`, `010C`, `010D`, `03`, `07`, `0A`, `0902`). The other eleven are `UNKNOWN`/`[]` skeletons.
**No manufacturer command exists in any pack**, and `ShippedBrandPacksTest` drives every declared
operation through the executor and asserts that not one command reaches the port.

## Verification status

The gate **runs on every push and pull request, and it passes**:
[`.github/workflows/build.yml`](.github/workflows/build.yml) runs
`./gradlew clean lintDebug testDebugUnitTest assembleDebug` on **JDK 17 + Android SDK**
(`ubuntu-latest`, Temurin 17), and the badge at the top of this file is its live result — on the
**default branch**, which is what GitHub badges report: on a feature branch it shows the default
branch's status until the work is merged. The first green run is
[41258730456](https://github.com/sarapudheen-ops/appili/actions/runs/41258730456) on `6d6c0b9`: lint,
the full unit-test suite and `assembleDebug` all succeeded.

That run is the first time this repository has ever been compiled. It has **never** been built in the
authoring environment, which has no JDK and no Android SDK (A9, A42) — so every earlier phase reported
the gate as *unrun*, honestly, rather than as passed. CI is what settled it, and it settled it the hard
way; see below.

What the suite covers, all against fake transports, replay strings, JSON fixtures and a bench ECU —
never a device:

- **317 JVM test methods across 31 test classes**: phases 1–6 (177 methods), the phase-7 profile tests
  (`VehicleProfileParserTest`, `ProfileOperationExecutorTest`, `ProfileModelTest`,
  `ToyotaInnovaProfileAssetTest`), the phase-8 active-test tests (`ActiveTestControllerTest`,
  `MockEcuSimulatorTest`, `ActiveTestPresentationTest`) and the phase-9 pack tests (52 methods:
  `BrandPackRegistryTest`, `PackSchemaTest`, `PackOperationExecutorTest`, `ShippedBrandPacksTest`).
- The phase-9 shipped assets are checked against the real schema and the model invariants by test and by
  an independent script (12 packs: no `null`, no `""`, no duplicate ids, no `VERIFIED` claim, no
  non-`READ_ONLY` operation, no frame outside the generic set).

Still outstanding, and not claimed: the emulator run and screenshots, and anything requiring a real
vehicle.

### What it took to go green

The gate never passed before, so the first runs were a backlog of everything that had never been
compiled. In order, CI found:

1. **Five compile errors in main sources** — a KDoc containing `brands/*.json` (Kotlin block comments
   *nest*, so that `/*` swallowed the rest of `AssetPackSource.kt`, and `BrandCatalogue` then reported
   the class as missing), a public `PackSchema.validate(document: JsonValue)` exposing the `internal`
   `JsonValue`, and two missing `crossinline` modifiers on the `inline` `mapData`/`mapSuccess`.
2. **Four compile errors in test sources** — `FixedChannel { "…" }` where the helper takes a `String`,
   a missing `import kotlinx.coroutines.cancelAndJoin`, and mockk's `any()` unable to infer a type
   against the overloaded `BluetoothAdapter.getRemoteDevice`.
3. **Four failing tests** — `mapData`/`mapSuccess` rebuilt failures instead of passing them through by
   identity; an adapter printing `SEARCHING… ELM327 v1.5` on one line yielded no identity at all; a
   whitespace-only `ATI` answer was reported as `Malformed` instead of `NoResponse`; and one phase-9 test
   asked for a variant its own catalogue did not declare.
4. **One more failing assertion that could never pass** — `^[0-9A-Fa-f]` was used to mean "a bare
   diagnostic request", but `A`–`F` are hex digits, so it matched every `AT…` command. It now matches a
   whole hex request.

Each is recorded in the commit history with its cause. No test assertion was deleted or relaxed: three
of those fixes made the tests pass by fixing the code they were written against, and one corrected a
pattern that contradicted its own stated intent.

The workflow provisions the Android SDK itself (`platforms;android-34`, `build-tools;34.0.0`) instead of
depending on `android-actions/setup-android`, which failed intermittently on this repository's own runs.
When the gate fails, two follow-up steps re-emit the failing tests and the compiler errors as
annotations, because the gate is one command and its reason is otherwise one line inside a long log.

## Build & verify

Requires JDK 17 and the Android SDK (platform 34, build-tools). `local.properties` (gitignored) must
point at the SDK, or set `ANDROID_HOME`.

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```

CI runs the same gate with `clean` in front of it, on JDK 17 plus the Android SDK it provisions itself
(A74, A75); see `.github/workflows/build.yml`.
