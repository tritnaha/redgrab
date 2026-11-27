package app.redgrab.ui

import app.redgrab.*
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Gradient
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.redgrab.Adjust
import app.redgrab.AppLog
import app.redgrab.Look
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FilterChip
import app.redgrab.Clip
import app.redgrab.ClipExporter
import app.redgrab.EditCopy
import app.redgrab.EditParams
import app.redgrab.Format
import app.redgrab.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Simple clip editor: trim (with live preview looping the selection), fade in/out, mute.
 * Saves either a new linked copy (`<id>-edit-N.mp4`) or, after confirmation, overwrites the file.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@OptIn(UnstableApi::class)
@Composable
fun EditorScreen(vm: MainViewModel, clip: Clip) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var source by remember { mutableStateOf<Uri?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var range by remember { mutableStateOf(0f..1f) }       // fraction of duration
    var fadeIn by remember { mutableFloatStateOf(0f) }      // seconds
    var fadeOut by remember { mutableFloatStateOf(0f) }
    var mute by remember { mutableStateOf(!clip.hasAudio) }
    var playing by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var confirmOverwrite by remember { mutableStateOf(false) }
    var adjust by remember { mutableStateOf(Adjust()) }

    val exo = remember { ExoPlayer.Builder(ctx).build().apply { repeatMode = Player.REPEAT_MODE_OFF; volume = 0f } }

    // Find the saved file to edit (edits always work on the phone's copy, never a stream).
    LaunchedEffect(clip.id) {
        val found = withContext(Dispatchers.IO) { vm.library.localFile(clip) }
        if (found == null) { loadError = "The saved file wasn't found in the save folder."; return@LaunchedEffect }
        source = found.first
        sourceName = clip.copyFile ?: found.second?.fileName(clip.id) ?: ""
        exo.setMediaItem(MediaItem.fromUri(found.first))
        exo.prepare()
        exo.play()
    }
    DisposableEffect(exo) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY && durationMs == 0L) durationMs = exo.duration.coerceAtLeast(1)
            }
        }
        exo.addListener(l)
        onDispose { exo.removeListener(l); exo.release() }
    }
    LaunchedEffect(mute) { exo.volume = if (mute || vm.playMuted) 0f else 1f }
    // Never keep playing (and sounding) once the app is in the background.
    val wantPlaying by rememberUpdatedState(playing)
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(exo, lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, ev ->
            if (ev == androidx.lifecycle.Lifecycle.Event.ON_STOP) exo.pause()
            else if (ev == androidx.lifecycle.Lifecycle.Event.ON_START && wantPlaying) exo.play()
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    val startMs = (range.start * durationMs).toLong()
    val endMs = (range.endInclusive * durationMs).toLong()

    // Loop playback inside the selected range.
    val loopStart by rememberUpdatedState(startMs)
    val loopEnd by rememberUpdatedState(endMs)
    LaunchedEffect(durationMs) {
        while (true) {
            position = exo.currentPosition
            if (durationMs > 0 && (position >= loopEnd - 50 || position < loopStart - 300)) exo.seekTo(loopStart)
            delay(100)
        }
    }

    fun params() = EditParams(startMs, endMs, (fadeIn * 1000).toLong(), (fadeOut * 1000).toLong(), mute, adjust)

    // Live preview of the look/adjustments (fades are only applied when saving).
    LaunchedEffect(adjust, durationMs) {
        if (durationMs == 0L) return@LaunchedEffect
        delay(250) // wait until the slider settles; each change restarts this effect
        runCatching {
            val pos = exo.currentPosition
            exo.setVideoEffects(ClipExporter.pictureEffects(adjust))
            exo.stop(); exo.prepare(); exo.seekTo(pos); exo.play()
        }.onFailure { AppLog.w("Live filter preview not supported on this device: ${it.message}") }
    }

    fun runExport(overwrite: Boolean) {
        val src = source ?: return
        exo.pause()
        progress = 0
        exporting = scope.launch {
            val p = params()
            val result = runCatching {
                val out: File = ClipExporter.export(ctx, src, p) { progress = it }
                withContext(Dispatchers.IO) { saveResult(vm, clip, out, p, overwrite, durationMs) }
            }
            exporting = null
            result.onSuccess { msg ->
                AppLog.i(msg)
                vm.closeEditor()
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) return@onFailure
                AppLog.e("Editing ${clip.id} failed: ${e.message}", e)
                loadError = "Saving failed: ${e.message}. See Log."
            }
        }
    }

    BackHandler { if (exporting == null) vm.closeEditor() }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (exporting == null) vm.closeEditor() }) { Icon(Icons.Default.Close, "Close editor") }
                Column(Modifier.weight(1f)) {
                    Text("Edit clip", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(sourceName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Preview; tap to play/pause.
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Color.Black)
                    .clickable(remember { MutableInteractionSource() }, null) {
                        if (exo.isPlaying) exo.pause() else exo.play(); playing = exo.playWhenReady
                    },
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { c ->
                    PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; player = exo }
                })
                if (!playing) Icon(Icons.Default.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(56.dp))
                if (durationMs == 0L && loadError == null) CircularProgressIndicator(color = Color.White)
            }

            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                Section(Icons.Default.ContentCut, "Trim",
                    "${Format.duration(startMs / 1000.0)} – ${Format.duration(endMs / 1000.0)}  ·  length ${Format.duration((endMs - startMs) / 1000.0)}")
                RangeSlider(
                    value = range,
                    onValueChange = { r ->
                        // Keep at least half a second selected.
                        val minFrac = if (durationMs > 0) 500f / durationMs else 0.05f
                        if (r.endInclusive - r.start >= minFrac) {
                            if (r.start != range.start) exo.seekTo((r.start * durationMs).toLong())
                            range = r
                        }
                    },
                    enabled = durationMs > 0 && exporting == null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        if (durationMs > 0) range = (position.toFloat() / durationMs).coerceAtMost(range.endInclusive - 0.01f)..range.endInclusive
                    }, enabled = durationMs > 0) { Text("Start here") }
                    FilledTonalButton(onClick = {
                        if (durationMs > 0) range = range.start..(position.toFloat() / durationMs).coerceAtLeast(range.start + 0.01f)
                    }, enabled = durationMs > 0) { Text("End here") }
                    TextButton(onClick = { range = 0f..1f }) { Text("Reset") }
                }

                Spacer(Modifier.height(8.dp))
                Section(Icons.Default.AutoAwesome, "Look", adjust.look.label)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Look.entries.forEach { l ->
                        FilterChip(adjust.look == l, { adjust = adjust.copy(look = l) }, { Text(l.label) }, enabled = exporting == null)
                    }
                }
                Section(Icons.Default.Tune, "Adjust", if (adjust.brightness == 0f && adjust.contrast == 0f && adjust.saturation == 0f) "Off" else "On")
                AdjustSlider("Brightness", adjust.brightness, -0.5f..0.5f) { adjust = adjust.copy(brightness = it) }
                AdjustSlider("Contrast", adjust.contrast, -0.5f..0.5f) { adjust = adjust.copy(contrast = it) }
                AdjustSlider("Saturation", adjust.saturation / 100f, -1f..1f) { adjust = adjust.copy(saturation = it * 100f) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(onClick = { adjust = adjust.copy(rotation = (adjust.rotation + 90) % 360) }) {
                        Icon(Icons.AutoMirrored.Filled.RotateRight, null); Spacer(Modifier.width(6.dp)); Text(if (adjust.rotation == 0) "Rotate" else "${adjust.rotation}°")
                    }
                    FilterChip(adjust.mirror, { adjust = adjust.copy(mirror = !adjust.mirror) }, { Text("Mirror") },
                        leadingIcon = { Icon(Icons.Default.Flip, null, Modifier.size(18.dp)) })
                    if (!adjust.isIdentity) TextButton(onClick = { adjust = Adjust() }) { Text("Reset") }
                }

                Spacer(Modifier.height(8.dp))
                Section(Icons.Default.Gradient, "Fade in", if (fadeIn == 0f) "Off" else "%.1f s".format(fadeIn))
                Slider(fadeIn, { fadeIn = it }, valueRange = 0f..3f, steps = 5, enabled = exporting == null)
                Section(Icons.Default.Gradient, "Fade out", if (fadeOut == 0f) "Off" else "%.1f s".format(fadeOut))
                Slider(fadeOut, { fadeOut = it }, valueRange = 0f..3f, steps = 5, enabled = exporting == null)
                Text("Fades go to/from black (and silence) and are applied when you save.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.VolumeOff, null)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Remove sound")
                        Text(if (clip.hasAudio) "The saved clip will have no audio track." else "This clip has no sound.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(mute, { mute = it }, enabled = clip.hasAudio && exporting == null)
                }
            }

            // Save actions.
            Column(Modifier.padding(16.dp)) {
                exporting?.let {
                    Text("Saving… $progress%", style = MaterialTheme.typography.labelLarge)
                    LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    OutlinedButton(onClick = { it.cancel(); exporting = null }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                } ?: run {
                    Button(onClick = { runExport(overwrite = false) }, enabled = source != null && durationMs > 0, modifier = Modifier.fillMaxWidth()) {
                        Text("Save as copy")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { confirmOverwrite = true }, enabled = source != null && durationMs > 0, modifier = Modifier.fillMaxWidth()) {
                        Text(if (clip.isCopy) "Overwrite this copy" else "Overwrite original")
                    }
                }
            }
        }
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("Overwrite $sourceName?") },
            text = { Text("The saved file is replaced with the edited version. This can't be undone. \"Save as copy\" keeps the original.") },
            confirmButton = { TextButton(onClick = { confirmOverwrite = false; runExport(overwrite = true) }) { Text("Overwrite") } },
            dismissButton = { TextButton(onClick = { confirmOverwrite = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AdjustSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
        Slider(value, onChange, valueRange = range, modifier = Modifier.weight(1f))
        Text("%+d".format((value / range.endInclusive * 100).toInt()), style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(40.dp))
    }
}

@Composable
private fun Section(icon: ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Moves the exported temp file into the save folder, as a linked copy or over the source file. */
private fun saveResult(vm: MainViewModel, clip: Clip, out: File, p: EditParams, overwrite: Boolean, sourceMs: Long): String {
    val lib = vm.library
    val ctx = vm.getApplication<android.app.Application>()
    val folder = lib.folder() ?: error("Save folder not available")
    try {
        if (overwrite) {
            // Write the new version next to the old one first, then swap: a failure midway
            // never leaves you with a half-written original.
            val found = lib.localFile(clip) ?: error("Original file not found")
            val targetName = clip.copyFile ?: found.second?.fileName(clip.id) ?: error("Original file not found")
            val tmp = folder.createFile("application/octet-stream", "$targetName.new") ?: error("Could not create a temporary file")
            try {
                ctx.contentResolver.openOutputStream(tmp.uri)!!.use { o -> out.inputStream().use { it.copyTo(o) } }
            } catch (e: Exception) {
                tmp.delete(); throw e
            }
            // Swap: set the original aside, move the new file in, and only then drop the original.
            val resolver = ctx.contentResolver
            synchronized(lib.swapLock) {
                val aside = runCatching { android.provider.DocumentsContract.renameDocument(resolver, found.first, "$targetName.old") }.getOrNull()
                if (aside == null) { tmp.delete(); error("Couldn't set the original aside, so nothing was changed") }
                val placed = runCatching { android.provider.DocumentsContract.renameDocument(resolver, tmp.uri, targetName) }.getOrNull()
                if (placed == null) {
                    runCatching { android.provider.DocumentsContract.renameDocument(resolver, aside, targetName) }
                    tmp.delete()
                    error("Couldn't put the edited file in place; the original is unchanged")
                }
                runCatching { android.provider.DocumentsContract.deleteDocument(resolver, aside) }
            }
            // The file changed: new preview picture, new checksum, and (for copies) new length/sound info.
            lib.fileUri(targetName)?.let { lib.makeThumb(clip.id, it) }
            runCatching {
                val md = java.security.MessageDigest.getInstance("SHA-256")
                out.inputStream().use { i -> val buf = ByteArray(64 * 1024); while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
                lib.recordFile(targetName, out.length(), md.digest().joinToString("") { "%02x".format(it) })
            }
            if (clip.isCopy) lib.updateCopy(clip.id) {
                it.copy(duration = p.lengthMs / 1000.0, hasAudio = it.hasAudio && !p.mute, note = p.describe(sourceMs))
            }
            return "Overwrote $targetName (${p.describe(sourceMs)})"
        }
        val originalId = clip.copyOf ?: clip.id
        val (copyId, name) = lib.nextCopyName(originalId)
        val doc = folder.createFile("video/mp4", name) ?: error("Could not create $name")
        ctx.contentResolver.openOutputStream(doc.uri)!!.use { o -> out.inputStream().use { it.copyTo(o) } }
        // The provider may append its own extension; record the real name.
        val realName = doc.name ?: name
        lib.makeThumb(copyId, doc.uri)
        lib.addCopy(EditCopy(copyId, originalId, realName, p.describe(sourceMs), System.currentTimeMillis(),
            p.lengthMs / 1000.0, hasAudio = clip.hasAudio && !p.mute))
        return "Saved edited copy $realName of $originalId (${p.describe(sourceMs)})"
    } finally {
        out.delete()
    }
}
