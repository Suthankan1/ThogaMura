package lk.thogamura.gateway.network.notifications;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for the Gateway Java NIO notification subscriber.
 *
 * <p>Enforces loopback binding (127.0.0.1:7444) by default for private demo network safety.</p>
 */
public record NotificationSubscriberConfig(
        String host,
        int port,
        int maxFrameBytes,
        Duration initialBackoff,
        Duration maxBackoff,
        double backoffMultiplier,
        Duration connectTimeout
) {
    public static final String DEFAULT_HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 7444;
    public static final int DEFAULT_MAX_FRAME_BYTES = 65_536; // 64 KB
    public static final Duration DEFAULT_INITIAL_BACKOFF = Duration.ofMillis(500);
    public static final Duration DEFAULT_MAX_BACKOFF = Duration.ofSeconds(10);
    public static final double DEFAULT_BACKOFF_MULTIPLIER = 2.0;
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);

    public NotificationSubscriberConfig {
        Objects.requireNonNull(host, "host cannot be null");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host cannot be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535 (got " + port + ")");
        }
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive (got " + maxFrameBytes + ")");
        }
        Objects.requireNonNull(initialBackoff, "initialBackoff cannot be null");
        Objects.requireNonNull(maxBackoff, "maxBackoff cannot be null");
        Objects.requireNonNull(connectTimeout, "connectTimeout cannot be null");
        if (backoffMultiplier < 1.0) {
            throw new IllegalArgumentException("backoffMultiplier must be >= 1.0 (got " + backoffMultiplier + ")");
        }
    }

    public static NotificationSubscriberConfig defaultLocal() {
        return new NotificationSubscriberConfig(
                DEFAULT_HOST,
                DEFAULT_PORT,
                DEFAULT_MAX_FRAME_BYTES,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAX_BACKOFF,
                DEFAULT_BACKOFF_MULTIPLIER,
                DEFAULT_CONNECT_TIMEOUT
        );
    }

    public static NotificationSubscriberConfig of(String host, int port) {
        return new NotificationSubscriberConfig(
                host,
                port,
                DEFAULT_MAX_FRAME_BYTES,
                DEFAULT_INITIAL_BACKOFF,
                DEFAULT_MAX_BACKOFF,
                DEFAULT_BACKOFF_MULTIPLIER,
                DEFAULT_CONNECT_TIMEOUT
        );
    }

    public static NotificationSubscriberConfig ofPort(int port) {
        return of(DEFAULT_HOST, port);
    }

    /**
     * Factory method for creating test configurations with fast backoff for quick tests.
     */
    public static NotificationSubscriberConfig forTesting(int port, Duration initialBackoff, Duration maxBackoff) {
        return new NotificationSubscriberConfig(
                DEFAULT_HOST,
                port,
                DEFAULT_MAX_FRAME_BYTES,
                initialBackoff,
                maxBackoff,
                1.5,
                Duration.ofSeconds(1)
        );
    }
}
