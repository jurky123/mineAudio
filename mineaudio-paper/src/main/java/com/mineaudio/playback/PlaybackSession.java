package com.mineaudio.playback;

import java.util.UUID;

import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackOptions;

/** 某玩家处的一次实际播放（单玩家一条）。 */
public record PlaybackSession(
        UUID id,
        AudioTrack track,
        AudioSource source,
        PlaybackOptions options,
        PlaybackHandle handle,
        String backend,
        PlaybackOrigin origin) {
}
