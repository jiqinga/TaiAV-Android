@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.res.ColorStateList
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import com.taiav.network.AddressPolicy
import com.taiav.network.AdRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Native library and settings screens; navigation and WebView stay in the activity. */
class BrowserPages(
    private val activity: Activity,
    private val store: BrowserStore,
    private val open: (String) -> Unit,
    private val changed: () -> Unit,
    private val clearCache: () -> Unit,
    private val diagnose: () -> Unit,
    private val filterStatus: () -> String,
    private val filterChanged: () -> Unit,
    private val blockedLog: () -> Unit,
    private val continuing: () -> Unit,
    private val backup: () -> Unit,
    private val offline: () -> Unit,
) {
    private fun page(title: String, description: String): Pair<ScrollView, LinearLayout> = with(activity) {
        val body = column(24)
        body.addView(label(title, 29f, Palette.text, true))
        body.space(9)
        body.addView(label(description, 13f, Palette.muted).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        body.space(28)
        ScrollView(this).apply { isFillViewport = true; addView(body) } to body
    }

    fun entries(history: Boolean, refresh: () -> Unit): View = with(activity) {
        val records = if (history) store.history() else store.bookmarks()
        val (scroll, body) = page(if (history) "浏览历史" else "我的收藏",
            if (history) "最近访问的页面，只保存在这台手机。" else "把喜欢的页面留在这里，随时回来。")
        if (history) {
            body.addView(card("继续观看", "${store.playback().size} 个视频有已保存进度", continuing)); body.space(16)
        } else {
            body.addView(actionButton("收藏与规则备份", false, backup), LinearLayout.LayoutParams(-1, -2)); body.space(16)
        }
        if (history && records.isNotEmpty()) {
            body.addView(actionButton("清空历史", false) {
                AlertDialog.Builder(this).setTitle("清空浏览历史？").setMessage("收藏和登录状态会继续保留。")
                    .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ -> store.clearHistory(); refresh() }.show()
            }, LinearLayout.LayoutParams(-1, -2))
            body.space(16)
        }
        if (records.isEmpty()) {
            body.space(48)
            body.addView(label(if (history) "还没有浏览记录" else "还没有收藏", 19f, Palette.text, true))
            body.space(10)
            body.addView(label(if (history) "打开页面后会自动记录在这里。" else "浏览时点击右上角的书签，即可保存当前页。", 14f, Palette.muted))
            body.space(24)
            body.addView(actionButton("去首页看看") { open(MainActivity.HOME) }, LinearLayout.LayoutParams(-1, -2))
        }
        records.forEach { record ->
            val row = card(record.title, if (history) formatTime(record.visitedAt) else Uri.parse(record.url).path.orEmpty()) { open(record.url) }
            if (!history) row.setOnLongClickListener {
                AlertDialog.Builder(this).setTitle("移除收藏？").setMessage(record.title)
                    .setNegativeButton("取消", null).setPositiveButton("移除") { _, _ -> store.removeBookmark(record.url); refresh() }.show()
                true
            }
            body.addView(row)
            body.space(10)
        }
        scroll
    }

    fun settings(): View = with(activity) {
        val (scroll, body) = page("设置", "按你的网络环境，选择合适的连接方式。")
        body.addView(label("连接", 12f, Palette.accent, true))
        body.space(12)
        body.addView(card("网络模式", if (store.optimized) "备用 IP 直连 · 推荐" else "系统网络") { chooseMode() })
        body.space(10)
        body.addView(card("站点备用地址", store.fixedAddresses.replace('\n', ' ')) {
            editValue("站点备用地址", "每行一个公网 IPv4 地址，优先连接第一项。", store.fixedAddresses, true) { value ->
                store.fixedAddresses = BrowserStore.parseAddresses(value).joinToString("\n")
                changed()
            }
        })
        body.space(10)
        body.addView(card("附加域名 DoH", store.dohEndpoint.ifBlank { "跟随系统 DNS" }) { chooseDoh() })
        body.space(12)
        body.addView(label("备用 IP 只用于本站。DoH 可用于图片与播放器的其他域名；开启后仍需检查实际加载效果。", 12f, Palette.muted).apply { setLineSpacing(dp(4).toFloat(), 1f) })
        body.space(24)
        body.addView(actionButton("检测连接") { diagnose() }, LinearLayout.LayoutParams(-1, -2))
        body.space(28)
        body.addView(label("浏览", 12f, Palette.accent, true))
        body.space(12)
        body.addView(card("默认播放器", when (store.playerEngine) { "mpv" -> "mpv"; "web" -> "网页播放器"; else -> "Media3 ExoPlayer · 推荐" }) {
            AlertDialog.Builder(this).setTitle("默认播放器")
                .setSingleChoiceItems(arrayOf("Media3 ExoPlayer（推荐）", "mpv", "网页播放器"),
                    listOf("media3", "mpv", "web").indexOf(store.playerEngine)) { dialog, index ->
                    store.playerEngine = listOf("media3", "mpv", "web")[index]
                    dialog.dismiss()
                    // Refresh settings without changing the network or reloading the page.
                    (scroll.parent as? android.view.ViewGroup)?.let { parent ->
                        parent.removeView(scroll); parent.addView(settings())
                    }
                }.setNegativeButton("取消", null).show()
        })
        body.space(10)
        body.addView(card("预加载缓冲", "目标 ${store.preloadSeconds} 秒 · 根据网络与码率调整") {
            val values = intArrayOf(15, 30, 60, 120)
            AlertDialog.Builder(this).setTitle("预加载时长")
                .setSingleChoiceItems(values.map { "$it 秒" }.toTypedArray(), values.indexOf(store.preloadSeconds)) { dialog, index ->
                    store.preloadSeconds = values[index]; dialog.dismiss()
                    (scroll.parent as? android.view.ViewGroup)?.let { parent -> parent.removeView(scroll); parent.addView(settings()) }
                }.setNegativeButton("取消", null).show()
        })
        body.space(10)
        body.addView(card("播放缓存容量", "${store.playbackCacheMb} MB · 仅限制自动播放缓存") {
            val values = intArrayOf(64, 256, 512, 1024)
            AlertDialog.Builder(this).setTitle("播放缓存容量")
                .setSingleChoiceItems(values.map { if (it == 1024) "1 GB" else "$it MB" }.toTypedArray(), values.indexOf(store.playbackCacheMb)) { dialog, index ->
                    store.playbackCacheMb = values[index]; dialog.dismiss()
                    (scroll.parent as? android.view.ViewGroup)?.let { parent -> parent.removeView(scroll); parent.addView(settings()) }
                }.setNegativeButton("取消", null).show()
        })
        body.space(10)
        body.addView(card("清理播放缓存", "清理自动缓存；已完成的离线下载会保留") {
            AlertDialog.Builder(this).setTitle("清理播放缓存？").setPositiveButton("清理") { _, _ ->
                Thread({
                    val result = runCatching { PlayerCaches.clearStreaming(this) }
                    runOnUiThread { Toast.makeText(this, if (result.isSuccess) "播放缓存已清理" else "清理未完成，请稍后重试", Toast.LENGTH_SHORT).show() }
                }, "clear-playback-cache").start()
            }.setNegativeButton("取消", null).show()
        })
        body.space(10)
        body.addView(card("下载并发数", "${store.downloadConcurrency} 路 · HLS 分片下载") {
            val values = BrowserStore.DOWNLOAD_CONCURRENCY
            AlertDialog.Builder(this).setTitle("下载并发数")
                .setSingleChoiceItems(values.map { "$it 路" }.toTypedArray(), values.indexOf(store.downloadConcurrency)) { dialog, index ->
                    OfflineMedia.setConcurrency(this, values[index]); dialog.dismiss()
                    (scroll.parent as? android.view.ViewGroup)?.let { parent -> parent.removeView(scroll); parent.addView(settings()) }
                    Toast.makeText(this, "已应用，正在传输的分片会先完成", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("取消", null).show()
        })
        body.space(10)
        body.addView(card("离线下载", "HLS / MP4 · 查看速度、进度、暂停和离线播放", offline))
        body.space(10)
        body.addView(card("广告过滤", filterStatus()) { chooseAdFilter() })
        body.space(10)
        body.addView(card("广告拦截日志", "最近拦截的域名与次数 · 一键放行", blockedLog))
        body.space(10)
        body.addView(card("自定义拦截域名", store.blockedAdDomains.ifBlank { "补充广告域名，包含其子域名" }.replace('\n', ' ')) {
            editValue("自定义拦截域名", "每行一个域名，自动包含子域名。保存后重新加载页面。", store.blockedAdDomains, true) {
                store.blockedAdDomains = AdRules.parseDomains(it).joinToString("\n"); filterChanged()
            }
        })
        body.space(10)
        body.addView(card("放行域名", store.allowedAdDomains.ifBlank { "误拦时放行；优先于拦截规则" }.replace('\n', ' ')) {
            editValue("放行域名", "每行一个域名，自动包含子域名。仅放行你信任的资源域名。", store.allowedAdDomains, true) {
                store.allowedAdDomains = AdRules.parseDomains(it).joinToString("\n"); filterChanged()
            }
        })
        body.space(10)
        body.addView(label("在请求发出前拦截广告域名及本站广告脚本，同时清理广告空白与弹窗。内置规则保存在本机，无需下载订阅。", 12f, Palette.muted))
        body.space(12)
        body.addView(card("继续观看", "${store.playback().size} 个视频有已保存进度", continuing))
        body.space(10)
        body.addView(card("收藏与规则备份", "导出 JSON 文件 · 导入前预览并选择合并或替换", backup))
        body.space(10)
        body.addView(card("清理网页缓存", "保留收藏、历史和登录状态") {
            clearCache()
            Toast.makeText(this, "网页缓存已清理", Toast.LENGTH_SHORT).show()
        })
        body.space(28)
        body.addView(label("TaiAV  ${BuildConfig.VERSION_NAME}", 14f, Palette.text, true))
        body.space(7)
        body.addView(label("收藏与历史仅保存在本机", 12f, Palette.muted))
        body.space(24)
        scroll
    }

    private fun chooseAdFilter() {
        AlertDialog.Builder(activity).setTitle("广告过滤")
            .setSingleChoiceItems(arrayOf("开启（推荐）", "关闭"), if (store.adFilterEnabled) 0 else 1) { dialog, index ->
                store.adFilterEnabled = index == 0
                dialog.dismiss()
                filterChanged()
            }.setNegativeButton("取消", null).show()
    }

    private fun card(title: String, description: String, action: () -> Unit): LinearLayout = with(activity) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(12), dp(18))
            minimumHeight = dp(80)
            background = android.graphics.drawable.RippleDrawable(ColorStateList.valueOf(0x22FFFFFF),
                shape(Palette.surface, dp(18).toFloat(), Palette.border), null)
            isFocusable = true
            setOnClickListener { action() }
        }
        val texts = column().apply {
            addView(label(title, 15f, Palette.text, true).apply { maxLines = 2 })
            space(7)
            addView(label(description, 12f, Palette.muted).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ImageView(this).apply { setImageResource(R.drawable.ic_chevron); imageTintList = ColorStateList.valueOf(Palette.muted) },
            LinearLayout.LayoutParams(dp(24), dp(24)))
        row
    }

    private fun chooseMode() {
        AlertDialog.Builder(activity).setTitle("网络模式")
            .setSingleChoiceItems(arrayOf("备用 IP 直连（推荐）", "系统网络"), if (store.optimized) 0 else 1) { dialog, index ->
                store.optimized = index == 0
                dialog.dismiss()
                changed()
            }.setNegativeButton("取消", null).show()
    }

    private fun chooseDoh() {
        val choices = arrayOf("跟随系统 DNS", "linuxdo", "plus", "自定义 HTTPS 地址")
        val endpoints = arrayOf("", "https://edge.1molchuan.top/linuxdo", "https://edge.1molchuan.top/plus")
        val selected = endpoints.indexOf(store.dohEndpoint).let { if (it < 0) 3 else it }
        AlertDialog.Builder(activity).setTitle("附加域名 DoH")
            .setSingleChoiceItems(choices, selected) { dialog, index ->
                dialog.dismiss()
                if (index == 3) {
                    editValue("自定义 DoH", "填写完整 HTTPS DoH 地址。", store.dohEndpoint, false) { value ->
                        store.dohEndpoint = AddressPolicy.endpoint(value).orEmpty(); changed()
                    }
                } else { store.dohEndpoint = endpoints[index]; changed() }
            }.setNegativeButton("取消", null).show()
    }

    private fun editValue(title: String, hint: String, initial: String, multiline: Boolean, save: (String) -> Unit) = with(activity) {
        val group = column(24)
        group.addView(label(hint, 13f, Palette.muted))
        group.space(12)
        val input = EditText(this).apply {
            setText(initial)
            setTextColor(Palette.text)
            textSize = 15f
            inputType = InputType.TYPE_CLASS_TEXT or (if (multiline) InputType.TYPE_TEXT_FLAG_MULTI_LINE else InputType.TYPE_TEXT_VARIATION_URI)
            if (multiline) minLines = 3 else setSingleLine(true)
            setSelectAllOnFocus(true)
        }
        group.addView(input, LinearLayout.LayoutParams(-1, -2))
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(group)
            .setNegativeButton("取消", null).setPositiveButton("保存", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try { save(input.text.toString().trim()); dialog.dismiss() }
                catch (error: Exception) { input.error = error.message ?: "请检查输入内容" }
            }
        }
        dialog.show()
    }

    private fun formatTime(time: Long): String = SimpleDateFormat("MM 月 dd 日  HH:mm", Locale.CHINA).format(Date(time))
}
