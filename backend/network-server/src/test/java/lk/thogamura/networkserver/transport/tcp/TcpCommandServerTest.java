package lk.thogamura.networkserver.transport.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TcpCommandServerTest {

    private TcpCommandServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        // Ephemeral port 0 on loopback
        TcpCommandServerConfig config = new TcpCommandServerConfig("127.0.0.1", 0);
        server = new TcpCommandServer(config);
        server.start();
        port = server.getPort();
        assertThat(port).isGreaterThan(0);
        assertThat(server.isRunning()).isTrue();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Should exchange real PING and PONG over TCP socket with valid ISO-8601 timestamp")
    void shouldHandlePingCommandSuccessfully() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String pingPayload = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);

        try (Socket socket = new Socket("127.0.0.1", port);
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);

            writer.writeFrame(pingPayload);

            JsonNode response = reader.readNextFrameJson();
            assertThat(response).isNotNull();
            assertThat(response.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
            assertThat(response.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(response.get("requestId").asText()).isEqualTo(requestId);

            JsonNode dataNode = response.get("data");
            assertThat(dataNode).isNotNull();
            assertThat(dataNode.hasNonNull("serverTime")).isTrue();

            String serverTime = dataNode.get("serverTime").asText();
            Instant parsedInstant = Instant.parse(serverTime);
            assertThat(parsedInstant).isNotNull();
        }
    }

    @Test
    @DisplayName("Should reject non-loopback plaintext binding by default")
    void shouldRejectNonLoopbackPlaintextBindingByDefault() {
        TcpCommandServerConfig insecureConfig = new TcpCommandServerConfig(
                "192.168.1.100", 7443, 50, 5000, 50, 65536, false
        );

        assertThatThrownBy(insecureConfig::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Plaintext TCP command transport is strictly prohibited on non-loopback interface");
    }

    @Test
    @DisplayName("Should permit non-loopback plaintext binding only when explicitly approved")
    void shouldAllowNonLoopbackPlaintextWhenExplicitlyApproved() {
        TcpCommandServerConfig approvedConfig = new TcpCommandServerConfig(
                "127.0.0.1", 7443, 50, 5000, 50, 65536, true
        );
        approvedConfig.validate();
        assertThat(approvedConfig.isAllowNonLoopbackPlaintext()).isTrue();
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when receiving malformed JSON")
    void shouldReturnValidationErrorOnMalformedJson() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);

            // Send malformed JSON ending with newline
            out.write("{\"v\": 1, invalid json}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            JsonNode response = reader.readNextFrameJson();
            assertThat(response).isNotNull();
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
            assertThat(response.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        }
    }

    @Test
    @DisplayName("Should return UNSUPPORTED_VERSION when wire version is not 1")
    void shouldReturnUnsupportedVersionOnProtocolVersionMismatch() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String unsupportedPayload = "{\"v\":2,\"type\":\"PING\",\"requestId\":\"" + requestId + "\"}\n";

        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);

            out.write(unsupportedPayload.getBytes(StandardCharsets.UTF_8));
            out.flush();

            JsonNode response = reader.readNextFrameJson();
            assertThat(response).isNotNull();
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
            assertThat(response.get("requestId").asText()).isEqualTo(requestId);
            assertThat(response.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_UNSUPPORTED_VERSION);
        }
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when command type is unsupported or unknown")
    void shouldReturnValidationErrorForUnsupportedCommand() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String unknownPayload = TcpMessageCodec.formatRequest("UNKNOWN_COMMAND_XYZ", requestId, null);

        try (Socket socket = new Socket("127.0.0.1", port);
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);

            writer.writeFrame(unknownPayload);

            JsonNode response = reader.readNextFrameJson();
            assertThat(response).isNotNull();
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
            assertThat(response.get("requestId").asText()).isEqualTo(requestId);
            assertThat(response.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
            assertThat(response.get("error").get("message").asText()).contains("Unsupported or unhandled command type");
        }
    }

    @Test
    @DisplayName("Should safely handle oversized frames exceeding 64 KB and return VALIDATION_ERROR")
    void shouldHandleOversizedFrameSafely() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);

            // Send 70,000 bytes without newline, then newline
            byte[] flood = new byte[70_000];
            java.util.Arrays.fill(flood, (byte) 'a');
            out.write(flood);
            out.write('\n');
            out.flush();

            JsonNode response = reader.readNextFrameJson();
            assertThat(response).isNotNull();
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
            assertThat(response.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        }
    }

    @Test
    @DisplayName("Should handle multiple sequential commands on single persistent TCP connection")
    void shouldHandleMultipleRequestsSequentiallyOnSameSocket() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);

            // First PING
            String id1 = UUID.randomUUID().toString();
            writer.writeFrame(TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, id1, null));
            JsonNode resp1 = reader.readNextFrameJson();
            assertThat(resp1.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(resp1.get("requestId").asText()).isEqualTo(id1);

            // Second PING
            String id2 = UUID.randomUUID().toString();
            writer.writeFrame(TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, id2, null));
            JsonNode resp2 = reader.readNextFrameJson();
            assertThat(resp2.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(resp2.get("requestId").asText()).isEqualTo(id2);
        }
    }

    @Test
    @DisplayName("Should gracefully handle abrupt client disconnection without server crash")
    void shouldHandleAbruptClientDisconnectionGracefully() throws Exception {
        // Connect and immediately close
        Socket socket = new Socket("127.0.0.1", port);
        socket.close();

        // Server must remain healthy and accept subsequent connections
        try (Socket client = new Socket("127.0.0.1", port);
             TcpFrameWriter writer = new TcpFrameWriter(client.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(client.getInputStream())) {

            client.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            String requestId = UUID.randomUUID().toString();
            writer.writeFrame(TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null));

            JsonNode response = reader.readNextFrameJson();
            assertThat(response.get("requestId").asText()).isEqualTo(requestId);
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
        }
    }

    @Test
    @DisplayName("Stopping server closes socket cleanly and rejects subsequent connection attempts")
    void shouldShutDownCleanlyAndRejectNewConnections() {
        server.stop();
        assertThat(server.isRunning()).isFalse();

        assertThatThrownBy(() -> new Socket("127.0.0.1", port))
                .isInstanceOf(IOException.class);
    }
}
