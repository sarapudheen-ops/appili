# VEHICLE_MATRIX.md — Verified Vehicle Capability Matrix

**Status:** Normative. Single source of truth for every vehicle-specific value.
**Pilot vehicle:** 2007 Toyota Innova (GCC market).
**Adapter:** Vgate vLinker MC+ (Bluetooth Classic RFCOMM first).

> **Fail-closed rule:** a field is usable only when its status is `VERIFIED`.
> `UNKNOWN - PENDING_PHYSICAL_LOG` means **no code path may use it** — not a fallback, not a
> default, not "try ISO 9141-2 first". An unknown field denies the operation
> ([AGENTS.md](../AGENTS.md) Rule 1, [PRODUCT_SCOPE.md](PRODUCT_SCOPE.md) §1).

---

## 1. Current Matrix — 2007 Toyota Innova (GCC)

### 1.1 Vehicle identity

| Field | Value | Status |
|---|---|---|
| Make | Toyota | RECORDED — owner documentation, not log-verified |
| Model | Innova | RECORDED — owner documentation, not log-verified |
| Model year | 2007 | RECORDED — owner documentation, not log-verified |
| Market / spec | GCC | RECORDED — owner documentation, not log-verified |
| VIN | *(not captured — do not invent)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |
| Engine code / displacement | *(not captured — do not invent)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |
| Transmission | *(not captured — do not invent)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |

### 1.2 Required diagnostic fields

| Field | Value | Status |
|---|---|---|
| **Engine** | *(not captured)* | **`UNKNOWN - PENDING_PHYSICAL_LOG`** |
| **Protocol** | *(not captured)* | **`UNKNOWN - PENDING_PHYSICAL_LOG`** |
| **ECU Address** | *(not captured)* | **`UNKNOWN - PENDING_PHYSICAL_LOG`** |
| **Header** | *(not captured)* | **`UNKNOWN - PENDING_PHYSICAL_LOG`** |
| **Init Sequence** | *(not captured)* | **`UNKNOWN - PENDING_PHYSICAL_LOG`** |

### 1.3 Adjacent fields required before any Level 2 work

| Field | Value | Status |
|---|---|---|
| Pin / bus assignment (OBD-II connector) | *(not captured)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |
| Baud / timing parameters (P1–P4, keep-alive interval) | *(not captured)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |
| Generic supported-PID bitmask (01 00 / 01 20 / 01 40) | *(not captured)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |
| Enhanced DIDs per module | *(not captured)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |
| Additional modules (ABS / SRS / body / cluster) addressing | *(not captured)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |
| Adapter firmware version & AT/ST command set behaviour | *(not captured)* | `UNKNOWN - PENDING_PHYSICAL_LOG` |

**Deliberate omission:** no candidate protocol, header, address, or init sequence is listed as a
"likely" or "probable" value. Naming a candidate invites it becoming a default. There are zero
guesses in this document by design.

---

## 2. Canonical Machine-Readable Form

The table above is the human view. Any tooling that consumes the matrix must read this block, and
must treat every value that is not `VERIFIED` as a hard block.

```yaml
matrix_version: 0
last_updated: 2026-10-31
entries:
  - id: INNOVA_2007_GCC
    vehicle:
      make: { value: "Toyota", status: RECORDED }
      model: { value: "Innova", status: RECORDED }
      model_year: { value: 2007, status: RECORDED }
      market: { value: "GCC", status: RECORDED }
      vin: { value: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
    engine:        { value: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
    protocol:      { value: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
    ecu_address:   { value: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
    header:        { value: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
    init_sequence: { value: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
    timing:        { value: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
    modules:
      engine_ecu:  { address: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
      abs:         { address: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
      srs:         { address: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
      body:        { address: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
      cluster:     { address: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
adapter:
  model: { value: "Vgate vLinker MC+", status: RECORDED }
  transport: { value: "Bluetooth Classic RFCOMM", status: RECORDED }
  firmware: { value: null, status: UNKNOWN_PENDING_PHYSICAL_LOG }
```

Status vocabulary — exactly these six values, no free text:

| Status | Meaning | Usable? |
|---|---|---|
| `VERIFIED` | Captured from a physical log, reviewed, evidence committed. | ✅ Yes |
| `UNKNOWN - PENDING_PHYSICAL_LOG` | Not yet observed. | ❌ No — deny |
| `UNSUPPORTED` | Confirmed absent on this vehicle. | ❌ No — deny |
| `MALFORMED` | Observed but the capture was corrupt/ambiguous. | ❌ No — re-capture |
| `DEPRECATED` | Superseded by a newer entry for the same field. | ❌ No |
| `RECORDED` | Documented fact (owner/manual), **not** log-verified. Usable for display only, never for bus access. | ⚠️ Display only |

`RECORDED` is intentionally weaker than `VERIFIED`. Make/model/year/market may be shown in the UI;
they may never be used to choose a protocol or header.

---

## 3. Effect on the Product Right Now

All five required fields are `UNKNOWN - PENDING_PHYSICAL_LOG`. Therefore:

| Consequence | State |
|---|---|
| Level 1 on a **physical vehicle** | **UNAVAILABLE** — protocol/init/header unverified |
| Level 2 on a physical vehicle | **UNAVAILABLE** |
| Level 3 / Level 4 | **UNAVAILABLE** — simulator not in scope yet; and never bound to physical anyway |
| Simulator operation | **UNAVAILABLE** — no simulator exists in this phase |
| Any connection attempt to the physical adapter | Must be **denied** with the specific unmet field named in the message |

This is the intended outcome of the fail-closed design, not a bug. The application must not
"try" a connection to discover the answer; discovery happens through a deliberate, logged
physical capture procedure (§4) and lands here as verified data.

---

## 4. Promotion Procedure: UNKNOWN → VERIFIED

No field may be promoted by reasoning, inference, forum post, or manufacturer folklore. Promotion
requires a committed raw capture.

### 4.1 Capture requirements

1. **Raw log, unmodified.** Capture the full AT/diagnostic hex exchange from connect to
   disconnect, including adapter banner and command echoes. Store under `docs/captures/`
   with the exact filename referenced in the row (§4.3).
2. **Record context**: date, adapter model + firmware, connection type, ignition state
   (KOEO/KOER), battery voltage, ambient conditions if relevant, app/commit hash used.
3. **Capture twice.** Two independent sessions on separate power cycles must agree. A single
   session is `MALFORMED`, not `VERIFIED`.
4. **Let the adapter report.** Protocol must be read from the adapter's own reported result
   (`ATDP` / auto-detect output) and the raw request/response bytes — never set by assumption,
   never inferred from a single successful PID.
5. **Never record inferred values.** If the log does not contain a header, the header stays
   `UNKNOWN - PENDING_PHYSICAL_LOG`. Absence of evidence is not evidence of a value.
6. **Review.** A second reviewer confirms the field value is present verbatim in the capture
   before the status flips to `VERIFIED`.
7. **Evidence pointer.** Each promoted row must cite the capture file and the byte offset or
   line range that proves the value.

### 4.2 Restrictions during capture

Capture is **read-only** at all times
([PRODUCT_SCOPE.md](PRODUCT_SCOPE.md) §6): generic read services only, no session control, no
`0x27`, no routines, no actuator tests, no writes of any kind, and no raw-console feature shipped
to reach the vehicle. Capture uses an external, independent tool (or the debug-guarded console,
disabled by default) — never a release code path.

### 4.3 Promotion record template

```
Field:            <Engine | Protocol | ECU Address | Header | Init Sequence | ...>
Value:            <verbatim from capture>
Status:           VERIFIED
Evidence:         docs/captures/<file>.log  lines <a>–<b>
Sessions:         2 (identical)
Reviewed by:      <name/role>
Date:             <YYYY-MM-DD>
Matrix version:   <n+1>
```

### 4.4 Invalidation

A `VERIFIED` field returns to `UNKNOWN - PENDING_PHYSICAL_LOG` if: a capture is found corrupt, the
vehicle variant differs (different trim/year/engine), the adapter firmware changes materially, or a
same-VIN capture contradicts the record. Downgrade immediately; do not leave a stale value usable.

---

## 5. Future Vehicles

Add one `entries:` item per vehicle, keyed by a stable id, with the same field set and the same
status vocabulary. Do **not** generalize a value from one vehicle to another: a shared platform,
shared engine family, or shared market is not evidence. Each entry stands alone until verified
from its own capture.
