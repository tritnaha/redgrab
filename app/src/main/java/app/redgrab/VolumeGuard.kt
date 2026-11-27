package app.redgrab

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/**
 * Protects against a clip suddenly blasting from the phone's loudspeaker. Only acts when media
 * plays through the built-in speaker (not Bluetooth, wired or USB headphones), and never after
 * the user has used the volume keys this session (their choice wins).
 */
object VolumeGuard {

    private val external = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
        AudioDeviceInfo.TYPE_HDMI,
    )

    /** True when media audio is currently routed to the phone's own loudspeaker. */
    fun onPhoneSpeaker(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return false
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
        val route = am.getAudioDevicesForAttributes(attrs)
        return route.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } && route.none { it.type in external }
    }

    /**
     * Lowers media volume to [limitPercent] of max if it's above that and playing on the speaker.
     * Returns a short message when it changed something, else null.
     */
    fun apply(ctx: Context, limitPercent: Int, afterRouteChange: Boolean = false): String? {
        // The volume-key override was a choice made for the old output; a switch to the speaker checks again.
        if (afterRouteChange) MainViewModel.volumeOverride = false
        if (MainViewModel.volumeOverride) return null
        if (!onPhoneSpeaker(ctx)) return null
        val am = ctx.getSystemService(AudioManager::class.java) ?: return null
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val limit = (max * limitPercent / 100).coerceAtLeast(1)
        if (now <= limit) return null
        am.setStreamVolume(AudioManager.STREAM_MUSIC, limit, 0)
        AppLog.i("Volume guard: speaker volume lowered from $now to $limit of $max")
        return "Speaker volume lowered to $limitPercent%. Use the volume keys to go higher."
    }
}
