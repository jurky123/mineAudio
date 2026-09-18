package com.mineaudio.stream.resolve;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * 网易 eapi 最小实现（设计文档 Phase 8）：
 * params = AES-128-ECB( path + "-36cd479b6b5-" + json + "-36cd479b6b5-" + md5("nobody"+path+"use"+json+"md5forencrypt") )。
 * 仅实现播放 URL 解析所需部分，不含登录/搜索/歌单。
 */
public final class EapiCrypto {

    static final String SEPARATOR = "-36cd479b6b5-";
    private static final byte[] KEY = "e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII);

    private EapiCrypto() {
    }

    /** eapi digest：md5(nobody + path + use + json + md5forencrypt)。 */
    public static String digest(String path, String json) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] hash = md5.digest(("nobody" + path + "use" + json + "md5forencrypt")
                    .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 不可用", e);
        }
    }

    /** 生成 params 密文（大写十六进制）。 */
    public static String encrypt(String path, String json) {
        try {
            String payload = path + SEPARATOR + json + SEPARATOR + digest(path, json);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY, "AES"));
            byte[] encrypted = cipher.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().withUpperCase().formatHex(encrypted);
        } catch (Exception e) {
            throw new IllegalStateException("eapi 加密失败", e);
        }
    }

    /** 生成表单请求体：params=<encrypt>。 */
    public static String body(String path, String json) {
        return "params=" + java.net.URLEncoder.encode(encrypt(path, json), StandardCharsets.UTF_8);
    }

    /** eapi 响应解密（AES-128-ECB/PKCS5，同一密钥）。非法密文抛异常，由调用方归类。 */
    public static String decrypt(byte[] encrypted) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
