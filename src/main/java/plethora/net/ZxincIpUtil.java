package plethora.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Zxinc IP 查询工具类 (IPv6 Only / 双栈通用版)
 * 适配纯 IPv6 服务器环境，防止因尝试 IPv4 导致的超时
 */
public class ZxincIpUtil {

    private static final String API_URL_TEMPLATE = "https://ip.zxinc.org/api.php?type=json&ip=%s";
    private static final ObjectMapper objectMapper = new ObjectMapper();
    // --- 2. 全局 HttpClient (启用连接池) ---
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            // 在 IPv6 环境下，HTTP/2 有时会有 MTU 问题，HTTP/1.1 更稳
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(3))  // IPv6 路由通常跳数多，稍微放宽连接超时
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // --- 1. 关键设置：适配 IPv6 环境 ---
    static {
        // 如果服务器没有 IPv4，必须禁止强制 IPv4，并建议开启 IPv6 优先
        // 这样 Java 在解析域名时会优先选取 AAAA 记录，避免尝试连接无法到达的 IPv4 地址
        System.clearProperty("java.net.preferIPv4Stack"); // 确保清除强制 IPv4
        System.setProperty("java.net.preferIPv6Addresses", "true"); // 优先使用 IPv6
    }

    /**
     * 查询 IP
     */
    public static IPResponse query(String ip) {
        return query(ip, 3000);
    }

    public static IPResponse query(String ip, int timeoutMs) {
        if (ip == null || ip.isBlank()) return null;

        try {
            String url = API_URL_TEMPLATE.formatted(ip);

            // --- 3. 伪装头 (防止被拦截) ---
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(timeoutMs))
                    // 必须伪装成浏览器，否则部分 CDN 节点会限速
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Accept", "application/json, text/plain, */*")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return parseResponse(response.body());
            }

        } catch (Exception e) {
            // 生产环境建议记录日志
            // e.printStackTrace();
        }
        return null;
    }

    private static IPResponse parseResponse(String jsonResult) {
        try {
            JsonNode rootNode = objectMapper.readTree(jsonResult);
            int code = rootNode.path("code").asInt(-1);

            if (code == 0) {
                JsonNode dataNode = rootNode.path("data");
                IPResponse response = new IPResponse();

                // IP
                String queryIp = dataNode.path("ip").path("query").asText();
                if (queryIp == null || queryIp.isEmpty()) queryIp = dataNode.path("myip").asText();
                response.setIp(queryIp);

                // ISP
                response.setIsp(dataNode.path("local").asText("未知"));

                // Location
                String locStr = dataNode.path("country").asText("");
                response.setLocation(locStr);

                parseGeoLocation(response, locStr);
                return response;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    private static void parseGeoLocation(IPResponse response, String geoStr) {
        if (geoStr == null || geoStr.isEmpty()) return;
        String[] parts = geoStr.split("[\\-–\\s\\t]+");
        if (parts.length > 0) response.setCountry(parts[0]);
        if (parts.length > 1) response.setRegion(parts[1]);
        if (parts.length > 2) response.setCity(parts[2]);
    }

    public static class IPResponse {
        private String ip;
        private String location;
        private String country;
        private String region;
        private String city;
        private String isp;

        public IPResponse() {
        }

        // Getters and Setters
        public String getIp() {
            return ip;
        }

        public void setIp(String ip) {
            this.ip = ip;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public String getIsp() {
            return isp;
        }

        public void setIsp(String isp) {
            this.isp = isp;
        }

        public String getCountry() {
            return country;
        }

        public void setCountry(String country) {
            this.country = country;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getCity() {
            return city;
        }

        public void setCity(String city) {
            this.city = city;
        }

        @Override
        public String toString() {
            return "IPResponse {ip='%s', location='%s', isp='%s'}".formatted(ip, location, isp);
        }
    }
}