package com.mineaudio.ui;

import org.bukkit.entity.Player;

/** MineUI 音乐界面（可选集成）；未安装 MineUI 时为 Noop。 */
public interface AudioUi {

    boolean available();

    /** 打开音乐界面，未安装 MineUI 客户端或界面不可用时返回 false。 */
    boolean open(Player player);

    /** 该玩家是否支持“正在播放”HUD（MineUI 0.8+ 客户端）。 */
    default boolean hudSupported(Player player) {
        return false;
    }

    /** 切换“正在播放”HUD；返回 true 表示当前已显示。不支持时返回 false。 */
    default boolean toggleHud(Player player) {
        return false;
    }

    void close(Player player);

    void shutdown();
}
