package com.mineaudio.integration;

import org.bukkit.Bukkit;

import com.mineaudio.MineAudioPlugin;

/** 通过反射注册 PlaceholderAPI 扩展，未安装时返回空操作。 */
public final class PlaceholderHook {

    private PlaceholderHook() {
    }

    /** 返回注销动作。 */
    public static Runnable create(MineAudioPlugin plugin) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return () -> {
            };
        }
        try {
            Class<?> type = Class.forName("com.mineaudio.integration.MineAudioPlaceholders");
            Object expansion = type.getConstructor(MineAudioPlugin.class).newInstance(plugin);
            return () -> {
                try {
                    type.getMethod("unregister").invoke(expansion);
                } catch (Throwable ignored) {
                    // 插件卸载时忽略
                }
            };
        } catch (Throwable t) {
            plugin.getLogger().warning("PlaceholderAPI 扩展注册失败：" + t);
            return () -> {
            };
        }
    }
}
