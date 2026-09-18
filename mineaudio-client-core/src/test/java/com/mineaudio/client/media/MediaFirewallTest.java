package com.mineaudio.client.media;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

class MediaFirewallTest {

    private final MediaFirewall firewall = new MediaFirewall(MediaFirewall.Policy.defaults());

    @Test
    void rejectsHttpWhenHttpsOnly() {
        assertThrows(MediaSecurityException.class,
                () -> firewall.validate(URI.create("http://example.com/a.mp3")));
    }

    @Test
    void rejectsUserinfo() {
        assertThrows(MediaSecurityException.class,
                () -> firewall.validate(URI.create("https://user:pass@example.com/a.mp3")));
    }

    @Test
    void rejectsPrivateAddresses() throws Exception {
        String[] hosts = {"127.0.0.1", "10.1.2.3", "172.16.0.1", "192.168.1.1",
                "169.254.1.1", "100.64.0.1", "::1", "fc00::1", "fe80::1", "224.0.0.1"};
        for (String host : hosts) {
            assertThrows(MediaSecurityException.class,
                    () -> firewall.validate(URI.create("https://" + bracket(host) + "/a.mp3")),
                    "should reject " + host);
        }
    }

    @Test
    void allowsPublicAddress() {
        assertDoesNotThrow(() -> firewall.validate(URI.create("https://93.184.216.34/a.mp3")));
    }

    @Test
    void enforcesAllowlist() {
        MediaFirewall allowlist = new MediaFirewall(new MediaFirewall.Policy(
                true, true, 5, List.of("example.com"), List.of()));
        assertDoesNotThrow(() -> allowlist.validate(URI.create("https://example.com/a.mp3")));
        assertThrows(MediaSecurityException.class,
                () -> allowlist.validate(URI.create("https://93.184.216.34/a.mp3")));
    }

    @Test
    void intersectTakesStricterPolicy() {
        MediaFirewall.Policy server = new MediaFirewall.Policy(false, false, 5,
                List.of("a.com", "b.com"), List.of());
        MediaFirewall.Policy local = new MediaFirewall.Policy(true, true, 3,
                List.of("a.com"), List.of("evil.com"));
        MediaFirewall.Policy effective = server.intersect(local);
        org.junit.jupiter.api.Assertions.assertTrue(effective.httpsOnly());
        org.junit.jupiter.api.Assertions.assertTrue(effective.denyPrivateNetwork());
        org.junit.jupiter.api.Assertions.assertEquals(3, effective.maxRedirects());
        org.junit.jupiter.api.Assertions.assertEquals(List.of("a.com"), effective.allowHosts());
        org.junit.jupiter.api.Assertions.assertTrue(effective.denyHosts().contains("evil.com"));
    }

    @Test
    void privateAddressDetection() throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(
                MediaFirewall.isPrivateAddress(InetAddress.getByName("192.168.0.10")));
        org.junit.jupiter.api.Assertions.assertFalse(
                MediaFirewall.isPrivateAddress(InetAddress.getByName("8.8.8.8")));
    }

    private static String bracket(String host) {
        return host.contains(":") ? "[" + host + "]" : host;
    }
}
