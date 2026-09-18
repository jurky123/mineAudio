package com.mineaudio.region;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.mineaudio.config.YamlFile;

import net.kyori.adventure.key.Key;

class RegionParserTest {

    private final List<String> warnings = new ArrayList<>();

    @Test
    void parsesWorldsAndRegions() {
        RegionParser.Parsed parsed = RegionParser.parse(YamlFile.parse("""
                worlds:
                  world:
                    music: spawn
                    ambient: [birds]
                regions:
                  tavern:
                    world: world
                    shape:
                      type: CUBOID
                      min: { x: 100, y: 60, z: -40 }
                      max: { x: 150, y: 80, z: -10 }
                    priority: 20
                    music: tavern
                    ambient: [fireplace, rain]
                    fade-in-ms: 1000
                    enter-delay-ms: 600
                  orb:
                    world: world
                    shape:
                      type: SPHERE
                      center: { x: 5, y: 64, z: 5 }
                      radius: 8
                """), warnings::add, 300, 500);

        assertEquals(1, parsed.worlds().size());
        RegionParser.WorldLayer world = parsed.worlds().get("world");
        assertEquals(Key.key("mineaudio:spawn"), world.music());
        assertEquals(List.of(Key.key("mineaudio:birds")), world.ambient());

        assertEquals(2, parsed.regions().size());
        AudioRegion tavern = parsed.regions().get(0);
        assertEquals("tavern", tavern.id());
        assertEquals(20, tavern.priority());
        assertEquals(Key.key("mineaudio:tavern"), tavern.music());
        assertEquals(2, tavern.ambient().size());
        assertEquals(12, tavern.enterDelayTicks());
        assertEquals(10, tavern.exitDelayTicks());

        AudioRegion orb = parsed.regions().get(1);
        assertEquals(6, orb.enterDelayTicks());
        assertNull(orb.music());
        assertTrueEmptyWarnings();
    }

    @Test
    void rejectsInvalidShapeAndKeepsOthers() {
        RegionParser.Parsed parsed = RegionParser.parse(YamlFile.parse("""
                regions:
                  broken:
                    world: world
                    shape:
                      type: CIRCLE
                      radius: 5
                  ok:
                    world: world
                    shape:
                      type: SPHERE
                      center: { x: 0, y: 0, z: 0 }
                      radius: 3
                """), warnings::add, 300, 500);

        assertEquals(1, parsed.regions().size());
        assertEquals("ok", parsed.regions().get(0).id());
        assertFalse(warnings.isEmpty());
    }

    private void assertTrueEmptyWarnings() {
        if (!warnings.isEmpty()) {
            throw new AssertionError(warnings.toString());
        }
    }
}
