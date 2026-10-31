# appili

Safety-critical Android OBD-II diagnostics for the Vgate vLinker MC+ adapter.
Pilot vehicle: 2007 Toyota Innova (GCC). **Read-only toward physical vehicles** — see
[`docs/PRODUCT_SCOPE.md`](docs/PRODUCT_SCOPE.md).

## Status

| Phase | State |
|---|---|
| Governance & safety docs | Complete — [`docs/`](docs) |
| Android skeleton + transport contracts | Complete (build gate unrun — A9) |
| Bluetooth Classic RFCOMM transport | Implemented: socket only, no commands sent on connect |
| ELM adapter engine (channel + init) | Implemented: AT configuration only — **no PIDs, no headers** |
| OBD-II read-only decode (modes 01/03/07/09) | Implemented: decoders + request port; **no writes** (build gate unrun — A9) |
| Compose UI: connection, generic scan, live data, session export | Implemented: 177 JVM tests; gate and emulator run **unrun** (A9, A42) |
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
  platform/demo/           # demo-mode switch; the scripted transport itself lives in src/debug
  ui/                      # Compose: connection, generic scan, live data, raw console (debug only)
app/src/test/java/com/innova/diag/
  core/transport/          # FakeByteTransport fixtures + contract tests
  adapter/elm/             # transcript-replay tests: chunking, noise, error tokens, timeouts
  domain/obd/              # decoder tests: SAE J1979 vectors, DTC/VIN, absence-is-not-zero
  platform/bluetooth/      # transport/socket tests with mocked Android Bluetooth classes
  ui/                      # presentation tests: "never renders 0 for an absent value" and friends
  domain/session/          # export tests: well-formed JSON/CSV, VIN redaction, escaping
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

## Verification status

`./gradlew lintDebug testDebugUnitTest assembleDebug` has **not** been run: this repository was
authored in an environment with no JDK and no Android SDK (A9, A42), so the gate is **UNRUN, not
passed**, and the requested emulator run and screenshots could not be taken. What was verified
instead, statically and mechanically:

- 177 JVM test methods across 20 test classes, 50 of them new in this phase (`ui/`, `domain/session/`,
  `GenericScanTest`, `AdapterInfoReaderTest`, `RecordingCommandChannelTest`), all against fake
  transports and replay strings — never a device.
- A bracket/quote-aware scan of all 65 Kotlin sources: no unbalanced or mismatched delimiters.
- An identifier audit over `app/src/**/*.kt`: no unresolved capitalised reference.
- The demo transcript driven through a mirror of the channel → decoder → `Readings` pipeline, which
  reproduces exactly the values in the demo section above (including that `0105` is never written).

Treat the gate as the first thing to run on a machine with a JDK.

## Build & verify

Requires JDK 17 and the Android SDK (platform 34, build-tools). `local.properties` (gitignored) must
point at the SDK, or set `ANDROID_HOME`.

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```
