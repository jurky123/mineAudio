package com.mineaudio.protocol;

import java.util.List;
import java.util.Map;

/** 所有协议消息 DTO（Gson 序列化，未知字段忽略）。 */
public final class Packets {

    private Packets() {
    }

    public record Hello(
            String modVersion,
            String minecraft,
            String locale,
            List<String> capabilities,
            List<String> formats) {
    }

    public record HelloAck(
            String serverVersion,
            int reportIntervalMs,
            int maxAmbientLayers,
            Sync sync,
            Firewall firewall,
            Library library) {

        public record Sync(boolean enabled, int pingIntervalMs, int driftThresholdMs) {
        }

        public record Firewall(boolean httpsOnly, boolean denyPrivateNetwork, int maxRedirects) {
        }

        /** 本地曲库上传托管信息（未启用时为 null）。 */
        public record Library(String baseUrl, String uploadUrl, String token) {
        }
    }

    public record Play(
            String trackId,
            String source,
            String sourceId,
            String url,
            Map<String, String> headers,
            int resourceVersion,
            long expiresAt,
            long serverStartTime,
            long positionMs,
            float volume,
            String bus,
            int fadeInMs,
            long durationHintMs,
            String title,
            String artist,
            String coverUrl,
            Spatial spatial) {

        public record Spatial(String world, double x, double y, double z, double radius, String rolloff) {
        }
    }

    public record Stop(String reason) {
    }

    public record Pause(long positionMs, long executeAtServerTime) {
    }

    public record Resume(long positionMs, long executeAtServerTime) {
    }

    public record Seek(
            long requestId,
            long positionMs,
            long executeAtServerTime) {
    }

    public record Volume(float volume, int transitionMs) {
    }

    public record State(
            int seq,
            String state,
            long positionMs,
            long durationMs,
            long bufferedMs,
            double bufferRatio,
            long rttMs,
            long driftMs,
            long lastCommandId,
            Error error) {

        public record Error(String code, String message) {
        }
    }

    public record Ping(long t0) {
    }

    public record Pong(long t0, long t1, long t2) {
    }

    public record ErrorReport(String code, String message) {
    }

    /** 客户端本地曲库上传完成后，请求服务端把该曲目加入全服队列。 */
    public record LibraryAdd(
            String audioId,
            String audioExt,
            String coverId,
            String coverExt,
            String title,
            String artist,
            String album,
            long durationMs) {
    }
}
