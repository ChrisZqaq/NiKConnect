#!/usr/bin/env bash
# 从手机把 Pictures/NiKConnect 里最新的 N 个文件拉到本机并算 SHA-256。
# 用法: tools/dev/pull_hashes.sh <serial> [N]
set -euo pipefail
SERIAL="${1:?需要 adb serial，例如 192.168.110.227:5555}"
N="${2:-6}"
OUT="$(mktemp -d /tmp/nik-samples.XXXXXX)"
adb -s "$SERIAL" shell ls -t /sdcard/Pictures/NiKConnect/ | head -n "$N" > "$OUT/list.txt"
if [ ! -s "$OUT/list.txt" ]; then echo "手机上没有 Pictures/NiKConnect 里的文件"; exit 1; fi
while IFS= read -r name; do
  name="${name%$'\r'}"
  [ -z "$name" ] && continue
  adb -s "$SERIAL" pull "/sdcard/Pictures/NiKConnect/$name" "$OUT/$name" >/dev/null 2>&1 || continue
done < "$OUT/list.txt"
echo "样本目录: $OUT"
printf '%-8s %-52s %s\n' SIZE NAME SHA256
for f in "$OUT"/*; do
  [ -f "$f" ] || continue
  printf '%-8s %-52s %s\n' "$(stat -f %z "$f")" "$(basename "$f")" "$(shasum -a 256 "$f" | cut -c1-16)"
done
