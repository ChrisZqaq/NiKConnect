from pathlib import Path
import re
from docx import Document
from docx.shared import Cm, Pt
from docx.oxml.ns import qn
base=Path('docs/NiKConnect_全项目评审与重构决策_2026-11-13')
source=base.with_suffix('.md').read_text()
doc=Document()
sec=doc.sections[0]
sec.page_width=Cm(21)
sec.page_height=Cm(29.7)
sec.top_margin=sec.bottom_margin=Cm(2)
sec.left_margin=sec.right_margin=Cm(2)
for name in ['Normal','Title','Heading 1','Heading 2','Heading 3']:
    style=doc.styles[name]
    style.font.name='Arial'
    style.element.get_or_add_rPr().rFonts.set(qn('w:eastAsia'),'PingFang SC')
    if name=='Normal':
        style.font.size=Pt(10.5)
        style.paragraph_format.line_spacing=1.25
    style.paragraph_format.space_after=Pt(7)
refs=[]
def clean(text):
    def link(m):
        label,target=m.group(1),m.group(2)
        if target not in refs: refs.append(target)
        return label+' ['+str(refs.index(target)+1)+']'
    text=re.sub(r'\[([^\]]+)\]\(<?([^)>]+)>?\)',link,text)
    return text.replace('**','').replace(chr(96),'')
lines=source.splitlines()
i=0
code=False
while i<len(lines):
    line=lines[i]
    if line.startswith(chr(96)*3):
        code=not code
        i+=1
        continue
    if not line.strip():
        i+=1
        continue
    if code:
        p=doc.add_paragraph(line)
        p.paragraph_format.space_after=Pt(0)
        for r in p.runs: r.font.size=Pt(8)
    elif line.startswith('|'):
        rows=[]
        while i<len(lines) and lines[i].startswith('|'):
            cells=[c.strip() for c in lines[i].strip('|').split('|')]
            if not all(re.fullmatch(':?-+:?',c) for c in cells): rows.append(cells)
            i+=1
        table=doc.add_table(rows=0,cols=len(rows[0]))
        table.style='Light Shading Accent 1'
        for n,row in enumerate(rows):
            cells=table.add_row().cells
            for cell,text in zip(cells,row):
                cell.text=clean(text)
                for p in cell.paragraphs:
                    for r in p.runs:
                        r.font.size=Pt(9)
                        r.bold=n==0
        continue
    elif line.startswith('#'):
        level=len(line)-len(line.lstrip('#'))
        doc.add_heading(clean(line[level:].strip()),level=0 if i==0 else min(level,3))
    elif line.startswith('- '):
        doc.add_paragraph(clean(line[2:]),style='List Bullet')
    else:
        doc.add_paragraph(clean(line))
    i+=1
doc.add_heading('附录：证据与参考链接',level=1)
for n,target in enumerate(refs,1):
    doc.add_paragraph(str(n+0)+'. '+target.removeprefix('../'))
doc.core_properties.title='NiKConnect 全项目评审与重构决策报告'
output=base.with_suffix('.docx')
assert not output.exists()
doc.save(output)
check=Document(output)
text=chr(10).join(p.text for p in check.paragraphs)+chr(10).join(c.text for t in check.tables for row in t.rows for c in row.cells)
for keyword in ['云创频控','唤醒锁','未验证','验收门','Unable to locate a Java Runtime.','导入回执']:
    assert keyword in text,keyword
assert len(check.tables)==3
assert len(refs)>20
print({'paragraphs':len(check.paragraphs),'tables':len(check.tables),'references':len(refs),'bytes':output.stat().st_size,'validation':'passed'})
