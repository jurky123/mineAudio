package com.mineaudio.client.fabric.library;

import java.nio.file.Path;

import com.mineaudio.client.library.LocalLibrary;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 客户端本地曲库服务：固定目录 {@code <config>/mineaudio/library/}，
 * 启动/手动刷新时在后台线程扫描（解析标签与封面），供 MineUI 本地页展示与试听。
 */
public final class LocalLibraryService {

    private final LocalLibrary library;

    public LocalLibraryService() {
        this(defaultDirectory());
    }

    public LocalLibraryService(Path directory) {
        this.library = new LocalLibrary(directory, new JaudiotaggerProbe());
    }

    private static Path defaultDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("mineaudio").resolve("library");
    }

    public LocalLibrary library() {
        return library;
    }

    /** 确保本地项可播放（.ncm 按需解密）；只在后台线程调用。 */
    public Path ensureDecoded(com.mineaudio.client.library.LocalTrack track) throws java.io.IOException {
        return library.ensureDecoded(track);
    }

    public byte[] coverBytes(com.mineaudio.client.library.LocalTrack track) {
        return library.coverBytes(track);
    }

    /** 后台扫描（不阻塞渲染线程）。 */
    public void scanAsync() {
        Thread thread = new Thread(() -> {
            library.scan();
            com.mineaudio.client.fabric.MineAudioFabricClient.LOGGER.info(
                    "[library] 扫描完成：{}（目录 {}）", library.note(), library.directory());
        }, "MineAudio-Library");
        thread.setDaemon(true);
        thread.start();
    }

    public void init() {
        scanAsync();
    }
}
