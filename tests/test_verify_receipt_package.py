#!/usr/bin/env python3
"""Pure-Python regression tests for the fixture and real-App package checkers."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent
GENERATOR = ROOT / "receipt_fixtures" / "generate_receipts.py"
VERIFIER = ROOT / "verify_receipt_package.py"
EXPECTED = ROOT / "receipt_fixtures" / "expected.json"


class ReceiptPackageVerifierTests(unittest.TestCase):
    def run_generator(self, output: Path, package_format: str) -> None:
        result = subprocess.run(
            [sys.executable, str(GENERATOR), "--output-dir", str(output), "--package-format", package_format],
            text=True,
            capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def run_verifier(self, output: Path, mode: str, package: Path | None = None) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(VERIFIER),
                "--mode",
                mode,
                "--package",
                str(package or (output / "receipt-package.zip")),
                "--source-dir",
                str(output / "source"),
                "--expected",
                str(EXPECTED),
            ],
            text=True,
            capture_output=True,
        )

    def test_legacy_fixture_self_check_remains_green(self) -> None:
        with tempfile.TemporaryDirectory(prefix="quiet-receipt-fixture-") as temp:
            output = Path(temp)
            self.run_generator(output, "fixture")
            result = self.run_verifier(output, "fixture")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("fixture receipt manifest", result.stdout)

    def test_real_app_bundle_has_required_metadata_and_rejects_oracle_leak(self) -> None:
        with tempfile.TemporaryDirectory(prefix="quiet-receipt-app-") as temp:
            output = Path(temp)
            self.run_generator(output, "app")
            result = self.run_verifier(output, "app")
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("10 unique payloads", result.stdout)

            leaked = output / "leaked.zip"
            with zipfile.ZipFile(output / "receipt-package.zip", "r") as source_zip, zipfile.ZipFile(leaked, "w") as target_zip:
                for info in source_zip.infolist():
                    target_zip.writestr(info, source_zip.read(info.filename))
                target_zip.writestr("expected.json", EXPECTED.read_bytes())
            failed = self.run_verifier(output, "app", leaked)
            self.assertNotEqual(failed.returncode, 0)
            self.assertIn("expected.json", failed.stdout)


if __name__ == "__main__":
    unittest.main()
