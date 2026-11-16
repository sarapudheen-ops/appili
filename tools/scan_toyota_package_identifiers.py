#!/usr/bin/env python3
"""Read-only identifier sweep for shipped Toyota/Lexus package files.

The scanner searches raw package bytes and decoded payloads of the verified
Toyota V10.01 YZJM wrapper. It reports matches only; it does not infer a
vehicle-to-ECU relationship, a diagnostic transport, or a diagnostic command.
"""

from __future__ import annotations

import argparse
import json
import zipfile
from collections import defaultdict
from pathlib import Path
from typing import Any, Iterable

try:  # Support both `python tools/...` and package imports from the tests.
    from .extract_toyota_protocols import (
        UnsupportedWrapper,
        decode_v10_payload,
        parse_yzjm_header,
    )
except ImportError:  # pragma: no cover - exercised by the command-line path.
    from extract_toyota_protocols import (  # type: ignore[no-redef]
        UnsupportedWrapper,
        decode_v10_payload,
        parse_yzjm_header,
    )


DEFAULT_EXCLUSIONS = frozenset({".gitignore", "AGENTS.md", "README.md"})


def _encoded_forms(term: str) -> tuple[bytes, bytes, bytes]:
    """Return ASCII, UTF-16LE, and UTF-16BE forms of an ASCII identifier."""
    try:
        ascii_term = term.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ValueError(f"identifier must be ASCII: {term!r}") from exc
    if not ascii_term:
        raise ValueError("identifier must not be empty")
    return (
        ascii_term,
        b"".join(bytes((character, 0)) for character in ascii_term),
        b"".join(bytes((0, character)) for character in ascii_term),
    )


def find_identifier_offsets(blob: bytes, term: str) -> list[int]:
    """Find an identifier in supported encodings, without interpreting bytes."""
    searchable = blob.lower()
    offsets: list[int] = []
    for encoded in _encoded_forms(term):
        needle = encoded.lower()
        start = 0
        while True:
            offset = searchable.find(needle, start)
            if offset < 0:
                break
            offsets.append(offset)
            start = offset + 1
    return sorted(offsets)


def _add_matches(
    matches: dict[str, list[dict[str, object]]],
    source: str,
    blob: bytes,
    terms: Iterable[str],
) -> None:
    for term in terms:
        offsets = find_identifier_offsets(blob, term)
        if offsets:
            matches[term].append(
                {
                    "source": source,
                    "count": len(offsets),
                    # The complete count is retained while offsets are bounded
                    # to keep reports usable for short, high-frequency terms.
                    "offsets": offsets[:16],
                }
            )


def _json_ready(value: Any) -> Any:
    if isinstance(value, defaultdict):
        return {key: _json_ready(item) for key, item in value.items()}
    if isinstance(value, dict):
        return {key: _json_ready(item) for key, item in value.items()}
    if isinstance(value, list):
        return [_json_ready(item) for item in value]
    return value


def scan_package(
    root: Path,
    archive_name: str,
    terms: Iterable[str],
    exclusions: Iterable[str] = DEFAULT_EXCLUSIONS,
) -> dict[str, object]:
    """Sweep top-level package files and a ZIP archive without modifying either.

    `raw_matches` covers raw individual package files and decompressed ZIP
    members before wrapper decoding. `decoded_matches` covers only payloads
    whose headers exactly match the validated Toyota V10.01 wrapper.
    """
    root = root.resolve()
    terms = tuple(terms)
    if not terms:
        raise ValueError("at least one identifier is required")
    if len(set(terms)) != len(terms):
        raise ValueError("identifiers must be unique")
    # Validate terms before spending time on a package scan.
    for term in terms:
        _encoded_forms(term)

    excluded_names = set(exclusions)
    raw_matches: dict[str, list[dict[str, object]]] = defaultdict(list)
    decoded_matches: dict[str, list[dict[str, object]]] = defaultdict(list)
    report: dict[str, object] = {
        "terms": list(terms),
        "top_level_file_count": 0,
        "top_level_byte_count": 0,
        "top_level_verified_yzjm_files": [],
        "zip_non_directory_member_count": 0,
        "zip_verified_yzjm_member_count": 0,
        "raw_matches": raw_matches,
        "decoded_matches": decoded_matches,
    }

    for path in sorted(root.iterdir()):
        if not path.is_file() or path.name in excluded_names:
            continue
        blob = path.read_bytes()
        report["top_level_file_count"] = int(report["top_level_file_count"]) + 1
        report["top_level_byte_count"] = int(report["top_level_byte_count"]) + len(blob)
        _add_matches(raw_matches, path.name, blob, terms)
        try:
            header = parse_yzjm_header(blob)
        except UnsupportedWrapper:
            continue
        verified_files = report["top_level_verified_yzjm_files"]
        assert isinstance(verified_files, list)
        verified_files.append(path.name)
        _add_matches(
            decoded_matches,
            path.name,
            decode_v10_payload(blob[header.payload_offset:]),
            terms,
        )

    archive_path = root / archive_name
    with zipfile.ZipFile(archive_path) as archive:
        for member in archive.infolist():
            if member.is_dir():
                continue
            report["zip_non_directory_member_count"] = (
                int(report["zip_non_directory_member_count"]) + 1
            )
            source = f"{archive_name}:{member.filename}"
            blob = archive.read(member)
            _add_matches(raw_matches, source, blob, terms)
            try:
                header = parse_yzjm_header(blob)
            except UnsupportedWrapper:
                continue
            report["zip_verified_yzjm_member_count"] = (
                int(report["zip_verified_yzjm_member_count"]) + 1
            )
            _add_matches(
                decoded_matches,
                source,
                decode_v10_payload(blob[header.payload_offset:]),
                terms,
            )

    return _json_ready(report)


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--term",
        action="append",
        required=True,
        help="ASCII identifier to search; repeat for each identifier",
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path("."),
        help="package root (default: current directory)",
    )
    parser.add_argument(
        "--archive",
        default="BIN.ZIP",
        help="ZIP payload archive relative to --root (default: BIN.ZIP)",
    )
    parser.add_argument(
        "--output",
        type=Path,
        required=True,
        help="JSON output path",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    try:
        report = scan_package(args.root, args.archive, args.term)
    except (OSError, ValueError, zipfile.BadZipFile) as exc:
        print(f"error: {exc}")
        return 2
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
