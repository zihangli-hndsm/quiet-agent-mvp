#!/usr/bin/env python3
"""Generate deterministic, fictional Chinese receipt images and a package.

The generated source is intentionally small and self-contained.  The oracle
used by the independent verifier lives beside this generator in tests/.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import shutil
import zipfile
from datetime import datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFilter, ImageFont


ROOT = Path(__file__).resolve().parent
RESOURCE_FILE = ROOT / "resources.json"
FONT_CANDIDATES = [
    Path(r"C:\Windows\Fonts\msyh.ttc"),
    Path(r"C:\Windows\Fonts\simhei.ttf"),
    Path(r"/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"),
    Path(r"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
]

RECORDS = [
    ("receipt-01.png", "清晰", "thermal", "青禾便利店", "2024-03-01", "18.80"),
    ("receipt-02.png", "清晰", "thermal", "海风咖啡", "2024-03-02", "42.50"),
    ("receipt-03.png", "清晰", "formal", "星桥书店", "2024-03-03", "9.90"),
    ("receipt-04.png", "清晰", "formal", "南站便利", "2024-03-04", "128.00"),
    ("receipt-05.png", "清晰", "card", "小满食堂", "2024-03-05", "36.60"),
    ("receipt-06.png", "清晰", "card", "云朵超市", "2024-03-06", "75.00"),
    ("receipt-07.png", "清晰", "thermal", "纸飞机文具", "2024-03-07", "16.80"),
    ("receipt-08.png", "清晰", "formal", "溪谷药房", "2024-03-08", "53.20"),
    ("duplicate-01.png", "重复", "thermal", "青禾便利店", "2024-03-01", "18.80"),
    ("duplicate-02.png", "重复", "formal", "星桥书店", "2024-03-03", "9.90"),
    ("ambiguous-amount.png", "金额模糊", "card", "晚风小馆", "2024-03-09", None),
    ("not-a-receipt.png", "非票据", "card", "城市活动通知", "2024-03-10", None),
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [FONT_CANDIDATES[1] if bold else FONT_CANDIDATES[0]] + FONT_CANDIDATES
    for path in candidates:
        if path.exists():
            try:
                return ImageFont.truetype(str(path), size=size, index=0)
            except OSError:
                pass
    return ImageFont.load_default()


def centered(draw: ImageDraw.ImageDraw, text: str, y: int, width: int, fnt: Any, fill: str) -> None:
    box = draw.textbbox((0, 0), text, font=fnt)
    draw.text(((width - (box[2] - box[0])) // 2, y), text, font=fnt, fill=fill)


def machine_summary(draw: ImageDraw.ImageDraw, merchant: str, date: str, amount: str | None, y: int) -> None:
    """A high-contrast receipt block that remains readable after camera-like layout segmentation."""
    fnt = font(34, True)
    draw.text((90, y), "MERCHANT: " + merchant, font=fnt, fill="#111111")
    draw.text((90, y + 52), "DATE: " + date, font=fnt, fill="#111111")
    draw.text((90, y + 104), "TOTAL: " + (("CNY " + amount) if amount else "NEEDS CHECK"), font=fnt, fill="#111111")


def thermal(path: Path, merchant: str, date: str, amount: str) -> None:
    image = Image.new("RGB", (900, 1250), "#f8f7f2")
    d = ImageDraw.Draw(image)
    title, body, small = font(52, True), font(30), font(24)
    centered(d, "商户名称：" + merchant, 58, image.width, title, "#173f36")
    centered(d, "消费凭证", 125, image.width, body, "#426158")
    d.line((75, 195, 825, 195), fill="#8ba59a", width=3)
    d.text((85, 230), "日期  " + date, font=body, fill="#263b35")
    d.text((85, 285), "流水号  Q" + date.replace("-", ""), font=small, fill="#53655f")
    d.line((85, 360, 815, 360), fill="#c8d2cc", width=2)
    rows = [("苹果气泡水", "12.00 元"), ("燕麦小饼干", "8.80 元")]
    for index, (label, value) in enumerate(rows):
        y = 415 + index * 70
        d.text((90, y), label, font=body, fill="#243c34")
        d.text((690, y), value, font=body, fill="#243c34")
    d.line((85, 575, 815, 575), fill="#8ba59a", width=3)
    d.text((90, 625), "合计", font=title, fill="#173f36")
    d.text((650, 625), amount + " 元", font=title, fill="#b34b32")
    d.text((90, 760), "谢谢惠顾，欢迎再次光临", font=small, fill="#52665e")
    machine_summary(d, merchant, date, amount, 880)
    d.text((90, 1130), "离线演示票据 · 虚构数据", font=small, fill="#7b8b84")
    image.save(path, format="PNG", optimize=False)


def formal(path: Path, merchant: str, date: str, amount: str) -> None:
    image = Image.new("RGB", (1250, 900), "#fffdf8")
    d = ImageDraw.Draw(image)
    title, body, small = font(46, True), font(28), font(22)
    d.rectangle((0, 0, 1250, 145), fill="#255c54")
    d.text((70, 38), "商户名称：" + merchant, font=title, fill="#ffffff")
    d.text((905, 52), "电子收据", font=body, fill="#d9efdf")
    d.text((80, 190), "收据编号", font=small, fill="#57736a")
    d.text((225, 190), "F-" + date.replace("-", ""), font=body, fill="#233f37")
    d.text((790, 190), "日期", font=small, fill="#57736a")
    d.text((900, 190), date, font=body, fill="#233f37")
    d.rectangle((70, 275, 1180, 360), fill="#e8f1ec")
    d.text((105, 302), "项目", font=body, fill="#255c54")
    d.text((850, 302), "金额", font=body, fill="#255c54")
    d.line((70, 360, 1180, 360), fill="#8eaaa0", width=2)
    d.text((105, 405), "日常用品与服务", font=body, fill="#263f38")
    d.text((850, 405), amount + " 元", font=body, fill="#263f38")
    d.line((70, 490, 1180, 490), fill="#c2d4cc", width=2)
    d.text((740, 545), "合计", font=title, fill="#255c54")
    d.text((925, 545), amount + " 元", font=title, fill="#bd4d32")
    machine_summary(d, merchant, date, amount, 640)
    d.text((80, 825), "本票据为离线演示数据，内容虚构", font=small, fill="#73867e")
    image.save(path, format="PNG", optimize=False)


def card(path: Path, merchant: str, date: str, amount: str | None, non_receipt: bool = False) -> None:
    image = Image.new("RGB", (1000, 1000), "#f7fbff")
    d = ImageDraw.Draw(image)
    title, body, small = font(44, True), font(30), font(22)
    d.rounded_rectangle((55, 55, 945, 945), radius=36, fill="#ffffff", outline="#77a8b8", width=5)
    d.rounded_rectangle((55, 55, 945, 220), radius=36, fill="#197b84")
    d.rectangle((55, 150, 945, 220), fill="#197b84")
    d.text((100, 98), (merchant if non_receipt else "商户名称：" + merchant), font=title, fill="#ffffff")
    if non_receipt:
        d.text((100, 285), "活动安排通知", font=title, fill="#1d5364")
        d.text((100, 390), "周六 · 城市公共阅读会", font=body, fill="#274858")
        d.text((100, 475), "时间 14:00      地点 河畔大厅", font=body, fill="#274858")
        d.text((100, 625), "这是一张通知卡片，不是消费票据。", font=body, fill="#b34b32")
    else:
        d.text((100, 285), "扫码消费凭证", font=title, fill="#1d5364")
        d.text((100, 390), "日期", font=small, fill="#65808b")
        d.text((250, 385), date, font=body, fill="#274858")
        d.text((100, 480), "项目", font=small, fill="#65808b")
        d.text((250, 475), "套餐与饮品", font=body, fill="#274858")
        d.line((100, 575, 900, 575), fill="#c0d5dc", width=3)
        d.text((100, 645), "应付金额", font=body, fill="#1d5364")
        d.text((600, 635), (amount or "??.??") + " 元", font=title, fill="#bd4d32")
        machine_summary(d, merchant, date, amount, 720)
    d.text((100, 920), "离线演示票据 · 虚构数据", font=small, fill="#6c8490")
    image.save(path, format="PNG", optimize=False)


def make_image(path: Path, category: str, layout: str, merchant: str, date: str, amount: str | None) -> None:
    if category == "非票据":
        card(path, merchant, date, None, non_receipt=True)
    elif layout == "thermal":
        thermal(path, merchant, date, amount or "??.??")
    elif layout == "formal":
        formal(path, merchant, date, amount or "??.??")
    else:
        card(path, merchant, date, amount)
    if category == "金额模糊":
        original = Image.open(path).convert("RGB")
        # The amount region is intentionally unreadable while the rest remains sharp.
        crop = original.crop((520, 570, 950, 760)).filter(ImageFilter.GaussianBlur(radius=13))
        original.paste(crop, (520, 570))
        original.save(path, format="PNG", optimize=False)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument(
        "--package-format",
        choices=("fixture", "app"),
        default="fixture",
        help="fixture keeps the legacy self-check package; app emits the real App-shaped result bundle",
    )
    args = parser.parse_args()
    root = args.output_dir.resolve()
    if root.exists() and any(root.iterdir()):
        raise SystemExit(f"refusing to overwrite non-empty output: {root}")
    source = root / "source"
    source.mkdir(parents=True, exist_ok=False)
    generated: dict[str, dict[str, Any]] = {}
    for filename, category, layout, merchant, date, amount in RECORDS[:8]:
        path = source / filename
        make_image(path, category, layout, merchant, date, amount)
        generated[filename] = {"category": category, "layout": layout, "merchant": merchant, "date": date, "amount": amount}
    shutil.copyfile(source / "receipt-01.png", source / "duplicate-01.png")
    shutil.copyfile(source / "receipt-03.png", source / "duplicate-02.png")
    generated["duplicate-01.png"] = {"category": "重复", "layout": "thermal", "merchant": "青禾便利店", "date": "2024-03-01", "amount": "18.80", "duplicateOf": "receipt-01.png"}
    generated["duplicate-02.png"] = {"category": "重复", "layout": "formal", "merchant": "星桥书店", "date": "2024-03-03", "amount": "9.90", "duplicateOf": "receipt-03.png"}
    make_image(source / "ambiguous-amount.png", "金额模糊", "card", "晚风小馆", "2024-03-09", None)
    generated["ambiguous-amount.png"] = {"category": "金额模糊", "layout": "card", "merchant": "晚风小馆", "date": "2024-03-09", "amount": None}
    make_image(source / "not-a-receipt.png", "非票据", "card", "城市活动通知", "2024-03-10", None)
    generated["not-a-receipt.png"] = {"category": "非票据", "layout": "card", "merchant": "城市活动通知", "date": "2024-03-10", "amount": None}

    rows = []
    for filename, meta in generated.items():
        row = {"source_path": filename, "category": meta["category"], "layout": meta["layout"], "merchant": meta["merchant"], "date": meta["date"], "amount": meta["amount"] or "", "amount_status": "clear" if meta["amount"] else ("ambiguous" if meta["category"] == "金额模糊" else "not_applicable"), "duplicate_of": meta.get("duplicateOf", "")}
        rows.append(row)
    rows.sort(key=lambda item: item["source_path"])
    records = []
    source_ids = {row["source_path"]: sha256_text("fixture-source:" + row["source_path"]) for row in rows}
    for row in rows:
        path = source / row["source_path"]
        duplicate_of = row["duplicate_of"] or None
        review = None if row["category"] in ("清晰", "重复") else ("金额模糊，无法确认合计" if row["category"] == "金额模糊" else "无法确认这是票据")
        records.append({"sourceId": source_ids[row["source_path"]], "sourceName": row["source_path"], "sha256": sha256(path), "sizeBytes": path.stat().st_size, "archivePath": None if duplicate_of else "receipts/" + row["source_path"], "included": duplicate_of is None, "duplicateOf": source_ids.get(duplicate_of), "merchant": row["merchant"], "dateIso": row["date"], "amountCents": int(Decimal(row["amount"]) * 100) if row["amount"] else None, "reviewReason": review, "category": row["category"], "layout": row["layout"]})
    manifest = {"schema": "quiet-agent-receipt-manifest-v1", "source": "fictional-offline-receipts", "recognizedTotalCents": 38080, "rows": records}
    manifest_path = root / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    csv_path = root / "receipt.csv"
    with csv_path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, lineterminator="\r\n")
        writer.writerow(["source_id", "source_name", "sha256", "size_bytes", "archive_path", "included", "duplicate_of", "merchant", "date_iso", "amount_cents", "review_reason"])
        for record in records:
            writer.writerow([record["sourceId"], record["sourceName"], record["sha256"], record["sizeBytes"], record["archivePath"] or "", str(record["included"]).lower(), record["duplicateOf"] or "", record["merchant"], record["dateIso"], "" if record["amountCents"] is None else record["amountCents"], record["reviewReason"] or ""])
    audit_path = root / "audit.jsonl"
    with audit_path.open("w", encoding="utf-8", newline="\n") as handle:
        handle.write(json.dumps({"event": "fixture-created", "generator": "tests/receipt_fixtures/generate_receipts.py", "fixtureVersion": 1}, ensure_ascii=False) + "\n")
        for record in records:
            handle.write(json.dumps({"event": "source-snapshot", "sourceIdHash": sha256_text(record["sourceId"]), "sha256": record["sha256"], "size": record["sizeBytes"], "duplicateOfIdHash": None if record["duplicateOf"] is None else sha256_text(record["duplicateOf"])}, ensure_ascii=False) + "\n")
    provenance = {"generator": "tests/receipt_fixtures/generate_receipts.py", "generatorSha256": sha256(Path(__file__)), "resourceCatalog": "tests/receipt_fixtures/resources.json", "resourceCatalogSha256": sha256(RESOURCE_FILE), "fixtureVersion": 1, "layouts": ["thermal", "formal", "card"]}
    provenance_path = root / "provenance.json"
    provenance_path.write_text(json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    package = root / "receipt-package.zip"
    if args.package_format == "app":
        # Mirror ReceiptTaskRunner's public result package.  The fixture oracle
        # stays outside this ZIP and is never copied into the App-shaped output.
        app_manifest = {
            "schema": manifest["schema"],
            "source": manifest["source"],
            "recognizedTotalCents": manifest["recognizedTotalCents"],
            "rows": [
                {key: value for key, value in record.items()
                 if key not in ("category", "layout")}
                for record in records
            ],
        }
        (root / "manifest.json").write_text(
            json.dumps(app_manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        (root / "summary.html").write_text(
            "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            "<title>票据整理结果</title><h1>票据整理结果</h1>"
            "<p>写入图片 10 张，重复跳过 2 张，需要复核 2 张。</p>"
            "<p>自动识别金额：380.80 元；金额模糊和非票据各 1 张待核对。</p>",
            encoding="utf-8",
        )
        audit_snapshot = {
            "schema": "quiet-receipt-audit-snapshot-v1",
            "files": [
                {
                    "sourceIdHash": record["sourceId"],
                    "sha256": record["sha256"],
                    "size": record["sizeBytes"],
                    "duplicateOfIdHash": record["duplicateOf"],
                }
                for record in records
            ],
        }
        (root / "audit-snapshot").write_text(
            json.dumps(audit_snapshot, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8"
        )
    with zipfile.ZipFile(package, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for record in records:
            if record["included"]: archive.write(source / record["sourceName"], record["archivePath"])
        if args.package_format == "app":
            archive.write(csv_path, "receipts.csv")
            archive.write(root / "manifest.json", "manifest.json")
            archive.write(root / "summary.html", "summary.html")
            archive.write(root / "audit-snapshot", "audit-snapshot")
        else:
            for path in (csv_path, manifest_path, audit_path, provenance_path):
                archive.write(path, path.name)
    print(json.dumps({"package": str(package), "source": str(source), "manifest": str(manifest_path), "fileCount": len(records), "totalAmount": "380.80", "packageFormat": args.package_format}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
