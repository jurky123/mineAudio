package com.mineaudio.client.library;

import java.nio.file.Path;

/**
 * 元数据解析器：由平台层（Fabric）实现（jaudiotagger 读标签/封面，LavaPlayer 兜底时长）。
 * 放在 core 里是为了让 {@link LocalLibrary} 保持纯 Java、可单测。
 */
@FunctionalInterface
public interface MetadataProbe {

    /** 解析文件元数据；实现不得抛出异常（失败返回兜底值）。 */
    TrackMeta probe(Path file);
}
