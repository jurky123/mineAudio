package com.mineaudio.backend;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.api.PlaybackOptions;
import com.mineaudio.playback.NoopPlaybackHandle;

import com.xxmicloxx.NoteBlockAPI.model.RepeatMode;
import com.xxmicloxx.NoteBlockAPI.model.Song;
import com.xxmicloxx.NoteBlockAPI.model.SoundCategory;
import com.xxmicloxx.NoteBlockAPI.songplayer.PositionSongPlayer;
import com.xxmicloxx.NoteBlockAPI.songplayer.RadioSongPlayer;
import com.xxmicloxx.NoteBlockAPI.utils.NBSDecoder;

/**
 * NoteBlockAPI Backend：播放 plugins/MineAudio/nbs/ 下的 .nbs。
 * 仅在服务器安装 NoteBlockAPI 时创建，缺失不影响其他 Backend（设计文档 §67）。
 */
public final class NbsBackend implements AudioBackend {

    private static final AudioCapabilities CAPABILITIES =
            new AudioCapabilities(true, true, true, true, false, true,
                    true, true, true, true, false, false);

    private final MineAudioPlugin plugin;
    private final Map<String, Song> songs = new HashMap<>();

    public NbsBackend(MineAudioPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    @Override
    public String id() {
        return "nbs";
    }

    @Override
    public boolean supports(AudioSource source) {
        return source instanceof AudioSource.Nbs;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public AudioCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public long durationMs(AudioTrack track, AudioSource source) {
        Song song = songOf(source);
        return song == null ? -1 : song.getLength() * 50L;
    }

    public void reload() {
        songs.clear();
        File dir = new File(plugin.getDataFolder(), "nbs");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建 NBS 目录：" + dir.getPath());
            return;
        }
        File[] files = dir.listFiles((ignored, name) -> name.toLowerCase().endsWith(".nbs"));
        if (files == null) return;
        for (File file : files) {
            try {
                Song song = NBSDecoder.parse(file);
                if (song != null) {
                    songs.put(file.getName(), song);
                } else {
                    plugin.getLogger().warning("NBS 解析结果为空：" + file.getName());
                }
            } catch (Exception e) {
                plugin.getLogger().warning("NBS 解析失败 " + file.getName() + "：" + e.getMessage());
            }
        }
        plugin.getLogger().info("已加载 " + songs.size() + " 首 NBS 曲目");
    }

    @Override
    public PlaybackHandle play(Player player, AudioTrack track, AudioSource source, PlaybackOptions options) {
        Song song = songOf(source);
        if (song == null) return NoopPlaybackHandle.stopped();
        RadioSongPlayer songPlayer = new RadioSongPlayer(song);
        songPlayer.setCategory(categoryOf(track.bus()));
        songPlayer.setVolume(volumeOf(options.volume()));
        songPlayer.setRepeatMode(options.loop() ? RepeatMode.ONE : RepeatMode.NO);
        songPlayer.addPlayer(player);
        songPlayer.setPlaying(true);
        return new NbsPlaybackHandle(songPlayer);
    }

    @Override
    public PlaybackHandle playAt(Location location, AudioTrack track, AudioSource source, PlaybackOptions options,
                                 double radius) {
        Song song = songOf(source);
        if (song == null) return NoopPlaybackHandle.stopped();
        PositionSongPlayer songPlayer = new PositionSongPlayer(song);
        songPlayer.setCategory(categoryOf(track.bus()));
        songPlayer.setVolume(volumeOf(options.volume()));
        songPlayer.setRepeatMode(options.loop() ? RepeatMode.ONE : RepeatMode.NO);
        songPlayer.setTargetLocation(location);
        songPlayer.setDistance(radius > 0 ? (int) Math.ceil(radius)
                : Math.max(1, plugin.getConfig().getInt("nbs.position-distance", 32)));
        songPlayer.setPlaying(true);
        return new NbsPlaybackHandle(songPlayer);
    }

    private Song songOf(AudioSource source) {
        if (!(source instanceof AudioSource.Nbs nbs)) return null;
        Song song = songs.get(nbs.file());
        if (song == null) {
            plugin.getLogger().warning("NBS 文件不存在或未加载：" + nbs.file());
        }
        return song;
    }

    private static SoundCategory categoryOf(AudioBus bus) {
        return switch (bus) {
            case MUSIC -> SoundCategory.MUSIC;
            case AMBIENT -> SoundCategory.AMBIENT;
            case SFX, UI -> SoundCategory.MASTER;
        };
    }

    private static byte volumeOf(float volume) {
        return (byte) Math.max(0, Math.min(100, Math.round(volume * 100)));
    }
}
