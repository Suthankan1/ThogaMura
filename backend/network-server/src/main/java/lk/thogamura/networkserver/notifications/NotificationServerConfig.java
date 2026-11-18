package lk.thogamura.networkserver.notifications;

import java.util.Objects;

/**
 * Configuration parameters for the non-blocking Java NIO notification server.
 *
 * <p>By default, bindings are strictly restricted to loopback (127.0.0.1) on port 7444
 * to satisfy private demo network requirements and Issue #33 security constraints.</p>
 */
public record NotificationServerConfig(
        String host,
        int port,
        int maxClients,
        int maxPendingMessagesPerClient,
        int socketSendBufferSize
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7444;
    public static final int DEFAULT_MAX_CLIENTS = 100;
    public static final int DEFAULT_MAX_PENDING_MESSAGES = 50;
    public static final int DEFAULT_SOCKET_SEND_BUFFER = 0; // 0 = OS default

    public NotificationServerConfig(String host, int port, int maxClients, int maxPendingMessagesPerClient) {
        this(host, port, maxClients, maxPendingMessagesPerClient, DEFAULT_SOCKET_SEND_BUFFER);
    }

    public NotificationServerConfig {
        Objects.requireNonNull(host, "host cannot be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535 (got " + port + ")");
        }
        if (maxClients <= 0) {
            throw new IllegalArgumentException("maxClients must be positive (got " + maxClients + ")");
        }
        if (maxPendingMessagesPerClient <= 0) {
            throw new IllegalArgumentException("maxPendingMessagesPerClient must be positive (got "
                    + maxPendingMessagesPerClient + ")");
        }
        if (socketSendBufferSize < 0) {
            throw new IllegalArgumentException("socketSendBufferSize cannot be negative (got "
                    + socketSendBufferSize + ")");
        }
    }

    /**
     * Creates default development configuration bound to 127.0.0.1:7444.
     */
    public static NotificationServerConfig defaultLocal() {
        return new NotificationServerConfig(DEFAULT_HOST, DEFAULT_PORT, DEFAULT_MAX_CLIENTS, DEFAULT_MAX_PENDING_MESSAGES);
    }

    /**
     * Creates a configuration with an explicit host and port.
     */
    public static NotificationServerConfig of(String host, int port) {
        return new NotificationServerConfig(host, port, DEFAULT_MAX_CLIENTS, DEFAULT_MAX_PENDING_MESSAGES);
    }

    /**
     * Creates a configuration on loopback with an explicit port (e.g., 0 for dynamic testing).
     */
    public static NotificationServerConfig ofPort(int port) {
        return new NotificationServerConfig(DEFAULT_HOST, port, DEFAULT_MAX_CLIENTS, DEFAULT_MAX_PENDING_MESSAGES);
    }
}
