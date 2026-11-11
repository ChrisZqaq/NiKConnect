from pathlib import Path
from collections import Counter
import re
from bs4 import BeautifulSoup

root = Path(__file__).resolve().parent.parent
html_path = root / "nikon-companion-prototype.html"
html = html_path.read_text(encoding="utf-8")
soup = BeautifulSoup(html, "html.parser")

checks = []
def check(condition, label):
    if not condition:
        raise AssertionError(label)
    checks.append(label)

screens = soup.select(".screen")
check(len(screens) == 6, "六个完整界面")
ids = [tag["id"] for tag in soup.select("[id]")]
check(all(count == 1 for count in Counter(ids).values()), "页面 ID 无重复")
id_set = set(ids)
check(all(f'screen-{tag["data-go"]}' in id_set for tag in soup.select("[data-go]")), "导航目标均存在")
check(all(tag.get("src", "").startswith("data:") for tag in soup.select("[src]")), "所有资源均内嵌")
check(not soup.select("script[src], link[href]"), "无外部脚本或样式资源")

scripts = soup.find_all("script")
check(bool(scripts), "存在交互脚本")
js = scripts[-1].string or scripts[-1].get_text()
(root / "prototype-work" / "prototype-check.js").write_text(js, encoding="utf-8")
for target in re.findall(r'\$\("#([A-Za-z0-9_-]+)"\)', js):
    check(target in id_set, f"脚本目标 #{target} 存在")

check(re.search(r'function finishWrite\(\)[\s\S]*?renderSlots\(\);\s*renderRecipe\(\);', js) is not None, "写入完成后同步槽位号")
check("setTimeout(finishWrite, 600)" in js, "目标槽位原地填充 600ms")
check("正在写入 ${selectedSlot}…" in js, "动态写入槽位文案")
check(".slot-panel.writing .slot-cell:not(.selected)" in html, "写入时其余八格变暗")
check(".group-duration { display: block;" in html or ".group-copy p, .group-duration { display: block;" in html, "分组时长块级显示")
check("data.bars[index] / Math.max(...data.bars) * 100" in js, "条形图按当前最大值归一化")
check(js.count("Array.from({ length: 15 }") == 2, "相机和手机图库均为 15 格")
check("28 + (index * 17) % 55" in js and "31 + (index * 19) % 48" in js, "图库使用差异化二维裁切")
check(js.count("brightness(${0.8") == 2, "图库使用确定性轻微亮度差")
check(soup.select_one("#download-this") is not None and '$("#download-this").addEventListener' in js, "照片查看器可下载当前照片")
check(len(soup.select("#recipe-summary .summary-row")) == 6, "滤镜参数摘要共六项")
check([row.select_one("span").get_text(strip=True) for row in soup.select("#recipe-summary .summary-row")] == ["对比度", "清晰度", "饱和度", "高光", "阴影", "色相偏移"], "滤镜摘要名称完整")
check(len(soup.select(".recent-photos img")) == 3, "连接页包含三张最近传输照片")
check("512 GB" in html and "剩余 318 GB" in html and "固件版本" in html and "1.10" in html, "连接页包含容量和固件状态")
check(".primary-button { border: 1px solid var(--accent); background: transparent; color: var(--accent);" in html, "主按钮默认描边透明底")
check(".primary-button.writing" in html, "仅写入状态使用填充按钮")
check("#12100e" in html and "#c3a36e" in html, "暖黑背景与降饱和强调色")
check("Math.random() * 2 - 1) * .08" in js, "传输速度保持 ±8% 小幅波动")
check("已校验" not in html and "读回校验" not in html and "校验" not in html, "无不恰当校验承诺")

print(f"PASS: {len(checks)} 项静态检查通过；最终文件 {html_path.stat().st_size:,} bytes")
