package com.mineaudio.playback;

import java.util.Optional;
import java.util.function.Predicate;

import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;

/** 选择实际播放的素材：primary → fallback，按玩家客户端能力跳过 PACK / Stream。 */
public final class SourceResolver {

    private SourceResolver() {
    }

    public static Optional<AudioSource> resolve(AudioTrack track, boolean packAvailable,
                                                boolean streamAvailable,
                                                Predicate<AudioSource> backendAvailable) {
        if (canPlay(track.primary(), packAvailable, streamAvailable, backendAvailable)) {
            return Optional.of(track.primary());
        }
        AudioSource fallback = track.fallback();
        if (fallback != null && canPlay(fallback, packAvailable, streamAvailable, backendAvailable)) {
            return Optional.of(fallback);
        }
        return Optional.empty();
    }

    private static boolean canPlay(AudioSource source, boolean packAvailable, boolean streamAvailable,
                                   Predicate<AudioSource> backendAvailable) {
        if (source instanceof AudioSource.PackSound && !packAvailable) {
            return false;
        }
        if (source instanceof AudioSource.Stream && !streamAvailable) {
            return false;
        }
        return backendAvailable.test(source);
    }
}
