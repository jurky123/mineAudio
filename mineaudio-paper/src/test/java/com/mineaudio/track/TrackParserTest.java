package com.mineaudio.track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mineaudio.api.AudioBus;
import com.mineaudio.api.AudioSource;
import com.mineaudio.api.AudioTrack;
import com.mineaudio.config.YamlNode;

import net.kyori.adventure.key.Key;

class TrackParserTest {

    private final List<String> warnings = new ArrayList<>();

    private YamlNode tracks(String yaml) {
        return com.mineaudio.config.YamlFile.parse(yaml).section("tracks");
    }

    @Test
    void parsesPackTrackWithFallbackAndOptions() {
        YamlNode root = tracks("""
                tracks:
                  spawn:
                    type: PACK
                    bus: MUSIC
                    sound: mineaudio:music.spawn
                    duration: 180000
                    loop: true
                    volume: 0.7
                    title: 出生点主题曲
                    fallback:
                      type: VANILLA
                      sound: minecraft:music.overworld.forest
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        AudioTrack track = registry.get(Key.key("mineaudio:spawn")).orElseThrow();
        assertEquals(AudioBus.MUSIC, track.bus());
        assertEquals(new AudioSource.PackSound(Key.key("mineaudio:music.spawn")), track.primary());
        assertEquals(new AudioSource.VanillaSound(Key.key("minecraft:music.overworld.forest")), track.fallback());
        assertEquals(0.7f, track.options().volume(), 0.0001f);
        assertTrue(track.options().loop());
        assertEquals(180000, track.metadata().durationMs());
        assertEquals("出生点主题曲", track.metadata().title());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void keepsDottedTrackIdIntact() {
        YamlNode root = tracks("""
                tracks:
                  mineuno:card.play:
                    type: PACK
                    sound: mineuno:card.play
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        assertTrue(registry.contains(Key.key("mineuno:card.play")));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void parsesNbsTrackWithDefaultBus() {
        YamlNode root = tracks("""
                tracks:
                  tavern:
                    type: NBS
                    file: tavern.nbs
                    loop: true
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        AudioTrack track = registry.get(Key.key("mineaudio:tavern")).orElseThrow();
        assertEquals(AudioBus.MUSIC, track.bus());
        assertEquals(new AudioSource.Nbs("tavern.nbs"), track.primary());
    }

    @Test
    void parsesStreamTrack() {
        YamlNode root = tracks("""
                tracks:
                  radio:
                    type: STREAM
                    bus: MUSIC
                    provider: mineaudio
                    source: netease
                    id: "1234567890"
                    fallback:
                      type: PACK
                      sound: mineaudio:music.demo
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        AudioTrack track = registry.get(Key.key("mineaudio:radio")).orElseThrow();
        assertEquals(new AudioSource.Stream("mineaudio", "netease", "1234567890", null), track.primary());
        assertTrue(track.fallback() instanceof AudioSource.PackSound);
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void parsesStreamUriTrack() {
        YamlNode root = tracks("""
                tracks:
                  web:
                    type: STREAM
                    uri: "https://cdn.example.com/a.mp3"
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        AudioTrack track = registry.get(Key.key("mineaudio:web")).orElseThrow();
        assertEquals(new AudioSource.Stream("mineaudio", null, null, "https://cdn.example.com/a.mp3"),
                track.primary());
    }

    @Test
    void rejectsStreamWithoutReference() {
        YamlNode root = tracks("""
                tracks:
                  broken:
                    type: STREAM
                    bus: MUSIC
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        assertEquals(0, registry.size());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void rejectsNbsPathTraversal() {
        YamlNode root = tracks("""
                tracks:
                  evil:
                    type: NBS
                    file: ../evil.nbs
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        assertEquals(0, registry.size());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void fallsBackToDefaultBusOnUnknownBus() {
        YamlNode root = tracks("""
                tracks:
                  odd:
                    type: VANILLA
                    bus: LOUD
                    sound: minecraft:block.note_block.pling
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        assertEquals(AudioBus.MUSIC, registry.get(Key.key("mineaudio:odd")).orElseThrow().bus());
        assertFalse(warnings.isEmpty());
    }

    @Test
    void rejectsUnknownSourceType() {
        YamlNode root = tracks("""
                tracks:
                  broken:
                    type: STREAM
                    sound: whatever
                """);
        TrackRegistry registry = new TrackRegistry();
        registry.load(root, warnings::add);

        assertEquals(0, registry.size());
        assertFalse(warnings.isEmpty());
    }
}
