package com.cappielloantonio.tempo.service

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.cappielloantonio.tempo.util.Preferences

private const val TICK_INTERVAL_MS = 100L

/**
 * Manages audio crossfade between two ExoPlayer instances.
 *
 * Two-player crossfade (mode != "disabled"):
 *  ┌─────────────────────────────────────────────────────────────────────┐
 *  │ IDLE ──► PREPARING ──► CROSSFADING ──► COMPLETING ──► IDLE (reset) │
 *  └─────────────────────────────────────────────────────────────────────┘
 *
 *  IDLE        Normal playback; tick watches for the trigger window.
 *  PREPARING   Secondary player has been started; waiting for it to begin
 *              producing audio. Primary fades using remaining-time ratio.
 *  CROSSFADING Both players are audible. Primary volume ramps 1→0 and
 *              secondary volume ramps 0→1 over [crossfadeMs] milliseconds,
 *              measured in real wall-clock time (immune to seek/position jumps).
 *  COMPLETING  Primary has been silenced and stopped; [onCrossfadeComplete]
 *              has been fired. Tick idles until [notifyHandoffComplete] is
 *              called by BaseMediaService to reset state.
 *
 * Single-player fade-in fallback (fadingIn flag):
 *  Used after a manual skip or when the two-player crossfade was not triggered
 *  (e.g. very short track, no next item). Fades the primary from 0→1 using the
 *  player's current position as a proxy for elapsed time.
 *
 * Queue contract:
 *  When the trigger window is reached, all media items from [nextMediaItemIndex]
 *  onward are removed from the primary player (preventing an auto-transition to
 *  the same track that secondary is starting) and captured in
 *  [capturedRemainingQueue]. That list is delivered to [onCrossfadeComplete] so
 *  BaseMediaService can transplant it onto the secondary before promoting it.
 */
@UnstableApi
class CrossfadeManager {

    // ── Active players ────────────────────────────────────────────────────────

    /** The session's current output player. Set via [attach]. */
    private var primaryPlayer: Player? = null

    /** The incoming player that will overlap and then replace the primary. */
    private var secondaryPlayer: ExoPlayer? = null

    /** Minimal error-watching listener installed on secondary during crossfade. */
    private var secondaryListener: Player.Listener? = null

    /**
     * Queue items captured from the primary at trigger time, beginning with the
     * item secondary is about to play. Delivered to [onCrossfadeComplete].
     */
    private var capturedRemainingQueue: List<MediaItem> = emptyList()

    // ── Configuration (wired up by BaseMediaService) ──────────────────────────

    /**
     * Creates or reclaims an ExoPlayer to use as the secondary.
     * BaseMediaService cycles the same instance to avoid repeated allocation.
     */
    var secondaryPlayerFactory: (() -> ExoPlayer)? = null

    /**
     * Called on the main thread once the crossfade volume ramp completes.
     * [secondary] is fully faded in and ready to become the session player.
     * [remaining] is the full queue tail captured from the primary, where
     * index 0 is the item secondary is already playing and indices 1..N are
     * the items that must be appended to secondary's queue before it is promoted.
     *
     * BaseMediaService must call [notifyHandoffComplete] synchronously inside
     * this callback so CrossfadeManager can clean up its internal state.
     */
    var onCrossfadeComplete: ((secondary: ExoPlayer, remaining: List<MediaItem>) -> Unit)? = null

    // ── State ─────────────────────────────────────────────────────────────────

    /** True while fading the primary in from silence (single-player fallback). */
    private var fadingIn = false

    private enum class State { IDLE, PREPARING, CROSSFADING, COMPLETING }
    private var state = State.IDLE

    /**
     * Wall-clock elapsed time since secondary began producing audio, used to
     * drive the volume ramp independently of ExoPlayer's position reporting.
     */
    private var crossfadeElapsedMs = 0L
    private var lastTickRealtime = 0L

    // ── Tick ──────────────────────────────────────────────────────────────────

    private val handler = Handler(Looper.getMainLooper())

    private val tickRunnable = object : Runnable {
        override fun run() {
            tick()
            val keepGoing = primaryPlayer?.isPlaying == true
                || secondaryPlayer?.isPlaying == true
                || state != State.IDLE
                || fadingIn
            if (keepGoing) handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Sets the active primary player. Aborts any in-progress crossfade if the
     * player reference changes (e.g. Cast handoff).
     */
    fun attach(player: Player) {
        if (player !== primaryPlayer && state != State.IDLE) abortCrossfade()
        primaryPlayer = player
    }

    /** Ensures the tick loop is running. Safe to call redundantly. */
    fun start() {
        handler.removeCallbacks(tickRunnable)
        handler.postDelayed(tickRunnable, TICK_INTERVAL_MS)
    }

    /** Pauses the tick loop without resetting crossfade state. */
    fun stop() {
        handler.removeCallbacks(tickRunnable)
    }

    /**
     * Returns true while a two-player crossfade overlap is active or completing.
     * Used by BaseMediaService to suppress duplicate handling in listener
     * callbacks that fire during the handoff.
     */
    fun isCrossfadeActive(): Boolean =
        state == State.CROSSFADING || state == State.COMPLETING

    /**
     * Called when the primary player transitions to a new item via a mechanism
     * other than the two-player crossfade (manual skip, seek, first-play, etc.).
     * Triggers a single-player fade-in from silence as a fallback.
     */
    fun onTrackTransition(oldItem: MediaItem?, newItem: MediaItem?) {
        val mode = Preferences.getCrossfadeMode()
        if (mode == "disabled" || newItem == null) {
            abortCrossfade(); fadingIn = false; resetPrimaryVolume(); return
        }
        if (mode == "album_aware" && isConsecutiveAlbumTrack(oldItem, newItem)) {
            abortCrossfade(); fadingIn = false; resetPrimaryVolume(); return
        }
        // Two-player handoff is completing; nothing more to do.
        if (state == State.COMPLETING) return
        // Single-player fade-in from silence.
        primaryPlayer?.volume = 0f
        fadingIn = true
        start()
    }

    /**
     * Called when playback stops or pauses. Aborts any active crossfade unless
     * the primary was intentionally silenced by the crossfade machinery itself.
     */
    fun onPlaybackStopped() {
        stop()
        // CROSSFADING / COMPLETING: primary was stopped by us; do not abort.
        if (state == State.CROSSFADING || state == State.COMPLETING) return
        abortCrossfade(); fadingIn = false; resetPrimaryVolume()
    }

    /**
     * Called on a manual seek or skip so the fade state is reset and the
     * next [onTrackTransition] starts a clean single-player fade-in.
     */
    fun onSeekOrSkip() {
        abortCrossfade(); fadingIn = false; resetPrimaryVolume()
    }

    /**
     * Must be called by BaseMediaService synchronously inside [onCrossfadeComplete]
     * after it has transplanted the queue onto the secondary and switched the
     * session player. Resets CrossfadeManager's internal state to IDLE.
     */
    fun notifyHandoffComplete() {
        state = State.IDLE
        crossfadeElapsedMs = 0L
        secondaryPlayer = null        // ownership transferred to BaseMediaService
        secondaryListener = null
        capturedRemainingQueue = emptyList()
        fadingIn = false
    }

    fun release() {
        stop()
        abortCrossfade()
        resetPrimaryVolume()
        primaryPlayer = null
        onCrossfadeComplete = null
        secondaryPlayerFactory = null
    }

    // ── Tick implementation ───────────────────────────────────────────────────

    private fun tick() {
        val primary = primaryPlayer ?: return
        val mode = Preferences.getCrossfadeMode()

        if (mode == "disabled") {
            if (fadingIn || state != State.IDLE) { abortCrossfade(); fadingIn = false; resetPrimaryVolume() }
            return
        }

        val crossfadeMs = Preferences.getCrossfadeDurationSeconds() * 1000L
        if (crossfadeMs <= 0) {
            if (state != State.IDLE) abortCrossfade()
            resetPrimaryVolume(); return
        }

        // ── Single-player fade-in fallback ────────────────────────────────────
        if (fadingIn && state == State.IDLE) {
            if (!primary.isPlaying) return
            val pos = primary.currentPosition.takeIf { it >= 0 } ?: return
            val progress = (pos.toFloat() / crossfadeMs).coerceIn(0f, 1f)
            primary.volume = progress
            if (progress >= 1f) { fadingIn = false; primary.volume = 1f }
            return
        }

        // ── Two-player state machine ──────────────────────────────────────────
        when (state) {

            State.IDLE -> {
                if (primary.isPlaying) maybeStartCrossfade(primary, crossfadeMs, mode)
            }

            State.PREPARING -> {
                val secondary = secondaryPlayer ?: run { state = State.IDLE; return }
                if (secondary.playbackState == Player.STATE_ERROR) { abortCrossfade(); return }

                // Keep fading primary proportionally while secondary buffers.
                applyPrimaryFadeFromRemaining(primary, crossfadeMs)

                // Advance to CROSSFADING once secondary is producing audio.
                if (secondary.isPlaying) {
                    // Seed elapsed time so the ramp is continuous with the
                    // proportional fade already applied during PREPARING.
                    val durationMs = effectiveDuration(primary)
                    val remaining = if (durationMs != C.TIME_UNSET)
                        (durationMs - primary.currentPosition).coerceAtLeast(0L)
                    else 0L
                    crossfadeElapsedMs = (crossfadeMs - remaining).coerceAtLeast(0L)
                    lastTickRealtime = SystemClock.elapsedRealtime()
                    state = State.CROSSFADING
                }
            }

            State.CROSSFADING -> {
                val secondary = secondaryPlayer ?: run { abortCrossfade(); return }

                // Accumulate real wall-clock time; immune to position jumps.
                val now = SystemClock.elapsedRealtime()
                crossfadeElapsedMs += (now - lastTickRealtime)
                lastTickRealtime = now

                val progress = (crossfadeElapsedMs.toFloat() / crossfadeMs).coerceIn(0f, 1f)
                primary.volume = 1f - progress
                secondary.volume = progress

                if (progress >= 1f) {
                    primary.volume = 0f
                    secondary.volume = 1f
                    state = State.COMPLETING

                    // Stop the primary — volume is 0 so there is no audible impact.
                    primary.stop()

                    // Remove minimal listener before handing secondary off.
                    secondaryListener?.let { secondary.removeListener(it) }
                    secondaryListener = null

                    val remaining = capturedRemainingQueue
                    capturedRemainingQueue = emptyList()

                    // BaseMediaService must call notifyHandoffComplete() inside this callback.
                    onCrossfadeComplete?.invoke(secondary, remaining)
                }
            }

            State.COMPLETING -> {
                // BaseMediaService is processing the handoff; tick keeps running
                // so keepGoing stays true and the loop doesn't die prematurely.
            }
        }
    }

    // ── Crossfade trigger ─────────────────────────────────────────────────────

    private fun maybeStartCrossfade(primary: Player, crossfadeMs: Long, mode: String) {
        if (secondaryPlayerFactory == null || !primary.hasNextMediaItem()) return

        val positionMs = primary.currentPosition.takeIf { it >= 0 } ?: return
        val durationMs = effectiveDuration(primary).takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val timeUntilEndMs = durationMs - positionMs
        if (timeUntilEndMs > crossfadeMs || timeUntilEndMs < 0) return

        val nextIndex = primary.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET } ?: return
        val nextItem = primary.getMediaItemAt(nextIndex)

        if (mode == "album_aware" && isConsecutiveAlbumTrack(primary.currentMediaItem, nextItem)) return

        // Capture the full queue tail before we mutate the primary.
        val remaining = (nextIndex until primary.mediaItemCount).map { primary.getMediaItemAt(it) }

        // Remove items from nextIndex onwards so the primary ends naturally at
        // STATE_ENDED rather than auto-transitioning to the track that secondary
        // is already starting. The onPlaybackStateChanged(STATE_ENDED) path in
        // BaseMediaService handles the scrobble for this final-item scenario.
        for (i in primary.mediaItemCount - 1 downTo nextIndex) {
            primary.removeMediaItem(i)
        }

        capturedRemainingQueue = remaining
        startSecondary(nextItem)
    }

    private fun startSecondary(nextItem: MediaItem) {
        val factory = secondaryPlayerFactory ?: return
        state = State.PREPARING

        val secondary = try {
            factory()
        } catch (_: Exception) {
            state = State.IDLE
            return
        }

        // Remove any stale minimal listener left from a previous crossfade cycle.
        secondaryListener?.let { secondary.removeListener(it) }

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ERROR) handler.post { abortCrossfade() }
            }
        }
        secondaryListener = listener
        secondary.addListener(listener)

        secondary.volume = 0f
        secondary.setMediaItem(nextItem)
        secondary.prepare()
        secondary.playWhenReady = true
        secondaryPlayer = secondary
        lastTickRealtime = SystemClock.elapsedRealtime()
        crossfadeElapsedMs = 0L
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Fades the primary volume proportionally to the remaining time in the
     * track. Used during PREPARING while the secondary buffers, so the listener
     * hears a smooth fade-out even if buffering takes a moment.
     */
    private fun applyPrimaryFadeFromRemaining(primary: Player, crossfadeMs: Long) {
        val durationMs = effectiveDuration(primary).takeIf { it != C.TIME_UNSET } ?: return
        val remaining = (durationMs - primary.currentPosition).coerceAtLeast(0L)
        primary.volume = (remaining.toFloat() / crossfadeMs).coerceIn(0f, 1f)
    }

    /**
     * Cancels any in-progress crossfade, stops and clears the secondary player
     * (returning it to a reusable idle state), and resets all state flags.
     * Does NOT call ExoPlayer.release() — lifetime management stays with
     * BaseMediaService so the instance can be recycled as the next secondary.
     */
    private fun abortCrossfade() {
        state = State.IDLE
        crossfadeElapsedMs = 0L
        fadingIn = false
        capturedRemainingQueue = emptyList()

        val secondary = secondaryPlayer ?: return
        secondaryListener?.let { secondary.removeListener(it) }
        secondaryListener = null
        secondary.stop()
        secondary.clearMediaItems()
        secondaryPlayer = null

        resetPrimaryVolume()
    }

    private fun resetPrimaryVolume() {
        primaryPlayer?.volume = 1f
    }

    /**
     * Returns the track duration in milliseconds, falling back to the Subsonic
     * metadata "duration" extra (in seconds) when ExoPlayer hasn't yet parsed
     * the bitstream header (common for streaming tracks at start of playback).
     */
    private fun effectiveDuration(player: Player): Long {
        val d = player.duration
        if (d != C.TIME_UNSET && d > 0) return d
        val sec = player.currentMediaItem?.mediaMetadata?.extras?.getInt("duration", 0) ?: 0
        return if (sec > 0) sec * 1000L else C.TIME_UNSET
    }

    /**
     * Returns true when [newItem] is the immediate successor of [oldItem] within
     * the same album (same albumId, consecutive track number, allowing disc
     * boundaries). Used by album-aware mode to preserve gapless playback.
     */
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
