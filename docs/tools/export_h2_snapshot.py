#!/usr/bin/env python3
"""Export the running development H2 database to a Markdown snapshot.

Run this script inside the VS Code development container while the backend is
running. It uses the already enabled H2 web console so the embedded database
does not need to be stopped or copied.
"""

from __future__ import annotations

import argparse
import html
import http.cookiejar
import re
import sys
import urllib.parse
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime
from html.parser import HTMLParser
from pathlib import Path
from zoneinfo import ZoneInfo


TABLE_PURPOSES = {
    "USERS": "用户基础资料",
    "HOSPITALS": "医院目录",
    "DEPARTMENTS": "医院科室目录",
    "CLINICS": "诊室与无障碍路线位置",
    "APPOINTMENT_SLOTS": "滚动生成的可预约号源",
    "USER_SCHEDULES": "用户已有日程",
    "FAMILY_CONTACTS": "家属联系人",
    "APPOINTMENTS": "已确认预约",
    "MATERIAL_TEMPLATES": "复诊材料模板",
    "CARE_GUIDE_ARTICLES": "照护指南文章",
    "APPOINTMENT_MATERIALS": "预约材料准备状态",
    "USER_PREFERENCES": "语音交互偏好",
    "TRAVEL_ROUTES": "模拟出行路线",
    "REMINDERS": "预约相关提醒",
    "FAMILY_NOTIFICATIONS": "发给家属联系人的通知",
    "CONVERSATION_SESSIONS": "智能体会话状态",
    "CONVERSATION_MESSAGES": "智能体会话消息",
    "CONVERSATION_ATTACHMENTS": "会话图片等附件",
    "VISION_RESULTS": "识图与 OCR 结果",
    "TOOL_CALL_LOGS": "工具调用审计日志",
    "CARE_RELATIONS": "照护者与老人绑定关系",
    "CARE_NOTIFICATIONS": "照护协同通知",
    "MEMOS": "健康备忘",
    "HEALTH_RECORDS": "健康实测记录",
    "USER_MEMORIES": "跨会话长期记忆",
}

PREFERRED_TABLE_ORDER = list(TABLE_PURPOSES)


@dataclass
class HtmlTable:
    rows: list[list[str]] = field(default_factory=list)


class ResultTableParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.tables: list[HtmlTable] = []
        self._table: HtmlTable | None = None
        self._row: list[str] | None = None
        self._cell: list[str] | None = None
        self._cell_is_null = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        attrs_dict = dict(attrs)
        if tag == "table" and "resultSet" in (attrs_dict.get("class") or "").split():
            self._table = HtmlTable()
        elif self._table is not None and tag == "tr":
            self._row = []
        elif self._row is not None and tag in {"th", "td"}:
            self._cell = []
            self._cell_is_null = False
        elif self._cell is not None and tag == "i":
            self._cell_is_null = True
        elif self._cell is not None and tag == "br":
            self._cell.append("\n")

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag in {"th", "td"} and self._cell is not None and self._row is not None:
            value = "".join(self._cell)
            self._row.append("NULL" if self._cell_is_null and value == "null" else value)
            self._cell = None
        elif tag == "tr" and self._row is not None and self._table is not None:
            self._table.rows.append(self._row)
            self._row = None
        elif tag == "table" and self._table is not None:
            self.tables.append(self._table)
            self._table = None


class H2Console:
    def __init__(self, base_url: str, jdbc_url: str, user: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        self.jdbc_url = jdbc_url
        self.user = user
        self.password = password
        cookie_jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))
        self.jsessionid = ""

    def _read(self, request: urllib.request.Request | str) -> str:
        with self.opener.open(request, timeout=30) as response:
            return response.read().decode("utf-8")

    def connect(self) -> None:
        welcome = self._read(f"{self.base_url}/")
        match = re.search(r"login\.jsp\?jsessionid=([a-f0-9]+)", welcome)
        if not match:
            raise RuntimeError("无法从 H2 控制台首页取得会话标识")
        self.jsessionid = match.group(1)

        form = urllib.parse.urlencode(
            {
                "language": "zh_CN",
                "setting": "Generic H2 (Embedded)",
                "name": "Generic H2 (Embedded)",
                "driver": "org.h2.Driver",
                "url": self.jdbc_url,
                "user": self.user,
                "password": self.password,
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}/login.do?jsessionid={self.jsessionid}", data=form
        )
        result = self._read(request)
        if "frameset" not in result.lower():
            raise RuntimeError("连接 H2 数据库失败，请确认后端正在运行且连接参数正确")

    def query(self, sql: str) -> list[list[str]]:
        form = urllib.parse.urlencode({"sql": sql}).encode("utf-8")
        request = urllib.request.Request(
            f"{self.base_url}/query.do?jsessionid={self.jsessionid}", data=form
        )
        result = self._read(request)
        parser = ResultTableParser()
        parser.feed(result)
        if not parser.tables:
            plain = re.sub(r"<[^>]+>", " ", result)
            raise RuntimeError(f"查询没有返回结果集：{sql}\n{html.unescape(plain).strip()}")
        return parser.tables[0].rows


def quote_identifier(identifier: str) -> str:
    return '"' + identifier.replace('"', '""') + '"'


def sql_literal(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def markdown_cell(value: str) -> str:
    normalized = value.replace("\r\n", "\n").replace("\r", "\n")
    normalized = normalized.replace("\\", "\\\\").replace("|", "\\|")
    normalized = normalized.replace("\n", "<br>")
    return normalized if normalized else "（空字符串）"


def markdown_table(rows: list[list[str]]) -> list[str]:
    if not rows:
        return ["（查询未返回字段定义。）"]
    header = rows[0]
    lines = [
        "| " + " | ".join(markdown_cell(cell) for cell in header) + " |",
        "| " + " | ".join("---" for _ in header) + " |",
    ]
    lines.extend(
        "| " + " | ".join(markdown_cell(cell) for cell in row) + " |"
        for row in rows[1:]
    )
    return lines


def get_primary_key_columns(console: H2Console, table_name: str) -> list[str]:
    rows = console.query(
        "SELECT KCU.COLUMN_NAME "
        "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS TC "
        "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE KCU "
        "ON TC.CONSTRAINT_CATALOG = KCU.CONSTRAINT_CATALOG "
        "AND TC.CONSTRAINT_SCHEMA = KCU.CONSTRAINT_SCHEMA "
        "AND TC.CONSTRAINT_NAME = KCU.CONSTRAINT_NAME "
        "WHERE TC.TABLE_SCHEMA = 'PUBLIC' "
        f"AND TC.TABLE_NAME = {sql_literal(table_name)} "
        "AND TC.CONSTRAINT_TYPE = 'PRIMARY KEY' "
        "ORDER BY KCU.ORDINAL_POSITION"
    )
    return [row[0] for row in rows[1:]]


def build_document(console: H2Console, jdbc_url: str) -> str:
    table_rows = console.query(
        "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES "
        "WHERE TABLE_SCHEMA = 'PUBLIC' ORDER BY TABLE_NAME"
    )
    discovered = [row[0] for row in table_rows[1:]]
    ordered = [name for name in PREFERRED_TABLE_ORDER if name in discovered]
    ordered.extend(name for name in discovered if name not in ordered)

    exported_at = datetime.now(ZoneInfo("Asia/Shanghai"))
    counts: dict[str, int] = {}
    schemas: dict[str, list[list[str]]] = {}
    contents: dict[str, list[list[str]]] = {}

    for table_name in ordered:
        quoted_table = quote_identifier(table_name)
        count_rows = console.query(f"SELECT COUNT(*) AS ROW_COUNT FROM {quoted_table}")
        counts[table_name] = int(count_rows[1][0])
        schemas[table_name] = console.query(
            "SELECT COLUMN_NAME AS 字段, DATA_TYPE AS 类型, IS_NULLABLE AS 允许空值, "
            "COALESCE(CAST(CHARACTER_MAXIMUM_LENGTH AS VARCHAR), "
            "CAST(NUMERIC_PRECISION AS VARCHAR), '—') AS 长度或精度 "
            "FROM INFORMATION_SCHEMA.COLUMNS "
            "WHERE TABLE_SCHEMA = 'PUBLIC' "
            f"AND TABLE_NAME = {sql_literal(table_name)} "
            "ORDER BY ORDINAL_POSITION"
        )
        primary_keys = get_primary_key_columns(console, table_name)
        order_clause = ""
        if primary_keys:
            order_clause = " ORDER BY " + ", ".join(quote_identifier(col) for col in primary_keys)
        contents[table_name] = console.query(f"SELECT * FROM {quoted_table}{order_clause}")

    total_rows = sum(counts.values())
    lines = [
        "# H2 数据库全表与数据快照",
        "",
        f"> 快照时间：{exported_at:%Y年%m月%d日 %H:%M:%S}（Asia/Shanghai）  ",
        f"> 数据源：开发容器中的 `{jdbc_url}`  ",
        f"> 范围：`PUBLIC` 模式下全部 {len(ordered)} 张表，共 {total_rows} 行记录。",
        "",
        "本文档是运行中开发数据库的只读快照，包含静态种子数据、滚动生成数据和当时已产生的业务/会话数据。",
        "`NULL` 表示数据库空值；`（空字符串）` 表示长度为 0 的字符串。模拟数据仅供比赛演示，不代表真实医院、患者或医疗信息。",
        "",
        "## 一、表与行数总览",
        "",
        "| 序号 | 表名 | 用途 | 行数 |",
        "| ---: | --- | --- | ---: |",
    ]
    for index, table_name in enumerate(ordered, 1):
        purpose = TABLE_PURPOSES.get(table_name, "—")
        anchor = table_name.lower().replace("_", "-")
        lines.append(f"| {index} | [`{table_name}`](#{anchor}) | {purpose} | {counts[table_name]} |")

    lines.extend(["", "## 二、逐表字段与全部数据", ""])
    for index, table_name in enumerate(ordered, 1):
        purpose = TABLE_PURPOSES.get(table_name, "未分类")
        lines.extend(
            [
                f"### `{table_name}`",
                "",
                f"用途：{purpose}。当前共 **{counts[table_name]}** 行。",
                "",
                "字段定义：",
                "",
                *markdown_table(schemas[table_name]),
                "",
                "全部数据：",
                "",
            ]
        )
        data_rows = contents[table_name]
        if len(data_rows) == 1:
            lines.append("（空表，无数据。）")
        else:
            lines.extend(markdown_table(data_rows))
        lines.append("")

    lines.extend(
        [
            "## 三、重新生成",
            "",
            "在 VS Code 开发容器中启动后端后，于 `/workspace` 执行：",
            "",
            "```bash",
            "python3 docs/tools/export_h2_snapshot.py",
            "```",
            "",
            "该命令会以只读查询覆盖本文件，使表结构、行数和记录内容与当前开发数据库保持一致。",
            "",
        ]
    )
    return "\n".join(lines)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="导出运行中 H2 数据库的 Markdown 快照")
    parser.add_argument("--console-url", default="http://127.0.0.1:8080/h2-console")
    parser.add_argument("--jdbc-url", default="jdbc:h2:file:/workspace/backend/data/silver-agent")
    parser.add_argument("--user", default="sa")
    parser.add_argument("--password", default="")
    parser.add_argument("--output", default="docs/14-database-table-data.md")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    console = H2Console(args.console_url, args.jdbc_url, args.user, args.password)
    try:
        console.connect()
        document = build_document(console, args.jdbc_url)
        output_path = Path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_text(document, encoding="utf-8", newline="\n")
        print(f"已导出：{output_path}（{len(document.encode('utf-8'))} 字节）")
        return 0
    except Exception as exc:  # noqa: BLE001 - CLI should print a concise actionable error.
        print(f"导出失败：{exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
