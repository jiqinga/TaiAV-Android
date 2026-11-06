@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.media3.exoplayer.offline.Download
import java.util.Locale

class OfflinePages(private val activity: Activity, private val play: (Download, String) -> Unit) {
    fun page(): View = with(activity) {
        val body = column(20)
        body.addView(label("离线下载", 28f, Palette.text, true)); body.space(10)
        body.addView(label("在播放页点「离线下载」加入任务。下载完成后可用 Media3 或 mpv 播放；下载内容独立保存，手动删除前持续保留。", 13f, Palette.muted))
        body.space(18)
        val rows = column()
        body.addView(rows)
        val handler = Handler(Looper.getMainLooper())
        var shownIds: List<String> = emptyList()
        var labels = mutableMapOf<String, android.widget.TextView>()
        data class TransferSample(val time: Long, val bytes: Long, val bytesPerSecond: Double)
        val transfers = mutableMapOf<String, TransferSample>()
        var dialogVisible = false
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(body) }
        fun options(download: Download) {
            dialogVisible = true
            val choices = if (download.state == Download.STATE_COMPLETED) arrayOf("Media3 离线播放", "mpv 离线播放", "删除下载")
                else if (download.state == Download.STATE_STOPPED || download.state == Download.STATE_FAILED) arrayOf("继续 / 重试下载", "删除下载")
                else arrayOf("暂停下载", "删除下载")
            AlertDialog.Builder(this).setTitle(OfflineInfo.from(download.request)?.title ?: "离线视频")
                .setItems(choices) { _, index ->
                    when (choices[index]) {
                        "Media3 离线播放" -> play(download, "media3")
                        "mpv 离线播放" -> play(download, "mpv")
                        "暂停下载" -> OfflineMedia.stop(this, download.request.id, true)
                        "继续 / 重试下载" -> {
                            androidx.media3.exoplayer.offline.DownloadService.sendAddDownload(this, OfflineDownloadService::class.java, download.request, 0, true)
                        }
                        "删除下载" -> {
                            dialogVisible = true
                            AlertDialog.Builder(this).setTitle("删除离线内容？").setMessage("移除该任务及其已下载的媒体数据。收藏和观看进度会保留。")
                                .setPositiveButton("删除") { _, _ -> OfflineMedia.remove(this, download.request.id) }
                                .setNegativeButton("取消", null).setOnDismissListener { dialogVisible = false }.show()
                        }
                    }
                }.setNegativeButton("取消", null).setOnDismissListener { dialogVisible = false }.show()
        }
        val tick = object : Runnable {
            override fun run() {
                if (!scroll.isAttachedToWindow) return
                if (!dialogVisible) {
                    val downloads = OfflineMedia.entries(this@with)
                    val ids = downloads.map { it.request.id }
                    transfers.keys.retainAll(ids.toSet())
                    if (ids != shownIds || rows.childCount == 0) {
                        shownIds = ids; labels = mutableMapOf(); rows.removeAllViews()
                        if (downloads.isEmpty()) rows.addView(label("还没有离线下载", 18f, Palette.muted))
                        downloads.forEach { download ->
                            val row = column(16).apply {
                                background = shape(Palette.surface, dp(16).toFloat())
                                addView(label(OfflineInfo.from(download.request)?.title ?: "离线视频", 15f, Palette.text, true).apply { maxLines = 2 })
                                space(10)
                                val detail = label("", 13f, Palette.muted)
                                labels[download.request.id] = detail; addView(detail)
                                minimumHeight = dp(80); isFocusable = true
                                setOnClickListener { OfflineMedia.entries(this@with).firstOrNull { it.request.id == download.request.id }?.let(::options) }
                            }
                            rows.addView(row, LinearLayout.LayoutParams(-1, -2)); rows.space(12)
                        }
                    }
                    downloads.forEach { download ->
                        val state = when (download.state) {
                            Download.STATE_COMPLETED -> "已完成 · 可离线播放"
                            Download.STATE_DOWNLOADING -> "下载中"
                            Download.STATE_QUEUED -> "等待下载 / 网络"
                            Download.STATE_STOPPED -> "已暂停"
                            Download.STATE_FAILED -> "下载中断 · 点击重试"
                            Download.STATE_REMOVING -> "正在删除"
                            else -> "准备中"
                        }
                        val percent = download.percentDownloaded.takeIf { it >= 0 }?.let { String.format(Locale.ROOT, " %.1f%%", it) }.orEmpty()
                        val size = String.format(Locale.ROOT, "%.1f MB", download.bytesDownloaded / (1024.0 * 1024))
                        val transfer = if (download.state == Download.STATE_DOWNLOADING) {
                            val now = SystemClock.elapsedRealtime()
                            val previous = transfers[download.request.id]
                            val instant = if (previous != null && now > previous.time)
                                (download.bytesDownloaded - previous.bytes).coerceAtLeast(0) * 1000.0 / (now - previous.time) else 0.0
                            val rate = if (previous != null && previous.bytesPerSecond > 0)
                                previous.bytesPerSecond * 0.65 + instant * 0.35 else instant
                            transfers[download.request.id] = TransferSample(now, download.bytesDownloaded, rate)
                            val speed = if (rate >= 1024 * 1024) String.format(Locale.ROOT, "%.1f MB/s", rate / (1024 * 1024))
                                else String.format(Locale.ROOT, "%.0f KB/s", rate / 1024)
                            val total = if (download.contentLength > 0) download.contentLength.toDouble()
                                else if (download.percentDownloaded > 0) download.bytesDownloaded * 100.0 / download.percentDownloaded else 0.0
                            val remaining = if (rate >= 1024 && total > download.bytesDownloaded)
                                ((total - download.bytesDownloaded) / rate).toLong().coerceAtLeast(1) else 0L
                            val eta = when {
                                remaining >= 3600 -> " · 约 ${remaining / 3600} 小时 ${(remaining % 3600) / 60} 分钟"
                                remaining >= 60 -> " · 约 ${remaining / 60} 分钟"
                                remaining > 0 -> " · 约 $remaining 秒"
                                else -> ""
                            }
                            if (previous == null) " · 正在测速…" else " · $speed$eta"
                        } else {
                            transfers.remove(download.request.id)
                            ""
                        }
                        val text = "$state$percent · $size$transfer"
                        labels[download.request.id]?.let { if (it.text.toString() != text) it.text = text }
                    }
                }
                handler.postDelayed(this, 2000)
            }
        }
        scroll.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) { handler.post(tick) }
            override fun onViewDetachedFromWindow(view: View) { handler.removeCallbacksAndMessages(null) }
        })
        scroll
    }
}
