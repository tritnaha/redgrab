package app.redgrab

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Colour looks, like a phone gallery editor's filters. */
enum class Look(val label: String) { None("Original"), Mono("B&W"), Noir("Noir"), Sepia("Sepia"), Vintage("Vintage"), Vivid("Vivid"), Warm("Warm"), Cool("Cool"), Faded("Faded") }

/** Picture adjustments. Brightness/contrast -1..1, saturation -100..100, rotation in degrees (0/90/180/270). */
data class Adjust(val look: Look = Look.None, val brightness: Float = 0f, val contrast: Float = 0f, val saturation: Float = 0f,
                  val rotation: Int = 0, val mirror: Boolean = false) {
    val isIdentity get() = this == Adjust()
}

/** What the editor asks for. Times in milliseconds, relative to the source clip. */
data class EditParams(val startMs: Long, val endMs: Long, val fadeInMs: Long, val fadeOutMs: Long, val mute: Boolean,
                      val adjust: Adjust = Adjust()) {
    val lengthMs get() = endMs - startMs

    /** Short human description, stored with copies (e.g. "Trim 0:03–0:12 · fade in/out · muted"). */
    fun describe(sourceMs: Long): String = buildList {
        if (startMs > 0 || endMs < sourceMs) add("Trim ${Format.duration(startMs / 1000.0)}–${Format.duration(endMs / 1000.0)}")
        when {
            fadeInMs > 0 && fadeOutMs > 0 -> add("fade in/out")
            fadeInMs > 0 -> add("fade in")
            fadeOutMs > 0 -> add("fade out")
        }
        if (mute) add("muted")
        if (adjust.look != Look.None) add(adjust.look.label)
        if (adjust.brightness != 0f || adjust.contrast != 0f || adjust.saturation != 0f) add("adjusted")
        if (adjust.rotation != 0) add("rotated ${adjust.rotation}°")
        if (adjust.mirror) add("mirrored")
    }.joinToString(" · ").ifEmpty { "Re-encoded copy" }
}

/**
 * Re-encodes a clip on the phone with Media3 Transformer: trim, fade video to/from black,
 * fade or remove audio. Nothing leaves the device.
 */
@OptIn(UnstableApi::class)
object ClipExporter {

    /** Runs the export into a temp file in the app's cache and returns it. [onProgress] gets 0..100. */
    suspend fun export(ctx: Context, src: Uri, p: EditParams, onProgress: (Int) -> Unit): File = coroutineScope {
        val out = File(ctx.cacheDir, "edit").apply { mkdirs(); listFiles()?.forEach { it.delete() } }.resolve("out.mp4")
        val lengthUs = p.lengthMs * 1000

        val item = MediaItem.Builder().setUri(src)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setStartPositionMs(p.startMs).setEndPositionMs(p.endMs).build()
            ).build()
        val video = pictureEffects(p.adjust) +
            (if (p.fadeInMs > 0 || p.fadeOutMs > 0) listOf(FadeToBlack(lengthUs, p.fadeInMs * 1000, p.fadeOutMs * 1000)) else emptyList())
        val audio: List<AudioProcessor> =
            if (!p.mute && (p.fadeInMs > 0 || p.fadeOutMs > 0)) listOf(AudioFade(lengthUs, p.fadeInMs * 1000, p.fadeOutMs * 1000)) else emptyList()
        val edited = EditedMediaItem.Builder(item)
            .setRemoveAudio(p.mute)
            .setEffects(Effects(audio, video))
            .build()

        withContext(Dispatchers.Main) {
            val transformer = Transformer.Builder(ctx)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .build()
            val ticker = launch {
                val holder = ProgressHolder()
                while (true) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                    delay(250)
                }
            }
            try {
                suspendCancellableCoroutine { cont ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) { cont.resume(Unit) }
                        override fun onError(composition: Composition, result: ExportResult, e: ExportException) {
                            cont.resumeWithException(e)
                        }
                    })
                    cont.invokeOnCancellation { transformer.cancel() }
                    transformer.start(edited, out.path)
                }
            } finally {
                ticker.cancel()
            }
        }
        onProgress(100)
        out
    }

    /** Video effects for a look + adjustments; also used for the live preview in the editor. */
    fun pictureEffects(a: Adjust): List<androidx.media3.common.Effect> = buildList {
        when (a.look) {
            Look.None -> {}
            Look.Mono -> add(androidx.media3.effect.RgbFilter.createGrayscaleFilter())
            Look.Noir -> { add(androidx.media3.effect.RgbFilter.createGrayscaleFilter()); add(androidx.media3.effect.Contrast(0.35f)) }
            Look.Sepia -> add(Matrix(sepia(1f)))
            Look.Vintage -> { add(Matrix(sepia(0.55f))); add(androidx.media3.effect.Contrast(-0.12f)); add(androidx.media3.effect.Brightness(0.04f)) }
            Look.Vivid -> { add(hsl(35f)); add(androidx.media3.effect.Contrast(0.12f)) }
            Look.Warm -> add(androidx.media3.effect.RgbAdjustment.Builder().setRedScale(1.08f).setBlueScale(0.86f).build())
            Look.Cool -> add(androidx.media3.effect.RgbAdjustment.Builder().setRedScale(0.88f).setBlueScale(1.1f).build())
            Look.Faded -> { add(hsl(-30f)); add(androidx.media3.effect.Contrast(-0.25f)); add(androidx.media3.effect.Brightness(0.06f)) }
        }
        if (a.brightness != 0f) add(androidx.media3.effect.Brightness(a.brightness))
        if (a.contrast != 0f) add(androidx.media3.effect.Contrast(a.contrast))
        if (a.saturation != 0f) add(hsl(a.saturation))
        if (a.rotation != 0 || a.mirror) add(
            androidx.media3.effect.ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(a.rotation.toFloat()).setScale(if (a.mirror) -1f else 1f, 1f).build()
        )
    }

    private fun hsl(sat: Float) = androidx.media3.effect.HslAdjustment.Builder().adjustSaturation(sat).build()

    /** Sepia blended with the original by [amount] (0..1), as a column-major 4×4 matrix. */
    private fun sepia(amount: Float): FloatArray {
        val s = floatArrayOf(0.393f, 0.349f, 0.272f, 0f, 0.769f, 0.686f, 0.534f, 0f, 0.189f, 0.168f, 0.131f, 0f, 0f, 0f, 0f, 1f)
        val i = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        return FloatArray(16) { k -> i[k] + (s[k] - i[k]) * amount }
    }

    private class Matrix(private val m: FloatArray) : RgbMatrix {
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean) = m
    }

    private fun gain(tUs: Long, lengthUs: Long, inUs: Long, outUs: Long): Float {
        var g = 1f
        if (inUs > 0 && tUs < inUs) g = minOf(g, tUs.toFloat() / inUs)
        if (outUs > 0 && tUs > lengthUs - outUs) g = minOf(g, (lengthUs - tUs).toFloat() / outUs)
        return g.coerceIn(0f, 1f)
    }

    /** Video fade: scales RGB toward black at the start and end. */
    private class FadeToBlack(private val lengthUs: Long, private val inUs: Long, private val outUs: Long) : RgbMatrix {
        private var baseUs = -1L
        override fun getMatrix(presentationTimeUs: Long, useHdr: Boolean): FloatArray {
            // Frame times may or may not start at 0 after trimming; measure from the first frame seen.
            if (baseUs < 0 || presentationTimeUs < baseUs) baseUs = presentationTimeUs
            val f = gain(presentationTimeUs - baseUs, lengthUs, inUs, outUs)
            return floatArrayOf(f, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, f, 0f, 0f, 0f, 0f, 1f)
        }
    }

    /** Audio fade on 16-bit PCM: linear volume ramp at the start and end. */
    private class AudioFade(private val lengthUs: Long, private val inUs: Long, private val outUs: Long) : BaseAudioProcessor() {
        private var frames = 0L
        private var rate = 44100
        private var channels = 2

        override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
            rate = inputAudioFormat.sampleRate
            channels = inputAudioFormat.channelCount
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val out = replaceOutputBuffer(inputBuffer.remaining())
            val frameBytes = 2 * channels
            while (inputBuffer.remaining() >= frameBytes) {
                val g = gain(frames * 1_000_000L / rate, lengthUs, inUs, outUs)
                repeat(channels) {
                    val v = (inputBuffer.short * g).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    out.putShort(v.toShort())
                }
                frames++
            }
            while (inputBuffer.hasRemaining()) out.put(inputBuffer.get())
            out.flip()
        }

        override fun onReset() { frames = 0 }
    }
}
