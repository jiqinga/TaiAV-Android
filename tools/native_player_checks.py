"""Opt-in checks on one authorized physical phone; does not reset app data.

Set TAIAV_DEVICE_SERIAL. Stages: connect, media3, mpv, quality, controls,
download, download_wait, settings, offline_media3, offline_mpv, background, web.
Uses the existing page, source discovery and native UI rather than exported test activities.
"""
import json
import base64
import subprocess
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path
import device_debug as d

sys.stdout.reconfigure(encoding="utf-8")
d.SESSION = "taiav-native-040"
VERSION = re.search(r'versionName\s*=\s*"([^"]+)"', (d.ROOT / "app/build.gradle.kts").read_text("utf-8"))[1]
REPORT = d.ROOT / f"artifacts/native-players-{VERSION}.json"

def js(source):
    encoded = base64.b64encode(source.encode()).decode()
    result = subprocess.run([d.browser_exe(), "--session", d.SESSION, "--cdp", "19366", "--json", "eval", "-b", encoded],
        capture_output=True, timeout=35)
    if result.returncode:
        raise AssertionError(result.stderr.decode("utf-8", "replace"))
    data = json.loads(result.stdout)
    assert data.get("success"), data
    return data["data"]["result"]

def visible():
    return [value for n in d.ui() for value in (n.get("text", ""), n.get("content-desc", "")) if value]

def wait_for(predicate, timeout=35):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        value = predicate()
        if value:
            return value
        time.sleep(.5)
    raise AssertionError("Device condition timed out")

def record(name, **details):
    report = json.loads(REPORT.read_text("utf-8")) if REPORT.exists() else {"device": d.SERIAL, "checks": []}
    report["checks"].append({"name": name, "passed": True, **details})
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS", name, json.dumps(details, ensure_ascii=False), flush=True)

def show_controls():
    nodes = d.ui()
    if not any(n.get("content-desc") == "使用网页播放器" for n in nodes):
        # Accessibility bounds follow rotation; wm size reports the natural orientation.
        tree = ET.fromstring(d.adb("exec-out", "cat", "/sdcard/taiav-feature-test.xml", binary=True))
        bounds = [list(map(int, re.findall(r"\d+", n.get("bounds", "")))) for n in tree.iter("node") if n.get("bounds")]
        # Reveal from empty video space, away from the centered play/pause button.
        d.adb("shell", "input", "tap", str(max(b[2] for b in bounds)//2), str(int(max(b[3] for b in bounds)*.30)))
        nodes = d.ui()
    return nodes

def tap_visible(label):
    nodes = d.ui()
    found = next((n for n in nodes if n.get("text") == label or n.get("content-desc") == label), None)
    if not found:
        nodes = show_controls()
        found = next((n for n in nodes if n.get("text") == label or n.get("content-desc") == label), None)
    assert found, label
    d.adb("shell", "input", "tap", *d.center(found))

def ensure_paused():
    # The controls may expire between an accessibility dump and its tap.
    for _ in range(5):
        nodes = d.ui()
        if any(n.get("content-desc") == "播放" for n in nodes): return
        button = next((n for n in nodes if n.get("content-desc") == "暂停"), None)
        if button: d.adb("shell", "input", "tap", *d.center(button))
        else:
            width, height, items = player_layout(include_hidden=True)
            # Reveal and pause without another slow accessibility dump between
            # taps, so the four-second auto-hide cannot race the pause action.
            d.adb("shell", "input", "tap", str(width//2), str(int(height*.30)))
            b = items["player_play_pause"]
            d.adb("shell", "input", "tap", str((b[0]+b[2])//2), str((b[1]+b[3])//2))
    raise AssertionError("Pause control did not settle")

def seek_label(label):
    for _ in range(24):
        nodes = d.ui()
        found = next((n for n in nodes if n.get("text") == label and n.get("bounds") != "[0,0][0,0]"), None)
        if found:
            try:
                d.adb("shell", "input", "tap", *d.center(found)); return
            except Exception: pass
        bounds = [list(map(int, re.findall(r"\d+", n["bounds"]))) for n in nodes if n.get("bounds")]
        width = max(b[2] for b in bounds)
        height = max(b[3] for b in bounds)
        d.adb("shell", "input", "swipe", str(width//2), str(int(height*.72)), str(width//2), str(int(height*.42)), "800")
    raise AssertionError(label)

def settings_nav():
    node = next(n for n in d.ui() if n.get("content-desc") == "设置" and n.get("class") == "android.widget.LinearLayout")
    d.adb("shell", "input", "tap", *d.center(node))

def playing_position():
    rows = [value for n in show_controls() for value in (n.get("text", ""), n.get("content-desc", "")) if value]
    if any("未成功" in value for value in rows):
        raise AssertionError([value for value in rows if "未成功" in value])
    timeline = next((value for value in rows if re.match(r"\d+:\d+ / \d+:\d+", value)), None)
    if not timeline:
        return None
    pos, duration = timeline.split(" / ")
    def seconds(value):
        total = 0
        for part in value.split(":"):
            total = total*60 + int(part)
        return total
    return (seconds(pos), seconds(duration)) if seconds(duration) > 0 else None


def player_layout(include_hidden=False):
    # UIAutomator clips nodes to Display.getSize(), which excludes the cutout on
    # some Android 11 phones even when the actual activity fills the display.
    # Measure the rendered view hierarchy instead of treating that clip as UI loss.
    dump = d.adb("shell", "dumpsys", "activity", "top")
    activity = re.search(r"ACTIVITY com\.taiav\.browser/\.NativePlayerActivity\b", dump)
    assert activity, "Native player activity missing"
    hierarchy = dump[activity.end():].split("View Hierarchy:", 1)[1].split("Looper (", 1)[0]
    stack, items = [], {}
    width = height = 0
    for line in hierarchy.splitlines():
        match = re.search(r" (-?\d+),(-?\d+)-(-?\d+),(-?\d+)(?: |\})", line)
        if not match: continue
        depth = len(line)-len(line.lstrip())
        while stack and stack[-1][0] >= depth: stack.pop()
        x, y, right, bottom = map(int, match.groups())
        parent_x, parent_y = stack[-1][1:3] if stack else (0, 0)
        hidden = (stack[-1][3] if stack else False) or bool(re.search(r"\{\S+ [GI]", line))
        absolute = [parent_x+x, parent_y+y, parent_x+right, parent_y+bottom]
        stack.append((depth, absolute[0], absolute[1], hidden))
        if not width: width, height = right-x, bottom-y
        view_id = re.search(r"app:id/(player_\w+)", line)
        if view_id and (include_hidden or not hidden): items[view_id[1]] = absolute
    assert width and height and len(items) == 8, (width, height, items)
    return width, height, items

stage = sys.argv[1]
if stage == "connect":
    pid = d.adb("shell", "pidof", "com.taiav.browser").split()[0]
    d.adb("forward", "tcp:19366", "localabstract:webview_devtools_remote_" + pid)
    subprocess.run([d.browser_exe(), "--session", d.SESSION, "connect", "19366"], check=True, timeout=35)
    print(js('({site:location.hostname, monitor:!!window.__taiavMedia})'))
elif stage == "media3":
    js("(()=>{const a=document.querySelector('a[href*=\"/cn/movie/\"]');if(!a)throw Error('movie link missing');location.assign(a.href);return true})()")
    wait_for(lambda: js('!!window.__taiavMedia&&!!document.querySelector("#player video")'))
    js('document.title="播放器真机验证";true')
    if "继续观看？" in visible(): d.tap("继续观看")
    d.tap("选择播放器"); d.tap("Media3 ExoPlayer（推荐）"); d.tap("播放")
    names = visible()
    if "选择媒体源" in names:
        source = next(value for value in names if value.startswith("1. "))
        d.tap(source)
    first = wait_for(playing_position, 45)
    second = wait_for(lambda: (value if (value := playing_position()) and value[0] > first[0] else None), 20)
    record("Media3 real HTTPS media playback", position=second[0], duration=second[1])
elif stage == "mpv":
    tap_visible("暂停")
    before = playing_position()
    tap_visible("Media3"); d.tap("mpv")
    wait_for(playing_position, 45)
    d.tap("播放")
    first = wait_for(playing_position, 45)
    second = wait_for(lambda: (value if (value := playing_position()) and value[0] > first[0] else None), 20)
    assert second[0] >= before[0]-2
    record("mpv real HTTPS playback and switch position", position=second[0], duration=second[1])
elif stage == "controls":
    tap_visible("暂停")
    before = playing_position()
    d.tap("快进10秒")
    after = wait_for(lambda: value if (value := playing_position()) and value[0] >= before[0]+8 else None)
    d.tap("倍速"); d.tap("1.5×")
    d.tap("全屏")
    d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
    assert "使用网页播放器" in visible()
    record("mpv seek speed and fullscreen back", before=before[0], after=after[0])
elif stage == "quality":
    tap_visible("暂停")
    d.tap("画质")
    items = visible()
    assert "清晰度" in items
    record("quality menu on real media", choices=[x for x in items if "kbps" in x or "原始" in x or "画质" in x or "自动" in x])
    if "知道了" in items: d.tap("知道了")
    else: d.tap("取消")
    d.tap("播放")
elif stage in {"download", "download_wait"}:
    if stage == "download":
        tap_visible("暂停")
        d.tap("离线下载"); d.tap("下载")
        # The preparation runs independently; leave through the normal back flow.
        d.tap("使用网页播放器")
        wait_for(lambda: "设置" in visible())
        d.tap("设置")
        seek_label("离线下载")
    def complete():
        rows = visible()
        if any("下载中断" in value for value in rows): raise AssertionError(rows)
        if any("已完成" in value for value in rows): return rows
        print("DOWNLOAD", next((value for value in rows if "下载中" in value or "等待下载" in value), "preparing"), flush=True)
        time.sleep(3)
        return None
    rows = wait_for(complete, 1800)
    record("persistent HLS offline download completed", summary=next(value for value in rows if "已完成" in value))
elif stage == "download_pause":
    d.tap("播放器真机验证"); d.tap("暂停下载")
    wait_for(lambda: any("已暂停" in x for x in visible()))
    d.tap("播放器真机验证"); d.tap("继续 / 重试下载")
    wait_for(lambda: any("下载中" in x or "已完成" in x for x in visible()))
    record("download pause and resume retains existing segments")
elif stage == "settings":
    settings_nav()
    seek_label("预加载缓冲"); d.tap("120 秒")
    seek_label("播放缓存容量"); d.tap("512 MB")
    prefs = d.prefs()
    assert prefs.get("preload_seconds") == "120", prefs.get("preload_seconds")
    assert prefs.get("playback_cache_mb") == "512", prefs.get("playback_cache_mb")
    record("preload and cache settings persisted", preloadSeconds=120, cacheMb=512)
    seek_label("预加载缓冲"); d.tap("60 秒")
    seek_label("播放缓存容量"); d.tap("256 MB")
    seek_label("离线下载")
elif stage == "download_concurrency":
    settings_nav()
    seek_label("下载并发数")
    choices = visible()
    assert all(f"{value} 路" in choices for value in (1, 2, 3, 4, 6, 8, 12, 16, 24, 32)), choices
    d.tap("32 路")
    assert d.prefs().get("download_concurrency") == "32"
    seek_label("下载并发数"); d.tap("1 路")
    assert d.prefs().get("download_concurrency") == "1"
    seek_label("下载并发数"); d.tap("6 路")
    assert d.prefs().get("download_concurrency") == "6"
    record("download concurrency range and resizing", choices=[1, 2, 3, 4, 6, 8, 12, 16, 24, 32], restored=6)
    seek_label("离线下载")
elif stage in {"icons_media3", "icons_mpv"}:
    engine = "Media3" if stage.endswith("media3") else "mpv"
    print("CHECK", engine, "open and pause", flush=True)
    d.tap("播放器真机验证"); d.tap(f"{engine} 离线播放")
    wait_for(playing_position, 45)
    ensure_paused()
    rows = d.ui()
    descriptions = {n.get("content-desc"): n for n in rows}
    expected = ("播放", "快退10秒", "快进10秒", "倍速", "画质", "全屏", "使用网页播放器")
    for label in expected:
        node = descriptions[label]
        assert node.get("class") == "android.widget.ImageButton", node
        assert node.get("clickable") == "true", node
    density_text = d.adb("shell", "wm", "density")
    density = int(re.findall(r"density:\s*(\d+)", density_text)[-1]) / 160
    def check_layout(nodes, screen):
        width, height, measured = player_layout()
        items = {n["content-desc"]: measured[n["resource-id"].split("/")[-1]] for n in nodes
                 if n.get("content-desc") and n.get("resource-id", "").split("/")[-1] in measured}
        center = lambda b: ((b[0]+b[2])/2, (b[1]+b[3])/2)
        play = items["播放"]
        px, py = center(play)
        assert abs(px-width/2) < width*.06 and abs(py-height/2) < height*.06, (screen, play, width, height)
        rewind, forward = items["快退10秒"], items["快进10秒"]
        assert rewind[2] < play[0] and forward[0] > play[2], (screen, rewind, play, forward)
        assert abs(center(rewind)[1]-py) < 5 and abs(center(forward)[1]-py) < 5
        assert items["播放进度"][1] > play[3], (screen, items)
        for label in ("倍速", "画质", "退出全屏" if screen == "landscape" else "全屏"):
            assert items[label][1] > items["播放进度"][1], (screen, label, items)
        for label in ("播放", "快退10秒", "快进10秒", "倍速", "画质", "退出全屏" if screen == "landscape" else "全屏", "使用网页播放器"):
            b = items[label]
            assert b[2]-b[0] >= 48*density-1 and b[3]-b[1] >= 48*density-1, ("clipped touch target", screen, label, b)
            assert 0 <= b[0] < b[2] <= width and 0 <= b[1] < b[3] <= height, (screen, label, b, width, height)
        assert items["使用网页播放器"][3] < play[1]
        return {"screen": screen, "width": width, "height": height, "playBounds": play, "buttonBounds": items, "geometrySource": "activity rendered view hierarchy"}
    initial_layout = check_layout(rows, "portrait")
    # Start away from the end so both seek directions have room.
    bar = descriptions["播放进度"]
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bar["bounds"]))
    d.adb("shell", "input", "tap", str(x1+(x2-x1)//4), str((y1+y2)//2))
    before = wait_for(playing_position)
    print("CHECK", engine, "seek", before, flush=True)
    tap_visible("快进10秒")
    forward = wait_for(lambda: value if (value := playing_position()) and value[0] >= before[0]+8 else None)
    tap_visible("快退10秒")
    back = wait_for(lambda: value if (value := playing_position()) and abs(value[0]-before[0]) <= 2 else None)
    print("CHECK", engine, "resume", back, flush=True)
    tap_visible("播放")
    wait_for(lambda: "暂停" in visible())
    wait_for(lambda: value if (value := playing_position()) and value[0] >= back[0]+2 else None)
    time.sleep(5)
    hidden = visible()
    assert all(label not in hidden for label in ("暂停", "快进10秒", "倍速", "播放进度", "使用网页播放器")), hidden
    ensure_paused()
    paused = playing_position()
    time.sleep(2)
    assert abs(playing_position()[0]-paused[0]) <= 1
    print("CHECK", engine, "menus and fullscreen", flush=True)
    tap_visible("倍速"); d.tap("1.5×")
    tap_visible("画质")
    assert "清晰度" in visible()
    if "知道了" in visible(): d.tap("知道了")
    else: d.tap("取消")
    tap_visible("全屏")
    wait_for(lambda: "退出全屏" in visible())
    landscape = d.ui()
    for label in ("播放", "快退10秒", "快进10秒", "倍速", "画质", "退出全屏"):
        assert any(n.get("content-desc") == label and n.get("class") == "android.widget.ImageButton" for n in landscape), label
    fullscreen_layout = check_layout(landscape, "landscape")
    assert fullscreen_layout["width"] > fullscreen_layout["height"], fullscreen_layout
    d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_for(lambda: "全屏" in visible())
    record(engine + " centered controls, playback state, seek, menus and fullscreen", before=before[0], forward=forward[0], back=back[0], networkUpstream=False, layouts=[initial_layout, fullscreen_layout])
    tap_visible("使用网页播放器")
elif stage in {"offline_media3", "offline_mpv"}:
    d.tap("播放器真机验证")
    d.tap("Media3 离线播放" if stage.endswith("media3") else "mpv 离线播放")
    first = wait_for(playing_position, 45)
    second = wait_for(lambda: (value if (value := playing_position()) and value[0] > first[0] else None), 20)
    record(stage + " cache-only playback", position=second[0], duration=second[1], networkUpstream=False)
    tap_visible("使用网页播放器")
elif stage == "offline_controls":
    d.tap("播放器真机验证"); d.tap("mpv 离线播放")
    wait_for(playing_position)
    tap_visible("暂停"); before = playing_position()
    d.tap("快进10秒")
    after = wait_for(lambda: value if (value := playing_position()) and value[0] >= before[0]+8 else None)
    d.tap("倍速"); d.tap("1.5×")
    d.tap("全屏"); d.adb("shell", "input", "keyevent", "KEYCODE_BACK")
    assert "使用网页播放器" in visible()
    record("offline mpv seek speed and fullscreen back", before=before[0], after=after[0])
    d.tap("使用网页播放器")
elif stage == "clear_cache":
    settings_nav(); seek_label("清理播放缓存"); d.tap("清理")
    seek_label("离线下载")
    wait_for(lambda: any("已完成" in x for x in visible()))
    record("clear rolling cache preserves completed downloads")
elif stage == "restart_offline":
    d.adb("shell", "am", "force-stop", "com.taiav.browser")
    d.adb("shell", "am", "start", "-n", "com.taiav.browser/.MainActivity", "--ez", "offline", "true")
    wait_for(lambda: any("已完成" in x for x in visible()))
    record("completed download persists across app restart")
elif stage == "background":
    before = playing_position()
    d.adb("shell", "input", "keyevent", "KEYCODE_HOME")
    time.sleep(2)
    d.adb("shell", "am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", "com.taiav.browser/.MainActivity", "-f", "0x10200000")
    wait_for(lambda: "播放" in visible())
    after = wait_for(playing_position)
    assert abs(after[0]-before[0]) <= 3, (before, after)
    record("background pause and session recreation", position=after[0], savedRecords=len(json.loads(d.prefs().get("playback", "[]"))))
elif stage == "web":
    tap_visible("使用网页播放器")
    wait_for(lambda: "选择播放器" in [node.get("content-desc") for node in d.ui()])
    result = js('({paused:document.querySelector("#player video")?.paused,position:window.__taiavMedia?.snapshot()?.position})')
    assert result.get("paused") is True
    record("return to webpage", **result)
else:
    raise ValueError(stage)
