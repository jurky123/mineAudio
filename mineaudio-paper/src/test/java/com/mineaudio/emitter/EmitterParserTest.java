package com.mineaudio.emitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mineaudio.config.YamlFile;

import net.kyori.adventure.key.Key;

class EmitterParserTest {

    private final List<String> warnings = new ArrayList<>();

    private List<AudioEmitter> parse(String yaml) {
        return EmitterParser.parse(YamlFile.parse(yaml).section("emitters"), warnings::add);
    }

    @Test
    void parsesEmitter() {
        List<AudioEmitter> emitters = parse("""
                emitters:
                  tavern_radio:
                    world: world
                    x: 135
                    y: 65
                    z: -21
                    track: tavern
                    radius: 24
                    trigger: REDSTONE
                    loop: true
                """);

        assertEquals(1, emitters.size());
        AudioEmitter emitter = emitters.get(0);
        assertEquals("tavern_radio", emitter.id());
        assertEquals("world", emitter.world());
        assertEquals(135, emitter.x(), 0.0001);
        assertEquals(Key.key("mineaudio:tavern"), emitter.track());
        assertEquals(24, emitter.radius(), 0.0001);
        assertEquals(Trigger.REDSTONE, emitter.trigger());
        assertTrue(emitter.loop());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void rejectsMissingTrack() {
        List<AudioEmitter> emitters = parse("""
                emitters:
                  broken:
                    world: world
                    x: 0
                    y: 0
                    z: 0
                """);
        assertTrue(emitters.isEmpty());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void unknownTriggerFallsBackToAlways() {
        List<AudioEmitter> emitters = parse("""
                emitters:
                  odd:
                    world: world
                    x: 1
                    y: 2
                    z: 3
                    track: tavern
                    trigger: PROXIMITY
                """);
        assertEquals(1, emitters.size());
        assertEquals(Trigger.ALWAYS, emitters.get(0).trigger());
        assertFalse(warnings.isEmpty());
    }
}
