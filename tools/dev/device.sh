#!/usr/bin/env bash
# 真机自动化：安装、启动、截图、抓日志、按脚本点按。
#
# 前提：手机已开「开发者选项 → 无线调试」，并已 `adb pair` + `adb connect`。
# 用法：
#   tools/dev/device.sh install      构建并 -r 安装（保留应用数据，含加密后的云创 token）
#   tools/dev/device.sh launch       启动 App
#   tools/dev/device.sh shot [名字]  截图到 tools/dev/shots/
#   tools/dev/device.sh logs         实时看 App 日志（Ctrl-C 退出）
#   tools/dev/device.sh dump         把当前界面层级存到 tools/dev/shots/ui.xml
#   tools/dev/device.sh tap X Y      按坐标点一下
#   tools/dev/device.sh text "abc"   输入 ASCII 文本
#   tools/dev/device.sh cam-shot     截图 + 当前界面层级 + 最近 200 行日志（一次性取证）
set -uo pipefail

cd "$(dirname "$0")/../.." || exit 1

APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="com.chrisz.nikconnect.debug"
ACT="$PKG/com.chrisz.nikconnect.MainActivity"
OUT="tools/dev/shots"

need_device() {
  if ! adb get-state 2>/dev/null | grep -q device; then
    echo "没有已连接的设备。先做一次：" >&2
    echo "  1) 手机：设置 → 开发者选项 → 无线调试 → 使用配对码配对设备" >&2
    echo "  2) adb pair <手机IP>:<配对端口>    # 配对码由你在终端里输入" >&2
    echo "  3) adb connect <手机IP>:<调试端口>" >&2
    exit 1
  fi
}

case "${1:-help}" in
  install)
    need_device
    JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}" \
      ./gradlew :app:assembleDebug --offline >/dev/null || exit 1
    adb install -r "$APK" && echo "已安装（数据保留）"
    ;;
  launch)
    need_device
    adb shell am force-stop "$PKG"
    adb shell am start -n "$ACT" >/dev/null && echo "已启动 $ACT"
    ;;
  shot)
    need_device
    mkdir -p "$OUT"
    name="${2:-shot-$(date +%H%M%S)}"
    adb exec-out screencap -p > "$OUT/$name.png" && echo "$OUT/$name.png"
    ;;
  dump)
    need_device
    mkdir -p "$OUT"
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null && echo "$OUT/ui.xml"
    ;;
  logs)
    need_device
    adb logcat -v time -s NikConnect:V AndroidRuntime:E
    ;;
  tap)
    need_device
    adb shell input tap "$2" "$3"
    ;;
  text)
    need_device
    adb shell input text "$2"
    ;;
  cam-shot)
    need_device
    mkdir -p "$OUT"
    ts=$(date +%H%M%S)
    adb exec-out screencap -p > "$OUT/$ts.png"
    adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
    adb pull /sdcard/ui.xml "$OUT/$ts.xml" >/dev/null
    adb logcat -d -v time -s NikConnect:V AndroidRuntime:E | tail -200 > "$OUT/$ts.log"
    echo "$OUT/$ts.png  $OUT/$ts.xml  $OUT/$ts.log"
    ;;
  *)
    sed -n '2,20p' "$0"
    ;;
esac
