package com.taiav.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.taiav.browserdata.PlaybackPolicy
import com.taiav.network.NetworkJournal
import com.taiav.network.ResourceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class FeaturePages(private val activity: Activity, private val store: BrowserStore, private val journal: NetworkJournal) {
    private var reportView: TextView? = null
    private fun page(title: String, description: String): Pair<ScrollView, LinearLayout> = with(activity) {
        val body = column(24)
        body.addView(label(title, 27f, Palette.text, true)); body.space(10)
        body.addView(label(description, 13f, Palette.muted)); body.space(24)
        ScrollView(this).apply { isFillViewport = true; addView(body) } to body
    }
    private fun row(title: String, detail: String): LinearLayout = with(activity) {
        column(16).apply {
            background = shape(Palette.surface, dp(16).toFloat(), Palette.border)
            addView(label(title, 16f, Palette.text, true).apply { maxLines = 3 })
            space(8); addView(label(detail, 12f, Palette.muted).apply { setLineSpacing(dp(3).toFloat(), 1f) })
        }
    }

    fun blocked(refresh: () -> Unit, allow: (String) -> Unit): View = with(activity) {
        val entries = journal.entries(ResourceState.BLOCKED)
        val (scroll, body) = page("广告拦截日志", "本次启动的最近记录；相同域名与来源合并计数。仅在本机内存保存，网址参数不显示。")
        body.addView(actionButton("刷新记录", false, refresh), LinearLayout.LayoutParams(-1, -2)); body.space(10)
        if (entries.isEmpty()) body.addView(label("暂无拦截记录，浏览网页后可回来查看。", 15f))
        else {
            body.addView(actionButton("清空拦截记录", false) { journal.clearBlocked(); refresh() }, LinearLayout.LayoutParams(-1, -2)); body.space(16)
            entries.take(100).forEach { event ->
                val item = row(event.host, "${event.kind.label} · ${event.detail} · ${event.count} 次\n${time(event.time)}\n${event.url}")
                item.space(12)
                val allowed = !store.adRules().blocks(event.url)
                item.addView(actionButton(if (allowed) "已放行 · 重载后生效" else "放行域名并重载", false) { allow(event.host) }, LinearLayout.LayoutParams(-1, -2))
                item.setOnLongClickListener {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("域名", event.host))
                    android.widget.Toast.makeText(this, "域名已复制", android.widget.Toast.LENGTH_SHORT).show(); true
                }
                body.addView(item); body.space(12)
            }
        }
        scroll
    }

    fun continuing(open: (String) -> Unit, refresh: () -> Unit): View = with(activity) {
        val records = store.playback()
        val (scroll, body) = page("继续观看", "自动保存最近 100 个视频的播放位置；打开后可选择继续或从头播放。长按移除单条记录。")
        if (records.isEmpty()) body.addView(label("还没有可继续观看的视频。", 16f))
        else {
            body.addView(actionButton("清空播放记录", false) {
                AlertDialog.Builder(this).setTitle("清空播放记录？").setMessage("收藏和浏览历史会保留。")
                    .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> store.clearPlayback(); refresh() }.show()
            }, LinearLayout.LayoutParams(-1, -2)); body.space(16)
            records.forEach { entry ->
                val item = row(entry.title, "${PlaybackPolicy.time(entry.position)} / ${PlaybackPolicy.time(entry.duration)}\n${time(entry.updatedAt)}")
                item.isFocusable = true
                item.setOnClickListener { open(entry.url) }
                item.setOnLongClickListener {
                    AlertDialog.Builder(this).setTitle("移除这条播放记录？").setMessage(entry.title)
                        .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ -> store.removePlayback(entry.url); refresh() }.show(); true
                }
                body.addView(item); body.space(12)
            }
        }
        scroll
    }

    fun diagnostics(report: String, running: Boolean, probe: () -> Unit, refresh: () -> Unit): View = with(activity) {
        val (scroll, body) = page("网络故障定位", "分别检查首页、最近图片与视频资源。被广告规则拦截的请求单独列入拦截日志。")
        body.addView(actionButton(if (running) "正在检测…" else "开始检测", !running, probe).apply { isEnabled = !running }, LinearLayout.LayoutParams(-1, -2))
        body.space(16)
        reportView = label(report.ifBlank { "先浏览页面或播放视频，再检测对应资源域名。" }, 13f, Palette.text).apply {
            setTextIsSelectable(true); setLineSpacing(dp(4).toFloat(), 1f)
        }
        body.addView(reportView); body.space(24)
        body.addView(label("网页实际失败记录", 18f, Palette.text, true)); body.space(10)
        body.addView(actionButton("刷新失败记录", false, refresh), LinearLayout.LayoutParams(-1, -2)); body.space(12)
        val failures = journal.entries(ResourceState.FAILED)
        if (failures.isEmpty()) body.addView(label("本次启动尚未记录到加载失败。", 13f, Palette.muted))
        else failures.take(60).forEach { event ->
            body.addView(row(event.host, "${event.kind.label} · ${event.detail} · ${event.count} 次\n${time(event.time)}\n${event.url}")); body.space(10)
        }
        scroll
    }

    fun updateReport(report: String) { reportView?.text = report }
    private fun time(value: Long): String = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(value))
}
