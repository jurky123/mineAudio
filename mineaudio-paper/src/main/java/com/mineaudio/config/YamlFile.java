package com.mineaudio.config;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * tracks/cues/regions/emitters 的原始 YAML 读取。
 * <p>
 * 不用 Bukkit YamlConfiguration 的原因：它把 key 里的 '.' 当路径分隔符，
 * 而声音 ID 形如 {@code mineaudio:music.spawn} / {@code mineuno:card.play}，必须保持完整。
 */
public final class YamlFile {

    private YamlFile() {
    }

    public static YamlNode load(File file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    public static YamlNode parse(String yaml) {
        return parse(new StringReader(yaml));
    }

    private static YamlNode parse(Reader reader) {
        Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(reader);
        if (loaded instanceof Map<?, ?> map) {
            return new YamlNode(map);
        }
        return YamlNode.empty();
    }
}
