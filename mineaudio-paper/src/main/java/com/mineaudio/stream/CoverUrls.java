package com.mineaudio.stream;

/**
 * 封面 URL 处理：网易 CDN 支持 ?param=WxY 服务端缩放。
 * 统一取 64px 缩略图，降低传输/解码压力，配合客户端最近邻放大更贴近像素风。
 */
public final class CoverUrls {

    public static final int THUMB_PX = 64;

    private CoverUrls() {
    }

    /** 网易封面加缩略参数（px<=0 表示保留原图）；其他 URL 原样返回。 */
    public static String thumb(String url, int px) {
        if (url == null || url.isBlank() || px <= 0) {
            return url;
        }
        if (!url.contains("music.126.net") || url.contains("?")) {
            return url;
        }
        return url + "?param=" + px + "y" + px;
    }

    public static String thumb(String url) {
        return thumb(url, THUMB_PX);
    }
}
