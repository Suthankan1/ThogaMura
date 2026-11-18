package lk.thogamura.networkserver.security;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.networkserver.transport.tcp.DefaultCommandDispatcher;
import lk.thogamura.networkserver.transport.tcp.TcpCommandServer;
import lk.thogamura.networkserver.transport.tcp.TcpCommandServerConfig;
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
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
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
 * Integration test for {@link TcpCommandServer} running with {@link TlsServerSocketFactory}.
 *
 * <p>Proves that:
 * <ul>
 *   <li>The server socket enforces validated JSSE TLS encryption.</li>
 *   <li>Valid TLS client with trusted CA successfully negotiates TLS and exchanges framed commands.</li>
 *   <li>Plaintext client is rejected without sending any plaintext response (no plaintext fallback).</li>
 *   <li>Client with untrusted CA fails the TLS handshake.</li>
 *   <li>Server cleanly shuts down active TLS connections.</li>
 * </ul>
 * </p>
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TlsTcpCommandServerIntegrationTest {

    private static TlsTestCertificateHelper.CertBundle validBundle;
    private static Path untrustedKeystore;

    private TcpCommandServer server;
    private int serverPort;

    @BeforeAll
    static void generateCerts(@TempDir Path tempDir) throws Exception {
        validBundle = TlsTestCertificateHelper.generateValidBundle(tempDir);
        untrustedKeystore = TlsTestCertificateHelper.generateUntrustedServerKeystore(tempDir);
    }

    @BeforeEach
    void startTlsServer() throws Exception {
        TlsServerSocketFactory socketFactory = TlsServerSocketFactory.fromKeystore(
                validBundle.serverKeystore(),
                validBundle.serverPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"}
        );

        TcpCommandServerConfig config = new TcpCommandServerConfig(
                "127.0.0.1",
                0, // dynamic port
                50,
                ProtocolInfo.DEFAULT_READ_TIMEOUT_MS,
                50,
                ProtocolInfo.MAX_FRAME_BYTES,
                false,
                true // tlsEnabled
        );

        server = new TcpCommandServer(config, new DefaultCommandDispatcher(), socketFactory);
        server.start();
        serverPort = server.getPort();
        assertThat(serverPort).isGreaterThan(0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    private SSLSocket createTlsClientSocket(Path truststorePath, char[] password, String host, int port) throws Exception {
        KeyStore truststore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(truststorePath.toFile())) {
            truststore.load(in, password);
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
        socket.connect(new InetSocketAddress(host, port), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);
        socket.startHandshake();
        return socket;
    }

    @Test
    @DisplayName("Trusted TLS client successfully exchanges PING/PONG with ISO-8601 serverTime")
    void testSuccessfulTlsPingPongExchange() throws Exception {
        try (SSLSocket socket = createTlsClientSocket(
                validBundle.gatewayTruststore(), validBundle.trustPassword(), "localhost", serverPort)) {

            assertThat(socket.getSession().isValid()).isTrue();
            assertThat(socket.getSession().getProtocol()).isIn("TLSv1.2", "TLSv1.3");

            try (TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
                 TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

                String requestId = UUID.randomUUID().toString();
                String requestJson = TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null);
                writer.writeFrame(requestJson);

                JsonNode responseNode = reader.readNextFrameJson();
                assertThat(responseNode).isNotNull();
                assertThat(responseNode.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
                assertThat(responseNode.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
                assertThat(responseNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
                assertThat(responseNode.get("requestId").asText()).isEqualTo(requestId);

                JsonNode dataNode = responseNode.get("data");
                assertThat(dataNode).isNotNull();
                assertThat(dataNode.hasNonNull("serverTime")).isTrue();
                Instant serverTime = Instant.parse(dataNode.get("serverTime").asText());
                assertThat(serverTime).isNotNull();
            }
        }
    }

    @Test
    @DisplayName("Multiple sequential commands succeed over single persistent TLS connection")
    void testMultipleCommandsOverSingleTlsConnection() throws Exception {
        try (SSLSocket socket = createTlsClientSocket(
                validBundle.gatewayTruststore(), validBundle.trustPassword(), "127.0.0.1", serverPort)) {

            try (TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
                 TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

                for (int i = 0; i < 5; i++) {
                    String requestId = UUID.randomUUID().toString();
                    writer.writeFrame(TcpMessageCodec.formatRequest(ProtocolInfo.COMMAND_PING, requestId, null));

                    JsonNode response = reader.readNextFrameJson();
                    assertThat(response.get("requestId").asText()).isEqualTo(requestId);
                    assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PONG);
                }
            }
        }
    }

    @Test
    @DisplayName("Plaintext client is rejected without sending any plaintext response (no plaintext fallback)")
    void testPlaintextClientRejectedWithoutPlaintextFallback() throws Exception {
        try (Socket plainSocket = new Socket()) {
            plainSocket.connect(new InetSocketAddress("127.0.0.1", serverPort), ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS);
            plainSocket.setSoTimeout(3000);

            OutputStream out = plainSocket.getOutputStream();
            String request = "{\"v\":1,\"type\":\"PING\",\"requestId\":\"5a3e07fd-158b-4d77-9b97-51844197ed9c\"}\n";
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = plainSocket.getInputStream();
            byte[] responseBytes = in.readAllBytes();

            // The server must terminate the connection without returning any unencrypted plaintext JSON.
            // If any bytes were transmitted by JSSE before closing, it must be a TLS alert (ContentType 21 / 0x15).
            if (responseBytes.length > 0) {
                assertThat(responseBytes[0])
                        .as("Response must be a TLS alert (0x15), NEVER unencrypted plaintext JSON ('{')")
                        .isEqualTo((byte) 21);
            }
        }
    }

    @Test
    @DisplayName("Client fails closed when connecting with untrusted truststore")
    void testClientWithUntrustedTruststoreFailsHandshake(@TempDir Path tempDir) throws Exception {
        // Generate an independent rogue bundle
        TlsTestCertificateHelper.CertBundle rogueBundle = TlsTestCertificateHelper.generateValidBundle(tempDir);

        // Attempt to connect to our server using rogue CA truststore
        assertThatThrownBy(() -> {
            try (SSLSocket s = createTlsClientSocket(
                    rogueBundle.gatewayTruststore(), rogueBundle.trustPassword(), "localhost", serverPort)) {
                s.getOutputStream().write(1);
            }
        }).as("Connecting with untrusted truststore must fail with SSLHandshakeException")
          .isInstanceOf(SSLHandshakeException.class)
          .hasMessageContaining("PKIX path");
    }

    @Test
    @DisplayName("Stopping TLS server closes open client connections cleanly")
    void testStoppingServerClosesConnections() throws Exception {
        SSLSocket socket = createTlsClientSocket(
                validBundle.gatewayTruststore(), validBundle.trustPassword(), "localhost", serverPort);

        assertThat(socket.isConnected()).isTrue();
        server.stop();
        assertThat(server.isRunning()).isFalse();

        // Reading from socket after server stop should yield EOF (-1) or IOException
        try {
            int read = socket.getInputStream().read();
            assertThat(read).isEqualTo(-1);
        } catch (Exception ignored) {
            // Socket closed / reset is also valid
        } finally {
            socket.close();
        }
    }
}
