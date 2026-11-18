package lk.thogamura.gateway.network.notifications;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class NotificationSubscriberLifecycleTest {

    private TestNotificationServer server;
    private NotificationSubscriberLifecycle lifecycle;

    @BeforeEach
    void setUp() throws IOException {
        server = new TestNotificationServer();
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (lifecycle != null) {
            lifecycle.stop();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should start and stop subscriber via SmartLifecycle and deliver notifications to injected listeners")
    void shouldStartAndStopViaLifecycle() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        List<Long> received = new CopyOnWriteArrayList<>();
        NotificationListener listener = v -> {
            received.add(v);
            latch.countDown();
        };

        lifecycle = new NotificationSubscriberLifecycle(
                "127.0.0.1",
                server.getPort(),
                65536,
                100,
                500,
                1.5,
                List.of(listener)
        );

        assertThat(lifecycle.isRunning()).isFalse();

        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> lifecycle.isConnected() && server.hasConnectedClient());

        server.sendNotification(777L);

        boolean notified = latch.await(3, TimeUnit.SECONDS);
        assertThat(notified).isTrue();
        assertThat(received).containsExactly(777L);

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(lifecycle.isConnected()).isFalse();
    }

    @Test
    @DisplayName("Subscriber must not block or fail application startup when the warehouse server is unavailable")
    void shouldNotBlockStartupWhenServerUnavailable() {
        // Stop the test server so the target port has nothing listening
        server.stop();

        lifecycle = new NotificationSubscriberLifecycle(
                "127.0.0.1",
                server.getPort(),
                65536,
                100,
                500,
                1.5,
                List.of()
        );

        // start() should return immediately without throwing an exception or blocking
        long startTime = System.currentTimeMillis();
        lifecycle.start();
        long elapsed = System.currentTimeMillis() - startTime;

        assertThat(elapsed).isLessThan(1000);
        assertThat(lifecycle.isRunning()).isTrue();

        // Status should transition to CONNECTING or RECONNECTING in background
        await().atMost(Duration.ofSeconds(2))
                .until(() -> lifecycle.getStatus() == SubscriberStatus.RECONNECTING
                        || lifecycle.getStatus() == SubscriberStatus.CONNECTING);

        lifecycle.stop();
        assertThat(lifecycle.isRunning()).isFalse();
    }
}
