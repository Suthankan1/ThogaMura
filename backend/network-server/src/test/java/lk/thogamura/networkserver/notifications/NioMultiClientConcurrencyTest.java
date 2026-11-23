package lk.thogamura.networkserver.notifications;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Demonstrates Test Plan T06: Single Java NIO Selector event loop efficiently serving
 * 10+ simultaneous branch subscribers without thread-per-client model.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NioMultiClientConcurrencyTest {

    private NioNotificationServer server;
    private final List<Socket> clientSockets = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Socket socket : clientSockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        clientSockets.clear();

        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    private Socket connectClient() throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getPort());
        socket.setSoTimeout(3000);
        clientSockets.add(socket);
        return socket;
    }

    @Test
    @DisplayName("T06 Concurrency: One selector loop services 12 simultaneous subscribers without thread-per-client")
    void shouldServiceTwelveSubscribersConcurrentlyWithSingleSelectorThread() throws Exception {
        final int clientCount = 12;

        NotificationServerConfig config = new NotificationServerConfig("127.0.0.1", 0, 20, 50, 8192);
        server = new NioNotificationServer(config);
        server.start();

        // 1. Connect 12 simultaneous real TCP clients
        List<BufferedReader> readers = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            Socket socket = connectClient();
            readers.add(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)));
        }

        // Wait for server to accept all 12 clients
        await().atMost(Duration.ofSeconds(4))
                .until(() -> server.getConnectedClientCount() == clientCount);

        // 2. Threading audit: Verify that the server does NOT spawn a thread per client.
        // Exactly one selector thread named 'nio-notification-eventloop' exists.
        Thread[] threads = new Thread[Thread.activeCount() * 2];
        int numThreads = Thread.enumerate(threads);
        long nioWorkerThreads = 0;
        for (int i = 0; i < numThreads; i++) {
            Thread t = threads[i];
            if (t != null && t.getName().startsWith("nio-notification-eventloop")) {
                nioWorkerThreads++;
            }
        }
        assertThat(nioWorkerThreads)
                .as("Only ONE single selector event loop thread must service all 12 clients")
                .isEqualTo(1);

        // 3. Publish 5 sequential version-only notifications concurrently from 3 producer threads
        final int messageCount = 5;
        CountDownLatch producerLatch = new CountDownLatch(messageCount);
        ExecutorService producers = Executors.newFixedThreadPool(3);

        for (int m = 1; m <= messageCount; m++) {
            final long ver = 200L + m;
            producers.submit(() -> {
                server.publish(ver);
                producerLatch.countDown();
            });
        }

        boolean published = producerLatch.await(3, TimeUnit.SECONDS);
        producers.shutdown();
        assertThat(published).isTrue();

        // A late older version can be coalesced. Every healthy subscriber must reach the
        // newest version, and the versions it receives must progress monotonically.
        for (int c = 0; c < clientCount; c++) {
            BufferedReader reader = readers.get(c);
            long previous = 200L;
            while (previous < 205L) {
                String line = reader.readLine();
                assertThat(line).isNotNull();
                ChangeNotification notification = ChangeNotification.parse(line);
                assertThat(notification.type()).isEqualTo("CHANGED");
                assertThat(notification.version()).isGreaterThan(previous).isLessThanOrEqualTo(205L);
                previous = notification.version();
            }
        }

        // 5. Client subset disconnection resilience: Disconnect 6 clients
        for (int i = 0; i < 6; i++) {
            clientSockets.get(i).close();
        }

        await().atMost(Duration.ofSeconds(4))
                .until(() -> server.getConnectedClientCount() == 6);

        // Publish another notification (version 299)
        server.publish(299L);

        // The remaining 6 clients still receive it
        for (int c = 6; c < clientCount; c++) {
            String line = readers.get(c).readLine();
            assertThat(line).isNotNull();
            ChangeNotification notification = ChangeNotification.parse(line);
            assertThat(notification.version()).isEqualTo(299L);
        }

        assertThat(server.isRunning()).isTrue();
    }
}
