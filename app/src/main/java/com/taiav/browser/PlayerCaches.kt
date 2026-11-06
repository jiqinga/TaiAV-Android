@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.taiav.browser

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.cache.ContentMetadata
import java.io.File

/** Downloaded media is never evicted by the independent rolling playback cache. */
object PlayerCaches {
    private var streaming: SimpleCache? = null
    private var streamingLimit = 0
    private var downloads: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null

    @Synchronized fun database(context: Context): StandaloneDatabaseProvider = database
        ?: StandaloneDatabaseProvider(context.applicationContext).also { database = it }

    @Synchronized fun downloads(context: Context): SimpleCache = downloads ?: SimpleCache(
        File(context.filesDir, "offline-media"), NoOpCacheEvictor(), database(context)
    ).also { downloads = it }

    @Synchronized fun streaming(context: Context): SimpleCache {
        val limit = BrowserStore(context).playbackCacheMb
        if (streaming != null && streamingLimit != limit) { streaming?.release(); streaming = null }
        return streaming ?: SimpleCache(File(context.cacheDir, "playback-media"),
            LeastRecentlyUsedCacheEvictor(limit * 1024L * 1024), database(context)).also { streaming = it; streamingLimit = limit }
    }

    fun factory(context: Context, upstream: DataSource.Factory?, offline: Boolean): CacheDataSource.Factory {
        val online = if (offline) null else CacheDataSource.Factory().setCache(streaming(context))
            .setUpstreamDataSourceFactory(upstream).setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return CacheDataSource.Factory().setCache(downloads(context))
            .setCacheWriteDataSinkFactory(null).setUpstreamDataSourceFactory(online)
    }

    @Synchronized fun streamingBytes(context: Context): Long = streaming(context).cacheSpace

    fun contentLength(context: Context, url: String): Long = ContentMetadata.getContentLength(downloads(context).getContentMetadata(url))
        .takeIf { it >= 0 } ?: ContentMetadata.getContentLength(streaming(context).getContentMetadata(url))

    @Synchronized fun clearStreaming(context: Context) {
        val cache = streaming(context)
        cache.keys.toList().forEach(cache::removeResource)
    }
}
