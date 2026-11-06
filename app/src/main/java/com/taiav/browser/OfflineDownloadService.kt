@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import androidx.media3.exoplayer.offline.DownloadNotificationHelper

class OfflineDownloadService : DownloadService(2401, 1000L, "offline-media", R.string.offline_downloads, 0) {
    override fun getDownloadManager(): DownloadManager = OfflineMedia.manager(this)
    override fun getScheduler(): Scheduler? = null
    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val pending = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("offline", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return DownloadNotificationHelper(this, "offline-media").buildProgressNotification(this,
            R.drawable.ic_download, pending, "离线缓存", downloads, notMetRequirements)
    }
}
