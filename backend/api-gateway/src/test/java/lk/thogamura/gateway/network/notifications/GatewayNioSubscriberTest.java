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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class GatewayNioSubscriberTest {

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
    @DisplayName("Requirement 1: Gateway connects to a running warehouse notification server")
    void shouldConnectToNotificationServer() {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(100), Duration.ofMillis(500));

        subscriber = new GatewayNioSubscriber(config);
        subscriber.start();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        assertThat(subscriber.getStatus()).isEqualTo(SubscriberStatus.CONNECTED);
        assertThat(subscriber.isConnected()).isTrue();
    }

    @Test
    @DisplayName("Requirement 2: Gateway receives a valid CHANGED notification and dispatches version to listener")
    void shouldReceiveValidNotificationAndInvokeListener() throws IOException, InterruptedException {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(100), Duration.ofMillis(500));

        subscriber = new GatewayNioSubscriber(config);

        CountDownLatch latch = new CountDownLatch(1);
        List<Long> receivedVersions = new CopyOnWriteArrayList<>();

        subscriber.addListener(version -> {
            receivedVersions.add(version);
            latch.countDown();
        });

        subscriber.start();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        // Warehouse server publishes change version 42
        server.sendNotification(42L);

        boolean received = latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(receivedVersions).containsExactly(42L);
        assertThat(subscriber.getLastReceivedVersion()).isEqualTo(42L);
        assertThat(subscriber.getNotificationCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Requirement 3: Fragmented TCP messages across multiple reads are reconstructed correctly")
    void shouldReconstructFragmentedTcpMessages() throws Exception {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(100), Duration.ofMillis(500));

        subscriber = new GatewayNioSubscriber(config);

        CountDownLatch latch = new CountDownLatch(1);
        List<Long> receivedVersions = new CopyOnWriteArrayList<>();
        subscriber.addListener(version -> {
            receivedVersions.add(version);
            latch.countDown();
        });

        subscriber.start();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        // Send frame in two separate TCP fragments
        server.send("{\"v\":1,\"type\":\"CH");
        Thread.sleep(50); // slight delay to force fragmented read
        server.send("ANGED\",\"version\":88}\n");

        boolean received = latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(receivedVersions).containsExactly(88L);
    }

    @Test
    @DisplayName("Requirement 4: Multiple frames in a single read are decoded and dispatched sequentially")
    void shouldHandleMultipleFramesInSingleRead() throws Exception {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(100), Duration.ofMillis(500));

        subscriber = new GatewayNioSubscriber(config);

        CountDownLatch latch = new CountDownLatch(3);
        List<Long> receivedVersions = new CopyOnWriteArrayList<>();
        subscriber.addListener(version -> {
            receivedVersions.add(version);
            latch.countDown();
        });

        subscriber.start();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        // Send 3 notifications in a single socket write
        String multiFrames = "{\"v\":1,\"type\":\"CHANGED\",\"version\":10}\n"
                + "{\"v\":1,\"type\":\"CHANGED\",\"version\":20}\n"
                + "{\"v\":1,\"type\":\"CHANGED\",\"version\":30}\n";
        server.send(multiFrames);

        boolean received = latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        assertThat(receivedVersions).containsExactly(10L, 20L, 30L);
    }

    @Test
    @DisplayName("Requirement 5: Malformed frames are safely rejected without crashing the subscriber")
    void shouldSafelyRejectMalformedFrames() throws Exception {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(100), Duration.ofMillis(500));

        subscriber = new GatewayNioSubscriber(config);

        CountDownLatch latch = new CountDownLatch(1);
        List<Long> receivedVersions = new CopyOnWriteArrayList<>();
        subscriber.addListener(version -> {
            receivedVersions.add(version);
            latch.countDown();
        });

        subscriber.start();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        // 1. Send malformed non-JSON frame
        server.send("corrupt-data-not-json\n");
        // 2. Send frame with unauthorized business data (should be rejected)
        server.send("{\"v\":1,\"type\":\"CHANGED\",\"version\":5,\"sku\":\"SECRET-001\"}\n");
        // 3. Send valid frame
        server.sendNotification(99L);

        boolean received = latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        // Only the valid notification was dispatched
        assertThat(receivedVersions).containsExactly(99L);
        assertThat(subscriber.isConnected()).isTrue();
    }

    @Test
    @DisplayName("Requirement 6: Gateway detects server disconnection and updates status to RECONNECTING")
    void shouldDetectServerDisconnection() {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(200), Duration.ofMillis(1000));

        subscriber = new GatewayNioSubscriber(config);
        subscriber.start();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        // Server abruptly severs the connection
        server.disconnectClient();

        await().atMost(Duration.ofSeconds(4))
                .until(() -> subscriber.getStatus() == SubscriberStatus.RECONNECTING);

        assertThat(subscriber.isConnected()).isFalse();
        assertThat(subscriber.getReconnectCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Requirement 7: Gateway automatically reconnects when the server restarts on the same port")
    void shouldReconnectWhenServerRestarts() throws Exception {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(100), Duration.ofMillis(500));

        subscriber = new GatewayNioSubscriber(config);

        List<Long> receivedVersions = new CopyOnWriteArrayList<>();
        subscriber.addListener(receivedVersions::add);

        subscriber.start();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        server.sendNotification(1L);
        await().atMost(Duration.ofSeconds(2)).until(() -> receivedVersions.contains(1L));

        // Restart server on same port (simulates temporary network failure / server reboot)
        server.restartOnSamePort();

        // Subscriber should automatically reconnect via backoff loop
        await().atMost(Duration.ofSeconds(5))
                .until(() -> subscriber.isConnected() && server.hasConnectedClient());

        // Send post-reconnect notification
        server.sendNotification(2L);
        await().atMost(Duration.ofSeconds(3)).until(() -> receivedVersions.contains(2L));

        assertThat(receivedVersions).containsExactly(1L, 2L);
        assertThat(subscriber.getReconnectCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("Requirement 8: Clean shutdown releases socket channel and terminates subscriber thread")
    void shouldShutdownCleanlyAndReleaseResources() {
        NotificationSubscriberConfig config = NotificationSubscriberConfig.forTesting(
                server.getPort(), Duration.ofMillis(100), Duration.ofMillis(500));

        subscriber = new GatewayNioSubscriber(config);
        subscriber.start();

        await().atMost(Duration.ofSeconds(3)).until(subscriber::isConnected);

        subscriber.stop();

        assertThat(subscriber.isRunning()).isFalse();
        assertThat(subscriber.getStatus()).isEqualTo(SubscriberStatus.STOPPED);
        assertThat(subscriber.isConnected()).isFalse();
    }
}
