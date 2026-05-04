package com.cappielloantonio.tempo.util;

import android.os.Handler;
import android.os.Looper;

/**
 * Singleton that manages a sleep timer countdown.
 *
 * The timer survives fragment recreation (e.g. rotation) because it lives
 * in a singleton. Callers reconnect their tick/expiry logic by calling
 * {@link #setTickListener} on resume and clearing it on stop.
 */
public class SleepTimerManager {

    public interface TickListener {
        /**
         * Called on the main thread every second while the timer is active,
         * and once more (with expired=true) when the countdown reaches zero.
         */
        void onTick(boolean expired);
    }

    private static SleepTimerManager instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable scheduledTick;

    private long endTimeMs = 0;
    private boolean active = false;

    private TickListener tickListener;

    private SleepTimerManager() {}

    public static SleepTimerManager getInstance() {
        if (instance == null) {
            instance = new SleepTimerManager();
        }
        return instance;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Start (or restart) the timer for the given number of minutes. */
    public void startTimer(int minutes) {
        cancelInternal(false);
        endTimeMs = System.currentTimeMillis() + (long) minutes * 60 * 1000;
        active = true;
        scheduleNextTick();
    }

    /**
     * Cancel the timer and notify the listener so the UI resets.
     * Safe to call even when no timer is running.
     */
    public void cancelTimer() {
        cancelInternal(true);
    }

    /** Whether a countdown is currently running. */
    public boolean isActive() {
        return active;
    }

    /** Remaining time formatted as "MM:SS". Returns "0:00" when inactive. */
    public String getRemainingFormatted() {
        long ms = getRemainingMs();
        long minutes = ms / 60_000;
        long seconds = (ms % 60_000) / 1000;
        return String.format("%d:%02d", minutes, seconds);
    }

    /**
     * Attach a listener that receives ticks and the expiry event.
     * Pass {@code null} to disconnect (do this in onStop to avoid leaks).
     * Immediately fires {@link TickListener#onTick(boolean)} with the current
     * state so the UI can sync right away.
     */
    public void setTickListener(TickListener listener) {
        this.tickListener = listener;
        if (listener != null) {
            // Fire immediately so the caller can sync its UI.
            listener.onTick(false);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private long getRemainingMs() {
        if (!active) return 0;
        return Math.max(0, endTimeMs - System.currentTimeMillis());
    }

    private void cancelInternal(boolean notifyListener) {
        active = false;
        endTimeMs = 0;
        if (scheduledTick != null) {
            handler.removeCallbacks(scheduledTick);
            scheduledTick = null;
        }
        if (notifyListener && tickListener != null) {
            tickListener.onTick(false); // Let the UI reset (expired=false so player keeps playing)
        }
    }

    private void scheduleNextTick() {
        scheduledTick = () -> {
            if (!active) return;

            long remaining = getRemainingMs();
            if (remaining <= 0) {
                active = false;
                scheduledTick = null;
                if (tickListener != null) {
                    tickListener.onTick(true); // expired — caller should pause playback
                }
            } else {
                if (tickListener != null) {
                    tickListener.onTick(false);
                }
                scheduleNextTick();
            }
        };
        handler.postDelayed(scheduledTick, 1000);
    }
}
