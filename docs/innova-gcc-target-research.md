# 2007 Toyota Innova 2.7 petrol (GCC) — research and package sweep

**Research date:** 2026-11-15

**Safety status:** fail closed. This note does not authorize a vehicle connection,
request, address, header, timing value, initialization sequence, PID, service, or
write operation.

## Result

| Item | Status | Evidence / limit |
|---|---|---|
| Vehicle family | **Supported by catalog evidence**: Innova/Kijang Innova, `TGN41L` / 2TR-FE family, GCC | The General-market EPC describes `TGN41L-GKPNKV` as `2TRFE`, LHD, `4FC`, GCC, CBU, with production beginning in 07.2005. A separate GCC catalog configuration, `TGN41L-GKPDKV`, covers 08.2007–08.2008 and identifies `2TRFE`, LHD, `4FC`, GCC, CBU. See [EPC configuration](https://toyota-general.epc-data.com/innova_kijang/tgn41l/4037/) and [08.2007 configuration](https://www.amayama.com/en/genuine-catalogs/epc/toyota-general/innova_kijang/TGN41L/15294/engine/8719). This supports the requested 2007 GCC 2.7-petrol / 2TR-FE family, but not a unique build configuration. |
| Exact configuration | **UNKNOWN** | A calendar year alone does not identify trim, transmission, build month, VIN/frame, or ECU calibration. In particular, the cited EPC configurations distinguish GL/DLX and `4FC` configurations. |
| ECU system type | **Engine control computer / ECM class only** | Toyota's catalog nomenclature calls this part class `COMPUTER, ENGINE CONTROL` (PNC `89661`). However, the directly found engine-control catalog record is for **Indonesian** `TGN41R`, `89661-0K150`, 12.2004–08.2007 — not the GCC target — so it is explicitly excluded as a target part number. See [the excluded Indonesian record](https://toyota-general.epc-data.com/innova_kijang/tgn41r/4038/chassis/8404/89661/). |
| Exact Toyota ECU part number | **UNKNOWN** | No source found binds a 2007 GCC `TGN41L` 2TR-FE configuration to a Toyota `89661-...` number. |
| Denso model / hardware / calibration | **UNKNOWN** | No target-specific ECU label, Denso number, calibration number, or VIN/frame lookup was found. |
| Diagnostic transport protocol | **UNKNOWN** | No source found that explicitly binds a 2007 GCC 2TR-FE Innova to CAN, ISO, KWP, ISO 9141, or another protocol. |

The result intentionally distinguishes an **engine-control ECU class** from a
verified ECU part number or a communications protocol. They are not
interchangeable facts.

## Evidence boundaries

The online parts catalogs are useful configuration evidence, not a substitute for
a VIN/frame-specific Toyota lookup or an ECU-label photograph. The target can be
narrowed to the GCC `TGN41L` / 2TR-FE family, but the following remain necessary
to identify a specific ECU:

1. the VIN/frame number or a clear production date;
2. trim and transmission; and
3. the engine ECU label, or a target-specific Toyota EPC/service-manual page
   that provides its `89661-...` number.

An apparent ISO 14230/M-OBD reference found during research was for a
**1KD/2KD diesel** Kijang Innova manual, not for this 2TR-FE petrol target. It
is therefore excluded rather than generalized. The same exclusion applies to
Toyota-by-year protocol lists and to related Hilux/Fortuner/Tacoma sources.

## Read-only Toyota/Lexus package sweep

### Identifier set

The sweep used the supported target identity and searched case-insensitively for
ASCII plus UTF-16LE/UTF-16BE forms of:

```text
innova, kijang, tgn41l, tgn41, an40, 2tr-fe, 2trfe, 89661,
iso 14230, kwp2000, iso 15765, iso 9141
```

`2tr` was retained only as a short lead; it is **not** sufficient to identify an
engine or a vehicle profile.

### Scope and method

The sweep was read-only and covered:

- 55 shipped, top-level package files (82,787,168 bytes), excluding repository
  documentation and test/source files;
- raw bytes of those files, including the proprietary `TOYOTA_EN.GGP` and
  `TCODEH_CN.GAG` containers;
- decoded payloads of all 12 top-level files that have the verified Toyota
  V10.01 `YZJM` wrapper; and
- all 1,395 non-directory members of `BIN.ZIP`; every member had that same
  verified V10.01 wrapper and was decoded before searching.

The existing V10.01 decoder was used only after it validated its wrapper
parameters. Its behavior and scope are documented in
[`connection-protocol-inventory.md`](connection-protocol-inventory.md).
No diagnostic transport was opened and no vehicle traffic was generated.

### Sweep results

1. **No strong target identifier matched** in the decoded top-level YZJM tables
   or in the decoded `BIN.ZIP` payloads. In particular, there was no
   `Innova`, `Kijang`, `TGN41(L)`, `AN40`, `2TRFE`, `89661`, or target protocol
   phrase that could be used to select a numeric vehicle-profile or system key.
2. The only decoded short-lead occurrence was `2tr` in `MENU.BIN` at payload
   offset `7,500,823`. It is not adjacent to `Innova`, `2TRFE`, a TGN model
   code, an ECU part number, or a decoded table schema that identifies it as an
   engine string. It is therefore not a target record.
3. Raw package libraries contain generic `KWP2000` capability strings (41
   occurrences across 21 libraries). Those describe package capability and are
   not associated with a target vehicle/profile identifier; they do **not**
   establish the Innova's protocol.
4. Raw `2tr` byte occurrences are likewise insufficient: they include a PDF,
   encrypted/compressed material, and library bytes. None forms the verified
   `2TRFE` target identifier or binds to the GCC Innova.
5. The human-readable vehicle/system labels remain in undecoded proprietary
   GGP/GAG resources. The absence of a clear-text match in those raw containers
   is **not** evidence that they do not contain the vehicle. It means the
   already-extracted numeric profile/system matrix cannot be safely joined to
   this target yet.

Consequently, the sweep produces **no target-associated package record** — no
vehicle-profile key, system/ECU key, entry selector, or protocol row can be
assigned to this Innova without guessing. The numeric protocol inventory must
remain unfiltered for this target until the package text resources are decoded
or a source provides a stable package identifier that can be independently
linked to the vehicle.

## Explicit non-results

This research and sweep do **not** verify any of the following:

- engine-ECU address, CAN identifier, K-line address, request header, baud
  rate, initialization method, timing, service, PID, security access, or
  actuator routine;
- a specific physical ECU unit, its connector, or its calibration; or
- any command suitable for use with an adapter or a live vehicle.

Those fields remain `UNKNOWN`, and the denial behavior required by
[`AGENTS.md`](../AGENTS.md) remains in force.
