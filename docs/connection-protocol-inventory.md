# Toyota/Lexus connection-protocol inventory

## Result and scope

This repository is a **Toyota/Lexus diagnostic data package**, not source code for a
vehicle-protocol matrix. The package identifies itself as Toyota/Lexus V10.62 in
`HINI_EN.HTML`; its data containers are labelled `TOYOTA` and most of the binary
tables are labelled V10.01.

I was able to extract the connection modes implemented by the Toyota system-entry
module and the protocol-transport capabilities that support them. I could **not
truthfully assign one of those modes to each vehicle, model year, or individual
ECU** from the files currently present. The vehicle/ECU-to-mode relation is held
inside proprietary `YZJM` binary tables and is not available as plaintext or as a
self-contained, decodable schema in this checkout.

This distinction is important: a mode appearing in the native library means that
this package can use that mode. It does not prove that every ECU—or any particular
vehicle—uses it.

## Extracted system-entry connection modes

The following entry-mode functions are exported by `libTOYOTA_SYSP.so` and are the
strongest direct evidence of the Toyota-specific connection modes in this package.

| Mode exposed by the package | Evidence | What can safely be concluded |
|---|---|---|
| **CAN bus** | `CANBUSEnterMode`; `CANBUSCheckEcuConnect` in `libTOYOTA_FDATA.so` | A Toyota ECU entry path over CAN is implemented. |
| **Fast KWP** | `FastKWPEnterMode`; `FastKWPCheckEcuConnect` | A distinct fast-KWP entry path is implemented. |
| **ISO** | `ISOEnterMode`; `ISOCheckEcuConnect` | A distinct ISO entry path is implemented. The files do not identify its per-vehicle physical/timing variant. |
| **VPW** | `VPWEnterMode` plus `VPW_*` transport routines | A VPW entry path is implemented. |
| **DESO** | `DESOEnterMode` | A separate vendor-specific entry mode exists under this exact internal name. Its expansion and vehicle/ECU assignments are not exposed by the repository. |

### Supporting transport capabilities

These symbols are present in the Toyota libraries, but should be treated as
**capabilities**, not per-vehicle assignments:

| Capability | Evidence | Classification |
|---|---|---|
| CAN multi-frame transport | `ISO15765SendAndReceiveMultiFrame`, `SetCan15765`, `Can*Send*Frame*` | CAN transport capability; `ISO15765` appears as a multi-frame transport routine. |
| KWP request handling | `ToyotaKwpSendDataToEcuGetMultiFrameAnswer`, `KWP2000_SendDataToEcuGetAnswer`, `Kwp*` | KWP/KWP2000 service and transport capability. |
| ISO/KWP line setup | `InitKWPISOMode`, `ISOEnterMode`, `FastKWPEnterMode` | Initialization/entry capability; no physical-layer parameters are exposed in plaintext. |
| L-line support | `EnableLLine`, `AddressCodeCommunicationWay_Lline` | Optional L-line-related handling exists; it is not enough to assign an ECU to an L-line configuration. |
| CAN setup variants | `InitCanBusMode`, `InitExtendCanbusMode`, `SetCanComPara`, `SetCan15765` | CAN configuration support; bitrate, identifier format, and ECU address are not recoverable from the available plaintext. |

## ECU/vehicle coverage visible in the package

`HINI_EN.HTML` identifies the covered ECU families, including Engine, Vehicle
Control, Transmission, ABS, Electric Parking Brake, SRS, Hybrid Control, Air
Conditioner, Body, Smart Key, TPMS, Gateway, radar/camera ADAS systems, EV/HV
Battery, Combination Meter, Telematics, Navigation, and many others.

The package includes regional ECU-data payloads in `BIN.ZIP`:

| Region directory | `Ecu_Data_*.BIN` files |
|---|---:|
| EU | 477 |
| NA | 421 |
| JP | 500 |

Those 1,398 files are **payload files, not verified unique ECU records**. For
example, an identically named ECU payload can occur in multiple regions. Their
names do not include a vehicle, ECU label, or protocol.

## Why a per-vehicle/per-ECU protocol matrix cannot be produced from this checkout

1. The relevant binary tables—`SYSLIST_*.BIN`, `SYSTEMENTER.BIN`,
   `FUNCDATA_*.BIN`, `ECUPROGRAM.BIN`, and the archived ECU payloads—use the
   proprietary `05 00 59 5A 4A 4D` (`YZJM`) container form after a small cleartext
   product header. They do not contain readable vehicle/ECU/protocol records.
2. `libTOYOTA_FILE.so` parses system-list records through indirection points named
   `fopenEx`, `fseekEx`, and `freadEx`. In this repository they are exported as
   pointer objects, rather than implementations that can be run to materialize the
   records. The surrounding host/application that initializes them is not present.
3. The package does include generic resource helpers such as `SearchIdOpenFile`,
   `FromIdGetBinaryString`, and GGP/GAG readers, but their directly recognized
   `WCX`/`LAH` formats do not decode the supplied `YZJM` tables into a usable
   vehicle/ECU mapping on their own.
4. The native code establishes the available entry modes, but neither the HTML nor
   cleartext metadata binds an ECU name or vehicle application to one of those mode
   selectors.

Therefore this repository supports an **evidence-based protocol inventory**, not a
safe protocol matrix. Assigning CAN, ISO, Fast KWP, VPW, or DESO to a specific ECU
without the missing runtime/container decoder would be a guess.

## What is needed to finish the requested one-to-one extraction

Provide one of the following from the diagnostic application's matching runtime:

- the Android APK or native component that initializes `fopenEx`, `fseekEx`,
  `freadEx`, and `fcloseEx` for the `YZJM` data containers; or
- an exported/decoded system list containing, at minimum, vehicle identifier,
  model year/market, ECU name or system ID, and the entry-mode value; or
- a captured **read-only** system-list load from the app with the decoded records.

With that input, the final matrix can be emitted as:

```text
market | vehicle | model year/range | ECU/system | entry mode | transport | evidence/source
```

## Safety boundary

No ECU addresses, headers, initialization bytes, timing values, or write commands
were inferred. In particular, `AGENTS.md` marks the 2007 Toyota Innova GCC
engine/ECU/protocol as **UNKNOWN until verified**; this inventory does not change
that status.
