package cn.tworice.huaweiddns.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * 获取当前主机最新公网 IPv4 地址。
 * 依次尝试多个外部服务，任一个成功即返回；均失败则抛异常。
 */
public final class PublicIpFetcher {

    private static final List<String> PROVIDERS = Arrays.asList(
            "https://api.ipify.org",
            "https://ifconfig.me/ip",
            "https://myip.ipip.net"
    );

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private PublicIpFetcher() {
    }

    public static String getPublicIp() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();

        StringBuilder errors = new StringBuilder();
        for (String url : PROVIDERS) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    String ip = response.body().trim();
                    // myip.ipip.net 返回形如 "当前 IP：1.2.3.4 来自..."，提取首个 IPv4
                    ip = extractIpv4(ip);
                    if (ip != null) {
                        return ip;
                    }
                }
            } catch (Exception e) {
                errors.append(url).append(" -> ").append(e.getMessage()).append("; ");
            }
        }
        throw new IllegalStateException("无法获取当前公网 IP（已尝试的服务：" + errors + "）");
    }

    private static String extractIpv4(String text) {
        if (text == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b")
                .matcher(text);
        if (m.find()) {
            String ip = m.group();
            return isValidIpv4(ip) ? ip : null;
        }
        return null;
    }

    private static boolean isValidIpv4(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        try {
            for (String p : parts) {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
