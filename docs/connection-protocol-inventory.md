# Toyota/Lexus connection-protocol inventory

## Deliverable

The package contains a recoverable system-entry transport selector for every
`SYSLIST` system record. The read-only extractor is:

```bash
python3 tools/extract_toyota_protocols.py \
  --output /tmp/toyota-system-protocols.csv \
  --summary /tmp/toyota-system-protocol-summary.json
```

It writes one row for every represented **vehicle-profile key / system (ECU)
key** relation. It does not send traffic to a vehicle and does not produce
addresses, headers, timing values, initialization bytes, or write commands.

The CSV columns are:

```text
region, vehicle_key, system_record_ordinal, system_id,
system_entry_variant, entry_selector, entry_record_kind,
connection_path, verification_status
```

`vehicle_key` and `system_id` are the package's stable numeric database keys.
They are the identifiers actually used in the system-entry lookup. The English
vehicle/system labels are held in separate encrypted GGP text resources; they
have **not** been relabelled using guesses. This is intentional: it prevents a
numeric system key from being incorrectly represented as a named ECU.

## Verified results

The extractor decodes the `YZJM` V10.01 wrapper used by `SYSLIST_*.BIN` and
`SYSTEMENTER.BIN`, joins each regional vehicle-profile list to its
`SYSTEMENTER` record, and obtains the selector byte consumed by
`libTOYOTA_SYSP.so:SystemEnterPart`.

| Region | Vehicle-profile keys | System rows | System-entry group | Unknown rows |
|---|---:|---:|---:|---:|
| EU | 5,654 | 157,500 | 1 | 152 |
| NA | 2,726 | 74,985 | 2 | 729 |
| JP | 1,764 | 54,545 | 3 | 0 |
| **Total** | **10,144** | **287,030** | — | **881** |

A row may repeat a system key across multiple vehicle-profile keys, and a
vehicle profile can contain repeated system records. The CSV intentionally
preserves those relationships rather than falsely treating the 1,398
`Ecu_Data_*.BIN` archive members as globally unique ECUs.

### Connection-path counts

| Connection path | Rows | Meaning |
|---|---:|---|
| CAN | 236,066 | `SystemEnterPart` selector 3, 4, or 7 dispatches to `CANBUSEnterMode`. |
| Fast KWP | 49,750 | Selector 1 dispatches to `FastKWPEnterMode`. |
| ISO | 10 | Selector 2 dispatches to `ISOEnterMode`. |
| Fast KWP then ISO fallback | 27 | Selector 5 attempts Fast KWP and falls back to ISO only on the native failure path. |
| VPW | 294 | Selector 10 dispatches to `VPWEnterMode`. |
| Custom/unclassified | 2 | Selector 6 uses an unnamed/custom entry path. It is **not** called DESO without further proof. |
| Unknown/unmapped | 881 | No matching `SYSTEMENTER` record in the selected group; deliberately fail closed. |

The unknown rows are retained by the CSV with `connection_path=UNKNOWN` and
`verification_status=UNKNOWN` rather than assigned a protocol.

## Native selector evidence

`libTOYOTA_FILE.so:GetInfoFromEnterList` reads the record byte at offset `+5`
into the system-entry context selector (`+0x828`).
`libTOYOTA_SYSP.so:SystemEnterPart` dispatches that selector as follows:

| Selector | Native behavior | Extractor connection path/status |
|---:|---|---|
| 1 | `FastKWPEnterMode` | Fast KWP / verified named entry |
| 2 | `ISOEnterMode` | ISO / verified named entry |
| 3 | `CANBUSEnterMode` | CAN / verified named entry |
| 4 | `CANBUSEnterMode` | CAN / verified named entry |
| 5 | `FastKWPEnterMode`, then `ISOEnterMode` on the failure branch | Fast KWP then ISO fallback / verified sequence |
| 6 | Internal custom data-driven path | Unclassified / unknown |
| 7 | `CANBUSEnterMode` | CAN / verified named entry |
| 8 | Dispatcher failure path | No usable entry path / not supported |
| 9 | No entry action | No entry action / not supported |
| 10 | `VPWEnterMode` | VPW / verified named entry |

Selectors 8 and 9 are exposed in `SYSTEMENTER.BIN` but do not occur in the
current `SYSLIST` rows. The extractor still implements them to remain faithful
to the native dispatch table.

## Decoder scope and validation

The canonical files use the clear header:

```text
05 00 59 5A 4A 4D 00 07 00 TOYOTA 00 07 00 V10.01 00 D5 21 58 34
```

`libDEVICEID.so:fopenEx` derives a 32-byte context key from that header, and
`freadEx` applies its word transform when the data tables are read. The
extractor reproduces that exact V10.01 path only and rejects any different
product/version/seed combination. It also preserves the native unencrypted
`0x00000000` and `0xffffffff` sentinel words.

The regression suite includes a raw encrypted `SYSTEMENTER.BIN` hexadecimal
fixture and verifies its decoded output, plus checks the first decoded EU
system-profile records:

```bash
python3 -m unittest discover -s tests -v
```

## Important limits

- **Package capability is not a universal assignment.** The CSV records the
  selector bound to a particular package vehicle-profile/system-key relation;
  it does not claim that all Toyota/Lexus ECUs use CAN, KWP, ISO, or VPW.
- **Text labels remain unresolved.** The English vehicle and ECU display names
  are in proprietary GGP/GAG text resources. Until that text layer is decoded,
  use the CSV's numeric keys as canonical identifiers and do not substitute
  guessed model/ECU names.
- **No low-level diagnostic configuration was extracted.** A transport family
  does not verify ECU address, CAN identifier, K-line initialization, baud
  rate, request header, service, PID, or timing. All such fields remain
  unknown unless separately verified.
- The 2007 Toyota Innova GCC engine/ECU/protocol in `AGENTS.md` remains
  **UNKNOWN until verified**. This data extraction does not authorize an
  on-vehicle connection or change that status.
