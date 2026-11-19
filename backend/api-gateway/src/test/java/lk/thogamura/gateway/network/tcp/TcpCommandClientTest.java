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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
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
    @DisplayName("Should throw CommandCorrelationException immediately when server returns mismatched requestId (no retry)")
    void shouldThrowCommandCorrelationExceptionWhenRequestIdMismatches() throws Exception {
        String expectedId = UUID.randomUUID().toString();
        String mismatchedId = UUID.randomUUID().toString();
        AtomicInteger serverCallCount = new AtomicInteger();

        int port = startMockServer(socket -> {
            serverCallCount.incrementAndGet();
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());

            reader.readNextFrameJson();
            // Server responds with an unexpected requestId
            String resp = TcpMessageCodec.formatResultResponse(
                    mismatchedId,
                    ProtocolInfo.STATUS_PONG,
                    Map.of("serverTime", Instant.now().toString())
            );
            writer.writeFrame(resp);
        });

        // Configured with 2 retries, but correlation error must fail immediately on attempt 1
        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 1000, 2000, 65536, 2, 50, 2.0, 500
        );
        TcpCommandClient client = new TcpCommandClient(config);

        assertThatThrownBy(() -> client.sendCommand(ProtocolInfo.COMMAND_PING, expectedId, null))
                .isInstanceOf(CommandCorrelationException.class)
                .hasMessageContaining("Response correlation failure")
                .satisfies(e -> {
                    CommandCorrelationException ce = (CommandCorrelationException) e;
                    assertThat(ce.getExpectedRequestId()).isEqualTo(expectedId);
                    assertThat(ce.getReceivedRequestId()).isEqualTo(mismatchedId);
                });

        // Verifies zero retry attempts were made
        assertThat(serverCallCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should retry transient socket drop using identical requestId and recover successfully")
    void shouldRetryOnTransientDropWithIdenticalRequestIdAndRecover() throws Exception {
        String testRequestId = UUID.randomUUID().toString();
        AtomicInteger connectionCount = new AtomicInteger(0);
        List<String> receivedRequestIds = Collections.synchronizedList(new ArrayList<>());
        List<Long> sleepDelays = Collections.synchronizedList(new ArrayList<>());

        int port = startMockServer(socket -> {
            int attempt = connectionCount.incrementAndGet();
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            JsonNode req = reader.readNextFrameJson();
            if (req != null) {
                receivedRequestIds.add(req.get("requestId").asText());
            }

            if (attempt == 1) {
                // Drop connection abruptly without sending response on attempt 1
                socket.close();
            } else {
                // Return success on retry attempt 2
                TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
                String resp = TcpMessageCodec.formatResultResponse(
                        testRequestId,
                        ProtocolInfo.STATUS_ACCEPTED,
                        Map.of("recoveredOnAttempt", attempt)
                );
                writer.writeFrame(resp);
            }
        });

        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 1000, 2000, 65536, 2, 50, 2.0, 500
        );
        // Track sleep backoff delays
        Sleeper mockSleeper = sleepDelays::add;
        TcpCommandClient client = new TcpCommandClient(config, javax.net.SocketFactory.getDefault(), mockSleeper);

        JsonNode response = client.sendCommand(ProtocolInfo.COMMAND_REQUEST_STOCK, testRequestId, Map.of("sku", "ITEM-007", "quantity", 8));

        // Result verified
        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(response.get("requestId").asText()).isEqualTo(testRequestId);
        assertThat(response.get("data").get("recoveredOnAttempt").asInt()).isEqualTo(2);

        // Verify exactly 2 connections were made
        assertThat(connectionCount.get()).isEqualTo(2);

        // Critical requirement: Verify exact identical requestId was reused on the retry attempt!
        assertThat(receivedRequestIds).hasSize(2);
        assertThat(receivedRequestIds.get(0)).isEqualTo(testRequestId);
        assertThat(receivedRequestIds.get(1)).isEqualTo(testRequestId);

        // Verify backoff sleep was invoked once for attempt 1
        assertThat(sleepDelays).containsExactly(50L);
    }

    @Test
    @DisplayName("Should preserve exact payload and generate single requestId across retries if not provided")
    void shouldPreserveSingleGeneratedRequestIdAcrossRetries() throws Exception {
        AtomicInteger connectionCount = new AtomicInteger(0);
        List<String> seenRequestIds = Collections.synchronizedList(new ArrayList<>());
        List<String> seenSkus = Collections.synchronizedList(new ArrayList<>());

        int port = startMockServer(socket -> {
            int attempt = connectionCount.incrementAndGet();
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            JsonNode req = reader.readNextFrameJson();
            if (req != null) {
                seenRequestIds.add(req.get("requestId").asText());
                seenSkus.add(req.get("data").get("sku").asText());
            }

            if (attempt <= 2) {
                socket.close(); // drop first two attempts
            } else {
                TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
                String resp = TcpMessageCodec.formatResultResponse(
                        req.get("requestId").asText(),
                        ProtocolInfo.STATUS_ACCEPTED,
                        Map.of("status", "ACCEPTED")
                );
                writer.writeFrame(resp);
            }
        });

        // 3 retries (total 4 attempts max)
        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 1000, 2000, 65536, 3, 20, 2.0, 500
        );
        TcpCommandClient client = new TcpCommandClient(config, javax.net.SocketFactory.getDefault(), millis -> {});

        // Pass null requestId to let client generate UUID
        JsonNode response = client.sendCommand(ProtocolInfo.COMMAND_REQUEST_STOCK, null, Map.of("sku", "ITEM-007", "quantity", 5));

        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(connectionCount.get()).isEqualTo(3);

        // Critical: The generated requestId must be identical across all 3 attempts!
        assertThat(seenRequestIds).hasSize(3);
        String firstGeneratedId = seenRequestIds.get(0);
        assertThat(seenRequestIds).containsOnly(firstGeneratedId);

        // And payload is preserved unchanged
        assertThat(seenSkus).containsOnly("ITEM-007");
    }

    @Test
    @DisplayName("Retries must terminate at configured maximum without retrying forever")
    void shouldTerminateRetriesAtConfiguredMaximum() throws Exception {
        AtomicInteger connectionCount = new AtomicInteger(0);
        List<Long> backoffs = Collections.synchronizedList(new ArrayList<>());

        int port = startMockServer(socket -> {
            connectionCount.incrementAndGet();
            socket.close(); // always drop
        });

        // maxRetries = 2 (initial attempt + 2 retries = 3 total)
        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 1000, 2000, 65536, 2, 100, 2.0, 1000
        );
        TcpCommandClient client = new TcpCommandClient(config, javax.net.SocketFactory.getDefault(), backoffs::add);

        assertThatThrownBy(client::ping)
                .isInstanceOf(ServerUnavailableException.class);

        // Verifies exactly 3 attempts (1 initial + 2 retries)
        assertThat(connectionCount.get()).isEqualTo(3);

        // Verifies exponential backoff progression (100ms, then 200ms)
        assertThat(backoffs).containsExactly(100L, 200L);
    }

    @Test
    @DisplayName("Should throw CommandTimeoutException when server read exceeds SO_TIMEOUT and retries exhaust")
    void shouldRetryOnReadTimeoutAndTerminateWhenExhausted() throws Exception {
        AtomicInteger acceptCount = new AtomicInteger(0);

        int port = startMockServer(socket -> {
            acceptCount.incrementAndGet();
            // Sleep longer than read timeout to force SocketTimeoutException
            Thread.sleep(1000);
        });

        // Read timeout 100ms, maxRetries 1
        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 1000, 100, 65536, 1, 10, 2.0, 500
        );
        TcpCommandClient client = new TcpCommandClient(config, javax.net.SocketFactory.getDefault(), millis -> {});

        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTimeoutException.class)
                .hasMessageContaining("Read timed out");

        // 1 initial attempt + 1 retry = 2 total
        assertThat(acceptCount.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("Should throw CommandTransportException without retry when response frame is malformed")
    void shouldNotRetryOnMalformedResponseEnvelope() throws Exception {
        AtomicInteger acceptCount = new AtomicInteger(0);

        int port = startMockServer(socket -> {
            acceptCount.incrementAndGet();
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
            reader.readNextFrameString();
            OutputStream out = socket.getOutputStream();
            out.write("Not a JSON object\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
        });

        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "127.0.0.1", port, 1000, 2000, 65536, 2, 50, 2.0, 500
        );
        TcpCommandClient client = new TcpCommandClient(config, javax.net.SocketFactory.getDefault(), millis -> {});

        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTransportException.class)
                .hasMessageContaining("Received malformed response envelope");

        // Malformed frames are not transient socket hiccups; must not retry
        assertThat(acceptCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should validate client configuration parameters and retry bounds")
    void shouldValidateConfigParameters() {
        assertThatThrownBy(() -> new TcpCommandClientConfig("", 7443).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpCommandClientConfig("127.0.0.1", 0).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpCommandClientConfig("127.0.0.1", 70000).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpCommandClientConfig("127.0.0.1", 7443, -1, 5000, 65536).validate())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TcpCommandClientConfig(
                "127.0.0.1", 7443, 3000, 5000, 65536, -1, 100, 2.0, 1000).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries must be non-negative");
        assertThatThrownBy(() -> new TcpCommandClientConfig(
                "127.0.0.1", 7443, 3000, 5000, 65536, 2, -1, 2.0, 1000).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryBackoffMs must be non-negative");
        assertThatThrownBy(() -> new TcpCommandClientConfig(
                "127.0.0.1", 7443, 3000, 5000, 65536, 2, 100, 0.5, 1000).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("retryBackoffMultiplier must be >= 1.0");
        assertThatThrownBy(() -> new TcpCommandClientConfig(
                "127.0.0.1", 7443, 3000, 5000, 65536, 2, 500, 2.0, 100).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetryBackoffMs must be >= retryBackoffMs");
    }
}
