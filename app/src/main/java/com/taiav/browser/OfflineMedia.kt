@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.offline.DefaultDownloaderFactory
import androidx.media3.exoplayer.offline.DefaultDownloadIndex
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.offline.Downloader
import androidx.media3.exoplayer.offline.DownloaderFactory
import androidx.media3.exoplayer.scheduler.Requirements
import com.taiav.browserdata.MediaSources
import com.taiav.browserdata.SiteUrls
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

data class OfflineInfo(val title: String, val page: String, val url: String, val referer: String, val agent: String) {
    fun bytes(): ByteArray = JSONObject().put("title", title.take(160)).put("page", page).put("url", url)
        .put("referer", referer).put("agent", agent).toString().toByteArray(Charsets.UTF_8)
    companion object {
        fun from(request: DownloadRequest): OfflineInfo? = runCatching {
            val value = JSONObject(request.data.toString(Charsets.UTF_8))
            OfflineInfo(value.getString("title"), value.getString("page"), request.uri.toString(), value.getString("referer"), value.getString("agent"))
        }.getOrNull()?.takeIf { SiteUrls.movieKey(it.page) != null && MediaSources.playable(it.url) }
    }
}

/** Media3 persists task state and all HLS manifests, keys, renditions and segments in its download index. */
object OfflineMedia {
    private var instance: DownloadManager? = null
    private val segments = Executors.newFixedThreadPool(6) as ThreadPoolExecutor
    private val main = Handler(Looper.getMainLooper())
    private val loading = mutableSetOf<String>()

    @Synchronized private fun resizeSegments(count: Int) {
        // Increase maximum first; decrease core first, so both pool invariants hold.
        if (count > segments.maximumPoolSize) {
            segments.maximumPoolSize = count
            segments.corePoolSize = count
        } else {
            segments.corePoolSize = count
            segments.maximumPoolSize = count
        }
    }

    fun setConcurrency(context: Context, count: Int) {
        require(count in BrowserStore.DOWNLOAD_CONCURRENCY)
        resizeSegments(count)
        BrowserStore(context).downloadConcurrency = count
    }

    @Synchronized fun manager(context: Context): DownloadManager {
        instance?.let { return it }
        val app = context.applicationContext
        resizeSegments(BrowserStore(app).downloadConcurrency)
        val factory = DownloaderFactory { request ->
            val info = requireNotNull(OfflineInfo.from(request)) { "离线任务信息有误" }
            object : Downloader {
                @Volatile private var delegate: Downloader? = null
                @Volatile private var cancelled = false
                override fun download(listener: Downloader.ProgressListener?) {
                    NativePlaybackNetwork(app, BrowserStore(app), info.url).use { network ->
                        val http = OkHttpDataSource.Factory(network.client).setUserAgent(info.agent)
                            .setDefaultRequestProperties(mapOf("Referer" to info.referer))
                        val data = CacheDataSource.Factory().setCache(PlayerCaches.downloads(app)).setUpstreamDataSourceFactory(http)
                        val downloader = DefaultDownloaderFactory(data, segments).createDownloader(request)
                        delegate = downloader
                        if (cancelled) downloader.cancel()
                        downloader.download(listener)
                    }
                }
                override fun cancel() { cancelled = true; delegate?.cancel() }
                override fun remove() {
                    // Removal uses only the index and downloaded cache, including the persisted manifests.
                    val data = CacheDataSource.Factory().setCache(PlayerCaches.downloads(app)).setUpstreamDataSourceFactory(null)
                    DefaultDownloaderFactory(data, segments).createDownloader(request).remove()
                }
            }
        }
        return DownloadManager(app, DefaultDownloadIndex(PlayerCaches.database(app)), factory).apply {
            maxParallelDownloads = 1
            setRequirements(Requirements(Requirements.NETWORK))
        }.also { instance = it }
    }

    fun entries(context: Context): List<Download> {
        val values = mutableListOf<Download>()
        manager(context).downloadIndex.getDownloads().use { cursor -> while (cursor.moveToNext()) values += cursor.download }
        val active = manager(context).currentDownloads.associateBy { it.request.id }
        return values.map { active[it.request.id] ?: it }.sortedByDescending { it.startTimeMs }
    }

    fun completed(context: Context, id: String): Download? = manager(context).downloadIndex.getDownload(id)?.takeIf { it.state == Download.STATE_COMPLETED }

    fun add(context: Context, info: OfflineInfo, finished: (String?) -> Unit) {
        val key = MessageDigest.getInstance("SHA-256").digest((SiteUrls.movieKey(info.page) + "|" + info.url).toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (!loading.add(key)) { finished("该视频正在准备下载"); return }
        Executors.newSingleThreadExecutor().apply {
            execute {
                val result = runCatching {
                    require(java.io.File(context.filesDir.path).usableSpace > 256 * 1024L * 1024) { "可用存储不足 256 MB，请先清理空间" }
                    require(info.url.substringBefore('?').endsWith(".mp4", true) || info.url.substringBefore('?').endsWith(".m3u8", true)) { "离线下载当前支持 HLS 和 MP4" }
                    if (info.url.substringBefore('?').endsWith(".m3u8", true)) {
                        NativePlaybackNetwork(context.applicationContext, BrowserStore(context), info.url).use { network ->
                            fun manifest(url: String): String = network.client.newCall(okhttp3.Request.Builder().url(url)
                                .header("Referer", info.referer).header("User-Agent", info.agent).build()).execute().use { response ->
                                check(response.isSuccessful) { "媒体列表加载未成功" }
                                val input = requireNotNull(response.body).byteStream()
                                val bytes = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(4096)
                                input.use { while (bytes.size() <= 1024 * 1024) { val count = it.read(buffer); if (count < 0) break; bytes.write(buffer, 0, count) } }
                                require(bytes.size() <= 1024 * 1024) { "媒体列表过大" }
                                bytes.toString("UTF-8")
                            }
                            var text = manifest(info.url)
                            if (text.contains("#EXT-X-STREAM-INF:")) {
                                val relative = text.lineSequence().map(String::trim).firstOrNull { it.isNotBlank() && !it.startsWith('#') }
                                require(!relative.isNullOrBlank()) { "媒体列表为空" }
                                val variant = java.net.URI(info.url).resolve(relative).toString()
                                require(MediaSources.playable(variant)) { "媒体列表格式需要检查" }
                                text = manifest(variant)
                            }
                            require(text.contains("#EXT-X-ENDLIST")) { "该媒体源为直播或尚未完结，暂不加入离线下载" }
                            require(!text.contains("SAMPLE-AES", true)) { "该媒体源请使用在线播放" }
                        }
                    }
                    DownloadRequest.Builder(key, Uri.parse(info.url))
                        .setMimeType(if (info.url.substringBefore('?').endsWith(".m3u8", true)) MimeTypes.APPLICATION_M3U8 else MimeTypes.VIDEO_MP4)
                        .setData(info.bytes()).build()
                }
                main.post {
                    loading.remove(key)
                    result.onSuccess { request ->
                        val started = runCatching { DownloadService.sendAddDownload(context, OfflineDownloadService::class.java, request, true) }
                        finished(if (started.isSuccess) null else "请回到应用内重新加入下载")
                    }
                        .onFailure { finished(it.message ?: "下载准备未成功，请重试") }
                }
                shutdown()
            }
        }
    }

    fun stop(context: Context, id: String, stopped: Boolean) = DownloadService.sendSetStopReason(context, OfflineDownloadService::class.java, id, if (stopped) 1 else Download.STOP_REASON_NONE, true)
    fun remove(context: Context, id: String) = DownloadService.sendRemoveDownload(context, OfflineDownloadService::class.java, id, true)
}
