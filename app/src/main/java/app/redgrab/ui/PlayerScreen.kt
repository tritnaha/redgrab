package app.redgrab.ui

import android.app.Activity
import android.net.Uri
import android.webkit.WebSettings
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Hd
import androidx.compose.material.icons.outlined.Sd
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.DynamicFeed
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.redgrab.AppLog
import app.redgrab.Clip
import app.redgrab.Facet
import app.redgrab.Format
import app.redgrab.MainViewModel
import app.redgrab.OnlineSource
import app.redgrab.PlayerSession
import app.redgrab.Quality
import app.redgrab.RedGifsApi
import app.redgrab.RedGrabApp
import androidx.media3.datasource.cache.CacheDataSource
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a clip is played from. Saved files play offline; others stream from their site. */
private enum class Source { Saved, Stream }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: MainViewModel, session: PlayerSession) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val s by vm.state.collectAsStateWithLifecycle()
    val dl by vm.download.collectAsStateWithLifecycle()
    val byId = remember(s.clips) { s.clips.associateBy { it.id } }
    val clipId = session.ids[session.index]
    val clip: Clip = byId[clipId] ?: session.online[clipId] ?: run { vm.closePlayer(); return }
    val saved = clip.id in s.saved
    /** A clip from online browsing that isn't in the library (yet). */
    val online = clipId !in byId
    val currentClip by rememberUpdatedState(clip)

    var fullscreen by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(true) }
    var firstFrame by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var seeking by remember { mutableStateOf<Float?>(null) }
    var flash by remember { mutableStateOf<ImageVector?>(null) }
    var flashKey by remember { mutableLongStateOf(0L) }
    var currentSource by remember { mutableStateOf(Source.Stream) }
    var retried by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    /** Width and height of what's showing (from the site, else from the video or picture itself); 0 = not known yet. */
    var mediaSize by remember { mutableStateOf(0 to 0) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    val ua = remember { vm.library.prefs.userAgent ?: runCatching { WebSettings.getDefaultUserAgent(ctx) }.getOrDefault("Mozilla/5.0") }
    // Saved files: read straight from the phone. Unsaved clips: stream through a local cache.
    val localFactory = remember { DefaultMediaSourceFactory(DefaultDataSource.Factory(ctx)) }
    val streamFactory = remember {
        val upstream = DefaultDataSource.Factory(ctx, OkHttpDataSource.Factory(RedGifsApi.clientFor(ua)))
        DefaultMediaSourceFactory(
            CacheDataSource.Factory().setCache((ctx.applicationContext as RedGrabApp).mediaCache).setUpstreamDataSourceFactory(upstream)
        )
    }
    val exo = remember {
        ExoPlayer.Builder(ctx).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }
    var playingQuality by remember { mutableStateOf<Quality?>(null) }
    /** Set when the current item is a picture file (GIF, WebP): shown with the image viewer, not the video player. */
    var imageUri by remember { mutableStateOf<Uri?>(null) }

    fun show(icon: ImageVector) { flash = icon; flashKey = System.nanoTime() }

    fun load(c: Clip, source: Source, url: Uri) {
        currentSource = source
        firstFrame = false
        error = null
        val item = MediaItem.fromUri(url)
        exo.setMediaSource((if (source == Source.Saved) localFactory else streamFactory).createMediaSource(item))
        exo.prepare()
        exo.playWhenReady = true
    }

    // Saved file first (HD, then SD); stream only clips that aren't on the phone.
    LaunchedEffect(clip.id, s.qualities[clip.id], clip.copyFile) {
        retried = false
        position = 0; duration = 0
        mediaSize = if (clip.width > 0 && clip.height > 0) clip.width to clip.height else 0 to 0
        val local = withContext(Dispatchers.IO) { vm.library.markSeen(listOf(clip.id)); vm.library.localFile(clip) }
        playingQuality = local?.second
        imageUri = null
        if (clip.isImage) {
            exo.stop(); exo.clearMediaItems()
            when {
                local != null -> { currentSource = Source.Saved; error = null; imageUri = local.first }
                // An online GIF that isn't saved yet: shown straight from its site.
                clip.copyFile == null && clip.hd != null -> { currentSource = Source.Stream; error = null; imageUri = Uri.parse(clip.hd) }
                else -> { currentSource = Source.Saved; error = "The file is missing from the save folder." }
            }
            return@LaunchedEffect
        }
        var c = clip
        if (local == null && c.hd == null && c.sd == null && !c.isCopy && !c.imported) {
            // A RedGifs clip found on Reddit: only its ID is known until RedGifs is asked.
            exo.stop(); exo.clearMediaItems()
            firstFrame = false; error = null; currentSource = Source.Stream
            c = vm.resolveOnline(c) ?: run {
                error = if (vm.signedIn) "Couldn't get this clip from RedGifs." else "Sign in to RedGifs to play this clip."
                return@LaunchedEffect
            }
        }
        val stream = c.url(vm.library.prefs.hd)?.let(Uri::parse)
        when {
            local != null -> load(c, Source.Saved, local.first)
            c.isCopy -> error = "The edited file is missing from the save folder."
            c.imported -> error = "The file is missing from the save folder."
            stream != null -> load(c, Source.Stream, stream)
            else -> error = "No video available for this clip."
        }
    }

    // Player callbacks: play state, buffering, first frame, errors (with one automatic retry).
    DisposableEffect(exo) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = exo.playWhenReady }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onRenderedFirstFrame() { firstFrame = true }
            override fun onVideoSizeChanged(v: androidx.media3.common.VideoSize) {
                if (v.width > 0 && v.height > 0) mediaSize = (v.width * v.pixelWidthHeightRatio).toInt() to v.height
            }
            override fun onPlayerError(e: PlaybackException) {
                val clip = currentClip
                AppLog.w("Playback error for ${clip.id} (${currentSource}): ${e.errorCodeName}")
                if (retried || clip.imported) { error = "Can't play this clip (${e.errorCodeName}). See Log."; return }
                retried = true
                scope.launch {
                    // Saved file unreadable → stream instead. Stream URL expired → fetch a fresh one from the clip's site.
                    val fresh = vm.refreshClip(clip)
                    if (currentClip.id != clip.id) return@launch // you've moved on to another clip
                    val url = if (clip.isCopy) null else (fresh ?: clip).url(vm.library.prefs.hd)
                    if (url != null) load(clip, Source.Stream, Uri.parse(url))
                    else error = "Can't play this clip. See Log."
                }
            }
        }
        exo.addListener(listener)
        onDispose { exo.removeListener(listener) }
    }

    // SFW mode always mutes. When sound is on, keep the phone speaker from blasting (volume guard).
    LaunchedEffect(vm.playMuted, vm.sfw) {
        val muted = vm.playMuted || vm.sfw
        exo.volume = if (muted) 0f else 1f
        if (!muted && vm.volumeGuard) {
            app.redgrab.VolumeGuard.apply(ctx, vm.volumeLimit)?.let {
                android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    DisposableEffect(Unit) {
        val am = ctx.getSystemService(android.media.AudioManager::class.java)
        val cb = object : android.media.AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removed: Array<out android.media.AudioDeviceInfo>?) {
                // Headphones or Bluetooth just went away: sound would now come out of the speaker.
                if (!(vm.playMuted || vm.sfw) && vm.volumeGuard) {
                    app.redgrab.VolumeGuard.apply(ctx, vm.volumeLimit, afterRouteChange = true)?.let {
                        android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        am?.registerAudioDeviceCallback(cb, android.os.Handler(android.os.Looper.getMainLooper()))
        onDispose { am?.unregisterAudioDeviceCallback(cb) }
    }

    // Progress ticker.
    LaunchedEffect(exo) {
        while (true) {
            position = exo.currentPosition
            duration = exo.duration.takeIf { it > 0 } ?: 0
            delay(200)
        }
    }

    // Pause when the app goes to the background; release when the player closes.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_PAUSE) exo.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); exo.release() }
    }

    // The player takes the whole screen: the status and navigation bars stay hidden while it's open
    // (a swipe from the edge shows them briefly). "Fullscreen" additionally hides the player's own controls.
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val ctl = WindowCompat.getInsetsController(window, view)
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (window != null) WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars()) }
    }

    // How the clip sits on the screen. With "Fill the screen" on, a clip that is the same way up as
    // the screen covers all of it (edges cropped). Otherwise the whole clip is shown as large as it
    // fits, over a dimmed view of what's behind, and a tap beside it closes the player.
    val aspect = if (mediaSize.second > 0) mediaSize.first.toFloat() / mediaSize.second else 0f
    val screenAspect = if (boxSize.height > 0) boxSize.width.toFloat() / boxSize.height else 0f
    val cover = vm.playerFill && (aspect == 0f || screenAspect == 0f || (aspect < 1f) == (screenAspect < 1f))
    val coverNow by rememberUpdatedState(cover)
    val aspectNow by rememberUpdatedState(aspect)
    val scale = if (cover) ContentScale.Crop else ContentScale.Fit

    BackHandler { if (fullscreen) fullscreen = false else vm.closePlayer() }

    val hasPrev = session.index > 0
    val hasNext = session.index < session.ids.lastIndex
    fun prev() { if (hasPrev) { vm.movePlayer(-1); show(Icons.Default.SkipPrevious) } }
    fun next() { if (hasNext) { vm.movePlayer(1); show(Icons.Default.SkipNext) } }
    fun togglePlay() {
        if (clip.isImage) { fullscreen = !fullscreen; return } // pictures loop by themselves
        if (exo.isPlaying) { exo.pause(); playing = false; show(Icons.Default.Pause) }
        else { exo.play(); playing = true; show(Icons.Default.PlayArrow) }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { boxSize = it }.background(Color.Black.copy(alpha = if (cover) 1f else 0.88f))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    player = exo
                }
            },
            update = {
                it.resizeMode = if (cover) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                // A picture needs no video surface; left in place it would paint the whole screen black behind the picture.
                it.visibility = if (imageUri != null) android.view.View.GONE else android.view.View.VISIBLE
            },
        )

        imageUri?.let {
            AsyncImage(it, null, contentScale = scale, modifier = Modifier.fillMaxSize(),
                onSuccess = { ok -> if (ok.result.image.width > 0 && ok.result.image.height > 0) mediaSize = ok.result.image.width to ok.result.image.height })
        }

        // Thumbnail over the (still empty) video surface until the first frame arrives.
        if (!firstFrame && imageUri == null) {
            AsyncImage(thumbModel(s, vm.library, clip), null, contentScale = scale, modifier = Modifier.fillMaxSize())
        }

        // Gesture layer. On the clip: left quarter = previous, right quarter = next, middle = play/pause.
        // Beside the clip (when it doesn't cover the screen): close. Swipe sideways to navigate, down to close.
        Box(
            Modifier.fillMaxSize()
                .pointerInput(session.index, session.ids.size) {
                    detectTapGestures { o ->
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val a = aspectNow
                        // The part of the screen the clip takes up when it's shown whole.
                        val onClip = coverNow || a <= 0f || h <= 0f || run {
                            val wide = w / h > a
                            val mw = if (wide) h * a else w
                            val mh = if (wide) h else w / a
                            kotlin.math.abs(o.x - w / 2) <= mw / 2 && kotlin.math.abs(o.y - h / 2) <= mh / 2
                        }
                        when {
                            !onClip -> vm.closePlayer()
                            o.x < w * 0.25f -> prev()
                            o.x > w * 0.75f -> next()
                            else -> togglePlay()
                        }
                    }
                }
                .pointerInput(session.index, session.ids.size) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = { if (dx > 120) prev() else if (dx < -120) next() },
                    ) { _, d -> dx += d }
                }
                .pointerInput(Unit) {
                    var dy = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dy = 0f },
                        onDragEnd = { if (dy > size.height * 0.12f) vm.closePlayer() },
                    ) { _, d -> dy += d }
                },
        )

        if (buffering && error == null) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Color.White, strokeWidth = 3.dp)
        }
        error?.let {
            Text(it, color = Color.White, modifier = Modifier.align(Alignment.Center)
                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp)).padding(16.dp))
        }

        // Brief feedback icon for taps.
        LaunchedEffect(flashKey) { if (flash != null) { delay(550); flash = null } }
        AnimatedVisibility(flash != null, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.size(84.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape), contentAlignment = Alignment.Center) {
                flash?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(48.dp)) }
            }
        }
        if (!playing && flash == null && error == null && !buffering) {
            Icon(Icons.Default.PlayArrow, "Paused", tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.align(Alignment.Center).size(72.dp))
        }

        // A thin accent line marks anything that's streaming rather than playing from the phone.
        if (currentSource == Source.Stream && error == null) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(3.dp).background(RgRed))
        }

        if (fullscreen) {
            // Only a subtle corner control in fullscreen.
            IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                Icon(Icons.Default.FullscreenExit, "Show controls", tint = Color.White.copy(alpha = 0.6f))
            }
        } else {
            // Top bar.
            Row(
                Modifier.align(Alignment.TopCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                    // Clear of the camera cut-out now that the status bar is hidden. Taps on the bar itself do nothing.
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = vm::closePlayer) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
                Text("${session.index + 1} / ${session.ids.size}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                if (currentSource == Source.Stream && error == null) OnlinePill(Modifier.padding(start = 10.dp))
                Spacer(Modifier.weight(1f))
                if (!clip.isImage) IconButton(onClick = { vm.changePlayMuted(!vm.playMuted) }) {
                    Icon(if (vm.playMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        if (vm.playMuted) "Unmute" else "Mute", tint = Color.White)
                }
                IconButton(onClick = { vm.changePlayerFill(!vm.playerFill) }) {
                    Icon(if (vm.playerFill) Icons.Default.FitScreen else Icons.Default.ZoomOutMap,
                        if (vm.playerFill) "Show the whole clip" else "Fill the screen", tint = Color.White)
                }
                IconButton(onClick = { fullscreen = true }) { Icon(Icons.Default.Fullscreen, "Hide controls", tint = Color.White) }
            }

            // Bottom panel: info, tags, progress, actions.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .navigationBarsPadding().pointerInput(Unit) { detectTapGestures { } }.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        if (clip.user.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable {
                                    if (online) vm.openPerformerOnline(clip.user, OnlineSource.of(clip.id)) else vm.browse(Facet(Facet.Kind.Performer, clip.user))
                                }) {
                                Text(clip.user, color = Color.White, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        if (clip.description.isNotBlank()) {
                            Text(clip.description, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Text(
                            buildString {
                                // Some sites don't say how long a clip is; the length is left out then.
                                listOfNotNull(
                                    if (clip.isImage) clip.fileExt.uppercase() else if (clip.duration > 0 || !online) Format.duration(clip.duration) else null,
                                    if (clip.width <= 0) null else if (clip.isImage) "${clip.width}×${clip.height}" else "${clip.height}p",
                                    "no sound".takeIf { !clip.hasAudio && !clip.isImage },
                                    "%,d views".format(clip.views).takeIf { clip.views > 0 },
                                ).joinTo(this, " · ")
                            },
                            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                        )
                    }
                    // Same status icon as the grids: heart / bookmark / globe, green and filled once saved.
                    if (!clip.isCopy) SourceIcon(liked = clip.liked, inCollection = clip.colIds.isNotEmpty(), saved = saved, size = 24.dp, imported = "Imported" in clip.sources)
                }
                if (clip.tags.isNotEmpty() || clip.niches.isNotEmpty() || clip.category.isNotBlank()) {
                    Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Category (yours) and niches (RedGifs communities) first, then tags.
                        if (clip.category.isNotBlank()) {
                            Text(clip.category, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(RgRed.copy(alpha = 0.55f))
                                    .clickable { vm.browse(Facet(Facet.Kind.Category, clip.category)) }.padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        clip.niches.forEach { n ->
                            Text("◆ $n", color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(RgPurple.copy(alpha = 0.45f))
                                    .clickable { if (online) vm.openNicheOnline(n) else vm.browse(Facet(Facet.Kind.Niche, n)) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                        clip.tags.forEach { t ->
                            Text(t, color = Color.White, fontSize = 12.sp,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f))
                                    .clickable { if (online) vm.searchOnline(t, OnlineSource.of(clip.id)) else vm.browse(Facet(Facet.Kind.Tag, t)) }
                                    .padding(horizontal = 10.dp, vertical = 4.dp))
                        }
                    }
                }
                val frac = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                if (!clip.isImage) Slider(
                    value = seeking ?: frac,
                    onValueChange = { seeking = it },
                    onValueChangeFinished = { seeking?.let { exo.seekTo((it * duration).toLong()) }; seeking = null },
                    colors = SliderDefaults.colors(thumbColor = RgRed, activeTrackColor = RgRed, inactiveTrackColor = Color.White.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth().height(28.dp),
                )
                s.failures[clip.id]?.let { f ->
                    Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = 0.5f)).padding(start = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, null, tint = RgAmber, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download failed: ${f.reason}" + (if (f.attempts > 1) " (${f.attempts} tries)" else "") +
                            (if (f.gaveUp) ". No more automatic retries." else ""),
                            color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
                        androidx.compose.material3.TextButton(onClick = { vm.retryFailed(listOf(clip.id)) }) { Text("Retry") }
                    }
                }
                // Where it's playing from and which qualities are saved (tap a missing one to save it).
                val have = s.qualities[clip.id] ?: emptySet()
                Row(Modifier.padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (clip.isCopy) {
                        Text("Edited copy · ${clip.copyNote}", color = RgPurple.copy(alpha = 0.95f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    } else if (clip.imported || OnlineSource.keepsOwnFile(clip)) {
                        // Where it's from ("Imported", or the site a picture was saved from), then the file once it's on the phone.
                        Text(listOfNotNull(clip.sources.firstOrNull() ?: "Imported", clip.copyFile?.substringAfterLast('/')).joinToString(" · "),
                            color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    } else {
                        for (q in listOf(Quality.HD, Quality.SD)) {
                            val isSaved = q in have
                            val available = clip.url(q) != null
                            QualityChip(q, isSaved, available, downloading = dl.running && dl.currentId == clip.id) {
                                if (!isSaved && available) { if (online) vm.saveOnline(clip, q) else vm.downloadQuality(clip.id, q) }
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            when {
                                currentSource == Source.Stream -> "Streaming from ${OnlineSource.of(clip.id).label}"
                                playingQuality == Quality.LEGACY -> "Playing saved file"
                                playingQuality != null -> "Playing saved ${playingQuality!!.label}"
                                else -> ""
                            },
                            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    PillButton(Icons.AutoMirrored.Filled.Send, if (vm.sharing) "Preparing…" else "WhatsApp", RgGreen.copy(alpha = 0.9f)) {
                        vm.shareToWhatsApp(clip)
                    }
                    if (clip.isImage) {
                        // Pictures can't be trimmed; one from online can be saved.
                        if (!saved) PillButton(Icons.Default.Download, if (clip.id in vm.savingImages) "Saving…" else "Save", Color.White.copy(alpha = 0.15f)) {
                            vm.saveOnline(clip)
                        }
                    } else if (saved) PillButton(Icons.Default.ContentCut, "Edit", Color.White.copy(alpha = 0.15f)) { vm.openEditor(clip) }
                    else PillButton(Icons.Default.Download, if (clip.id in vm.savingImages) "Saving…" else "Save", Color.White.copy(alpha = 0.15f)) {
                        if (online) vm.saveOnline(clip) else vm.download(clip.id)
                    }
                    Spacer(Modifier.weight(1f))
                    if (clip.isCopy) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.DeleteOutline, "Delete edited copy", tint = Color.White) }
                    } else {
                        // Related: RedGifs' own "more like this" list for the clip, shown in Discover.
                        if (!clip.imported && !clip.isImage && OnlineSource.of(clip.id) == OnlineSource.RedGifs) IconButton(onClick = { vm.openRelated(clip) }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.DynamicFeed, "Related clips", tint = Color.White)
                        }
                        if (!online) IconButton(onClick = { vm.editInfo(listOf(clip.id)) }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.EditNote, "Edit info", tint = Color.White)
                        }
                        if (saved) IconButton(onClick = { vm.requestDelete(listOf(clip.id)) }, modifier = Modifier.size(42.dp)) {
                            Icon(Icons.Default.DeleteOutline, "Delete", tint = Color.White)
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this edited copy?") },
            text = { Text("${clip.copyFile} is deleted from the save folder. The original clip isn't affected.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false; vm.deleteCopy(clip) }) { Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

/**
 * HD/SD indicator: the icon alone. Filled green = saved; white with a small download arrow =
 * available, tap to save it; faded = RedGifs has no file in that quality.
 */
@Composable
private fun QualityChip(q: Quality, saved: Boolean, available: Boolean, downloading: Boolean, onClick: () -> Unit) {
    val icon = if (q == Quality.SD) Icons.Outlined.Sd else Icons.Outlined.Hd
    val desc = when { saved -> "${q.label} saved"; available -> "Save in ${q.label}"; else -> "No ${q.label} version" }
    // Plain icon, no background: green = saved, white with a small arrow = tap to save, faded = not offered.
    Box(Modifier.clip(RoundedCornerShape(8.dp)).clickable(enabled = !saved && available && !downloading, onClick = onClick).padding(2.dp)) {
        Icon(icon, desc, tint = if (saved) RgGreen else Color.White.copy(alpha = if (available) 1f else 0.35f), modifier = Modifier.size(28.dp))
        if (!saved && available) Icon(Icons.Default.ArrowDownward, null, tint = Color.White,
            modifier = Modifier.size(11.dp).align(Alignment.BottomEnd))
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, bg: Color, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
