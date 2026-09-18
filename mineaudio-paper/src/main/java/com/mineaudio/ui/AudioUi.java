package com.mineaudio.ui;

import org.bukkit.entity.Player;

/** MineUI 音乐界面（可选集成）；未安装 MineUI 时为 Noop。 */
public interface AudioUi {

    boolean available();

    /** 打开音乐界面，未安装 MineUI 客户端或界面不可用时返回 false。 */
    boolean open(Player player);

    void close(Player player);

    void shutdown();
}
