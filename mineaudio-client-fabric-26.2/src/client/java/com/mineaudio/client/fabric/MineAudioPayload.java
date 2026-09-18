package com.mineaudio.client.fabric;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 唯一载荷：内部承载 mineaudio-protocol 的 UTF-8 JSON 字节。 */
public record MineAudioPayload(byte[] data) implements CustomPacketPayload {

    public static final Identifier ID = Identifier.fromNamespaceAndPath("mineaudio", "stream");

    public static final CustomPacketPayload.Type<MineAudioPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, MineAudioPayload> CODEC = StreamCodec.of(
            (buf, payload) -> buf.writeBytes(payload.data()),
            buf -> {
                byte[] data = new byte[buf.readableBytes()];
                buf.readBytes(data);
                return new MineAudioPayload(data);
            }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
