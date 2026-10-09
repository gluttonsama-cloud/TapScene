#!/usr/bin/env python3
"""Regenerate synthetic UI inputs. Pillow/font needed here, never for CI evaluation."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, __version__ as pillow_version

ROOT = Path(__file__).resolve().parent
DEFAULT_FONT = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--font", type=Path, default=Path(DEFAULT_FONT))
    parser.add_argument("--output", type=Path, default=ROOT / "fixtures")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    specs = [
        ("normal", "core", 250, 30, [
            ("创建演示", 40, 36, 32), ("选择画面", 40, 140, 26),
            ("上一步", 52, 326, 26), ("下一步", 420, 326, 26)]),
        ("mixed", "core", 250, 30, [
            ("项目 Demo 2026", 40, 36, 30), ("步骤 03 / 12", 40, 140, 26),
            ("保存为 PNG", 40, 252, 26)]),
        ("dark", "core", 24, 236, [
            ("隐私检查", 40, 36, 32), ("确认当前画面", 40, 140, 26),
            ("继续", 420, 326, 26)]),
        ("small", "stress", 250, 38, [
            ("编辑步骤", 40, 40, 14), ("尚未确认", 40, 142, 12),
            ("返回列表", 420, 326, 14)]),
        ("low-contrast", "stress", 232, 198, [
            ("候选画面", 40, 36, 24), ("重新分析", 40, 142, 20),
            ("保留此帧", 420, 326, 20)]),
        ("icons-only", "negative", 250, 30, []),
    ]
    manifest = {
        "schema": 1,
        "purpose": "Synthetic host-only offline OCR checks; not Android screenshots or tap evidence.",
        "generator": "generate-fixtures.py",
        "pillow_version": pillow_version,
        "font": {
            "filename": args.font.name,
            "collection_index": 2,
            "sha256": hashlib.sha256(args.font.read_bytes()).hexdigest(),
            "license": "SIL Open Font License 1.1; rasterized synthetic text only, font not redistributed",
        },
        "fixtures": [],
    }
    for name, group, background, foreground, lines in specs:
        im = Image.new("L", (720, 440), background)
        draw = ImageDraw.Draw(im)
        truth = []
        for text, x, y, size in lines:
            font = ImageFont.truetype(str(args.font), size, index=2)
            box = list(draw.textbbox((x, y), text, font=font, anchor="lt"))
            draw.text((x, y), text, fill=foreground, font=font, anchor="lt")
            truth.append({"text": text, "box": box, "font_px": size})
        if name == "normal":
            for box in [(32, 304, 210, 386), (400, 304, 640, 386)]:
                draw.rounded_rectangle(box, radius=12, outline=160, width=2)
        if name == "dark":
            draw.rounded_rectangle((392, 304, 624, 386), radius=12, outline=120, width=2)
        if name == "icons-only":
            # Draw geometry, never Unicode icon glyphs: truth really contains no text.
            draw.line([(76, 74), (50, 100), (76, 126)], fill=foreground, width=5)
            draw.ellipse((278, 64, 326, 112), outline=foreground, width=5)
            draw.line((321, 107, 345, 131), fill=foreground, width=5)
            draw.line([(494, 86), (514, 108), (554, 66)], fill=foreground, width=5)
            draw.rounded_rectangle((50, 228, 170, 344), radius=14, outline=110, width=4)
            draw.line((82, 286, 138, 286), fill=foreground, width=5)
            draw.line((110, 258, 110, 314), fill=foreground, width=5)
        pgm = b"P5\n720 440\n255\n" + im.tobytes()
        filename = name + ".pgm.gz"
        (args.output / filename).write_bytes(gzip.compress(pgm, mtime=0))
        manifest["fixtures"].append({
            "name": name, "group": group, "file": filename, "width": 720, "height": 440,
            "pgm_sha256": hashlib.sha256(pgm).hexdigest(), "lines": truth,
        })
    (args.output / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(specs)} synthetic fixtures in {args.output}")


if __name__ == "__main__":
    main()
