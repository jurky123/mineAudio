package com.mineaudio.client.library;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * 客户端本地曲库：扫描固定目录、解析元数据与封面、维护索引（磁盘缓存）并按内容 sha256 去重。
 *
 * <p>纯 Java（不依赖 MC）。普通音频走注入的 {@link MetadataProbe}（jaudiotagger）；
 * {@code .ncm} 由 {@link NcmDecoder} 解密为 {@code .decoded/} 下的缓存音频再索引；
 * 同名 {@code .lrc} 作为歌词路径关联存储（暂不解析）。</p>
 */
public final class LocalLibrary {

    static final Set<String> EXTENSIONS = Set.of(
            "mp3", "flac", "ogg", "oga", "m4a", "mp4", "aac", "wav", "opus", "webm", "ncm");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path dir;
    private final Path coversDir;
    private final Path decodedDir;
    private final Path indexFile;
    private final MetadataProbe probe;
    private final NcmDecoder ncm;
    /** 不可变快照：渲染线程无锁读取，扫描完成后整体替换。 */
    private volatile List<LocalTrack> tracks = List.of();
    private final java.util.concurrent.atomic.AtomicBoolean scanning =
            new java.util.concurrent.atomic.AtomicBoolean();
    private volatile long generation;
    private volatile String note = "尚未扫描";
    private volatile String lastFailure;
    /** 封面字节缓存（内容寻址，按 track id 缓存，供本地图片绑定）。 */
    private final Map<String, byte[]> coverCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int COVER_MAX_BYTES = 2 * 1024 * 1024;

    public LocalLibrary(Path dir, MetadataProbe probe) {
        this(dir, probe, new NcmDecoder());
    }

    public LocalLibrary(Path dir, MetadataProbe probe, NcmDecoder ncm) {
        this.dir = dir;
        this.probe = probe;
        this.ncm = ncm;
        this.coversDir = dir.resolve(".covers");
        this.decodedDir = dir.resolve(".decoded");
        this.indexFile = dir.resolve("index.json");
    }

    public Path directory() {
        return dir;
    }

    /** 曲库快照（不可变，无锁）。 */
    public List<LocalTrack> tracks() {
        return tracks;
    }

    public int size() {
        return tracks.size();
    }

    public LocalTrack track(int index) {
        List<LocalTrack> snapshot = tracks;
        return index >= 0 && index < snapshot.size() ? snapshot.get(index) : null;
    }

    /** 按内容 id 查曲目（“自己”播放时服务端回传 id）。 */
    public LocalTrack byId(String id) {
        if (id == null) return null;
        for (LocalTrack track : tracks) {
            if (id.equals(track.id())) return track;
        }
        return null;
    }

    public Path coverPath(LocalTrack track) {
        return track.coverFile() == null ? null : dir.resolve(track.coverFile());
    }

    public Path lyricsPath(LocalTrack track) {
        return track.lyricsFile() == null ? null : dir.resolve(track.lyricsFile());
    }

    /** 封面字节（本地图片绑定用）：按内容寻址缓存；无封面或超过 2MiB 返回 null。 */
    public byte[] coverBytes(LocalTrack track) {
        if (track == null || track.coverFile() == null) return null;
        byte[] cached = coverCache.get(track.id());
        if (cached != null) return cached;
        try {
            Path path = dir.resolve(track.coverFile());
            if (!Files.isRegularFile(path) || Files.size(path) > COVER_MAX_BYTES) return null;
            byte[] bytes = Files.readAllBytes(path);
            coverCache.put(track.id(), bytes);
            return bytes;
        } catch (IOException e) {
            return null;
        }
    }

    /** 结构性变化计数：列表增删/扫描完成时自增（MineUI 据此重排）。 */
    public long generation() {
        return generation;
    }

    public String note() {
        return note;
    }

    /** 扫描目录并重建索引（阻塞；调用方应放到后台线程）。未变化的文件复用索引缓存，不重复解析。 */
    public void scan() {
        if (!scanning.compareAndSet(false, true)) {
            return; // 已有扫描进行中
        }
        try {
            Files.createDirectories(dir);
            Files.createDirectories(coversDir);
            Map<String, LocalTrack> cached = loadIndex();
            Map<String, Path> decodedFiles = listDecoded();
            List<LocalTrack> result = new ArrayList<>();
            int failures = 0;
            lastFailure = null;
            try (Stream<Path> stream = Files.walk(dir, 6)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> !under(p, coversDir) && !under(p, decodedDir))
                        .filter(p -> !p.getFileName().toString().equals("index.json"))
                        .filter(p -> EXTENSIONS.contains(extension(p)))
                        .sorted()
                        .toList();
                for (Path file : files) {
                    LocalTrack track = materialize(file, cached, decodedFiles);
                    if (track != null) {
                        result.add(track);
                    } else {
                        failures++;
                    }
                }
            }
            result.sort(Comparator
                    .comparing((LocalTrack t) -> t.title() == null ? "" : t.title(), String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(t -> t.file().getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
            saveIndex(result);
            tracks = List.copyOf(result); // 原子替换快照
            if (result.isEmpty() && failures == 0) {
                note = "本地曲库为空：把音频文件放进 " + dir + " 后点“刷新”";
            } else if (failures > 0) {
                note = "本地曲库：" + result.size() + " 首（" + failures + " 个文件失败："
                        + (lastFailure == null ? "" : lastFailure) + "）";
            } else {
                note = "本地曲库：" + result.size() + " 首";
            }
        } catch (Throwable t) {
            note = "扫描失败：" + t;
        } finally {
            scanning.set(false);
            generation++;
        }
    }

    /** 删除某首（删除源文件、解密缓存与封面）。返回是否成功。 */
    public boolean delete(int index) {
        List<LocalTrack> snapshot = tracks;
        if (index < 0 || index >= snapshot.size()) return false;
        List<LocalTrack> result = new ArrayList<>(snapshot);
        LocalTrack track = result.remove(index);
        try {
            Files.deleteIfExists(track.file());
            if (!track.playableFile().equals(track.file())) {
                Files.deleteIfExists(track.playableFile());
            }
            Path cover = coverPath(track);
            if (cover != null) Files.deleteIfExists(cover);
        } catch (IOException ignored) {
            // 文件占用/权限问题：索引已移除，文件残留不致命
        }
        saveIndex(result);
        tracks = List.copyOf(result);
        note = "已删除：" + track.title();
        generation++;
        return true;
    }

    // ---------- 内部 ----------

    /** 已解密的缓存文件（按 baseName 索引，扩展名可能不同）。 */
    private Map<String, Path> listDecoded() {
        Map<String, Path> map = new HashMap<>();
        if (!Files.isDirectory(decodedDir)) return map;
        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(decodedDir)) {
            for (Path path : stream) {
                String name = path.getFileName().toString();
                int dot = name.lastIndexOf('.');
                if (dot > 0) {
                    map.put(name.substring(0, dot), path);
                }
            }
        } catch (IOException ignored) {
            // 解密目录不可读时按未解密处理
        }
        return map;
    }

    /**
     * 确保 .ncm 已解密为可播放文件（按需调用，切勿在渲染线程调用）。
     * 解码后用 jaudiotagger 补全元数据并原子更新快照；返回实际可播放路径。
     */
    public Path ensureDecoded(LocalTrack track) throws IOException {
        if (!NcmDecoder.isNcm(track.file()) || ncm == null) {
            return track.file();
        }
        if (Files.isRegularFile(track.playableFile())) {
            return track.playableFile();
        }
        NcmDecoder.Decoded decoded = ncm.decode(track.file(), decodedDir);
        Path actual = decoded.audio();
        TrackMeta meta = probe == null ? null : probe.probe(actual);
        List<LocalTrack> snapshot = tracks;
        List<LocalTrack> updated = new ArrayList<>(snapshot.size());
        for (LocalTrack existing : snapshot) {
            if (existing.id().equals(track.id())) {
                updated.add(new LocalTrack(existing.id(), existing.file(), actual,
                        existing.sizeBytes(), existing.modifiedMs(),
                        meta != null && meta.durationMs() > 0 ? meta.durationMs() : existing.durationMs(),
                        meta != null && notBlank(meta.title()) ? meta.title() : existing.title(),
                        meta != null && notBlank(meta.artist()) ? meta.artist() : existing.artist(),
                        meta != null && notBlank(meta.album()) ? meta.album() : existing.album(),
                        existing.coverFile(), existing.lyricsFile()));
            } else {
                updated.add(existing);
            }
        }
        tracks = List.copyOf(updated);
        generation++;
        return actual;
    }

    private LocalTrack materialize(Path file, Map<String, LocalTrack> cached, Map<String, Path> decodedFiles) {
        try {
            String key = relativeKey(file);
            long size = Files.size(file);
            long mtime = Files.getLastModifiedTime(file).toMillis();
            LocalTrack previous = cached.get(key);
            if (previous != null && previous.sizeBytes() == size && previous.modifiedMs() == mtime) {
                // 未变化：直接复用索引（.ncm 的音频按需解密，不要求 playable 已存在）
                return previous;
            }
            String lyrics = findLyrics(file);

            if (NcmDecoder.isNcm(file) && ncm != null) {
                // 只探测头部/meta/封面（不解码音频），音频在试听/上传时按需解密
                NcmDecoder.Probe probeResult = ncm.probe(file);
                String id = probeResult.id();
                Path playable = decodedFiles.get(id);
                if (playable == null) {
                    playable = decodedDir.resolve(id + "." + probeResult.ext());
                }
                String cover = writeCover(id, probeResult.cover(), probeResult.coverExt(),
                        previous != null ? previous.coverFile() : null);
                return new LocalTrack(id, file, playable, size, mtime, probeResult.durationMs(),
                        notBlank(probeResult.title()) ? probeResult.title() : stem(file),
                        nullToEmpty(probeResult.artist()), nullToEmpty(probeResult.album()), cover, lyrics);
            }

            String id = sha256(file);
            TrackMeta meta = probe == null ? null : probe.probe(file);
            String title = meta != null && notBlank(meta.title()) ? meta.title() : stem(file);
            String cover = writeCover(id, meta == null ? null : meta.cover(),
                    meta == null ? null : meta.coverExt(),
                    previous != null ? previous.coverFile() : null);
            return new LocalTrack(id, file, file, size, mtime,
                    meta != null ? meta.durationMs() : -1,
                    title,
                    meta != null ? nullToEmpty(meta.artist()) : "",
                    meta != null ? nullToEmpty(meta.album()) : "",
                    cover, lyrics);
        } catch (Throwable t) {
            // 单个文件失败不影响整体扫描；记录原因供 UI 提示
            lastFailure = file.getFileName() + "：" + t;
            return null;
        }
    }

    /** 同名 .lrc（大小写不敏感），返回相对路径或 null。 */
    private String findLyrics(Path source) {
        String stem = stem(source);
        Path parent = source.getParent();
        if (parent == null) return null;
        for (String ext : new String[] {"lrc", "LRC", "Lrc"}) {
            Path candidate = parent.resolve(stem + "." + ext);
            if (Files.isRegularFile(candidate)) {
                return relativeKey(candidate);
            }
        }
        return null;
    }

    private String writeCover(String id, byte[] cover, String ext, String fallback) {
        if (cover != null && cover.length > 0) {
            String suffix = ext == null || ext.isBlank() ? "jpg" : ext;
            String relative = ".covers/" + id + "." + suffix;
            try {
                Files.write(dir.resolve(relative), cover);
                return relative;
            } catch (IOException ignored) {
                return fallback;
            }
        }
        return fallback;
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
                    Path playable = entry.playable == null ? file : dir.resolve(entry.playable);
                    LocalTrack track = new LocalTrack(entry.id, file, playable, entry.size, entry.mtime,
                            entry.duration, entry.title, entry.artist, entry.album, entry.cover, entry.lyrics);
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
            entry.playable = relativeKey(track.playableFile());
            entry.size = track.sizeBytes();
            entry.mtime = track.modifiedMs();
            entry.duration = track.durationMs();
            entry.title = track.title();
            entry.artist = track.artist();
            entry.album = track.album();
            entry.cover = track.coverFile();
            entry.lyrics = track.lyricsFile();
            index.tracks.add(entry);
        }
        try {
            Files.writeString(indexFile, GSON.toJson(index));
        } catch (IOException ignored) {
            // 索引写失败不致命，下次重扫
        }
    }

    private static boolean under(Path file, Path dir) {
        return file.normalize().startsWith(dir.normalize());
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

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
        String playable;
        long size;
        long mtime;
        long duration;
        String title;
        String artist;
        String album;
        String cover;
        String lyrics;
    }
}
