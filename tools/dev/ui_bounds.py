#!/usr/bin/env python3
"""从 uiautomator dump 里取文本与 bounds，用于「不靠肉眼」核对布局。

用法：
    adb -s $D shell uiautomator dump /sdcard/u.xml
    adb -s $D shell cat /sdcard/u.xml | python3 tools/dev/ui_bounds.py [--screen 1080x2376]

能回答三类问题（比截图更能证伪）：
  1. 某段文本是否被屏幕底边/侧边裁掉  —— --clip
  2. 两块文本的纵向区间是否重叠        —— --overlap
  3. 网格实际列数                      —— --columns
"""
import argparse
import re
import sys

NODE = re.compile(r'<node[^>]*>')
TEXT = re.compile(r'text="([^"]*)"')
DESC = re.compile(r'content-desc="([^"]*)"')
BOUNDS = re.compile(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')


def parse(xml):
    out = []
    for raw in NODE.findall(xml):
        b = BOUNDS.search(raw)
        if not b:
            continue
        t = TEXT.search(raw)
        d = DESC.search(raw)
        label = (t.group(1) if t else "").strip()
        desc = (d.group(1) if d else "").strip()
        if not label and not desc:
            continue
        x1, y1, x2, y2 = (int(v) for v in b.groups())
        out.append({"label": label or desc, "x1": x1, "y1": y1, "x2": x2, "y2": y2})
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--screen", default="1080x2376", help="WxH，取自 adb shell wm size")
    ap.add_argument("--nav-top", type=int, default=0,
                    help="系统导航/手势区的起始 y（如 2280）；用于判断可点控件是否被手势条压住")
    ap.add_argument("--clip", action="store_true", help="报告贴边/越界的控件")
    ap.add_argument("--overlap", action="store_true", help="报告纵向区间重叠的控件")
    ap.add_argument("--columns", action="store_true", help="按 y 分组估算网格列数")
    ap.add_argument("--contains", default="", help="只看 label 含该子串的控件")
    args = ap.parse_args()

    w, h = (int(v) for v in args.screen.lower().split("x"))
    items = parse(sys.stdin.read())
    if args.contains:
        items = [i for i in items if args.contains in i["label"]]

    for i in items:
        print(f'{i["label"][:34]:<34} [{i["x1"]},{i["y1"]}][{i["x2"]},{i["y2"]}]')

    if args.clip:
        # 底部导航本身就在手势区里，是设计如此；真正要防的是别的控件掉进去。
        tabs = {"照片", "传输", "云创"}
        limit = args.nav_top or h
        bad = [i for i in items
               if i["label"] not in tabs and (i["y2"] > limit or i["x2"] > w or i["y1"] < 0)]
        print("\n-- clip --")
        if not bad:
            print(f"clean: no control below y={limit} except the bottom nav")
        for i in bad:
            print(f'CLIPPED {i["label"][:30]} bottom={i["y2"]} limit={limit}')

    if args.overlap:
        print("\n-- overlap --")
        rows = sorted({(i["y1"], i["y2"]) for i in items})
        clashes = [(a, b) for idx, a in enumerate(rows) for b in rows[idx + 1:]
                   if a[0] < b[1] and b[0] < a[1]]
        if not clashes:
            print("no vertical overlap between distinct blocks")
        for a, b in clashes:
            print(f"OVERLAP {a} <-> {b}")

    if args.columns:
        print("\n-- columns --")
        rows = {}
        for i in items:
            rows.setdefault(i["y1"] // 40, []).append(i)
        for key in sorted(rows):
            line = sorted(rows[key], key=lambda i: i["x1"])
            if len(line) > 1:
                print(f"y~{key * 40}: {len(line)} items -> {[i['label'][:16] for i in line]}")


if __name__ == "__main__":
    main()
