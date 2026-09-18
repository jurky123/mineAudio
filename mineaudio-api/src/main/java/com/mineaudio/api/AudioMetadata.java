package com.mineaudio.api;

/** 曲目元信息；durationMs &lt;= 0 表示未知（PACK / Vanilla 循环需要时长）。 */
public record AudioMetadata(String title, String author, long durationMs) {

    public static final AudioMetadata EMPTY = new AudioMetadata("", "", -1);

    public AudioMetadata {
        title = title == null ? "" : title;
        author = author == null ? "" : author;
    }
}
