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
}
