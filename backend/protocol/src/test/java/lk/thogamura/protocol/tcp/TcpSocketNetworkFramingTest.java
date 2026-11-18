package lk.thogamura.protocol.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcpSocketNetworkFramingTest {

    private ServerSocket serverSocket;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        // Bind to loopback on an ephemeral OS-assigned port
        serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        port = serverSocket.getLocalPort();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (serverSocket != null && !serverSocket.isClosed()) {
            serverSocket.close();
        }
    }

    @Test
    @DisplayName("Performs full duplex request-response round-trip over real loopback Java sockets")
    void shouldPerformDuplexRoundtripOverRealSocket() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String requestPayload = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);

        CompletableFuture<String> serverReceivedFuture = new CompletableFuture<>();

        // Server worker thread
        Thread serverThread = new Thread(() -> {
            try (Socket clientSocket = serverSocket.accept();
                 TcpFrameReader reader = new TcpFrameReader(clientSocket.getInputStream());
                 TcpFrameWriter writer = new TcpFrameWriter(clientSocket.getOutputStream())) {

                clientSocket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);

                // Read request
                JsonNode requestNode = reader.readNextFrameJson();
                TcpMessageCodec.validateEnvelope(requestNode);
                serverReceivedFuture.complete(requestNode.get("requestId").asText());

                // Send PONG response
                String responsePayload = TcpMessageCodec.formatResultResponse(
                        requestId,
                        ProtocolInfo.STATUS_PONG,
                        Map.of("serverTime", "2026-11-18T18:20:00Z")
                );
                writer.writeFrame(responsePayload);

            } catch (Exception e) {
                serverReceivedFuture.completeExceptionally(e);
            }
        });
        serverThread.start();

        // Client side
        try (Socket client = new Socket("127.0.0.1", port);
             TcpFrameWriter clientWriter = new TcpFrameWriter(client.getOutputStream());
             TcpFrameReader clientReader = new TcpFrameReader(client.getInputStream())) {

            client.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);

            clientWriter.writeFrame(requestPayload);

            JsonNode responseNode = clientReader.readNextFrameJson();
            assertThat(responseNode.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
            assertThat(responseNode.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
            assertThat(responseNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(responseNode.get("requestId").asText()).isEqualTo(requestId);
            assertThat(responseNode.get("data").get("serverTime").asText()).isEqualTo("2026-11-18T18:20:00Z");
        }

        assertThat(serverReceivedFuture.get(5, TimeUnit.SECONDS)).isEqualTo(requestId);
        serverThread.join(2000);
    }

    @Test
    @DisplayName("Reconstructs frame split across multiple physical TCP socket writes")
    void shouldReconstructFragmentedFrameOverRealSocket() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String frameJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId,
                Map.of("sku", "ITEM-007", "quantity", 8)
        );
        byte[] frameBytes = (frameJson + "\n").getBytes(StandardCharsets.UTF_8);

        CompletableFuture<String> serverResultFuture = new CompletableFuture<>();

        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept();
                 TcpFrameReader reader = new TcpFrameReader(accepted.getInputStream())) {
                accepted.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
                String readFrame = reader.readNextFrameString();
                serverResultFuture.complete(readFrame);
            } catch (Exception e) {
                serverResultFuture.completeExceptionally(e);
            }
        });
        serverThread.start();

        try (Socket client = new Socket("127.0.0.1", port)) {
            client.setTcpNoDelay(true);
            OutputStream out = client.getOutputStream();

            // Send in small 3-byte fragments with short pauses to enforce physical TCP packet fragmentation
            int chunkSize = 3;
            for (int i = 0; i < frameBytes.length; i += chunkSize) {
                int length = Math.min(chunkSize, frameBytes.length - i);
                out.write(frameBytes, i, length);
                out.flush();
                Thread.sleep(2);
            }
        }

        String received = serverResultFuture.get(5, TimeUnit.SECONDS);
        assertThat(received).isEqualTo(frameJson);
        serverThread.join(2000);
    }

    @Test
    @DisplayName("Correctly processes multiple complete frames coalesced in a single TCP socket read")
    void shouldDecodeMultipleFramesArrivingInSingleTcpPacket() throws Exception {
        String req1 = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, UUID.randomUUID().toString(), null);
        String req2 = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_GET_PRODUCTS, UUID.randomUUID().toString(), null);
        byte[] coalescedPayload = (req1 + "\n" + req2 + "\n").getBytes(StandardCharsets.UTF_8);

        CompletableFuture<String[]> serverReceivedFuture = new CompletableFuture<>();

        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept();
                 TcpFrameReader reader = new TcpFrameReader(accepted.getInputStream())) {
                accepted.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
                String first = reader.readNextFrameString();
                String second = reader.readNextFrameString();
                serverReceivedFuture.complete(new String[]{first, second});
            } catch (Exception e) {
                serverReceivedFuture.completeExceptionally(e);
            }
        });
        serverThread.start();

        try (Socket client = new Socket("127.0.0.1", port)) {
            OutputStream out = client.getOutputStream();
            out.write(coalescedPayload);
            out.flush();
        }

        String[] frames = serverReceivedFuture.get(5, TimeUnit.SECONDS);
        assertThat(frames[0]).isEqualTo(req1);
        assertThat(frames[1]).isEqualTo(req2);
        serverThread.join(2000);
    }

    @Test
    @DisplayName("Enforces 65,536-byte limit on socket stream: throws FrameTooLargeException and emits error frame")
    void shouldHandleOversizedFrameOnSocketSafely() throws Exception {
        CompletableFuture<Throwable> serverErrorFuture = new CompletableFuture<>();

        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept();
                 TcpFrameReader reader = new TcpFrameReader(accepted.getInputStream());
                 TcpFrameWriter writer = new TcpFrameWriter(accepted.getOutputStream())) {

                accepted.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
                try {
                    reader.readNextFrameString();
                } catch (FrameTooLargeException e) {
                    serverErrorFuture.complete(e);
                    // Emit canonical error frame and terminate
                    String errorResponse = TcpMessageCodec.formatErrorResponse(
                            null,
                            ProtocolInfo.ERROR_VALIDATION,
                            "Incoming frame exceeded maximum allowed limit of 65536 bytes"
                    );
                    writer.writeFrame(errorResponse);
                }
            } catch (Exception e) {
                serverErrorFuture.completeExceptionally(e);
            }
        });
        serverThread.start();

        try (Socket client = new Socket("127.0.0.1", port);
             TcpFrameReader clientReader = new TcpFrameReader(client.getInputStream())) {

            client.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            OutputStream out = client.getOutputStream();

            // Stream 70,000 bytes without newline
            byte[] bigChunk = new byte[70_000];
            for (int i = 0; i < bigChunk.length; i++) {
                bigChunk[i] = 'X';
            }
            out.write(bigChunk);
            out.flush();

            // Client should read the server's error frame
            JsonNode errorNode = clientReader.readNextFrameJson();
            assertThat(errorNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
            assertThat(errorNode.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
            assertThat(errorNode.get("error").get("message").asText()).contains("65536 bytes");
        }

        Throwable serverError = serverErrorFuture.get(5, TimeUnit.SECONDS);
        assertThat(serverError).isInstanceOf(FrameTooLargeException.class);
        serverThread.join(2000);
    }

    @Test
    @DisplayName("Detects premature TCP socket drop mid-frame and throws TruncatedFrameException")
    void shouldDetectPrematureSocketDisconnectAsTruncatedFrame() throws Exception {
        CompletableFuture<Throwable> serverErrorFuture = new CompletableFuture<>();

        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept();
                 TcpFrameReader reader = new TcpFrameReader(accepted.getInputStream())) {
                accepted.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
                reader.readNextFrameString();
            } catch (Throwable t) {
                serverErrorFuture.complete(t);
            }
        });
        serverThread.start();

        try (Socket client = new Socket("127.0.0.1", port)) {
            OutputStream out = client.getOutputStream();
            // Send partial frame without trailing newline, then abruptly close socket
            out.write("{\"v\":1,\"type\":\"PING\"".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } // client.close() happens here

        Throwable serverError = serverErrorFuture.get(5, TimeUnit.SECONDS);
        assertThat(serverError)
                .isInstanceOf(TruncatedFrameException.class)
                .hasMessageContaining("without a trailing newline delimiter");
        serverThread.join(2000);
    }

    @Test
    @DisplayName("Accepts CRLF-terminated frame over real network socket")
    void shouldHandleCarriageReturnOverRealSocket() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String frameJson = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);

        CompletableFuture<String> serverReceivedFuture = new CompletableFuture<>();

        Thread serverThread = new Thread(() -> {
            try (Socket accepted = serverSocket.accept();
                 TcpFrameReader reader = new TcpFrameReader(accepted.getInputStream())) {
                accepted.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
                String readFrame = reader.readNextFrameString();
                serverReceivedFuture.complete(readFrame);
            } catch (Exception e) {
                serverReceivedFuture.completeExceptionally(e);
            }
        });
        serverThread.start();

        try (Socket client = new Socket("127.0.0.1", port)) {
            OutputStream out = client.getOutputStream();
            out.write((frameJson + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        String received = serverReceivedFuture.get(5, TimeUnit.SECONDS);
        assertThat(received).isEqualTo(frameJson);
        serverThread.join(2000);
    }
}
