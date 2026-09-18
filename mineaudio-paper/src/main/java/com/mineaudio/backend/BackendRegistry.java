package com.mineaudio.backend;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mineaudio.api.AudioSource;

/** Backend 注册表：按来源类型找到第一个可用 Backend。 */
public final class BackendRegistry {

    private final List<AudioBackend> backends = new ArrayList<>();

    public void register(AudioBackend backend) {
        backends.add(backend);
    }

    public List<AudioBackend> all() {
        return List.copyOf(backends);
    }

    public Optional<AudioBackend> find(AudioSource source) {
        return backends.stream()
                .filter(AudioBackend::available)
                .filter(backend -> backend.supports(source))
                .findFirst();
    }

    public Optional<AudioBackend> byId(String id) {
        return backends.stream().filter(backend -> backend.id().equals(id)).findFirst();
    }

    public boolean canPlay(AudioSource source) {
        return find(source).isPresent();
    }
}
