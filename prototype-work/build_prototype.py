from __future__ import annotations

import base64
import io
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
TEMPLATE = ROOT / "prototype-work" / "nikon-prototype.template.html"
OUTPUT = ROOT / "nikon-companion-prototype.html"


def data_uri(path: Path, max_width: int, quality: int, image_format: str) -> str:
    with Image.open(path) as source:
        image = source.copy()
    if image.width > max_width:
        height = round(image.height * max_width / image.width)
        image = image.resize((max_width, height), Image.Resampling.LANCZOS)
    if image_format == "JPEG":
        image = image.convert("RGB")
    buffer = io.BytesIO()
    image.save(buffer, format=image_format, quality=quality, optimize=True, method=6)
    mime = "image/jpeg" if image_format == "JPEG" else "image/webp"
    return f"data:{mime};base64,{base64.b64encode(buffer.getvalue()).decode('ascii')}"


html = TEMPLATE.read_text(encoding="utf-8")
replacements = {
    "__LOGO__": data_uri(ROOT / "assets" / "nikon-brand" / "nikon-logo.png", 96, 78, "WEBP"),
    "__CAMERA__": data_uri(ROOT / "assets" / "nikon-brand" / "nikon-z6iii.jpg", 640, 78, "JPEG"),
    "__LANDSCAPE__": data_uri(ROOT / "prototype-work" / "landscape.jpg", 960, 82, "JPEG"),
}
for placeholder, uri in replacements.items():
    html = html.replace(placeholder, uri)

provenance = """<!--
  内嵌素材来源：
  Nikon 标志：Wikimedia Commons / File:Nikon Logo.svg（商标权利归 Nikon）。
  Z6III 照片：昼落ち / Wikimedia Commons / File:Nikon Z6III 13 jul 2024g.jpg（CC0）。
  山湖照片：Johannes Plenio / Beautiful mountain reflection（CC0）。
  本文件不发起任何外部资源请求。界面中的用户数据均为原型演示数据。
-->
"""
html = html.replace("<html lang=\"zh-CN\">", "<html lang=\"zh-CN\">\n" + provenance, 1)
OUTPUT.write_text(html, encoding="utf-8")
print(f"Built {OUTPUT} ({OUTPUT.stat().st_size:,} bytes)")
