#!/usr/bin/env python3
"""Subset PDF fonts to GB 8105 plus common Western/math glyphs.

Requires fontTools: `.tmp-fonttools\\Scripts\\python.exe scripts\\subset_pdf_fonts.py`
"""

from __future__ import annotations

import argparse
import re
import unicodedata
import urllib.request
from pathlib import Path

from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont


WIKISOURCE_RAW = (
    "https://zh.wikisource.org/w/index.php?"
    "title=%E9%80%9A%E7%94%A8%E8%A7%84%E8%8C%83%E6%B1%89%E5%AD%97%E8%A1%A8&action=raw"
)

# Western text, CJK punctuation/fullwidth forms, and the symbols commonly used by
# medical/science questions. Rare pictographs are deliberately omitted.
PRESERVED_RANGES = (
    (0x0020, 0x007E),  # Basic Latin
    (0x00A0, 0x024F),  # Latin-1 Supplement, Extended-A/B
    (0x0250, 0x02AF),  # IPA
    (0x02B0, 0x02FF),  # Spacing Modifier Letters
    (0x0300, 0x036F),  # Combining Diacritical Marks
    (0x0370, 0x03FF),  # Greek
    (0x0400, 0x052F),  # Cyrillic
    (0x1D00, 0x1DBF),  # Phonetic Extensions / Supplement
    (0x1DC0, 0x1DFF),  # Combining Diacritical Marks Supplement
    (0x1E00, 0x1EFF),  # Latin Extended Additional
    (0x1F00, 0x1FFF),  # Greek Extended
    (0x2000, 0x206F),  # General Punctuation
    (0x2070, 0x209F),  # Super/subscripts
    (0x20A0, 0x20CF),  # Currency Symbols
    (0x2100, 0x214F),  # Letterlike Symbols
    (0x2150, 0x218F),  # Number Forms
    (0x2190, 0x21FF),  # Arrows
    (0x2200, 0x22FF),  # Mathematical Operators
    (0x2300, 0x23FF),  # Miscellaneous Technical
    (0x2460, 0x24FF),  # Enclosed Alphanumerics
    (0x25A0, 0x25FF),  # Geometric Shapes
    (0x2600, 0x26FF),  # Miscellaneous Symbols
    (0x2700, 0x27BF),  # Dingbats
    (0x27C0, 0x27EF),  # Miscellaneous Mathematical Symbols-A
    (0x27F0, 0x27FF),  # Supplemental Arrows-A
    (0x2800, 0x28FF),  # Braille Patterns
    (0x2900, 0x297F),  # Supplemental Arrows-B
    (0x2980, 0x29FF),  # Miscellaneous Mathematical Symbols-B
    (0x2A00, 0x2AFF),  # Supplemental Mathematical Operators
    (0x2B00, 0x2BFF),  # Miscellaneous Symbols and Arrows
    (0x2C60, 0x2C7F),  # Latin Extended-C
    (0x3000, 0x303F),  # CJK Symbols and Punctuation
    (0xFE10, 0xFE1F),  # Vertical Forms
    (0xFE30, 0xFE4F),  # CJK Compatibility Forms
    (0xFE50, 0xFE6F),  # Small Form Variants
    (0xFF00, 0xFFEF),  # Halfwidth and Fullwidth Forms
    (0xA720, 0xA7FF),  # Latin Extended-D
    (0xAB30, 0xAB6F),  # Latin Extended-E
    (0x1D400, 0x1D7FF),  # Mathematical Alphanumeric Symbols
)

CJK_RANGES = (
    "\u3400-\u4DBF\u4E00-\u9FFF\uF900-\uFAFF"
    "\U00020000-\U0002FA1F\U00030000-\U0003134F"
)


def fetch_standard_chars(path: Path) -> list[str]:
    if not path.exists():
        request = urllib.request.Request(WIKISOURCE_RAW, headers={"User-Agent": "ShuaGeLeTi-font-subset"})
        with urllib.request.urlopen(request, timeout=30) as response:
            source = response.read().decode("utf-8")
        entries: dict[int, str] = {}
        for number, rest in re.findall(r"^:(\d{4})\s+(.+)$", source, flags=re.MULTILINE):
            index = int(number)
            if not 1 <= index <= 8105:
                continue
            match = re.search(f"[{CJK_RANGES}]", rest)
            if match:
                entries[index] = unicodedata.normalize("NFC", match.group(0))
        if len(entries) != 8105:
            missing = [i for i in range(1, 8106) if i not in entries]
            raise RuntimeError(f"规范汉字表解析不完整：{len(entries)}/8105，缺失编号：{missing[:80]}")
        chars = [entries[i] for i in range(1, 8106)]
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("\n".join(chars) + "\n", encoding="utf-8")
    chars = [line.strip() for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(chars) != 8105:
        raise RuntimeError(f"{path} 应包含 8105 字，实际 {len(chars)}")
    return chars


def allowed_codepoints(chars: list[str]) -> set[int]:
    points = {ord(char) for char in chars}
    points.add(ord("〇"))
    for start, end in PRESERVED_RANGES:
        points.update(range(start, end + 1))
    return points


def subset_font(source: Path, output: Path, points: set[int], required: set[int]) -> None:
    font = TTFont(source, recalcBBoxes=False, recalcTimestamp=False)
    cmap = font.getBestCmap() or {}
    missing_required = sorted(required - cmap.keys())
    if missing_required:
        sample = "".join(chr(i) for i in missing_required[:30])
        raise RuntimeError(f"{source.name} 缺少 {len(missing_required)} 个必需字符：{sample}")

    options = Options()
    options.layout_features = ["*"]
    options.name_languages = ["*"]
    options.notdef_glyph = True
    options.notdef_outline = True
    options.recommended_glyphs = True
    options.symbol_cmap = True
    options.legacy_cmap = True
    options.recalc_bounds = False
    options.recalc_timestamp = False

    subsetter = Subsetter(options=options)
    subsetter.populate(unicodes=points)
    subsetter.subset(font)
    output.parent.mkdir(parents=True, exist_ok=True)
    font.save(output)
    font.close()

    subset_cmap = TTFont(output).getBestCmap() or {}
    missing_after_subset = sorted((points & cmap.keys()) - subset_cmap.keys())
    if missing_after_subset:
        sample = "".join(chr(i) for i in missing_after_subset[:30])
        raise RuntimeError(f"{output.name} 子集丢失 {len(missing_after_subset)} 个字符：{sample}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", action="append", required=True, help="source font path")
    parser.add_argument("--out-dir", default=".tmp/pdf-fonts")
    parser.add_argument("--chars", default="scripts/gb8105.txt")
    args = parser.parse_args()

    chars = fetch_standard_chars(Path(args.chars))
    required = {ord(char) for char in chars} | {ord("〇")}
    points = allowed_codepoints(chars)
    print(f"GB8105={len(chars)} allowed_codepoints={len(points)}")

    for raw in args.font:
        source = Path(raw)
        output = Path(args.out_dir) / source.name
        before = source.stat().st_size
        subset_font(source, output, points, required)
        after = output.stat().st_size
        print(f"{source.name}: {before:,} -> {after:,} bytes ({after * 100 / before:.1f}%)")


if __name__ == "__main__":
    main()
