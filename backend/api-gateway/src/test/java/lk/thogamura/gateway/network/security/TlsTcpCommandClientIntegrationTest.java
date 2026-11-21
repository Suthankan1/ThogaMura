package lk.thogamura.gateway.network.security;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.gateway.network.tcp.CommandCorrelationException;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.gateway.network.tcp.TcpCommandClientConfig;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration tests for {@link TcpCommandClient} using {@link TlsSocketFactory}.
 *
 * <p>Proves that:
 * <ul>
 *   <li>The client initiates and validates server-authenticated JSSE TLS.</li>
 *   <li>Standard PING/PONG and framed commands succeed with valid Root CA truststore.</li>
 *   <li>Strict RFC 2818 endpoint identification rejects mismatched SANs.</li>
 *   <li>Untrusted rogue CA certificates are rejected (fail closed).</li>
 *   <li>Plaintext servers cannot talk to TLS clients (no plaintext fallback).</li>
 *   <li>Missing or invalid truststores fail safely during initialization.</li>
 * </ul>
 * </p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TlsTcpCommandClientIntegrationTest {

    private static TlsTestCertificateHelper.CertBundle validBundle;
    private static Path untrustedServerKeystore;
    private static Path mismatchedSanServerKeystore;

    private final List<ServerSocket> openServerSockets = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @BeforeAll
    static void generateCerts(@TempDir Path tempDir) throws Exception {
        validBundle = TlsTestCertificateHelper.generateValidBundle(tempDir);
        untrustedServerKeystore = TlsTestCertificateHelper.generateUntrustedServerKeystore(tempDir);
        mismatchedSanServerKeystore = TlsTestCertificateHelper.generateMismatchedSanServerKeystore(
                tempDir, validBundle.caKeystore(), validBundle.caCert());
    }

    @AfterEach
    void tearDown() {
        for (ServerSocket ss : openServerSockets) {
            try {
                ss.close();
            } catch (Exception ignored) {}
        }
        openServerSockets.clear();
        executor.shutdownNow();
    }

    private SSLServerSocket startTlsEchoServer(Path keystorePath, char[] password) throws Exception {
        KeyStore serverKs = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(keystorePath.toFile())) {
            serverKs.load(in, password);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverKs, password);

        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);

        SSLServerSocket serverSocket = (SSLServerSocket) serverCtx.getServerSocketFactory().createServerSocket(0);
        openServerSockets.add(serverSocket);

        executor.submit(() -> {
            while (!serverSocket.isClosed()) {
                try {
                    SSLSocket socket = (SSLSocket) serverSocket.accept();
                    executor.submit(() -> {
                        try (socket;
                             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream());
                             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream())) {

                            while (!socket.isClosed()) {
                                String frame = reader.readNextFrameString();
                                if (frame == null) break;

                                JsonNode req = TcpMessageCodec.parseJsonObject(frame);
                                String reqId = req.get("requestId").asText();
                                String type = req.get("type").asText();

                                if (ProtocolInfo.COMMAND_PING.equals(type)) {
                                    writer.writeFrame(TcpMessageCodec.formatResultResponse(
                                            reqId, ProtocolInfo.STATUS_PONG, java.util.Map.of("serverTime", Instant.now().toString())));
                                } else {
                                    writer.writeFrame("{\"v\":1,\"type\":\"RESULT\",\"status\":\"ACCEPTED\",\"requestId\":\"" + reqId + "\"}");
                                }
                            }
                        } catch (Exception ignored) {
                            // Expected on client disconnect or handshake failure
                        }
                    });
                } catch (Exception ignored) {
                    break;
                }
            }
        });

        return serverSocket;
    }

    @Test
    @DisplayName("Gateway client successfully executes PING over TLS with trusted server certificate")
    void testSuccessfulTlsPing() throws Exception {
        SSLServerSocket server = startTlsEchoServer(validBundle.serverKeystore(), validBundle.serverPassword());
        int port = server.getLocalPort();

        TlsSocketFactory socketFactory = TlsSocketFactory.fromTruststore(
                validBundle.gatewayTruststore(),
                validBundle.trustPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"},
                true
        );

        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "localhost",
                port,
                ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS,
                ProtocolInfo.DEFAULT_READ_TIMEOUT_MS,
                ProtocolInfo.MAX_FRAME_BYTES
        );

        TcpCommandClient client = new TcpCommandClient(config, socketFactory);
        JsonNode pong = client.ping();

        assertThat(pong).isNotNull();
        assertThat(pong.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
        assertThat(pong.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
        assertThat(pong.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
        assertThat(pong.hasNonNull("requestId")).isTrue();

        JsonNode data = pong.get("data");
        assertThat(data).isNotNull();
        assertThat(data.hasNonNull("serverTime")).isTrue();
    }

    @Test
    @DisplayName("Gateway client successfully sends framed business commands over TLS")
    void testSuccessfulBusinessCommandOverTls() throws Exception {
        SSLServerSocket server = startTlsEchoServer(validBundle.serverKeystore(), validBundle.serverPassword());
        int port = server.getLocalPort();

        TlsSocketFactory socketFactory = TlsSocketFactory.fromTruststore(
                validBundle.gatewayTruststore(),
                validBundle.trustPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"},
                true
        );

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port);
        TcpCommandClient client = new TcpCommandClient(config, socketFactory);

        String requestId = UUID.randomUUID().toString();
        JsonNode response = client.sendCommand("REQUEST_STOCK", requestId, null);

        assertThat(response).isNotNull();
        assertThat(response.get("requestId").asText()).isEqualTo(requestId);
        assertThat(response.get("status").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("T12: Handshake succeeds when server certificate matches configured LAN IP/DNS in SAN")
    void testLanSanMatchingSucceeds(@TempDir Path tempDir) throws Exception {
        // Generate bundle configured with demonstration LAN IP and custom warehouse DNS name
        TlsTestCertificateHelper.CertBundle lanBundle = TlsTestCertificateHelper.generateValidBundle(
                tempDir, "ip:192.168.8.100", "dns:warehouse.thogamura.internal");

        SSLServerSocket server = startTlsEchoServer(lanBundle.serverKeystore(), lanBundle.serverPassword());
        int port = server.getLocalPort();

        TlsSocketFactory socketFactory = TlsSocketFactory.fromTruststore(
                lanBundle.gatewayTruststore(),
                lanBundle.trustPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"},
                true
        );

        // Connect using loopback IP (which is also present in SAN alongside LAN IP and DNS)
        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port);
        TcpCommandClient client = new TcpCommandClient(config, socketFactory);

        JsonNode pong = client.ping();
        assertThat(pong).isNotNull();
        assertThat(pong.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
    }

    @Test
    @DisplayName("Gateway client rejects server certificate signed by untrusted rogue CA (fail-closed)")
    void testUntrustedServerCertificateRejected() throws Exception {
        SSLServerSocket server = startTlsEchoServer(untrustedServerKeystore, TlsTestCertificateHelper.TEST_PASSWORD);
        int port = server.getLocalPort();

        TlsSocketFactory socketFactory = TlsSocketFactory.fromTruststore(
                validBundle.gatewayTruststore(),
                validBundle.trustPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"},
                true
        );

        TcpCommandClientConfig config = new TcpCommandClientConfig("localhost", port);
        TcpCommandClient client = new TcpCommandClient(config, socketFactory);

        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTransportException.class)
                .hasCauseInstanceOf(SSLHandshakeException.class)
                .hasMessageContaining("PKIX path");
    }

    @Test
    @DisplayName("Gateway client rejects server certificate when hostname does not match SAN")
    void testHostnameMismatchRejectedBySanVerification() throws Exception {
        SSLServerSocket server = startTlsEchoServer(
                mismatchedSanServerKeystore, TlsTestCertificateHelper.TEST_PASSWORD);
        int port = server.getLocalPort();

        TlsSocketFactory socketFactory = TlsSocketFactory.fromTruststore(
                validBundle.gatewayTruststore(),
                validBundle.trustPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"},
                true
        );

        // Connect to 127.0.0.1 which is not present in mismatchedSanServerKeystore SAN
        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", port);
        TcpCommandClient client = new TcpCommandClient(config, socketFactory);

        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTransportException.class)
                .hasCauseInstanceOf(SSLHandshakeException.class)
                .hasMessageContaining("No subject alternative names matching IP address 127.0.0.1 found");
    }

    @Test
    @DisplayName("Missing truststore fails safely during factory creation (fail-closed)")
    void testMissingTruststoreFailsClosed() {
        Path missing = Path.of("missing-truststore.p12");

        assertThatThrownBy(() -> TlsSocketFactory.fromTruststore(
                missing, "pass".toCharArray(), "PKCS12", null, true))
                .isInstanceOf(FileNotFoundException.class)
                .hasMessageContaining("Gateway truststore file not found");
    }

    @Test
    @DisplayName("Wrong truststore password fails safely during factory creation")
    void testWrongTruststorePasswordFailsClosed() {
        assertThatThrownBy(() -> TlsSocketFactory.fromTruststore(
                validBundle.gatewayTruststore(),
                "wrong-password".toCharArray(),
                "PKCS12",
                null,
                true))
                .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("TLS client fails handshake when connecting to plaintext server (no plaintext fallback)")
    void testTlsClientFailsWhenServerIsPlaintext() throws Exception {
        // Start plaintext server
        ServerSocket plainServer = new ServerSocket(0);
        openServerSockets.add(plainServer);

        executor.submit(() -> {
            try (Socket socket = plainServer.accept()) {
                // Read whatever bytes client sends, or write plaintext JSON
                socket.getOutputStream().write("{\"v\":1,\"type\":\"PONG\"}\n".getBytes());
                socket.getOutputStream().flush();
            } catch (Exception ignored) {}
        });

        TlsSocketFactory socketFactory = TlsSocketFactory.fromTruststore(
                validBundle.gatewayTruststore(),
                validBundle.trustPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"},
                true
        );

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", plainServer.getLocalPort());
        TcpCommandClient client = new TcpCommandClient(config, socketFactory);

        assertThatThrownBy(client::ping)
                .isInstanceOf(CommandTransportException.class)
                .hasMessageContaining("TLS handshake failed");
    }

    @Test
    @DisplayName("Gateway client throws ServerUnavailableException when target warehouse is offline")
    void testServerOfflineThrowsServerUnavailableException() throws Exception {
        // Allocate and immediately close a port
        ServerSocket temp = new ServerSocket(0);
        int closedPort = temp.getLocalPort();
        temp.close();

        TlsSocketFactory socketFactory = TlsSocketFactory.fromTruststore(
                validBundle.gatewayTruststore(),
                validBundle.trustPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"},
                true
        );

        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", closedPort, 500, 500, 65536);
        TcpCommandClient client = new TcpCommandClient(config, socketFactory);

        assertThatThrownBy(client::ping)
                .isInstanceOf(ServerUnavailableException.class);
    }

    @Test
    @DisplayName("NetworkStatusController derives truthful OPERATIONAL status over live JSSE TLS connection")
    void testNetworkStatusControllerReportsOperationalOverRealTls() throws Exception {
        SSLServerSocket server = startTlsEchoServer(validBundle.serverKeystore(), validBundle.serverPassword());
        int port = server.getLocalPort();

        TlsSocketFactory socketFactory = TlsSocketFactory.fromTruststore(
                validBundle.gatewayTruststore(),
                validBundle.trustPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"},
                true
        );

        TcpCommandClientConfig config = new TcpCommandClientConfig(
                "localhost",
                port,
                ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS,
                ProtocolInfo.DEFAULT_READ_TIMEOUT_MS,
                ProtocolInfo.MAX_FRAME_BYTES
        );
        TcpCommandClient client = new TcpCommandClient(config, socketFactory);

        TlsClientProperties properties = new TlsClientProperties();
        properties.setEnabled(true);
        properties.setTruststorePath(validBundle.gatewayTruststore().toString());
        properties.setHostnameVerification(true);

        lk.thogamura.gateway.sse.SseNotificationBroadcaster mockSse = org.mockito.Mockito.mock(lk.thogamura.gateway.sse.SseNotificationBroadcaster.class);
        lk.thogamura.gateway.web.NetworkStatusController controller = new lk.thogamura.gateway.web.NetworkStatusController(
                "127.0.0.1",
                7444,
                false,
                null,
                mockSse,
                client,
                properties
        );

        java.util.Map<String, Object> status = controller.getNetworkStatus();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> tlsCommand = (java.util.Map<String, Object>) status.get("tlsCommand");

        assertThat(tlsCommand).isNotNull();
        assertThat(tlsCommand.get("status")).isEqualTo("OPERATIONAL");
        assertThat(tlsCommand.get("owner")).isEqualTo("Member 5");
    }
}
