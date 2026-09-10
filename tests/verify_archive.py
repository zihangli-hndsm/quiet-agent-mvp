#!/usr/bin/env python3
"""Independent verifier for a quiet-agent archive package.

The verifier only uses the Python standard library. It checks source bytes,
manifest claims, ZIP paths and payload hashes; it never trusts an app success
message. The manifest reader accepts the canonical fields documented in
``docs/ARCHITECTURE.md`` plus common snake_case/camelCase aliases so core can
evolve without making this safety check silently permissive.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile
from typing import Any


PATH_KEYS = ("source_path", "sourcePath", "relative_path", "relativePath", "original_path", "originalPath", "input_path", "inputPath", "path", "name")
ZIP_KEYS = ("archive_path", "archivePath", "zip_path", "zipPath", "entry", "entry_name", "entryName", "stored_as", "storedAs", "output_path", "outputPath")
HASH_KEYS = ("source_sha256", "sourceSha256", "source_hash", "sourceHash", "input_sha256", "inputSha256", "content_sha256", "contentSha256", "sha256", "hash")
ARCHIVE_HASH_KEYS = ("archive_sha256", "archiveSha256", "archive_hash", "archiveHash", "zip_sha256", "zipSha256", "stored_sha256", "storedSha256", "output_sha256", "outputSha256")
SIZE_KEYS = ("source_size", "sourceSize", "input_size", "inputSize", "size", "bytes", "length")
INCLUDED_KEYS = ("included", "archived", "in_archive", "inArchive")
DUPLICATE_KEYS = ("duplicate_of", "duplicateOf", "duplicate")
ID_KEYS = ("id", "source_id", "sourceId")
META_NAMES = {"manifest.json", "summary.html"}


def first(record: dict[str, Any], keys: tuple[str, ...]) -> Any:
    for key in keys:
        if key in record and record[key] is not None:
            return record[key]
    return None


def as_int(value: Any, label: str, errors: list[str]) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        errors.append(f"invalid integer for {label}: {value!r}")
        return None


def iter_records(value: Any):
    """Find record-like dicts in common manifest list/nesting shapes."""
    if isinstance(value, list):
        for item in value:
            yield from iter_records(item)
    elif isinstance(value, dict):
        if first(value, PATH_KEYS) is not None and (first(value, HASH_KEYS) is not None or first(value, ZIP_KEYS) is not None):
            yield value
        else:
            for item in value.values():
                yield from iter_records(item)


def record_archive_path(record: dict[str, Any]) -> Any:
    included = first(record, INCLUDED_KEYS)
    # Some producers retain the chosen item's archivePath on an excluded
    # duplicate for display. It is not a second ZIP entry.
    if included is False:
        return None
    archive = first(record, ZIP_KEYS)
    if archive is not None:
        return archive
    # Compact path-only records are accepted for included files. A duplicate
    # record with `archivePath: null` must not accidentally become an archive
    # entry through its source path.
    duplicate = first(record, DUPLICATE_KEYS)
    if included is not False and duplicate is None:
        return first(record, ("path", "name"))
    return None


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def safe_zip_name(name: str) -> bool:
    if not name or "\\" in name:
        return False
    path = PurePosixPath(name)
    return not path.is_absolute() and ".." not in path.parts and path.as_posix() == name


def source_snapshot(source_dir: Path) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    for path in source_dir.rglob("*"):
        if path.is_file():
            rel = path.relative_to(source_dir).as_posix()
            rows[rel] = {"sha256": sha256_file(path), "size": path.stat().st_size}
    return rows


def expected_snapshot(path: Path) -> dict[str, dict[str, Any]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    rows = {}
    for item in payload.get("files", []):
        rel = item.get("relative_path", item.get("relativePath"))
        if rel:
            rows[str(rel)] = {"sha256": item.get("sha256"), "size": item.get("size")}
    return rows


def find_count(manifest: dict[str, Any], names: tuple[str, ...]) -> int | None:
    for name in names:
        value = manifest.get(name)
        if isinstance(value, int) and value >= 0:
            return value
    return None


def verify(args: argparse.Namespace) -> list[str]:
    errors: list[str] = []
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    except Exception as exc:
        return [f"manifest unreadable: {exc}"]
    if not isinstance(manifest, dict):
        return ["manifest root must be an object"]

    records = list(iter_records(manifest))
    if not records:
        errors.append("manifest has no file records with a source path and hash/ZIP path")
    record_by_source: dict[str, dict[str, Any]] = {}
    record_by_zip: dict[str, dict[str, Any]] = {}
    for index, record in enumerate(records):
        source = first(record, PATH_KEYS)
        archive = record_archive_path(record)
        if source is not None:
            source = str(source).replace("\\", "/")
            if source in record_by_source:
                errors.append(f"duplicate manifest source path: {source}")
            record_by_source[source] = record
        if archive is not None:
            archive = str(archive)
            if not safe_zip_name(archive):
                errors.append(f"unsafe manifest ZIP path at record {index}: {archive!r}")
            if archive in record_by_zip:
                errors.append(f"duplicate manifest archive path: {archive}")
            record_by_zip[archive] = record

    actual_source = source_snapshot(args.source_dir)
    if args.expected:
        oracle = expected_snapshot(args.expected)
        if oracle and oracle != actual_source:
            errors.append("source differs from expected.json snapshot")
    source_count_claim = find_count(manifest, ("source_file_count", "sourceFileCount", "input_file_count", "inputFileCount", "file_count", "fileCount", "selected"))
    if source_count_claim is not None and source_count_claim != len(actual_source):
        errors.append(f"manifest source file count {source_count_claim} != actual {len(actual_source)}")

    source_hashes: dict[str, list[str]] = {}
    actual_hashes: dict[str, list[str]] = {}
    for source, actual in actual_source.items():
        actual_hashes.setdefault(actual["sha256"], []).append(source)
    for source, record in record_by_source.items():
        if source not in actual_source:
            errors.append(f"manifest source missing on disk: {source}")
            continue
        actual = actual_source[source]
        claimed_hash = first(record, HASH_KEYS)
        if claimed_hash is None:
            errors.append(f"missing source hash for {source}")
        elif str(claimed_hash).lower() != actual["sha256"]:
            errors.append(f"source hash mismatch for {source}")
        claimed_size = first(record, SIZE_KEYS)
        if claimed_size is not None:
            parsed_size = as_int(claimed_size, f"source size {source}", errors)
            if parsed_size is not None and parsed_size != actual["size"]:
                errors.append(f"source size mismatch for {source}")
        source_hashes.setdefault(actual["sha256"], []).append(source)

    # Validate explicit duplicate metadata and duplicate groups without assuming
    # whether the operation elected to retain or omit duplicate archive entries.
    record_by_id = {str(first(record, ID_KEYS)): record for record in records if first(record, ID_KEYS) is not None}
    for source, record in record_by_source.items():
        duplicate = first(record, DUPLICATE_KEYS)
        duplicate_record = None
        if isinstance(duplicate, str):
            duplicate_record = record_by_source.get(duplicate) or record_by_id.get(duplicate)
            if duplicate_record is None and duplicate in actual_source:
                duplicate_record = record_by_source.get(duplicate)
        if isinstance(duplicate, str) and duplicate_record is None and duplicate not in actual_source:
            errors.append(f"duplicate reference for {source} points nowhere: {duplicate}")
        if duplicate and source in actual_source and duplicate_record is not None:
            duplicate_source = first(duplicate_record, PATH_KEYS)
            duplicate_hash = actual_source.get(str(duplicate_source), {}).get("sha256") if duplicate_source is not None else first(duplicate_record, HASH_KEYS)
            if duplicate_hash is not None and actual_source[source]["sha256"] != duplicate_hash:
                errors.append(f"duplicate reference hash mismatch: {source} -> {duplicate}")
        included = first(record, INCLUDED_KEYS)
        if included is False and duplicate is None:
            errors.append(f"excluded source has no duplicateOf mapping: {source}")
    duplicate_groups = sum(1 for paths in actual_hashes.values() if len(paths) > 1)
    claimed_duplicates = find_count(manifest, ("duplicate_group_count", "duplicateGroupCount", "duplicates"))
    if isinstance(claimed_duplicates, int) and claimed_duplicates != duplicate_groups:
        errors.append(f"manifest duplicate group count {claimed_duplicates} != actual {duplicate_groups}")

    try:
        with zipfile.ZipFile(args.archive, "r") as archive:
            names = archive.namelist()
            if len(names) != len(set(names)):
                errors.append("ZIP contains duplicate entry names")
            for name in names:
                if not safe_zip_name(name):
                    errors.append(f"unsafe ZIP entry path: {name!r}")
            payload_names = {name for name in names if name not in META_NAMES and not name.endswith("/")}
            if record_by_zip and payload_names != set(record_by_zip):
                errors.append("manifest archive paths do not exactly match ZIP payload entries")
            if not record_by_zip:
                errors.append("manifest has no archive paths to verify against ZIP")
            for zip_name, record in record_by_zip.items():
                if zip_name not in names:
                    errors.append(f"manifest archive entry missing from ZIP: {zip_name}")
                    continue
                data = archive.read(zip_name)
                source_path = first(record, PATH_KEYS)
                # Core's `sha256` is the content hash for both the source and
                # included ZIP payload. Prefer an explicit archive hash, then
                # the record hash, then the freshly read source snapshot.
                claimed_hash = first(record, ARCHIVE_HASH_KEYS)
                if claimed_hash is None:
                    claimed_hash = first(record, HASH_KEYS)
                if claimed_hash is None and source_path is not None:
                    claimed_hash = actual_source.get(str(source_path), {}).get("sha256")
                if claimed_hash is not None and str(claimed_hash).lower() != sha256_bytes(data):
                    errors.append(f"archive payload hash mismatch for {zip_name}")
                claimed_size = first(record, ("archive_size", "archiveSize", "stored_size", "storedSize"))
                if claimed_size is not None:
                    parsed_size = as_int(claimed_size, f"archive size {zip_name}", errors)
                    if parsed_size is not None and parsed_size != len(data):
                        errors.append(f"archive payload size mismatch for {zip_name}")
            zip_sha = first(manifest, ("archive_sha256", "archiveSha256", "zip_sha256", "zipSha256"))
            if zip_sha is not None and str(zip_sha).lower() != sha256_file(args.archive):
                errors.append("ZIP file hash mismatch")
    except zipfile.BadZipFile as exc:
        errors.append(f"invalid ZIP: {exc}")
    except OSError as exc:
        errors.append(f"archive unreadable: {exc}")

    archive_count_claim = find_count(manifest, ("archive_file_count", "archiveFileCount", "output_file_count", "outputFileCount", "unique"))
    if archive_count_claim is not None and archive_count_claim != len(record_by_zip):
        errors.append(f"manifest archive file count {archive_count_claim} != records {len(record_by_zip)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", "--source", dest="source_dir", type=Path, required=True)
    parser.add_argument("--archive", type=Path, required=True, help="ZIP archive to inspect")
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--expected", type=Path, help="optional expected.json from make_fixtures.py")
    args = parser.parse_args()
    errors = verify(args)
    if errors:
        print("FAIL")
        for error in errors:
            print(f"- {error}")
        return 1
    print("PASS: source hashes, manifest records, ZIP paths, payload hashes, counts, and duplicates verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
