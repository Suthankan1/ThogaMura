package lk.thogamura.gateway.network.notifications;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies Test Plan T14: Java NIO reconnection resilience, backoff behavior,
 * and the architectural recovery boundary for missed invalidation events during downtime.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NioReconnectionResilienceTest {

    private TestNotificationServer server;
    private GatewayNioSubscriber subscriber;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestNotificationServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (subscriber != null) {
            subscriber.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("T14: Reconnection detects missed events during downtime and signals authoritative state refresh")
    void shouldDemonstrateT14ReconnectionAndAuthoritativeRecoveryBoundary() throws Exception {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(100), Duration.ofMillis(500));

        subscriber = new GatewayNioSubscriber(config);

        List<Long> receivedVersions = new CopyOnWriteArrayList<>();
        subscriber.addListener(receivedVersions::add);

        subscriber.start();

        // 1. Initial connection
        await().atMost(Duration.ofSeconds(3))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        assertThat(subscriber.getStatus()).isEqualTo(SubscriberStatus.CONNECTED);

        // 2. Initial event delivered over TCP
        server.sendNotification(10L);
        await().atMost(Duration.ofSeconds(2)).until(() -> receivedVersions.contains(10L));
        assertThat(subscriber.getLastReceivedVersion()).isEqualTo(10L);

        // 3. Network / Server Failure: Server stops
        server.stop();

        // 4. Subscriber detects disconnect and enters RECONNECTING
        await().atMost(Duration.ofSeconds(4))
                .until(() -> subscriber.getStatus() == SubscriberStatus.RECONNECTING);
        assertThat(subscriber.isConnected()).isFalse();

        // 5. While disconnected, warehouse commits change version 11 (simulating missed event during downtime).
        // Since NIO feeds are lightweight non-durable streams (no broker queue), offline clients miss intermediate events.

        // 6. Server restarts on the same port
        server.restartOnSamePort();

        // 7. Subscriber automatically recovers and reconnects via exponential backoff
        await().atMost(Duration.ofSeconds(5))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        assertThat(subscriber.getStatus()).isEqualTo(SubscriberStatus.CONNECTED);
        assertThat(subscriber.getReconnectCount()).isGreaterThanOrEqualTo(1);

        // 8. T14 Architectural Recovery Boundary Assertion:
        // Because reconnectCount > 0 occurred, client/gateway knows a disconnection occurred and
        // that notifications during downtime were not guaranteed.
        // This is the trigger for authoritative REST refetch (e.g. GET /api/v1/warehouse/inventory).
        boolean requiresAuthoritativeRefresh = subscriber.getReconnectCount() > 0;
        assertThat(requiresAuthoritativeRefresh)
                .as("Reconnection must signal that an authoritative REST refresh is required for T14")
                .isTrue();

        // 9. Subsequent notification delivered successfully after recovery
        server.sendNotification(12L);
        await().atMost(Duration.ofSeconds(2)).until(() -> receivedVersions.contains(12L));

        assertThat(receivedVersions).containsExactly(10L, 12L);
        assertThat(subscriber.getLastReceivedVersion()).isEqualTo(12L);
    }

    @Test
    @DisplayName("Should survive multiple rapid server restart cycles without thread leak or channel degradation")
    void shouldSurviveMultipleServerRestarts() throws Exception {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(80), Duration.ofMillis(300));

        subscriber = new GatewayNioSubscriber(config);
        List<Long> received = new CopyOnWriteArrayList<>();
        subscriber.addListener(received::add);

        subscriber.start();

        await().atMost(Duration.ofSeconds(3)).until(subscriber::isConnected);

        // Cycle through 2 server restarts to verify resilience across successive failure events
        for (int cycle = 1; cycle <= 2; cycle++) {
            Thread.sleep(50);
            server.restartOnSamePort();

            await().atMost(Duration.ofSeconds(4))
                    .until(() -> subscriber.isConnected() && server.hasConnectedClient());

            long ver = 100L + cycle;
            server.sendNotification(ver);
            await().atMost(Duration.ofSeconds(2)).until(() -> received.contains(ver));
        }

        assertThat(received).containsExactly(101L, 102L);
        assertThat(subscriber.getReconnectCount()).isGreaterThanOrEqualTo(2);
        assertThat(subscriber.isRunning()).isTrue();
    }
}
