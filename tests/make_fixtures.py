#!/usr/bin/env python3
"""Create deterministic, offline input data for the quiet-agent MVP.

The command writes ``<output>/source`` and a sibling ``<output>/expected.json``.
The snapshot is deliberately outside the source tree so a careless archive walk
cannot include its oracle.

Examples:
    python make_fixtures.py --mode small --output-dir .tmp/fixture-small
    python make_fixtures.py --mode stress --output-dir .tmp/fixture-stress
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import zlib
from datetime import datetime, timezone


FIXTURE_VERSION = 1
STRESS_BYTES = 32 * 1024 * 1024


def png_1x1() -> bytes:
    """Return a tiny valid RGBA PNG without third-party libraries."""
    signature = b"\x89PNG\r\n\x1a\n"

    def chunk(kind: bytes, payload: bytes) -> bytes:
        return (struct.pack(">I", len(payload)) + kind + payload +
                struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF))

    # One opaque blue pixel, encoded as a scanline with filter byte 0.
    return signature + chunk(b"IHDR", struct.pack(">IIBBBBB", 1, 1, 8, 6, 0, 0, 0)) + chunk(
        b"IDAT", zlib.compress(b"\x00\x2b\x74\xff\xff")) + chunk(b"IEND", b"")


def minimal_pdf() -> bytes:
    """Return a small valid PDF with one text line."""
    stream = b"BT /F1 12 Tf 20 60 Td (offline fixture) Tj ET\n"
    objects = [
        b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
        b"2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
        b"3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n",
        b"4 0 obj\n<< /Length " + str(len(stream)).encode("ascii") + b" >>\nstream\n" + stream + b"endstream\nendobj\n",
        b"5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n",
    ]
    out = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets = [0]
    for obj in objects:
        offsets.append(len(out))
        out.extend(obj)
    xref = len(out)
    out.extend((f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n" +
                "".join(f"{pos:010d} 00000 n \n" for pos in offsets[1:]) +
                f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n").encode("ascii"))
    return bytes(out)


def deterministic_bytes(size: int, seed: str) -> bytes:
    """Stream-like deterministic bytes, independent of Python hash randomization."""
    block = hashlib.sha256(("quiet-agent-fixture:" + seed).encode("utf-8")).digest()
    repeats, remainder = divmod(size, len(block))
    return block * repeats + block[:remainder]


def write_file(path: Path, data: bytes, mtime: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    os.utime(path, (mtime, mtime))


def make_small(source: Path) -> list[dict]:
    files: list[tuple[str, bytes, int]] = []
    jan = int(datetime(2024, 1, 15, 10, 0, tzinfo=timezone.utc).timestamp())
    feb = int(datetime(2024, 2, 20, 11, 30, tzinfo=timezone.utc).timestamp())
    mar = int(datetime(2024, 3, 25, 12, 0, tzinfo=timezone.utc).timestamp())
    # Duplicate bytes under different names; same name in different directories.
    same = "重复内容：offline and deterministic\n".encode("utf-8")
    files.extend([
        ("docs/readme-样例.txt", "这是离线夹具，不是用户资料。\n中文输入与 emoji: 📁✅\n".encode("utf-8"), jan),
        ("docs/duplicate-a.txt", same, jan),
        ("copies/duplicate-b.txt", same, feb),
        ("same-name/one/report.txt", b"same basename, first directory\n", feb),
        ("same-name/two/report.txt", b"same basename, second directory\n", mar),
        ("images/蓝色-📷.png", png_1x1(), mar),
        ("pdf/最小有效-样例.pdf", minimal_pdf(), jan),
        ("tables/data-样例.csv", "名称,数值\n苹果,1\n茶,2\n".encode("utf-8"), feb),
        ("empty/空文件.txt", b"", mar),
    ])
    for relative, data, mtime in files:
        write_file(source / relative, data, mtime)
    return snapshot(source)


def make_stress(source: Path) -> list[dict]:
    # Exactly 100 files and exactly 32 MiB, while retaining the small fixture's
    # meaningful file kinds. The first 9 are small, remaining files are blobs.
    small = make_small(source)
    existing_size = sum(item["size"] for item in small)
    remaining = STRESS_BYTES - existing_size
    count = 100 - len(small)
    base, extra = divmod(remaining, count)
    epoch = int(datetime(2024, 4, 10, 9, 0, tzinfo=timezone.utc).timestamp())
    for index in range(count):
        size = base + (1 if index < extra else 0)
        # A pair of same-content stress files exercises hash deduplication while
        # preserving the exact target byte count.
        seed = "stress-duplicate" if index in (0, 1) else f"stress-{index:03d}"
        data = deterministic_bytes(size, seed)
        relative = f"bulk/part-{index + 1:03d}.bin"
        write_file(source / relative, data, epoch + index)
    result = snapshot(source)
    if len(result) != 100 or sum(item["size"] for item in result) != STRESS_BYTES:
        raise AssertionError("stress fixture invariant failed")
    return result


def snapshot(source: Path) -> list[dict]:
    rows = []
    for path in sorted((p for p in source.rglob("*") if p.is_file()), key=lambda p: p.relative_to(source).as_posix()):
        relative = path.relative_to(source).as_posix()
        digest = hashlib.sha256()
        size = 0
        with path.open("rb") as handle:
            while chunk := handle.read(1024 * 1024):
                digest.update(chunk)
                size += len(chunk)
        rows.append({
            "relative_path": relative,
            "sha256": digest.hexdigest(),
            "size": size,
            "mtime_epoch": int(path.stat().st_mtime),
        })
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("small", "stress"), default="small")
    parser.add_argument("--output-dir", "-o", type=Path, required=True,
                        help="artifact directory; source/ and expected.json are created inside")
    args = parser.parse_args()
    root = args.output_dir.resolve()
    source = root / "source"
    if source.exists() or (root / "expected.json").exists():
        raise SystemExit(f"refusing to overwrite existing fixture directory: {root}")
    source.mkdir(parents=True, exist_ok=False)
    records = make_small(source) if args.mode == "small" else make_stress(source)
    expected = {
        "fixture_version": FIXTURE_VERSION,
        "mode": args.mode,
        "source_dir": "source",
        "file_count": len(records),
        "total_bytes": sum(row["size"] for row in records),
        "files": records,
    }
    (root / "expected.json").write_text(json.dumps(expected, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"source": str(source), "expected": str(root / "expected.json"),
                      "file_count": len(records), "total_bytes": expected["total_bytes"]}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
