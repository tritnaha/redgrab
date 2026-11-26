package app.redgrab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.webkit.WebSettings
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RedGrabApp : Application(), SingletonImageLoader.Factory {

    /** Cache for clips streamed before they're saved (so replays don't re-download). 300 MB, oldest evicted. */
    val mediaCache by lazy {
        androidx.media3.datasource.cache.SimpleCache(
            cacheDir.resolve("stream"),
            androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor(300L * 1024 * 1024),
            androidx.media3.database.StandaloneDatabaseProvider(this),
        )
    }

    lateinit var library: Library
        private set

    /** The browser's own user agent, for when no RedGifs sign-in has stored one. */
    private val defaultUserAgent by lazy { runCatching { WebSettings.getDefaultUserAgent(this) }.getOrDefault("Mozilla/5.0") }

    /** User agent for requests: the one RedGifs sign-in used when there is one. */
    val userAgent: String get() = library.prefs.userAgent ?: defaultUserAgent

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        library = Library(this)
        AppLock.install(this, library.prefs)
        runCatching { SyncWorker.schedule(this) }
        runCatching { UpdateWorker.schedule(this) }
        // Remove leftover WhatsApp share copies from earlier sessions.
        Thread { runCatching { WhatsAppShare.cleanUp(this) } }.start()
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).apply {
                createNotificationChannel(NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW))
                createNotificationChannel(NotificationChannel(CH_DONE, "Download finished", NotificationManager.IMPORTANCE_DEFAULT))
            }
        }
    }

    /** Thumbnails load through the same client and user agent as the API. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val ua = userAgent
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { RedGifsApi.clientFor(ua) }))
                // GIFs and WebPs (imported, or from sex.com) play animated.
                add(if (Build.VERSION.SDK_INT >= 28) coil3.gif.AnimatedImageDecoder.Factory() else coil3.gif.GifDecoder.Factory())
            }
            .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(200L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }

    companion object {
        /** True while the app's main screen is on display; an update isn't installed under your hands. */
        @Volatile var inFront = false
        const val CH_PROGRESS = "progress"
        const val CH_DONE = "done"
    }
}

val Context.library: Library get() = (applicationContext as RedGrabApp).library
val Context.userAgent: String get() = (applicationContext as RedGrabApp).userAgent
