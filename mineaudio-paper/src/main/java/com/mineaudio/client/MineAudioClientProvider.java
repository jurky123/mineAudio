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

/**
 * MineAudio Client Provider：服务端解析 URL、下发 PLAY，客户端直连 CDN 播放并回报状态。
 * V1 支持直链（uri）；source+id 的服务端 Resolver 在后续阶段接入。
 */
public final class MineAudioClientProvider implements StreamProvider {

    public static final String ID = "mineaudio-client";

    private final MineAudioPlugin plugin;
    private final ClientProtocolService protocol;

    public MineAudioClientProvider(MineAudioPlugin plugin, ClientProtocolService protocol) {
        this.plugin = plugin;
        this.protocol = protocol;
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
        String url = stream.uri();
        if (url == null) {
            plugin.getLogger().warning("[client] 流媒体曲目缺少可播放 URL（Resolver 未接入）："
                    + stream.source() + ":" + stream.id());
            return NoopPlaybackHandle.stopped();
        }
        AudioTrack track = request.track();
        Packets.Play play = new Packets.Play(
                track.id().asString(),
                stream.source(),
                stream.id(),
                url,
                Map.of(),
                1,
                0,
                request.timing().serverStartTimeMs(),
                request.timing().positionMs(),
                request.options().volume(),
                track.bus().name(),
                request.options().fadeInMs(),
                track.metadata().durationMs(),
                track.metadata().title(),
                track.metadata().author(),
                null);
        protocol.send(player, Envelope.session(PacketType.PLAY, request.sessionId().toString(),
                request.timing().revision(), ProtocolCodec.data(play)));
        return new ClientStreamHandle(plugin, protocol, player, request.sessionId());
    }
}
