@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import com.taiav.browserdata.MediaSources
import com.taiav.browserdata.PlaybackEntry
import com.taiav.browserdata.PlaybackPolicy
import com.taiav.browserdata.SiteUrls
import com.taiav.network.NetworkJournal
import java.util.concurrent.Executors
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.taiav.browserdata.HlsQuality

class NativePlayerActivity : ComponentActivity() {
    private lateinit var store: BrowserStore
    private lateinit var root: FrameLayout
    private lateinit var toolbar: LinearLayout
    private lateinit var controlsPanel: LinearLayout
    private lateinit var transportControls: LinearLayout
    private lateinit var videoPanel: FrameLayout
    private lateinit var status: TextView
    private lateinit var timeline: TextView
    private lateinit var toggle: ImageButton
    private lateinit var fullscreenButton: ImageButton
    private lateinit var engineButton: TextView
    private lateinit var seekBar: SeekBar
    private lateinit var audio: AudioManager
    private lateinit var focus: AudioFocusRequest
    private var hasFocus = false
    private var resumed = false
    private var foreground = false
    private var destroyed = false
    private var generation = 0
    private var session: PlaybackEngine? = null
    private var network: NativePlaybackNetwork? = null
    private var cacheServer: CacheMediaServer? = null
    private var offlineId: String? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val handler = Handler(Looper.getMainLooper())
    private var page = ""
    private var url = ""
    private var title = ""
    private var referer = ""
    private var agent = ""
    private var engine = "media3"
    private var position = 0.0
    private var duration = 0.0
    private var ended = false
    private var speed = 1f
    private var qualityKey = "auto"
    private var dragging = false
    private var landscape = false
    private var wantsPlay = true
    private var lastSaved = 0L
    private var errorMessage: String? = null
    private var receiverRegistered = false
    private val hideControls = Runnable {
        if (session?.playing == true && errorMessage == null && !dragging) {
            showControls(false)
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) play(false) }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!foreground) return
            sync()
            val current = session
            updatePlaybackButton()
            timeline.text = "${PlaybackPolicy.time(position)} / ${PlaybackPolicy.time(duration)}"
            if (!dragging) seekBar.progress = if (duration > 0) (position / duration * 1000).toInt().coerceIn(0, 1000) else 0
            val buffered = current?.bufferedPosition?.takeIf { it.isFinite() } ?: position
            seekBar.secondaryProgress = if (duration > 0) (buffered / duration * 1000).toInt().coerceIn(0, 1000) else 0
            status.text = errorMessage ?: when {
                current == null -> "正在准备播放器…"
                ended -> "播放结束"
                current.buffering -> "正在缓冲…"
                current.playing -> "$engineName · ${speed}× · 已缓冲 ${PlaybackPolicy.time((buffered - position).coerceAtLeast(0.0))}"
                else -> "已暂停 · $engineName"
            }
            status.isClickable = errorMessage != null
            status.isFocusable = errorMessage != null
            status.minHeight = dp(if (errorMessage != null) 48 else 32)
            if (System.currentTimeMillis() - lastSaved >= 5000 || ended) save()
            handler.postDelayed(this, 1500)
        }
    }
    private val engineName get() = if (engine == "mpv") "mpv" else "Media3"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = BrowserStore(this)
        page = intent.getStringExtra("page").orEmpty()
        url = intent.getStringExtra("media").orEmpty()
        offlineId = intent.getStringExtra("offline_id")
        if (SiteUrls.movieKey(page) == null || !MediaSources.playable(url)) { finish(); return }
        if (offlineId != null && OfflineMedia.completed(this, offlineId.orEmpty())?.request?.uri?.toString() != url) { finish(); return }
        title = intent.getStringExtra("title").orEmpty().take(160).ifBlank { "TaiAV 视频" }
        referer = intent.getStringExtra("referer")?.takeIf { NetworkJournal.parsed(it) != null } ?: page
        agent = intent.getStringExtra("agent").orEmpty().replace("\r", "").replace("\n", "")
        engine = savedInstanceState?.getString("engine") ?: intent.getStringExtra("engine") ?: "media3"
        position = savedInstanceState?.getDouble("position") ?: intent.getDoubleExtra("position", 0.0)
        speed = savedInstanceState?.getFloat("speed") ?: 1f
        qualityKey = savedInstanceState?.getString("quality") ?: "auto"
        wantsPlay = savedInstanceState?.getBoolean("playing") ?: true
        landscape = savedInstanceState?.getBoolean("landscape") ?: false
        buildUi()
        audio = getSystemService(AudioManager::class.java)
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) { hasFocus = false; play(false) }
            }, handler).build()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (landscape) setFullscreen(false) else finish() }
        })
        setFullscreen(landscape)
    }

    @Suppress("DEPRECATION")
    private fun buildUi() {
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        root.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.mandatorySystemGestures())
                root.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else root.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        videoPanel = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            setOnClickListener {
                val show = toolbar.visibility != View.VISIBLE
                showControls(show)
                if (show) scheduleHide()
            }
        }
        root.addView(videoPanel, FrameLayout.LayoutParams(-1, -1))
        transportControls = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        fun transport(drawable: Int, description: Int, viewId: Int, size: Int = 56, action: () -> Unit): ImageButton =
            playerIcon(drawable, description, action).apply {
                id = viewId
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), shape(0x99000000.toInt(), dp(size / 2).toFloat()), null)
                transportControls.addView(this, LinearLayout.LayoutParams(dp(size), dp(size)).apply {
                    if (transportControls.childCount > 0) marginStart = dp(24)
                })
            }
        transport(R.drawable.ic_replay_10, R.string.player_rewind, R.id.player_rewind) {
            session?.let { it.seek((it.position - 10).coerceAtLeast(0.0)) }; save()
        }
        toggle = transport(R.drawable.ic_play, R.string.player_play, R.id.player_play_pause, 72) { play(!(session?.playing ?: false)) }
            .apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        transport(R.drawable.ic_forward_10, R.string.player_forward, R.id.player_forward) {
            session?.let { it.seek((it.position + 10).coerceAtMost(it.duration.coerceAtLeast(0.0))) }; save()
        }
        root.addView(transportControls, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        toolbar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xDD000000.toInt(), 0x33000000))
        }
        toolbar.addView(iconButton(R.drawable.ic_back, "返回网页") { finish() })
        toolbar.addView(label(title, 15f, Palette.text, true).apply { singleLine() }, LinearLayout.LayoutParams(0, -2, 1f))
        engineButton = actionButton(engineName, false) { chooseEngine() }
        engineButton.setPadding(dp(12), 0, dp(12), 0)
        engineButton.minHeight = dp(48)
        toolbar.addView(engineButton)
        if (offlineId == null) toolbar.addView(playerIcon(R.drawable.ic_download, R.string.offline_downloads) { addOffline() })
        toolbar.addView(playerIcon(R.drawable.ic_player_web, R.string.player_web) { finish() }.apply { id = R.id.player_web })
        root.addView(toolbar, FrameLayout.LayoutParams(-1, dp(56), Gravity.TOP))
        controlsPanel = column().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33000000, 0xE6000000.toInt()))
            setPadding(dp(8), 0, dp(8), dp(8))
        }
        root.addView(controlsPanel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        status = label("正在准备播放器…", 13f, Palette.muted).apply {
            setPadding(dp(8), dp(4), dp(8), dp(4))
            minHeight = dp(32); gravity = Gravity.CENTER_VERTICAL
            contentDescription = "播放状态；点击重试"
            setOnClickListener { if (errorMessage != null) openEngine() }
            isClickable = false
        }
        controlsPanel.addView(status)
        seekBar = SeekBar(this).apply {
            id = R.id.player_progress
            max = 1000; minimumHeight = dp(48); contentDescription = "播放进度"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { dragging = true; handler.removeCallbacks(hideControls) }
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) timeline.text = "${PlaybackPolicy.time(duration * value / 1000)} / ${PlaybackPolicy.time(duration)}"
                }
                override fun onStopTrackingTouch(bar: SeekBar) { session?.seek(duration * bar.progress / 1000); dragging = false; save(); scheduleHide() }
            })
        }
        controlsPanel.addView(seekBar, LinearLayout.LayoutParams(-1, dp(48)))
        val bottomActions = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        timeline = label("00:00 / 00:00", 12f, Palette.text).apply {
            setPadding(dp(8), 0, dp(4), 0); singleLine()
        }
        bottomActions.addView(timeline, LinearLayout.LayoutParams(0, dp(48), 1f))
        bottomActions.addView(playerIcon(R.drawable.ic_speed, R.string.player_speed) { chooseSpeed() }.apply { id = R.id.player_speed })
        bottomActions.addView(playerIcon(R.drawable.ic_quality, R.string.player_quality) { chooseQuality() }.apply { id = R.id.player_quality })
        fullscreenButton = playerIcon(R.drawable.ic_fullscreen, R.string.player_fullscreen) { setFullscreen(!landscape) }
            .apply { id = R.id.player_fullscreen }
        bottomActions.addView(fullscreenButton)
        controlsPanel.addView(bottomActions)
        setContentView(root)
    }

    private fun showControls(visible: Boolean) {
        val visibility = if (visible) View.VISIBLE else View.GONE
        toolbar.visibility = visibility
        transportControls.visibility = visibility
        controlsPanel.visibility = visibility
    }

    private fun playerIcon(drawable: Int, description: Int, action: () -> Unit): ImageButton =
        iconButton(drawable, getString(description)) { scheduleHide(); action() }.apply {
            imageTintList = ColorStateList.valueOf(Palette.text)
            minimumWidth = dp(48); minimumHeight = dp(48)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            tooltipText = getString(description)
        }

    private fun updatePlaybackButton() {
        val pausing = session?.playing == true && !ended
        if (toggle.tag == pausing) return
        toggle.tag = pausing
        toggle.setImageResource(if (pausing) R.drawable.ic_pause else R.drawable.ic_play)
        toggle.contentDescription = getString(if (pausing) R.string.player_pause else R.string.player_play)
        toggle.tooltipText = toggle.contentDescription
    }

    private fun chooseEngine() {
        handler.removeCallbacks(hideControls)
        AlertDialog.Builder(this).setTitle("切换播放器")
            .setSingleChoiceItems(arrayOf("Media3 ExoPlayer", "mpv"), if (engine == "mpv") 1 else 0) { dialog, index ->
                val next = if (index == 1) "mpv" else "media3"
                dialog.dismiss()
                if (next != engine) { save(); engine = next; qualityKey = "auto"; store.playerEngine = next; openEngine() }
            }.setNegativeButton("取消", null).setOnDismissListener { scheduleHide() }.show()
    }

    private fun chooseSpeed() {
        handler.removeCallbacks(hideControls)
        val values = floatArrayOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog.Builder(this).setTitle("播放速度")
            .setSingleChoiceItems(values.map { "${it}×" }.toTypedArray(), values.indexOfFirst { it == speed }) { dialog, index ->
                speed = values[index]; session?.speed(speed); dialog.dismiss()
            }.setNegativeButton("取消", null).setOnDismissListener { scheduleHide() }.show()
    }

    private fun scheduleHide() { handler.removeCallbacks(hideControls); handler.postDelayed(hideControls, 4000) }

    private fun addOffline() {
        handler.removeCallbacks(hideControls)
        AlertDialog.Builder(this).setTitle("加入离线下载？")
            .setMessage("完整保存当前 HLS 或 MP4 媒体源。多画质 HLS 会保存其全部轨道，下载大小可能较大；完成后两种播放器均可离线播放。")
            .setPositiveButton("下载") { _, _ ->
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                OfflineMedia.add(this, OfflineInfo(title, page, url, referer, agent)) { error ->
                    android.widget.Toast.makeText(this, error ?: "已加入下载，可在设置 → 离线下载查看", android.widget.Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("取消", null).setOnDismissListener { scheduleHide() }.show()
    }

    private data class Resources(val network: NativePlaybackNetwork?, val server: CacheMediaServer?, val data: DataSource.Factory, val variants: List<HlsQuality>) {
        fun close() { server?.close(); network?.close() }
    }

    private fun chooseQuality() {
        handler.removeCallbacks(hideControls)
        val choices = session?.qualities.orEmpty()
        if (choices.size <= 1) {
            AlertDialog.Builder(this).setTitle("清晰度")
                .setMessage(choices.firstOrNull()?.label?.let { "$it\n当前媒体源只提供一档画质。" } ?: "正在读取媒体信息，稍后再试。")
                .setPositiveButton("知道了", null).setOnDismissListener { scheduleHide() }.show()
            return
        }
        AlertDialog.Builder(this).setTitle("清晰度")
            .setSingleChoiceItems(choices.map { it.label }.toTypedArray(), choices.indexOfFirst { it.key == qualityKey }.coerceAtLeast(0)) { dialog, index ->
                qualityKey = choices[index].key
                if (engine == "mpv") { save(); openEngine() } else session?.selectQuality(qualityKey)
                dialog.dismiss()
            }.setNegativeButton("取消", null).setOnDismissListener { scheduleHide() }.show()
    }

    private fun openEngine() {
        releaseEngine()
        errorMessage = null
        ended = false
        engineButton.text = engineName
        val token = ++generation
        val targetEngine = engine
        worker.execute {
            val result = runCatching {
                val connection = if (offlineId == null) NativePlaybackNetwork(this, store, url) else null
                var server: CacheMediaServer? = null
                try {
                    val http = connection?.let { OkHttpDataSource.Factory(it.client).setUserAgent(agent)
                        .setDefaultRequestProperties(mapOf("Referer" to referer, "Origin" to UriOrigin(referer))) }
                    val data = PlayerCaches.factory(this, http, offlineId != null)
                    if (targetEngine == "mpv" && !java.net.URI(url).path.orEmpty().endsWith(".mpd", true)) server = CacheMediaServer(data, offlineId != null) { PlayerCaches.contentLength(this, it) }
                    Resources(connection, server, data, server?.qualities(url).orEmpty())
                } catch (failure: Exception) { server?.close(); connection?.close(); throw failure }
            }
            handler.post {
                if (destroyed || !foreground || token != generation) { result.getOrNull()?.close(); return@post }
                result.onSuccess { resources ->
                    val connection = resources.network
                    network = connection
                    cacheServer = resources.server
                    try {
                        val onError: (String) -> Unit = { message -> if (token == generation && !destroyed) {
                            errorMessage = "$message · 点击此处重试"; play(false)
                            showControls(true)
                        } }
                        session = if (engine == "mpv") MpvEngine(this, cacheServer?.url(url) ?: url, referer, agent, connection, position, qualityKey.toLongOrNull() ?: 0, resources.variants, onError)
                            else Media3Engine(this, url, referer, agent, resources.data, position, qualityKey, onError)
                        videoPanel.addView(session?.view, FrameLayout.LayoutParams(-1, -1))
                        session?.speed(speed)
                        if (wantsPlay && resumed) play(true)
                    } catch (failure: Throwable) {
                        if (failure !is Exception && failure !is LinkageError) throw failure
                        session?.release(); session = null; cacheServer?.close(); cacheServer = null; network?.close(); network = null
                        errorMessage = "$engineName 初始化未成功，可切换播放器或点击重试"
                    }
                }.onFailure { errorMessage = "连接准备未成功，可点击重试或返回网页" }
            }
        }
    }

    private fun sync() {
        session?.let { current ->
            if (current.duration.isFinite() && current.duration > 0) {
                position = current.position.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: position
                duration = current.duration; ended = current.ended
            }
        }
    }

    private fun save() {
        sync()
        if (PlaybackPolicy.valid(position, duration)) store.savePlayback(PlaybackEntry(title, page, position, duration), ended)
        lastSaved = System.currentTimeMillis()
        setResult(RESULT_OK, Intent().putExtra("page", page).putExtra("position", position).putExtra("duration", duration).putExtra("ended", ended))
    }

    private fun play(value: Boolean) {
        wantsPlay = value
        if (!value) {
            handler.removeCallbacks(hideControls)
            session?.play(false); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); save()
            showControls(true)
            updatePlaybackButton()
            if (hasFocus) { hasFocus = false; audio.abandonAudioFocusRequest(focus) }
            return
        }
        if (!resumed || session == null || errorMessage != null) return
        hasFocus = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (hasFocus) { session?.play(true); window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); scheduleHide() }
        else { wantsPlay = false; status.text = "音频正被其他应用使用，点击播放重试" }
        updatePlaybackButton()
    }

    private fun releaseEngine() {
        generation++
        if (session != null) save()
        session?.release(); session = null
        videoPanel.removeAllViews()
        cacheServer?.close(); cacheServer = null
        network?.close(); network = null
    }

    @Suppress("DEPRECATION")
    private fun setFullscreen(value: Boolean) {
        landscape = value
        fullscreenButton.setImageResource(if (value) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
        fullscreenButton.contentDescription = getString(if (value) R.string.player_fullscreen_exit else R.string.player_fullscreen)
        fullscreenButton.tooltipText = fullscreenButton.contentDescription
        requestedOrientation = if (value) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (value) hide(WindowInsets.Type.systemBars()) else show(WindowInsets.Type.systemBars())
            }
        } else window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            (if (value) View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION else 0)
        root.requestApplyInsets()
    }

    override fun onStart() {
        super.onStart()
        if (!::root.isInitialized) return
        foreground = true
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)) }
        receiverRegistered = true
        openEngine(); handler.post(tick)
    }
    override fun onResume() { super.onResume(); resumed = true; if (::audio.isInitialized && wantsPlay) play(true) }
    override fun onPause() {
        resumed = false
        if (::audio.isInitialized) play(false)
        super.onPause()
    }
    override fun onStop() {
        foreground = false; handler.removeCallbacks(tick); handler.removeCallbacks(hideControls)
        if (::videoPanel.isInitialized) releaseEngine()
        if (receiverRegistered) { unregisterReceiver(noisy); receiverRegistered = false }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        save()
        outState.putString("engine", engine); outState.putDouble("position", position)
        outState.putFloat("speed", speed); outState.putBoolean("playing", wantsPlay); outState.putBoolean("landscape", landscape)
        outState.putString("quality", qualityKey)
        super.onSaveInstanceState(outState)
    }
    override fun finish() { if (::root.isInitialized) save(); super.finish() }
    override fun onDestroy() {
        destroyed = true; generation++
        if (::videoPanel.isInitialized) releaseEngine()
        worker.shutdown(); super.onDestroy()
    }

    private fun UriOrigin(url: String): String = android.net.Uri.parse(url).let { "${it.scheme}://${it.encodedAuthority}" }
}
