package com.mineaudio.config;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/** 只读 YAML 节点，key 不做任何路径拆分。 */
public final class YamlNode {

    private static final YamlNode EMPTY = new YamlNode(Collections.emptyMap());

    private final Map<String, Object> map;

    public YamlNode(Map<?, ?> map) {
        if (map == null || map.isEmpty()) {
            this.map = Collections.emptyMap();
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> cast = (Map<String, Object>) map;
        this.map = cast;
    }

    public static YamlNode empty() {
        return EMPTY;
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    public Set<String> keys() {
        return map.keySet();
    }

    public boolean has(String key) {
        return map.containsKey(key);
    }

    public YamlNode section(String key) {
        Object value = map.get(key);
        return value instanceof Map<?, ?> ? new YamlNode((Map<?, ?>) value) : null;
    }

    public String string(String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    public boolean bool(String key, boolean fallback) {
        Object value = map.get(key);
        if (value instanceof Boolean bool) return bool;
        if (value instanceof String string) return Boolean.parseBoolean(string.trim());
        return fallback;
    }

    public double number(String key, double fallback) {
        Object value = map.get(key);
        if (value instanceof Number number) return number.doubleValue();
        if (value instanceof String string) {
            try {
                return Double.parseDouble(string.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public long longValue(String key, long fallback) {
        double value = number(key, fallback);
        return (long) value;
    }
}
