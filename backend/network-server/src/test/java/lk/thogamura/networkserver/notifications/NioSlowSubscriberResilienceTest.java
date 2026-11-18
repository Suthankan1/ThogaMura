package lk.thogamura.networkserver.notifications;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies slow subscriber eviction, outbound queue memory bounds, and failure isolation
 * under real non-blocking Java NIO channels as required by Issue #20 and Test Plan T07.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NioSlowSubscriberResilienceTest {

    private NioNotificationServer server;
    private final List<Socket> sockets = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Socket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
        sockets.clear();

        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    private Socket connectSocket() throws IOException {
        Socket s = new Socket("127.0.0.1", server.getPort());
        s.setSoTimeout(3000);
        sockets.add(s);
        return s;
    }

    @Test
    @DisplayName("T07 Client Isolation: Slow subscriber is evicted on queue overflow while healthy clients receive all events")
    void shouldIsolateHealthyClientsWhenSlowClientOverflowsQueue() throws Exception {
        // Configure small queue bound (5 messages) and small send buffer (2048 bytes)
        NotificationServerConfig config = new NotificationServerConfig("127.0.0.1", 0, 10, 5, 2048);
        server = new NioNotificationServer(config);
        server.start();

        // 1. Connect slow client that never reads from the socket
        Socket slowClient = new Socket();
        slowClient.setReceiveBufferSize(2048);
        slowClient.connect(new InetSocketAddress("127.0.0.1", server.getPort()));
        sockets.add(slowClient);

        // 2. Connect two healthy clients that actively consume notifications
        Socket healthy1 = connectSocket();
        Socket healthy2 = connectSocket();

        await().atMost(Duration.ofSeconds(3))
                .until(() -> server.getConnectedClientCount() == 3);

        List<Long> healthy1Versions = new CopyOnWriteArrayList<>();
        List<Long> healthy2Versions = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(40); // 20 messages * 2 healthy clients

        AtomicBoolean reading = new AtomicBoolean(true);

        Runnable reader1 = () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(healthy1.getInputStream(), StandardCharsets.UTF_8))) {
                while (reading.get()) {
                    String line = r.readLine();
                    if (line != null) {
                        ChangeNotification cn = ChangeNotification.parse(line);
                        healthy1Versions.add(cn.version());
                        latch.countDown();
                    }
                }
            } catch (Exception ignored) {
            }
        };

        Runnable reader2 = () -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(healthy2.getInputStream(), StandardCharsets.UTF_8))) {
                while (reading.get()) {
                    String line = r.readLine();
                    if (line != null) {
                        ChangeNotification cn = ChangeNotification.parse(line);
                        healthy2Versions.add(cn.version());
                        latch.countDown();
                    }
                }
            } catch (Exception ignored) {
            }
        };

        Thread t1 = new Thread(reader1, "healthy-reader-1");
        Thread t2 = new Thread(reader2, "healthy-reader-2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        // 3. Publish 20 messages. The slow client will fail to drain and exceed the 5-message bound.
        for (long v = 1; v <= 20; v++) {
            server.publish(v);
            Thread.sleep(10);
        }

        // 4. Verify slow subscriber was evicted by the NIO server
        await().atMost(Duration.ofSeconds(5))
                .until(() -> server.getConnectedClientCount() <= 2);

        // 5. Verify healthy clients received all 20 published notifications without loss
        boolean completed = latch.await(4, TimeUnit.SECONDS);
        reading.set(false);

        assertThat(completed).isTrue();
        assertThat(healthy1Versions).hasSize(20);
        assertThat(healthy2Versions).hasSize(20);
        assertThat(healthy1Versions.get(0)).isEqualTo(1L);
        assertThat(healthy1Versions.get(19)).isEqualTo(20L);

        // 6. Verify server event loop remains running and healthy
        assertThat(server.isRunning()).isTrue();
    }

    @Test
    @DisplayName("T07 Fault Tolerance: Client abruptly terminated with TCP RST does not crash NIO selector")
    void shouldDisconnectAbruptlyClosedClientsWithoutSelectorCrash() throws Exception {
        server = new NioNotificationServer(0);
        server.start();

        Socket regular = connectSocket();
        Socket badClient = connectSocket();
        // setSoLinger(true, 0) forces immediate TCP RST packet when closed
        badClient.setSoLinger(true, 0);

        await().atMost(Duration.ofSeconds(3))
                .until(() -> server.getConnectedClientCount() == 2);

        // Abruptly sever badClient connection with RST
        badClient.close();

        // Server detects client loss and cleans up key
        await().atMost(Duration.ofSeconds(4))
                .until(() -> server.getConnectedClientCount() == 1);

        // Selector loop continues processing normally
        server.publish(999L);

        BufferedReader reader = new BufferedReader(new InputStreamReader(regular.getInputStream(), StandardCharsets.UTF_8));
        String line = reader.readLine();
        assertThat(line).isNotNull();
        assertThat(ChangeNotification.parse(line).version()).isEqualTo(999L);
        assertThat(server.isRunning()).isTrue();
    }
}
