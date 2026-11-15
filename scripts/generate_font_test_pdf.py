#!/usr/bin/env python3
"""Generate a PDF that exercises the bundled GB8105 PDF fonts."""

from __future__ import annotations

import argparse
import unicodedata
from datetime import datetime
from pathlib import Path

from fontTools.ttLib import TTFont as FontToolsFont
from reportlab.lib.pagesizes import A4
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas


GREEK_RANGES = ((0x0370, 0x03FF), (0x1F00, 0x1FFF))

MATH_SYMBOL_RANGES = (
    (0x2000, 0x206F),  # General punctuation
    (0x2070, 0x209F),  # Super/subscripts
    (0x20A0, 0x20CF),  # Currency
    (0x2100, 0x214F),  # Letterlike
    (0x2150, 0x218F),  # Number forms
    (0x2190, 0x21FF),  # Arrows
    (0x2200, 0x22FF),  # Mathematical operators
    (0x2300, 0x23FF),  # Engineering / technical
    (0x2460, 0x24FF),  # Enclosed alphanumerics
    (0x25A0, 0x25FF),  # Geometric shapes
    (0x2600, 0x26FF),  # Miscellaneous symbols
    (0x2700, 0x27BF),  # Dingbats
    (0x27C0, 0x27EF),  # Mathematical symbols A
    (0x27F0, 0x27FF),  # Supplemental arrows A
    (0x2800, 0x28FF),  # Braille
    (0x2900, 0x297F),  # Supplemental arrows B
    (0x2980, 0x29FF),  # Mathematical symbols B
    (0x2A00, 0x2AFF),  # Supplemental math operators
    (0x2B00, 0x2BFF),  # Symbols and arrows
    (0x3000, 0x303F),  # CJK punctuation
    (0xFE10, 0xFE1F),
    (0xFE30, 0xFE4F),
    (0xFE50, 0xFE6F),
    (0xFF00, 0xFFEF),
    (0x1D400, 0x1D7FF),  # Mathematical alphanumerics
)

PAGE_W, PAGE_H = A4
MARGIN = 30
HEADER_Y = PAGE_H - 24
FOOTER_Y = 18


def supported_codepoints(path: Path) -> set[int]:
    font = FontToolsFont(path)
    try:
        return set((font.getBestCmap() or {}).keys())
    finally:
        font.close()


def in_ranges(cp: int, ranges: tuple[tuple[int, int], ...]) -> bool:
    return any(start <= cp <= end for start, end in ranges)


def greek_letters(points: set[int]) -> list[int]:
    result = []
    for cp in points:
        if not in_ranges(cp, GREEK_RANGES):
            continue
        char = chr(cp)
        if unicodedata.category(char).startswith("L"):
            result.append(cp)
    return sorted(result)


def math_symbols(points: set[int]) -> list[int]:
    return sorted(
        cp for cp in points
        if in_ranges(cp, MATH_SYMBOL_RANGES)
        and unicodedata.category(chr(cp)) in {"Sm", "Sk", "So", "Sc"}
    )


def draw_header(c: canvas.Canvas, section: str, subtitle: str) -> float:
    c.setFont("ShuaSans", 10)
    c.drawString(MARGIN, HEADER_Y, section)
    c.setFont("ShuaSerif", 7.5)
    c.drawRightString(PAGE_W - MARGIN, HEADER_Y, subtitle)
    c.setStrokeColorRGB(0.72, 0.68, 0.62)
    c.line(MARGIN, HEADER_Y - 5, PAGE_W - MARGIN, HEADER_Y - 5)
    return HEADER_Y - 18


def draw_footer(c: canvas.Canvas, page_number: int) -> None:
    c.setFont("ShuaSerif", 7)
    c.setFillColorRGB(0.35, 0.33, 0.30)
    c.drawCentredString(PAGE_W / 2, FOOTER_Y, f"- {page_number} -")
    c.setFillColorRGB(0, 0, 0)


def draw_grid_pages(
    c: canvas.Canvas,
    page_number: int,
    section: str,
    chars: list[str],
    font_name: str,
    columns: int,
    rows: int,
    font_size: float = 12.5,
    label: str = "",
) -> tuple[int, int]:
    per_page = columns * rows
    total = len(chars)
    for start in range(0, total, per_page):
        part = chars[start:start + per_page]
        top = draw_header(
            c,
            section,
            f"{label}  {start + 1}-{start + len(part)} / {total}",
        )
        cell_w = (PAGE_W - MARGIN * 2) / columns
        cell_h = (top - 24 - MARGIN) / rows
        c.setFont(font_name, font_size)
        for i, char in enumerate(part):
            col = i % columns
            row = i // columns
            x = MARGIN + col * cell_w + cell_w / 2
            y = top - (row + 1) * cell_h + (cell_h - font_size) / 2 + 2
            c.drawCentredString(x, y, char)
        draw_footer(c, page_number)
        c.showPage()
        page_number += 1
    return page_number, total


def draw_symbol_pages(
    c: canvas.Canvas,
    page_number: int,
    title: str,
    codepoints: list[int],
    sans_points: set[int],
    serif_points: set[int],
) -> tuple[int, int]:
    columns, rows = 7, 13
    per_page = columns * rows
    for start in range(0, len(codepoints), per_page):
        part = codepoints[start:start + per_page]
        top = draw_header(c, title, f"{start + 1}-{start + len(part)} / {len(codepoints)}")
        cell_w = (PAGE_W - MARGIN * 2) / columns
        cell_h = (top - 24 - MARGIN) / rows
        for i, cp in enumerate(part):
            col = i % columns
            row = i // columns
            x = MARGIN + col * cell_w
            y = top - (row + 1) * cell_h
            char = chr(cp)
            available_sans = cp in sans_points
            available_serif = cp in serif_points
            draw_font = "ShuaSans" if available_sans else "ShuaSerif"
            c.setFont(draw_font, 15)
            c.drawCentredString(x + cell_w / 2, y + 18, char)
            c.setFont("ShuaSerif", 5.8)
            c.setFillColorRGB(0.35, 0.33, 0.30)
            c.drawCentredString(x + cell_w / 2, y + 9, f"U+{cp:04X}")
            c.drawCentredString(
                x + cell_w / 2,
                y + 2,
                ("S" if available_sans else "-") + "/" +
                ("R" if available_serif else "-"),
            )
            c.setFillColorRGB(0, 0, 0)
        draw_footer(c, page_number)
        c.showPage()
        page_number += 1
    return page_number, len(codepoints)


def draw_formula_page(c: canvas.Canvas, page_number: int) -> int:
    top = draw_header(c, "数学公式与工程符号样张", "formula samples")
    formulas = [
        "二次公式：x = (-b ± √(b² - 4ac)) / 2a",
        "欧拉恒等式：e^(iπ) + 1 = 0",
        "积分：∫(0→∞) e^(−x²) dx = √π / 2",
        "微分：d/dx f(x) = lim_(h→0) [f(x+h) − f(x)] / h",
        "偏微分：∂u/∂t = α ∇²u",
        "麦克斯韦：∇·E = ρ/ε0    ∇×B = μ0J + μ0ε0 ∂E/∂t",
        "薛定谔：i h(bar) ∂ψ/∂t = Hψ",
        "热力学：dU = T dS − p dV",
        "矢量：A · B = |A||B| cos θ    A × B = |A||B| sin θ n",
        "集合：A ∪ B    A ∩ B    A ⊆ B    x ∈ A    A ⊄ B",
        "逻辑：∀x ∈ A, ∃y ∈ B, P ⇒ Q, P ⇔ Q",
        "电路：U = IR    P = UI    Z = √(R² + X²)",
        "化学：2H2 + O2 → 2H2O    Ca^2+ + CO3^2− → CaCO3↓",
        "统计：mean(x) ± s    μ ≠ 0    p ≤ 0.05    χ² 检验",
        "矩阵：A = [[a11, a12], [a21, a22]]    det(A) = a11a22 − a12a21",
    ]
    y = top - 20
    for formula in formulas:
        c.setFont("ShuaSerif", 11)
        c.drawString(MARGIN, y, formula)
        y -= 30
    draw_footer(c, page_number)
    c.showPage()
    return page_number + 1


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="output/pdf/字体覆盖测试-8105-西文-数学符号.pdf")
    parser.add_argument("--sans", default="app/src/main/assets/pdf/NotoSansSC-Bold.ttf")
    parser.add_argument("--serif", default="app/src/main/assets/pdf/NotoSerifSC-Regular.ttf")
    parser.add_argument("--chars", default="scripts/gb8105.txt")
    args = parser.parse_args()

    sans_path = Path(args.sans)
    serif_path = Path(args.serif)
    chars = [line.strip() for line in Path(args.chars).read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(chars) != 8105:
        raise RuntimeError(f"GB8105 count is {len(chars)}, expected 8105")

    pdfmetrics.registerFont(TTFont("ShuaSans", str(sans_path)))
    pdfmetrics.registerFont(TTFont("ShuaSerif", str(serif_path)))
    sans_points = supported_codepoints(sans_path)
    serif_points = supported_codepoints(serif_path)
    greek = greek_letters(sans_points | serif_points)
    math = math_symbols(sans_points | serif_points)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    c = canvas.Canvas(str(output), pagesize=A4)
    c.setTitle("刷了个题 - 字体覆盖测试")
    c.setAuthor("ShuaGeLeTi")

    c.setFont("ShuaSans", 22)
    c.drawCentredString(PAGE_W / 2, PAGE_H - 110, "字体覆盖测试文档")
    c.setFont("ShuaSerif", 12)
    c.drawCentredString(PAGE_W / 2, PAGE_H - 142, "Noto Sans SC / Noto Serif SC - GB8105 子集")
    c.setFont("ShuaSerif", 10)
    summary = [
        f"规范汉字：{len(chars)} 字，两种字体各完整展示一次",
        f"希腊字母：{len(greek)} 个（字体支持集合）",
        f"数学/工程/特殊符号：{len(math)} 个（字体支持集合）",
        "标记说明：S = Sans 支持，R = Serif 支持",
        f"生成时间：{datetime.now().strftime('%Y-%m-%d %H:%M:%S')}",
    ]
    y = PAGE_H - 205
    for line in summary:
        c.drawString(95, y, line)
        y -= 22
    c.setFont("ShuaSerif", 8)
    c.drawString(95, 90, "字符来源：scripts/gb8105.txt；符号范围：scripts/subset_pdf_fonts.py")
    c.showPage()
    page = 2

    page, _ = draw_grid_pages(
        c, page, "全部规范汉字 - Serif", chars, "ShuaSerif", 16, 40, 12.5, "汉字"
    )
    page, _ = draw_grid_pages(
        c, page, "全部规范汉字 - Sans", chars, "ShuaSans", 16, 40, 12.5, "汉字"
    )
    page, _ = draw_symbol_pages(c, page, "全部希腊字母", greek, sans_points, serif_points)
    page, _ = draw_symbol_pages(c, page, "数学 / 工程 / 特殊符号", math, sans_points, serif_points)
    page = draw_formula_page(c, page)
    c.save()
    print(f"output={output}")
    print(f"pages={page - 1} gb8105={len(chars)} greek={len(greek)} symbols={len(math)}")


if __name__ == "__main__":
    main()
