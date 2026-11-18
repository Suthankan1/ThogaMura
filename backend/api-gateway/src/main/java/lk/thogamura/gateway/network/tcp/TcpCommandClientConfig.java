package lk.thogamura.gateway.network.tcp;

import lk.thogamura.protocol.ProtocolInfo;

import java.util.Objects;

/**
 * Configuration parameters for the gateway's TCP command client.
 *
 * <p>Matches the default socket timeouts and framing bounds defined in
 * {@code docs/contracts/NETWORK_PROTOCOL_V1.md}.</p>
 */
public class TcpCommandClientConfig {

    private final String host;
    private final int port;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final int maxFrameBytes;

    public TcpCommandClientConfig(
            String host,
            int port,
            int connectTimeoutMs,
            int readTimeoutMs,
            int maxFrameBytes) {
        this.host = (host != null && !host.isBlank()) ? host.trim() : host;
        this.port = port;
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.maxFrameBytes = maxFrameBytes;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TcpCommandClientConfig that)) return false;
        return port == that.port &&
                connectTimeoutMs == that.connectTimeoutMs &&
                readTimeoutMs == that.readTimeoutMs &&
                maxFrameBytes == that.maxFrameBytes &&
                Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, connectTimeoutMs, readTimeoutMs, maxFrameBytes);
    }

    @Override
    public String toString() {
        return "TcpCommandClientConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", connectTimeoutMs=" + connectTimeoutMs +
                ", readTimeoutMs=" + readTimeoutMs +
                ", maxFrameBytes=" + maxFrameBytes +
                '}';
    }
}
