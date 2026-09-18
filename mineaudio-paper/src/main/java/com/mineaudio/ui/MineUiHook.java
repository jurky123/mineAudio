package com.mineaudio.ui;

import org.bukkit.Bukkit;

import com.mineaudio.MineAudioPlugin;

/**
 * 通过反射加载 MineUI 集成，避免未安装 MineUI 时抛 NoClassDefFoundError。
 * 与 MineChess / MineSkin 的集成模式保持一致。
 */
public final class MineUiHook {

    private MineUiHook() {
    }

    public static AudioUi create(MineAudioPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("MineUI") == null) {
            return new NoopAudioUi();
        }
        try {
            Class<?> type = Class.forName("com.mineaudio.integration.MineUiIntegration");
            AudioUi ui = (AudioUi) type.getConstructor(MineAudioPlugin.class).newInstance(plugin);
            return ui.available() ? ui : new NoopAudioUi();
        } catch (Throwable t) {
            plugin.getLogger().warning("MineUI API 不匹配（可能是旧版 MineUI），音乐界面禁用：" + t);
            return new NoopAudioUi();
        }
    }
}
