from pathlib import Path
import re
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

ROOT = Path(__file__).resolve().parent
SRC = ROOT / "NiKConnect_终局评审与重构路线_2026-11-14.md"
DST = SRC.with_suffix(".docx")
if DST.exists():
    raise SystemExit("Destination already exists; review before replacement")
text = SRC.read_text()
doc = Document()
sec = doc.sections[0]
sec.top_margin = sec.bottom_margin = Cm(1.8)
sec.left_margin = sec.right_margin = Cm(1.8)
normal = doc.styles["Normal"]
normal.font.name = "Arial"
normal.font.size = Pt(10)
normal._element.rPr.rFonts.set(qn("w:eastAsia"), "PingFang SC")
normal.paragraph_format.space_after = Pt(6)
normal.paragraph_format.line_spacing = 1.15
for name in ["Title", "Heading 1", "Heading 2", "Heading 3"]:
    style = doc.styles[name]
    style.font.name = "Arial"
    style._element.rPr.rFonts.set(qn("w:eastAsia"), "PingFang SC")
    style.font.color.rgb = RGBColor.from_string("17365D")
references = []
def clean(s):
    def link(m):
        label, target = m.group(1), m.group(2)
        if target not in [t for _, t in references]:
            references.append((label, target))
        return label + " [" + str([t for _, t in references].index(target) + 1) + "]"
    return re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, s).replace("**", "").replace("`", "")

lines = text.splitlines()
i = 0
while i < len(lines):
    line = lines[i]
    if not line.strip():
        i += 1
        continue
    if line.startswith("|"):
        rows = []
        while i < len(lines) and lines[i].startswith("|"):
            cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
            if not all(re.fullmatch(r"[: -]+", c) for c in cells):
                rows.append(cells)
            i += 1
        table = doc.add_table(rows=1, cols=len(rows[0]))
        table.style = "Light Shading Accent 1"
        for j, value in enumerate(rows[0]):
            table.rows[0].cells[j].text = clean(value)
        header = OxmlElement("w:tblHeader")
        table.rows[0]._tr.get_or_add_trPr().append(header)
        for row in rows[1:]:
            for cell, value in zip(table.add_row().cells, row):
                cell.text = clean(value)
        continue
    m = re.match(r"^(#{1,4}) (.*)$", line)
    if m:
        level = len(m.group(1))
        doc.add_heading(clean(m.group(2)), level=0 if level == 1 else level - 1)
    elif line.startswith("- "):
        doc.add_paragraph(clean(line[2:]), style="List Bullet")
    elif re.match(r"^\d+\. ", line):
        doc.add_paragraph(clean(re.sub(r"^\d+\. ", "", line)), style="List Number")
    else:
        doc.add_paragraph(clean(line))
    i += 1

doc.add_heading("来源与源码定位", level=1)
for index, (label, target) in enumerate(references, 1):
    if target.startswith("../"):
        path, _, fragment = target.partition("#L")
        target = str((ROOT / path).resolve()) + (":" + fragment if fragment else "")
    doc.add_paragraph(f"[{index}] {label} — {target}")
footer = sec.footer.paragraphs[0]
footer.add_run("NiKConnect · 0.9.3-alpha · 2026-11-14 · ")
fld = OxmlElement("w:fldSimple")
fld.set(qn("w:instr"), "PAGE")
footer._p.append(fld)
doc.save(DST)
check = Document(DST)
all_text = "\n".join(p.text for p in check.paragraphs)
assert all(f"F{x:02}" in all_text for x in range(1,16))
assert len(check.tables) == 5, len(check.tables)
for _, target in references:
    if target.startswith("../"):
        assert (ROOT / target.partition("#")[0]).is_file(), target
assert "Unable to locate a Java Runtime." in all_text
assert "五项简报" in all_text
print({"document": str(DST), "paragraphs": len(check.paragraphs), "tables": len(check.tables), "references": len(references), "bytes": DST.stat().st_size, "validation": "content and structure passed; visual rendering not performed"})
