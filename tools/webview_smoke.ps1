param(
    [string]$Adb = 'adb',
    [Parameter(Mandatory)][string]$Serial,
    [int]$DebugPort = 19365
)

$ErrorActionPreference = 'Stop'
if ($Serial.StartsWith('emulator-')) { throw '请选择已连接的 Android 真机。' }
$projectDirectory = Split-Path -Parent $PSScriptRoot
$sessionName = "taiav-probe-$PID"
$result = [ordered]@{ success = $false; systemProxy = ''; homepage = $null; player = $null; fullscreen = $false; seek = $false; back = $false }

function Invoke-PageJs([string]$javascript) {
    $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($javascript))
    $raw = & agent-browser --session $sessionName --json eval -b $encoded | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'WebView 调试连接中断。' }
    $response = $raw | ConvertFrom-Json
    if (-not $response.success) { throw $response.error }
    return $response.data.result
}

function Wait-Page([string]$predicate) {
    & agent-browser --session $sessionName wait --fn $predicate | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '网页验证条件尚未满足。' }
}

try {
    $result.systemProxy = (& $Adb -s $Serial shell settings get global http_proxy | Out-String).Trim()
    if ($result.systemProxy -notin @(':0', 'null', '')) { throw '请在独立模拟器中关闭系统代理后再验证直连。' }
    $appProcessId = (& $Adb -s $Serial shell pidof com.taiav.browser | Out-String).Trim()
    if (-not $appProcessId) { throw '请先启动 TaiAV 调试版应用。' }
    & $Adb -s $Serial forward "tcp:$DebugPort" "localabstract:webview_devtools_remote_$appProcessId" | Out-Null
    # On Windows, a new daemon can inherit a PowerShell pipeline's stdout handle.
    # Start it without an output pipeline; later commands reuse that daemon.
    & agent-browser --session $sessionName connect $DebugPort
    if ($LASTEXITCODE -ne 0) { throw '连接 WebView 调试端口未成功。' }

    # Assert the main document and player rather than waiting for every ad iframe's
    # window.load event. Some third-party frames never finish loading.
    Invoke-PageJs '(()=>{location.assign("https://taiav.com/cn");return true})()' | Out-Null
    Wait-Page 'location.pathname==="/cn"&&document.readyState!=="loading"'
    Wait-Page 'Array.from(document.images).some(i=>i.complete&&i.naturalWidth>0)'
    $result.homepage = Invoke-PageJs @'
({ready:document.readyState,images:document.images.length,
loadedImages:Array.from(document.images).filter(i=>i.complete&&i.naturalWidth>0).length,
styles:document.styleSheets.length})
'@
    if ($result.homepage.loadedImages -le 0) { throw '首页图片尚未加载。' }
    Write-Output 'PASS real homepage, styles, and images'

    $movieUrl = Invoke-PageJs 'document.querySelector("a[href*=\"/cn/movie/\"]")?.href'
    if (-not $movieUrl) { throw '首页没有找到播放页入口。' }
    $movieAsJson = ConvertTo-Json -InputObject $movieUrl -Compress
    Invoke-PageJs "(()=>{location.assign($movieAsJson);return true})()" | Out-Null
    Wait-Page "location.href===$movieAsJson&&document.readyState!=='loading'"
    Wait-Page '(()=>{const v=Array.from(document.querySelectorAll("video")).sort((a,b)=>b.clientWidth-a.clientWidth)[0];return v&&v.readyState>=2})()'

    $result.player = Invoke-PageJs @'
(()=>{
if (!window.__taiavAdFilter) throw new Error('请先在设置中开启广告过滤');
const v=Array.from(document.querySelectorAll('video')).sort((a,b)=>b.clientWidth-a.clientWidth)[0];
window.__taiavProbe={video:v,before:v.currentTime,error:null};
const b=document.createElement('button');b.id='__taiav_test_fullscreen';b.textContent='播放器验证';
b.style.cssText='position:fixed;left:12px;top:12px;z-index:2147483647;width:140px;height:52px;background:#ff755d;color:#101114;border:0';
b.onclick=e=>{e.preventDefault();e.stopImmediatePropagation();
v.play().catch(x=>window.__taiavProbe.error=x.message);
v.requestFullscreen().catch(x=>window.__taiavProbe.error=x.message)};
document.body.appendChild(b);
return {ready:v.readyState,duration:v.duration,sourceProtocol:(v.currentSrc||v.src).split(':')[0]};
})()
'@
    & agent-browser --session $sessionName click '#__taiav_test_fullscreen' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw '播放器交互未完成。' }
    Wait-Page 'document.fullscreenElement!==null&&window.__taiavProbe&&!window.__taiavProbe.video.paused&&window.__taiavProbe.video.currentTime>window.__taiavProbe.before+0.3'
    $result.fullscreen = $true
    $result.playback = Invoke-PageJs '({time:window.__taiavProbe.video.currentTime,paused:window.__taiavProbe.video.paused,fullscreen:document.fullscreenElement.tagName,error:window.__taiavProbe.error,frames:window.__taiavProbe.video.getVideoPlaybackQuality().totalVideoFrames,width:window.__taiavProbe.video.videoWidth,height:window.__taiavProbe.video.videoHeight})'
    $result.probePopupSuppression = $false
    $result.appAdFilterEnabled = $true
    Write-Output 'PASS actual video playback and native fullscreen'

    Invoke-PageJs '(()=>{const p=window.__taiavProbe;p.seekTarget=Math.min(60,p.video.duration-2);p.video.currentTime=p.seekTarget;return true})()' | Out-Null
    Wait-Page 'window.__taiavProbe&&!window.__taiavProbe.video.seeking&&window.__taiavProbe.video.readyState>=2&&window.__taiavProbe.video.currentTime>=window.__taiavProbe.seekTarget-0.1'
    $result.seek = $true
    Invoke-PageJs 'window.__taiavProbe.video.pause()' | Out-Null
    Write-Output 'PASS media seeking'
    & $Adb -s $Serial shell input keyevent KEYCODE_BACK | Out-Null
    Wait-Page 'document.fullscreenElement===null'
    $result.back = $true
    Invoke-PageJs 'document.getElementById("__taiav_test_fullscreen")?.remove()' | Out-Null
    $result.success = $true
    Write-Output 'PASS native back exits fullscreen'
} catch {
    $result.error = $_.Exception.Message
    throw
} finally {
    $artifactDirectory = Join-Path $projectDirectory 'artifacts'
    New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
    $result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $artifactDirectory 'webview-smoke.json') -Encoding utf8
}
