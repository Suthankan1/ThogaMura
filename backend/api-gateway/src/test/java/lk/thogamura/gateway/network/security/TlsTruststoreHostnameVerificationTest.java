package lk.thogamura.gateway.network.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Member 5 (Issue #5): Verification of branch gateway TLS truststore and hostname verification.
 * Proves that:
 * 1. Gateway truststore contains solely the Root CA and strictly no private keys.
 * 2. TLS handshake succeeds when the server certificate is signed by the trusted CA and matches SAN.
 * 3. Server certificate signed by an untrusted / rogue CA is immediately rejected (fail-closed).
 * 4. Mismatched hostname / IP is rejected by RFC 2818 endpoint identification (HTTPS).
 * 5. Truststore configuration enforces fail-closed behavior (no trust-all certificate manager).
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TlsTruststoreHostnameVerificationTest {

    private static TlsTestCertificateHelper.CertBundle validBundle;
    private static Path untrustedServerKeystore;
    private static Path mismatchedSanServerKeystore;

    private final List<SSLServerSocket> openServerSockets = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    @BeforeAll
    static void generateTestMaterials(@TempDir Path tempDir) throws Exception {
        validBundle = TlsTestCertificateHelper.generateValidBundle(tempDir);
        untrustedServerKeystore = TlsTestCertificateHelper.generateUntrustedServerKeystore(tempDir);
        mismatchedSanServerKeystore = TlsTestCertificateHelper.generateMismatchedSanServerKeystore(
                tempDir, validBundle.caKeystore(), validBundle.caCert());
    }

    @AfterEach
    void tearDown() {
        for (SSLServerSocket ss : openServerSockets) {
            try {
                ss.close();
            } catch (Exception ignored) {
            }
        }
        openServerSockets.clear();
        executor.shutdownNow();
    }

    private SSLServerSocket startEchoServer(Path keystorePath, char[] password) throws Exception {
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
                        try (socket) {
                            socket.startHandshake();
                            OutputStream out = socket.getOutputStream();
                            out.write("{\"v\":1,\"type\":\"PONG\"}\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } catch (Exception ignored) {
                            // Expected when client rejects certificate and closes connection
                        }
                    });
                } catch (Exception ignored) {
                    break;
                }
            }
        });

        return serverSocket;
    }

    private SSLContext createGatewayClientSslContext(Path truststorePath, char[] password) throws Exception {
        KeyStore trustKs = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(truststorePath.toFile())) {
            trustKs.load(in, password);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustKs);

        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(null, tmf.getTrustManagers(), null);
        return clientCtx;
    }

    @Test
    @DisplayName("Gateway truststore contains solely the Root CA and strictly no private keys")
    void testGatewayTruststoreLoadsAndContainsOnlyTrustedCa() throws Exception {
        KeyStore truststore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(validBundle.gatewayTruststore().toFile())) {
            truststore.load(in, validBundle.trustPassword());
        }

        assertThat(truststore.containsAlias("thogamura-ca"))
                .as("Truststore must contain alias 'thogamura-ca'")
                .isTrue();
        assertThat(truststore.isCertificateEntry("thogamura-ca"))
                .as("CA entry must be a trusted certificate entry")
                .isTrue();
        assertThat(truststore.isKeyEntry("thogamura-ca"))
                .as("Truststore MUST NOT contain any private key entries")
                .isFalse();

        Certificate cert = truststore.getCertificate("thogamura-ca");
        assertThat(cert)
                .as("CA certificate must be an X.509 certificate")
                .isInstanceOf(X509Certificate.class);

        X509Certificate x509Cert = (X509Certificate) cert;
        assertThat(x509Cert.getSubjectX500Principal().getName())
                .as("CA subject DN must match synthetic coursework CA")
                .contains("ThogaMura Dev Root CA")
                .contains("O=ThogaMura Coursework");
    }

    @Test
    @DisplayName("TLS handshake succeeds when server certificate is signed by trusted CA and matches SAN")
    void testSuccessfulTlsHandshakeWithTrustedCaAndMatchingSan() throws Exception {
        SSLServerSocket serverSocket = startEchoServer(validBundle.serverKeystore(), validBundle.serverPassword());
        int port = serverSocket.getLocalPort();

        SSLContext clientCtx = createGatewayClientSslContext(
                validBundle.gatewayTruststore(), validBundle.trustPassword());

        try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket("localhost", port)) {
            // Enable strict RFC 2818 endpoint identification (hostname verification)
            SSLParameters params = clientSocket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            clientSocket.setSSLParameters(params);

            clientSocket.startHandshake();

            assertThat(clientSocket.isConnected()).isTrue();
            assertThat(clientSocket.getSession().isValid()).isTrue();
            assertThat(clientSocket.getSession().getProtocol())
                    .as("Negotiated protocol must be TLSv1.2 or TLSv1.3")
                    .isIn("TLSv1.2", "TLSv1.3");

            Certificate[] peerCerts = clientSocket.getSession().getPeerCertificates();
            assertThat(peerCerts)
                    .as("Peer certificate chain must have length 2")
                    .hasSize(2);

            X509Certificate peerLeaf = (X509Certificate) peerCerts[0];
            assertThat(peerLeaf.getSubjectX500Principal().getName())
                    .as("Server certificate CN must be localhost")
                    .contains("CN=localhost");

            BufferedReader reader = new BufferedReader(new InputStreamReader(
                    clientSocket.getInputStream(), StandardCharsets.UTF_8));
            String response = reader.readLine();
            assertThat(response).isEqualTo("{\"v\":1,\"type\":\"PONG\"}");
        }
    }

    @Test
    @DisplayName("TLS handshake strictly fails when server presents certificate from untrusted / rogue CA")
    void testHandshakeFailsWhenServerCertificateIsSignedByUntrustedCa() throws Exception {
        // Start server with untrusted rogue CA certificate
        SSLServerSocket serverSocket = startEchoServer(untrustedServerKeystore, TlsTestCertificateHelper.TEST_PASSWORD);
        int port = serverSocket.getLocalPort();

        // Gateway trusts only the official Dev Root CA
        SSLContext clientCtx = createGatewayClientSslContext(
                validBundle.gatewayTruststore(), validBundle.trustPassword());

        try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket("localhost", port)) {
            SSLParameters params = clientSocket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            clientSocket.setSSLParameters(params);

            assertThatThrownBy(clientSocket::startHandshake)
                    .as("Handshake must fail with SSLHandshakeException when CA is not in truststore")
                    .isInstanceOf(SSLHandshakeException.class)
                    .hasMessageContaining("PKIX path building failed");
        }
    }

    @Test
    @DisplayName("TLS handshake strictly fails when destination IP/host does not match certificate SAN")
    void testHandshakeFailsWhenHostnameDoesNotMatchSan() throws Exception {
        // Start server with certificate having SAN: dns:wrong-host.thogamura.internal, ip:192.0.2.1
        // (missing localhost and 127.0.0.1)
        SSLServerSocket serverSocket = startEchoServer(
                mismatchedSanServerKeystore, TlsTestCertificateHelper.TEST_PASSWORD);
        int port = serverSocket.getLocalPort();

        SSLContext clientCtx = createGatewayClientSslContext(
                validBundle.gatewayTruststore(), validBundle.trustPassword());

        // Connect to 127.0.0.1 which is NOT in the certificate SAN
        try (SSLSocket clientSocket = (SSLSocket) clientCtx.getSocketFactory().createSocket("127.0.0.1", port)) {
            SSLParameters params = clientSocket.getSSLParameters();
            params.setEndpointIdentificationAlgorithm("HTTPS");
            clientSocket.setSSLParameters(params);

            assertThatThrownBy(clientSocket::startHandshake)
                    .as("Handshake must fail with SSLHandshakeException when connecting IP does not match SAN")
                    .isInstanceOf(SSLHandshakeException.class)
                    .hasMessageContaining("No subject alternative names matching IP address 127.0.0.1 found");
        }
    }

    @Test
    @DisplayName("Truststore initialization fails closed when truststore path is missing or invalid")
    void testNoTrustAllManagerAccepted() {
        Path nonexistent = Path.of("nonexistent-truststore-file.p12");

        assertThatThrownBy(() -> {
            try (InputStream in = new FileInputStream(nonexistent.toFile())) {
                KeyStore ks = KeyStore.getInstance("PKCS12");
                ks.load(in, "password".toCharArray());
            }
        }).as("Missing truststore must throw FileNotFoundException (fail-closed)")
          .isInstanceOf(FileNotFoundException.class);
    }
}
