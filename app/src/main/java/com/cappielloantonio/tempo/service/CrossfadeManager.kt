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
 * This class is responsible for:
 *   • Syncing [CrossfadeAudioProcessor.crossfadeDurationMs] from prefs eagerly
 *     in attach() so the processor is active from the very first configure()
 *     call, and keeping it current via tick() for mid-session pref changes.
 *   • Setting [CrossfadeAudioProcessor.crossfadeArmed] when position enters the
 *     window, AND providing a last-chance arm in onTrackTransition() in case the
 *     100 ms tick poll missed the window or isPlaying was briefly false.
 *   • Resetting the arm flag on manual seeks/skips so the processor knows the
 *     next flush is not a crossfade.
 *   • Respecting album-aware mode (no arm for consecutive same-album tracks).
 *
 * Threading: tick runs on the main looper; arm flag is @Volatile so it is
 * visible to the ExoPlayer playback thread that reads it in flush().
 *
 * Tick lifecycle:
 *   The tick keeps running as long as the player is playing OR buffering and
 *   has a next media item. Previously the tick stopped whenever isPlaying
 *   dropped to false, which meant a brief network-buffering pause inside the
 *   crossfade window could cause the arm to never be set. The tick is still
 *   stopped on a genuine pause (STATE_IDLE / STATE_ENDED or explicit stop).
 */
@UnstableApi
class CrossfadeManager(private val processor: CrossfadeAudioProcessor) {

    private var player: Player? = null

    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            // Keep ticking while there is audio playing or buffering AND a
            // next track to crossfade into. This prevents a brief buffering
            // gap from stopping the tick and missing the arm window.
            val p = player
            val keepGoing = p != null &&
                (p.isPlaying || p.playbackState == Player.STATE_BUFFERING) &&
                p.hasNextMediaItem()
            if (keepGoing) handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called when the active player changes (startup or Cast handoff).
     *
     * Syncs [CrossfadeAudioProcessor.crossfadeDurationMs] from preferences
     * immediately so that [CrossfadeAudioProcessor.isActive()] returns the
     * correct value at the very first configure() call ExoPlayer makes. Without
     * this, crossfadeDurationMs would be 0 at configure time, the processor
     * would be excluded from the pipeline, and the ring buffer would never fill
     * for the first track — making the first crossfade impossible.
     */
    fun attach(player: Player) {
        if (player !== this.player) {
            processor.crossfadeArmed = false
        }
        this.player = player
        // Eagerly push the current pref so isActive() is correct before the
        // first configure() fires. This is the fix for the lazy-init bug.
        syncProcessorDuration()
    }

    fun start() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    fun stop() {
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * Called on every track transition. For music tracks in an enabled crossfade
     * mode this provides a last-chance arm: if the 100 ms tick poll happened to
     * fire just before the crossfade window opened, or if isPlaying was briefly
     * false during the window, crossfadeArmed may still be false even though the
     * ring is full and a crossfade is appropriate. Arming here (just before
     * onFlush fires) catches those cases.
     *
     * For non-music, disabled-crossfade, or album-aware consecutive-track
     * transitions, crossfadeArmed is explicitly cleared so the flush is treated
     * as a clean reset.
     */
    fun onTrackTransition(oldItem: MediaItem?, newItem: MediaItem?) {
        val mode = Preferences.getCrossfadeMode()
        if (mode == "disabled" || newItem == null) {
            processor.crossfadeArmed = false
            return
        }
        if (mode == "album_aware" && isConsecutiveAlbumTrack(oldItem, newItem)) {
            processor.crossfadeArmed = false
            return
        }
        // Last-chance arm: tick may have missed the window due to the 100 ms
        // polling interval or a brief isPlaying=false gap during buffering.
        // onFlush() fires immediately after this callback, so arming here is
        // safe — the flag will be consumed by the correct flush.
        if (!processor.crossfadeArmed && processor.ringFill > 0) {
            processor.crossfadeArmed = true
        }
        // If crossfadeArmed was already true (set by tick()), leave it as-is.
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
        processor.crossfadeArmed      = false
        processor.crossfadeDurationMs = 0L
        player = null
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    private fun tick() {
        val p = player ?: return

        // Sync the desired duration from prefs on every tick so mid-session
        // preference changes are picked up. The new value is latched into
        // cachedDurationMs (and therefore isActive()) only at the next
        // onConfigure() or onFlush() call, so this write never causes a
        // surprise mid-stream pipeline rebuild.
        syncProcessorDuration()

        val crossfadeMs = processor.crossfadeDurationMs   // read back the value we just set

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
        val mode      = Preferences.getCrossfadeMode()
        if (mode == "album_aware" && isConsecutiveAlbumTrack(p.currentMediaItem, nextItem)) return

        // ARM. The processor's ring already holds the last crossfadeMs of audio.
        // When ExoPlayer's internal track transition fires flush(), the processor
        // will see crossfadeArmed = true and enter CROSSFADING state.
        processor.crossfadeArmed = true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Reads crossfade preferences and writes [CrossfadeAudioProcessor.crossfadeDurationMs].
     * Safe to call at any time; the processor latches the value into its active
     * state only at configure/flush boundaries.
     */
    private fun syncProcessorDuration() {
        val mode = Preferences.getCrossfadeMode()
        processor.crossfadeDurationMs =
            if (mode == "disabled") 0L
            else Preferences.getCrossfadeDurationSeconds() * 1000L
    }

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
