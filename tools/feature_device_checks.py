"""Explicit physical-device scenarios. Set TAIAV_DEVICE_SERIAL before running.

Stages: init, ads, export, backup, media, fullscreen, diagnostics, cleanup.
The device is never reset. Backup scenarios restore the initial bookmarks/rules.
"""
import hashlib
import json
import os
import sys
import time
from pathlib import Path
import device_debug as d

sys.stdout.reconfigure(encoding="utf-8")
sys.stderr.reconfigure(encoding="utf-8")
VERSION = os.environ.get("TAIAV_TEST_VERSION", "0.4.0")
TAG = VERSION.replace('.', '')
REPORT = d.ROOT / f"artifacts/phone-features-{VERSION}.json"
FIXTURES = d.ROOT / "artifacts/phone-fixtures"
VIDEO = "Array.from(document.querySelectorAll('video')).filter(v=>!v.closest('.movie-poster')&&!v.classList.contains('hidden-video')).sort((a,b)=>b.clientWidth-a.clientWidth)[0]"

def labels(): return [n.get("text", "") for n in d.ui()]

def wait(label, timeout=20):
    end = time.monotonic()+timeout
    while time.monotonic()<end:
        if label in labels(): return
    raise AssertionError("UI did not show: "+label)

def seek_label(label):
    for _ in range(12):
        if label in labels(): return
        d.adb("shell", "input", "swipe", "540", "1500", "540", "1100", "500")
    raise AssertionError("Missing scroll item: "+label)

def passed(name, **extra):
    report=json.loads(REPORT.read_text("utf-8"))
    report["checks"].append({"name":name,"passed":True,**extra})
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print("PASS",name,flush=True)

def backup_state():
    p=d.prefs()
    return {"format":"taiav-backup","version":1,"bookmarks":json.loads(p.get("bookmarks","[]")),
            "rules":{"enabled":p.get("ad_filter_enabled","true")=="true",
            "blocked":p.get("blocked_ad_domains","").split(),"allowed":p.get("allowed_ad_domains","").split()}}

def push_fixture(name, value):
    FIXTURES.mkdir(exist_ok=True)
    path=FIXTURES/name
    path.write_text(value if isinstance(value,str) else json.dumps(value,ensure_ascii=False),encoding="utf-8")
    d.adb("push",str(path),"/sdcard/Download/"+name)

def import_file(name, replace=False, invalid=False):
    d.tap("收藏");wait("收藏与规则备份");d.tap("收藏与规则备份");wait("从文件导入")
    d.tap("从文件导入");wait("下载")
    if "列表视图" in [n.get("content-desc") for n in d.ui()]: d.tap("列表视图")
    seek_label(name)
    # Some vendor DocumentsUI builds ignore injected taps in RecyclerView rows.
    # Focus by keyboard, and enter only after verifying the exact test filename.
    d.adb("shell","input","keyevent","KEYCODE_DPAD_DOWN")
    for _ in range(4):
        roots=[n for n in d.tree().iter('node') if n.get('resource-id','').endswith('/item_root')]
        target=next(i for i,n in enumerate(roots) if any(c.get('text')==name for c in n.iter('node')))
        focused=next((i for i,n in enumerate(roots) if n.get('focused')=='true'),None)
        if focused==target:
            d.adb('shell','input','keyevent','KEYCODE_ENTER');break
        if focused is None: d.adb('shell','input','keyevent','KEYCODE_DPAD_DOWN');continue
        delta=target-focused
        d.adb('shell','input','keyevent',*(['KEYCODE_DPAD_DOWN' if delta>0 else 'KEYCODE_DPAD_UP']*abs(delta)))
    else: raise AssertionError('System picker did not focus the requested test file')
    if invalid: wait("备份读取未成功");d.tap("知道了");return
    wait("合并：保留本机收藏，合并域名规则")
    if replace: d.tap("替换：收藏与规则以备份为准")
    d.tap("导入");wait("网络模式")

def initialize():
    assert d.SERIAL and not d.SERIAL.startswith("emulator-")
    state=backup_state()
    push_fixture(f"taiav-test-restore-{TAG}.json",state)
    apk=d.ROOT/f"dist/TaiAV-{VERSION}-debug.apk"
    report={"version":VERSION,"device":d.SERIAL,"api":d.adb("shell","getprop","ro.build.version.sdk"),
        "model":d.adb("shell","getprop","ro.product.model"),"systemProxy":d.adb("shell","settings","get","global","http_proxy"),
        "apkSha256":hashlib.sha256(apk.read_bytes()).hexdigest(),"initialBackup":state,"checks":[]}
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    home=d.js("({agent:navigator.userAgent,url:location.href,ready:document.readyState,images:document.images.length,loaded:Array.from(document.images).filter(i=>i.complete&&i.naturalWidth>0).length,filter:!!window.__taiavAdFilter,media:!!window.__taiavMedia})")
    assert home["loaded"]>0 and home["filter"] and home["media"]
    passed("homepage images and both scripts load on physical Android",**home)

def ads():
    d.tap("设置");seek_label("广告拦截日志");d.tap("广告拦截日志")
    wait("放行域名并重载")
    rows=labels()
    hosts=[s for s in rows if s and ' ' not in s and '.' in s and '/' not in s]
    assert hosts
    domain=hosts[0]
    d.tap("放行域名并重载");wait("已放行 · 重载后生效")
    assert domain in d.prefs()["allowed_ad_domains"]
    passed("real ad block log and native one-click allow/reload",host=domain)
    d.tap("首页")

def export():
    d.tap("首页")
    current=d.ui()
    if any(n.get("content-desc")=="收藏当前页" for n in current): d.tap("收藏当前页")
    d.tap("收藏");d.tap("收藏与规则备份");wait("导出到文件");d.tap("导出到文件")
    wait("保存")
    names=[n["text"] for n in d.ui() if n.get("class")=="android.widget.EditText"]
    assert len(names)==1
    name=names[0]
    d.tap("保存");wait("我的收藏")
    data=json.loads(d.adb("shell","cat","/sdcard/Download/"+name))
    assert data["bookmarks"] and data["rules"]["enabled"]
    assert set(data)=={"format","version","createdAt","bookmarks","rules"}
    report=json.loads(REPORT.read_text("utf-8"));report["exportedFile"]=name
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    passed("system document picker exports bookmarks and rules to JSON",bookmarks=len(data["bookmarks"]))

def backup():
    name=json.loads(REPORT.read_text("utf-8"))["exportedFile"]
    before=backup_state();import_file(name)
    assert backup_state()==before
    passed("system picker merge deduplicates bookmarks")
    fixture={"format":"taiav-backup","version":1,"bookmarks":[{"title":"Device backup check","url":"https://taiav.com/cn?device-check=031","time":0}],
        "rules":{"enabled":True,"blocked":["ads.device-check.invalid"],"allowed":["allow.device-check.invalid"]}}
    push_fixture("taiav-test-merge-031.json",fixture);push_fixture("taiav-test-invalid-031.json","{invalid")
    import_file("taiav-test-merge-031.json")
    state=backup_state();assert len(state["bookmarks"])==len(before["bookmarks"])+1
    assert 'ads.device-check.invalid' in state['rules']['blocked'] and 'allow.device-check.invalid' in state['rules']['allowed']
    passed("merge imports extra bookmark and custom allow/block domains")
    import_file(name,replace=True);assert backup_state()==before
    passed("replace restores exported bookmarks and rules")
    before=backup_state();import_file("taiav-test-invalid-031.json",invalid=True)
    assert backup_state()==before
    passed("invalid backup reports error and leaves stored data intact")

def media():
    d.tap("首页")
    d.js("(()=>{location.assign('https://taiav.com/cn');return true})()")
    d.browser("wait","--fn","document.readyState!=='loading'&&location.pathname==='/cn'&&document.links.length>10")
    movie=d.js('Array.from(document.links).find(a=>a.pathname.includes("/cn/movie/"))?.href');assert movie
    d.js("(()=>{location.assign("+json.dumps(movie)+");return true})()")
    d.browser("wait","--fn",f"(()=>{{const v={VIDEO};return location.pathname.includes('/movie/')&&v&&v.readyState>=1}})()")
    d.js(f"""(()=>{{const v={VIDEO};const b=document.createElement('button');b.id='__device_play';b.textContent='Playback check';
    b.style.cssText='position:fixed;left:12px;top:12px;z-index:2147483647;width:160px;height:52px';
    b.onclick=e=>{{e.preventDefault();e.stopImmediatePropagation();v.play()}};document.body.appendChild(b);return true}})()""")
    d.browser("click","#__device_play")
    d.browser("wait","--fn",f"(()=>{{const v={VIDEO};return !v.paused&&v.currentTime>12&&v.getVideoPlaybackQuality().totalVideoFrames>0}})()")
    assert "继续观看？" not in labels()
    info=d.js(f"(()=>{{const v={VIDEO};return {{time:v.currentTime,width:v.videoWidth,height:v.videoHeight,frames:v.getVideoPlaybackQuality().totalVideoFrames}}}})()")
    passed("actual video decodes and first progress save does not prompt",**info)
    d.js(f"(()=>{{const v={VIDEO};v.currentTime=60;return true}})()")
    d.browser("wait","--fn",f"(()=>{{const v={VIDEO};return !v.seeking&&v.readyState>=2&&v.currentTime>=60}})()")
    d.js(f"(()=>{{({VIDEO}).pause();return true}})()")
    entry=json.loads(d.prefs()["playback"])[0];assert entry["position"]>=59
    time.sleep(6);after=json.loads(d.prefs()["playback"])[0]
    assert after["position"]==entry["position"] and after["time"]==entry["time"]
    passed("seek/pause saves progress; unchanged polling retains timestamp",position=entry["position"])
    d.adb("shell","am","force-stop","com.taiav.browser");d.adb("shell","am","start","-W","-n","com.taiav.browser/.MainActivity")
    d.adb("forward","tcp:19365","localabstract:webview_devtools_remote_"+d.adb("shell","pidof","com.taiav.browser"));d.browser("connect","19365")
    assert json.loads(d.prefs()["playback"])[0]["position"]==entry["position"]
    resume_tail(entry)

def resume_tail(entry=None):
    entry=entry or json.loads(d.prefs()["playback"])[0]
    d.tap("历史");wait("继续观看");d.tap("继续观看")
    matches=[n.get('text') for n in d.ui() if n.get('text','').startswith(entry['title'][:25])]
    assert matches;d.tap(matches[0])
    wait("继续观看？",30);d.tap("继续观看")
    state=d.js(f"(()=>{{const v={VIDEO};return {{position:v.currentTime,paused:v.paused}}}})()")
    assert abs(state["position"]-entry["position"])<1 and state["paused"]
    passed("restart persists progress; native resume restores position",**state)
    d.js("(()=>{location.reload();return true})()")
    wait("继续观看？",30);d.tap("从头播放")
    assert d.js(f"({VIDEO}).currentTime")<1 and all(e['url']!=entry['url'] for e in json.loads(d.prefs()["playback"]))
    passed("native start-over resets position and clears previous progress")

def fullscreen():
    d.js(f"""(()=>{{const v={VIDEO};const b=document.createElement('button');b.id='__device_fullscreen';b.textContent='Fullscreen check';
    b.style.cssText='position:fixed;left:12px;top:12px;z-index:2147483647;width:160px;height:52px';
    b.onclick=e=>{{e.preventDefault();e.stopImmediatePropagation();v.requestFullscreen();v.play()}};document.body.appendChild(b);return true}})()""")
    d.browser("click","#__device_fullscreen");d.browser("wait","--fn",f"document.fullscreenElement!==null&&({VIDEO}).currentTime>1")
    d.js(f"(()=>{{({VIDEO}).currentTime=60;return true}})()")
    d.browser("wait","--fn",f"!({VIDEO}).seeking&&({VIDEO}).currentTime>=60")
    d.js(f"(()=>{{({VIDEO}).pause();return true}})()")
    d.adb("shell","input","keyevent","KEYCODE_BACK");d.browser("wait","--fn","document.fullscreenElement===null")
    d.js("(()=>{document.getElementById('__device_fullscreen')?.remove();return true})()")
    passed("native fullscreen playback seeking and Android back exit")

def diagnostics():
    d.js("(()=>{window.__deviceMissingImage=new Image();window.__deviceMissingImage.src='https://device-network-check.invalid/check.png';return true})()")
    # Allow the real request callback to observe the test resource before sampling.
    time.sleep(2)
    d.tap("设置");seek_label("检测连接");d.tap("检测连接")
    end=time.monotonic()+80
    while time.monotonic()<end:
        rows=labels();text='\n'.join(rows)
        if '检测完成。' in text:break
    else:raise AssertionError('Diagnostic did not finish')
    assert '页面 · taiav.com' in text and 'HTTP 200' in text
    assert '图片 · ' in text and '视频 · ' in text
    passed("separate homepage image video probes complete",report=text)
    if 'device-network-check.invalid' in text:
        assert 'DNS 解析失败（UnknownHostException）' in text
        passed("wrapped DNS failure displays its real cause")
    for _ in range(5):
        d.adb('shell','input','swipe','540','1830','540','700','350');rows+=labels()
    text='\n'.join(rows)
    assert 'device-network-check.invalid' in text
    passed("actual failure list identifies unreachable test image host")

def cleanup():
    import_file(f'taiav-test-restore-{TAG}.json',replace=True)
    report=json.loads(REPORT.read_text('utf-8'));assert backup_state()==report['initialBackup']
    passed('initial bookmarks and rules restored after test')
    for name in [f'taiav-test-restore-{TAG}.json','taiav-test-merge-031.json','taiav-test-invalid-031.json',report['exportedFile']]:
        assert '/' not in name and name.endswith('.json')
        d.adb('shell','rm','-f','/sdcard/Download/'+name)
    d.tap('首页');d.js("(()=>{location.assign('https://taiav.com/cn');return true})()")

if __name__=='__main__':
    stages={'init':initialize,'ads':ads,'export':export,'backup':backup,'media':media,'resume':resume_tail,'fullscreen':fullscreen,'diagnostics':diagnostics,'cleanup':cleanup}
    stages[sys.argv[1]]()
