@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.jdtech.mpv.MPVLib
import com.taiav.browserdata.HlsQuality

data class QualityChoice(val key: String, val label: String)

interface PlaybackEngine {
    val view: View
    val position: Double
    val duration: Double
    val playing: Boolean
    val ended: Boolean
    val buffering: Boolean
    val bufferedPosition: Double
    val qualities: List<QualityChoice>
    fun selectQuality(key: String)
    fun play(value: Boolean)
    fun seek(seconds: Double)
    fun speed(value: Float)
    fun release()
}

class Media3Engine(context: Context, url: String, referer: String, agent: String,
    dataSource: DataSource.Factory, start: Double, quality: String, error: (String) -> Unit) : PlaybackEngine {
    private var pendingQuality = quality.takeIf { it != "auto" }
    private val preload = BrowserStore(context).preloadSeconds * 1000
    private val player = ExoPlayer.Builder(context)
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(minOf(15_000, preload), preload, 1500, 3000).build())
        .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build()
    override val view = PlayerView(context).apply { useController = false; player = this@Media3Engine.player }

    init {
        player.setAudioAttributes(androidx.media3.common.AudioAttributes.DEFAULT, false)
        player.addListener(object : Player.Listener {
            override fun onPlayerError(exception: PlaybackException) { error("Media3 播放未成功（${exception.errorCodeName}）") }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val key = pendingQuality ?: return
                pendingQuality = null
                selectQuality(key)
            }
        })
        player.setMediaItem(MediaItem.fromUri(url), (start * 1000).toLong())
        player.prepare()
    }
    override val position get() = player.currentPosition.coerceAtLeast(0) / 1000.0
    override val duration get() = player.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
    override val playing get() = player.playWhenReady
    override val ended get() = player.playbackState == Player.STATE_ENDED
    override val buffering get() = player.playbackState == Player.STATE_BUFFERING
    override val bufferedPosition get() = player.bufferedPosition.coerceAtLeast(0) / 1000.0
    override val qualities: List<QualityChoice> get() {
        val tracks = player.currentTracks.groups.flatMapIndexed { groupIndex, group ->
            if (group.type != C.TRACK_TYPE_VIDEO) emptyList() else (0 until group.length).filter { group.isTrackSupported(it) }.map { track ->
                val format = group.getTrackFormat(track)
                val label = if (format.height > 0) "${format.height}p" else "原始画质"
                QualityChoice("$groupIndex:$track", label + if (format.bitrate > 0) " · ${format.bitrate / 1000} kbps" else "")
            }
        }.distinctBy { it.label }
        return if (tracks.size <= 1) tracks else listOf(QualityChoice("auto", "自动（根据网络调整）")) + tracks
    }
    override fun selectQuality(key: String) {
        val builder = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (key != "auto") {
            val parts = key.split(':').mapNotNull(String::toIntOrNull)
            val group = parts.firstOrNull()?.let { player.currentTracks.groups.getOrNull(it) }
            val track = parts.getOrNull(1)
            if (group != null && track != null && track in 0 until group.length) builder.setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, track))
        }
        player.trackSelectionParameters = builder.build()
    }
    override fun play(value: Boolean) { if (value && ended) player.seekTo(0); player.playWhenReady = value }
    override fun seek(seconds: Double) { player.seekTo((seconds.coerceAtLeast(0.0) * 1000).toLong()) }
    override fun speed(value: Float) { player.setPlaybackSpeed(value) }
    override fun release() { (view as PlayerView).player = null; player.release() }
}

/** MPVLib is per session; JNI callbacks are marshalled onto the view's main thread. */
class MpvEngine(context: Context, private val url: String, referer: String, agent: String,
    network: NativePlaybackNetwork?, start: Double, bitrate: Long, variants: List<HlsQuality>, private val error: (String) -> Unit) : PlaybackEngine, SurfaceHolder.Callback {
    private val mpv = requireNotNull(MPVLib.create(context)) { "mpv 初始化未成功" }
    override val view = SurfaceView(context)
    private var released = false
    private var loaded = false
    private var attached = false
    private var desiredPlay = false
    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: Double) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Boolean) = Unit
        override fun event(eventId: Int) {
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) view.post {
                if (!released && !ended && duration <= 0) error("mpv 加载未成功，请重试或切换播放器")
            }
        }
    }

    init {
        try {
            fun option(name: String, value: String) { check(mpv.setOptionString(name, value) >= 0) { "mpv 配置未成功：$name" } }
            option("config", "no")
            option("vo", "gpu")
            option("gpu-context", "android")
            option("hwdec", "mediacodec-copy")
            option("ao", "audiotrack")
            option("idle", "yes")
            option("keep-open", "yes")
            option("pause", "yes")
            option("force-window", "no")
            if (url.startsWith("https:") && network != null) option("http-proxy", network.proxyUrl)
            option("user-agent", agent)
            option("referrer", referer)
            option("http-header-fields", "Origin: ${origin(referer)}")
            option("tls-verify", "yes")
            if (network != null) {
                option("tls-ca-file", network.caFile.absolutePath)
                option("cookies", "yes")
                option("cookies-file", network.cookieFile.absolutePath)
            }
            option("network-timeout", "25")
            option("cache", "yes")
            option("demuxer-max-bytes", (64 * 1024 * 1024).toString())
            option("demuxer-readahead-secs", BrowserStore(context).preloadSeconds.toString())
            option("hls-bitrate", if (bitrate > 0) bitrate.toString() else "max")
            option("start", start.toString())
            // Suppress URL-bearing native log messages, including authenticated media URLs.
            option("msg-level", "all=no")
            mpv.init()
            mpv.addObserver(observer)
            view.holder.addCallback(this)
        } catch (failure: Throwable) { mpv.destroy(); throw failure }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (released) return
        mpv.attachSurface(holder.surface); attached = true
        mpv.setPropertyString("vo", "gpu")
        if (!loaded) { loaded = true; mpv.command(arrayOf("loadfile", url)) }
        mpv.setPropertyBoolean("pause", !desiredPlay)
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!released) mpv.setPropertyString("android-surface-size", "${width}x$height")
    }
    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (!released && attached) { mpv.setPropertyString("vo", "null"); mpv.detachSurface(); attached = false }
    }
    override val position get() = if (released) 0.0 else mpv.getPropertyDouble("time-pos") ?: 0.0
    override val duration get() = if (released) 0.0 else mpv.getPropertyDouble("duration") ?: 0.0
    override val playing get() = desiredPlay
    override val ended get() = !released && mpv.getPropertyBoolean("eof-reached") == true
    override val buffering get() = !released && (mpv.getPropertyBoolean("paused-for-cache") == true || duration <= 0)
    override val bufferedPosition get() = if (released) position else (position + (mpv.getPropertyDouble("demuxer-cache-duration") ?: 0.0)).coerceAtMost(duration.coerceAtLeast(position))
    override val qualities = if (variants.size > 1) listOf(QualityChoice("auto", "默认（最高可用）")) + variants.map { QualityChoice(it.bitrate.toString(), it.label) }
        else listOf(QualityChoice("auto", variants.firstOrNull()?.label ?: "原始画质"))
    // mpv's HLS bitrate is applied by recreating the session at its saved position.
    override fun selectQuality(key: String) = Unit
    override fun play(value: Boolean) {
        desiredPlay = value
        if (!released) { if (value && ended) seek(0.0); mpv.setPropertyBoolean("pause", !value) }
    }
    override fun seek(seconds: Double) { if (!released && loaded) mpv.command(arrayOf("seek", seconds.coerceAtLeast(0.0).toString(), "absolute+exact")) }
    override fun speed(value: Float) { if (!released) mpv.setPropertyDouble("speed", value.toDouble()) }
    override fun release() {
        if (released) return
        released = true
        view.holder.removeCallback(this)
        mpv.removeObserver(observer)
        if (attached) mpv.detachSurface()
        mpv.destroy()
    }
}

private fun origin(url: String): String = android.net.Uri.parse(url).let { "${it.scheme}://${it.encodedAuthority}" }
