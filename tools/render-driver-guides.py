#!/usr/bin/env python3
"""Render the two short Markdown guides to printable PDFs (requires reportlab)."""

from __future__ import annotations

import html
import re
from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.enums import TA_CENTER, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    HRFlowable,
    Image,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs" / "guides"
OUTPUT = ROOT / "output" / "pdf"
FONT_DIR = Path("/System/Library/Fonts/Supplemental")
INK = colors.HexColor("#18242B")
MUTED = colors.HexColor("#53636C")
GREEN = colors.HexColor("#176354")
PALE = colors.HexColor("#E9F2EF")
LINE = colors.HexColor("#D9E3DF")


def setup_fonts() -> None:
    pdfmetrics.registerFont(TTFont("Guide", str(FONT_DIR / "Arial.ttf")))
    pdfmetrics.registerFont(TTFont("GuideBold", str(FONT_DIR / "Arial Bold.ttf")))
    pdfmetrics.registerFontFamily("Guide", normal="Guide", bold="GuideBold")


def styles() -> dict[str, ParagraphStyle]:
    base = ParagraphStyle(
        "body", fontName="Guide", fontSize=10.2, leading=14.9,
        textColor=INK, spaceAfter=8,
    )
    return {
        "body": base,
        "title": ParagraphStyle(
            "title", parent=base, fontName="GuideBold", fontSize=23,
            leading=28, textColor=INK, spaceAfter=8,
        ),
        "subtitle": ParagraphStyle(
            "subtitle", parent=base, fontSize=9.2, leading=13,
            textColor=MUTED, spaceAfter=15,
        ),
        "section": ParagraphStyle(
            "section", parent=base, fontName="GuideBold", fontSize=14.2,
            leading=19, textColor=GREEN, spaceBefore=12, spaceAfter=8,
            keepWithNext=True,
        ),
        "bullet": ParagraphStyle(
            "bullet", parent=base, leftIndent=16, firstLineIndent=-10,
            spaceAfter=5,
        ),
        "number": ParagraphStyle(
            "number", parent=base, leftIndent=18, firstLineIndent=-14,
            spaceAfter=7,
        ),
        "table": ParagraphStyle(
            "table", parent=base, fontSize=9.1, leading=12.5,
            spaceAfter=0,
        ),
        "tableHeader": ParagraphStyle(
            "tableHeader", parent=base, fontName="GuideBold", fontSize=9.1,
            leading=12.5, textColor=colors.white, spaceAfter=0,
        ),
        "caption": ParagraphStyle(
            "caption", parent=base, fontSize=8.8, leading=11.5,
            alignment=TA_CENTER, textColor=MUTED, spaceAfter=5,
        ),
    }


def inline(text: str) -> str:
    text = html.escape(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", text)
    text = re.sub(r"`(.+?)`", r'<font name="GuideBold">\1</font>', text)
    text = re.sub(r"(?<![\w\"=])(https://[^\s<]+)",
                  r'<link href="\1" color="#176354">\1</link>', text)
    return text


def image_flow(path: Path, caption: str, max_width: float, max_height: float, ss: dict) -> list:
    reader = ImageReader(str(path))
    width, height = reader.getSize()
    scale = min(max_width / width, max_height / height)
    picture = Image(str(path), width=width * scale, height=height * scale)
    picture.hAlign = "CENTER"
    return [picture, Spacer(1, 4), Paragraph(inline(caption), ss["caption"])]


def image_group(images: list[tuple[Path, str]], ss: dict, available: float) -> list:
    if len(images) == 1:
        return [KeepTogether(image_flow(images[0][0], images[0][1], 185, 210, ss)), Spacer(1, 5)]
    cell_width = (available - (len(images) - 1) * 8) / len(images)
    cells = [image_flow(path, caption, cell_width - 10, 265, ss) for path, caption in images]
    table = Table([cells], colWidths=[cell_width] * len(cells), hAlign="CENTER")
    table.setStyle(TableStyle([
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("BACKGROUND", (0, 0), (-1, -1), colors.HexColor("#F7F9F8")),
        ("LEFTPADDING", (0, 0), (-1, -1), 5),
        ("RIGHTPADDING", (0, 0), (-1, -1), 5),
        ("TOPPADDING", (0, 0), (-1, -1), 8),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 8),
    ]))
    return [table, Spacer(1, 8)]


def markdown_table(rows: list[list[str]], ss: dict, available: float) -> Table:
    count = len(rows[0])
    if count == 3:
        widths = [available * 0.14, available * 0.35, available * 0.51]
    elif count == 2:
        widths = [available * 0.80, available * 0.20]
    else:
        widths = [available / count] * count
    data = [
        [Paragraph(inline(cell), ss["tableHeader"] if index == 0 else ss["table"]) for cell in row]
        for index, row in enumerate(rows)
    ]
    table = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
    table.setStyle(TableStyle([
        ("BACKGROUND", (0, 0), (-1, 0), GREEN),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, colors.HexColor("#F5F8F7")]),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("LINEBELOW", (0, 0), (-1, 0), 0.4, GREEN),
        ("LINEBELOW", (0, 1), (-1, -1), 0.35, LINE),
        ("LEFTPADDING", (0, 0), (-1, -1), 8),
        ("RIGHTPADDING", (0, 0), (-1, -1), 8),
        ("TOPPADDING", (0, 0), (-1, -1), 7),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 7),
    ]))
    return table


def parse(source: Path, ss: dict, available: float) -> list:
    lines = source.read_text(encoding="utf-8").splitlines()
    result: list = []
    i = 0
    first_paragraph = True
    while i < len(lines):
        line = lines[i].strip()
        if not line:
            i += 1
            continue
        if line == "<!-- pagebreak -->":
            result.append(PageBreak())
        elif line.startswith("# "):
            result.append(Paragraph(inline(line[2:]), ss["title"]))
            result.append(HRFlowable(width="100%", thickness=2, color=GREEN, spaceAfter=11))
        elif line.startswith("## "):
            result.append(Paragraph(inline(line[3:]), ss["section"]))
        elif line.startswith("| "):
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                cells = [part.strip() for part in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r"[-:]+", cell) for cell in cells):
                    rows.append(cells)
                i += 1
            result.append(markdown_table(rows, ss, available))
            result.append(Spacer(1, 9))
            continue
        elif line.startswith("!["):
            images = []
            while i < len(lines) and lines[i].strip().startswith("!["):
                match = re.fullmatch(r"!\[(.*)\]\((.*)\)", lines[i].strip())
                if match is None:
                    raise ValueError(f"Invalid image line: {lines[i]}")
                images.append(((source.parent / match.group(2)).resolve(), match.group(1)))
                i += 1
            result.extend(image_group(images, ss, available))
            continue
        elif line.startswith("- "):
            result.append(Paragraph("•  " + inline(line[2:]), ss["bullet"]))
        elif re.match(r"\d+\. ", line):
            result.append(Paragraph(inline(line), ss["number"]))
        else:
            result.append(Paragraph(inline(line), ss["subtitle"] if first_paragraph else ss["body"]))
            first_paragraph = False
        i += 1
    return result


def page_frame(canvas, doc, label: str) -> None:
    canvas.saveState()
    canvas.setStrokeColor(LINE)
    canvas.setLineWidth(0.5)
    canvas.line(46, A4[1] - 39, A4[0] - 46, A4[1] - 39)
    canvas.setFont("Guide", 8)
    canvas.setFillColor(MUTED)
    canvas.drawString(46, A4[1] - 31, "ПРОФСТРОЙ  /  АРМ ВОДИТЕЛЯ")
    canvas.line(46, 36, A4[0] - 46, 36)
    canvas.drawString(46, 25, label + "  •  16.11.2026")
    canvas.drawRightString(A4[0] - 46, 25, f"{doc.page}")
    canvas.restoreState()


def render(source_name: str, output_name: str, label: str) -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    source = SOURCE / source_name
    output = OUTPUT / output_name
    margin = 46
    available = A4[0] - 2 * margin
    ss = styles()
    doc = SimpleDocTemplate(
        str(output), pagesize=A4, leftMargin=margin, rightMargin=margin,
        topMargin=55, bottomMargin=49,
        title=label, author="Профстрой", subject="АРМ водителя",
    )
    doc.build(parse(source, ss, available),
              onFirstPage=lambda canvas, doc: page_frame(canvas, doc, label),
              onLaterPages=lambda canvas, doc: page_frame(canvas, doc, label))
    print(output)


if __name__ == "__main__":
    setup_fonts()
    render("phone-issuance.md", "arm-driver-phone-issuance.pdf", "Регламент выдачи телефона")
    render("driver-guide.md", "arm-driver-driver-guide.pdf", "Инструкция для водителя")
