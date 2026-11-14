#!/usr/bin/env python3
"""NP3 生成/对照脚本。

用法：`python3 tools/np3/np3_generate.py --samples <NP3目录>`

注意（2026-11-14）：仓库不再内置第三方 .NP3 样本与官方方案详情快照（避免再分发他人作品）。
要复现对照结论，请自备样本：把相机导出的 .NP3 放进任意目录，用 --samples 指向它。

NP3 生成器：把尼康云创官方参数表（/bff/recipe/release/detail 或 publicrecipe）转换为 NP3 文件字节。

字段映射（对 baseName = FLEXIBLE COLOR，32/32 样本验证）：
  tag 0x06 sharpening        每 0.25 一步：hi = 4*v + 0x80, lo = 0x04
  tag 0x07 clarity           同上
  tag 0x16 middleRangeSharp  同上
  tag 0x14 quickSharp        hi = v + 0x80, lo = 0x01（API 值为 "-" 时写 0xff01）
  tag 0x19 contrast          同上；API 值为 "gamma" 时写 0x0101
  tag 0x1a highlight / 0x1b shadow / 0x1c whiteLevel / 0x1d blackLevel / 0x1e saturation
                             同上（gamma 同 0x0101）

API 未提供、需从样本或相机侧取得的字段：
  tag 0x1F 14 个曲线锚点（28B）
  tag 0x20 色相/饱和微调表（20B）
  尾部 BI0 自定义色调曲线块（978B 版独有）
"""
import json
import sys

sys.path.insert(0, __file__.rsplit('/', 1)[0])
import np3_format as F

# 常量字段（FLEXIBLE COLOR 基准，32/32 样本一致）
CONST_TAGS = {
    0x03: '0020', 0x04: '0000', 0x05: 'ff01',
    0x08: 'ff04', 0x09: 'ff04', 0x0a: 'ff04', 0x0b: 'ff04',
    0x0c: 'ff00', 0x0d: 'ff00', 0x0e: 'ff04',
    0x0f: 'ff01', 0x10: 'ff01', 0x11: 'ff01', 0x12: 'ff01', 0x13: 'ff01',
    0x15: 'ff0a', 0x17: 'ff04', 0x18: 'ff04',
}

# 参数 → (tag, 每单位步数, lo 字节)
PARAM_TAGS = {
    'sharpening': (0x06, 4, 0x04),
    'clarity': (0x07, 4, 0x04),
    'middleRangeSharp': (0x16, 4, 0x04),
    'quickSharp': (0x14, 1, 0x01),
    'contrast': (0x19, 1, 0x01),
    'highlight': (0x1a, 1, 0x01),
    'shadow': (0x1b, 1, 0x01),
    'whiteLevel': (0x1c, 1, 0x01),
    'blackLevel': (0x1d, 1, 0x01),
    'saturation': (0x1e, 1, 0x01),
}

GAMMA = bytes([0x01, 0x01])
UNSET = lambda lo: bytes([0xff, lo])          # API 值为 "-"（未设置）
NEUTRAL_CURVE = bytes([0x80] * 24 + [0x01, 0x01, 0x01, 0x00])
NEUTRAL_HUE = bytes.fromhex('80008080800080808000808001010100b2018001')


def params_to_tags(pc, curve=None, hue=None):
    """官方参数表 → tag 表。curve/hue 缺省用中性值。"""
    tags = {t: bytes.fromhex(v) for t, v in CONST_TAGS.items()}
    for param, (tag, scale, lo) in PARAM_TAGS.items():
        raw = str(pc.get(param, '-')).strip()
        if raw in ('-', ''):
            tags[tag] = UNSET(lo)
        elif raw == 'gamma':
            tags[tag] = GAMMA
        else:
            hi = int(round(float(raw) * scale)) + 0x80
            if not 0 <= hi <= 0xff:
                raise ValueError(f'{param}={raw} 超出可编码范围')
            tags[tag] = bytes([hi, lo])
    tags[0x1f] = curve if curve is not None else NEUTRAL_CURVE
    tags[0x20] = hue if hue is not None else NEUTRAL_HUE
    return tags


def build_np3(pc, name=None, curve=None, hue=None, tail=b'\x00\x00'):
    """生成 NP3 字节。name 缺省取 settingName（上限 20 字节）。"""
    name = (name or pc.get('settingName') or 'NP3')[:20]
    return F.build(name, params_to_tags(pc, curve, hue), tail)


def main():
    """自检：用 32 个样本的官方参数重建 NP3，与原始文件逐字节对照。"""
    import glob
    pairs = json.load(open('/tmp/pairs.json'))
    ok_a = ok_b = 0
    print(f'{"样本":24} {"大小":>5} {"仅API参数":>10} {"API+曲线":>10}')
    for name, tags, pc in pairs:
        orig = open(f'app/src/test/resources/np3/{name}.NP3', 'rb').read()
        _, _, tail = F.parse(orig)
        gen_a = build_np3(pc)                                   # 只用 API 参数
        gen_b = build_np3(pc, name=name,
                          curve=bytes.fromhex(tags['31']),
                          hue=bytes.fromhex(tags['32']), tail=tail)
        da = sum(1 for x, y in zip(orig, gen_a) if x != y) + abs(len(orig) - len(gen_a))
        print(f'{name[:24]:24} {len(orig):5} {da:10} {"一致" if orig == gen_b else "差异":>10}')
        ok_a += orig == gen_a
        ok_b += orig == gen_b
    print(f'\n仅 API 参数完全一致: {ok_a}/32；API 参数 + 样本曲线: {ok_b}/32')


if __name__ == '__main__':
    main()
