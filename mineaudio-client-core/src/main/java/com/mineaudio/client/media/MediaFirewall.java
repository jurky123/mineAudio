package com.mineaudio.client.media;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;

/**
 * 媒体防火墙：只允许服务端下发且通过校验的 URL（设计文档 §30）。
 * 规则：HTTPS（可配例外）、禁止 userinfo、主机白/黑名单、DNS 解析后拒绝私网地址。
 * 服务端策略与本地策略取交集（本地只能更严格）。
 */
public final class MediaFirewall {

    public record Policy(
            boolean httpsOnly,
            boolean denyPrivateNetwork,
            int maxRedirects,
            List<String> allowHosts,
            List<String> denyHosts) {

        public static Policy defaults() {
            return new Policy(true, true, 5, List.of(), List.of());
        }

        /** 服务端策略与本地策略取交集（更严格的一方生效）。 */
        public Policy intersect(Policy local) {
            return new Policy(
                    httpsOnly || local.httpsOnly,
                    denyPrivateNetwork || local.denyPrivateNetwork,
                    Math.min(maxRedirects, local.maxRedirects),
                    mergeStricter(allowHosts, local.allowHosts),
                    mergeDeny(denyHosts, local.denyHosts));
        }

        private static List<String> mergeStricter(List<String> server, List<String> local) {
            if (local.isEmpty()) return List.copyOf(server);
            if (server.isEmpty()) return List.copyOf(local);
            return server.stream().filter(local::contains).toList();
        }

        private static List<String> mergeDeny(List<String> server, List<String> local) {
            return java.util.stream.Stream.concat(server.stream(), local.stream()).distinct().toList();
        }
    }

    private final Policy policy;

    public MediaFirewall(Policy policy) {
        this.policy = policy;
    }

    public Policy policy() {
        return policy;
    }

    /** 校验 URL（含 DNS 解析后的地址检查）。 */
    public void validate(URI uri) throws MediaSecurityException {
        String scheme = uri.getScheme();
        if (scheme == null) {
            throw new MediaSecurityException("INVALID_URL", "missing scheme");
        }
        String lowerScheme = scheme.toLowerCase(Locale.ROOT);
        if (!lowerScheme.equals("https") && !(!policy.httpsOnly() && lowerScheme.equals("http"))) {
            throw new MediaSecurityException("FIREWALL_REJECTED", "scheme not allowed: " + scheme);
        }
        if (uri.getUserInfo() != null) {
            throw new MediaSecurityException("FIREWALL_REJECTED", "userinfo not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new MediaSecurityException("INVALID_URL", "missing host");
        }
        if (uri.toString().length() > 8 * 1024) {
            throw new MediaSecurityException("FIREWALL_REJECTED", "url too long");
        }
        checkHostPolicy(host);
        if (policy.denyPrivateNetwork()) {
            checkAddresses(host);
        }
    }

    /** 校验 redirect 目标（每一跳都要重新校验）。 */
    public URI validateRedirect(URI target) throws MediaSecurityException {
        validate(target);
        return target;
    }

    private void checkHostPolicy(String host) throws MediaSecurityException {
        String lower = host.toLowerCase(Locale.ROOT);
        for (String denied : policy.denyHosts()) {
            if (matches(lower, denied)) {
                throw new MediaSecurityException("FIREWALL_REJECTED", "host denied: " + host);
            }
        }
        if (!policy.allowHosts().isEmpty()) {
            boolean allowed = policy.allowHosts().stream().anyMatch(entry -> matches(lower, entry));
            if (!allowed) {
                throw new MediaSecurityException("FIREWALL_REJECTED", "host not in allowlist: " + host);
            }
        }
    }

    private static boolean matches(String host, String rule) {
        String lower = rule.toLowerCase(Locale.ROOT);
        return host.equals(lower) || host.endsWith("." + lower);
    }

    private void checkAddresses(String host) throws MediaSecurityException {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new MediaSecurityException("DNS_FAILED", "cannot resolve host: " + host);
        }
        for (InetAddress address : addresses) {
            if (isPrivateAddress(address)) {
                throw new MediaSecurityException("PRIVATE_ADDRESS", "private address: " + address.getHostAddress());
            }
        }
    }

    /** 私网/保留地址判断（无 DNS 依赖，便于单测）。 */
    public static boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            // 100.64.0.0/10（运营商内网）
            return first == 100 && second >= 64 && second <= 127;
        }
        // IPv6：fc00::/7 唯一本地地址
        return (bytes[0] & 0xFE) == 0xFC;
    }
}
