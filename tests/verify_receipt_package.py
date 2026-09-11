#!/usr/bin/env python3
"""Independent checker for the App-shaped fictional receipt package."""
from __future__ import annotations

import argparse
import csv
import hashlib
import io
import json
import zipfile
from decimal import Decimal
from pathlib import Path, PurePosixPath


META = {"receipt.csv", "manifest.json", "audit.jsonl", "provenance.json"}
APP_META = {"receipts.csv", "manifest.json", "summary.html", "audit-snapshot"}


def digest_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def digest_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def digest_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def safe_name(name: str) -> bool:
    path = PurePosixPath(name)
    return bool(name) and "\\" not in name and not path.is_absolute() and ".." not in path.parts and path.as_posix() == name


def verify(args: argparse.Namespace) -> list[str]:
    errors: list[str] = []
    expected = json.loads(args.expected.read_text(encoding="utf-8"))
    source = args.source_dir
    source_paths = sorted(p.name for p in source.glob("*.png") if p.is_file())
    expected_by_path = {row["sourcePath"]: row for row in expected["records"]}
    source_meta = {name: {"sha256": digest_file(source / name), "size": (source / name).stat().st_size} for name in source_paths}
    if len(source_paths) != 12 or set(source_paths) != set(expected_by_path):
        errors.append("source must contain exactly the 12 expected images")

    with zipfile.ZipFile(args.package, "r") as archive:
        if archive.testzip() is not None:
            errors.append("ZIP CRC failure")
        names = archive.namelist()
        if len(names) != len(set(names)) or any(not safe_name(name) for name in names):
            errors.append("ZIP contains duplicate or unsafe paths")
        if any(name.endswith("expected.json") for name in names):
            errors.append("oracle expected.json leaked into package")
        included_paths = sorted("receipts/" + name for name, row in expected_by_path.items() if not row["duplicateOf"])
        actual_images = sorted(name for name in names if name.startswith("receipts/") and name.endswith(".png"))
        if actual_images != included_paths:
            errors.append("ZIP must contain exactly 10 unique image payloads")
        for name in actual_images:
            source_name = name[len("receipts/"):]
            if archive.read(name) != (source / source_name).read_bytes():
                errors.append("ZIP image differs from source: " + source_name)
        if META - set(names):
            errors.append("ZIP is missing required metadata files")
        manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
        csv_rows = list(csv.DictReader(io.StringIO(archive.read("receipt.csv").decode("utf-8"))))
        audit_raw = archive.read("audit.jsonl").decode("utf-8")
        audit_lines = [json.loads(line) for line in audit_raw.splitlines() if line.strip()]
        provenance = json.loads(archive.read("provenance.json").decode("utf-8"))

        if manifest.get("schema") != "quiet-agent-receipt-manifest-v1":
            errors.append("manifest schema mismatch")
        rows = {row.get("sourceName"): row for row in manifest.get("rows", [])}
        if len(rows) != 12 or set(rows) != set(source_paths):
            errors.append("manifest must contain all 12 source rows")
        csv_by_name = {row.get("source_name"): row for row in csv_rows}
        if len(csv_rows) != 12 or set(csv_by_name) != set(source_paths):
            errors.append("CSV must contain all 12 source rows")

        source_id_by_name = {name: rows[name].get("sourceId") for name in rows}
        for name, expected_row in expected_by_path.items():
            actual = rows.get(name, {})
            expected_cents = None if expected_row["amount"] is None else int(Decimal(expected_row["amount"]) * 100)
            if actual.get("category") != expected_row["category"] or actual.get("layout") != expected_row["layout"] or actual.get("merchant") != expected_row["merchant"] or actual.get("dateIso") != expected_row["date"] or actual.get("amountCents") != expected_cents:
                errors.append("manifest OCR answer mismatch: " + name)
            if actual.get("sha256") != source_meta.get(name, {}).get("sha256") or int(actual.get("sizeBytes", -1)) != source_meta.get(name, {}).get("size", -2):
                errors.append("manifest source hash/size mismatch: " + name)
            expected_archive = None if expected_row["duplicateOf"] else "receipts/" + name
            if actual.get("archivePath") != expected_archive or bool(actual.get("included")) != (expected_archive is not None):
                errors.append("manifest inclusion/archive mapping mismatch: " + name)
            if expected_row["duplicateOf"] and source_id_by_name.get(name) and actual.get("duplicateOf") != source_id_by_name.get(expected_row["duplicateOf"]):
                errors.append("manifest duplicate mapping mismatch: " + name)
            csv_row = csv_by_name.get(name, {})
            expected_amount = "" if expected_cents is None else str(expected_cents)
            if csv_row.get("source_id") != actual.get("sourceId") or csv_row.get("amount_cents", "") != expected_amount or (csv_row.get("duplicate_of") or None) != (actual.get("duplicateOf") or None):
                errors.append("CSV mapping mismatch: " + name)

        duplicate_rows = [row for row in rows.values() if row.get("duplicateOf")]
        if len(duplicate_rows) != expected["duplicateCount"] or any(row.get("included") for row in duplicate_rows):
            errors.append("duplicate rows must be excluded from ZIP and retain duplicateOf")
        for row in duplicate_rows:
            original = next((candidate for candidate in rows.values() if candidate.get("sourceId") == row.get("duplicateOf")), None)
            if original is None or original.get("sha256") != row.get("sha256"):
                errors.append("duplicate content hash mapping mismatch")

        if sum((Decimal(row["amount"]) for row in expected["records"] if row["category"] == "清晰"), Decimal("0.00")) != Decimal(expected["totalAmount"]):
            errors.append("expected amount oracle is inconsistent")
        if int(manifest.get("recognizedTotalCents", -1)) != int(Decimal(expected["totalAmount"]) * 100):
            errors.append("recognizedTotalCents mismatch")
        categories = [row.get("category") for row in rows.values()]
        for category, key in (("清晰", "clearCount"), ("重复", "duplicateCount"), ("金额模糊", "ambiguousCount"), ("非票据", "nonReceiptCount")):
            if categories.count(category) != expected[key]:
                errors.append("category count mismatch: " + category)

        snapshots = [row for row in audit_lines if row.get("event") == "source-snapshot"]
        if len(snapshots) != 12 or any(row.get("sha256") not in {item["sha256"] for item in source_meta.values()} for row in snapshots):
            errors.append("audit snapshot does not cover source hashes")
        if any(row["merchant"] in audit_raw for row in expected["records"]) or "ocr" in audit_raw.lower() or "text" in audit_raw.lower():
            errors.append("audit snapshot contains raw OCR or merchant data")
        fixture_root = Path(__file__).resolve().parent / "receipt_fixtures"
        if provenance.get("generator") != "tests/receipt_fixtures/generate_receipts.py" or provenance.get("generatorSha256") != digest_file(fixture_root / "generate_receipts.py") or provenance.get("resourceCatalogSha256") != digest_file(fixture_root / "resources.json"):
            errors.append("generator/resource provenance mismatch")
    return errors


def verify_app(args: argparse.Namespace) -> list[str]:
    """Verify the ZIP shape emitted for a real ReceiptTaskRunner result.

    This path intentionally checks only observable App output and uses the
    fixture oracle for expected source identities/amounts.  It does not accept
    category/layout fields as evidence because the production manifest does
    not contain test-only labels.
    """
    errors: list[str] = []
    expected = json.loads(args.expected.read_text(encoding="utf-8"))
    source = args.source_dir
    expected_by_name = {row["sourcePath"]: row for row in expected["records"]}
    source_paths = sorted(p.name for p in source.glob("*.png") if p.is_file())
    source_meta = {
        name: {"sha256": digest_file(source / name), "size": (source / name).stat().st_size}
        for name in source_paths
    }
    if len(source_paths) != 12 or set(source_paths) != set(expected_by_name):
        errors.append("source must contain exactly the 12 expected images")

    try:
        with zipfile.ZipFile(args.package, "r") as archive:
            if archive.testzip() is not None:
                errors.append("ZIP CRC failure")
            names = archive.namelist()
            if len(names) != len(set(names)) or any(not safe_name(name) for name in names):
                errors.append("ZIP contains duplicate or unsafe paths")
            if any(PurePosixPath(name).name.lower() == "expected.json" for name in names):
                errors.append("oracle expected.json leaked into App package")
            missing = APP_META - set(names)
            if missing:
                errors.append("App package missing required files: " + ", ".join(sorted(missing)))

            actual_images = sorted(
                name for name in names
                if name.startswith("receipts/") and name.lower().endswith((".png", ".jpg", ".jpeg"))
            )
            expected_unique = sorted(
                "receipts/" + name
                for name, row in expected_by_name.items()
                if not row.get("duplicateOf")
            )
            if actual_images != expected_unique:
                errors.append("ZIP must contain exactly 10 unique image payloads")
            for name in actual_images:
                source_name = name[len("receipts/"):]
                if source_name not in source_meta:
                    errors.append("ZIP contains an unknown image: " + source_name)
                elif archive.read(name) != (source / source_name).read_bytes():
                    errors.append("ZIP image differs from source: " + source_name)

            manifest = json.loads(archive.read("manifest.json").decode("utf-8"))
            csv_rows = list(csv.DictReader(io.StringIO(archive.read("receipts.csv").decode("utf-8"))))
            summary = archive.read("summary.html").decode("utf-8")
            audit = json.loads(archive.read("audit-snapshot").decode("utf-8"))
            package_text = "\n".join(
                archive.read(name).decode("utf-8", errors="replace")
                for name in names
                if name in APP_META
            )
            if "expected.json" in package_text:
                errors.append("oracle expected.json text leaked into App package")

            if manifest.get("schema") != "quiet-agent-receipt-manifest-v1":
                errors.append("manifest schema mismatch")
            rows_list = manifest.get("rows")
            if not isinstance(rows_list, list):
                errors.append("manifest rows must be a list")
                rows_list = []
            rows = {row.get("sourceName"): row for row in rows_list if isinstance(row, dict)}
            if len(rows) != 12 or set(rows) != set(source_paths):
                errors.append("manifest must contain all 12 source rows")

            csv_by_name = {row.get("source_name"): row for row in csv_rows}
            if len(csv_rows) != 12 or set(csv_by_name) != set(source_paths):
                errors.append("receipts.csv must contain all 12 source rows")

            for name, expected_row in expected_by_name.items():
                actual = rows.get(name, {})
                if actual.get("sha256") != source_meta.get(name, {}).get("sha256"):
                    errors.append("manifest source hash mismatch: " + name)
                try:
                    actual_size = int(actual.get("sizeBytes", -1))
                except (TypeError, ValueError):
                    actual_size = -1
                if actual_size != source_meta.get(name, {}).get("size", -2):
                    errors.append("manifest source size mismatch: " + name)

                duplicate_name = expected_row.get("duplicateOf")
                expected_included = duplicate_name is None
                if bool(actual.get("included")) != expected_included:
                    errors.append("manifest inclusion mismatch: " + name)
                expected_archive = None if duplicate_name else "receipts/" + name
                if actual.get("archivePath") != expected_archive:
                    errors.append("manifest archive path mismatch: " + name)
                if duplicate_name:
                    target = rows.get(duplicate_name, {})
                    if actual.get("duplicateOf") != target.get("sourceId"):
                        errors.append("manifest duplicateOf mismatch: " + name)
                elif actual.get("duplicateOf") not in (None, ""):
                    errors.append("unique row unexpectedly has duplicateOf: " + name)

                csv_row = csv_by_name.get(name, {})
                expected_amount = "" if expected_row.get("amount") is None else str(int(Decimal(expected_row["amount"]) * 100))
                if csv_row.get("source_id") != actual.get("sourceId"):
                    errors.append("CSV source mapping mismatch: " + name)
                if csv_row.get("amount_cents", "") != expected_amount and expected_row["category"] == "清晰":
                    errors.append("CSV amount mismatch: " + name)
                if (csv_row.get("duplicate_of") or None) != (actual.get("duplicateOf") or None):
                    errors.append("CSV duplicate mapping mismatch: " + name)
                if (csv_row.get("archive_path") or None) != (actual.get("archivePath") or None):
                    errors.append("CSV archive mapping mismatch: " + name)

            duplicate_rows = [row for row in rows.values() if row.get("duplicateOf")]
            if len(duplicate_rows) != 2 or any(row.get("included") for row in duplicate_rows):
                errors.append("App manifest must contain exactly 2 excluded duplicate rows")

            clear_names = [name for name, row in expected_by_name.items() if row["category"] == "清晰"]
            ambiguous_names = [name for name, row in expected_by_name.items() if row["category"] == "金额模糊"]
            non_receipt_names = [name for name, row in expected_by_name.items() if row["category"] == "非票据"]
            clear_rows = [rows.get(name, {}) for name in clear_names]
            if len(clear_rows) != 8 or any(row.get("reviewReason") not in (None, "") for row in clear_rows):
                errors.append("8 clear rows must be complete without review reasons")
            if any(row.get("amountCents") is None for row in clear_rows):
                errors.append("8 clear rows must have amountCents")
            clear_total = sum(int(row.get("amountCents")) for row in clear_rows if row.get("amountCents") is not None)
            if clear_total != 38080 or manifest.get("recognizedTotalCents") != 38080:
                errors.append("recognized total must be 380.80 / 38080 cents")
            for name in ambiguous_names + non_receipt_names:
                row = rows.get(name, {})
                if not row.get("reviewReason") or row.get("amountCents") is not None:
                    errors.append("待核对 row must have a reason and no amount: " + name)

            if len(actual_images) != 10:
                errors.append("App package must contain 10 unique image payloads")
            if "票据整理结果" not in summary or "10" not in summary or "2" not in summary:
                errors.append("summary.html does not describe the result counts")
            if "http://" in summary.lower() or "https://" in summary.lower() or "<script" in summary.lower():
                errors.append("summary.html must remain local and script-free")

            if audit.get("schema") != "quiet-receipt-audit-snapshot-v1":
                errors.append("audit snapshot schema mismatch")
            audit_files = audit.get("files")
            if not isinstance(audit_files, list) or len(audit_files) != 12:
                errors.append("audit snapshot must cover all 12 source rows")
                audit_files = []
            source_hashes = {meta["sha256"] for meta in source_meta.values()}
            if any(item.get("sha256") not in source_hashes for item in audit_files if isinstance(item, dict)):
                errors.append("audit snapshot source hashes do not match input images")
            if any(not isinstance(item, dict) or not item.get("sourceIdHash") for item in audit_files):
                errors.append("audit snapshot contains an invalid source identity")
            raw_sensitive = [
                "ocr", "recognizedtext", "sourceuri", "content://", "account", "身份证",
            ] + [row["merchant"] for row in expected["records"] if row.get("merchant")]
            lowered_audit = archive.read("audit-snapshot").decode("utf-8", errors="replace").lower()
            if any(token.lower() in lowered_audit for token in raw_sensitive):
                errors.append("audit snapshot contains raw OCR or personal fields")
    except KeyError as exc:
        errors.append("missing App package member: " + str(exc))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", type=Path, required=True)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--expected", type=Path, required=True)
    parser.add_argument(
        "--mode",
        choices=("fixture", "app"),
        default="fixture",
        help="fixture validates the legacy generator package; app validates a real App-shaped result ZIP",
    )
    args = parser.parse_args()
    try:
        errors = verify_app(args) if args.mode == "app" else verify(args)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        print("FAIL: " + str(exc))
        return 1
    if errors:
        print("FAIL")
        for error in errors:
            print("- " + error)
        return 1
    if args.mode == "app":
        print("PASS: real App receipt ZIP, 12 source rows, 10 unique payloads, duplicate mappings, metadata, audit redaction, and 380.80 total verified")
    else:
        print("PASS: fixture receipt manifest, CSV, ZIP, source hashes, duplicate mappings, audit redaction, provenance, and amount total verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
