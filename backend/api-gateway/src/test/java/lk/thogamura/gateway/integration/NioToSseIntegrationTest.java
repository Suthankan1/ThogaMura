package lk.thogamura.gateway.integration;

import lk.thogamura.gateway.GatewayApplication;
import lk.thogamura.gateway.network.notifications.NotificationSubscriberLifecycle;
import lk.thogamura.gateway.network.notifications.TestNotificationServer;
import lk.thogamura.gateway.sse.SseNotificationBroadcaster;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test demonstrating the complete networking path:
 *
 * <pre>
 * Raw TCP Loopback Server (TestNotificationServer)
 *               |
 *               | Real Java NIO SocketChannel (TCP)
 *               v
 * GatewayNioSubscriber (lk.thogamura.gateway)
 *               |
 *               | NotificationListener callback
 *               v
 * SseNotificationBroadcaster (Asynchronous Executor)
 *               |
 *               | HTTP text/event-stream
 *               v
 * GET /api/v1/events (Real HTTP SSE Client)
 * </pre>
 */
@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NioToSseIntegrationTest {

    private static TestNotificationServer nioServer;

    @LocalServerPort
    private int httpPort;

    @Autowired
    private NotificationSubscriberLifecycle subscriberLifecycle;

    @Autowired
    private SseNotificationBroadcaster broadcaster;

    @BeforeAll
    static void startNioServer() throws Exception {
        nioServer = new TestNotificationServer();
        nioServer.start();
    }

    @AfterAll
    static void stopNioServer() {
        if (nioServer != null) {
            nioServer.stop();
        }
    }

    @AfterEach
    void cleanUpEmitters() {
        if (broadcaster != null) {
            broadcaster.closeAll();
        }
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("thogamura.notifications.subscriber.enabled", () -> "true");
        registry.add("thogamura.notifications.subscriber.host", () -> "127.0.0.1");
        registry.add("thogamura.notifications.subscriber.port", () -> nioServer.getPort());
        registry.add("thogamura.notifications.subscriber.initial-backoff-ms", () -> "100");
        registry.add("thogamura.notifications.subscriber.max-backoff-ms", () -> "500");
    }

    @Test
    @DisplayName("Real TCP warehouse notification propagates end-to-end to HTTP SSE client with version-only payload")
    void shouldPropagateTcpNotificationToHttpSseClient() throws Exception {
        // 1. Verify Gateway NIO subscriber connected via TCP to our loopback NIO server
        await().atMost(Duration.ofSeconds(4))
                .until(() -> subscriberLifecycle.isConnected() && nioServer.hasConnectedClient());

        // 2. Connect an actual HTTP SSE client to GET /api/v1/events
        CountDownLatch firstEventLatch = new CountDownLatch(1);
        CountDownLatch secondEventLatch = new CountDownLatch(1);
        List<String> receivedLines = new CopyOnWriteArrayList<>();
        AtomicBoolean reading = new AtomicBoolean(true);

        URL url = URI.create("http://127.0.0.1:" + httpPort + "/api/v1/events").toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "text/event-stream");
        connection.setReadTimeout(10000);

        Thread clientThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while (reading.get() && (line = reader.readLine()) != null) {
                    if (!line.isEmpty()) {
                        receivedLines.add(line);
                        if (line.contains("data:501")) {
                            firstEventLatch.countDown();
                        } else if (line.contains("data:502")) {
                            secondEventLatch.countDown();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }, "test-sse-client");
        clientThread.start();

        // Ensure the SSE client registration is acknowledged on the gateway
        await().atMost(Duration.ofSeconds(2))
                .until(() -> broadcaster.getActiveEmitterCount() >= 1);

        // 3. Publish valid notification over raw TCP socket from warehouse server
        nioServer.sendNotification(501L);

        // Verify the HTTP SSE client received stock-changed with data:501
        boolean firstReceived = firstEventLatch.await(3, TimeUnit.SECONDS);
        assertThat(firstReceived).isTrue();
        assertThat(receivedLines).anyMatch(l -> l.contains("event:stock-changed"));
        assertThat(receivedLines).anyMatch(l -> l.contains("data:501"));

        // 4. Send an invalid frame over TCP: {"v":1,"type":"INVALID","version":999}\n
        nioServer.send("{\"v\":1,\"type\":\"INVALID\",\"version\":999}\n");
        Thread.sleep(100); // Allow subscriber event loop to process and discard

        // 5. Send another valid notification over TCP to confirm subscriber is still healthy
        nioServer.sendNotification(502L);

        boolean secondReceived = secondEventLatch.await(3, TimeUnit.SECONDS);
        assertThat(secondReceived).isTrue();
        assertThat(receivedLines).anyMatch(l -> l.contains("data:502"));

        // Confirm the invalid frame 999 was never broadcast
        assertThat(receivedLines).noneMatch(l -> l.contains("999"));

        // Confirm no sensitive fields are present in the HTTP stream
        for (String line : receivedLines) {
            assertThat(line).doesNotContain("sku");
            assertThat(line).doesNotContain("quantity");
            assertThat(line).doesNotContain("credentials");
        }

        // Clean up HTTP client
        reading.set(false);
        connection.disconnect();
        clientThread.interrupt();
    }
}
