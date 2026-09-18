package com.mineaudio.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** JSON 编解码与校验：UTF-8、无压缩、未知字段忽略。 */
public final class ProtocolCodec {

    private static final Gson GSON = new Gson();

    private ProtocolCodec() {
    }

    public static byte[] encode(Envelope envelope) {
        JsonObject root = new JsonObject();
        root.addProperty("protocol", envelope.protocol());
        root.addProperty("type", envelope.type().name());
        if (envelope.session() != null) root.addProperty("session", envelope.session());
        if (envelope.revision() > 0) root.addProperty("revision", envelope.revision());
        root.add("data", envelope.data() == null ? new JsonObject() : envelope.data());
        return GSON.toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    public static Envelope decode(byte[] bytes) throws ProtocolException {
        if (bytes == null || bytes.length == 0) {
            throw new ProtocolException("empty packet");
        }
        if (bytes.length > ProtocolLimits.MAX_PACKET_BYTES) {
            throw new ProtocolException("packet too large: " + bytes.length);
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new ProtocolException("invalid json", e);
        }
        int protocol = root.has("protocol") ? root.get("protocol").getAsInt() : -1;
        if (protocol != ProtocolVersion.PROTOCOL) {
            throw new ProtocolException("unsupported protocol: " + protocol);
        }
        PacketType type = PacketType.from(root.has("type") ? root.get("type").getAsString() : null);
        String session = root.has("session") && root.get("session").isJsonPrimitive()
                ? root.get("session").getAsString() : null;
        int revision = root.has("revision") ? root.get("revision").getAsInt() : 0;
        JsonObject data = root.has("data") && root.get("data").isJsonObject()
                ? root.getAsJsonObject("data") : new JsonObject();
        return new Envelope(protocol, type, session, revision, data);
    }

    public static <T> T data(Envelope envelope, Class<T> type) throws ProtocolException {
        try {
            return GSON.fromJson(envelope.data(), type);
        } catch (RuntimeException e) {
            throw new ProtocolException("invalid " + envelope.type() + " data", e);
        }
    }

    public static JsonObject data(Object value) {
        return GSON.toJsonTree(value).getAsJsonObject();
    }

    public static void validatePlay(Packets.Play play) throws ProtocolException {
        if (play.url() == null || play.url().isBlank()) {
            throw new ProtocolException("PLAY missing url");
        }
        if (play.url().length() > ProtocolLimits.MAX_URL_LENGTH) {
            throw new ProtocolException("PLAY url too long");
        }
        if (play.trackId() != null && play.trackId().length() > ProtocolLimits.MAX_ID_LENGTH) {
            throw new ProtocolException("PLAY trackId too long");
        }
        Map<String, String> headers = play.headers();
        if (headers != null) {
            if (headers.size() > ProtocolLimits.MAX_HEADER_COUNT) {
                throw new ProtocolException("PLAY too many headers");
            }
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getValue() != null
                        && entry.getValue().length() > ProtocolLimits.MAX_HEADER_VALUE_LENGTH) {
                    throw new ProtocolException("PLAY header too long: " + entry.getKey());
                }
            }
        }
    }
}
