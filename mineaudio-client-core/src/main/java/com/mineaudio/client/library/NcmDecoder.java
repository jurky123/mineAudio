package com.mineaudio.client.library;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 网易云 .ncm 解密（纯 Java，JDK 自带 AES/Base64，无新依赖）。
 *
 * <p>容器结构：magic(8) + gap(2) + keyLen(4) + key + metaLen(4) + meta + crc(4) + gap(5)
 * + imageSize(4) + image + audio。key/meta 分别 XOR + AES-ECB/PKCS5 解密；
 * 音频用 keyBox 派生密钥流异或还原为原始 mp3/flac。</p>
 *
 * <p>仅用于解密玩家自有、已购买的本地文件，输出到本地缓存目录，不上传网易云。</p>
 */
public final class NcmDecoder {

    private static final byte[] MAGIC = "CTENFDAM".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] META_KEY = "hzHRAmso5kInbaxW".getBytes(StandardCharsets.US_ASCII);
    private static final String KEY_PREFIX = "neteasecloudmusic";
    private static final String META_PREFIX = "music:";

    /** 解密结果：可播放文件与从 NCM 提取的元数据。 */
    public record Decoded(Path audio, long durationMs, String title, String artist, String album,
                          byte[] cover, String coverExt) {
    }

    /** 是否疑似 .ncm（按扩展名）。 */
    public static boolean isNcm(Path file) {
        return extension(file).equals("ncm");
    }

    /**
     * 解密到 {@code outDir/<sha256(source)>.mp3|flac}；已存在且大小一致则直接复用。
     */
    public Decoded decode(Path source, Path outDir) throws IOException {
        byte[] raw = Files.readAllBytes(source);
        Cursor cursor = new Cursor(raw);
        byte[] magic = cursor.read(8);
        if (!java.util.Arrays.equals(magic, MAGIC)) {
            throw new IOException("不是有效的 .ncm（magic 不匹配）");
        }
        byte[] gap = cursor.read(2);

        int keyLen = cursor.readIntLE();
        byte[] keyRaw = cursor.read(keyLen);
        byte[] keyBox = deriveKeyBox(keyRaw, keyLen, gap);

        JsonObject meta = readMeta(cursor);
        cursor.skip(4); // crc
        cursor.skip(5); // gap
        int imageSize = cursor.readIntLE();
        byte[] cover = cursor.read(imageSize);
        byte[] audio = cursor.read(cursor.remaining());
        decryptAudio(audio, keyBox);

        String format = meta != null && meta.has("format") ? meta.get("format").getAsString() : "mp3";
        String ext = format == null || format.isBlank() ? "mp3" : format.toLowerCase(java.util.Locale.ROOT);
        Files.createDirectories(outDir);
        Path out = outDir.resolve(LocalLibrary.sha256(source) + "." + ext);
        if (!Files.isRegularFile(out) || Files.size(out) != audio.length) {
            Files.write(out, audio);
        }

        long duration = meta != null && meta.has("duration") ? meta.get("duration").getAsLong() : -1;
        String title = optString(meta, "musicName");
        String artist = optArtist(meta);
        String album = optString(meta, "album");
        return new Decoded(out, duration, title, artist, album, cover, coverExt(cover));
    }

    /** 从 key 段派生 keyBox：XOR 0x64 与否两种变体都试，用 "neteasecloudmusic" 前缀校验。 */
    private static byte[] deriveKeyBox(byte[] keyRaw, int keyLen, byte[] gap) throws IOException {
        String lastError = null;
        for (boolean xor : new boolean[] {true, false}) {
            byte[] candidate = keyRaw.clone();
            if (xor) {
                for (int i = 0; i < candidate.length; i++) candidate[i] ^= 0x64;
            }
            if (candidate.length == 0 || candidate.length % 16 != 0) {
                lastError = "len=" + candidate.length + "(len%16=" + (candidate.length % 16) + ")";
                continue;
            }
            try {
                byte[] plain = aesDecrypt(candidate, META_KEY, "key", "keyLen=" + keyLen);
                if (plain.length > KEY_PREFIX.length() && startsWith(plain, KEY_PREFIX)) {
                    byte[] key = java.util.Arrays.copyOfRange(plain, KEY_PREFIX.length(), plain.length);
                    return buildKeyBox(key);
                }
                lastError = "前缀不匹配(xor=" + xor + ")";
            } catch (IOException e) {
                lastError = e.getMessage();
            }
        }
        throw new IOException("key 解密失败（" + lastError + "，keyLen=" + keyLen
                + " gap=" + hex(gap, 2) + " keyHead=" + hex(keyRaw, 8) + "）");
    }

    /** meta：XOR 0x63 与否两种变体都试，用 JSON 结构校验。 */
    private JsonObject readMeta(Cursor cursor) throws IOException {
        int metaLen = cursor.readIntLE();
        byte[] raw = cursor.read(metaLen);
        String lastError = null;
        for (boolean xor : new boolean[] {true, false}) {
            try {
                JsonObject meta = tryMeta(raw, xor);
                if (meta != null) return meta;
            } catch (Throwable t) {
                lastError = t.getMessage();
            }
        }
        throw new IOException("meta 解析失败（metaLen=" + metaLen + "）"
                + (lastError == null ? "" : "：" + lastError));
    }

    private JsonObject tryMeta(byte[] raw, boolean xor) throws IOException {
        byte[] text = raw.clone();
        if (xor) {
            for (int i = 0; i < text.length; i++) text[i] ^= 0x63;
        }
        byte[] decoded;
        try {
            decoded = Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (decoded.length == 0 || decoded.length % 16 != 0) return null;
        byte[] plain = aesDecrypt(decoded, META_KEY, "meta", "len=" + decoded.length);
        if (startsWith(plain, META_PREFIX)) {
            plain = java.util.Arrays.copyOfRange(plain, META_PREFIX.length(), plain.length);
        }
        String json = new String(plain, StandardCharsets.UTF_8);
        int brace = json.indexOf('{');
        if (brace > 0) json = json.substring(brace);
        try {
            return JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean startsWith(byte[] data, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.US_ASCII);
        if (data.length < p.length) return false;
        for (int i = 0; i < p.length; i++) {
            if (data[i] != p[i]) return false;
        }
        return true;
    }

    private static String optString(JsonObject meta, String key) {
        if (meta == null || !meta.has(key) || meta.get(key).isJsonNull()) return null;
        String value = meta.get(key).getAsString();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** NCM 的 artist 为 [["名字","id"], ...]。 */
    private static String optArtist(JsonObject meta) {
        if (meta == null || !meta.has("artist") || meta.get("artist").isJsonNull()) return null;
        try {
            JsonArray artists = meta.getAsJsonArray("artist");
            StringBuilder sb = new StringBuilder();
            for (var element : artists) {
                if (element.isJsonArray() && element.getAsJsonArray().size() > 0) {
                    String name = element.getAsJsonArray().get(0).getAsString();
                    if (name != null && !name.isBlank()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(name.trim());
                    }
                } else if (element.isJsonPrimitive()) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(element.getAsString().trim());
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] aesDecrypt(byte[] data, byte[] key, String stage, String detail) throws IOException {
        if (data.length == 0 || data.length % 16 != 0) {
            throw new IOException("AES(" + stage + ") 长度非法：" + data.length + "B（" + detail + "）");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IOException("AES(" + stage + ") 解密失败：" + e.getMessage() + "（" + detail + "）", e);
        }
    }

    private static byte[] buildKeyBox(byte[] key) {
        byte[] box = new byte[256];
        for (int i = 0; i < 256; i++) {
            box[i] = (byte) i;
        }
        int keyOffset = 0;
        byte scramble = 0;
        for (int i = 0; i < 256; i++) {
            int keyByte = key[keyOffset] & 0xFF;
            byte boxValue = box[i];
            scramble = (byte) (scramble + boxValue + keyByte);
            box[i] = box[scramble & 0xFF];
            box[scramble & 0xFF] = boxValue;
            keyOffset = (keyOffset + 1) % key.length;
        }
        return box;
    }

    private static void decryptAudio(byte[] data, byte[] keyBox) {
        for (int i = 0; i < data.length; i++) {
            int j = (i + 1) & 0xFF;
            data[i] ^= keyBox[(keyBox[j] + keyBox[(keyBox[j] + j) & 0xFF]) & 0xFF];
        }
    }

    private static String coverExt(byte[] cover) {
        if (cover == null || cover.length < 4) return null;
        if ((cover[0] & 0xFF) == 0xFF && (cover[1] & 0xFF) == 0xD8) return "jpg";
        if ((cover[0] & 0xFF) == 0x89 && cover[1] == 'P' && cover[2] == 'N' && cover[3] == 'G') return "png";
        return "jpg";
    }

    private static String hex(byte[] data, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(count, data.length); i++) {
            sb.append(String.format("%02x", data[i] & 0xFF));
        }
        return sb.toString();
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    /** 小端游标。 */
    private static final class Cursor {
        private final byte[] data;
        private int position;

        Cursor(byte[] data) {
            this.data = data;
        }

        int remaining() {
            return data.length - position;
        }

        byte[] read(int length) throws IOException {
            if (length < 0 || position + length > data.length) {
                throw new IOException(".ncm 结构越界（需要 " + length + " 字节，剩余 " + remaining() + "）");
            }
            byte[] out = new byte[length];
            System.arraycopy(data, position, out, 0, length);
            position += length;
            return out;
        }

        void skip(int length) throws IOException {
            read(length);
        }

        int readIntLE() throws IOException {
            if (position + 4 > data.length) throw new IOException(".ncm 读取 int 越界");
            int value = (data[position] & 0xFF)
                    | ((data[position + 1] & 0xFF) << 8)
                    | ((data[position + 2] & 0xFF) << 16)
                    | ((data[position + 3] & 0xFF) << 24);
            position += 4;
            return value;
        }
    }
}
