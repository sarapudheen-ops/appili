# appili

Safety-critical Android OBD-II diagnostics for the Vgate vLinker MC+ adapter.
Pilot vehicle: 2007 Toyota Innova (GCC). **Read-only toward physical vehicles** — see
[`docs/PRODUCT_SCOPE.md`](docs/PRODUCT_SCOPE.md).

## Status

| Phase | State |
|---|---|
| Governance & safety docs | Complete — [`docs/`](docs) |
| Android skeleton + transport contracts | Implemented; **build gate not yet executed** (see [`docs/ASSUMPTIONS.md`](docs/ASSUMPTIONS.md) A9) |
| Adapter engine (ELM/STN), OBD/UDS parsing | Not started |

No Bluetooth permission is declared and no physical connection path exists: every vehicle-specific
field in [`docs/VEHICLE_MATRIX.md`](docs/VEHICLE_MATRIX.md) is still `UNKNOWN - PENDING_PHYSICAL_LOG`.

## Layout

```
app/src/main/java/com/innova/diag/
  core/transport/          # pure Kotlin: ByteTransport contract + TransportState (no Android imports)
  core/transport/fake/     # FakeByteTransport: in-memory, injected delays, split streams, drops
  ui/                      # Compose shell + ViewModel
app/src/test/java/com/innova/diag/core/transport/
                          # FakeByteTransport contract tests + raw byte fixtures
```

## Build & verify

Requires JDK 17 and the Android SDK (platform 34, build-tools). `local.properties` (gitignored) must
point at the SDK, or set `ANDROID_HOME`.

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug
```
