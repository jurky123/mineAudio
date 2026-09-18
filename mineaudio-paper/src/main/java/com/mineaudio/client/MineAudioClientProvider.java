package com.mineaudio.client;

import java.util.Map;

import org.bukkit.entity.Player;

import com.mineaudio.MineAudioPlugin;
import com.mineaudio.api.AudioCapabilities;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.api.PlaybackHandle;
import com.mineaudio.protocol.Envelope;
import com.mineaudio.protocol.PacketType;
import com.mineaudio.protocol.Packets;
import com.mineaudio.protocol.ProtocolCodec;
import com.mineaudio.playback.NoopPlaybackHandle;
import com.mineaudio.stream.StreamPlaybackRequest;
import com.mineaudio.stream.StreamProvider;
import com.mineaudio.stream.resolve.ResolveException;
import com.mineaudio.stream.resolve.ResolveRequest;
import com.mineaudio.stream.resolve.ResolveResult;
import com.mineaudio.stream.resolve.StreamResolverChain;

/**
 * MineAudio Client Provider：服务端解析 URL、下发 PLAY，客户端直连 CDN 播放并回报状态。
 * 直链直接使用；source+id 走 Resolver 链，解析失败上报分类错误。
 */
public final class MineAudioClientProvider implements StreamProvider {

    public static final String ID = "mineaudio-client";

    private final MineAudioPlugin plugin;
    private final ClientProtocolService protocol;
    private final StreamResolverChain resolvers;

    public MineAudioClientProvider(MineAudioPlugin plugin, ClientProtocolService protocol,
                                   StreamResolverChain resolvers) {
        this.plugin = plugin;
        this.protocol = protocol;
        this.resolvers = resolvers;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean available(Player player) {
        if (player == null) return protocol.registry().hasAny();
        ClientConnectionRegistry.ClientInfo info = protocol.registry().get(player);
        return info != null && info.supports(ClientCapabilities.STREAM_PLAYBACK);
    }

    @Override
    public AudioCapabilities capabilities(Player player) {
        ClientConnectionRegistry.ClientInfo info = player == null ? null : protocol.registry().get(player);
        if (info == null || !info.supports(ClientCapabilities.STREAM_PLAYBACK)) {
            return AudioCapabilities.NONE;
        }
        return new AudioCapabilities(
                false,
                info.supports(ClientCapabilities.SEEK),
                info.supports(ClientCapabilities.PAUSE),
                info.supports(ClientCapabilities.VOLUME),
                info.supports(ClientCapabilities.FADE),
                false,
                info.supports(ClientCapabilities.POSITIONAL),
                info.supports(ClientCapabilities.SYNC),
                true,
                info.supports(ClientCapabilities.MULTI_SESSION),
                info.supports(ClientCapabilities.CACHE),
                false);
    }

    @Override
    public PlaybackHandle play(Player player, StreamPlaybackRequest request) {
        if (!available(player)) return NoopPlaybackHandle.stopped();
        AudioSource.Stream stream = request.source();
        ResolvingPlaybackHandle handle = new ResolvingPlaybackHandle();
        ResolveRequest resolveRequest = new ResolveRequest(stream.source(), stream.id(), stream.uri());
        resolvers.resolve(resolveRequest).whenComplete((result, error) ->
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (handle.cancelled()) return;
                    if (error != null) {
                        fallback(player, request, handle, error);
                        return;
                    }
                    handle.attach(sendPlay(player, request, result));
                }));
        return handle;
    }

    private ClientStreamHandle sendPlay(Player player, StreamPlaybackRequest request, ResolveResult result) {
        AudioTrack track = request.track();
        AudioSource.Stream stream = request.source();
        String title = result.title() != null ? result.title() : track.metadata().title();
        String artist = result.artist() != null ? result.artist() : track.metadata().author();
        long duration = result.durationMs() > 0 ? result.durationMs() : track.metadata().durationMs();
        Packets.Play play = new Packets.Play(
                track.id().asString(),
                stream.source(),
                stream.id(),
                result.streamUrl().toString(),
                Map.of(),
                1,
                result.expiresAt() == null ? 0 : result.expiresAt().toEpochMilli(),
                request.timing().serverStartTimeMs(),
                request.timing().positionMs(),
                request.options().volume(),
                track.bus().name(),
                request.options().fadeInMs(),
                duration,
                title,
                artist,
                null);
        protocol.send(player, Envelope.session(PacketType.PLAY, request.sessionId().toString(),
                request.timing().revision(), ProtocolCodec.data(play)));
        return new ClientStreamHandle(plugin, protocol, player, request.sessionId());
    }

    /** 解析失败：上报分类错误，交由 UI/PAPI 展示（无外部插件降级）。 */
    private void fallback(Player player, StreamPlaybackRequest request,
                          ResolvingPlaybackHandle handle, Throwable error) {
        ResolveException resolve = resolveException(error);
        String kind = resolve == null ? "RESOLVE_FAILED" : resolve.kind().name();
        String message = resolve == null ? String.valueOf(error.getMessage()) : resolve.getMessage();
        plugin.getLogger().warning("[client] 解析失败（" + kind + "）：" + message);
        protocol.send(player, Envelope.session(PacketType.ERROR, request.sessionId().toString(),
                request.timing().revision(),
                ProtocolCodec.data(new Packets.ErrorReport(kind, message))));
        handle.fail(kind, message);
    }

    private static ResolveException resolveException(Throwable error) {
        if (error instanceof ResolveException resolve) return resolve;
        if (error != null && error.getCause() instanceof ResolveException resolve) return resolve;
        return null;
    }
}
