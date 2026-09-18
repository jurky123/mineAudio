package com.mineaudio.ui;

import org.bukkit.entity.Player;

/** MineUI 不可用时的占位实现。 */
public final class NoopAudioUi implements AudioUi {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean open(Player player) {
        return false;
    }

    @Override
    public void close(Player player) {
    }

    @Override
    public void shutdown() {
    }
}
