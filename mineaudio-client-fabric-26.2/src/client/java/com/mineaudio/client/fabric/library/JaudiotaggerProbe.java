package com.mineaudio.client.fabric.library;

import java.io.File;
import java.nio.file.Path;
import java.util.Locale;

import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import com.mineaudio.client.library.MetadataProbe;
import com.mineaudio.client.library.TrackMeta;

/**
 * 基于 jaudiotagger 的本地元数据解析：读取标题/歌手/专辑/时长与嵌入封面。
 * 失败时返回兜底值（标题留空，由 LocalLibrary 用文件名兜底），不抛异常。
 */
final class JaudiotaggerProbe implements MetadataProbe {

    @Override
    public TrackMeta probe(Path file) {
        try {
            AudioFile audio = AudioFileIO.read(file.toFile());
            Tag tag = audio.getTag();
            long durationMs = -1;
            try {
                durationMs = Math.round(audio.getAudioHeader().getPreciseTrackLength() * 1000.0);
            } catch (Throwable ignored) {
                // 某些容器没有精确时长
            }
            if (durationMs <= 0) {
                try {
                    durationMs = audio.getAudioHeader().getTrackLength() * 1000L;
                } catch (Throwable ignored) {
                    durationMs = -1;
                }
            }
            String title = first(tag, FieldKey.TITLE);
            String artist = first(tag, FieldKey.ARTIST);
            String album = first(tag, FieldKey.ALBUM);
            byte[] cover = null;
            String coverExt = null;
            if (tag != null) {
                Artwork artwork = tag.getFirstArtwork();
                if (artwork != null && artwork.getBinaryData() != null && artwork.getBinaryData().length > 0) {
                    cover = artwork.getBinaryData();
                    coverExt = extensionFor(artwork.getMimeType(), file);
                }
            }
            return new TrackMeta(durationMs, title, artist, album, cover, coverExt);
        } catch (Throwable t) {
            return new TrackMeta(-1, null, "", "", null, null);
        }
    }

    private static String first(Tag tag, FieldKey key) {
        if (tag == null) return null;
        try {
            String value = tag.getFirst(key);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String extensionFor(String mime, Path file) {
        if (mime != null) {
            String lower = mime.toLowerCase(Locale.ROOT);
            if (lower.contains("png")) return "png";
            if (lower.contains("gif")) return "gif";
            if (lower.contains("bmp")) return "bmp";
            if (lower.contains("jpeg") || lower.contains("jpg")) return "jpg";
        }
        String name = file.getFileName().toString();
        return name.toLowerCase(Locale.ROOT).endsWith(".flac") ? "jpg" : "jpg";
    }
}
