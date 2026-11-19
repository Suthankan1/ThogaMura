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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real TCP socket integration test suite for the API Gateway's command client.
 *
 * <p>Verifies wire transport behavior:
 * <ul>
 *   <li>Real loopback socket exchange of PING/PONG messages</li>
 *   <li>Strict response requestId correlation matching client request UUID</li>
 *   <li>Reconstruction of fragmented TCP response frames over the network</li>
 *   <li>Bounded read timeouts (SO_TIMEOUT)</li>
 *   <li>Safe same-requestId retries over real TCP sockets upon unexpected disconnects</li>
 *   <li>Detection of stopped/unavailable server and bounded retry termination</li>
 *   <li>Clean socket closure across successive requests without socket leaks</li>
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
    @DisplayName("1. Normal request: Real Java TCP socket round-trip exchanges PING and correlated PONG with server timestamp")
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
    @DisplayName("2. Unexpected connection close: Client retries over new TCP socket with identical requestId and succeeds")
    void shouldRetryOnUnexpectedConnectionCloseOverRealTcpSockets() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverRunning.set(true);

        AtomicInteger acceptCount = new AtomicInteger(0);
        List<String> observedRequestIds = Collections.synchronizedList(new ArrayList<>());
        String expectedRequestId = UUID.randomUUID().toString();

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

                            JsonNode req = reader.readNextFrameJson();
                            if (req != null) {
                                observedRequestIds.add(req.get("requestId").asText());
                            }

                            int count = acceptCount.incrementAndGet();
                            if (count == 1) {
                                // Simulate unexpected abort / reset on attempt 1
                                socket.close();
                            } else {
                                // Respond successfully on retry attempt 2
                                String resp = TcpMessageCodec.formatResultResponse(
                                        expectedRequestId,
                                        ProtocolInfo.STATUS_ACCEPTED,
                                        Map.of("attempt", count)
                                );
                                writer.writeFrame(resp);
                            }
                        } catch (Exception ignored) {}
                    }).start();
                } catch (IOException ignored) {
                    if (!serverRunning.get()) break;
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Config with 2 retries, 50ms backoff
        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 2000, 3000, 65536, 2, 50, 2.0, 500
        );
        TcpCommandClient client = new TcpCommandClient(config);

        JsonNode response = client.sendCommand(ProtocolInfo.COMMAND_REQUEST_STOCK, expectedRequestId, Map.of("sku", "ITEM-007", "quantity", 8));

        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(response.get("requestId").asText()).isEqualTo(expectedRequestId);
        assertThat(response.get("data").get("attempt").asInt()).isEqualTo(2);

        // Verification of real network retries
        assertThat(acceptCount.get()).isEqualTo(2);
        assertThat(observedRequestIds).containsExactly(expectedRequestId, expectedRequestId);
    }

    @Test
    @DisplayName("3. Socket read timeout: Bounded read fails with CommandTimeoutException when server hangs across retries")
    void shouldEnforceBoundedReadTimeoutAndTerminateWhenExhausted() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverRunning.set(true);

        AtomicInteger acceptCount = new AtomicInteger(0);

        serverThread = new Thread(() -> {
            while (serverRunning.get() && !serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try (socket) {
                            acceptCount.incrementAndGet();
                            // Hang without writing any response
                            Thread.sleep(5000);
                        } catch (Exception ignored) {}
                    }).start();
                } catch (IOException ignored) {
                    if (!serverRunning.get()) break;
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // 200ms read timeout, 1 retry (2 attempts total)
        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 1000, 200, 65536, 1, 10, 2.0, 100
        );
        TcpCommandClient client = new TcpCommandClient(config);

        long start = System.currentTimeMillis();
        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTimeoutException.class)
                .hasMessageContaining("Read timed out");
        long elapsed = System.currentTimeMillis() - start;

        // Bounded: should fail near ~400ms (2 attempts * 200ms + backoff), well below 5 seconds
        assertThat(elapsed).isLessThan(2500);
        assertThat(acceptCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("4. Mismatched response requestId: Immediately throws CommandCorrelationException without retry")
    void shouldFailImmediatelyOnMismatchedRequestIdOverRealSocket() throws Exception {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverRunning.set(true);

        AtomicInteger serverConnections = new AtomicInteger(0);
        String clientRequestId = UUID.randomUUID().toString();
        String rogueRequestId = UUID.randomUUID().toString();

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

                            serverConnections.incrementAndGet();
                            reader.readNextFrameJson();
                            // Return wrong requestId
                            String resp = TcpMessageCodec.formatResultResponse(
                                    rogueRequestId,
                                    ProtocolInfo.STATUS_PONG,
                                    Map.of("serverTime", Instant.now().toString())
                            );
                            writer.writeFrame(resp);
                        } catch (Exception ignored) {}
                    }).start();
                } catch (IOException ignored) {
                    if (!serverRunning.get()) break;
                }
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Configured with 3 retries, but correlation error must fail immediately on attempt 1
        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 2000, 3000, 65536, 3, 20, 2.0, 200
        );
        TcpCommandClient client = new TcpCommandClient(config);

        assertThatThrownBy(() -> client.sendCommand(ProtocolInfo.COMMAND_PING, clientRequestId, null))
                .isInstanceOf(CommandCorrelationException.class)
                .satisfies(e -> {
                    CommandCorrelationException ce = (CommandCorrelationException) e;
                    assertThat(ce.getExpectedRequestId()).isEqualTo(clientRequestId);
                    assertThat(ce.getReceivedRequestId()).isEqualTo(rogueRequestId);
                });

        // Verifies zero retry attempts were made
        assertThat(serverConnections.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("5. Client successfully reassembles response fragmented across multiple TCP packets")
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
    @DisplayName("6. Consecutive requests open and close sockets cleanly without connection leaks")
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
    @DisplayName("7. Stopping server produces ServerUnavailableException after bounded retries")
    void shouldFailClearlyWhenServerIsStopped() throws Exception {
        int port = startEchoPongServer();

        // 0 retries for immediate failure check
        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 500, 500, 65536, 0, 10, 2.0, 100
        );
        TcpCommandClient client = new TcpCommandClient(config);

        // First call succeeds
        assertThat(client.ping().get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);

        // Stop the server
        serverRunning.set(false);
        serverSocket.close();
        if (serverThread != null) {
            serverThread.join(1000);
        }

        // Subsequent call immediately fails with ServerUnavailableException
        assertThatThrownBy(client::ping)
                .isInstanceOf(ServerUnavailableException.class)
                .hasMessageContaining("Warehouse server is unavailable");
    }
}
