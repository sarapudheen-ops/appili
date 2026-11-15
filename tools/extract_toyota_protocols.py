#!/usr/bin/env python3
"""Extract Toyota/Lexus database system-entry transport assignments.

This utility is deliberately read-only.  It decodes the V10.01 YZJM wrappers
used by the three SYSLIST files and SYSTEMENTER.BIN, then joins every database
vehicle-profile key to its system/ECU key and system-entry selector.

It does not generate diagnostic traffic or infer ECU addresses, headers,
timing, initialization bytes, or write commands.
"""

from __future__ import annotations

import argparse
import csv
import json
import struct
import sys
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator


# Reproduced from libDEVICEID.so:freadEx for this exact shipped wrapper family.
# This is the derived 32-byte word-key for:
#   product = TOYOTA, version = V10.01, seed = d5 21 58 34 (little endian).
# The key is not applied to raw 0x00000000 and 0xffffffff sentinel words.
_V10_WORD_KEY = bytes.fromhex(
    "8636d27ecea164fe8d5e18cced95309cb86cd7f06e5f2779a3cfc94c2767508f"
)
_SUPPORTED_PRODUCT = b"TOYOTA\0"
_SUPPORTED_VERSION = b"V10.01\0"
_SUPPORTED_SEED = bytes.fromhex("d5215834")
SYSTEM_RECORD_SIZE = 37

# These group IDs are the SystemEnter index groups selected by the regional
# SYSLIST loaders.  Coverage validation is performed while extracting.
REGIONS = (
    ("EU", "SYSLIST_EU.BIN", 1),
    ("NA", "SYSLIST_NA.BIN", 2),
    ("JP", "SYSLIST_JP.BIN", 3),
)

# libTOYOTA_SYSP.so:SystemEnterPart dispatches on the selector byte populated
# by libTOYOTA_FILE.so:GetInfoFromEnterList at context offset +0x828.
# Selector 6 does not call any named ISO/CAN/KWP/VPW entry routine and is kept
# deliberately unclassified.  Selectors 8 and 9 do not establish a usable
# connection in that dispatcher.
SELECTOR_DETAILS = {
    1: ("Fast KWP", "VERIFIED_NAMED_ENTRY"),
    2: ("ISO", "VERIFIED_NAMED_ENTRY"),
    3: ("CAN", "VERIFIED_NAMED_ENTRY"),
    4: ("CAN", "VERIFIED_NAMED_ENTRY"),
    5: ("Fast KWP then ISO fallback", "VERIFIED_FALLBACK_SEQUENCE"),
    6: ("UNCLASSIFIED_CUSTOM", "UNKNOWN"),
    7: ("CAN", "VERIFIED_NAMED_ENTRY"),
    8: ("NO_USABLE_ENTRY_PATH", "NOT_SUPPORTED_BY_DISPATCHER"),
    9: ("NO_ENTRY_ACTION", "NOT_SUPPORTED_BY_DISPATCHER"),
    10: ("VPW", "VERIFIED_NAMED_ENTRY"),
}


class UnsupportedWrapper(ValueError):
    """Raised when a package file is not the verified V10.01 wrapper form."""


@dataclass(frozen=True)
class YzjmHeader:
    product: bytes
    version: bytes
    seed: bytes
    payload_offset: int


@dataclass(frozen=True)
class SystemEntry:
    selector: int
    record_kind: int
    entry_variant: int
    offset: int


def _u16(data: bytes, offset: int) -> int:
    return struct.unpack_from("<H", data, offset)[0]


def _u32(data: bytes, offset: int) -> int:
    return struct.unpack_from("<I", data, offset)[0]


def _extr32(rn: int, rm: int, shift: int) -> int:
    """AArch64 EXTR Wd, Wn, Wm, #shift semantics."""
    return ((rn << (32 - shift)) | (rm >> shift)) & 0xFFFFFFFF


def _decode_word(ciphertext: int, word_index: int) -> int:
    """Decode one four-byte logical YZJM payload word.

    The input/output word byte order is big-endian because freadEx assembles
    bytes in that order.  Its 32-bit key words are loaded little-endian by the
    AArch64 implementation.
    """
    if ciphertext in (0, 0xFFFFFFFF):
        return ciphertext
    transformed = _extr32(ciphertext, ciphertext, 9)
    transformed = _extr32(ciphertext >> 9, transformed, 13)
    key_offset = (word_index % 8) * 4
    key_word = int.from_bytes(_V10_WORD_KEY[key_offset : key_offset + 4], "little")
    return transformed ^ key_word


def decode_v10_payload(payload: bytes, logical_offset: int = 0) -> bytes:
    """Decode an aligned V10.01 YZJM payload slice.

    `logical_offset` is the offset relative to the encrypted payload, not the
    physical file offset.  The table readers operate on four-byte aligned
    logical records; any trailing incomplete word is left unchanged because
    the native reader obtains its boundary bytes through a separate fread.
    """
    if logical_offset % 4:
        raise ValueError("payload slices must start on a four-byte boundary")
    decoded = bytearray(payload)
    full_words = len(payload) // 4
    for relative in range(full_words):
        byte_offset = relative * 4
        cipher = int.from_bytes(payload[byte_offset : byte_offset + 4], "big")
        clear = _decode_word(cipher, (logical_offset // 4) + relative)
        decoded[byte_offset : byte_offset + 4] = clear.to_bytes(4, "big")
    return bytes(decoded)


def parse_yzjm_header(blob: bytes) -> YzjmHeader:
    """Parse and validate the cleartext wrapper header before decoding."""
    if len(blob) < 29 or blob[2:7] != b"YZJM\0":
        raise UnsupportedWrapper("expected a YZJM wrapper")
    product_length = _u16(blob, 7)
    product_start = 9
    product_end = product_start + product_length
    if product_end + 2 > len(blob):
        raise UnsupportedWrapper("truncated product field")
    version_length = _u16(blob, product_end)
    version_start = product_end + 2
    version_end = version_start + version_length
    seed_end = version_end + 4
    if seed_end > len(blob):
        raise UnsupportedWrapper("truncated version or seed field")
    header = YzjmHeader(
        product=blob[product_start:product_end],
        version=blob[version_start:version_end],
        seed=blob[version_end:seed_end],
        payload_offset=seed_end,
    )
    if (
        header.product != _SUPPORTED_PRODUCT
        or header.version != _SUPPORTED_VERSION
        or header.seed != _SUPPORTED_SEED
    ):
        raise UnsupportedWrapper(
            "unsupported YZJM parameters: "
            f"product={header.product!r}, version={header.version!r}, "
            f"seed={header.seed.hex()}"
        )
    return header


def decode_yzjm_file(path: Path) -> bytes:
    """Decode one verified Toyota V10.01 YZJM file into memory."""
    blob = path.read_bytes()
    header = parse_yzjm_header(blob)
    return decode_v10_payload(blob[header.payload_offset:])


def parse_system_enter(decoded: bytes) -> dict[int, dict[int, SystemEntry]]:
    """Return {entry-group: {system-id: system-entry data}}."""
    if not decoded:
        raise ValueError("SYSTEMENTER payload is empty")
    group_count = decoded[0]
    directory_end = 1 + group_count * 5
    if directory_end > len(decoded):
        raise ValueError("SYSTEMENTER directory is truncated")

    groups: dict[int, dict[int, SystemEntry]] = {}
    for item in range(group_count):
        directory_offset = 1 + item * 5
        group_id = decoded[directory_offset]
        table_offset = _u32(decoded, directory_offset + 1)
        if table_offset + 2 > len(decoded):
            raise ValueError(f"SYSTEMENTER group {group_id} points outside the payload")
        count = _u16(decoded, table_offset)
        table_end = table_offset + 2 + count * 8
        if table_end > len(decoded):
            raise ValueError(f"SYSTEMENTER group {group_id} index is truncated")

        entries: dict[int, SystemEntry] = {}
        for row in range(count):
            row_offset = table_offset + 2 + row * 8
            system_id = _u32(decoded, row_offset)
            detail_offset = _u32(decoded, row_offset + 4)
            if detail_offset + 6 > len(decoded):
                raise ValueError(
                    f"SYSTEMENTER system {system_id} detail points outside the payload"
                )
            # GetInfoFromEnterList reads record byte 4 as a schema/type byte and
            # record byte 5 into the selector used by SystemEnterPart.
            entries[system_id] = SystemEntry(
                selector=decoded[detail_offset + 5],
                record_kind=decoded[detail_offset + 4] & 0x0F,
                entry_variant=decoded[detail_offset + 1],
                offset=detail_offset,
            )
        groups[group_id] = entries
    return groups


def iter_syslist_records(decoded: bytes) -> Iterator[tuple[int, int, int, int]]:
    """Yield vehicle-key, ordinal, system-id, SystemEnter-variant from SYSLIST."""
    if len(decoded) < 8:
        raise ValueError("SYSLIST payload is too short")
    first_table_offset = _u32(decoded, 4)
    if first_table_offset == 0 or first_table_offset > len(decoded):
        raise ValueError("SYSLIST index boundary is invalid")

    # The eight-byte vehicle-key index is followed by one four-byte 0xffffffff
    # sentinel, so the first system table need not be eight-byte aligned.
    if first_table_offset < 4 or _u32(decoded, first_table_offset - 4) != 0xFFFFFFFF:
        raise ValueError("SYSLIST index sentinel is missing")
    for index_offset in range(0, first_table_offset - 4, 8):
        vehicle_key = _u32(decoded, index_offset)
        table_offset = _u32(decoded, index_offset + 4)
        if vehicle_key == 0:
            continue
        if table_offset + 2 > len(decoded):
            raise ValueError(f"vehicle key {vehicle_key} points outside SYSLIST")
        record_count = _u16(decoded, table_offset)
        records_end = table_offset + 2 + record_count * SYSTEM_RECORD_SIZE
        if records_end > len(decoded):
            raise ValueError(f"vehicle key {vehicle_key} system records are truncated")
        for ordinal in range(record_count):
            record_offset = table_offset + 2 + ordinal * SYSTEM_RECORD_SIZE
            system_id = _u32(decoded, record_offset)
            # GetSystemListData copies physical byte 27 into its context +0x18
            # and passes it to GetSystemEnterInfoData as the entry variant.
            entry_variant = decoded[record_offset + 27]
            yield vehicle_key, ordinal, system_id, entry_variant


def build_rows(root: Path) -> tuple[Iterator[dict[str, object]], dict[str, object]]:
    """Construct protocol rows and a conservative summary from package files."""
    system_enter = parse_system_enter(decode_yzjm_file(root / "SYSTEMENTER.BIN"))
    summary: dict[str, object] = {"regions": {}, "totals": {}}

    def rows() -> Iterator[dict[str, object]]:
        selector_counts: Counter[str] = Counter()
        status_counts: Counter[str] = Counter()
        total_rows = 0
        for region, filename, group_id in REGIONS:
            if group_id not in system_enter:
                raise ValueError(f"SYSTEMENTER does not provide group {group_id} for {region}")
            syslist = decode_yzjm_file(root / filename)
            entries = system_enter[group_id]
            regional_rows = 0
            regional_selectors: Counter[str] = Counter()
            regional_status: Counter[str] = Counter()
            vehicle_keys: set[int] = set()
            missing_system_ids: set[int] = set()

            for vehicle_key, ordinal, system_id, entry_variant in iter_syslist_records(syslist):
                vehicle_keys.add(vehicle_key)
                system_entry = entries.get(system_id)
                if system_entry is None:
                    selector: int | str = ""
                    path = "UNKNOWN"
                    status = "UNKNOWN"
                    record_kind: int | str = ""
                    missing_system_ids.add(system_id)
                else:
                    selector = system_entry.selector
                    path, status = SELECTOR_DETAILS.get(
                        selector, ("UNKNOWN", "UNKNOWN")
                    )
                    record_kind = system_entry.record_kind

                row = {
                    "region": region,
                    "vehicle_key": vehicle_key,
                    "system_record_ordinal": ordinal,
                    "system_id": system_id,
                    "system_entry_variant": entry_variant,
                    "entry_selector": selector,
                    "entry_record_kind": record_kind,
                    "connection_path": path,
                    "verification_status": status,
                }
                regional_rows += 1
                total_rows += 1
                selector_counts[str(selector) if selector != "" else "UNKNOWN"] += 1
                status_counts[status] += 1
                regional_selectors[str(selector) if selector != "" else "UNKNOWN"] += 1
                regional_status[status] += 1
                yield row

            summary["regions"][region] = {
                "source": filename,
                "system_enter_group": group_id,
                "vehicle_profile_keys": len(vehicle_keys),
                "system_rows": regional_rows,
                "selectors": dict(sorted(regional_selectors.items(), key=lambda item: item[0])),
                "statuses": dict(sorted(regional_status.items())),
                "unmapped_system_ids": sorted(missing_system_ids),
            }
        summary["totals"] = {
            "system_rows": total_rows,
            "selectors": dict(sorted(selector_counts.items(), key=lambda item: item[0])),
            "statuses": dict(sorted(status_counts.items())),
        }

    return rows(), summary


def write_csv(root: Path, output: Path) -> dict[str, object]:
    """Write the complete package-key/system-key matrix and return a summary."""
    output.parent.mkdir(parents=True, exist_ok=True)
    rows, summary = build_rows(root)
    fields = (
        "region",
        "vehicle_key",
        "system_record_ordinal",
        "system_id",
        "system_entry_variant",
        "entry_selector",
        "entry_record_kind",
        "connection_path",
        "verification_status",
    )
    with output.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)
    return summary


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="directory containing SYSTEMENTER.BIN and SYSLIST_*.BIN",
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="destination CSV; it is generated on demand and is not checked in",
    )
    parser.add_argument(
        "--summary",
        type=Path,
        help="optional JSON summary destination",
    )
    args = parser.parse_args(argv)

    try:
        summary = write_csv(args.root, args.output)
    except (OSError, ValueError, UnsupportedWrapper) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        args.summary.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
