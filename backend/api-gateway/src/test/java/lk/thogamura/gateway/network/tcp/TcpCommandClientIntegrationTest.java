package lk.thogamura.gateway.network.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real TCP socket integration test suite for the API Gateway's command client.
 *
 * <p>Verifies wire transport behavior:
 * <ul>
 *   <li>Real loopback socket exchange of PING/PONG messages</li>
 *   <li>Response requestId matching client request UUID</li>
 *   <li>Reconstruction of fragmented TCP response frames over the network</li>
 *   <li>Bounded read timeouts (SO_TIMEOUT)</li>
 *   <li>Detection of stopped/unavailable server</li>
 *   <li>Clean socket closure across successive requests</li>
 * </ul>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TcpCommandClientIntegrationTest {

    private ServerSocket serverSocket;
    private Thread serverThread;
    private final AtomicBoolean serverRunning = new AtomicBoolean(false);

    @AfterEach
    void tearDown() {
        serverRunning.set(false);
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
        }
    }

    private int startEchoPongServer() throws IOException {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverRunning.set(true);

        serverThread = new Thread(() -> {
            while (serverRunning.get() && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try (socket;
                             InputStream in = socket.getInputStream();
                             OutputStream out = socket.getOutputStream();
                             TcpFrameReader reader = new TcpFrameReader(in);
                             TcpFrameWriter writer = new TcpFrameWriter(out)) {

                            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
                            JsonNode req = reader.readNextFrameJson();
                            if (req != null) {
                                String reqId = req.get("requestId").asText();
                                String resp = TcpMessageCodec.formatResultResponse(
                                        reqId,
                                        ProtocolInfo.STATUS_PONG,
                                        Map.of("serverTime", Instant.now().toString())
                                );
                                writer.writeFrame(resp);
                            }
                        } catch (Exception ignored) {}
                    }).start();
                } catch (IOException ignored) {
                    if (!serverRunning.get()) break;
                }
            }
        }, "integration-pong-server");
        serverThread.setDaemon(true);
        serverThread.start();
        return port;
    }

    @Test
    @DisplayName("Real Java TCP socket round-trip exchanges PING and correlated PONG with server timestamp")
    void shouldExchangePingPongOverRealSocket() throws Exception {
        int port = startEchoPongServer();

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 2000, 3000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        String requestId = UUID.randomUUID().toString();
        JsonNode response = client.sendCommand(ProtocolInfo.COMMAND_PING, requestId, null);

        assertThat(response).isNotNull();
        assertThat(response.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
        assertThat(response.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
        assertThat(response.get("requestId").asText()).isEqualTo(requestId);

        JsonNode data = response.get("data");
        assertThat(data).isNotNull();
        assertThat(data.hasNonNull("serverTime")).isTrue();
        Instant serverTime = Instant.parse(data.get("serverTime").asText());
        assertThat(serverTime).isNotNull();
    }

    @Test
    @DisplayName("Client successfully reassembles response fragmented across multiple TCP packets")
    void shouldHandleFragmentedResponseFromTcpSocket() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverRunning.set(true);

        String explicitRequestId = UUID.randomUUID().toString();

        serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept();
                 InputStream in = socket.getInputStream();
                 OutputStream out = socket.getOutputStream();
                 TcpFrameReader reader = new TcpFrameReader(in)) {

                reader.readNextFrameJson();

                String completeResponse = TcpMessageCodec.formatResultResponse(
                        explicitRequestId,
                        ProtocolInfo.STATUS_PONG,
                        Map.of("serverTime", Instant.now().toString())
                ) + "\n";

                byte[] bytes = completeResponse.getBytes(StandardCharsets.UTF_8);
                int mid = bytes.length / 2;

                // Send first half
                out.write(bytes, 0, mid);
                out.flush();

                // Small pause to force physical packet boundary
                Thread.sleep(50);

                // Send second half
                out.write(bytes, mid, bytes.length - mid);
                out.flush();

            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 2000, 3000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        JsonNode response = client.sendCommand(ProtocolInfo.COMMAND_PING, explicitRequestId, null);
        assertThat(response.get("requestId").asText()).isEqualTo(explicitRequestId);
        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
    }

    @Test
    @DisplayName("Consecutive requests open and close sockets cleanly without connection leaks")
    void shouldExecuteSuccessiveRequestsCleanly() throws Exception {
        int port = startEchoPongServer();

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 2000, 3000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        for (int i = 0; i < 5; i++) {
            JsonNode pong = client.ping();
            assertThat(pong.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
        }
    }

    @Test
    @DisplayName("Stopping server produces immediate ServerUnavailableException on client")
    void shouldFailClearlyWhenServerIsStopped() throws Exception {
        int port = startEchoPongServer();

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 1000, 1000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        // First call succeeds
        assertThat(client.ping().get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);

        // Stop the server
        serverRunning.set(false);
        serverSocket.close();
        if (serverThread != null) {
            serverThread.join(1000);
        }

        // Subsequent call immediately fails
        assertThatThrownBy(client::ping)
                .isInstanceOf(ServerUnavailableException.class)
                .hasMessageContaining("Warehouse server is unavailable");
    }

    @Test
    @DisplayName("Read timeout is bounded and fails with CommandTimeoutException when server hangs")
    void shouldEnforceBoundedReadTimeout() throws Exception {
        CountDownLatch clientConnected = new CountDownLatch(1);

        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverRunning.set(true);

        serverThread = new Thread(() -> {
            try (Socket socket = serverSocket.accept()) {
                clientConnected.countDown();
                // Hang without responding until test ends
                Thread.sleep(5000);
            } catch (Exception ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // 300ms read timeout
        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 1000, 300, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        long start = System.currentTimeMillis();
        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTimeoutException.class)
                .hasMessageContaining("Read timed out");
        long elapsed = System.currentTimeMillis() - start;

        // Bounded: should fail near 300ms, well below 5 seconds
        assertThat(elapsed).isLessThan(2000);
    }
}
