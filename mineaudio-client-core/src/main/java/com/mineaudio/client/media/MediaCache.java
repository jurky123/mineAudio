package com.mineaudio.client.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 磁盘媒体缓存：按 cacheKey 的 SHA-256 命名，只存原始编码字节（设计文档 §56-58）。
 * 未完成下载用 .part，完成后原子重命名；LRU 按 lastModified 清理。
 */
public final class MediaCache {

    private final Path directory;
    private final long maxSizeBytes;
    private final long maxTrackBytes;

    public MediaCache(Path directory, long maxSizeBytes, long maxTrackBytes) {
        this.directory = directory;
        this.maxSizeBytes = maxSizeBytes;
        this.maxTrackBytes = maxTrackBytes;
    }

    public Path directory() {
        return directory;
    }

    public void init() throws IOException {
        Files.createDirectories(directory);
        cleanupParts();
    }

    public Path fileFor(String cacheKey) {
        return directory.resolve(hash(cacheKey) + ".media");
    }

    public Path partFor(String cacheKey) {
        return directory.resolve(hash(cacheKey) + ".part");
    }

    public boolean has(String cacheKey) {
        return Files.isRegularFile(fileFor(cacheKey));
    }

    /** 提交完整下载：.part → .media，并触发 LRU 清理。 */
    public void commit(Path part, String cacheKey) throws IOException {
        if (Files.size(part) > maxTrackBytes) {
            Files.deleteIfExists(part);
            throw new IOException("track exceeds cache limit");
        }
        Files.move(part, fileFor(cacheKey), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        evictIfNeeded();
    }

    public void touch(String cacheKey) {
        try {
            Files.setLastModifiedTime(fileFor(cacheKey), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
            // 缓存命中标记失败不影响播放
        }
    }

    /** 清理崩溃残留的 .part。 */
    public void cleanupParts() {
        try (Stream<Path> files = Files.list(directory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".part")).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // 忽略
                }
            });
        } catch (IOException ignored) {
            // 目录不存在等
        }
    }

    public long totalSize() {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /** 超出上限时按 lastModified 从旧到新删除。 */
    public void evictIfNeeded() {
        if (maxSizeBytes <= 0) return;
        if (totalSize() <= maxSizeBytes) return;
        try (Stream<Path> files = Files.list(directory)) {
            List<Path> sorted = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(MediaCache::lastModified))
                    .toList();
            long size = totalSize();
            for (Path path : sorted) {
                if (size <= maxSizeBytes) break;
                try {
                    long length = Files.size(path);
                    Files.deleteIfExists(path);
                    size -= length;
                } catch (IOException ignored) {
                    // 忽略单个文件失败
                }
            }
        } catch (IOException ignored) {
            // 忽略
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
