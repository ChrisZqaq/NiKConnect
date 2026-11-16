from pathlib import Path
import re
from docx import Document
from docx.shared import Cm, Pt
from docx.oxml.ns import qn
root = Path(__file__).resolve().parent
source = root / 'NiKConnect_代码复审_2026-11-16.md'
target = source.with_suffix('.docx')
text = source.read_text(encoding='utf-8')
doc = Document()
sec = doc.sections[0]
sec.page_width, sec.page_height = Cm(21), Cm(29.7)
sec.top_margin = sec.bottom_margin = Cm(2)
sec.left_margin = sec.right_margin = Cm(2.2)
for name in ['Normal', 'Title', 'Heading 1', 'Heading 2']:
    style = doc.styles[name]
    style.font.name = 'Calibri'
    style.element.get_or_add_rPr().set(qn('w:eastAsia'), 'Microsoft YaHei')
doc.styles['Normal'].font.size = Pt(10.5)
doc.styles['Normal'].paragraph_format.space_after = Pt(7)
doc.styles['Normal'].paragraph_format.line_spacing = 1.2
refs = []
def plain(s):
    def link(m):
        path = m.group(2)
        if path not in refs:
            refs.append(path)
        return m.group(1) + ' [' + str(refs.index(path)+1) + ']'
    s = re.sub(r'\[([^\]]+)\]\(<([^>]+)>\)', link, s)
    return s.replace('**', '').replace(chr(96), '')
in_code = False
for line in text.splitlines():
    if line.startswith(chr(96)*3):
        in_code = not in_code
        continue
    if not line.strip():
        continue
    if line.startswith('# '):
        doc.add_heading(plain(line[2:]), 0)
    elif line.startswith('## '):
        doc.add_heading(plain(line[3:]), 1)
    elif re.match(r'^\*\*\d+\. \[P[12]\]', line):
        doc.add_heading(plain(line), 1)
    elif line.startswith('- '):
        doc.add_paragraph(plain(line[2:]), style='List Bullet')
    else:
        p = doc.add_paragraph(plain(line.removeprefix('> ')))
        if in_code:
            for run in p.runs:
                run.font.name = 'Consolas'
                run.font.size = Pt(9)
doc.add_heading('附录：源码证据索引', 1)
for i, ref in enumerate(refs, 1):
    doc.add_paragraph(f'[{i}] {ref}')
doc.add_heading('文档归档说明', 1)
doc.add_paragraph('正文中的“未新增或修改文件”“工作区保持干净”描述代码审查完成时的状态。本次归档仅新增 Markdown、Word 与生成脚本，未修改应用代码。')
doc.add_paragraph('内容与结构检查通过；视觉检查未完成。')
doc.save(target)
check = Document(target)
full = '\n'.join(p.text for p in check.paragraphs)
assert sum(1 for p in check.paragraphs if re.match(r'^\d+\. \[P[12]\]', p.text)) == 7
assert '19315df' in full and 'versionCode 65' in full
assert 'Unable to locate a Java Runtime.' in full
assert len(refs) == 15, len(refs)
print(f'PASS: {target.name}; paragraphs={len(check.paragraphs)}; references={len(refs)}; bytes={target.stat().st_size}')
