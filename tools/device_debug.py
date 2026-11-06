"""Small, explicit device debug commands; never clears application data."""
import base64
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ADB = r"C:\Users\feng\AppData\Local\mise\installs\android-sdk\22.0\platform-tools\adb.exe"
SESSION = "taiav-physical-031"
SERIAL = os.environ.get("TAIAV_DEVICE_SERIAL", "")

def browser_exe():
    shim = shutil.which("agent-browser.cmd")
    native = Path(shim).parent / "node_modules/agent-browser/bin/agent-browser-win32-x64.exe" if shim else None
    return str(native) if native and native.exists() else (shim or "agent-browser")

def adb(*args, binary=False):
    if not SERIAL: raise ValueError("Set TAIAV_DEVICE_SERIAL to the explicitly selected device")
    if SERIAL.startswith("emulator-"): raise ValueError("Use an authorized physical Android device")
    result = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, timeout=40, check=True)
    return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()

def tree():
    adb("shell", "uiautomator", "dump", "/sdcard/taiav-feature-test.xml")
    return ET.fromstring(adb("exec-out", "cat", "/sdcard/taiav-feature-test.xml", binary=True))

def center(node):
    a,b,c,d = map(int, re.findall(r"\d+", node.get("bounds", "")))
    return str((a+c)//2), str((b+d)//2)

def tap(label):
    for node in tree().iter("node"):
        if node.get("text") == label or node.get("content-desc") == label:
            if node.get("bounds") == "[0,0][0,0]": continue
            adb("shell", "input", "tap", *center(node))
            return
    raise AssertionError("Missing UI item: " + label)

def ui():
    return [{k:n.get(k) for k in ("text", "content-desc", "class", "bounds", "clickable", "resource-id") if n.get(k)}
            for n in tree().iter("node") if n.get("bounds") != "[0,0][0,0]" and (n.get("text") or n.get("content-desc") or n.get("class")=="android.widget.EditText")]

def js(source):
    encoded = base64.b64encode(source.encode()).decode()
    command = [browser_exe(), "--session", SESSION, "--cdp", "19365", "--json", "eval", "-b", encoded]
    result = subprocess.run(command, capture_output=True, timeout=45, check=True)
    data = json.loads(result.stdout.decode("utf-8", "replace"))
    if not data.get("success"):
        raise AssertionError(data)
    return data["data"]["result"]

def browser(*args):
    for attempt in range(2):
        result = subprocess.run([browser_exe(), "--session", SESSION, "--cdp", "19365", *args],capture_output=True,timeout=45)
        if result.returncode and args[0] in {"wait", "get"} and b"closed connection" in result.stderr and attempt == 0: continue
        break
    if result.returncode: raise AssertionError(result.stderr.decode("utf-8","replace")+result.stdout.decode("utf-8","replace"))
    return result.stdout.decode("utf-8", "replace")

def prefs():
    root = ET.fromstring(adb("exec-out", "run-as", "com.taiav.browser", "cat", "shared_prefs/browser.xml", binary=True))
    return {n.get("name"): (n.text or "") if n.tag=="string" else n.get("value") for n in root}

if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    action = sys.argv[1]
    if action == "ui": print(json.dumps(ui(), ensure_ascii=False, indent=2))
    elif action == "tap": tap(sys.argv[2])
    elif action == "scroll":
        adb("shell", "input", "swipe", "540", "1850" if sys.argv[2]=="down" else "700", "540", "700" if sys.argv[2]=="down" else "1850", "350")
    elif action == "js": print(json.dumps(js(sys.argv[2]), ensure_ascii=False))
    elif action == "prefs": print(json.dumps(prefs(), ensure_ascii=False, indent=2))
    elif action == "shot":
        path = ROOT / "artifacts" / sys.argv[2]
        path.write_bytes(adb("exec-out", "screencap", "-p", binary=True)); print(path)
    elif action == "back": adb("shell", "input", "keyevent", "KEYCODE_BACK")
    else: raise ValueError(action)
