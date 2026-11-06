# 验证记录

GitHub 公开版本从 0.0.1 开始；以下 0.2.0–0.5.2 为首次上传前的本地开发版本与测试记录。设备截图、具体媒体记录及本机测试产物保存在本地被忽略的 `artifacts/`，GitHub Actions 单独生成每次运行的检查报告。

## 0.5.2 播放器布局修正（2026-11-07）

- `assembleDebug`、`lintDebug`、`:network:checkNetwork`：通过。
- 中央播放 / 暂停 72dp，快退 / 快进 56dp 对称排列；底部进度、时间与辅助操作，顶部导航与下载，保留系统栏与刘海安全区域。
- 中央、顶部、底部控制同步显示 / 隐藏；暂停与错误重试时显示控制；退出全屏明确回到竖屏。
- 已在 vivo V1824A（Android 11 / API 30）覆盖安装，保留原有离线下载。Media3 与 mpv 的中央位置、按钮点击区域、播放 / 暂停、双向 10 秒跳转、控制自动隐藏、倍速 / 画质菜单以及横屏 / 返回竖屏全部通过。结果保存在 `artifacts/native-players-0.5.2.json`，并绑定最终安装包 SHA-256；手机实际安装包与本地文件的哈希一致。
- `icons_media3` / `icons_mpv` 新增实际 UI 边界断言：播放按钮接近画面中心，快退快进在同一水平线左右排列，进度与辅助按钮在其下方，网页入口在其上方，横屏全屏状态尺寸正确。按钮实际宽高至少 48dp，且位于窗口内。
- 几何尺寸读取 Activity 实际渲染的 View 层级，并结合 UIAutomator 验证按钮类型、描述和操作。此手机 UIAutomator 使用的可用显示高度为 2267px，实际竖屏窗口为 2340px；前者会截断返回的底部节点边界，因此不将其作为实际按钮尺寸。自动隐藏检查同时验证中央、顶部与底部控件消失。

## 0.5.1 播放器图标控制（2026-11-07）

- `assembleDebug`、`lintDebug`、`:network:checkNetwork`：通过。
- 播放、暂停、快退 / 快进 10 秒、倍速、画质、进入 / 退出全屏采用 Material 矢量图标。两个播放引擎复用同一控制栏，按钮最小点击区域 48dp，支持中文无障碍说明与长按提示，窄屏按两行排列。
- 下载与网页入口移动到顶部，播放 / 暂停按钮即时同步实际播放状态。
- 真机重新连接后覆盖安装成功，保留原有离线下载。Media3 与 mpv 的图标按钮、播放 / 暂停状态切换、双向 10 秒跳转、倍速 / 画质菜单、全屏 / 返回均已通过检查。结果记录在 `artifacts/native-players-0.5.1.json` 并绑定安装包 SHA-256。
- 设备重新连接后可执行 `python tools/native_player_checks.py icons_media3` 与 `icons_mpv`；先在应用打开离线下载列表，保留上一版测试视频。脚本验证按钮类型、暂停 / 播放状态、双向快进、菜单和全屏状态。

## 0.5.0 原生播放器、缓冲与离线缓存（2026-11-07）

- `assembleDebug`、`lintDebug`、`:network:checkNetwork`：通过。Lint 0 错误，保留依赖版本、资源及本地化建议。
- `node tools/media_monitor_checks.cjs`：7 项通过，包含原生媒体源发现、签名查询保留、blob / 分片排除及主播放器选择。
- `node tools/ad_filter_checks.cjs`：6 项通过。
- 新增 JVM 检查覆盖页面切换后旧媒体源隔离、候选源容量、HLS 清晰度、音轨引用、带签名的分片 / 密钥、字节范围初始化分片和离线列表重写。
- 真机：vivo V1824A，Android 11 / API 30，arm64；仅使用已授权在线真机，未启动 Android 模拟器。
- 真机脚本 `tools/native_player_checks.py` 记录 Media3 真实 HTTPS 播放、mpv 切换续播、画质信息菜单、缓冲和缓存设置持久保存、下载暂停 / 继续。完成一份 408 秒、57.8 MB 的 HLS 点播下载，Media3 与 mpv 使用无网络上游的数据源播放通过；mpv 离线快进、倍速与全屏返回通过。清理滚动缓存后完成任务保留，应用强制停止并重启后任务保留，随后两引擎再次通过只读缓存播放。具体结果以 `artifacts/native-players-0.5.0.json` 为准。
- 下载并发设置真机检查：完整展示 1 / 2 / 3 / 4 / 6 / 8 / 12 / 16 / 24 / 32 路，依次切换 32 → 1 → 6 路并验证偏好保存与线程池调整；最终恢复 6 路，应用重启后偏好仍为 6 路。下载列表显示平滑速度与估计剩余时间。
- 物理网络保持连接以维持无线 ADB；离线验证依靠数据源上游置空，未宣称断开 Wi-Fi 实测。
- 当前真实媒体仅提供单一视频轨道，真机画质菜单显示该源的原始分辨率；多档列表解析有 JVM 覆盖，真实多码率自适应切换仍需相应媒体源验证。
- 设备为 Android 11，Android 13+ 通知授权与较新系统前台服务时限未做真机覆盖。MP4、DASH、DRM、跨域 iframe、多语言音轨和字幕组合应按实际媒体另行验证。
- 后台网络清理曾触发 `NetworkOnMainThreadException`，已将 TLS 连接清理移到单独线程。设备播放页采用 1.5 秒进度刷新，以保留 UIAutomator 所需静止窗口。
- 播放缓存与离线下载使用独立 `SimpleCache`：前者 LRU 淘汰，后者不自动淘汰。离线播放的数据源不配置网络上游；mpv 通过随机会话路径的本机 HTTP 读取缓存，URL 不包含上游签名参数。

真机验证命令示例（先连接手机、开启 USB 调试并确认电脑授权）：

```powershell
$env:TAIAV_DEVICE_SERIAL = 'DEVICE_SERIAL'
python tools/native_player_checks.py connect
python tools/native_player_checks.py media3
python tools/native_player_checks.py quality
python tools/native_player_checks.py mpv
python tools/native_player_checks.py download
python tools/native_player_checks.py settings
python tools/native_player_checks.py download_wait
python tools/native_player_checks.py offline_media3
python tools/native_player_checks.py offline_mpv
```

脚本通过当前真实播放页生成测试媒体标题；下载测试会保存一份媒体到应用私有存储。`settings` 验证临时改为 120 秒 / 512 MB 后恢复 60 秒 / 256 MB。`restart_offline` 用于停止并重开应用核对任务持久性；不清除应用数据。测试运行时避免同时运行另一个 UIAutomator 会话。脚本以界面实际边界适配横竖屏；`wm size` 仅报告自然方向，不用于横屏点击坐标。

## 0.3.0 四项功能（2026-11-06）

- `assembleDebug`、`lintDebug`、`:network:checkNetwork`：通过。JVM 共 9 组检查，保留已有 DNS / CONNECT / 广告规则回归。
- 备份：JSON 编解码往返、完整格式校验、文件大小、外站 URL 拒绝、规则验证、合并去重和超限策略。
- 播放策略：跨语言视频身份、初始零进度保留旧记录、NaN / Infinity 拒绝、结束移除、100 条上限和时间格式。
- 日志：4 个线程并发聚合 400 次拦截；检查域名聚合、网址查询参数移除、图片与视频分类、拦截 / 失败分离、200 组事件限制。
- 诊断：使用 JDK keytool 生成临时测试证书，在本机回环 TLS 服务验证真实握手、HEAD 405 后回退 Range GET、签名查询参数与 Cookie 保留；对证书域名不匹配明确拒绝。临时证书仅用于独立 JVM 检查，测试结束删除，APK 仍使用系统可信证书验证。
- `node tools/ad_filter_checks.cjs`：既有 6 项通过。
- `node tools/media_monitor_checks.cjs`：新增 6 项通过。覆盖主播放器选择、忽略预览 / 未播放状态、进度节流、暂停 / seek / ended 保存、恢复范围、播放手势保留、播放器替换与旧事件隔离、资源去重、离开页面保存、顶层站点作用域及重复注入。
- `artifacts/build-verification-0.3.0.json` 绑定当前 APK 与构建检查；两个 DOM JSON 记录均绑定实际脚本 SHA-256，打包时重新校验。
- 未启动 Android 模拟器，未连接实体手机。本版不宣称已通过设备端文件选择器、原生页面交互或实际视频恢复测试。恢复后的播放仍由用户点击播放器触发；跨域 iframe 播放器暂不保存进度。
- 后续手机检查：浏览产生拦截记录后放行并确认重载；播放超过 10 秒、离开并重新打开，选择继续和从头播放；导出后分别尝试合并、替换和无效备份；播放后查看首页 / 图片 / 视频诊断及真实失败域名。

## 0.2.0 广告过滤（2026-11-06）

- `assembleDebug`、`lintDebug`、`:network:checkNetwork`：通过。广告规则与现有 DNS / CONNECT 回归一起运行。
- 域名回归：验证根域名与子域名匹配、大小写和尾点、域名边界、同域广告脚本路径、百分号编码路径、放行优先级、自定义输入验证。
- 媒体保留回归：本站首页、影片页、API、预览、`/acs/ai`、Clappr、UIkit、封面域名、M3U8、TS、MP4、blob URL 均不命中默认规则。
- `node tools/ad_filter_checks.cjs`：6 项通过。以轻量 DOM 运行 APK 中的同一脚本，覆盖早期弹窗抑制、重复注入、横幅及广告 iframe 隐藏、播放器和 Cloudflare iframe 保留、普通站内跳转、动态广告、放行优先级和站点作用域。此测试不启动浏览器或 Android 模拟器，也不发送网页请求。
- 静态站点观察：直接获取首页与一个播放页，确认横幅类名与广告脚本来源；最终分发包不包含抓取的 HTML。
- 用户报告 Android 模拟器影响系统稳定后已停止使用。0.2.0 未完成 Android 设备端广告过滤及播放验证；原生回调、设备 Cookie、真实播放器兼容性应以手机安装测试为准。
- `artifacts/build-verification-0.2.0.json` 绑定本版 APK 的 SHA-256 与构建检查；`artifacts/ad-filter-dom.json` 为 DOM 测试记录。下面的设备记录全部属于 0.1.0。
- 放行仅覆盖域名网络规则；横幅容器清理和弹窗防护独立生效。系统网络模式下 WebView 回调遗漏的重定向或 WebSocket 不经过本机 CONNECT 防护；备用 IP 模式增加连接层过滤。媒体流内嵌广告不由本功能处理。

## 0.1.0 历史验证

日期：2026-11-06。

## 构建与网络层

- `assembleDebug`：通过。
- `lintDebug`：通过，保留依赖升级及 Kotlin 风格建议类警告。
- `:network:checkNetwork`：通过；检查压缩 DNS 记录、请求编号匹配、报文截断、指针循环、问题匹配、目标与端口校验、本地地址过滤、请求头限制、100 KB 二进制双向传输、半关闭及服务关闭。
- `:network:probeSite`：通过；经应用使用的同一 CONNECT 连接层访问本站，HTTP 200，TLS 1.3，并执行域名与证书校验。

## Android 模拟器

环境：Android 15 / API 35，x86_64，Android System WebView 124.0.6367.219。使用现有 AVD 的独立只读实例；本次实例的系统 HTTP 代理设置为 `:0`。应用本机转发的上游使用直接 TCP 连接。视频验证使用 `-gpu swiftshader -feature "-Vulkan,-HardwareDecoder"`，避免宿主机模拟器硬件解码的兼容性问题。

- 真实首页：成功加载，`document.readyState=complete`。
- 首页图片：72 张中 70 张成功；两个未成功项目分别来自本站图片和第三方广告域名。
- 原生界面：8 项检查全部通过，详见 `artifacts/native-smoke.json`：设置页、网络模式切换、无效备用地址拒绝保存、DoH 选择与重置、Android 内的 HTTPS 200 诊断、收藏在进程重启后保留、移除收藏及空状态、历史清理及空状态。
- 最终网页回归：首页 69 张图片中 68 张已加载，5 份样式；数量随站点内容与广告变化。页面主体及图片可用时，第三方广告 iframe 可能仍在加载，因此脚本按主文档和播放器状态判断完成。
- 真实视频：`readyState=4`，1280×720，解码视频帧 23，播放进度 2.886 秒，`paused=false`，媒体源为 MSE / `blob:`。
- 全屏：通过；触发原生全屏与横屏展示。
- 拖动：通过；设置到 60 秒附近后完成 seek，播放器恢复可用。
- 返回：通过；Android 返回动作退出全屏。
- APK：签名验证通过；包名 `com.taiav.browser`，版本 `0.1.0`。分发包校验值见 `dist/SHA256SUMS.txt`。

网页验证脚本：

```powershell
.\tools\webview_smoke.ps1 -Adb PATH_TO_ADB -Serial emulator-5554
```

脚本使用 agent-browser 连接调试版 WebView。播放验证在当前页面临时添加测试按钮，并临时抑制 `window.open`，避免广告弹窗先消耗测试点击的用户激活。这些页面改动只存在于测试会话，不包含在 APK 中。结果见 `artifacts/webview-smoke.json`。

## 测试边界

尚未连接实体安卓手机。当前验证结果适用于所列模拟器与当前网络，不代表所有运营商、Android 系统或视频 CDN。未进行真实账号登录或付费操作。
