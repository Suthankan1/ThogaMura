package lk.thogamura.networkserver.integration.supplier;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** A fixed supplier endpoint. Only configured origins and local demo origins can be contacted. */
public class SupplierClientConfig {
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8090";
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 16_384;

    private static final Set<String> LOOPBACK_HOSTS = Set.of("127.0.0.1", "localhost", "[::1]");

    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxResponseBytes;
    private final Set<String> approvedOrigins;

    public SupplierClientConfig(String baseUrl, int connectTimeoutMs, int readTimeoutMs,
                                int maxResponseBytes, Set<String> approvedOrigins) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim().replaceAll("/+$", "");
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxResponseBytes = maxResponseBytes;
        this.approvedOrigins = approvedOrigins.stream().map(SupplierClientConfig::origin)
                .collect(Collectors.toUnmodifiableSet());
    }

    public SupplierClientConfig(String baseUrl) {
        this(baseUrl, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_MAX_RESPONSE_BYTES, Set.of());
    }

    public SupplierClientConfig() {
        this(DEFAULT_BASE_URL);
    }

    public void validate() {
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalArgumentException("Supplier connection and read timeouts must be positive");
        }
        if (maxResponseBytes <= 0 || maxResponseBytes > DEFAULT_MAX_RESPONSE_BYTES) {
            throw new IllegalArgumentException("Supplier response limit must be at most 16 KiB");
        }

        URI uri = parse(baseUrl);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        if (!LOOPBACK_HOSTS.contains(host) && (!"https".equals(scheme) || !approvedOrigins.contains(origin(uri)))) {
            throw new SecurityException("Supplier origin is outside the approved HTTPS allowlist");
        }
    }

    private static URI parse(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid supplier URL syntax");
        }
        if (uri.getScheme() == null || !Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("Supplier URL must use HTTP(S) and contain no credentials, query or fragment");
        }
        return uri;
    }

    private static String origin(String configured) {
        URI uri = parse(configured.trim());
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
            throw new IllegalArgumentException("Supplier allowlist entries must be HTTPS origins");
        }
        return origin(uri);
    }

    private static String origin(URI uri) {
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        int port = uri.getPort() < 0 ? ("https".equals(scheme) ? 443 : 80) : uri.getPort();
        return scheme + "://" + uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public int getMaxResponseBytes() {
        return maxResponseBytes;
    }
}
