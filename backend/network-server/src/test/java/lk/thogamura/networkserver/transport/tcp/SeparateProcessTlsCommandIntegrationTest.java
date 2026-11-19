package lk.thogamura.networkserver.transport.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.networkserver.security.TlsTestCertificateHelper;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end integration test proving real multi-process JVM networking separation over JSSE TLS:
 *
 * <pre>
 *   [Test Client JVM (PID A)] ---- Real OS TLS Socket (Port 7443) ----> [Warehouse Server JVM (PID B)]
 * </pre>
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The warehouse server runs as an independent OS process with its own PID</li>
 *   <li>The server socket enforces validated JSSE TLS using PKCS#12 keystore</li>
 *   <li>No direct in-memory Java method call replaces the network boundary</li>
 *   <li>Real TLS sockets exchange PING and correlated PONG messages</li>
 *   <li>Plaintext client is strictly rejected by the separate server process without fallback</li>
 *   <li>Killing the server process produces an immediate, clear connection failure</li>
 * </ul>
 * </p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class SeparateProcessTlsCommandIntegrationTest {

    private static TlsTestCertificateHelper.CertBundle bundle;

    private Process serverProcess;
    private int serverPort;
    private long serverPid;

    @BeforeAll
    static void generateCerts(@TempDir Path tempDir) throws Exception {
        bundle = TlsTestCertificateHelper.generateValidBundle(tempDir);
    }

    @BeforeEach
    void startServerInSeparateJvm() throws Exception {
        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", classpath,
                TcpCommandServer.class.getName(),
                "0",                                                 // Dynamic OS port
                "127.0.0.1",                                         // Loopback interface
                bundle.serverKeystore().toAbsolutePath().toString()  // Keystore path
        );
        pb.environment().put(
                "THOGAMURA_TLS_KEYSTORE_PASSWORD",
                new String(bundle.serverPassword())
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
        long deadline = System.currentTimeMillis() + 8000;
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

    private SSLSocket createTlsClientSocket() throws Exception {
        KeyStore truststore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(bundle.gatewayTruststore().toFile())) {
            truststore.load(in, bundle.trustPassword());
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(truststore);

        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, tmf.getTrustManagers(), new SecureRandom());

        SSLSocket socket = (SSLSocket) clientCtx.getSocketFactory().createSocket();
        socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
        SSLParameters params = socket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(params);
        socket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);
        socket.startHandshake();
        return socket;
    }

    @Test
    @DisplayName("Proves separate JVM process separation and exchanges PING/PONG over validated TLS")
    void shouldExchangeCommandsAcrossSeparateJvmProcessesOverTls() throws Exception {
        long currentPid = ProcessHandle.current().pid();

        // 1. Verify separate operating system processes
        assertThat(serverPid)
                .as("Server must run in an independent JVM process with a distinct PID")
                .isNotEqualTo(currentPid);

        // 2. Transmit PING over real TLS socket to the child JVM
        String requestId = UUID.randomUUID().toString();
        String pingPayload = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);

        try (SSLSocket socket = createTlsClientSocket()) {
            assertThat(socket.getSession().isValid()).isTrue();
            assertThat(socket.getSession().getProtocol()).isIn("TLSv1.2", "TLSv1.3");

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
    @DisplayName("Plaintext socket connecting to separate TLS server process is rejected without fallback")
    void shouldRejectPlaintextConnectionToSeparateTlsProcess() throws Exception {
        try (Socket plainSocket = new Socket()) {
            plainSocket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);
            plainSocket.setSoTimeout(3000);

            OutputStream out = plainSocket.getOutputStream();
            out.write("{\"v\":1,\"type\":\"PING\",\"requestId\":\"5a3e07fd-158b-4d77-9b97-51844197ed9c\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = plainSocket.getInputStream();
            byte[] responseBytes = in.readAllBytes();

            // Must NOT receive plaintext response
            if (responseBytes.length > 0) {
                assertThat(responseBytes[0])
                        .as("Response must be a TLS alert (0x15), NEVER unencrypted plaintext JSON ('{')")
                        .isEqualTo((byte) 21);
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
}
