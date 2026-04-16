package com.cappielloantonio.tempo.util;

import android.os.Handler;
import android.os.Looper;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.MetadataRetriever;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.extractor.metadata.id3.InternalFrame;
import androidx.media3.extractor.metadata.id3.TextInformationFrame;

import com.cappielloantonio.tempo.App;
import com.cappielloantonio.tempo.model.ReplayGain;
import com.cappielloantonio.tempo.model.ReplayGainCache;
import com.cappielloantonio.tempo.repository.ReplayGainCacheRepository;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class ReplayGainUtil {
    private static final String TAG = "ReplayGainUtil";
    private static final String[] tags = {
        "REPLAYGAIN_TRACK_GAIN", "REPLAYGAIN_ALBUM_GAIN",
        "R128_TRACK_GAIN", "R128_ALBUM_GAIN",
        "REPLAYGAIN_TRACK_PEAK", "REPLAYGAIN_ALBUM_PEAK"
    };

    private static final ConcurrentHashMap<String, List<ReplayGain>> gainDataMap =
            new ConcurrentHashMap<>();

    private static final Set<String> prefetchedIds = ConcurrentHashMap.newKeySet();
    private static final ReplayGainCacheRepository replayGainCacheRepository =
            new ReplayGainCacheRepository();

    private static final ExecutorService prefetchExecutor =
            Executors.newFixedThreadPool(2);

    // Audio processor that applies gain directly to PCM samples inside
    // ExoPlayer's audio pipeline.  Unlike player.setVolume() this is
    // sample-accurate across gapless transitions.
    private static final ReplayGainAudioProcessor audioProcessor =
            new ReplayGainAudioProcessor();

    private static volatile WeakReference<Player> playerRef = new WeakReference<>(null);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static ReplayGainAudioProcessor getAudioProcessor() {
        return audioProcessor;
    }

    public static void release() {
        gainDataMap.clear();
        prefetchedIds.clear();
        playerRef = new WeakReference<>(null);
    }

    public static void prefetchQueueGains(Player player) {
        if (Objects.equals(Preferences.getReplayGainMode(), "disabled")) return;

        playerRef = new WeakReference<>(player);
        seedFromPersistentCache(player);

        for (int i = 0; i < player.getMediaItemCount(); i++) {
            MediaItem item = player.getMediaItemAt(i);

            if (item.mediaId == null) continue;

            String mediaType = item.mediaMetadata.extras != null
                    ? item.mediaMetadata.extras.getString("type") : null;
            if (Constants.MEDIA_TYPE_RADIO.equals(mediaType)) continue;
            if (item.mediaId.startsWith("ir-")) continue;

            if (primeGainFromItemExtras(item)) continue;

            if (item.localConfiguration == null) continue;

            if (!prefetchedIds.add(item.mediaId)) continue;

            submitPrefetch(item);
        }
    }

    private static boolean primeGainFromItemExtras(MediaItem item) {
        if (item == null || item.mediaId == null) return false;

        Bundle extras = item.mediaMetadata.extras != null
                ? item.mediaMetadata.extras
                : item.requestMetadata.extras;
        if (extras == null) return false;

        if (!extras.containsKey("replayGainTrackGain")
                && !extras.containsKey("replayGainTrackPeak")
                && !extras.containsKey("replayGainAlbumGain")
                && !extras.containsKey("replayGainAlbumPeak")) {
            return false;
        }

        ReplayGain openSubsonicGains = new ReplayGain();
        if (extras.containsKey("replayGainTrackGain"))
            openSubsonicGains.setTrackGain(extras.getFloat("replayGainTrackGain"));
        if (extras.containsKey("replayGainTrackPeak"))
            openSubsonicGains.setTrackPeak(extras.getFloat("replayGainTrackPeak"));
        if (extras.containsKey("replayGainAlbumGain"))
            openSubsonicGains.setAlbumGain(extras.getFloat("replayGainAlbumGain"));
        if (extras.containsKey("replayGainAlbumPeak"))
            openSubsonicGains.setAlbumPeak(extras.getFloat("replayGainAlbumPeak"));

        List<ReplayGain> gains = new ArrayList<>();
        gains.add(openSubsonicGains);
        gains.add(new ReplayGain());
        gainDataMap.put(item.mediaId, gains);
        prefetchedIds.add(item.mediaId);
        persistGains(item.mediaId, gains);

        Log.d(TAG, "Primed gain from OpenSubsonic extras for " + item.mediaId
                + " trackGain=" + openSubsonicGains.getTrackGain());
        return true;
    }

    private static void submitPrefetch(MediaItem item) {
        prefetchExecutor.execute(() -> {
            try (MetadataRetriever retriever =
                         new MetadataRetriever.Builder(App.getInstance(), item).build()) {

                TrackGroupArray trackGroups =
                        retriever.retrieveTrackGroups().get(20,
                                java.util.concurrent.TimeUnit.SECONDS);

                List<Metadata> metadataList = extractMetadata(trackGroups);
                List<ReplayGain> gains = getReplayGains(metadataList);
                gainDataMap.put(item.mediaId, gains);
                persistGains(item.mediaId, gains);
                Log.d(TAG, "Prefetched " + item.mediaId
                        + " trackGain=" + resolveTrackGain(gains));

                // Post back to the main thread.  Two things can happen:
                //  1. If the prefetched item is the CURRENT playing track
                //     (prefetch finished AFTER the transition to it already
                //     happened, which is common on first play with a cold
                //     network), apply its gain immediately.  This corrects
                //     the audio without waiting for onTracksChanged.
                //  2. Queue the pending gain for the next gapless transition.
                mainHandler.post(() -> {
                    Player p = playerRef.get();
                    if (p == null) return;

                    MediaItem current = p.getCurrentMediaItem();
                    if (current != null && item.mediaId.equals(current.mediaId)) {
                        float gain = resolveGain(p, gains);
                        float peak = resolvePeak(p, gains);
                        float totalGain = computeTotalGain(gain, peak);
                        Log.d(TAG, "Late prefetch for current track " + item.mediaId
                                + " — applying gain immediately totalGain=" + totalGain);
                        audioProcessor.setGainImmediate(totalGain);
                    }

                    queuePendingForNextTrack(p);
                });

            } catch (Throwable e) {
                Log.d(TAG, "Prefetch failed for " + item.mediaId + ": " + e);
                prefetchedIds.remove(item.mediaId);
            }
        });
    }

    public static void applyGain(Player player, MediaItem mediaItem) {
        audioProcessor.clearPendingGain();

        if (mediaItem == null || mediaItem.mediaId == null) {
            Log.d(TAG, "applyGain: null mediaItem or mediaId, skipping");
            return;
        }

        List<ReplayGain> gains = gainDataMap.get(mediaItem.mediaId);
        if (gains == null && primeGainFromItemExtras(mediaItem)) {
            gains = gainDataMap.get(mediaItem.mediaId);
        }
        if (gains != null) {
            float gain = resolveGain(player, gains);
            float peak = resolvePeak(player, gains);
            float totalGain = computeTotalGain(gain, peak);
            Log.d(TAG, "applyGain: cache hit for " + mediaItem.mediaId
                    + " gain=" + gain + " peak=" + peak
                    + " totalGain=" + totalGain);
            audioProcessor.setGainImmediate(totalGain);
        } else {
            Log.d(TAG, "applyGain: cache miss for " + mediaItem.mediaId
                    + ", applying 0 dB fallback until onTracksChanged");
            audioProcessor.setGainImmediate(computeTotalGain(0f, 0f));
        }

        queuePendingForNextTrack(player);
    }

    public static void setReplayGain(Player player, Tracks tracks) {
        if (tracks == null || tracks.getGroups().isEmpty()) return;

        List<Metadata> metadataList = extractMetadata(tracks);
        List<ReplayGain> gains = getReplayGains(metadataList);

        MediaItem currentItem = player.getCurrentMediaItem();
        if (currentItem != null && currentItem.mediaId != null) {
            gainDataMap.put(currentItem.mediaId, gains);
            prefetchedIds.add(currentItem.mediaId);
            persistGains(currentItem.mediaId, gains);
        }

        float gain = resolveGain(player, gains);
        float peak = resolvePeak(player, gains);
        audioProcessor.setGainImmediate(computeTotalGain(gain, peak));

        queuePendingForNextTrack(player);
    }

    private static void queuePendingForNextTrack(Player player) {
        int nextIndex = player.getNextMediaItemIndex();
        if (nextIndex == C.INDEX_UNSET) return;
        MediaItem nextItem = player.getMediaItemAt(nextIndex);
        if (nextItem == null || nextItem.mediaId == null) return;

        List<ReplayGain> gains = gainDataMap.get(nextItem.mediaId);
        if (gains == null && primeGainFromItemExtras(nextItem)) {
            gains = gainDataMap.get(nextItem.mediaId);
        }

        if (gains == null) {
            // Ensure first samples of the next track do not inherit previous-track gain
            // when we still don't have ReplayGain metadata at stream boundary.
            audioProcessor.setPendingGain(computeTotalGain(0f, 0f));
            return;
        }

        float totalGain = computeTotalGain(
                resolveGainForNextTrack(player, gains),
                resolvePeakForNextTrack(player, gains));
        audioProcessor.setPendingGain(totalGain);
    }

    private static List<Metadata> extractMetadata(Tracks tracks) {
        List<Metadata> result = new ArrayList<>();
        if (tracks == null) return result;
        for (int i = 0; i < tracks.getGroups().size(); i++) {
            Tracks.Group group = tracks.getGroups().get(i);
            if (group == null || group.getMediaTrackGroup() == null) continue;
            for (int j = 0; j < group.getMediaTrackGroup().length; j++) {
                Metadata m = group.getTrackFormat(j).metadata;
                if (m != null) result.add(m);
            }
        }
        return result;
    }

    private static List<Metadata> extractMetadata(TrackGroupArray trackGroups) {
        List<Metadata> result = new ArrayList<>();
        if (trackGroups == null) return result;
        for (int i = 0; i < trackGroups.length; i++) {
            TrackGroup group = trackGroups.get(i);
            if (group == null) continue;
            for (int j = 0; j < group.length; j++) {
                Metadata m = group.getFormat(j).metadata;
                if (m != null) result.add(m);
            }
        }
        return result;
    }

    private static List<ReplayGain> getReplayGains(List<Metadata> metadataList) {
        ReplayGain id3Gains      = new ReplayGain();
        ReplayGain fallbackGains = new ReplayGain();

        if (metadataList != null) {
            for (Metadata metadata : metadataList) {
                if (metadata == null) continue;
                for (int j = 0; j < metadata.length(); j++) {
                    Metadata.Entry entry = metadata.get(j);
                    if (!isReplayGainEntry(entry)) continue;
                    boolean isId3 = (entry instanceof TextInformationFrame)
                                 || (entry instanceof InternalFrame);
                    mergeIntoReplayGain(entry, isId3 ? id3Gains : fallbackGains);
                }
            }
        }

        List<ReplayGain> gains = new ArrayList<>();
        gains.add(id3Gains);
        gains.add(fallbackGains);
        return gains;
    }

    private static boolean isReplayGainEntry(Metadata.Entry entry) {
        String upper = entry.toString().toUpperCase(java.util.Locale.ROOT);
        for (String tag : tags) {
            if (upper.contains(tag)) return true;
        }
        return false;
    }

    private static void mergeIntoReplayGain(Metadata.Entry entry, ReplayGain target) {
        String str = entry.toString();
        if (entry instanceof InternalFrame) {
            str = ((InternalFrame) entry).description + ((InternalFrame) entry).text;
        } else if (entry instanceof TextInformationFrame) {
            TextInformationFrame tf = (TextInformationFrame) entry;
            String desc = tf.description != null ? tf.description : tf.id;
            str = desc + (!tf.values.isEmpty() ? tf.values.get(0) : "");
        }

        String upper = str.toUpperCase(java.util.Locale.ROOT);

        if (upper.contains(tags[0])) target.setTrackGain(parseReplayGainTag(str));
        if (upper.contains(tags[1])) target.setAlbumGain(parseReplayGainTag(str));
        if (upper.contains(tags[2])) target.setTrackGain(parseReplayGainTag(str) / 256f + 5f);
        if (upper.contains(tags[3])) target.setAlbumGain(parseReplayGainTag(str) / 256f + 5f);
        if (upper.contains(tags[4])) target.setTrackPeak(parseReplayGainTag(str));
        if (upper.contains(tags[5])) target.setAlbumPeak(parseReplayGainTag(str));
    }

    private static float parseReplayGainTag(String entry) {
        try {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile(
                            "(-?\\d+(?:\\.\\d+)?)\\s*(?:dB)?\\s*$",
                            java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(entry.trim());
            String lastMatch = null;
            while (matcher.find()) lastMatch = matcher.group(1);
            return lastMatch != null ? Float.parseFloat(lastMatch) : 0f;
        } catch (NumberFormatException e) {
            return 0f;
        }
    }

    private static float resolveGain(Player player, List<ReplayGain> gains) {
        if (Objects.equals(Preferences.getReplayGainMode(), "disabled")
                || gains == null || gains.isEmpty()) return 0f;

        String mode = Objects.toString(Preferences.getReplayGainMode(), "");
        switch (mode) {
            case "track": return resolveTrackGain(gains);
            case "album": return resolveAlbumGain(gains);
            case "auto":  return areTracksConsecutive(player)
                                 ? resolveAlbumGain(gains) : resolveTrackGain(gains);
            default:      return 0f;
        }
    }

    private static float resolveGainForNextTrack(Player player, List<ReplayGain> gains) {
        if (Objects.equals(Preferences.getReplayGainMode(), "disabled")
                || gains == null || gains.isEmpty()) return 0f;

        String mode = Objects.toString(Preferences.getReplayGainMode(), "");
        switch (mode) {
            case "track": return resolveTrackGain(gains);
            case "album": return resolveAlbumGain(gains);
            case "auto":  return areCurrentAndNextConsecutive(player)
                                 ? resolveAlbumGain(gains) : resolveTrackGain(gains);
            default:      return 0f;
        }
    }

    private static float resolveTrackGain(List<ReplayGain> gains) {
        float primary   = gains.get(0).getTrackGain();
        float secondary = gains.get(1).getTrackGain();
        return primary != 0f ? primary : secondary;
    }

    private static float resolveAlbumGain(List<ReplayGain> gains) {
        float primary   = gains.get(0).getAlbumGain();
        float secondary = gains.get(1).getAlbumGain();
        float album = primary != 0f ? primary : secondary;
        return album != 0f ? album : resolveTrackGain(gains);
    }

    private static float resolvePeak(Player player, List<ReplayGain> gains) {
        if (Objects.equals(Preferences.getReplayGainMode(), "disabled")
                || gains == null || gains.isEmpty()) return 0f;

        boolean useAlbum = Objects.equals(Preferences.getReplayGainMode(), "album")
                || (Objects.equals(Preferences.getReplayGainMode(), "auto")
                    && areTracksConsecutive(player));

        return resolveTrackOrAlbumPeak(gains, useAlbum);
    }

    private static float resolvePeakForNextTrack(Player player, List<ReplayGain> gains) {
        if (Objects.equals(Preferences.getReplayGainMode(), "disabled")
                || gains == null || gains.isEmpty()) return 0f;

        boolean useAlbum = Objects.equals(Preferences.getReplayGainMode(), "album")
                || (Objects.equals(Preferences.getReplayGainMode(), "auto")
                    && areCurrentAndNextConsecutive(player));

        return resolveTrackOrAlbumPeak(gains, useAlbum);
    }

    private static float resolveTrackOrAlbumPeak(List<ReplayGain> gains, boolean useAlbum) {
        if (useAlbum) {
            float primary   = gains.get(0).getAlbumPeak();
            float secondary = gains.get(1).getAlbumPeak();
            float albumPeak = primary != 0f ? primary : secondary;
            if (albumPeak != 0f) return albumPeak;
        }

        float primary   = gains.get(0).getTrackPeak();
        float secondary = gains.get(1).getTrackPeak();
        return primary != 0f ? primary : secondary;
    }

    private static void seedFromPersistentCache(Player player) {
        List<String> missingIds = new ArrayList<>();
        for (int i = 0; i < player.getMediaItemCount(); i++) {
            MediaItem item = player.getMediaItemAt(i);
            if (item == null || item.mediaId == null) continue;
            if (gainDataMap.containsKey(item.mediaId)) continue;
            missingIds.add(item.mediaId);
        }
        if (missingIds.isEmpty()) return;

        List<ReplayGainCache> cachedRows = replayGainCacheRepository.getMany(missingIds);
        if (cachedRows == null) cachedRows = Collections.emptyList();

        for (ReplayGainCache row : cachedRows) {
            if (row == null || row.getMediaId() == null) continue;
            ReplayGain persisted = new ReplayGain();
            persisted.setTrackGain(row.getTrackGain());
            persisted.setAlbumGain(row.getAlbumGain());
            persisted.setTrackPeak(row.getTrackPeak());
            persisted.setAlbumPeak(row.getAlbumPeak());

            List<ReplayGain> gains = new ArrayList<>();
            gains.add(persisted);
            gains.add(new ReplayGain());
            gainDataMap.put(row.getMediaId(), gains);
            prefetchedIds.add(row.getMediaId());
        }
    }

    private static void persistGains(String mediaId, List<ReplayGain> gains) {
        if (mediaId == null || gains == null || gains.isEmpty()) return;

        ReplayGainCache cacheRow = new ReplayGainCache(mediaId);
        cacheRow.setTrackGain(resolveTrackGain(gains));
        cacheRow.setAlbumGain(resolveAlbumGain(gains));
        cacheRow.setTrackPeak(resolveTrackPeak(gains));
        cacheRow.setAlbumPeak(resolveAlbumPeak(gains));
        cacheRow.setUpdatedAt(System.currentTimeMillis());
        replayGainCacheRepository.insert(cacheRow);
    }

    private static float resolveTrackPeak(List<ReplayGain> gains) {
        float primary = gains.get(0).getTrackPeak();
        float secondary = gains.get(1).getTrackPeak();
        return primary != 0f ? primary : secondary;
    }

    private static float resolveAlbumPeak(List<ReplayGain> gains) {
        float primary = gains.get(0).getAlbumPeak();
        float secondary = gains.get(1).getAlbumPeak();
        float album = primary != 0f ? primary : secondary;
        return album != 0f ? album : resolveTrackPeak(gains);
    }

    /** Checks if the current and previous tracks share the same album. */
    private static boolean areTracksConsecutive(Player player) {
        MediaItem current = player.getCurrentMediaItem();
        int prevIdx = player.getPreviousMediaItemIndex();
        MediaItem prev = prevIdx == C.INDEX_UNSET ? null : player.getMediaItemAt(prevIdx);
        return current != null && prev != null
                && current.mediaMetadata.albumTitle != null
                && prev.mediaMetadata.albumTitle != null
                && prev.mediaMetadata.albumTitle.toString()
                       .equals(current.mediaMetadata.albumTitle.toString());
    }

    /** Checks if the current and NEXT tracks share the same album. */
    private static boolean areCurrentAndNextConsecutive(Player player) {
        MediaItem current = player.getCurrentMediaItem();
        int nextIdx = player.getNextMediaItemIndex();
        MediaItem next = nextIdx == C.INDEX_UNSET ? null : player.getMediaItemAt(nextIdx);
        return current != null && next != null
                && current.mediaMetadata.albumTitle != null
                && next.mediaMetadata.albumTitle != null
                && current.mediaMetadata.albumTitle.toString()
                       .equals(next.mediaMetadata.albumTitle.toString());
    }

    // Total gain computation (preamp + clipping prevention)

    private static float computeTotalGain(float gain, float peak) {
        float preamp    = Preferences.getReplayGainPreamp();
        float totalGain = gain + preamp;

        if (Preferences.isReplayGainPreventClipping() && peak > 0f) {
            float maxGainForPeak = -(float) (20.0 * Math.log10(peak));
            if (totalGain > maxGainForPeak) totalGain = maxGainForPeak;
        }

        return Math.max(-60f, Math.min(15f, totalGain));
    }
}
