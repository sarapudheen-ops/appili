# 2007 Toyota Innova 2.7 petrol (GCC) — TGN41L research and diagnostic-field inventory

**Research / package-sweep date:** 2026-11-15
**Target stated by requester:** 2007 Toyota Innova, 2.7 L four-cylinder petrol,
GCC/Middle East.

> **Safety status — fail closed:** This record does **not** authorize a vehicle
> connection or provide a usable request. No diagnostic traffic, request or
> response bytes, write operation, address, header, timing value, initialization
> sequence, service, PID, security procedure, or actuator control is supplied.
> Every unverified field is deliberately recorded as `UNKNOWN`.

## Executive result

The evidence supports the requested vehicle **family** as first-generation
Innova/Kijang `TGN41L` with the 2TR-FE petrol engine in a GCC/LHD/CBU
configuration. It does not identify the exact 2007 configuration, engine ECU,
or any target-specific diagnostic-communications parameter.

A complete read-only package sweep found **no decoded identifier that joins the
GCC TGN41L / 2TR-FE target to any package vehicle-profile key, ECU/system key,
connection selector, or command data**. Therefore no package-wide transport
capability (including generic `KWP2000`, CAN, ISO, or VPW strings) can be
assigned to this vehicle.

## Vehicle and ECU identity boundary

| Item | Result | Evidence / limit |
|---|---|---|
| Vehicle family | **Supported:** Innova/Kijang Innova `TGN41L` / 2TR-FE, GCC | The General-market EPC describes `TGN41L-GKPNKV` as 2TRFE, LHD, `4FC`, GCC and CBU, with production starting 07.2005 ([EPC configuration](https://toyota-general.epc-data.com/innova_kijang/tgn41l/4037/)). A separate GCC configuration, `TGN41L-GKPDKV`, covers 08.2007–08.2008 and identifies 2TRFE, LHD, `4FC`, GCC and CBU ([EPC configuration](https://www.amayama.com/en/genuine-catalogs/epc/toyota-general/innova_kijang/TGN41L/15294/engine/8719)). |
| Possible 2007 variants | **More than one** | The cited `GKPNKV` configuration begins 07.2005; the cited `GKPDKV` configuration begins 08.2007. A 2007 calendar year by itself cannot choose GL versus DLX or prove exact build month, transmission, trim, or calibration. |
| Engine-control ECU class | **Engine-control computer/ECM class only** | Toyota uses PNC `89661` for `COMPUTER, ENGINE CONTROL`. This is a part class, not a target-specific part number or a communication profile. |
| Exact Toyota ECU part number | **UNKNOWN** | No source found ties a `89661-...` part number to the 2007 GCC `TGN41L` / 2TR-FE target. `89661-0K150` is documented for an **Indonesian** TGN41R 2TR-FE application (12.2004–08.2007), so it is expressly excluded rather than transferred to GCC `TGN41L` ([excluded sibling record](https://toyota-general.epc-data.com/innova_kijang/tgn41r/4038/chassis/8404/89661/)). |
| Denso model, ECU hardware, software/calibration | **UNKNOWN** | No target ECU label, Denso number, calibration label, or VIN/frame-specific EPC lookup was found. |

The engine-control ECU **class** does not establish a particular ECU, transport,
or communication parameter. A year/model-family match also does not establish
an ECU calibration.

## Complete diagnostic-communication inventory

The following inventory is deliberately comprehensive for the requested scope.
`UNKNOWN` means that no source obtained in this research binds the field to the
specified 2007 GCC TGN41L/2TR-FE target. It is not a placeholder for an assumed
Toyota default and must not be turned into a live-vehicle action.

| Field group | Field | Status | Evidence boundary / disposition |
|---|---|---|---|
| Scope | Exact vehicle configuration | **UNKNOWN** | VIN/frame, build month, trim, transmission, and ECU label not supplied. Multiple GCC configurations can fall in 2007. |
| Scope | Target engine | **Supported at family level** | 2TR-FE is documented for cited GCC TGN41L configurations; exact ECU calibration remains unknown. |
| ECU identity | Toyota `89661-...` number | **UNKNOWN** | Indonesian TGN41R number is excluded; no GCC-TGN41L match found. |
| ECU identity | Denso number, hardware family, calibration/software ID | **UNKNOWN** | No target ECU label or target-bound technical data found. |
| Physical access | DLC/DLC3 pinout, diagnostic line, ground/power arrangement | **UNKNOWN** | No target-bound pinout or wiring source was found; no connection was made. |
| Transport | Bus/transport protocol | **UNKNOWN** | No source binds CAN, ISO 9141, ISO 14230/KWP, SAE J1850 VPW, or another transport to this exact target. |
| Transport | Physical layer and bus speed/baud rate | **UNKNOWN** | No target-bound evidence. |
| Addressing | Functional or physical addressing scheme | **UNKNOWN** | No target-bound evidence. |
| Addressing | CAN arbitration ID(s), K-line address(es), ECU address(es) | **UNKNOWN** | No target-bound evidence. |
| Headers | Request header, response header, addressing/header bytes | **UNKNOWN** | No target-bound evidence. |
| Initialization | Wake-up, key-cycle, slow/fast init, session start, keep-alive | **UNKNOWN** | No target-bound evidence. |
| Timing | Init timing, inter-byte/inter-frame delay, P2/P3/timeout/retry values | **UNKNOWN** | No target-bound evidence. |
| Traffic | Request/response frames, byte layout, checksum, flow control, trace/capture | **UNKNOWN** | No target-bound evidence and no live traffic was generated or captured. |
| Services | Diagnostic service IDs, subfunctions, sessions, DTC functions | **UNKNOWN** | No target-bound evidence. Generic package text is not a target service table. |
| Data identifiers | OBD modes, PIDs, Toyota data IDs, scaling/units | **UNKNOWN** | No target-bound evidence. |
| Read behavior | Supported ECU information, live-data, freeze-frame, DTC reads | **UNKNOWN** | No target-bound evidence. |
| Security | Security access level, seed/key algorithm, unlock/session procedure | **UNKNOWN** | No target-bound evidence. |
| Routines/controls | Actuator tests, routines, calibrations, relearns | **UNKNOWN** | No target-bound evidence. |
| Writes | Coding, configuration, ECU flashing, memory/write-data operations | **UNKNOWN** | No target-bound evidence. No write request is documented or permitted by this record. |
| Writes | Preconditions, safeguards, recovery/rollback | **UNKNOWN** | No target-bound evidence. |

### What is explicitly *not* transferable

The following were encountered or are commonly suggested but are rejected as
proof for the target:

- ISO 14230/M-OBD material for a **1KD/2KD diesel** Innova manual;
- generic Toyota-by-year protocol tables;
- shared-platform Hilux, Fortuner, Tacoma, later-Innova, 1TR-FE, or 2.0 L data;
- Indonesian TGN41R `89661-0K150` engine-control-computer catalog data;
- a raw `2tr` byte sequence; and
- generic transport/service/write strings from the software package.

None creates a valid TGN41L GCC 2TR-FE diagnostic assignment.

## Read-only package sweep

### Reproducible implementation

The repository includes a non-mutating scanner at
[`tools/scan_toyota_package_identifiers.py`](../tools/scan_toyota_package_identifiers.py).
It searches case-insensitively for ASCII, UTF-16LE, and UTF-16BE forms and has
no transport or adapter code. It reads top-level files and `BIN.ZIP`, decodes a
file only after the existing decoder validates the exact Toyota V10.01 `YZJM`
wrapper, and writes only its selected JSON report path.

The target run was performed as follows (report written outside the repository):

```bash
python3 tools/scan_toyota_package_identifiers.py \
  --term innova --term kijang --term tgn41l --term tgn41 --term tgn4 \
  --term an40 --term 2tr-fe --term 2trfe --term 89661 \
  --output /tmp/tgn41l-target-identifier-sweep.json
```

A separate capability-lead scan searched the non-target terms `kwp2000`,
`iso 14230`, `iso 15765`, `iso 9141`, `can`, `vpw`, `header`, `address`,
`timing`, `initialization`, `service`, and `pid`. These terms are investigative
leads only; they cannot assign a parameter absent a target identifier join.

### Scope and integrity

| Scope item | Result |
|---|---|
| Top-level package files read | 55 files; 82,787,168 bytes |
| Verified top-level YZJM files decoded | 12, all marked `TOYOTA` / `V10.01` by the existing validated decoder |
| `BIN.ZIP` members read | 1,395 non-directory members |
| Verified `BIN.ZIP` members decoded | 1,395 |
| Source archive SHA-256 | `438004d6e10b41133d494925b4fc98cb76b09f55a7b28f6c6865aa7c226607eb` (`BIN.ZIP`) |
| Proprietary label-resource SHA-256 | `231098c9cb40e7ea97ab8ca630c97c375bb5cefe2b163abab9050bb8c4f90923` (`TOYOTA_EN.GGP`); `749d2543c6ecd4f54ffe1182575243a4b17ba834e1c1cd33420a02707ab4ea15` (`TCODEH_CN.GAG`) |
| Vehicle communication | None. The analysis read package data only; it did not open a diagnostic transport or generate traffic. |

### Target-identifer result

| Search surface | `innova`, `kijang`, `tgn41l`, `tgn41`, `tgn4`, `an40`, `2tr-fe`, `2trfe`, `89661` |
|---|---|
| Raw top-level package files and decompressed `BIN.ZIP` member bytes | **0 matches** |
| Decoded top-level V10.01 YZJM payloads and decoded `BIN.ZIP` payloads | **0 matches** |

The narrow lead `2tr` returned 16 raw hits in eight package sources and **one**
decoded hit: `MENU.BIN`, payload offset `7,500,823`. It has no adjacent
Innova/Kijang/TGN identifier, 2TRFE string, `89661` number, or decoded schema
linking it to an engine or vehicle profile. It is not a target record.

The capability-lead scan found package-wide strings (for example, 41 raw
`kwp2000` hits across 21 libraries, and generic CAN/VPW terminology). Decoded
payloads had only generic `can` and `vpw` text hits, with no TGN41L identity.
These results demonstrate that the application has broad multi-vehicle
capabilities; they do **not** identify the target transport, header, address,
timing, service, PID, traffic, or write behavior.

The human-readable labels required to join numeric package records to vehicles
remain undecoded inside proprietary GGP/GAG resources. A clear-text miss in a
raw container is not proof that a label is absent; it is only proof that a safe,
decoded target-to-key join has not been established.

## Why the numeric protocol matrix cannot be used for this vehicle

The existing extractor can reliably generate an all-package table of numeric
profile/system rows and their connection selectors:

```bash
python3 tools/extract_toyota_protocols.py \
  --output /tmp/toyota-system-protocols.csv \
  --summary /tmp/toyota-system-protocol-summary.json
```

Its latest package-wide total is 287,030 system rows: 49,750 selector-1 Fast
KWP rows, 10 selector-2 ISO rows, 236,066 selector-3/4 CAN rows, 27 selector-5
Fast-KWP-then-ISO fallback rows, 294 selector-10 VPW rows, two selector-6
custom rows, and 881 unmapped selector entries. Those are **package-wide numeric
records**, not TGN41L records. Because this sweep found no TGN41L-to-profile or
TGN41L-to-system join, selecting any row for the target would be inference and
is prohibited.

See [`connection-protocol-inventory.md`](connection-protocol-inventory.md) for
the decoder’s verified selector semantics and its limits.

## Evidence needed to replace `UNKNOWN`

To narrow the inventory safely, obtain one or more of the following, then
re-evaluate only when the source is demonstrably for this vehicle:

1. VIN/frame number and production month, plus a Toyota General/GCC EPC result
   that identifies the engine ECU `89661-...` part number;
2. a clear, legible photograph of the installed engine ECU label showing Toyota
   and Denso numbers/calibration; and
3. a 2007 GCC TGN41L / 2TR-FE Toyota wiring diagram or service-manual section
   that explicitly maps the ECU and diagnostic communication properties.

Until then, all operational diagnostic fields in this document remain
`UNKNOWN`; the fail-closed requirements in [`AGENTS.md`](../AGENTS.md) control.
