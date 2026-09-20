package com.mineaudio.playback;

import java.util.Objects;

import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackOptions;

/**
 * 一条“应该存在的音乐”声明。它描述意图，不代表实际播放——
 * 实际播放由 {@link com.mineaudio.AudioOrchestrator} 的 reconcile 决定。
 *
 * <p>{@code timeline != null} 表示共享时间轴（被更高优先级打断后恢复、晚加入时按当前进度对齐）；
 * {@code null} 表示从头播放。</p>
 */
public record MusicIntent(
        String sourceId,
        MusicLayer layer,
        long sequence,
        AudioTrack track,
        PlaybackOptions options,
        PlaybackOrigin origin,
        Timeline timeline) {

    public MusicIntent {
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(origin, "origin");
    }

    /** 优先级比较：layer 高者胜，同层 sequence 大者胜。 */
    public int compare(MusicIntent other) {
        int byLayer = Integer.compare(layer.priority(), other.layer.priority());
        if (byLayer != 0) return byLayer;
        return Long.compare(sequence, other.sequence);
    }

    /** source 身份 + 内容一致：用于 upsert 保留原 sequence，避免 refresh 反抢。 */
    public boolean sameContent(MusicIntent other) {
        return layer == other.layer
                && track.id().equals(other.track.id())
                && sameOptions(options, other.options)
                && origin == other.origin
                && Objects.equals(timeline, other.timeline);
    }

    private static boolean sameOptions(PlaybackOptions a, PlaybackOptions b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return Float.compare(a.volume(), b.volume()) == 0
                && Float.compare(a.pitch(), b.pitch()) == 0
                && a.loop() == b.loop()
                && a.fadeInMs() == b.fadeInMs()
                && a.fadeOutMs() == b.fadeOutMs();
    }
}
