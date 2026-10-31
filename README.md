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
| UDS / manufacturer-specific modes | Not started — blocked by unverified vehicle matrix |

Opening an RFCOMM socket is transport plumbing: it sends zero bytes and assumes no protocol. Every
vehicle-specific field in [`docs/VEHICLE_MATRIX.md`](docs/VEHICLE_MATRIX.md) is still
`UNKNOWN - PENDING_PHYSICAL_LOG`, so no diagnostic operation is permitted yet.

## Layout

```
app/src/main/java/com/innova/diag/
  core/transport/          # pure Kotlin: ByteTransport contract + TransportState (no Android imports)
  domain/obd/              # pure Kotlin: DiagnosticResult, mode 01/03/07/09 decoders, ObdService, port
  adapter/elm/             # ElmChannel (command/response) + ElmInitializer + ElmObdCommandPort
  platform/bluetooth/      # RFCOMM transport, paired-device listing, permission provider
  ui/                      # Compose adapter screen (paired devices -> explicit select -> connect)
app/src/test/java/com/innova/diag/
  core/transport/          # FakeByteTransport fixtures + contract tests
  adapter/elm/             # transcript-replay tests: chunking, noise, error tokens, timeouts
  domain/obd/              # decoder tests: SAE J1979 vectors, DTC/VIN, absence-is-not-zero
  platform/bluetooth/      # transport/socket tests with mocked Android Bluetooth classes
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

## Build & verify

Requires JDK 17 and the Android SDK (platform 34, build-tools). `local.properties` (gitignored) must
point at the SDK, or set `ANDROID_HOME`.

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```
