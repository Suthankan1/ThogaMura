package lk.thogamura.networkserver.integration.supplier;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration parameters for the outbound Java HTTP supplier connector.
 *
 * <p>Enforces the security boundary specified in {@code docs/SECURITY_AND_LIMITATIONS.md}
 * and {@code docs/contracts/SUPPLIER_HTTP_V1.md}:
 * <ul>
 *   <li>Base URL is strictly configured and validated (no arbitrary user-supplied target hosts).</li>
 *   <li>Connect timeout (default 3,000 ms) and read timeout (default 5,000 ms) are strictly bounded.</li>
 *   <li>Maximum response size is strictly limited to 16,384 bytes (16 KB) to prevent memory exhaustion.</li>
 *   <li>Redirects are strictly disabled to prevent SSRF hopping.</li>
 * </ul>
 * </p>
 */
public class SupplierClientConfig {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8090";
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 5_000;
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 16_384; // 16 KB

    private static final Set<String> APPROVED_PROTOCOLS = Set.of("http", "https");
    private static final Set<String> DEFAULT_APPROVED_HOSTS = Set.of(
            "127.0.0.1",
            "localhost",
            "::1",
            "supplier",
            "mock-supplier",
            "supplier.thogamura.internal"
    );

    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxResponseBytes;
    private final boolean allowUnapprovedUrls;

    public SupplierClientConfig(
            String baseUrl,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxResponseBytes,
            boolean allowUnapprovedUrls) {
        this.baseUrl = (baseUrl != null && !baseUrl.isBlank()) ? baseUrl.trim() : DEFAULT_BASE_URL;
        this.connectTimeoutMs = connectTimeoutMs >= 0 ? connectTimeoutMs : DEFAULT_CONNECT_TIMEOUT_MS;
        this.readTimeoutMs = readTimeoutMs >= 0 ? readTimeoutMs : DEFAULT_READ_TIMEOUT_MS;
        this.maxResponseBytes = maxResponseBytes > 0 ? maxResponseBytes : DEFAULT_MAX_RESPONSE_BYTES;
        this.allowUnapprovedUrls = allowUnapprovedUrls;
    }

    public SupplierClientConfig(String baseUrl) {
        this(baseUrl, DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_MAX_RESPONSE_BYTES, false);
    }

    public SupplierClientConfig() {
        this(DEFAULT_BASE_URL);
    }

    /**
     * Validates configuration parameters and checks the supplier base URL against SSRF policy.
     *
     * @throws IllegalArgumentException if settings are invalid or violate protocol bounds
     * @throws SecurityException if the URL host is not in the approved supplier allowlist
     */
    public void validate() {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (connectTimeoutMs <= 0) {
            throw new IllegalArgumentException("connectTimeoutMs must be positive: " + connectTimeoutMs);
        }
        if (readTimeoutMs <= 0) {
            throw new IllegalArgumentException("readTimeoutMs must be positive: " + readTimeoutMs);
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive: " + maxResponseBytes);
        }

        try {
            URI uri = new URI(baseUrl);
            String scheme = uri.getScheme();
            if (scheme == null || !APPROVED_PROTOCOLS.contains(scheme.toLowerCase())) {
                throw new IllegalArgumentException("Unsupported protocol scheme in supplier base URL: '" + scheme + "' (expected http or https)");
            }

            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("Missing host in supplier base URL: " + baseUrl);
            }

            if (!allowUnapprovedUrls && !DEFAULT_APPROVED_HOSTS.contains(host.toLowerCase())) {
                throw new SecurityException(
                        "Supplier base URL host '" + host + "' is not in the approved supplier allowlist. "
                                + "SSRF protection blocks unapproved external hosts (set allowUnapprovedUrls=true to override for test environments)."
                );
            }
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid supplier base URL syntax: " + baseUrl, e);
        }
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

    public boolean isAllowUnapprovedUrls() {
        return allowUnapprovedUrls;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SupplierClientConfig that)) return false;
        return connectTimeoutMs == that.connectTimeoutMs &&
                readTimeoutMs == that.readTimeoutMs &&
                maxResponseBytes == that.maxResponseBytes &&
                allowUnapprovedUrls == that.allowUnapprovedUrls &&
                Objects.equals(baseUrl, that.baseUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl, connectTimeoutMs, readTimeoutMs, maxResponseBytes, allowUnapprovedUrls);
    }

    @Override
    public String toString() {
        return "SupplierClientConfig{" +
                "baseUrl='" + baseUrl + '\'' +
                ", connectTimeoutMs=" + connectTimeoutMs +
                ", readTimeoutMs=" + readTimeoutMs +
                ", maxResponseBytes=" + maxResponseBytes +
                ", allowUnapprovedUrls=" + allowUnapprovedUrls +
                '}';
    }
}
