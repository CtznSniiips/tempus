package com.cappielloantonio.tempo.service

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.util.Preferences

private const val TICK_INTERVAL_MS = 100L

@UnstableApi
class CrossfadeManager {

    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null

    // Whether the fade-in for the current track is still in progress
    private var fadingIn = false
    // Whether we are actively fading out (approaching end of track)
    private var fadingOut = false

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            if (player?.isPlaying == true) {
                handler.postDelayed(this, TICK_INTERVAL_MS)
            }
        }
    }

    fun attach(player: Player) {
        this.player = player
    }

    fun start() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * Called when a media item transition occurs. Decides whether to start a fade-in for the new
     * track based on the crossfade mode and album-awareness rules.
     *
     * @param oldItem  the item that just finished playing (may be null at first start)
     * @param newItem  the item now playing
     */
    fun onTrackTransition(oldItem: MediaItem?, newItem: MediaItem?) {
        val mode = Preferences.getCrossfadeMode()
        if (mode == "disabled" || newItem == null) {
            resetVolume()
            fadingIn = false
            fadingOut = false
            return
        }

        if (mode == "album_aware" && isConsecutiveAlbumTrack(oldItem, newItem)) {
            // Preserve gapless: no crossfade for sequential same-album tracks
            resetVolume()
            fadingIn = false
            fadingOut = false
            return
        }

        // Begin fade-in: start from silence
        player?.volume = 0f
        fadingIn = true
        fadingOut = false
    }

    /**
     * Called when playback is paused/stopped so we can clean up the tick and restore volume.
     */
    fun onPlaybackStopped() {
        stop()
        fadingIn = false
        fadingOut = false
        resetVolume()
    }

    /**
     * Called on seek within the same track or manual skip so the fade state is reset.
     */
    fun onSeekOrSkip() {
        fadingIn = false
        fadingOut = false
        resetVolume()
    }

    private fun tick() {
        val p = player ?: return
        if (!p.isPlaying) return

        val mode = Preferences.getCrossfadeMode()
        if (mode == "disabled") {
            if (fadingIn || fadingOut) {
                fadingIn = false
                fadingOut = false
                resetVolume()
            }
            return
        }

        val durationMs = p.duration
        val positionMs = p.currentPosition
        val crossfadeMs = Preferences.getCrossfadeDurationSeconds() * 1000L

        if (crossfadeMs <= 0) {
            resetVolume()
            return
        }

        if (fadingIn) {
            if (durationMs <= 0 || positionMs < 0) return

            val progress = (positionMs.toFloat() / crossfadeMs).coerceIn(0f, 1f)
            p.volume = progress
            if (progress >= 1f) {
                fadingIn = false
                p.volume = 1f
            }
            return
        }

        // Fade-out: only apply when we know the duration and there is a next track
        if (durationMs > 0 && positionMs >= 0 && p.hasNextMediaItem()) {
            val timeUntilEndMs = durationMs - positionMs
            if (timeUntilEndMs in 0..crossfadeMs) {
                fadingOut = true
                val progress = (timeUntilEndMs.toFloat() / crossfadeMs).coerceIn(0f, 1f)
                p.volume = progress
            } else if (fadingOut) {
                // Overshot (e.g. duration estimate changed) – reset
                fadingOut = false
                resetVolume()
            }
        }
    }

    /**
     * Returns true when [newItem] is the immediate successor of [oldItem] within the same album,
     * which means gapless playback should be preserved (no crossfade).
     *
     * Consecutive is defined as: same albumId, same disc, trackNumber == oldTrack + 1.
     * A disc boundary (disc N last track → disc N+1 track 1) also counts as consecutive.
     */
    private fun isConsecutiveAlbumTrack(oldItem: MediaItem?, newItem: MediaItem): Boolean {
        if (oldItem == null) return false

        val oldExtras = oldItem.mediaMetadata.extras ?: return false
        val newExtras = newItem.mediaMetadata.extras ?: return false

        val oldAlbumId = oldExtras.getString("albumId").takeIf { !it.isNullOrBlank() } ?: return false
        val newAlbumId = newExtras.getString("albumId").takeIf { !it.isNullOrBlank() } ?: return false

        if (oldAlbumId != newAlbumId) return false

        val oldTrack = oldExtras.getInt("track", 0)
        val newTrack = newExtras.getInt("track", 0)
        val oldDisc = oldExtras.getInt("discNumber", 1).let { if (it <= 0) 1 else it }
        val newDisc = newExtras.getInt("discNumber", 1).let { if (it <= 0) 1 else it }

        // Same disc, next track
        if (oldDisc == newDisc && newTrack == oldTrack + 1) return true

        // Next disc, first track (disc boundary gapless)
        if (newDisc == oldDisc + 1 && newTrack == 1) return true

        return false
    }

    private fun resetVolume() {
        player?.volume = 1f
    }

    fun release() {
        stop()
        resetVolume()
        player = null
    }
}
