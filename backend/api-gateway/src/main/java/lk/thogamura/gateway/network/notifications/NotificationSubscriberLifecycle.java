package lk.thogamura.gateway.network.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * Spring Boot SmartLifecycle adapter hosting the Gateway Java NIO subscriber.
 *
 * <p>Controls startup and shutdown cleanly with the Spring application context.
 * Kept disabled by default ({@code thogamura.notifications.subscriber.enabled=false})
 * so test suites and standalone deployments do not attempt connection unless explicitly enabled.</p>
 */
@Component
@ConditionalOnProperty(prefix = "thogamura.notifications.subscriber", name = "enabled", havingValue = "true")
public class NotificationSubscriberLifecycle implements SmartLifecycle {
    private static final Logger logger = LoggerFactory.getLogger(NotificationSubscriberLifecycle.class);

    private final String host;
    private final int port;
    private final int maxFrameBytes;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final double backoffMultiplier;
    private final List<NotificationListener> injectedListeners;

    private GatewayNioSubscriber subscriber;
    private boolean running = false;

    public NotificationSubscriberLifecycle(
            @Value("${thogamura.notifications.subscriber.host:127.0.0.1}") String host,
            @Value("${thogamura.notifications.subscriber.port:7444}") int port,
            @Value("${thogamura.notifications.subscriber.max-frame-bytes:65536}") int maxFrameBytes,
            @Value("${thogamura.notifications.subscriber.initial-backoff-ms:500}") long initialBackoffMs,
            @Value("${thogamura.notifications.subscriber.max-backoff-ms:10000}") long maxBackoffMs,
            @Value("${thogamura.notifications.subscriber.backoff-multiplier:2.0}") double backoffMultiplier,
            @Autowired(required = false) List<NotificationListener> injectedListeners) {
        this.host = host;
        this.port = port;
        this.maxFrameBytes = maxFrameBytes;
        this.initialBackoff = Duration.ofMillis(initialBackoffMs);
        this.maxBackoff = Duration.ofMillis(maxBackoffMs);
        this.backoffMultiplier = backoffMultiplier;
        this.injectedListeners = injectedListeners != null ? injectedListeners : List.of();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        NotificationSubscriberConfig config = new NotificationSubscriberConfig(
                host,
                port,
                maxFrameBytes,
                initialBackoff,
                maxBackoff,
                backoffMultiplier,
                Duration.ofSeconds(3)
        );

        subscriber = new GatewayNioSubscriber(config);
        for (NotificationListener listener : injectedListeners) {
            subscriber.addListener(listener);
        }

        subscriber.start();
        running = true;
        logger.info("NotificationSubscriberLifecycle started Gateway NIO subscriber for {}:{}", host, port);
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        if (subscriber != null) {
            subscriber.stop();
            subscriber = null;
        }
        running = false;
        logger.info("NotificationSubscriberLifecycle stopped Gateway NIO subscriber");
    }

    @Override
    public synchronized boolean isRunning() {
        return running && subscriber != null && subscriber.isRunning();
    }

    public GatewayNioSubscriber getSubscriber() {
        return subscriber;
    }

    public SubscriberStatus getStatus() {
        return subscriber != null ? subscriber.getStatus() : SubscriberStatus.DISCONNECTED;
    }

    public boolean isConnected() {
        return subscriber != null && subscriber.isConnected();
    }
}
