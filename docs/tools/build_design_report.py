from __future__ import annotations

import html
import re
import sys
from pathlib import Path
from urllib.parse import unquote

from reportlab.graphics.shapes import Drawing, Line, Polygon, Rect, String
from reportlab.lib import colors
from reportlab.lib.colors import HexColor
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.lib.utils import ImageReader
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    Image as ReportImage,
    KeepTogether,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)


ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "docs" / "设计思路报告.md"
OUTPUT = ROOT / "docs" / "deliverables" / "银龄复诊事项协同助手-设计思路报告-v0.1.pdf"

GREEN = HexColor("#176B4A")
DARK_GREEN = HexColor("#124B38")
PALE_GREEN = HexColor("#ECF5F0")
IVORY = HexColor("#FAF8F1")
WARM = HexColor("#C86A3A")
TEXT = HexColor("#202723")
MUTED = HexColor("#627069")
BORDER = HexColor("#D9DED9")


def setup_fonts() -> None:
    pdfmetrics.registerFont(TTFont("Deng", r"C:\Windows\Fonts\Deng.ttf"))
    pdfmetrics.registerFont(TTFont("DengBold", r"C:\Windows\Fonts\Dengb.ttf"))
    pdfmetrics.registerFontFamily(
        "Deng",
        normal="Deng",
        bold="DengBold",
        italic="Deng",
        boldItalic="DengBold",
    )


def inline(text: str) -> str:
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", r"\1", text)
    escaped = html.escape(text, quote=False)
    escaped = re.sub(r"\*\*(.+?)\*\*", r"<b>\1</b>", escaped)
    escaped = re.sub(r"`(.+?)`", r'<font color="#176B4A">\1</font>', escaped)
    return escaped


def styles():
    base = getSampleStyleSheet()
    return {
        "cover_title": ParagraphStyle(
            "CoverTitle", parent=base["Title"], fontName="DengBold",
            fontSize=27, leading=39, textColor=DARK_GREEN, alignment=TA_CENTER,
            spaceAfter=15 * mm,
        ),
        "cover_meta": ParagraphStyle(
            "CoverMeta", parent=base["Normal"], fontName="Deng",
            fontSize=11.5, leading=21, textColor=TEXT, alignment=TA_CENTER,
        ),
        "h2": ParagraphStyle(
            "H2", parent=base["Heading2"], fontName="DengBold",
            fontSize=18, leading=25, textColor=DARK_GREEN,
            spaceBefore=4 * mm, spaceAfter=4 * mm, keepWithNext=True,
        ),
        "h3": ParagraphStyle(
            "H3", parent=base["Heading3"], fontName="DengBold",
            fontSize=13, leading=19, textColor=GREEN,
            spaceBefore=3.5 * mm, spaceAfter=2.3 * mm, keepWithNext=True,
        ),
        "body": ParagraphStyle(
            "Body", parent=base["BodyText"], fontName="Deng",
            fontSize=10.2, leading=16.8, textColor=TEXT, alignment=TA_JUSTIFY,
            firstLineIndent=2 * 10.2, spaceAfter=2.6 * mm,
        ),
        "bullet": ParagraphStyle(
            "Bullet", parent=base["BodyText"], fontName="Deng",
            fontSize=10.1, leading=16, textColor=TEXT, leftIndent=6 * mm,
            firstLineIndent=-4 * mm, spaceAfter=1.5 * mm,
        ),
        "note": ParagraphStyle(
            "Note", parent=base["BodyText"], fontName="Deng",
            fontSize=9.3, leading=15, textColor=MUTED, leftIndent=4 * mm,
            rightIndent=4 * mm, spaceBefore=2 * mm, spaceAfter=3 * mm,
        ),
        "caption": ParagraphStyle(
            "Caption", parent=base["BodyText"], fontName="Deng",
            fontSize=8.5, leading=12, textColor=MUTED, alignment=TA_CENTER,
            spaceBefore=2 * mm, spaceAfter=4 * mm,
        ),
        "table": ParagraphStyle(
            "Table", parent=base["BodyText"], fontName="Deng",
            fontSize=8.3, leading=12.5, textColor=TEXT, alignment=TA_LEFT,
        ),
        "table_head": ParagraphStyle(
            "TableHead", parent=base["BodyText"], fontName="DengBold",
            fontSize=8.5, leading=12.5, textColor=colors.white, alignment=TA_CENTER,
        ),
    }


def arrow(d: Drawing, x1: float, y1: float, x2: float, y2: float, color=GREEN) -> None:
    d.add(Line(x1, y1, x2, y2, strokeColor=color, strokeWidth=1.5))
    angle = 4
    if abs(x2 - x1) >= abs(y2 - y1):
        sign = 1 if x2 > x1 else -1
        d.add(Polygon([x2, y2, x2 - sign * 7, y2 + angle, x2 - sign * 7, y2 - angle], fillColor=color, strokeColor=color))
    else:
        sign = 1 if y2 > y1 else -1
        d.add(Polygon([x2, y2, x2 - angle, y2 - sign * 7, x2 + angle, y2 - sign * 7], fillColor=color, strokeColor=color))


def box(d: Drawing, x: float, y: float, w: float, h: float, title: str, subtitle: str = "", fill=PALE_GREEN) -> None:
    d.add(Rect(x, y, w, h, rx=8, ry=8, fillColor=fill, strokeColor=BORDER, strokeWidth=1))
    d.add(String(x + w / 2, y + h * 0.60, title, fontName="DengBold", fontSize=9.3, fillColor=DARK_GREEN, textAnchor="middle"))
    if subtitle:
        d.add(String(x + w / 2, y + h * 0.28, subtitle, fontName="Deng", fontSize=7.1, fillColor=MUTED, textAnchor="middle"))


def architecture_figure() -> Drawing:
    d = Drawing(500, 255)
    top_y = 194
    items = [(4, "老年用户", "文字或语音"), (128, "适老化界面", "对话与事项"), (252, "安全前置", "紧急与越界"), (376, "模型或规则", "回答与动作建议")]
    for x, title, sub in items:
        box(d, x, top_y, 110, 42, title, sub)
    for x in [114, 238, 362]:
        arrow(d, x, top_y + 21, x + 14, top_y + 21)

    box(d, 190, 127, 120, 44, "AgentRuntime", "权限与状态审核", fill=HexColor("#DCEDE4"))
    arrow(d, 431, top_y, 300, 171)

    box(d, 36, 54, 116, 44, "只读工具", "号源 材料 路线 位置")
    box(d, 192, 54, 116, 44, "Java业务状态机", "任务拆解与依赖失效")
    box(d, 348, 54, 116, 44, "确认门禁", "绑定当前操作快照", fill=HexColor("#F8EBDD"))
    arrow(d, 220, 127, 126, 98)
    arrow(d, 250, 127, 250, 98)
    arrow(d, 280, 127, 378, 98)

    box(d, 92, 2, 130, 34, "H2模拟数据库", "权威业务事实", fill=IVORY)
    box(d, 278, 2, 130, 34, "写入工具与轨迹", "预约 提醒 通知", fill=IVORY)
    arrow(d, 94, 54, 144, 36)
    arrow(d, 406, 54, 348, 36)
    arrow(d, 278, 19, 222, 19)
    return d


def task_state_figure() -> Drawing:
    d = Drawing(500, 175)
    nodes = {
        "NONE": (8, 113, "无任务", "自由交流"),
        "ACTIVE": (176, 113, "办理中", "保存当前步骤"),
        "PAUSED": (344, 113, "已暂停", "闲聊或临时查询"),
        "CONFIRM": (176, 45, "等待确认", "写操作门禁"),
        "DONE": (344, 45, "已完成", "结果来自工具"),
        "CANCEL": (8, 45, "已取消", "只取消未提交任务"),
    }
    for key, (x, y, title, sub) in nodes.items():
        fill = HexColor("#F8EBDD") if key in {"CONFIRM", "CANCEL"} else PALE_GREEN
        box(d, x, y, 140, 40, title, sub, fill=fill)
    arrow(d, 148, 133, 176, 133)
    arrow(d, 316, 133, 344, 133)
    arrow(d, 344, 121, 316, 121)
    arrow(d, 246, 113, 246, 85)
    arrow(d, 316, 65, 344, 65)
    arrow(d, 176, 65, 148, 65)
    d.add(String(162, 147, "明确预约目标", fontName="Deng", fontSize=7.2, fillColor=MUTED, textAnchor="middle"))
    d.add(String(330, 147, "插入交流", fontName="Deng", fontSize=7.2, fillColor=MUTED, textAnchor="middle"))
    d.add(String(330, 105, "继续办理", fontName="Deng", fontSize=7.2, fillColor=MUTED, textAnchor="middle"))
    return d


def tool_loop_figure() -> Drawing:
    d = Drawing(500, 154)
    labels = [
        ("1", "发送工具说明"), ("2", "模型提出调用"), ("3", "解析结构化动作"),
        ("4", "校验权限参数"), ("5", "程序执行工具"), ("6", "记录真实结果"),
        ("7", "更新回复与计划"),
    ]
    positions = [(4, 96), (128, 96), (252, 96), (376, 96), (66, 28), (190, 28), (314, 28)]
    for (num, label), (x, y) in zip(labels, positions):
        box(d, x, y, 110, 38, f"{num}  {label}", "", fill=PALE_GREEN if num not in {"4", "5"} else HexColor("#F8EBDD"))
    for x in [114, 238, 362]:
        arrow(d, x, 115, x + 14, 115)
    arrow(d, 431, 96, 121, 66)
    for x in [176, 300]:
        arrow(d, x, 47, x + 14, 47)
    return d


FIGURES = {
    "architecture": (architecture_figure, "图 1 受控混合智能体总体结构"),
    "task_state": (task_state_figure, "图 2 对话状态与预约任务状态分离"),
    "tool_loop": (tool_loop_figure, "图 3 受控工具调用过程"),
}


def make_table(rows: list[list[str]], style_map: dict) -> Table:
    width = A4[0] - 38 * mm
    col_count = len(rows[0])
    if col_count == 2:
        widths = [width * 0.26, width * 0.74]
    elif col_count == 3:
        widths = [width * 0.20, width * 0.40, width * 0.40]
    elif col_count == 4:
        widths = [width * 0.15, width * 0.27, width * 0.36, width * 0.22]
    else:
        widths = [width / col_count] * col_count
    data = []
    for row_index, row in enumerate(rows):
        paragraph_style = style_map["table_head"] if row_index == 0 else style_map["table"]
        data.append([Paragraph(inline(cell.strip()), paragraph_style) for cell in row])
    table = Table(data, colWidths=widths, repeatRows=1, hAlign="LEFT")
    commands = [
        ("BACKGROUND", (0, 0), (-1, 0), DARK_GREEN),
        ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
        ("GRID", (0, 0), (-1, -1), 0.55, BORDER),
        ("VALIGN", (0, 0), (-1, -1), "MIDDLE"),
        ("LEFTPADDING", (0, 0), (-1, -1), 6),
        ("RIGHTPADDING", (0, 0), (-1, -1), 6),
        ("TOPPADDING", (0, 0), (-1, -1), 6),
        ("BOTTOMPADDING", (0, 0), (-1, -1), 6),
    ]
    for row_index in range(1, len(rows)):
        if row_index % 2 == 0:
            commands.append(("BACKGROUND", (0, row_index), (-1, row_index), HexColor("#F5F8F6")))
    table.setStyle(TableStyle(commands))
    return table


def make_image(alt_text: str, target: str, style_map: dict) -> KeepTogether:
    if re.match(r"^[a-zA-Z]+://", target):
        raise ValueError(f"报告图片必须保存到仓库，不能使用网络地址：{target}")

    image_path = (SOURCE.parent / Path(unquote(target))).resolve()
    if not image_path.is_file():
        raise FileNotFoundError(f"报告图片不存在：{image_path}")
    if image_path.suffix.lower() not in {".png", ".jpg", ".jpeg"}:
        raise ValueError(f"报告图片仅支持 PNG/JPG：{image_path}")

    pixel_width, pixel_height = ImageReader(str(image_path)).getSize()
    max_width = A4[0] - 38 * mm
    max_height = 155 * mm
    scale = min(max_width / pixel_width, max_height / pixel_height, 1.0)
    image = ReportImage(
        str(image_path),
        width=pixel_width * scale,
        height=pixel_height * scale,
    )
    image.hAlign = "CENTER"

    elements = [Spacer(1, 2 * mm), image]
    if alt_text.strip():
        elements.append(Paragraph(inline(alt_text.strip()), style_map["caption"]))
    else:
        elements.append(Spacer(1, 3 * mm))
    return KeepTogether(elements)


def cover(lines: list[str], style_map: dict) -> list:
    title = lines[0].removeprefix("# ").strip()
    title = title.replace(
        "面向银发群体的复诊事项协同办理智能体设计思路报告",
        "面向银发群体的<br/>复诊事项协同办理智能体<br/>设计思路报告",
    )
    meta = {}
    for line in lines[1:]:
        match = re.match(r"\*\*(.+?)：\*\*\s*(.+?)\s*$", line.strip())
        if match:
            meta[match.group(1)] = match.group(2).rstrip("  ")
    story = [Spacer(1, 33 * mm)]
    story.append(Paragraph("企业命题组解决方案", ParagraphStyle(
        "Kicker", parent=style_map["cover_meta"], textColor=GREEN, fontSize=12, leading=17,
    )))
    story.append(Spacer(1, 7 * mm))
    story.append(Paragraph(title, style_map["cover_title"]))
    story.append(Spacer(1, 13 * mm))
    story.append(Paragraph(meta.get("项目名称", "银龄复诊事项协同助手"), ParagraphStyle(
        "Project", parent=style_map["cover_meta"], fontSize=16, leading=23, textColor=TEXT,
    )))
    story.append(Spacer(1, 17 * mm))
    for key in ["参赛组别", "文档版本", "更新日期"]:
        if key in meta:
            story.append(Paragraph(f"{key}　{meta[key]}", style_map["cover_meta"]))
    story.append(Spacer(1, 28 * mm))
    story.append(Paragraph(
        "以自然语言串联预约 材料 日程 出行与家属协同",
        ParagraphStyle("CoverSub", parent=style_map["cover_meta"], fontSize=10.5, textColor=MUTED),
    ))
    story.append(PageBreak())
    return story


def parse_markdown(lines: list[str], style_map: dict) -> list:
    start = next(i for i, line in enumerate(lines) if line.startswith("## 设计摘要"))
    story = cover(lines[:start], style_map)
    i = start
    in_code = False
    paragraph_lines: list[str] = []

    def flush_paragraph() -> None:
        if paragraph_lines:
            text = " ".join(part.strip() for part in paragraph_lines).strip()
            if text:
                story.append(Paragraph(inline(text), style_map["body"]))
            paragraph_lines.clear()

    while i < len(lines):
        raw = lines[i].rstrip()
        stripped = raw.strip()
        if stripped.startswith("```"):
            flush_paragraph()
            in_code = not in_code
            i += 1
            continue
        if in_code:
            i += 1
            continue
        if stripped == "<!-- PAGE_BREAK -->":
            flush_paragraph()
            story.append(PageBreak())
            i += 1
            continue
        figure_match = re.fullmatch(r"<!-- PDF_FIGURE:(.+?) -->", stripped)
        if figure_match:
            flush_paragraph()
            factory, caption = FIGURES[figure_match.group(1)]
            story.append(KeepTogether([factory(), Paragraph(caption, style_map["caption"])]))
            i += 1
            continue
        image_match = re.fullmatch(r"!\[([^\]]*)\]\(([^)]+)\)", stripped)
        if image_match:
            flush_paragraph()
            story.append(make_image(image_match.group(1), image_match.group(2).strip(), style_map))
            i += 1
            continue
        if stripped.startswith("|"):
            flush_paragraph()
            table_lines = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                table_lines.append(lines[i].strip())
                i += 1
            rows = [[cell.strip() for cell in line.strip("|").split("|")] for line in table_lines]
            rows = [row for row in rows if not all(re.fullmatch(r":?-{3,}:?", cell) for cell in row)]
            if rows:
                story.append(make_table(rows, style_map))
                story.append(Spacer(1, 3 * mm))
            continue
        if stripped.startswith("## "):
            flush_paragraph()
            story.append(Paragraph(inline(stripped[3:]), style_map["h2"]))
            i += 1
            continue
        if stripped.startswith("### "):
            flush_paragraph()
            story.append(Paragraph(inline(stripped[4:]), style_map["h3"]))
            i += 1
            continue
        if re.match(r"^[-*]\s+", stripped):
            flush_paragraph()
            content = re.sub(r"^[-*]\s+", "", stripped)
            story.append(Paragraph("•　" + inline(content), style_map["bullet"]))
            i += 1
            continue
        if re.match(r"^\d+\.\s+", stripped):
            flush_paragraph()
            story.append(Paragraph(inline(stripped), style_map["bullet"]))
            i += 1
            continue
        if stripped.startswith(">"):
            flush_paragraph()
            story.append(Paragraph(inline(stripped.lstrip("> ")), style_map["note"]))
            i += 1
            continue
        if not stripped or stripped == "---":
            flush_paragraph()
            i += 1
            continue
        paragraph_lines.append(stripped)
        i += 1
    flush_paragraph()
    return story


def decorate_page(canvas, doc) -> None:
    canvas.saveState()
    page_width, page_height = A4
    if doc.page == 1:
        canvas.setFillColor(IVORY)
        canvas.rect(0, 0, page_width, page_height, fill=1, stroke=0)
        canvas.setFillColor(GREEN)
        canvas.rect(0, page_height - 8 * mm, page_width, 8 * mm, fill=1, stroke=0)
        canvas.setStrokeColor(GREEN)
        canvas.setLineWidth(1.4)
        canvas.line(72 * mm, page_height - 51 * mm, 138 * mm, page_height - 51 * mm)
    else:
        canvas.setStrokeColor(BORDER)
        canvas.setLineWidth(0.5)
        canvas.line(19 * mm, page_height - 15 * mm, page_width - 19 * mm, page_height - 15 * mm)
        canvas.setFont("Deng", 8)
        canvas.setFillColor(MUTED)
        canvas.drawString(19 * mm, page_height - 11.5 * mm, "银龄复诊事项协同助手")
        canvas.drawRightString(page_width - 19 * mm, page_height - 11.5 * mm, "解决方案 v0.1")
    canvas.setFont("Deng", 8)
    canvas.setFillColor(MUTED)
    canvas.drawString(19 * mm, 10 * mm, "企业命题组 产教协同创新组")
    canvas.drawRightString(page_width - 19 * mm, 10 * mm, str(doc.page))
    canvas.restoreState()


def build() -> None:
    setup_fonts()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    source_lines = SOURCE.read_text(encoding="utf-8").splitlines()
    style_map = styles()
    story = parse_markdown(source_lines, style_map)
    document = SimpleDocTemplate(
        str(OUTPUT), pagesize=A4,
        leftMargin=19 * mm, rightMargin=19 * mm,
        topMargin=19 * mm, bottomMargin=17 * mm,
        title="面向银发群体的复诊事项协同办理智能体设计思路报告",
        author="银龄复诊事项协同助手项目组",
        subject="智能体设计思路报告 v0.1",
    )
    document.build(story, onFirstPage=decorate_page, onLaterPages=decorate_page)
    print(OUTPUT)


if __name__ == "__main__":
    try:
        build()
    except Exception as exc:
        print(f"build failed: {exc}", file=sys.stderr)
        raise
