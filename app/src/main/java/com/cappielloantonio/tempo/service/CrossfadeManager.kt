package com.cappielloantonio.tempo.service

import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.util.Preferences

private const val TICK_INTERVAL_MS = 100L

/**
 * Position monitor that arms [CrossfadeAudioProcessor] when the current track
 * enters its crossfade window.
 *
 * The actual audio overlap is produced entirely inside [CrossfadeAudioProcessor]:
 * it maintains a lookback ring of the last [crossfadeDurationMs] of audio and,
 * on the track-transition flush() that immediately follows, mixes that tail with
 * the incoming track B frames using equal-power curves.
 *
 * This class is responsible only for:
 *   • Keeping [CrossfadeAudioProcessor.crossfadeDurationMs] in sync with prefs.
 *   • Setting [CrossfadeAudioProcessor.crossfadeArmed] when position enters the
 *     window (and keeping it armed until the flush fires or a skip cancels it).
 *   • Resetting the arm flag on manual seeks/skips so the processor knows the
 *     next flush is not a crossfade.
 *   • Respecting album-aware mode (no arm for consecutive same-album tracks).
 *
 * Threading: tick runs on the main looper; arm flag is @Volatile so it is
 * visible to the ExoPlayer playback thread that reads it in flush().
 */
@UnstableApi
class CrossfadeManager(private val processor: CrossfadeAudioProcessor) {

    private var player: Player? = null

    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            if (player?.isPlaying == true) handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun attach(player: Player) {
        if (player !== this.player) {
            processor.crossfadeArmed = false
        }
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
     * Called on every track transition. For music tracks we do nothing here —
     * the arm flag (set in tick) and the processor flush() handle the crossfade.
     * For non-music or disabled-crossfade transitions, we disarm so the flush
     * is treated as a clean reset.
     */
    fun onTrackTransition(oldItem: MediaItem?, newItem: MediaItem?) {
        val mode = Preferences.getCrossfadeMode()
        if (mode == "disabled" || newItem == null) {
            processor.crossfadeArmed = false
            return
        }
        if (mode == "album_aware" && isConsecutiveAlbumTrack(oldItem, newItem)) {
            processor.crossfadeArmed = false
        }
        // Otherwise: arm flag was set by tick(); leave it for the flush to consume.
    }

    /**
     * Called on user-initiated seek or skip. Disarms the processor so the
     * resulting flush() is treated as a clean restart rather than a crossfade.
     */
    fun onSeekOrSkip() {
        processor.crossfadeArmed = false
    }

    /**
     * Called when playback stops or pauses. Stop the tick; the arm flag is
     * preserved so a resume mid-window can still fire the crossfade.
     */
    fun onPlaybackStopped() {
        stop()
    }

    fun release() {
        stop()
        processor.crossfadeArmed  = false
        processor.crossfadeDurationMs = 0L
        player = null
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private fun tick() {
        val p = player ?: return

        val mode = Preferences.getCrossfadeMode()
        val crossfadeMs = if (mode == "disabled") 0L
                          else Preferences.getCrossfadeDurationSeconds() * 1000L

        // Keep the processor's duration in sync (change takes effect on next configure()).
        processor.crossfadeDurationMs = crossfadeMs

        if (crossfadeMs <= 0L || !p.isPlaying || !p.hasNextMediaItem()) {
            // Nothing to arm: disarm in case prefs changed mid-window.
            if (crossfadeMs <= 0L) processor.crossfadeArmed = false
            return
        }

        // Already armed for this window — no need to re-check.
        if (processor.crossfadeArmed) return

        val durationMs = effectiveDuration(p)
        if (durationMs == C.TIME_UNSET || durationMs <= 0L) return

        val remaining = durationMs - p.currentPosition
        if (remaining !in 0L..crossfadeMs) return

        // Verify we should actually crossfade this boundary.
        val nextIndex = p.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val nextItem  = p.getMediaItemAt(nextIndex)
        if (mode == "album_aware" && isConsecutiveAlbumTrack(p.currentMediaItem, nextItem)) return

        // ARM. The processor's ring already holds the last crossfadeMs of audio.
        // When ExoPlayer's internal track transition fires flush(), the processor
        // will see crossfadeArmed = true and enter CROSSFADING state.
        processor.crossfadeArmed = true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun effectiveDuration(player: Player): Long {
        val d = player.duration
        if (d != C.TIME_UNSET && d > 0L) return d
        val sec = player.currentMediaItem?.mediaMetadata?.extras?.getInt("duration", 0) ?: 0
        return if (sec > 0) sec * 1000L else C.TIME_UNSET
    }

    internal fun isConsecutiveAlbumTrack(oldItem: MediaItem?, newItem: MediaItem?): Boolean {
        if (oldItem == null || newItem == null) return false
        val oldE = oldItem.mediaMetadata.extras ?: return false
        val newE = newItem.mediaMetadata.extras ?: return false
        val oldAlbum = oldE.getString("albumId").takeIf { !it.isNullOrBlank() } ?: return false
        val newAlbum = newE.getString("albumId").takeIf { !it.isNullOrBlank() } ?: return false
        if (oldAlbum != newAlbum) return false
        val oldTrack = oldE.getInt("track", 0)
        val newTrack = newE.getInt("track", 0)
        val oldDisc = oldE.getInt("discNumber", 1).let { if (it <= 0) 1 else it }
        val newDisc = newE.getInt("discNumber", 1).let { if (it <= 0) 1 else it }
        if (oldDisc == newDisc && newTrack == oldTrack + 1) return true
        if (newDisc == oldDisc + 1 && newTrack == 1) return true
        return false
    }
}
