#!/usr/bin/env python3
"""NP3 (Nikon Picture Control) 二进制格式解析器/生成器。

格式逆向自 32 个真实样本（Timor88/NikonNP3），字节级重组验证 32/32 无损。
详见 docs/research/np3-format.md。

布局:
  0x00 "NCP\0" | 00000100 | 00000004 | "0310" | 00000200
  0x14 u16 name_len(=0x0014) | 20B name(picture_controll_name, \0 pad)
  0x2C 2B 0000
  0x2E TLV: tag u8 + 3B 00 + len u16 BE + val + 2B 00
      tag 0x03..0x1E: len=2, val u16 BE
      tag 0x1F: len=28 基准曲线锚点
      tag 0x20: len=20 色相调优表
  392B 版: 尾部 "0000"
  978B 版: 尾部含 BI0 自定义色调曲线块 + "0000 0000"
"""
import struct

HEADER = bytes.fromhex('4e435000000001000000000430333130000002000000001400')

def parse(data: bytes):
    assert data[:4] == b'NCP\0', 'bad magic'
    name = data[0x18:0x2c].rstrip(b'\x00').decode('utf-8')
    off = 0x2e
    recs = {}
    while off + 6 <= len(data):
        tag = data[off]
        ln = int.from_bytes(data[off+4:off+6], 'big')
        if tag == 0 and ln == 0:
            off += 6
            break
        recs[tag] = data[off+6:off+6+ln]
        off += 6 + ln + 2
        if tag == 0x20:
            break
    return name, recs, data[off:]

def build(name: str, recs: dict, tail: bytes = b'\x00\x00') -> bytes:
    out = bytearray(HEADER[:0x14])  # 0x14 = 20B 头部（到 name_len 字段前）
    out += b'\x00\x00'              # name_len u16 = 0x0014
    out += b'\x00\x14'
    out += name.encode('utf-8')[:20].ljust(20, b'\x00')
    out += b'\x00\x00'
    for tag in sorted(recs):
        val = recs[tag]
        out += bytes([tag]) + b'\x00\x00\x00' + len(val).to_bytes(2, 'big') + val + b'\x00\x00'
    out += tail
    return bytes(out)

def signed_u8(v: int) -> int:
    """0x80 基准有符号字节: 0x80=0, 0x81=+1, 0x7f=-1"""
    return v - 0x100 if v > 0x7f else v

if __name__ == '__main__':
    import sys, glob
    ok = 0
    files = sys.argv[1:] or sorted(glob.glob('/tmp/np3all/repo/NP3/*.NP3'))
    for p in files:
        orig = open(p, 'rb').read()
        name, recs, tail = parse(orig)
        rebuilt = build(name, recs, tail)
        status = 'OK ' if rebuilt == orig else 'FAIL'
        ok += rebuilt == orig
        print(f'{status} {p} ({len(orig)}B)')
    print(f'{ok}/{len(files)} byte-exact')
