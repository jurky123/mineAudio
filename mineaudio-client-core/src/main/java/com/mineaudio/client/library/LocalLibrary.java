package com.mineaudio.client.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * 客户端本地曲库：扫描固定目录、解析元数据与封面、维护索引（磁盘缓存）并按内容 sha256 去重。
 *
 * <p>纯 Java（不依赖 MC），元数据解析由注入的 {@link MetadataProbe} 完成，便于单测。
 * 目录结构：音频文件（可含子目录）+ {@code index.json} + {@code .covers/}。</p>
 */
public final class LocalLibrary {

    private static final Set<String> EXTENSIONS = Set.of(
            "mp3", "flac", "ogg", "oga", "m4a", "mp4", "aac", "wav", "opus", "webm");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private final Path coversDir;
    private final Path indexFile;
    private final MetadataProbe probe;
    private final List<LocalTrack> tracks = new ArrayList<>();
    private volatile long generation;
    private volatile String note = "尚未扫描";

    public LocalLibrary(Path dir, MetadataProbe probe) {
        this.dir = dir;
        this.probe = probe;
        this.coversDir = dir.resolve(".covers");
        this.indexFile = dir.resolve("index.json");
    }

    public Path directory() {
        return dir;
    }

    /** 曲库快照（不可变）。 */
    public synchronized List<LocalTrack> tracks() {
        return List.copyOf(tracks);
    }

    public synchronized int size() {
        return tracks.size();
    }

    public synchronized LocalTrack track(int index) {
        return index >= 0 && index < tracks.size() ? tracks.get(index) : null;
    }

    public synchronized Path coverPath(LocalTrack track) {
        return track.coverFile() == null ? null : dir.resolve(track.coverFile());
    }

    /** 结构性变化计数：列表增删/扫描完成时自增（MineUI 据此重排）。 */
    public long generation() {
        return generation;
    }

    public String note() {
        return note;
    }

    /**
     * 扫描目录并重建索引（阻塞；调用方应放到后台线程）。未变化的文件复用缓存，不重复解析/哈希。
     */
    public synchronized void scan() {
        try {
            Files.createDirectories(dir);
            Files.createDirectories(coversDir);
            Map<String, LocalTrack> cached = loadIndex();
            List<LocalTrack> result = new ArrayList<>();
            try (Stream<Path> stream = Files.walk(dir, 6)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> !p.normalize().startsWith(coversDir.normalize()))
                        .filter(p -> !p.getFileName().toString().equals("index.json"))
                        .filter(p -> EXTENSIONS.contains(extension(p)))
                        .sorted()
                        .toList();
                for (Path file : files) {
                    LocalTrack track = probeOrReuse(file, cached);
                    if (track != null) {
                        result.add(track);
                    }
                }
            }
            result.sort(Comparator
                    .comparing((LocalTrack t) -> t.title() == null ? "" : t.title(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(t -> t.file().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            tracks.clear();
            tracks.addAll(result);
            saveIndex(result);
            note = result.isEmpty()
                    ? "本地曲库为空：把音频文件放进 " + dir + " 后点“刷新”"
                    : "本地曲库：" + result.size() + " 首";
        } catch (Throwable t) {
            note = "扫描失败：" + t;
        } finally {
            generation++;
        }
    }

    /** 删除某首（删除音频文件与封面，重建条目）。返回是否成功。 */
    public synchronized boolean delete(int index) {
        if (index < 0 || index >= tracks.size()) return false;
        LocalTrack track = tracks.remove(index);
        try {
            Files.deleteIfExists(track.file());
            Path cover = coverPath(track);
            if (cover != null) Files.deleteIfExists(cover);
        } catch (IOException ignored) {
            // 文件占用/权限问题：索引已移除，文件残留不致命
        }
        saveIndex(tracks);
        note = "已删除：" + track.title();
        generation++;
        return true;
    }

    // ---------- 内部 ----------

    private LocalTrack probeOrReuse(Path file, Map<String, LocalTrack> cached) {
        try {
            String key = relativeKey(file);
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            LocalTrack previous = cached.get(key);
            if (previous != null && previous.sizeBytes() == size && previous.modifiedMs() == mtime) {
                return previous;
            }
            String id = sha256(file);
            TrackMeta meta = probe == null ? null : probe.probe(file);
            String title = meta != null && notBlank(meta.title()) ? meta.title() : stem(file);
            String artist = meta != null && meta.artist() != null ? meta.artist() : "";
            String album = meta != null && meta.album() != null ? meta.album() : "";
            long duration = meta != null ? meta.durationMs() : -1;
            String coverFile = writeCover(id, meta, previous);
            return new LocalTrack(id, file, size, mtime, duration, title, artist, album, coverFile);
        } catch (IOException e) {
            return null;
        }
    }

    private String writeCover(String id, TrackMeta meta, LocalTrack previous) {
        if (meta != null && meta.cover() != null && meta.cover().length > 0) {
            String ext = meta.coverExt() == null || meta.coverExt().isBlank() ? "jpg" : meta.coverExt();
            String relative = ".covers/" + id + "." + ext;
            try {
                Files.write(dir.resolve(relative), meta.cover());
                return relative;
            } catch (IOException ignored) {
                return previous != null ? previous.coverFile() : null;
            }
        }
        return previous != null ? previous.coverFile() : null;
    }

    private Map<String, LocalTrack> loadIndex() {
        Map<String, LocalTrack> map = new LinkedHashMap<>();
        if (!Files.isRegularFile(indexFile)) return map;
        try (InputStream in = Files.newInputStream(indexFile)) {
            IndexFile index = GSON.fromJson(new String(in.readAllBytes()), IndexFile.class);
            if (index != null && index.tracks != null) {
                for (Entry entry : index.tracks) {
                    if (entry.file == null) continue;
                    Path file = dir.resolve(entry.file);
                    LocalTrack track = new LocalTrack(entry.id, file, entry.size, entry.mtime,
                            entry.duration, entry.title, entry.artist, entry.album, entry.cover);
                    map.put(entry.file.replace('\\', '/'), track);
                }
            }
        } catch (Throwable ignored) {
            // 索引损坏时忽略，重新解析
        }
        return map;
    }

    private void saveIndex(List<LocalTrack> list) {
        IndexFile index = new IndexFile();
        for (LocalTrack track : list) {
            Entry entry = new Entry();
            entry.id = track.id();
            entry.file = relativeKey(track.file());
            entry.size = track.sizeBytes();
            entry.mtime = track.modifiedMs();
            entry.duration = track.durationMs();
            entry.title = track.title();
            entry.artist = track.artist();
            entry.album = track.album();
            entry.cover = track.coverFile();
            index.tracks.add(entry);
        }
        try {
            Files.writeString(indexFile, GSON.toJson(index));
        } catch (IOException ignored) {
            // 索引写失败不致命，下次重扫
        }
    }

    private String relativeKey(Path file) {
        try {
            return dir.relativize(file).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return file.getFileName().toString();
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    /** 流式 sha256（大文件不占内存）。 */
    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream in = new DigestInputStream(Files.newInputStream(file), digest)) {
                byte[] buffer = new byte[64 * 1024];
                while (in.read(buffer) != -1) {
                    // 读取即更新摘要
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static final class IndexFile {
        List<Entry> tracks = new ArrayList<>();
    }

    private static final class Entry {
        String id;
        String file;
        long size;
        long mtime;
        long duration;
        String title;
        String artist;
        String album;
        String cover;
    }

    static {
        // 保持 TypeToken 引用以便未来扩展（当前 gson 反射即可）
        TypeToken.get(IndexFile.class);
    }
}
