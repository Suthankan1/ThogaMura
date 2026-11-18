package lk.thogamura.networkserver.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Spring lifecycle adapter hosting the Java NIO notification server.
 *
 * <p>Enables integration into the Spring Boot application lifecycle without
 * compromising the pure JDK networking implementation. It remains disabled by default
 * so local developer setups and CI do not bind ports unintentionally.</p>
 */
@Component
@ConditionalOnProperty(prefix = "thogamura.notifications", name = "enabled", havingValue = "true")
public class NotificationServerLifecycle implements SmartLifecycle, NotificationPublisher {
    private static final Logger logger = LoggerFactory.getLogger(NotificationServerLifecycle.class);

    private final String host;
    private final int port;
    private final int maxClients;
    private final int maxPendingMessages;

    private NioNotificationServer server;
    private boolean running = false;

    public NotificationServerLifecycle(
            @Value("${thogamura.notifications.host:127.0.0.1}") String host,
            @Value("${thogamura.notifications.port:7444}") int port,
            @Value("${thogamura.notifications.max-clients:100}") int maxClients,
            @Value("${thogamura.notifications.max-pending-messages:50}") int maxPendingMessages) {
        this.host = host;
        this.port = port;
        this.maxClients = maxClients;
        this.maxPendingMessages = maxPendingMessages;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            NotificationServerConfig config = new NotificationServerConfig(host, port, maxClients, maxPendingMessages);
            server = new NioNotificationServer(config);
            server.start();
            running = true;
            logger.info("NotificationServerLifecycle started Java NIO notification feed on {}:{}", host, server.getPort());
        } catch (IOException e) {
            logger.error("Failed to start Java NIO notification feed on {}:{}", host, port, e);
            throw new IllegalStateException("Failed to start NIO notification server", e);
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        running = false;
        logger.info("NotificationServerLifecycle stopped Java NIO notification feed");
    }

    @Override
    public synchronized boolean isRunning() {
        return running && server != null && server.isRunning();
    }

    @Override
    public void publish(ChangeNotification notification) {
        if (server == null || !server.isRunning()) {
            throw new IllegalStateException("Cannot publish notification: notification server is not running");
        }
        server.publish(notification);
    }

    public NioNotificationServer getServer() {
        return server;
    }
}
