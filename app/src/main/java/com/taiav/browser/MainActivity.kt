@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.*
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewCompat
import androidx.webkit.ScriptHandler
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import com.taiav.network.DirectRouter
import com.taiav.network.TunnelServer
import com.taiav.network.NetworkJournal
import com.taiav.network.ResourceKind
import com.taiav.network.ResourceState
import com.taiav.network.ResourceTarget
import com.taiav.network.ResourceProbe
import com.taiav.browserdata.SiteUrls
import com.taiav.browserdata.MediaSources
import com.taiav.browserdata.MediaSource
import org.json.JSONObject
import java.util.concurrent.Executor
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n")
class MainActivity : ComponentActivity() {
    private enum class Screen { BROWSER, BOOKMARKS, HISTORY, SETTINGS, BLOCKED, CONTINUE, DIAGNOSTICS, OFFLINE }
    private lateinit var store: BrowserStore
    private lateinit var pages: BrowserPages
    private lateinit var adBlocker: AdBlocker
    private lateinit var pageMonitor: PageMonitor
    private lateinit var featurePages: FeaturePages
    private lateinit var backupDocuments: BackupDocuments
    private val journal = NetworkJournal()
    private var diagnosticsRunning = false
    private var diagnosticReport = ""
    @Volatile private var diagnosticGeneration = 0
    private var activityResumed = false
    private var adScriptHandler: ScriptHandler? = null
    private var adScript = ""
    private lateinit var root: FrameLayout
    private lateinit var shell: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var browserPanel: FrameLayout
    private lateinit var webView: WebView
    private lateinit var subtitle: TextView
    private lateinit var progress: ProgressBar
    private lateinit var bookmarkAction: ImageButton
    private lateinit var playerAction: ImageButton
    private val mediaSources = MediaSources()
    private var nativePlaying = false
    private val nativePlayer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        nativePlaying = false
        val value = result.data
        val sourcePage = value?.getStringExtra("page")
        val position = value?.getDoubleExtra("position", 0.0) ?: 0.0
        val ended = value?.getBooleanExtra("ended", false) ?: false
        if (sourcePage != null && SiteUrls.movieKey(sourcePage) == SiteUrls.movieKey(webView.url.orEmpty())) {
            pageMonitor.returnFromNative(if (ended) 0.0 else position)
        } else pageMonitor.returnFromNative(null)
    }
    private lateinit var errorPanel: LinearLayout
    private lateinit var errorDetail: TextView
    private lateinit var fullscreenPanel: FrameLayout
    private val navItems = mutableMapOf<Screen, LinearLayout>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor()
    private val uiExecutor = Executor { command -> runOnUiThread(command) }
    private var screen = Screen.BROWSER
    private var tunnel: TunnelServer? = null
    private var router: DirectRouter? = null
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var networkReady = false
    private var mainFrameFailed = false
    private var networkGeneration = 0
    private var pendingUrl = HOME
    @Volatile private var destroyed = false
    private val popups = mutableSetOf<WebView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrowserStore(this)
        adBlocker = AdBlocker(applicationContext, store, journal)
        pendingUrl = savedInstanceState?.getString("url")?.takeIf(::isSiteUrl) ?: HOME
        setupWindow()
        buildShell()
        configureWebView()
        featurePages = FeaturePages(this, store, journal)
        backupDocuments = BackupDocuments(this, store, background, { destroyed }) {
            refreshFilter()
            showScreen(Screen.SETTINGS)
        }
        pages = BrowserPages(this, store,
            open = { openBrowser(it) },
            changed = { applyNetwork(); showScreen(Screen.SETTINGS) },
            clearCache = { webView.clearCache(true) },
            diagnose = ::runDiagnostics,
            filterStatus = adBlocker::status,
            filterChanged = {
                refreshFilter()
                showScreen(Screen.SETTINGS)
            },
            blockedLog = { showScreen(Screen.BLOCKED) },
            continuing = { showScreen(Screen.CONTINUE) },
            backup = { backupDocuments.show() },
            offline = { showScreen(Screen.OFFLINE) },
        )
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { navigateBack() }
        })
        applyNetwork()
        if (intent.getBooleanExtra("offline", false)) showScreen(Screen.OFFLINE)
    }

    @Suppress("DEPRECATION")
    private fun setupWindow() {
        window.statusBarColor = Palette.background
        window.navigationBarColor = Palette.background
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    private fun buildShell() {
        root = FrameLayout(this).apply { setBackgroundColor(Palette.background) }
        shell = column()
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (customView != null) root.setPadding(0, 0, 0, 0)
            else if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        val toolbar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        toolbar.addView(iconButton(R.drawable.ic_back, "返回上一页") { navigateBack() })
        val titles = column().apply {
            addView(label("TaiAV", 23f, Palette.text, true))
            space(4)
            subtitle = label("正在准备连接", 11f, Palette.muted).apply { singleLine() }
            addView(subtitle)
        }
        toolbar.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))
        bookmarkAction = iconButton(R.drawable.ic_bookmark, "收藏当前页") { toggleBookmark() }
        playerAction = iconButton(R.drawable.ic_play, "选择播放器") { choosePlayer() }
        toolbar.addView(playerAction)
        toolbar.addView(bookmarkAction)
        toolbar.addView(iconButton(R.drawable.ic_refresh, "刷新页面") { openBrowser(reload = true) })
        shell.addView(toolbar, LinearLayout.LayoutParams(-1, dp(72)))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(Palette.accent)
            progressBackgroundTintList = ColorStateList.valueOf(Palette.surface)
        }
        shell.addView(progress, LinearLayout.LayoutParams(-1, dp(2)))
        content = FrameLayout(this)
        shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        browserPanel = FrameLayout(this)
        content.addView(browserPanel, FrameLayout.LayoutParams(-1, -1))
        webView = newWebView()
        browserPanel.addView(webView, FrameLayout.LayoutParams(-1, -1))
        errorPanel = column(28).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Palette.background)
            visibility = View.GONE
            addView(label("连接需要再试一次", 23f, Palette.text, true))
            space(12)
            errorDetail = label("检查网络，或在设置中更新备用地址。", 14f, Palette.muted).apply { gravity = Gravity.CENTER; setLineSpacing(dp(5).toFloat(), 1f) }
            addView(errorDetail)
            space(28)
            addView(actionButton("重新加载") { applyNetwork() }, LinearLayout.LayoutParams(-1, -2))
            space(12)
            addView(actionButton("打开网络设置", false) { showScreen(Screen.SETTINGS) }, LinearLayout.LayoutParams(-1, -2))
        }
        browserPanel.addView(errorPanel, FrameLayout.LayoutParams(-1, -1))
        shell.addView(View(this).apply { setBackgroundColor(Palette.border) }, LinearLayout.LayoutParams(-1, dp(1)))
        val navigation = LinearLayout(this).apply { gravity = Gravity.CENTER; setBackgroundColor(Palette.surface) }
        listOf(
            Triple(Screen.BROWSER, "首页", R.drawable.ic_home),
            Triple(Screen.BOOKMARKS, "收藏", R.drawable.ic_bookmark),
            Triple(Screen.HISTORY, "历史", R.drawable.ic_history),
            Triple(Screen.SETTINGS, "设置", R.drawable.ic_settings),
        ).forEach { (destination, title, iconRes) ->
            val item = column().apply {
                gravity = Gravity.CENTER
                contentDescription = title
                isFocusable = true
                background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), null, null)
                addView(ImageView(this@MainActivity).apply { setImageResource(iconRes) }, LinearLayout.LayoutParams(dp(23), dp(23)))
                space(5)
                addView(label(title, 11f).apply { gravity = Gravity.CENTER })
                setOnClickListener {
                    if (destination == Screen.BROWSER && screen == Screen.BROWSER) openBrowser(HOME)
                    else showScreen(destination)
                }
            }
            navItems[destination] = item
            navigation.addView(item, LinearLayout.LayoutParams(0, dp(72), 1f))
        }
        shell.addView(navigation)
        fullscreenPanel = FrameLayout(this).apply { setBackgroundColor(Color.BLACK); visibility = View.GONE }
        root.addView(fullscreenPanel, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        updateNavigation()
    }

    private fun newWebView(): WebView = WebView(this).apply {
        setBackgroundColor(Palette.background)
        isFocusable = true
        isFocusableInTouchMode = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(webView, true) }
        configureAdFilter()
        pageMonitor = PageMonitor(this, webView, store, journal, adBlocker::wouldBlock,
            { url -> mediaSources.observe(url, webView.url, mediaSources.token()) }) { !destroyed && !nativePlaying && activityResumed && screen == Screen.BROWSER }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = adBlocker.intercept(request)
            })
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                adBlocker.intercept(request)?.let { return it }
                val token = mediaSources.token()
                mediaSources.observe(request.url.toString(), request.requestHeaders.entries.firstOrNull {
                    it.key.equals("Referer", true)
                }?.value, token)
                journal.record(request.url.toString(), NetworkJournal.classify(request.url.toString(), request.requestHeaders.entries
                    .firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value, request.isForMainFrame), ResourceState.REQUESTED, "网页请求")
                return null
            }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                return interceptNavigation(request.url.toString(), request.hasGesture())
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                mediaSources.navigation(url)
                pageMonitor.navigation(url)
                mainFrameFailed = false
                errorPanel.visibility = View.GONE
                progress.visibility = View.VISIBLE
                subtitle.text = "正在连接 · ${if (store.optimized) "备用 IP 模式" else "系统网络"}"
                updateBookmarkAction()
            }
            override fun onPageFinished(view: WebView, url: String?) {
                injectAdFilter(view, url)
                pageMonitor.committed(url)
                if (mainFrameFailed || url == null || !isSiteUrl(url)) return
                pendingUrl = url
                subtitle.text = "taiav.com · ${if (store.optimized) "备用 IP 模式" else "系统网络"}"
                store.record(PageEntry(cleanTitle(view.title), url))
                CookieManager.getInstance().flush()
                updateBookmarkAction()
            }
            override fun onPageCommitVisible(view: WebView, url: String?) { injectAdFilter(view, url); pageMonitor.committed(url) }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                val category = when (error.errorCode) {
                    WebViewClient.ERROR_HOST_LOOKUP -> "DNS 解析失败"
                    WebViewClient.ERROR_CONNECT -> "连接失败"
                    WebViewClient.ERROR_TIMEOUT -> "加载超时"
                    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE -> "TLS 握手失败"
                    else -> "网页资源加载失败"
                }
                recordFailure(request, "$category（${error.errorCode}）")
                if (request.isForMainFrame) showPageError("页面连接中断（${error.errorCode}）。可以重试或检查备用地址。")
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                recordFailure(request, "HTTP ${response.statusCode}")
                if (request.isForMainFrame) showPageError("站点返回 HTTP ${response.statusCode}。请检查备用地址或切换网络模式。")
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                journal.record(error.url, state = ResourceState.FAILED, detail = "HTTPS 证书校验未通过")
                if (error.url == view.url) showPageError("HTTPS 证书校验未通过，请检查手机时间和连接地址。")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, value: Int) {
                progress.progress = value
                progress.visibility = if (value >= 100 || mainFrameFailed || screen != Screen.BROWSER) View.INVISIBLE else View.VISIBLE
            }
            override fun onShowCustomView(view: View, callback: CustomViewCallback) { enterFullscreen(view, callback) }
            override fun onHideCustomView() { exitFullscreen() }
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                if (adBlocker.blockPopup()) return false
                if (!isUserGesture) return false
                val popup = newWebView()
                popups += popup
                var consumed = false
                fun consume(url: String) {
                    if (consumed || url == "about:blank") return
                    consumed = true
                    // Pop-under scripts sometimes open the current page again. Keep the
                    // active player intact; external popups do not become app navigation.
                    if (isSiteUrl(url) && url != webView.url) openBrowser(url)
                    mainHandler.post { popups -= popup; popup.destroy() }
                }
                popup.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean { consume(request.url.toString()); return true }
                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { url?.let(::consume) }
                }
                (resultMsg.obj as? WebView.WebViewTransport)?.webView = popup
                resultMsg.sendToTarget()
                mainHandler.postDelayed({ if (popups.remove(popup)) popup.destroy() }, 10_000)
                return true
            }
        }
    }

    private fun configureAdFilter() {
        adBlocker.configure(store)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) adScriptHandler?.remove()
        adScriptHandler = null
        adScript = if (adBlocker.enabled) adBlocker.documentScript(store) else ""
        if (adBlocker.enabled && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            adScriptHandler = WebViewCompat.addDocumentStartJavaScript(webView, adScript,
                setOf("https://taiav.com", "https://*.taiav.com"))
        }
    }

    private fun refreshFilter() {
        diagnosticGeneration++
        diagnosticsRunning = false
        pageMonitor.capture()
        configureAdFilter()
        if (networkReady) webView.reload()
    }

    private fun recordFailure(request: WebResourceRequest, detail: String) {
        val url = request.url.toString()
        if (adBlocker.wouldBlock(url)) return
        journal.record(url, NetworkJournal.classify(url, mainFrame = request.isForMainFrame), ResourceState.FAILED, detail)
    }

    private fun injectAdFilter(view: WebView, url: String?) {
        if (adBlocker.enabled && url != null && isSiteUrl(url)) view.evaluateJavascript(adScript, null)
    }

    private fun applyNetwork() {
        diagnosticGeneration++
        diagnosticsRunning = false
        val generation = ++networkGeneration
        networkReady = false
        webView.stopLoading()
        mainFrameFailed = false
        errorPanel.visibility = View.GONE
        progress.visibility = View.VISIBLE
        subtitle.text = "正在准备连接"
        val previous = tunnel
        tunnel = null
        fun ready() {
            previous?.close()
            if (destroyed || generation != networkGeneration) return
            networkReady = true
            webView.loadUrl(pendingUrl)
        }
        val controller = if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) ProxyController.getInstance() else null
        if (controller == null) {
            previous?.close()
            if (store.optimized) showPageError("请更新 Android System WebView，或在设置中选择系统网络模式。") else ready()
            return
        }
        if (!store.optimized) {
            router = null
            controller.clearProxyOverride(uiExecutor) { ready() }
            return
        }
        try {
            val nextRouter = DirectRouter(store.routingConfig())
            val nextTunnel = TunnelServer { host, port ->
                // Covers redirected requests and WebSocket connections that can skip
                // WebViewClient's resource callback. TLS remains end to end.
                if (adBlocker.blocks("https://$host/", "CONNECT 域名过滤")) throw java.io.IOException("广告域名已拦截")
                try { nextRouter.connect(host, port) }
                catch (error: Exception) {
                    journal.record("https://$host/", state = ResourceState.FAILED, detail = "连接失败：${error.javaClass.simpleName}")
                    throw error
                }
            }
            router = nextRouter
            tunnel = nextTunnel
            controller.setProxyOverride(
                ProxyConfig.Builder().addProxyRule("http://127.0.0.1:${nextTunnel.port}").build(), uiExecutor,
            ) { ready() }
        } catch (error: Exception) {
            previous?.close(); tunnel?.close(); tunnel = null
            showPageError(error.message ?: "连接配置需要检查，请打开网络设置。")
        }
    }

    private fun showPageError(message: String) {
        mainFrameFailed = true
        errorDetail.text = message
        errorPanel.visibility = View.VISIBLE
        progress.visibility = View.INVISIBLE
        subtitle.text = "连接未完成"
    }

    private fun openBrowser(url: String? = null, reload: Boolean = false) {
        pageMonitor.capture()
        if (url != null || reload) pageMonitor.navigation(url ?: webView.url)
        showScreen(Screen.BROWSER)
        if (url != null && isSiteUrl(url)) pendingUrl = url
        if (!networkReady) { applyNetwork(); return }
        if (url != null) webView.loadUrl(pendingUrl)
        else if (reload) { mainFrameFailed = false; errorPanel.visibility = View.GONE; webView.reload() }
    }

    private fun showScreen(destination: Screen) {
        screen = destination
        for (index in content.childCount - 1 downTo 0) if (content.getChildAt(index) !== browserPanel) content.removeViewAt(index)
        browserPanel.visibility = if (destination == Screen.BROWSER) View.VISIBLE else View.GONE
        if (destination != Screen.BROWSER) progress.visibility = View.INVISIBLE
        val page = when (destination) {
            Screen.BOOKMARKS -> pages.entries(false) { showScreen(Screen.BOOKMARKS) }
            Screen.HISTORY -> pages.entries(true) { showScreen(Screen.HISTORY) }
            Screen.SETTINGS -> pages.settings()
            Screen.BLOCKED -> featurePages.blocked({ showScreen(Screen.BLOCKED) }) { host ->
                runCatching { store.allowDomain(host); refreshFilter() }
                    .onSuccess { showScreen(Screen.BLOCKED); Toast.makeText(this, "已放行 $host，页面正在重载", Toast.LENGTH_SHORT).show() }
                    .onFailure { Toast.makeText(this, it.message, Toast.LENGTH_LONG).show() }
            }
            Screen.CONTINUE -> featurePages.continuing({ openBrowser(it) }, { showScreen(Screen.CONTINUE) })
            Screen.DIAGNOSTICS -> featurePages.diagnostics(diagnosticReport, diagnosticsRunning, ::startDiagnostics) { showScreen(Screen.DIAGNOSTICS) }
            Screen.OFFLINE -> OfflinePages(this, ::playOffline).page()
            Screen.BROWSER -> null
        }
        page?.let { content.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        updateNavigation()
        updateBookmarkAction()
        if (destination == Screen.BROWSER) pageMonitor.maybePrompt()
    }

    private fun updateNavigation() {
        val selected = when (screen) { Screen.BLOCKED, Screen.DIAGNOSTICS, Screen.OFFLINE -> Screen.SETTINGS; Screen.CONTINUE -> Screen.HISTORY; else -> screen }
        navItems.forEach { (destination, item) ->
            item.isSelected = destination == selected
            val color = if (destination == selected) Palette.accent else Palette.muted
            (item.getChildAt(0) as ImageView).imageTintList = ColorStateList.valueOf(color)
            (item.getChildAt(2) as TextView).setTextColor(color)
        }
        bookmarkAction.visibility = if (screen == Screen.BROWSER) View.VISIBLE else View.GONE
        playerAction.visibility = if (screen == Screen.BROWSER) View.VISIBLE else View.GONE
    }

    private fun choosePlayer() {
        if (nativePlaying) return
        if (SiteUrls.movieKey(webView.url.orEmpty()) == null) {
            Toast.makeText(this, "先打开一个视频页面，再选择播放器", Toast.LENGTH_SHORT).show(); return
        }
        var selected = listOf("media3", "mpv", "web").indexOf(store.playerEngine).coerceAtLeast(0)
        AlertDialog.Builder(this).setTitle("选择播放器")
            .setSingleChoiceItems(arrayOf("Media3 ExoPlayer（推荐）", "mpv", "网页播放器"), selected) { _, index -> selected = index }
            .setPositiveButton("播放") { _, _ ->
                val engine = listOf("media3", "mpv", "web")[selected]
                store.playerEngine = engine
                if (engine != "web") discoverNativeSources(engine)
            }.setNegativeButton("取消", null).show()
    }

    private fun playOffline(download: androidx.media3.exoplayer.offline.Download, engine: String) {
        val info = OfflineInfo.from(download.request) ?: return
        nativePlaying = true; pageMonitor.suspendForNative()
        webView.evaluateJavascript("window.__taiavMedia&&window.__taiavMedia.pause()", null)
        nativePlayer.launch(Intent(this, NativePlayerActivity::class.java)
            .putExtra("offline_id", download.request.id).putExtra("media", info.url)
            .putExtra("page", info.page).putExtra("referer", info.referer).putExtra("agent", info.agent)
            .putExtra("title", info.title).putExtra("engine", engine)
            .putExtra("position", store.playbackFor(info.page)?.position ?: 0.0))
    }

    private fun discoverNativeSources(engine: String) {
        val page = webView.url ?: return
        val token = mediaSources.token()
        webView.evaluateJavascript("window.__taiavMedia?window.__taiavMedia.sources():[]") { raw ->
            if (destroyed || token != mediaSources.token()) return@evaluateJavascript
            runCatching {
                val items = org.json.JSONArray(raw)
                for (index in 0 until items.length().coerceAtMost(100)) {
                    val value = items.optString(index)
                    if (!adBlocker.wouldBlock(value)) mediaSources.observe(value, page, token)
                }
            }
            val sources = mediaSources.snapshot().filterNot { adBlocker.wouldBlock(it.url) }
            if (sources.isEmpty()) {
                AlertDialog.Builder(this).setTitle("尚未发现可播放的媒体源")
                    .setMessage("请先点击网页播放器，让网站加载视频，再点顶部播放按钮。原生播放支持已识别的 HLS、DASH 和视频文件地址；网页播放器可继续使用。")
                    .setPositiveButton("重新识别") { _, _ -> discoverNativeSources(engine) }
                    .setNegativeButton("网页播放", null).show()
            } else if (sources.size == 1) launchNative(engine, sources.first(), page, token)
            else {
                val names = sources.mapIndexed { index, source ->
                    val uri = Uri.parse(source.url)
                    "${index + 1}. ${uri.host} · ${uri.lastPathSegment.orEmpty().take(64)}"
                }.toTypedArray()
                AlertDialog.Builder(this).setTitle("选择媒体源")
                    .setItems(names) { _, index -> launchNative(engine, sources[index], page, token) }
                    .setNegativeButton("取消", null).show()
            }
        }
    }

    private fun launchNative(engine: String, source: MediaSource, page: String, token: Long) {
        if (nativePlaying || token != mediaSources.token()) return
        webView.evaluateJavascript("window.__taiavMedia?window.__taiavMedia.snapshot():null") { raw ->
            if (destroyed || nativePlaying || token != mediaSources.token()) return@evaluateJavascript
            val state = runCatching { JSONObject(raw) }.getOrNull()
            val current = state?.optDouble("position", 0.0)?.takeIf { it.isFinite() && it > 0 } ?: 0.0
            val position = if (current > 0) current else store.playbackFor(page)?.position ?: 0.0
            nativePlaying = true
            pageMonitor.suspendForNative()
            val intent = Intent(this, NativePlayerActivity::class.java)
                .putExtra("media", source.url).putExtra("referer", source.referer).putExtra("page", page)
                .putExtra("title", cleanTitle(webView.title)).putExtra("agent", webView.settings.userAgentString)
                .putExtra("position", position).putExtra("engine", engine)
            // Finish pausing the page before starting the second decoder/audio output.
            webView.evaluateJavascript("window.__taiavMedia&&window.__taiavMedia.pause()") {
                if (!destroyed && token == mediaSources.token()) nativePlayer.launch(intent)
                else { nativePlaying = false; pageMonitor.returnFromNative(null) }
            }
        }
    }

    private fun runDiagnostics() {
        showScreen(Screen.DIAGNOSTICS)
        startDiagnostics()
    }

    private fun startDiagnostics() {
        if (diagnosticsRunning) return
        val optimized = store.optimized
        val route = router
        if (optimized && (!networkReady || route == null)) {
            diagnosticReport = "连接尚在初始化，请稍后再检测。"
            showScreen(Screen.DIAGNOSTICS); return
        }
        diagnosticsRunning = true
        val generation = ++diagnosticGeneration
        val targets = listOf(ResourceTarget(HOME, ResourceKind.PAGE)) + journal.samples()
        val cookies = targets.associate { it.url to CookieManager.getInstance().getCookie(it.url) }
        val agent = webView.settings.userAgentString
        val rules = store.adRules()
        val filtering = store.adFilterEnabled
        val report = StringBuilder("${if (optimized) "备用 IP / 当前 DoH" else "系统 DNS 直连"}\nWebView ${WebView.getCurrentWebViewPackage()?.versionName ?: "未知"}\n\n")
        if (targets.none { it.kind == ResourceKind.IMAGE }) report.append("图片：尚未采集，请先打开首页图片。\n")
        if (targets.none { it.kind == ResourceKind.VIDEO }) report.append("视频：尚未采集，请先进入播放页并尝试播放。\n")
        diagnosticReport = report.toString() + "\n正在检测…"
        showScreen(Screen.DIAGNOSTICS)
        background.execute {
            targets.forEach { target ->
                if (destroyed || generation != diagnosticGeneration) return@execute
                val host = NetworkJournal.parsed(target.url)?.host.orEmpty()
                report.append("\n${target.kind.label} · $host\n")
                if (filtering && rules.blocks(target.url)) report.append("已被广告规则过滤，可在拦截日志放行。\n")
                else {
                    val result = runCatching { ResourceProbe.check(target.url,
                        if (optimized) { hostName, port -> requireNotNull(route).connect(hostName, port) } else ResourceProbe::systemConnect,
                        cookies[target.url], userAgent = agent) }
                    result.onSuccess { response ->
                        report.append("HTTP ${response.status} · ${response.tls} · ${response.elapsedMs} ms\n地址 ${response.address}\n")
                        if (response.status >= 400) report.append("服务器返回错误；可结合下方网页实际失败记录排查。\n")
                        else if (response.status in 300..399) report.append("服务器返回跳转，本次检测未跟随。\n")
                    }.onFailure { error ->
                        report.append("${ResourceProbe.failureDescription(error)}\n可检查该域名的解析、连接和 TLS，或查看下方网页失败记录。\n")
                    }
                }
                val partial = report.toString()
                runOnUiThread {
                    if (!destroyed && generation == diagnosticGeneration) {
                        diagnosticReport = partial
                        if (screen == Screen.DIAGNOSTICS) featurePages.updateReport(partial)
                    }
                }
            }
            val final = report.append("\n检测完成。探测仅请求响应头；不支持 HEAD 的服务器使用 1 字节范围 GET。需要 Cookie、Referer 或跳转的资源，应结合网页实际记录判断。\n").toString()
            runOnUiThread {
                if (!destroyed && generation == diagnosticGeneration) {
                    diagnosticsRunning = false; diagnosticReport = final
                    if (screen == Screen.DIAGNOSTICS) showScreen(Screen.DIAGNOSTICS)
                }
            }
        }
    }

    private fun toggleBookmark() {
        val url = webView.url ?: return
        if (!isSiteUrl(url) || mainFrameFailed) return
        val saved = runCatching { store.toggleBookmark(PageEntry(cleanTitle(webView.title), url)) }.getOrElse {
            Toast.makeText(this, it.message, Toast.LENGTH_LONG).show(); return
        }
        updateBookmarkAction()
        Toast.makeText(this, if (saved) "已加入收藏" else "已移除收藏", Toast.LENGTH_SHORT).show()
    }

    private fun updateBookmarkAction() {
        val saved = webView.url?.let(store::bookmarked) == true
        bookmarkAction.imageTintList = ColorStateList.valueOf(if (saved) Palette.accent else Palette.muted)
        bookmarkAction.contentDescription = if (saved) "移除当前页收藏" else "收藏当前页"
    }

    private fun interceptNavigation(url: String, userGesture: Boolean): Boolean {
        if (adBlocker.blocks(url)) return true
        if (isSiteUrl(url)) return false
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return true
        if (userGesture && uri.scheme in listOf("https", "http")) {
            AlertDialog.Builder(this).setTitle("打开外部链接？").setMessage(uri.host.orEmpty())
                .setNegativeButton("留在这里", null).setPositiveButton("使用浏览器") { _, _ ->
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)) }
                }.show()
        }
        return true
    }

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        previousOrientation = requestedOrientation
        customView = view
        customViewCallback = callback
        fullscreenPanel.addView(view, FrameLayout.LayoutParams(-1, -1))
        fullscreenPanel.visibility = View.VISIBLE
        shell.visibility = View.GONE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
        root.setPadding(0, 0, 0, 0)
        root.requestApplyInsets()
    }

    private fun exitFullscreen() {
        if (customView == null) return
        fullscreenPanel.removeAllViews()
        fullscreenPanel.visibility = View.GONE
        customView = null
        shell.visibility = View.VISIBLE
        requestedOrientation = previousOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 30) window.insetsController?.show(WindowInsets.Type.systemBars()) else setupWindow()
        root.requestApplyInsets()
        val callback = customViewCallback
        customViewCallback = null
        callback?.onCustomViewHidden()
    }

    private fun navigateBack() {
        when {
            customView != null -> exitFullscreen()
            screen == Screen.BLOCKED || screen == Screen.DIAGNOSTICS || screen == Screen.OFFLINE -> showScreen(Screen.SETTINGS)
            screen == Screen.CONTINUE -> showScreen(Screen.HISTORY)
            screen != Screen.BROWSER -> showScreen(Screen.BROWSER)
            webView.canGoBack() -> webView.goBack()
            else -> finish()
        }
    }
    override fun onResume() { super.onResume(); activityResumed = true; if (::webView.isInitialized) webView.onResume(); if (::pageMonitor.isInitialized) { pageMonitor.start(); pageMonitor.maybePrompt() } }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.getBooleanExtra("offline", false) && ::pages.isInitialized) showScreen(Screen.OFFLINE)
    }
    override fun onPause() { activityResumed = false; if (::pageMonitor.isInitialized) pageMonitor.pause(); if (::webView.isInitialized) webView.onPause(); super.onPause() }
    override fun onStop() {
        webView.evaluateJavascript("document.querySelectorAll('video,audio').forEach(function(m){m.pause();})", null)
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("url", webView.url?.takeIf(::isSiteUrl) ?: pendingUrl)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        destroyed = true
        networkGeneration++
        diagnosticGeneration++
        pageMonitor.close()
        mainHandler.removeCallbacksAndMessages(null)
        exitFullscreen()
        popups.forEach { it.destroy() }
        popups.clear()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) adScriptHandler?.remove()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(null)
        }
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            runCatching { ProxyController.getInstance().clearProxyOverride(Executor { it.run() }) {} }
        }
        tunnel?.close()
        background.shutdownNow()
        super.onDestroy()
    }
    private fun cleanTitle(title: String?): String = title?.replace(Regex("\\s+"), " ")?.trim()?.take(160)?.takeIf { it.isNotBlank() } ?: "TaiAV 页面"

    companion object {
        const val HOME = "https://taiav.com/cn"
        private fun isSiteUrl(value: String): Boolean {
            return SiteUrls.isSite(value)
        }
    }
}
