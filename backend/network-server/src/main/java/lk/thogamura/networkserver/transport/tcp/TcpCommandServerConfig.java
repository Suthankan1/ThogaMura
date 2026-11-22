package lk.thogamura.networkserver.transport.tcp;

import lk.thogamura.protocol.ProtocolInfo;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * Configuration for the central warehouse TCP command server.
 *
 * <p>Enforces the security boundary specified in {@code docs/SECURITY_AND_LIMITATIONS.md}:
 * Plaintext diagnostic TCP command transport must be strictly restricted to loopback
 * interfaces ({@code 127.0.0.1} / {@code ::1}) during local development. Binding plaintext
 * to non-loopback addresses without TLS requires explicit override.</p>
 */
public class TcpCommandServerConfig {

    public static final int DEFAULT_FRAME_DEADLINE_MS = 10000;

    private final String host;
    private final int port;
    private final int backlog;
    private final int readTimeoutMs;
    private final int frameDeadlineMs;
    private final int maxClients;
    private final int maxFrameBytes;
    private final boolean allowNonLoopbackPlaintext;
    private final boolean tlsEnabled;

    public TcpCommandServerConfig(
            String host,
            int port,
            int backlog,
            int readTimeoutMs,
            int frameDeadlineMs,
            int maxClients,
            int maxFrameBytes,
            boolean allowNonLoopbackPlaintext,
            boolean tlsEnabled) {
        this.host = (host != null && !host.isBlank()) ? host.trim() : "127.0.0.1";
        this.port = port;
        this.backlog = backlog > 0 ? backlog : 50;
        this.readTimeoutMs = readTimeoutMs >= 0 ? readTimeoutMs : ProtocolInfo.DEFAULT_READ_TIMEOUT_MS;
        this.frameDeadlineMs = frameDeadlineMs > 0 ? frameDeadlineMs : Math.max(this.readTimeoutMs * 2, DEFAULT_FRAME_DEADLINE_MS);
        this.maxClients = maxClients > 0 ? maxClients : 50;
        this.maxFrameBytes = maxFrameBytes > 0 ? maxFrameBytes : ProtocolInfo.MAX_FRAME_BYTES;
        this.allowNonLoopbackPlaintext = allowNonLoopbackPlaintext;
        this.tlsEnabled = tlsEnabled;
    }

    public TcpCommandServerConfig(
            String host,
            int port,
            int backlog,
            int readTimeoutMs,
            int maxClients,
            int maxFrameBytes,
            boolean allowNonLoopbackPlaintext,
            boolean tlsEnabled) {
        this(host, port, backlog, readTimeoutMs, Math.max(readTimeoutMs * 2, DEFAULT_FRAME_DEADLINE_MS), maxClients, maxFrameBytes, allowNonLoopbackPlaintext, tlsEnabled);
    }

    public TcpCommandServerConfig(
            String host,
            int port,
            int backlog,
            int readTimeoutMs,
            int maxClients,
            int maxFrameBytes,
            boolean allowNonLoopbackPlaintext) {
        this(host, port, backlog, readTimeoutMs, maxClients, maxFrameBytes, allowNonLoopbackPlaintext, false);
    }

    public TcpCommandServerConfig(String host, int port) {
        this(host, port, 50, ProtocolInfo.DEFAULT_READ_TIMEOUT_MS, 50, ProtocolInfo.MAX_FRAME_BYTES, false, false);
    }

    public TcpCommandServerConfig(int port) {
        this("127.0.0.1", port);
    }

    public void validate() {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of range (0-65535): " + port);
        }
        if (readTimeoutMs < 0) {
            throw new IllegalArgumentException("readTimeoutMs must be non-negative: " + readTimeoutMs);
        }
        if (frameDeadlineMs <= 0) {
            throw new IllegalArgumentException("frameDeadlineMs must be positive: " + frameDeadlineMs);
        }
        if (maxClients <= 0) {
            throw new IllegalArgumentException("maxClients must be positive: " + maxClients);
        }
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive: " + maxFrameBytes);
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            if (!address.isLoopbackAddress() && !tlsEnabled) {
                throw new IllegalStateException(
                        "Plaintext TCP command transport is strictly prohibited on non-loopback interface '" + host
                                + "' without TLS. Only loopback binding is permitted for development/testing "
                                + "(non-loopback always requires TLS)."
                );
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid host address: " + host, e);
        }
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public int getBacklog() {
        return backlog;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public int getFrameDeadlineMs() {
        return frameDeadlineMs;
    }

    public int getMaxClients() {
        return maxClients;
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    public boolean isAllowNonLoopbackPlaintext() {
        return allowNonLoopbackPlaintext;
    }

    public boolean isTlsEnabled() {
        return tlsEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TcpCommandServerConfig that)) return false;
        return port == that.port &&
                backlog == that.backlog &&
                readTimeoutMs == that.readTimeoutMs &&
                frameDeadlineMs == that.frameDeadlineMs &&
                maxClients == that.maxClients &&
                maxFrameBytes == that.maxFrameBytes &&
                allowNonLoopbackPlaintext == that.allowNonLoopbackPlaintext &&
                tlsEnabled == that.tlsEnabled &&
                Objects.equals(host, that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(host, port, backlog, readTimeoutMs, frameDeadlineMs, maxClients, maxFrameBytes, allowNonLoopbackPlaintext, tlsEnabled);
    }

    @Override
    public String toString() {
        return "TcpCommandServerConfig{" +
                "host='" + host + '\'' +
                ", port=" + port +
                ", backlog=" + backlog +
                ", readTimeoutMs=" + readTimeoutMs +
                ", frameDeadlineMs=" + frameDeadlineMs +
                ", maxClients=" + maxClients +
                ", maxFrameBytes=" + maxFrameBytes +
                ", allowNonLoopbackPlaintext=" + allowNonLoopbackPlaintext +
                ", tlsEnabled=" + tlsEnabled +
                '}';
    }
}
