package com.mineaudio.client.library;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/** 测试用：按 NcmDecoder 的算法构造一个最小 .ncm（编码与解码互逆）。 */
final class NcmFixtures {

    private static final byte[] META_KEY = "hzHRAmso5kInbaxW".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] KEY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    private NcmFixtures() {
    }

    static byte[] buildNcm(String metaJson, byte[] cover, byte[] audio) throws Exception {
        byte[] metaBlob = aesEncrypt(concat("music:".getBytes(StandardCharsets.US_ASCII),
                metaJson.getBytes(StandardCharsets.UTF_8)), META_KEY);
        metaBlob = concat("163 key(Don't modify it):".getBytes(StandardCharsets.US_ASCII),
                Base64.getEncoder().encode(metaBlob));
        for (int i = 0; i < metaBlob.length; i++) metaBlob[i] ^= 0x63;
        return assemble(metaBlob, cover, audio);
    }

    /** 直接用给定的（已存储形态的）meta 段构造 .ncm，用于测试 meta 解析失败时的容错。 */
    static byte[] buildNcmRawMeta(byte[] storedMeta, byte[] cover, byte[] audio) throws Exception {
        return assemble(storedMeta, cover, audio);
    }

    private static byte[] assemble(byte[] storedMeta, byte[] cover, byte[] audio) throws Exception {
        byte[] keyBlob = aesEncrypt(concat("neteasecloudmusic".getBytes(StandardCharsets.US_ASCII), KEY), META_KEY);
        for (int i = 0; i < keyBlob.length; i++) keyBlob[i] ^= 0x64;

        byte[] keyBox = buildKeyBox(KEY);
        byte[] encAudio = audio.clone();
        decrypt(encAudio, keyBox);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("CTENFDAM".getBytes(StandardCharsets.US_ASCII));
        out.write(new byte[2]);
        writeInt(out, keyBlob.length);
        out.write(keyBlob);
        writeInt(out, storedMeta.length);
        out.write(storedMeta);
        writeInt(out, 0);
        out.write(new byte[5]);
        writeInt(out, cover.length);
        out.write(cover);
        out.write(encAudio);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static byte[] aesEncrypt(byte[] data, byte[] key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static byte[] buildKeyBox(byte[] key) {
        byte[] box = new byte[256];
        for (int i = 0; i < 256; i++) box[i] = (byte) i;
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

    private static void decrypt(byte[] data, byte[] keyBox) {
        for (int i = 0; i < data.length; i++) {
            int j = (i + 1) & 0xFF;
            data[i] ^= keyBox[(keyBox[j] + keyBox[(keyBox[j] + j) & 0xFF]) & 0xFF];
        }
    }
}
