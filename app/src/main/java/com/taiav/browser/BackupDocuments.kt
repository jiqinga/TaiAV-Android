package com.taiav.browser

import android.app.AlertDialog
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.taiav.browserdata.BackupCodec
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

/** Uses the system document picker; no broad storage permission. */
class BackupDocuments(
    private val activity: ComponentActivity,
    private val store: BrowserStore,
    private val background: ExecutorService,
    private val isClosed: () -> Boolean,
    private val imported: () -> Unit,
) {
    private val create = activity.registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) export(uri)
    }
    private val open = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) load(uri)
    }

    fun show() {
        AlertDialog.Builder(activity).setTitle("收藏与规则备份")
            .setItems(arrayOf("导出到文件", "从文件导入")) { _, index ->
                if (index == 0) create.launch("TaiAV-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date())}.json")
                else open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }.setNegativeButton("取消", null).show()
    }

    private fun export(uri: Uri) = background.execute {
        val result = runCatching {
            val text = BackupCodec.encode(store.backup())
            activity.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                ?: error("文件尚未打开，请重新选择保存位置")
        }
        activity.runOnUiThread {
            if (!isClosed()) Toast.makeText(activity, if (result.isSuccess) "收藏与规则已导出" else "导出未成功：${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun load(uri: Uri) = background.execute {
        val result = runCatching {
            val bytes = activity.contentResolver.openInputStream(uri)?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= BackupCodec.MAX_BYTES) { "备份文件超过 2 MB" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("文件尚未打开")
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
            BackupCodec.decode(text)
        }
        activity.runOnUiThread {
            if (isClosed()) return@runOnUiThread
            val data = result.getOrNull()
            if (data == null) {
                AlertDialog.Builder(activity).setTitle("备份读取未成功").setMessage(result.exceptionOrNull()?.message ?: "请检查 JSON 备份文件")
                    .setPositiveButton("知道了", null).show()
            } else {
                var merge = true
                AlertDialog.Builder(activity).setTitle("导入 ${data.bookmarks.size} 条收藏与规则")
                    .setView(activity.label("拦截 ${data.blocked.size} 个域名 · 放行 ${data.allowed.size} 个域名\n备份中的广告过滤：${if (data.filterEnabled) "开启" else "关闭"}", 13f, Palette.muted).apply {
                        setPadding(activity.dp(24), activity.dp(12), activity.dp(24), activity.dp(12))
                    })
                    .setSingleChoiceItems(arrayOf("合并：保留本机收藏，合并域名规则", "替换：收藏与规则以备份为准"), 0) { _, which -> merge = which == 0 }
                    .setPositiveButton("导入") { _, _ ->
                        background.execute {
                            val saved = runCatching { store.importBackup(data, merge) }
                            activity.runOnUiThread {
                                if (!isClosed()) {
                                    if (saved.isSuccess) {
                                        imported()
                                        Toast.makeText(activity, "已导入，当前共 ${saved.getOrThrow().bookmarks.size} 条收藏", Toast.LENGTH_LONG).show()
                                    } else AlertDialog.Builder(activity).setTitle("导入未成功").setMessage(saved.exceptionOrNull()?.message)
                                        .setPositiveButton("知道了", null).show()
                                }
                            }
                        }
                    }.setNegativeButton("取消", null).show()
            }
        }
    }
}
