package com.mineaudio.stream.resolve;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.mineaudio.library.LibraryHost;

/**
 * 本地曲库托管解析器：把 {@code source="library"} 的 id 解析为 MineAudio 自托管 URL。
 * id 形态：{@code audioId|audioExt|coverId|coverExt}（封面可空）；缓存过期后返回 NOT_PLAYABLE。
 */
public final class LibraryResolver implements StreamResolver {

    public static final String SOURCE = "library";

    private final LibraryHost host;

    public LibraryResolver(LibraryHost host) {
        this.host = host;
    }

    @Override
    public String id() {
        return "library";
    }

    @Override
    public boolean supports(ResolveRequest request) {
        return SOURCE.equalsIgnoreCase(request.source());
    }

    @Override
    public CompletionStage<ResolveResult> resolve(ResolveRequest request) {
        String id = request.id();
        if (host == null || id == null || id.isBlank()) {
            return CompletableFuture.failedFuture(
                    new ResolveException(ResolveFailureKind.DISABLED, "本地托管未启用"));
        }
        String[] parts = id.split("\\|", -1);
        if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()
                || !host.contains(parts[0], parts[1])) {
            return CompletableFuture.failedFuture(
                    new ResolveException(ResolveFailureKind.NOT_PLAYABLE, "本地托管文件不存在或已过期"));
        }
        String cover = null;
        if (parts.length >= 4 && !parts[2].isBlank() && !parts[3].isBlank()) {
            cover = host.mediaUrl(parts[2], parts[3]);
        }
        return CompletableFuture.completedFuture(new ResolveResult(
                URI.create(host.mediaUrl(parts[0], parts[1])), null, null, 0, null, cover));
    }
}
