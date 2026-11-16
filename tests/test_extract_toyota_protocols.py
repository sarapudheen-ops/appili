"""Regression tests for the read-only Toyota V10.01 table extractor."""

from __future__ import annotations

import unittest
from pathlib import Path

from tools.extract_toyota_protocols import (
    decode_v10_payload,
    decode_yzjm_file,
    parse_system_enter,
)
from tools.scan_toyota_package_identifiers import find_identifier_offsets


ROOT = Path(__file__).resolve().parents[1]


class ToyotaProtocolExtractorTests(unittest.TestCase):
    def test_raw_yzjm_hex_fixture_decodes_like_libdeviceid_freadex(self) -> None:
        # First four encrypted words after SYSTEMENTER.BIN's clear YZJM header.
        # The expected text is the raw transcript returned by libDEVICEID.so's
        # fopenEx/fseekEx/freadEx path at logical offset zero.
        raw_payload = bytes.fromhex(
            "a19f74c8c33f9928a3b046177b4b0a65"
        )
        expected = bytes.fromhex(
            "03011400000002c20d000003b0190000"
        )
        self.assertEqual(decode_v10_payload(raw_payload), expected)

    def test_systementer_parser_exposes_dispatch_selector(self) -> None:
        system_enter = parse_system_enter(decode_yzjm_file(ROOT / "SYSTEMENTER.BIN"))
        self.assertEqual(set(system_enter), {1, 2, 3})
        # System ID 1 in group 1 has selector 1, which SystemEnterPart routes
        # to FastKWPEnterMode.  Byte 5 is the native dispatch selector.
        self.assertEqual(system_enter[1][1].selector, 1)
        self.assertEqual(system_enter[1][1].record_kind, 1)

    def test_raw_syslist_fixture_retains_first_eu_system_ids(self) -> None:
        # Decoding the real raw YZJM fixture validates the parser against the
        # package rather than a reconstructed cleartext table.
        decoded = decode_yzjm_file(ROOT / "SYSLIST_EU.BIN")
        first_profile_offset = int.from_bytes(decoded[4:8], "little")
        self.assertEqual(decoded[first_profile_offset:first_profile_offset + 2], b"\x03\x00")
        first_system_ids = [
            int.from_bytes(
                decoded[first_profile_offset + 2 + index * 37:first_profile_offset + 6 + index * 37],
                "little",
            )
            for index in range(3)
        ]
        self.assertEqual(first_system_ids, [97, 8, 15])

    def test_identifier_sweep_supports_ascii_and_utf16_without_overlap_loss(self) -> None:
        self.assertEqual(find_identifier_offsets(b"xxTGN41Lxx", "tgn41l"), [2])
        self.assertEqual(
            find_identifier_offsets(b"xT\x00G\x00N\x004\x001\x00L\x00x", "TGN41L"),
            [1],
        )
        # Advancing by one byte retains overlapping raw matches.
        self.assertEqual(find_identifier_offsets(b"aaaa", "aa"), [0, 1, 2])


if __name__ == "__main__":
    unittest.main()
