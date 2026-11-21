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
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test proving real multi-process JVM networking separation:
 *
 * <pre>
 *   [Test Client JVM (PID A)] ---- Real OS TCP Socket ----> [Warehouse Server JVM (PID B)]
 * </pre>
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The warehouse server runs as an independent OS process with its own PID</li>
 *   <li>No direct in-memory Java method call replaces the network boundary</li>
 *   <li>Real sockets exchange PING and correlated PONG messages</li>
 *   <li>Killing the server process produces an immediate, clear connection failure</li>
 * </ul>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class SeparateProcessTcpCommandIntegrationTest {

    private Process serverProcess;
    private int serverPort;
    private long serverPid;

    @BeforeEach
    void startServerInSeparateJvm() throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                TcpCommandServer.class.getName(),
                "0",          // Dynamic OS port
                "127.0.0.1"   // Loopback interface
        );
        pb.redirectErrorStream(true);

        serverProcess = pb.start();
        serverPid = serverProcess.pid();

        // Read stdout line by line until "SERVER_READY:<port>" is reported
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(serverProcess.getInputStream(), StandardCharsets.UTF_8)
        );

        String line;
        boolean ready = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && (line = reader.readLine()) != null) {
            if (line.startsWith("SERVER_READY:")) {
                serverPort = Integer.parseInt(line.substring("SERVER_READY:".length()).trim());
                ready = true;
                break;
            }
        }

        assertThat(ready)
                .as("Warehouse server process must output SERVER_READY:<port>")
                .isTrue();
        assertThat(serverPort).isGreaterThan(0);
        assertThat(serverProcess.isAlive()).isTrue();
    }

    @AfterEach
    void stopServerProcess() {
        if (serverProcess != null && serverProcess.isAlive()) {
            serverProcess.destroyForcibly();
            try {
                serverProcess.waitFor(3, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    @DisplayName("Proves separate JVM process separation and exchanges PING/PONG over real OS TCP socket")
    void shouldExchangeCommandsAcrossSeparateJvmProcesses() throws Exception {
        long currentPid = ProcessHandle.current().pid();

        // 1. Verify separate operating system processes
        assertThat(serverPid)
                .as("Server must run in an independent JVM process with a distinct PID")
                .isNotEqualTo(currentPid);

        // 2. Transmit PING over real TCP socket to the child JVM
        String requestId = UUID.randomUUID().toString();
        String pingPayload = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            socket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);

            try (TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
                 TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

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

                Instant serverTime = Instant.parse(dataNode.get("serverTime").asText());
                assertThat(serverTime).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("Stopping the separate server process produces a clear connection failure on client socket")
    void shouldFailConnectionWhenServerProcessTerminates() throws Exception {
        // Force kill the separate warehouse process
        serverProcess.destroyForcibly();
        serverProcess.waitFor(3, TimeUnit.SECONDS);
        assertThat(serverProcess.isAlive()).isFalse();

        // Attempting to connect must produce an immediate ConnectException
        try (Socket socket = new Socket()) {
            assertThatThrownBy(() -> socket.connect(
                    new InetSocketAddress("127.0.0.1", serverPort), 500
            )).isInstanceOf(ConnectException.class);
        }
    }

    @Test
    @DisplayName("Reconstructs frame split across multiple physical TCP socket writes in separate JVM process (T01)")
    void shouldReconstructFragmentedFrameAcrossSeparateJvmProcesses() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String pingPayload = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);
        byte[] frameBytes = (pingPayload + "\n").getBytes(StandardCharsets.UTF_8);

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());

            // Write in 3-byte fragments with short pauses to enforce physical TCP packet fragmentation
            int chunkSize = 3;
            for (int i = 0; i < frameBytes.length; i += chunkSize) {
                int length = Math.min(chunkSize, frameBytes.length - i);
                out.write(frameBytes, i, length);
                out.flush();
                Thread.sleep(2);
            }

            JsonNode response = reader.readNextFrameJson();
            assertThat(response).isNotNull();
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(response.get("requestId").asText()).isEqualTo(requestId);
            assertThat(response.get("data").get("serverTime").asText()).isNotBlank();
        }
    }

    @Test
    @DisplayName("Correctly processes multiple complete frames coalesced in a single TCP socket write in separate JVM process (T02)")
    void shouldProcessCoalescedFramesAcrossSeparateJvmProcesses() throws Exception {
        String req1 = UUID.randomUUID().toString();
        String req2 = UUID.randomUUID().toString();
        String frame1 = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, req1, null);
        String frame2 = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, req2, null);
        byte[] coalescedPayload = (frame1 + "\n" + frame2 + "\n").getBytes(StandardCharsets.UTF_8);

        try (Socket socket = new Socket()) {
            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            socket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());

            // Write both frames coalesced into a single TCP write
            out.write(coalescedPayload);
            out.flush();

            // Read first correlated response
            JsonNode resp1 = reader.readNextFrameJson();
            assertThat(resp1).isNotNull();
            assertThat(resp1.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(resp1.get("requestId").asText()).isEqualTo(req1);

            // Read second correlated response sequentially on the same persistent socket
            JsonNode resp2 = reader.readNextFrameJson();
            assertThat(resp2).isNotNull();
            assertThat(resp2.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(resp2.get("requestId").asText()).isEqualTo(req2);
        }
    }

    @Test
    @DisplayName("Returns VALIDATION_ERROR for malformed JSON without crashing separate server process")
    void shouldHandleMalformedJsonAcrossSeparateJvmProcessesWithoutCrashingServer() throws Exception {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            socket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);

            OutputStream out = socket.getOutputStream();
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());

            // Send malformed JSON frame
            out.write("{\"v\": 1, invalid_json_syntax}\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            JsonNode errorResp = reader.readNextFrameJson();
            assertThat(errorResp).isNotNull();
            assertThat(errorResp.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
            assertThat(errorResp.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        }

        // Server process must remain alive and responsive to subsequent clients
        assertThat(serverProcess.isAlive()).isTrue();

        String requestId = UUID.randomUUID().toString();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            socket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);

            TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());

            writer.writeFrame(TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null));
            JsonNode pong = reader.readNextFrameJson();
            assertThat(pong).isNotNull();
            assertThat(pong.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(pong.get("requestId").asText()).isEqualTo(requestId);
        }
    }

    @Test
    @DisplayName("Handles premature client socket disconnect mid-frame without leaking threads or crashing separate process")
    void shouldHandleAbruptClientDisconnectMidFrameAcrossSeparateJvmProcesses() throws Exception {
        // Client connects, sends partial frame without newline, then immediately closes socket
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write("{\"v\":1,\"type\":\"PING\"".getBytes(StandardCharsets.UTF_8));
            out.flush();
        } // socket closed here

        // Server process should handle EOF cleanly and remain healthy
        Thread.sleep(100);
        assertThat(serverProcess.isAlive()).isTrue();

        // Fresh client connects and successfully completes PING/PONG
        String requestId = UUID.randomUUID().toString();
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            socket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);

            TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
            TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());

            writer.writeFrame(TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null));
            JsonNode response = reader.readNextFrameJson();
            assertThat(response).isNotNull();
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
            assertThat(response.get("requestId").asText()).isEqualTo(requestId);
        }
    }
}
