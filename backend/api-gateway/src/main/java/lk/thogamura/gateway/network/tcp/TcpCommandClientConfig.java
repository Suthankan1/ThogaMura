package lk.thogamura.gateway.network.tcp;

import lk.thogamura.protocol.ProtocolInfo;

import java.util.Objects;

/**
 * Configuration parameters for the gateway's TCP command client.
 *
 * <p>Matches the default socket timeouts, framing bounds, and bounded retry policy defined in
 * {@code docs/contracts/NETWORK_PROTOCOL_V1.md}.</p>
 */
public class TcpCommandClientConfig {

    public static final int DEFAULT_MAX_RETRIES = 2;
    public static final long DEFAULT_RETRY_BACKOFF_MS = 100L;
    public static final double DEFAULT_RETRY_BACKOFF_MULTIPLIER = 2.0;
    public static final long DEFAULT_MAX_RETRY_BACKOFF_MS = 1000L;

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxFrameBytes;
    private final int maxRetries;
    private final long retryBackoffMs;
    private final double retryBackoffMultiplier;
    private final long maxRetryBackoffMs;

    public TcpCommandClientConfig(
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxFrameBytes,
            int maxRetries,
            long retryBackoffMs,
            double retryBackoffMultiplier,
            long maxRetryBackoffMs) {
        this.host = (host != null && !host.isBlank()) ? host.trim() : host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxFrameBytes = maxFrameBytes;
        this.maxRetries = maxRetries;
        this.retryBackoffMs = retryBackoffMs;
        this.retryBackoffMultiplier = retryBackoffMultiplier;
        this.maxRetryBackoffMs = maxRetryBackoffMs;
    }

    public TcpCommandClientConfig(
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxFrameBytes) {
        this(host, port, connectTimeoutMs, readTimeoutMs, maxFrameBytes,
                DEFAULT_MAX_RETRIES, DEFAULT_RETRY_BACKOFF_MS,
                DEFAULT_RETRY_BACKOFF_MULTIPLIER, DEFAULT_MAX_RETRY_BACKOFF_MS);
    }

    public TcpCommandClientConfig(String host, int port) {
        this(host, port, ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS, ProtocolInfo.DEFAULT_READ_TIMEOUT_MS, ProtocolInfo.MAX_FRAME_BYTES);
    }

    public TcpCommandClientConfig(int port) {
        this("127.0.0.1", port);
    }

    public void validate() {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be blank");
        }
        if (port <= 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of range (1-65535): " + port);
        }
        if (connectTimeoutMs < 0) {
            throw new IllegalArgumentException("connectTimeoutMs must be non-negative: " + connectTimeoutMs);
        }
        if (readTimeoutMs < 0) {
            throw new IllegalArgumentException("readTimeoutMs must be non-negative: " + readTimeoutMs);
        }
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive: " + maxFrameBytes);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative: " + maxRetries);
        }
        if (retryBackoffMs < 0) {
            throw new IllegalArgumentException("retryBackoffMs must be non-negative: " + retryBackoffMs);
        }
        if (retryBackoffMultiplier < 1.0) {
            throw new IllegalArgumentException("retryBackoffMultiplier must be >= 1.0: " + retryBackoffMultiplier);
        }
        if (maxRetryBackoffMs < retryBackoffMs) {
            throw new IllegalArgumentException("maxRetryBackoffMs must be >= retryBackoffMs: "
                    + maxRetryBackoffMs + " < " + retryBackoffMs);
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public double getRetryBackoffMultiplier() {
        return retryBackoffMultiplier;
    }

    public long getMaxRetryBackoffMs() {
        return maxRetryBackoffMs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TcpCommandClientConfig that)) return false;
        return port == that.port &&
                connectTimeoutMs == that.connectTimeoutMs &&
                readTimeoutMs == that.readTimeoutMs &&
                maxFrameBytes == that.maxFrameBytes &&
                maxRetries == that.maxRetries &&
                retryBackoffMs == that.retryBackoffMs &&
                Double.compare(that.retryBackoffMultiplier, retryBackoffMultiplier) == 0 &&
                maxRetryBackoffMs == that.maxRetryBackoffMs &&
                Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, connectTimeoutMs, readTimeoutMs, maxFrameBytes,
                maxRetries, retryBackoffMs, retryBackoffMultiplier, maxRetryBackoffMs);
    }

    @Override
    public String toString() {
        return "TcpCommandClientConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", connectTimeoutMs=" + connectTimeoutMs +
                ", readTimeoutMs=" + readTimeoutMs +
                ", maxFrameBytes=" + maxFrameBytes +
                ", maxRetries=" + maxRetries +
                ", retryBackoffMs=" + retryBackoffMs +
                ", retryBackoffMultiplier=" + retryBackoffMultiplier +
                ", maxRetryBackoffMs=" + maxRetryBackoffMs +
                '}';
    }
}
