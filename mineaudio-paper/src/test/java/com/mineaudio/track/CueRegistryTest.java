package com.mineaudio.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Proxy;

import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioCue;
import com.mineaudio.api.AudioSource;
import com.mineaudio.config.YamlFile;

import net.kyori.adventure.key.Key;

class CueRegistryTest {

    @Test
    void cueDefaultsToSfxBusAndParsesNestedPrimary() {
        CueRegistry registry = new CueRegistry();
        registry.load(YamlFile.parse("""
                cues:
                  mineuno:card.play:
                    primary:
                      type: PACK
                      sound: mineuno:card.play
                    fallback:
                      type: VANILLA
                      sound: minecraft:item.book.page_turn
                """).section("cues"), warning -> fail(warning));

        AudioCue cue = registry.get(Key.key("mineuno:card.play")).orElseThrow();
        assertEquals(AudioBus.SFX, cue.bus());
        assertEquals(new AudioSource.PackSound(Key.key("mineuno:card.play")), cue.primary());
        assertEquals(new AudioSource.VanillaSound(Key.key("minecraft:item.book.page_turn")), cue.fallback());
    }

    @Test
    void runtimeRegistrationOverridesYamlAndUnregistersByOwner() {
        CueRegistry registry = new CueRegistry();
        registry.load(YamlFile.parse("""
                cues:
                  test:cue:
                    type: VANILLA
                    sound: minecraft:ui.button.click
                """).section("cues"), warning -> fail(warning));

        Key key = Key.key("test:cue");
        AudioCue yamlCue = registry.get(key).orElseThrow();

        Plugin ownerA = plugin("A");
        Plugin ownerB = plugin("B");
        AudioCue runtimeA = new AudioCue(key, AudioBus.SFX, new AudioSource.VanillaSound(Key.key("minecraft:block.note_block.pling")));
        AudioCue runtimeB = new AudioCue(key, AudioBus.UI, new AudioSource.VanillaSound(Key.key("minecraft:block.note_block.bass")));

        registry.register(ownerA, runtimeA);
        assertEquals(runtimeA, registry.get(key).orElseThrow());

        registry.register(ownerB, runtimeB);
        assertEquals(runtimeB, registry.get(key).orElseThrow());

        // 后注册者注销后回到 YAML 定义（最后一次注册为准，不保留历史栈）
        registry.unregister(ownerB);
        assertEquals(yamlCue, registry.get(key).orElseThrow());

        registry.register(ownerA, runtimeA);
        registry.unregister(ownerA);
        assertEquals(yamlCue, registry.get(key).orElseThrow());
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(CueRegistryTest.class.getClassLoader(), new Class<?>[]{Plugin.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getName", "toString" -> name;
                    default -> null;
                });
    }
}
