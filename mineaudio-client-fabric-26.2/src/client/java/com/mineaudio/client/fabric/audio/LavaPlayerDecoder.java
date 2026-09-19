package com.mineaudio.client.fabric.audio;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.mineaudio.client.decode.AudioDecoder;
import com.mineaudio.client.decode.SeekStatus;
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

    /** 输出 48kHz/16bit/立体声 LE，与客户端 OpenAL 声明的 {@link javax.sound.sampled.AudioFormat} 一致。 */
    public static final AudioDataFormat FORMAT = StandardAudioDataFormats.DISCORD_PCM_S16_LE;

    private static final AudioPlayerManager MANAGER = createManager();

    private volatile AudioPlayer player;
    private volatile AudioTrack track;
    private volatile Sink sink;
    private volatile Thread decodeThread;
    private volatile boolean running;
    private volatile boolean finished;
    private volatile boolean failed;
    private volatile long durationMs;
    /** 解码代际：seek 时自增，帧在读取前打标，用于丢弃跨 seek 的旧帧。 */
    private final java.util.concurrent.atomic.AtomicLong generation = new java.util.concurrent.atomic.AtomicLong();

    private static AudioPlayerManager createManager() {
        DefaultAudioPlayerManager manager = new DefaultAudioPlayerManager();
        manager.getConfiguration().setOutputFormat(FORMAT);
        manager.getConfiguration().setResamplingQuality(AudioConfiguration.ResamplingQuality.HIGH);
        HttpAudioSourceManager http = new HttpAudioSourceManager();
        // 播放地址是本机网关，首次请求需等上游拉流，放宽超时避免误判
        http.configureRequests(config -> org.apache.http.client.config.RequestConfig.copy(config)
                .setConnectTimeout(30000)
                .setSocketTimeout(60000)
                .setConnectionRequestTimeout(30000)
                .build());
        manager.registerSourceManager(http);
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
                // 先取代际再 provide：provide 期间发生的 seek 会让该帧按旧代际丢弃
                long frameGeneration = generation.get();
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
                    current.onPcm(frameGeneration, frame.getData(), frame.getDataLength(), frame.getTimecode());
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
    public SeekStatus seek(long positionMs) {
        generation.incrementAndGet();
        AudioTrack t = track;
        if (t == null) {
            return SeekStatus.NOT_READY;
        }
        if (!t.isSeekable()) {
            return SeekStatus.NOT_SEEKABLE;
        }
        t.setPosition(Math.max(0, positionMs));
        return SeekStatus.APPLIED;
    }

    /** 当前解码代际；Sink 收到帧后据此判断是否已被 seek 作废。 */
    public long generation() {
        return generation.get();
    }

    @Override
    public boolean seekable() {
        AudioTrack t = track;
        return t != null && t.isSeekable();
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
