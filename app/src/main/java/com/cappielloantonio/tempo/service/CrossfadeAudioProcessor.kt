package com.cappielloantonio.tempo.service

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AudioProcessor that produces true overlapping crossfade between consecutive
 * tracks using a lookback ring buffer and equal-power volume curves.
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  TRADE-OFF: re-use of track A's tail                                    │
 * │                                                                         │
 * │  A delay-buffer approach (constant crossfadeMs latency on all audio)    │
 * │  avoids this issue but makes seeks noticeably sluggish. Instead, this   │
 * │  processor uses a lookback ring:                                        │
 * │                                                                         │
 * │    • DIRECT_PASSTHROUGH — A frames are output immediately AND written   │
 * │      into the ring. The ring always holds the most recent crossfadeMs   │
 * │      of audio without delaying it.                                      │
 * │                                                                         │
 * │    • CROSSFADING — on a track-transition flush(), the ring contains     │
 * │      A's unmodified tail (already heard). Those frames are re-output    │
 * │      as the fading-out component (100 % → 0 %) while B's frames fade   │
 * │      in (0 % → 100 %) simultaneously. Equal-power curves make the re-  │
 * │      use sonically transparent: the ear perceives A fading as B emerges │
 * │      rather than noticing a doubled signal.                             │
 * │                                                                         │
 * │  Result: true audio overlap, zero added latency, imperceptible echo.    │
 * └─────────────────────────────────────────────────────────────────────────┘
 *
 * State machine:
 *   DIRECT_PASSTHROUGH ──► (armed flush) ──► CROSSFADING ──► DIRECT_PASSTHROUGH
 *                    └───► (seek flush)  ──► DIRECT_PASSTHROUGH (ring cleared)
 *
 * Integration:
 *   1. Add one instance to DefaultAudioSink.Builder.setAudioProcessors().
 *   2. Pass the same instance to CrossfadeManager so it can arm the processor
 *      when position enters the crossfade window.
 *   3. CrossfadeManager sets [crossfadeDurationMs] eagerly via attach() and
 *      keeps it current via tick(); it sets [crossfadeArmed] just before the
 *      track transition flush fires.
 *
 * Supported formats: PCM_16BIT and PCM_FLOAT (interleaved, little-endian).
 * Other encodings are rejected via UnhandledAudioFormatException so ExoPlayer
 * can route around this processor (passthrough at the pipeline level).
 *
 * Threading notes:
 *   [crossfadeDurationMs] and [crossfadeArmed] are @Volatile and written from
 *   the main thread. All other state is owned by the ExoPlayer audio thread.
 *
 *   [isActive()] uses [cachedDurationMs] rather than [crossfadeDurationMs]
 *   directly. [cachedDurationMs] is only committed inside onConfigure() and
 *   onFlush() — moments where ExoPlayer already expects pipeline changes —
 *   so the active state never flips mid-stream. This prevents ExoPlayer from
 *   triggering a surprise pipeline rebuild (drain + AudioTrack restart) while
 *   it is trying to set up a gapless transition, which would otherwise cause
 *   playback to stop and leave the AudioTrack in a bad state.
 */
@UnstableApi
class CrossfadeAudioProcessor : BaseAudioProcessor() {

    // ── External controls (written from main thread, read from playback thread)

    /**
     * Desired crossfade duration in milliseconds. CrossfadeManager writes this
     * from the main thread. The value is latched into [cachedDurationMs] at the
     * next onConfigure() or onFlush() call, after which it affects [isActive()]
     * and the ring-buffer sizing. Set to 0 to disable crossfade.
     */
    @Volatile var crossfadeDurationMs: Long = 0L

    /**
     * Armed by CrossfadeManager when the current track's remaining time falls
     * within the crossfade window (or as a last-chance arm in onTrackTransition
     * if the tick missed the window). The next flush() call uses this flag to
     * decide whether to crossfade or just reset. Always reset inside onFlush().
     */
    @Volatile var crossfadeArmed: Boolean = false

    // ── Internal state ────────────────────────────────────────────────────────

    private enum class State { DIRECT_PASSTHROUGH, CROSSFADING }
    private var state = State.DIRECT_PASSTHROUGH

    /**
     * Latched copy of [crossfadeDurationMs], committed only at onConfigure()
     * and onFlush() so that [isActive()] is stable between pipeline configure
     * calls. See class-level kdoc for the rationale.
     */
    private var cachedDurationMs: Long = 0L

    /**
     * Lookback ring buffer. In DIRECT_PASSTHROUGH this is written frame-by-
     * frame with the most recent audio; it is read (not written) in CROSSFADING.
     * Capacity is set to exactly crossfadeMs of audio for the current format.
     */
    private var ring      = ByteArray(0)
    private var ringHead  = 0      // oldest frame (read start in CROSSFADING)
    private var ringTail  = 0      // next write position
    private var _ringFill = 0      // bytes currently valid in ring

    /**
     * Exposed read-only to CrossfadeManager for its last-chance arm check in
     * onTrackTransition(). Only read from the main thread after isPlaying
     * transitions, which happens-after the audio thread writes it.
     */
    internal val ringFill: Int get() = _ringFill

    // Crossfade progress counters (in sample-frames, measured from the start
    // of the CROSSFADING state).
    private var xfadeFramesTotal = 0
    private var xfadeFramesDone  = 0

    // ── BaseAudioProcessor ────────────────────────────────────────────────────

    /**
     * Accept only PCM_16BIT and PCM_FLOAT. ExoPlayer will route around this
     * processor for other encodings (e.g. passthrough, encoded audio).
     *
     * Also commits [crossfadeDurationMs] → [cachedDurationMs] so that
     * [isActive()] reflects the current preference at the moment ExoPlayer
     * evaluates the processor chain.
     */
    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        // Commit the desired duration so isActive() is correct for this
        // configure() call. ExoPlayer evaluates isActive() immediately after
        // onConfigure() to decide whether to include this processor in the
        // active chain — this is the only safe place to update cachedDurationMs
        // without risking a surprise mid-stream pipeline rebuild.
        cachedDurationMs = crossfadeDurationMs
        return inputAudioFormat   // output format = input format (in-place processing)
    }

    /**
     * isActive() is based on [cachedDurationMs] (committed at configure/flush
     * boundaries) rather than [crossfadeDurationMs] (written asynchronously by
     * the main-thread tick). This ensures ExoPlayer never sees a spontaneous
     * active-state change mid-stream that would force an unexpected AudioTrack
     * drain and restart.
     */
    override fun isActive(): Boolean = cachedDurationMs > 0L

    /**
     * Called by ExoPlayer on track transitions AND on seeks.
     *
     * Commits [crossfadeDurationMs] → [cachedDurationMs] first, so the new
     * preference takes effect for the incoming track.
     *
     * If [crossfadeArmed] is true and the ring has content: enter CROSSFADING
     * using the ring as track A's tail.
     *
     * Otherwise (seek, skip, or crossfade disabled): clear the ring and remain
     * in DIRECT_PASSTHROUGH. This produces a clean restart with zero latency.
     */
    override fun onFlush() {
        // Latch the desired duration so any preference change made since the
        // last configure/flush takes effect from the start of the new track.
        cachedDurationMs = crossfadeDurationMs

        val armed = crossfadeArmed
        crossfadeArmed = false   // always consume the arm flag

        if (armed && _ringFill > 0) {
            state = State.CROSSFADING
            val bpf = bytesPerFrame()
            xfadeFramesTotal = _ringFill / bpf
            xfadeFramesDone  = 0
        } else {
            clearRing()
            state = State.DIRECT_PASSTHROUGH
        }
    }

    override fun onReset() {
        clearRing()
        crossfadeArmed    = false
        cachedDurationMs  = 0L
        state = State.DIRECT_PASSTHROUGH
    }

    override fun queueInput(input: ByteBuffer) {
        if (!input.hasRemaining()) return

        val bpf = bytesPerFrame()
        val targetRingBytes = (cachedDurationMs *
                inputAudioFormat.sampleRate / 1000L * bpf).toInt()
        ensureRingCapacity(targetRingBytes)

        when (state) {
            State.DIRECT_PASSTHROUGH -> directPassthrough(input, bpf)
            State.CROSSFADING        -> crossfade(input, bpf)
        }
    }

    // ── DIRECT_PASSTHROUGH ────────────────────────────────────────────────────

    /**
     * Output input frames immediately (zero latency) while simultaneously
     * writing them into the lookback ring so A's tail is available for the
     * next crossfade.
     */
    private fun directPassthrough(input: ByteBuffer, bpf: Int) {
        val inBytes = frameAlign(input.remaining(), bpf)
        if (inBytes == 0) { input.position(input.limit()); return }

        val out = replaceOutputBuffer(inBytes)
        var remaining = inBytes
        while (remaining > 0) {
            val frame = ByteArray(bpf)
            input.get(frame)
            out.put(frame)
            writeRing(frame)
            remaining -= bpf
        }
    }

    // ── CROSSFADING ───────────────────────────────────────────────────────────

    /**
     * Mix track A's tail (from the lookback ring) with incoming track B frames
     * using equal-power curves:
     *
     *   gainA(t) = cos(π/2 · t)   — 1.0 at t=0, 0.0 at t=1
     *   gainB(t) = sin(π/2 · t)   — 0.0 at t=0, 1.0 at t=1
     *
     * Equal-power curves keep perceived loudness constant across the transition,
     * avoiding the mid-fade dip produced by linear crossfade.
     */
    private fun crossfade(input: ByteBuffer, bpf: Int) {
        val framesRemaining = xfadeFramesTotal - xfadeFramesDone
        val framesAvailable = min(input.remaining() / bpf, framesRemaining)
        if (framesAvailable == 0) {
            finishCrossfade()
            return
        }

        val fmt = inputAudioFormat
        val bps = bytesPerSample()
        val out = replaceOutputBuffer(framesAvailable * bpf)

        for (i in 0 until framesAvailable) {
            val t = (xfadeFramesDone + i).toDouble() / xfadeFramesTotal
            val gainA = cos(PI / 2.0 * t).toFloat()
            val gainB = sin(PI / 2.0 * t).toFloat()

            for (ch in 0 until fmt.channelCount) {
                if (bps == 2) {
                    val sA = readRingShortLE()
                    val sB = input.getShortLE()
                    val mixed = (sA * gainA + sB * gainB)
                        .toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                    out.putShortLE(mixed)
                } else {
                    // PCM_FLOAT
                    val fA = readRingFloatLE()
                    val fB = input.getFloatLE()
                    out.putFloatLE((fA * gainA + fB * gainB).coerceIn(-1f, 1f))
                }
            }
        }

        xfadeFramesDone += framesAvailable
        if (xfadeFramesDone >= xfadeFramesTotal) finishCrossfade()
    }

    private fun finishCrossfade() {
        clearRing()
        state = State.DIRECT_PASSTHROUGH
    }

    // ── Ring buffer helpers ───────────────────────────────────────────────────

    private fun writeRing(frame: ByteArray) {
        val cap = ring.size
        if (cap == 0) return
        for (b in frame) {
            ring[ringTail] = b
            ringTail = (ringTail + 1) % cap
            if (_ringFill < cap) {
                _ringFill++
            } else {
                // Ring is full: overwrite oldest byte, advance head
                ringHead = (ringHead + 1) % cap
            }
        }
    }

    private fun readRingShortLE(): Float {
        val lo = ring[ringHead].toInt() and 0xFF
        ringHead = (ringHead + 1) % ring.size
        val hi = ring[ringHead].toInt()
        ringHead = (ringHead + 1) % ring.size
        _ringFill -= 2
        return ((hi shl 8) or lo).toShort().toFloat()
    }

    private fun readRingFloatLE(): Float {
        val bytes = ByteArray(4) {
            val b = ring[ringHead]
            ringHead = (ringHead + 1) % ring.size
            _ringFill--
            b
        }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun clearRing() {
        ringHead = 0; ringTail = 0; _ringFill = 0
        xfadeFramesTotal = 0; xfadeFramesDone = 0
    }

    /**
     * Grows the ring buffer if needed, preserving existing content.
     * Shrinks are ignored (we keep the larger allocation to avoid GC churn).
     */
    private fun ensureRingCapacity(needed: Int) {
        if (needed <= ring.size) return
        val newRing = ByteArray(needed)
        if (_ringFill > 0) {
            // Copy existing content in insertion order (head → tail)
            val toHead = min(_ringFill, ring.size - ringHead)
            ring.copyInto(newRing, 0, ringHead, ringHead + toHead)
            if (toHead < _ringFill) {
                ring.copyInto(newRing, toHead, 0, _ringFill - toHead)
            }
        }
        ring     = newRing
        ringHead = 0
        ringTail = _ringFill % newRing.size
    }

    // ── Format helpers ────────────────────────────────────────────────────────

    private fun bytesPerSample(): Int = when (inputAudioFormat.encoding) {
        C.ENCODING_PCM_FLOAT  -> 4
        else                  -> 2   // PCM_16BIT
    }

    private fun bytesPerFrame(): Int = inputAudioFormat.channelCount * bytesPerSample()

    /** Round byte count down to the nearest frame boundary. */
    private fun frameAlign(bytes: Int, bpf: Int): Int = (bytes / bpf) * bpf

    // ── ByteBuffer extensions (little-endian I/O without reordering overhead)

    private fun ByteBuffer.getShortLE(): Float {
        val lo = get().toInt() and 0xFF
        val hi = get().toInt()
        return ((hi shl 8) or lo).toShort().toFloat()
    }

    private fun ByteBuffer.putShortLE(s: Short) {
        put((s.toInt() and 0xFF).toByte())
        put((s.toInt() shr 8).toByte())
    }

    private fun ByteBuffer.getFloatLE(): Float {
        val bytes = ByteArray(4) { get() }
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun ByteBuffer.putFloatLE(f: Float) {
        val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putFloat(f)
        put(bb.array())
    }
}
