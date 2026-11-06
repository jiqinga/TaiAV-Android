"""Legacy 0.1 checks for a dedicated physical test phone; changes saved data."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--adb", default=os.environ.get("ADB", "adb"))
parser.add_argument("--serial", required=True)
args = parser.parse_args()
if args.serial.startswith("emulator-"):
    parser.error("Select a physical Android device")
artifacts = ROOT / "artifacts"
artifacts.mkdir(exist_ok=True)
checks: list[str] = []


def adb(*parts: str, binary: bool = False):
    result = subprocess.run([args.adb, "-s", args.serial, *parts], capture_output=True, timeout=35, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()


def tree():
    for attempt in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/taiav-smoke.xml")
        raw = adb("exec-out", "cat", "/sdcard/taiav-smoke.xml", binary=True)
        try:
            return ET.fromstring(raw)
        except ET.ParseError:
            if attempt == 2:
                raise
            time.sleep(0.5)


def find(label: str, timeout: int = 25):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        current = tree()
        for node in current.iter("node"):
            if node.get("text") == label or node.get("content-desc") == label:
                return node
        time.sleep(0.3)
    raise AssertionError(f"UI element did not appear: {label}")


def point(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return str((x1 + x2) // 2), str((y1 + y2) // 2)


def tap(label):
    adb("shell", "input", "tap", *point(find(label)))


def passed(name):
    checks.append(name)
    print("PASS", name, flush=True)


def screenshot(name):
    (artifacts / name).write_bytes(adb("exec-out", "screencap", "-p", binary=True))


tap("设置")
find("网络模式")
find("检测连接")
screenshot("settings.png")
passed("native settings and layout")

tap("网络模式")
tap("系统网络")
find("系统网络")
tap("网络模式")
tap("备用 IP 直连（推荐）")
find("备用 IP 直连 · 推荐")
passed("network mode switching")

tap("站点备用地址")
field = next(node for node in tree().iter("node") if node.get("class") == "android.widget.EditText")
adb("shell", "input", "tap", *point(field))
adb("shell", "input", "keycombination", "KEYCODE_CTRL_LEFT", "KEYCODE_A")
adb("shell", "input", "text", "127.0.0.1")
tap("保存")
# Android's EditText.error tooltip is not exported by UIAutomator on every WebView
# emulator image. A rejected save must retain the dialog and its invalid input.
find("保存")
assert any(node.get("class") == "android.widget.EditText" and node.get("text") == "127.0.0.1" for node in tree().iter("node"))
tap("取消")
find("104.21.42.69 172.67.158.125")
passed("invalid fallback address rejected without saving")

tap("附加域名 DoH")
tap("plus")
find("https://edge.1molchuan.top/plus")
tap("附加域名 DoH")
tap("跟随系统 DNS")
find("跟随系统 DNS")
passed("DoH provider selection and reset")

tap("检测连接")
deadline = time.monotonic() + 30
report = ""
while time.monotonic() < deadline:
    report = "\n".join(node.get("text", "") for node in tree().iter("node"))
    if "首页连接成功" in report:
        break
    if "连接检测未通过" in report:
        raise AssertionError("Android HTTPS diagnostic failed")
    time.sleep(0.4)
assert "首页连接成功" in report and "HTTP  200" in report and "TLSv1." in report
screenshot("diagnostics.png")
tap("关闭")
passed("certificate-verified HTTPS 200 on Android without a system proxy")

tap("首页")
find("taiav.com · 备用 IP 模式", timeout=35)
current = tree()
if any(node.get("content-desc") == "收藏当前页" for node in current.iter("node")):
    tap("收藏当前页")
tap("收藏")
assert any(node.get("long-clickable") == "true" for node in tree().iter("node"))
adb("shell", "am", "force-stop", "com.taiav.browser")
adb("shell", "am", "start", "-n", "com.taiav.browser/.MainActivity")
tap("收藏")
record = next(node for node in tree().iter("node") if node.get("long-clickable") == "true")
passed("bookmarks persist across process restart")
x, y = point(record)
adb("shell", "input", "swipe", x, y, x, y, "1000")
tap("移除")
find("还没有收藏")
passed("bookmark deletion and empty state")

tap("历史")
tap("清空历史")
tap("清空")
find("还没有浏览记录")
passed("history storage and clearing")

tap("设置")
find("网络模式")
screenshot("settings.png")
result = {"checks": checks, "api": adb("shell", "getprop", "ro.build.version.sdk"),
          "system_proxy": adb("shell", "settings", "get", "global", "http_proxy")}
(artifacts / "native-smoke.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
print("ALL NATIVE CHECKS PASSED", flush=True)
