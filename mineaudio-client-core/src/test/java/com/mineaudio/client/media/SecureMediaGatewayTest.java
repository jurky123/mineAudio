package com.mineaudio.client.media;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecureMediaGatewayTest {

    @Test
    void tokenParsing() {
        String token = "0123456789abcdef0123456789abcdef";
        assertEquals(token, SecureMediaGateway.tokenOf("http://127.0.0.1:1234/media/" + token));
        assertEquals(token, SecureMediaGateway.tokenOf("/media/" + token));
        assertNull(SecureMediaGateway.tokenOf("/media/short"));
        assertNull(SecureMediaGateway.tokenOf("/other/" + token));
    }

    @Test
    void registersAndCloses(@TempDir Path dir) throws Exception {
        MediaCache cache = new MediaCache(dir, 1024, 1024);
        cache.init();
        SecureMediaGateway gateway = new SecureMediaGateway(
                new MediaFirewall(MediaFirewall.Policy.defaults()), cache);
        try {
            String url = gateway.register(new SecureMediaGateway.Resource(
                    "key", URI.create("https://93.184.216.34/a.mp3"), java.util.Map.of(), 0));
            assertTrue(url.startsWith("http://127.0.0.1:" + gateway.port() + "/media/"));
            gateway.unregister(url);
        } finally {
            gateway.close();
        }
    }
}
