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
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Verifies private LAN security restrictions for the Java NIO notification feed
 * as required by Issue #33 and Test Plan T06/T07/T14 safety boundaries.
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class NioPrivateNetworkSecurityTest {

    private NioNotificationServer server;
    private final List<Socket> clientSockets = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Socket s : clientSockets) {
            try {
                s.close();
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
    @DisplayName("Test A: Default configuration binds strictly to loopback 127.0.0.1 and disallows non-loopback exposure")
    void shouldBindToLoopbackByDefaultAndRequireApprovalForRemoteInterfaces() throws IOException {
        NotificationServerConfig defaultConfig = NotificationServerConfig.defaultLocal();
        assertThat(defaultConfig.host()).isEqualTo("127.0.0.1");
        assertThat(defaultConfig.port()).isEqualTo(7444);
        assertThat(defaultConfig.isLoopback()).isTrue();
        assertThat(defaultConfig.allowNonLoopback()).isFalse();

        // Start server on dynamic loopback port
        server = new NioNotificationServer(NotificationServerConfig.ofPort(0));
        server.start();

        assertThat(server.isRunning()).isTrue();
        assertThat(server.getBoundAddress().getAddress().isLoopbackAddress()).isTrue();
        assertThat(server.getHost()).isEqualTo("127.0.0.1");
    }

    @Test
    @DisplayName("Test B: Approved gateway client connects and receives version-only notification")
    void shouldAcceptApprovedGatewayClientAndDeliverNotification() throws IOException {
        // Configure explicit allowlist containing loopback
        NotificationServerConfig config = new NotificationServerConfig(
                "127.0.0.1", 0, 10, 50, 0, false, Set.of("127.0.0.1"));
        server = new NioNotificationServer(config);
        server.start();

        Socket socket = connectClient();
        BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

        await().atMost(Duration.ofSeconds(3))
                .until(() -> server.getConnectedClientCount() == 1);

        assertThat(server.getConnectedClientCount()).isEqualTo(1);
        assertThat(server.getRejectedConnectionCount()).isEqualTo(0);

        server.publish(555L);

        String line = reader.readLine();
        assertThat(line).isNotNull();
        ChangeNotification parsed = ChangeNotification.parse(line);
        assertThat(parsed.v()).isEqualTo(1);
        assertThat(parsed.type()).isEqualTo("CHANGED");
        assertThat(parsed.version()).isEqualTo(555L);
    }

    @Test
    @DisplayName("Test C: Unapproved client IP is denied immediately, channel closed, and zero frames delivered")
    void shouldRejectUnapprovedClientImmediatelyAndCloseChannel() throws IOException {
        // Configure allowlist for a different LAN IP (e.g. 192.168.1.99), excluding 127.0.0.1
        NotificationServerConfig config = new NotificationServerConfig(
                "127.0.0.1", 0, 10, 50, 0, false, Set.of("192.168.1.99"));
        server = new NioNotificationServer(config);
        server.start();

        // Attempt connection from 127.0.0.1 (not in allowlist)
        Socket unauthorizedClient = connectClient();

        // Server detects unapproved client address, logs warning, and closes clientChannel immediately
        await().atMost(Duration.ofSeconds(4))
                .until(() -> server.getRejectedConnectionCount() >= 1);

        assertThat(server.getConnectedClientCount())
                .as("Unauthorized client must never be registered in connectedClients")
                .isEqualTo(0);
        assertThat(server.getRejectedConnectionCount()).isEqualTo(1);

        // Reading from the closed socket returns EOF (-1) or throws SocketException
        try {
            int readByte = unauthorizedClient.getInputStream().read();
            assertThat(readByte).isEqualTo(-1);
        } catch (SocketException expected) {
            // SocketException ("Connection reset" or "Socket closed") is also a valid rejection behavior
        }

        // Server event loop remains running and healthy
        assertThat(server.isRunning()).isTrue();
    }

    @Test
    @DisplayName("Test D1: Wildcard binding (0.0.0.0 and ::) is rejected safely")
    void shouldRejectWildcardBinding() {
        assertThatThrownBy(() -> new NotificationServerConfig("0.0.0.0", 7444, 100, 50, 0, true, Collections.emptySet()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wildcard binding (0.0.0.0 / ::) is prohibited");

        assertThatThrownBy(() -> new NotificationServerConfig("::", 7444, 100, 50, 0, true, Collections.emptySet()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wildcard binding (0.0.0.0 / ::) is prohibited");
    }

    @Test
    @DisplayName("Test D2: Non-loopback binding requires explicit operator approval (allowNonLoopback=true)")
    void shouldRequireExplicitApprovalForNonLoopbackBinding() {
        assertThatThrownBy(() -> new NotificationServerConfig("192.168.1.50", 7444, 100, 50, 0, false, Set.of("192.168.1.20")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires explicit operator approval (allowNonLoopback=true)");

        // With explicit approval, configuration succeeds
        NotificationServerConfig lanConfig = NotificationServerConfig.forPrivateLan(
                "192.168.1.50", 7444, Set.of("192.168.1.20"));
        assertThat(lanConfig.allowNonLoopback()).isTrue();
        assertThat(lanConfig.host()).isEqualTo("192.168.1.50");
        assertThat(lanConfig.allowedClientAddresses()).containsExactly("192.168.1.20");
    }

    @Test
    @DisplayName("Test D3: Wildcards in client allowlist (* or 0.0.0.0) are strictly rejected")
    void shouldRejectWildcardInAllowedClientAddresses() {
        assertThatThrownBy(() -> new NotificationServerConfig("127.0.0.1", 7444, 100, 50, 0, false, Set.of("*")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wildcard entries ('*' or '0.0.0.0') are prohibited");

        assertThatThrownBy(() -> new NotificationServerConfig("127.0.0.1", 7444, 100, 50, 0, false, Set.of("0.0.0.0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Wildcard entries ('*' or '0.0.0.0') are prohibited");
    }

    @Test
    @DisplayName("Test D4: Malformed port numbers are rejected safely")
    void shouldRejectMalformedPortNumbers() {
        assertThatThrownBy(() -> new NotificationServerConfig("127.0.0.1", -1, 100, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port must be between 0 and 65535");

        assertThatThrownBy(() -> new NotificationServerConfig("127.0.0.1", 70000, 100, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("port must be between 0 and 65535");
    }

    @Test
    @DisplayName("Test E: Frame payload security strictly preserves version-only wire contract")
    void shouldPreserveVersionOnlyWireContractWithoutSensitiveData() {
        ChangeNotification notification = ChangeNotification.of(42L);
        String wireJson = notification.toWireJson();

        assertThat(wireJson).isEqualTo("{\"v\":1,\"type\":\"CHANGED\",\"version\":42}\n");
        assertThat(wireJson).doesNotContain("sku");
        assertThat(wireJson).doesNotContain("quantity");
        assertThat(wireJson).doesNotContain("customer");
        assertThat(wireJson).doesNotContain("token");
        assertThat(wireJson).doesNotContain("key");
    }
}
