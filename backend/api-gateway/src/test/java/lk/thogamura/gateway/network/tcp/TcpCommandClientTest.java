package lk.thogamura.gateway.network.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcpCommandClientTest {

    private ServerSocket serverSocket;
    private Thread serverThread;

    @AfterEach
    void tearDown() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
        }
    }

    private int startMockServer(MockServerHandler handler) throws IOException {
        serverSocket = new ServerSocket(0);
        int port = serverSocket.getLocalPort();
        serverThread = new Thread(() -> {
            try {
                while (!serverSocket.isClosed()) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> {
                        try (socket) {
                            handler.handle(socket);
                        } catch (Exception ignored) {}
                    }).start();
                }
            } catch (IOException ignored) {}
        });
        serverThread.setDaemon(true);
        serverThread.start();
        return port;
    }

    @FunctionalInterface
    interface MockServerHandler {
        void handle(Socket socket) throws Exception;
    }

    @Test
    @DisplayName("Should send PING command and receive correlated PONG response")
    void shouldSendPingAndReceivePong() throws Exception {
        int port = startMockServer(socket -> {
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());

            JsonNode req = reader.readNextFrameJson();
            String reqId = req.get("requestId").asText();

            String resp = TcpMessageCodec.formatResultResponse(
                    reqId,
                    ProtocolInfo.STATUS_PONG,
                    Map.of("serverTime", Instant.now().toString())
            );
            writer.writeFrame(resp);
        });

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 1000, 2000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        JsonNode pongNode = client.ping();
        assertThat(pongNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
        assertThat(pongNode.get("data").hasNonNull("serverTime")).isTrue();
    }

    @Test
    @DisplayName("Should enforce strict requestId correlation and succeed when IDs match")
    void shouldCorrelateRequestIdStrictly() throws Exception {
        String testRequestId = UUID.randomUUID().toString();
        AtomicReference<String> receivedByServer = new AtomicReference<>();

        int port = startMockServer(socket -> {
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());

            JsonNode req = reader.readNextFrameJson();
            receivedByServer.set(req.get("requestId").asText());

            String resp = TcpMessageCodec.formatResultResponse(
                    testRequestId,
                    ProtocolInfo.STATUS_ACCEPTED,
                    Map.of("message", "ok")
            );
            writer.writeFrame(resp);
        });

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 1000, 2000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        JsonNode response = client.sendCommand(ProtocolInfo.COMMAND_GET_PRODUCTS, testRequestId, null);
        assertThat(response.get("requestId").asText()).isEqualTo(testRequestId);
        assertThat(receivedByServer.get()).isEqualTo(testRequestId);
    }

    @Test
    @DisplayName("Should throw CommandCorrelationException when server returns mismatched requestId")
    void shouldThrowCommandCorrelationExceptionWhenRequestIdMismatches() throws Exception {
        String expectedId = UUID.randomUUID().toString();
        String mismatchedId = UUID.randomUUID().toString();

        int port = startMockServer(socket -> {
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());

            reader.readNextFrameJson();
            // Server responds with a different requestId!
            String resp = TcpMessageCodec.formatResultResponse(
                    mismatchedId,
                    ProtocolInfo.STATUS_PONG,
                    Map.of("serverTime", Instant.now().toString())
            );
            writer.writeFrame(resp);
        });

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 1000, 2000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        assertThatThrownBy(() -> client.sendCommand(ProtocolInfo.COMMAND_PING, expectedId, null))
                .isInstanceOf(CommandCorrelationException.class)
                .hasMessageContaining("Response correlation failure")
                .satisfies(e -> {
                    CommandCorrelationException ce = (CommandCorrelationException) e;
                    assertThat(ce.getExpectedRequestId()).isEqualTo(expectedId);
                    assertThat(ce.getReceivedRequestId()).isEqualTo(mismatchedId);
                });
    }

    @Test
    @DisplayName("Should throw ServerUnavailableException when target server is offline")
    void shouldThrowServerUnavailableExceptionWhenServerIsOffline() throws IOException {
        // Find an unused local port
        ServerSocket temp = new ServerSocket(0);
        int closedPort = temp.getLocalPort();
        temp.close();

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", closedPort, 500, 500, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        assertThatThrownBy(client::ping)
                .isInstanceOf(ServerUnavailableException.class)
                .hasMessageContaining("Warehouse server is unavailable");
    }

    @Test
    @DisplayName("Should throw CommandTimeoutException when server accepts but read exceeds SO_TIMEOUT")
    void shouldThrowCommandTimeoutExceptionOnReadTimeout() throws Exception {
        CountDownLatch clientConnected = new CountDownLatch(1);
        int port = startMockServer(socket -> {
            clientConnected.countDown();
            // Sleep longer than client's read timeout
            Thread.sleep(1000);
        });

        // 200ms read timeout
        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 1000, 200, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTimeoutException.class)
                .hasMessageContaining("Read timed out");
    }

    @Test
    @DisplayName("Should throw ServerUnavailableException when server drops connection prematurely")
    void shouldThrowServerUnavailableExceptionOnPrematureServerClosure() throws Exception {
        int port = startMockServer(socket -> {
            // Read request then close immediately without sending response
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            reader.readNextFrameString();
            socket.close();
        });

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 1000, 2000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        assertThatThrownBy(client::ping)
                .isInstanceOf(ServerUnavailableException.class)
                .hasMessageContaining("closed connection cleanly without returning a response");
    }

    @Test
    @DisplayName("Should throw CommandTransportException when server returns malformed response frame")
    void shouldThrowCommandTransportExceptionOnMalformedResponse() throws Exception {
        int port = startMockServer(socket -> {
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            reader.readNextFrameString();
            OutputStream out = socket.getOutputStream();
            out.write("Not a JSON object\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        });

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port, 1000, 2000, 65536);
        TcpCommandClient client = new TcpCommandClient(config);

        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTransportException.class)
                .hasMessageContaining("Received malformed response envelope");
    }

    @Test
    @DisplayName("Should validate client configuration parameters")
    void shouldValidateConfigParameters() {
        assertThatThrownBy(() -> new TcpCommandClientConfig("", 7443).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpCommandClientConfig("127.0.0.1", 0).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpCommandClientConfig("127.0.0.1", 70000).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpCommandClientConfig("127.0.0.1", 7443, -1, 5000, 65536).validate())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
