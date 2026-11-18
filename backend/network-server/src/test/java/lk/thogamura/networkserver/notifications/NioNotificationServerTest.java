package lk.thogamura.networkserver.notifications;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NioNotificationServerTest {

    private NioNotificationServer server;
    private final List<Socket> clientSockets = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        // Bind to port 0 on loopback for isolated dynamic port allocation in tests
        server = new NioNotificationServer(0);
        server.start();
    }

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
    @DisplayName("Requirement 1, 2, 3: Ten simultaneous clients connect and all receive version-only notification")
    void shouldDeliverNotificationToTenSimultaneousClients() throws IOException {
        final int clientCount = 10;
        List<BufferedReader> readers = new ArrayList<>();

        // 1. Connect ten simultaneous clients
        for (int i = 0; i < clientCount; i++) {
            Socket socket = connectClient();
            readers.add(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)));
        }

        // Wait for server selector to accept all ten clients
        await().atMost(Duration.ofSeconds(5))
                .until(() -> server.getConnectedClientCount() == clientCount);

        assertThat(server.getConnectedClientCount()).isEqualTo(clientCount);

        // 2. Publish version-only notification
        long changeVersion = 42L;
        server.publish(changeVersion);

        // 3. Verify all ten clients receive the exact notification containing only v, type, and version
        for (int i = 0; i < clientCount; i++) {
            BufferedReader reader = readers.get(i);
            String line = reader.readLine();

            assertThat(line).isNotNull();
            assertThat(line).isEqualTo("{\"v\":1,\"type\":\"CHANGED\",\"version\":42}");

            // Verify using strict ChangeNotification parser that no sensitive business data exists
            ChangeNotification parsed = ChangeNotification.parse(line);
            assertThat(parsed.v()).isEqualTo(1);
            assertThat(parsed.type()).isEqualTo("CHANGED");
            assertThat(parsed.version()).isEqualTo(42L);
        }
    }

    @Test
    @DisplayName("Should deliver multiple sequential notifications in order")
    void shouldDeliverSequentialNotificationsInOrder() throws IOException {
        Socket socket = connectClient();
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(3))
                .until(() -> server.getConnectedClientCount() == 1);

        server.publish(101L);
        server.publish(102L);
        server.publish(103L);

        assertThat(ChangeNotification.parse(reader.readLine()).version()).isEqualTo(101L);
        assertThat(ChangeNotification.parse(reader.readLine()).version()).isEqualTo(102L);
        assertThat(ChangeNotification.parse(reader.readLine()).version()).isEqualTo(103L);
    }

    @Test
    @DisplayName("Requirement 4: Disconnected client does not crash the selector server")
    void shouldNotCrashWhenClientDisconnectsAbruptly() throws IOException {
        Socket client1 = connectClient();
        Socket client2 = connectClient();
        BufferedReader reader2 = new BufferedReader(new InputStreamReader(client2.getInputStream(), StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(3))
                .until(() -> server.getConnectedClientCount() == 2);

        // Client 1 closes connection abruptly
        client1.close();

        // Server detects client disconnection via EOF / read
        await().atMost(Duration.ofSeconds(4))
                .until(() -> server.getConnectedClientCount() == 1);

        // Server continues operating and publishes a new version
        server.publish(77L);

        // Client 2 receives the notification normally
        String line = reader2.readLine();
        assertThat(line).isNotNull();
        assertThat(ChangeNotification.parse(line).version()).isEqualTo(77L);
        assertThat(server.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Requirement 5 & 6: Slow client buffering remains bounded and drops slow subscriber safely")
    void shouldBoundSlowClientBufferingAndDisconnectSubscriber() throws IOException {
        // Create server with small queue bound of 5 messages and 2048 byte socket buffer
        server.stop();
        NotificationServerConfig restrictedConfig = new NotificationServerConfig("127.0.0.1", 0, 10, 5, 2048);
        server = new NioNotificationServer(restrictedConfig);
        server.start();

        Socket normalClient = connectClient();
        BufferedReader normalReader = new BufferedReader(new InputStreamReader(normalClient.getInputStream(), StandardCharsets.UTF_8));

        // Read from normal client continuously in a background thread
        Thread normalReaderThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted() && normalReader.readLine() != null) {
                    // actively draining
                }
            } catch (IOException ignored) {
            }
        });
        normalReaderThread.setDaemon(true);
        normalReaderThread.start();

        // Slow client: sets small receive buffer and never reads
        Socket slowClient = new Socket();
        slowClient.setReceiveBufferSize(2048);
        slowClient.connect(new InetSocketAddress("127.0.0.1", server.getPort()));
        clientSockets.add(slowClient);

        await().atMost(Duration.ofSeconds(3))
                .until(() -> server.getConnectedClientCount() == 2);

        // Publish enough messages to saturate OS socket buffer and overflow the 5-message queue
        for (long v = 1; v <= 300; v++) {
            server.publish(v);
            if (server.getConnectedClientCount() == 1) {
                break;
            }
        }

        // Server must detect that the slow subscriber exceeded bounds and disconnect it
        await().atMost(Duration.ofSeconds(5))
                .until(() -> server.getConnectedClientCount() == 1);

        // Server remains alive and functional
        assertThat(server.isRunning()).isTrue();

        normalReaderThread.interrupt();
    }

    @Test
    @DisplayName("Requirement 7 & 8: Selector server shuts down cleanly and releases socket resources")
    void shouldShutdownCleanlyAndReleaseResources() throws IOException {
        Socket socket = connectClient();
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(3))
                .until(() -> server.getConnectedClientCount() == 1);

        int boundPort = server.getPort();
        server.stop();

        assertThat(server.isRunning()).isFalse();
        assertThat(server.getConnectedClientCount()).isEqualTo(0);

        // Attempting to connect to the stopped server should fail
        assertThatThrownBy(() -> new Socket("127.0.0.1", boundPort))
                .isInstanceOf(ConnectException.class);

        // Connected client should observe EOF from server
        int read = reader.read();
        assertThat(read).isEqualTo(-1);
    }

    @Test
    @DisplayName("Security: Configuration must default to loopback (127.0.0.1) and reject publishing when stopped")
    void shouldDefaultToLoopbackAndValidatePublishState() {
        NotificationServerConfig defaultConfig = NotificationServerConfig.defaultLocal();
        assertThat(defaultConfig.host()).isEqualTo("127.0.0.1");
        assertThat(defaultConfig.port()).isEqualTo(7444);

        NioNotificationServer unstartedServer = new NioNotificationServer();
        assertThatThrownBy(() -> unstartedServer.publish(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not running");
    }
}
