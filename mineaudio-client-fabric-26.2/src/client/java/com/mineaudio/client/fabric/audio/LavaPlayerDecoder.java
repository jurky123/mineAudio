package com.mineaudio.client.fabric.audio;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mineaudio.client.decode.AudioDecoder;
import com.sedmelluq.discord.lavaplayer.format.AudioDataFormat;
import com.sedmelluq.discord.lavaplayer.format.StandardAudioDataFormats;
import com.sedmelluq.discord.lavaplayer.player.AudioConfiguration;
import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter;
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason;
import com.sedmelluq.discord.lavaplayer.track.playback.AudioFrame;

/**
 * 基于 LavaPlayer 的解码器：输出 {@link StandardAudioDataFormats#COMMON_PCM_S16_LE}
 * （48kHz/16bit/立体声），由独立线程推入播放层。
 */
public final class LavaPlayerDecoder implements AudioDecoder {

    public static final AudioDataFormat FORMAT = StandardAudioDataFormats.COMMON_PCM_S16_LE;

    private static final AudioPlayerManager MANAGER = createManager();

    private volatile AudioPlayer player;
    private volatile AudioTrack track;
    private volatile Sink sink;
    private volatile Thread decodeThread;
    private volatile boolean running;
    private volatile boolean finished;
    private volatile boolean failed;
    private volatile long durationMs;

    private static AudioPlayerManager createManager() {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.getConfiguration().setOutputFormat(FORMAT);
        manager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        manager.registerSourceManager(new HttpAudioSourceManager());
        return manager;
    }

    @Override
    public void start(String url, long startPositionMs, Sink sink) {
        close();
        this.sink = sink;
        this.finished = false;
        this.failed = false;
        this.durationMs = 0;

        AudioPlayer p = MANAGER.createPlayer();
        p.setVolume(100);
        p.addListener(new AudioEventAdapter() {
            @Override
            public void onTrackEnd(AudioPlayer player, AudioTrack t, AudioTrackEndReason reason) {
                if (reason == AudioTrackEndReason.FINISHED) {
                    finish();
                } else if (reason == AudioTrackEndReason.LOAD_FAILED) {
                    fail("TRACK_ERROR", "音频解码失败");
                }
            }

            @Override
            public void onTrackException(AudioPlayer player, AudioTrack t, FriendlyException exception) {
                fail("TRACK_ERROR", exception.getMessage());
            }

            @Override
            public void onTrackStuck(AudioPlayer player, AudioTrack t, long thresholdMs) {
                fail("TRACK_STUCK", "音频流卡住超过 " + thresholdMs + "ms");
            }
        });
        this.player = p;
        this.running = true;

        Thread thread = new Thread(this::decodeLoop, "MineAudio-Decoder");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY + 1);
        this.decodeThread = thread;
        thread.start();

        MANAGER.loadItem(url, new AudioLoadResultHandler() {
            @Override
            public void trackLoaded(AudioTrack loaded) {
                if (!running) return;
                track = loaded;
                durationMs = loaded.getDuration();
                if (startPositionMs > 0 && loaded.isSeekable()) {
                    loaded.setPosition(startPositionMs);
                }
                p.playTrack(loaded);
            }

            @Override
            public void playlistLoaded(AudioPlaylist playlist) {
                if (!playlist.getTracks().isEmpty()) {
                    trackLoaded(playlist.getTracks().get(0));
                } else {
                    noMatches();
                }
            }

            @Override
            public void noMatches() {
                fail("NO_MATCH", "无法解析音频链接");
            }

            @Override
            public void loadFailed(FriendlyException exception) {
                fail("LOAD_FAILED", exception.getMessage());
            }
        });
    }

    private void decodeLoop() {
        try {
            while (running) {
                AudioPlayer p = player;
                if (p == null) break;
                AudioFrame frame;
                try {
                    frame = p.provide(20, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    continue;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (frame == null) {
                    if (finished || failed) break;
                    continue;
                }
                if (frame.isTerminator()) {
                    finish();
                    break;
                }
                Sink current = sink;
                if (current != null) {
                    current.onPcm(frame.getData(), frame.getDataLength(), frame.getTimecode());
                }
            }
        } catch (Throwable t) {
            fail("DECODE_ERROR", t.toString());
        }
    }

    private void finish() {
        if (finished || failed) return;
        finished = true;
        running = false;
        Sink current = sink;
        if (current != null) current.onEnded();
    }

    private void fail(String code, String message) {
        if (finished || failed) return;
        failed = true;
        running = false;
        Sink current = sink;
        if (current != null) current.onError(code, message == null ? code : message);
    }

    @Override
    public void setPaused(boolean paused) {
        AudioPlayer p = player;
        if (p != null) p.setPaused(paused);
    }

    @Override
    public void seek(long positionMs) {
        AudioTrack t = track;
        if (t != null && t.isSeekable()) {
            t.setPosition(Math.max(0, positionMs));
        }
    }

    @Override
    public long positionMs() {
        AudioTrack t = track;
        return t == null ? 0 : t.getPosition();
    }

    @Override
    public long durationMs() {
        return durationMs;
    }

    @Override
    public boolean finished() {
        return finished;
    }

    @Override
    public void close() {
        running = false;
        finished = true;
        Thread thread = decodeThread;
        if (thread != null) thread.interrupt();
        decodeThread = null;
        AudioPlayer p = player;
        player = null;
        track = null;
        if (p != null) {
            p.stopTrack();
            p.destroy();
        }
        sink = null;
    }
}
