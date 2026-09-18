package com.mineaudio.api;

import java.util.Objects;

import net.kyori.adventure.key.Key;

/**
 * 声音来源。业务插件只描述“素材是什么”，由 MineAudio 选择 Backend 播放。
 */
public sealed interface AudioSource {

    /** 资源包自定义声音，如 {@code mineuno:card.play}。 */
    record PackSound(Key sound) implements AudioSource {
        public PackSound {
            Objects.requireNonNull(sound, "sound");
        }
    }

    /** 原版声音，如 {@code minecraft:item.book.page_turn}。 */
    record VanillaSound(Key sound) implements AudioSource {
        public VanillaSound {
            Objects.requireNonNull(sound, "sound");
        }
    }

    /** NBS 曲目，文件位于 plugins/MineAudio/nbs/。 */
    record Nbs(String file) implements AudioSource {
        public Nbs {
            Objects.requireNonNull(file, "file");
        }
    }

    /**
     * 流媒体曲目：服务端只发播放控制，客户端直连音源播放。
     * <p>
     * 两种写法二选一：
     * <ul>
     *   <li>稳定引用：{@code provider + source + id}（如 moemusic + netease + 歌曲 ID）</li>
     *   <li>直链：{@code provider + uri}（默认禁用，需管理员开启并配置白名单）</li>
     * </ul>
     */
    record Stream(String provider, String source, String id, String uri) implements AudioSource {
        public Stream {
            Objects.requireNonNull(provider, "provider");
            if (uri == null && (source == null || source.isBlank() || id == null || id.isBlank())) {
                throw new IllegalArgumentException("Stream 需要 uri 或 source+id");
            }
        }
    }
}
