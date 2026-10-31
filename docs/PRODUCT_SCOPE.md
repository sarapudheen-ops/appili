# PRODUCT_SCOPE.md — Functional Levels & Permanent Exclusions

**Status:** Normative. Binding on all code, tests, reviews, and release notes.
**Applies to:** Vgate vLinker MC+ adapter (Bluetooth Classic RFCOMM first), pilot vehicle 2007 Toyota Innova (GCC).
**Supersedes:** any statement in code, comment, test name, UI copy, or issue that contradicts this file.

This document defines the **only** functionality this product may ever perform. Anything not
explicitly permitted here is denied. When a behavior is ambiguous, the answer is **DENY**
(see [AGENTS.md](../AGENTS.md) Rule 1, *Fail-Closed Architecture*).

---

## 1. Level Model

The product is organized into four capability levels. Levels are **cumulative in UI visibility
only** — a level never inherits permission to write to a physical bus. Each level has an explicit
allow-list and an explicit deny-list. There is no implicit permission and no "miscellaneous"
service pass-through.

| Level | Name | Target | Can change vehicle state? | Default |
|---|---|---|---|---|
| 1 | Read-Only Generic OBD-II | Physical vehicle | **No** | Enabled |
| 2 | Read-Only Enhanced OEM | Physical vehicle | **No** | Disabled until matrix verified |
| 3 | Simulator-Only Active Tests | Internal simulator only | **No — simulator state only** | Disabled |
| 4 | Simulator-Only Coding | Internal simulator only | **No — simulator state only** | Disabled |

A level is **available** only if the vehicle matrix entry it depends on is marked `VERIFIED`
(see [VEHICLE_MATRIX.md](VEHICLE_MATRIX.md)). A level that depends on an
`UNKNOWN - PENDING_PHYSICAL_LOG` field is **unavailable**, not "best effort".

---

## 2. Level 1 — Read-Only Generic OBD-II

**Definition.** Standardized legislated OBD-II (SAE J1979 / ISO 15031-5) queries against a physical
vehicle over a **verified** transport and a **verified** protocol. Generic services only. No
manufacturer-specific interpretation is performed at this level.

### 2.1 Permitted operations

Only the following OBD-II services may ever be emitted at Level 1:

| Service | Name | Rationale |
|---|---|---|
| `0x01` | Show current data (live PIDs) | Read |
| `0x02` | Show freeze frame data | Read |
| `0x03` | Read stored emission-related DTCs | Read |
| `0x07` | Read pending DTCs | Read |
| `0x09` | Read vehicle information (VIN, CVN, calibration ID) | Read |
| `0x0A` | Read permanent DTCs | Read |

All responses must be decoded per the values actually reported in the ECU's supported-PID
bitmask (`0x00/0x20/0x40/…`). A PID that is not reported as supported is `UNSUPPORTED` — never
a zero, never a blank cell, never interpolated.

### 2.2 Explicitly denied at Level 1

| Service | Name | Why denied |
|---|---|---|
| `0x04` | Clear DTCs / reset readiness | Destructive write: erases freeze frames and readiness monitors |
| `0x05` | Oxygen sensor monitoring | Not required by scope; not enabled |
| `0x06` | On-board monitoring test results | Not required by scope; not enabled |
| `0x08` | Control operation (actuator test) | Active test — Level 3 territory, simulator only |
| `0x0B`–`0x0F` | Reserved / control | Undefined behavior — denied by default |

Level 1 must reach the vehicle **without unlocking any write path**. It must not require an
extended diagnostic session to function; if the adapter cannot return generic PIDs in the default
session, Level 1 is **unavailable**, not "promoted" to Level 2.

---

## 3. Level 2 — Read-Only Enhanced OEM

**Definition.** Manufacturer-specific **read** access to modules beyond the generic engine ECU
(e.g. body, ABS, SRS, cluster, A/C), using OEM data identifiers and OEM diagnostic addressing.

### 3.1 Permitted operations

| Service | Name | Condition |
|---|---|---|
| `0x22` | ReadDataByIdentifier | DID must be listed as `VERIFIED` in the vehicle matrix |
| `0x19` | ReadDTCInformation | Sub-functions limited to read/report only |
| `0x1A` | ReadEcuIdentification (where supported) | ECU must be `VERIFIED` |
| `0x3E` | TesterPresent | Keep-alive only; suppression bit permitted |
| `0x10` | DiagnosticSessionControl | **Only** to a session explicitly declared non-write-capable in the verified matrix |

`0x10` is accepted at Level 2 solely because some modules gate *reads* behind a non-default
session. A session may be entered at Level 2 **only** if the matrix entry states it does not
enable any write, routine, or actuator service. If that is unknown, `0x10` is denied.

### 3.2 Explicitly denied at Level 2

| Service | Name | Why denied |
|---|---|---|
| `0x27` | SecurityAccess (seed/key) | Exists only to unlock write/routine paths. Denied at every level below 4, and at Level 4 only inside the simulator. |
| `0x2E` | WriteDataByIdentifier | Write |
| `0x2F` | InputOutputControlByIdentifier | Active test — Level 3, simulator only |
| `0x31` | RoutineControl | Level 3/4, simulator only |
| `0x3D` | WriteMemoryByAddress | Arbitrary memory write |
| `0x23` | ReadMemoryByAddress | Arbitrary memory read by composed address — unverified byte access |
| `0x34`–`0x38` | RequestDownload / RequestUpload / TransferData / RequestTransferExit / RequestFileTransfer | Flashing primitives — permanently out of scope (§6 P1) |
| `0x11` | ECUReset | State-changing, not read |
| `0x28` | CommunicationControl | Can silence modules — hazard |
| `0x85` | ControlDTCSetting | Can suppress fault reporting — hazard |
| `0x2A` / `0x2C` | ReadDataByPeriodicIdentifier / DynamicallyDefineDataIdentifier | Composed-address reads and scheduled streaming outside the typed command set |

Level 2 must never accept a DID, ECU address, header, or timing value that is absent from the
verified matrix. There is no runtime "try it and see".

---

## 4. Level 3 — Simulator-Only Active Tests

**Definition.** Operations that change *observable behavior* — actuator tests, output control,
routines, and forced states — executed **exclusively** against the internal simulator.

Permitted only in the simulator:
- `0x2F` InputOutputControlByIdentifier (short/long-term adjustment, return-to-ECU-control)
- `0x31` RoutineControl (start / stop / request results) for routines designated non-persistent
- `0x08` / Mode 08-style legacy actuator tests
- Bi-directional forced states on simulated sensors and outputs (fan, injector pulse width,
  gauge sweep, relay state, solenoid duty)

### 4.1 Hard wiring constraint

Level 3 capability must be **unreachable from a physical transport by construction**, not by a
flag check at the call site:

1. Active-test commands are bound to a simulator-only domain interface. The physical transport
   implementation must not satisfy that interface.
2. The dependency graph must make it impossible to construct an active-test use case with a
   physical `ByteStream` — this is a compile-time/architecture guarantee, not a runtime guard.
3. No `BuildConfig` flag, debug toggle, developer menu, intent extra, deep link, or ADB command
   may connect Level 3/4 to a physical adapter. There is no "engineering mode" exception.

### 4.2 Denied at Level 3

Everything in §3.2 remains denied. Level 3 grants **simulator behavior only** — it never grants
memory writes, `0x27`, flashing primitives, or any of §6.

---

## 5. Level 4 — Simulator-Only Coding

**Definition.** Configuration and adaptation changes — variant coding, long coding, adaptation
values, parameter sets — executed **exclusively** against the internal simulator, and only within
the transaction state machine in [SAFETY_POLICY.md](SAFETY_POLICY.md).

Permitted only in the simulator:
- `0x2E` WriteDataByIdentifier against a whitelisted simulated DID set
- Adaptation-channel writes (e.g. coding bytes for lighting, comfort, cluster units)
- Variant / long-coding blocks with a verified backup and read-back verification
- Restore-from-backup of simulated coding

### 5.1 Hard wiring constraint

Identical to §4.1: simulator-only by architecture. In addition, Level 4 requires:

1. A **verified backup** captured and read back before `USER_CONFIRMED` may be reached.
2. A **read-back verification** after transmission; mismatch ⇒ `VERIFY_FAILED` + rollback.
3. Only DIDs present on an explicit allow-list are writable. Allow-lists are never generated at
   runtime and never inferred from a response.

### 5.2 Denied at Level 4

All permanent exclusions in §6, and all of §3.2 except `0x2E`/coding primitives — in the simulator
only. `0x27` is permitted **only** inside the simulator, against simulated seeds, and must never
be paired with a physical transport object.

---

## 6. PERMANENTLY OUT OF SCOPE

The following are **PERMANENTLY OUT OF SCOPE**. This is a product-identity statement, not a
roadmap gap and not a deferred backlog item.

| # | Excluded capability | Why permanently excluded |
|---|---|---|
| **P1** | **ECU Flashing / Reprogramming / Reflashing** (bootloader entry, `0x34/0x36/0x37`, calibration or firmware download, seed-key-based flash unlock) | A failed or interrupted flash can brick safety-critical modules and disable the vehicle. Irreversible, high-blast-radius, and cannot be made fail-closed within a phone-to-BT-adapter link. |
| **P2** | **Immobilizer / Key Programming** (transponder programming, key add/erase, PIN/SECURITY code reading, immobilizer reset or pairing, smart-key registration) | Directly enables vehicle theft and defeats an anti-theft system. Also legally restricted in many jurisdictions. |
| **P3** | **Airbag Crash Reset** (SRS crash-data clearing, crash-counter reset, airbag-module "repair", seat-belt pretensioner clearing, emulator/simulator dongles for SRS) | Restores a vehicle to service while safety systems may be non-functional. Risk of death or serious injury in a subsequent collision. |
| **P4** | **Odometer Alteration** (mileage correction, cluster mileage write, EEPROM mileage edit, "mileage calibration", rollback) | Odometer fraud. Illegal in essentially every market, including the GCC pilot market, and facilitates consumer deception. |

### 6.1 No exceptions

For P1–P4, the following are also denied and must never be implemented:

- No "advanced", "professional", "expert", "service", or "beta" mode that performs them.
- No simulator implementation of P1–P4. Simulator code is reachable by copy-paste and by
  refactoring mistakes; a working implementation of an excluded capability is a defect even if
  currently unreachable.
- No raw AT / diagnostic terminal capable of composing them (release builds forbid raw console
  entirely; debug console is guarded by `BuildConfig.DEBUG` and disabled by default).
- No "send arbitrary command" / "custom PID" / "hex payload" feature — arbitrary byte emission is
  the common substrate of P1–P4.
- No import/export, scripting, plugin, or remote-config path that could introduce them.
- No documentation, screenshot, string resource, or UI affordance that suggests they exist.

### 6.2 Adjacent capabilities refused for the same reasons

To prevent scope creep through re-naming, the following are also out of scope and require an
explicit, written amendment to this file before any work begins:

- Emissions defeat: DPF/GPF regeneration suppression or delete, EGR delete, AdBlue/SCR disable,
  catalyst monitor masking, readiness-monitor forced-pass.
- `0x85` ControlDTCSetting abuse to hide faults for inspection.
- VIN rewriting or cloning; component protection removal; ECU identity spoofing.
- Ghost immobilizer / CAN injection / keyless-relay attack tooling.
- Any persistent write to a physical module for any reason, including "just a small setting".

---

## 7. Level Gating Summary

| Capability | L1 | L2 | L3 | L4 | Physical | Simulator |
|---|---|---|---|---|---|---|
| Generic PIDs (01/02/03/07/09/0A) | ✅ | ✅ | ✅ | ✅ | ✅ read | ✅ |
| OEM DIDs (22) | — | ✅ verified only | ✅ | ✅ | ✅ read | ✅ |
| Session control (10) | ❌ | ⚠️ non-write sessions only | ✅ | ✅ | ⚠️ | ✅ |
| SecurityAccess (27) | ❌ | ❌ | ❌ | ✅ sim only | ❌ | ✅ sim |
| Actuator test (2F / 08) | ❌ | ❌ | ✅ | ✅ | ❌ **never** | ✅ |
| RoutineControl (31) | ❌ | ❌ | ✅ | ✅ | ❌ **never** | ✅ |
| Coding write (2E) | ❌ | ❌ | ❌ | ✅ | ❌ **never** | ✅ |
| DTC clear (04) | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| P1 Flashing | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| P2 Immobilizer | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| P3 Airbag reset | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |
| P4 Odometer | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

Legend: ✅ permitted · ⚠️ permitted under stated condition · ❌ denied.

**Invariant:** the "Physical" column for every write/active capability is ❌ at every level.
The only physical-vehicle capability this product will ever ship is **reading data**.

---

## 8. Scope Changes

This file is normative. Any change to a level's allow-list requires:

1. A written amendment to this document in the same PR.
2. An entry in [ASSUMPTIONS.md](ASSUMPTIONS.md) for every new unverified dependency.
3. A new row in [VEHICLE_MATRIX.md](VEHICLE_MATRIX.md) promoted to `VERIFIED` with captured
   evidence, before any code path can use it.

Changing §6 is not permitted by a normal PR. P1–P4 are removed from the product, not gated.
