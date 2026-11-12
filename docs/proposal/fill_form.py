"""기획서 초안(proposal.md) 1~8절을 공식 접수양식 DOCX 02 칸에 옮긴다.

python docs/proposal/fill_form.py  →  docs/proposal/drive-song-application.docx
- 칸마다 들어 있는 태그 달린 텍스트 콘텐츠 컨트롤(app_summary 등)은 그대로 두고 내용만 채운다 (운영사 추출용일 수 있음).
  일반 텍스트 컨트롤이라 굵게 등 서식 없이 줄바꿈으로 구조를 잡는다. 6·7번 칸은 한 줄 컨트롤이라 여러 줄로 바꾼다.
- 01·02·04의 '앱명 / 프로젝트명' 밑줄만 채운다. 성명·연락처·소속·서명·작성일은 본인이 적는다.
"""

import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from xml.sax.saxutils import escape

HERE = Path(__file__).parent
FORM = HERE / "official-form" / "contest-application-consent.docx"
OUT = HERE / "drive-song-application.docx"
APP_NAME = "오늘의 드라이브 송 (Drive Song)"


def sections(md: str) -> dict[int, str]:
    parts = re.split(r"^## (\d)\. .*$", md, flags=re.M)
    out = {}
    for i in range(1, len(parts) - 1, 2):
        body = parts[i + 1].split("\n---")[0]
        body = body.split("\n## 제출 전 확인 목록")[0]
        out[int(parts[i])] = body.strip()
    return out


def cells(row: str) -> list[str]:
    return [c.strip().replace("**", "").replace("`", "") for c in row.strip().strip("|").split("|")]


def table_lines(rows: list[str]) -> list[str]:
    head = cells(rows[0])
    body = [cells(r) for r in rows[2:]]
    lines = []
    for r in body:
        if head[:2] == ["기능", "내용"]:
            lines.append(f"{r[0]} — {r[1]}")
        elif head == ["단계", "모델", "라이선스"]:
            lines.append(f"· {r[0]}: {r[1]} ({r[2]})")
        elif head[0] == "단계" and len(head) == 3:
            lines.append(f"[{r[0]} · {r[1]}] {r[2]}")
        elif head[0] == "SDK / API":
            lines.append(f"· {r[0]}: {r[1]}")
            lines.append(f"   현재: {r[2]} / 계획: {r[3]}")
        else:
            lines.append(f"· {r[0]}: {' / '.join(r[1:])}")
    return lines


def plain(text: str) -> list[str]:
    """마크다운 한 절 → 양식에 넣을 줄 목록."""
    out, table = [], []
    for raw in text.splitlines() + [""]:
        line = raw.rstrip()
        if line.startswith("|"):
            table.append(line)
            continue
        if table:
            out += table_lines(table)
            table = []
        if line.startswith("`심사 포인트"):
            continue
        line = line.replace("`", "")
        lead = re.fullmatch(r"\*\*(.+?)\*\*(.*)", line)  # 굵은 글씨로 시작하는 줄은 소제목
        if lead:
            line = "■ " + lead.group(1) + lead.group(2)
        line = line.replace("**", "")
        if line.startswith("- "):
            line = "· " + line[2:]
        out.append(line)
    # 빈 줄 여러 개는 하나로, 앞뒤 빈 줄 제거
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip()
    return text.splitlines()


def runs(lines: list[str]) -> str:
    parts = []
    for i, line in enumerate(lines):
        if i:
            parts.append("<w:br/>")
        if line:
            parts.append(f'<w:t xml:space="preserve">{escape(line)}</w:t>')
    return "<w:r>" + "".join(parts) + "</w:r>"


def fill(xml: str, contents: list[list[str]]) -> str:
    sdts = list(re.finditer(r"<w:sdt>.*?</w:sdt>", xml, re.S))
    assert len(sdts) == 8, len(sdts)
    pieces, last = [], 0
    for i, (m, lines) in enumerate(zip(sdts, contents), start=1):
        sdt = m.group(0)
        if i in (6, 7):  # 6 기대효과·7 참고자료 칸은 여러 줄이 필요하다
            sdt = sdt.replace("<w:text/>", '<w:text w:multiLine="1"/>')
        sdt = re.sub(r"<w:sdtContent>.*</w:sdtContent>", lambda _: f"<w:sdtContent>{runs(lines)}</w:sdtContent>", sdt, flags=re.S)
        before = xml[last:m.start()]
        # 칸(한 칸짜리 표의 행)이 페이지를 넘어가도 되게: 긴 칸이 통째로 다음 쪽으로 밀려 빈 쪽이 생기지 않게
        row = before.rfind("<w:tr ")
        before = before[:row] + before[row:].replace("<w:cantSplit/>", "", 1)
        pieces += [before, sdt]
        last = m.end()
    xml = "".join(pieces) + xml[last:]

    # '앱명 / 프로젝트명: ____' 밑줄 (01·02·04)
    def name_line(p: re.Match) -> str:
        para = re.sub(r"(프로젝트명: )_+", lambda m: m.group(1) + escape(APP_NAME), p.group(0), count=1)
        return re.sub(r"<w:r(?: [^>]*)?>(?:<w:rPr>(?:(?!</w:rPr>).)*</w:rPr>)?<w:t(?: [^>]*)?>_+</w:t></w:r>", "", para, flags=re.S)

    xml, n = re.subn(r"<w:p [^>]*>(?:(?!</w:p>).)*?프로젝트명: _(?:(?!</w:p>).)*?</w:p>", name_line, xml, flags=re.S)
    assert n == 3, n
    return xml


def main():
    sec = sections((HERE / "proposal.md").read_text(encoding="utf-8"))
    contents = [plain(sec[i]) for i in range(1, 9)]
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "src"
        with zipfile.ZipFile(FORM) as z:
            z.extractall(src)
        doc = src / "word" / "document.xml"
        doc.write_text(fill(doc.read_text(encoding="utf-8"), contents), encoding="utf-8")
        with zipfile.ZipFile(FORM) as zin, zipfile.ZipFile(OUT, "w", zipfile.ZIP_DEFLATED) as zout:
            for item in zin.infolist():  # 원본 순서·이름 그대로 ([Content_Types].xml 먼저)
                zout.write(src / item.filename, item.filename)
    for i, c in enumerate(contents, 1):
        print(f"{i}: {len(c)}줄, {sum(len(x) for x in c)}자")
    print("->", OUT)


if __name__ == "__main__":
    main()
