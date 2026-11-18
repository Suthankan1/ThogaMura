package lk.thogamura.networkserver.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Member 5 (Issue #5): Verification of warehouse server TLS certificate and keystore setup.
 * Proves that generated server certificates conform to ADR-004, contain the required SANs,
 * provide a complete trust chain, and fail closed without plaintext fallback.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class TlsCertificateSetupTest {

    private static TlsTestCertificateHelper.CertBundle bundle;

    @BeforeAll
    static void generateTestCertificates(@TempDir Path tempDir) throws Exception {
        bundle = TlsTestCertificateHelper.generateValidBundle(tempDir);
    }

    @Test
    @DisplayName("Server keystore loads successfully and contains a 2-certificate trust chain")
    void testValidServerKeystoreLoadsAndContainsCompleteChain() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(bundle.serverKeystore().toFile())) {
            keystore.load(in, bundle.serverPassword());
        }

        assertThat(keystore.containsAlias("thogamura-warehouse"))
                .as("Server keystore must contain alias 'thogamura-warehouse'")
                .isTrue();
        assertThat(keystore.isKeyEntry("thogamura-warehouse"))
                .as("Warehouse entry must be a private key entry")
                .isTrue();

        Certificate[] chain = keystore.getCertificateChain("thogamura-warehouse");
        assertThat(chain)
                .as("Server certificate chain must contain leaf and Root CA (length 2)")
                .hasSize(2);

        X509Certificate leafCert = (X509Certificate) chain[0];
        X509Certificate caCert = (X509Certificate) chain[1];

        assertThat(leafCert.getIssuerX500Principal())
                .as("Leaf certificate must be issued by the Dev Root CA")
                .isEqualTo(caCert.getSubjectX500Principal());

        assertThat(caCert.getSubjectX500Principal().getName())
                .as("Root CA subject DN must match synthetic demonstration identity")
                .contains("ThogaMura Dev Root CA")
                .contains("O=ThogaMura Coursework");

        // Verify leaf certificate is signed by Root CA public key
        leafCert.verify(caCert.getPublicKey());
    }

    @Test
    @DisplayName("Server certificate strictly includes required Subject Alternative Names (SAN)")
    void testServerCertificateContainsRequiredSubjectAlternativeNames() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(bundle.serverKeystore().toFile())) {
            keystore.load(in, bundle.serverPassword());
        }

        X509Certificate leafCert = (X509Certificate) keystore.getCertificate("thogamura-warehouse");
        Collection<List<?>> sanEntries = leafCert.getSubjectAlternativeNames();

        assertThat(sanEntries)
                .as("Subject Alternative Names extension must be present on server certificate")
                .isNotNull()
                .isNotEmpty();

        boolean hasDnsLocalhost = false;
        boolean hasIpLoopback = false;

        for (List<?> entry : sanEntries) {
            Integer type = (Integer) entry.get(0);
            Object value = entry.get(1);

            // Type 2 = DNSName, Type 7 = IPAddress
            if (type == 2 && "localhost".equalsIgnoreCase(String.valueOf(value))) {
                hasDnsLocalhost = true;
            }
            if (type == 7 && "127.0.0.1".equals(String.valueOf(value))) {
                hasIpLoopback = true;
            }
        }

        assertThat(hasDnsLocalhost)
                .as("SAN must contain DNS:localhost for local development")
                .isTrue();
        assertThat(hasIpLoopback)
                .as("SAN must contain IP:127.0.0.1 for loopback IP binding")
                .isTrue();

        // Check key usage: digitalSignature (bit 0), keyEncipherment (bit 2)
        boolean[] keyUsage = leafCert.getKeyUsage();
        assertThat(keyUsage)
                .as("Server certificate must specify key usage")
                .isNotNull();
        assertThat(keyUsage[0]).as("digitalSignature must be enabled").isTrue();
        assertThat(keyUsage[2]).as("keyEncipherment must be enabled").isTrue();

        // Extended Key Usage must include serverAuth (OID: 1.3.6.1.5.5.7.3.1)
        List<String> extendedKeyUsage = leafCert.getExtendedKeyUsage();
        assertThat(extendedKeyUsage)
                .as("Extended Key Usage must contain serverAuth OID")
                .contains("1.3.6.1.5.5.7.3.1");
    }

    @Test
    @DisplayName("Server keystore fails closed when loaded with an incorrect password")
    void testServerKeystoreRejectsWrongPassword() throws Exception {
        KeyStore keystore = KeyStore.getInstance("PKCS12");
        char[] wrongPassword = "wrong-password-attempt".toCharArray();

        assertThatThrownBy(() -> {
            try (InputStream in = new FileInputStream(bundle.serverKeystore().toFile())) {
                keystore.load(in, wrongPassword);
            }
        }).as("Loading PKCS12 keystore with wrong password must throw IOException")
          .isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("Server TLS listener strictly rejects plaintext connection without fallback")
    void testServerRejectsPlaintextConnectionWithoutFallback() throws Exception {
        KeyStore serverKs = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(bundle.serverKeystore().toFile())) {
            serverKs.load(in, bundle.serverPassword());
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverKs, bundle.serverPassword());

        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), null, null);

        SSLServerSocket serverSocket = (SSLServerSocket) serverCtx.getServerSocketFactory().createServerSocket(0);
        int port = serverSocket.getLocalPort();

        AtomicBoolean serverCaughtTlsError = new AtomicBoolean(false);
        AtomicReference<String> errorReason = new AtomicReference<>("");
        CountDownLatch serverDone = new CountDownLatch(1);

        Thread serverThread = new Thread(() -> {
            try (SSLSocket socket = (SSLSocket) serverSocket.accept()) {
                socket.startHandshake();
            } catch (SSLException sslEx) {
                // Expected failure: non-TLS plaintext byte stream sent to SSLServerSocket
                serverCaughtTlsError.set(true);
                errorReason.set(sslEx.getMessage());
            } catch (Exception e) {
                errorReason.set(e.getMessage());
            } finally {
                serverDone.countDown();
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // Plaintext client attempt: connects using standard java.net.Socket (NO TLS)
        try (Socket plainSocket = new Socket("127.0.0.1", port)) {
            OutputStream out = plainSocket.getOutputStream();
            out.write("{\"v\":1,\"type\":\"PING\",\"requestId\":\"5a3e07fd-158b-4d77-9b97-51844197ed9c\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = plainSocket.getInputStream();
            byte[] responseBytes = in.readAllBytes();
            // Server must terminate connection without sending any plaintext command response.
            // If the TLS layer emits bytes before closing, it sends a TLS alert record (ContentType 21 / 0x15),
            // NEVER an unencrypted plaintext JSON frame (which begins with '{' / ASCII 123 / 0x7B).
            if (responseBytes.length > 0) {
                assertThat(responseBytes[0])
                        .as("Server response must be a TLS alert record (0x15), never unencrypted plaintext JSON")
                        .isEqualTo((byte) 21);
            }
        }

        serverDone.await(5, TimeUnit.SECONDS);
        serverSocket.close();

        assertThat(serverCaughtTlsError.get())
                .as("SSLServerSocket must throw SSLException when receiving non-TLS frame")
                .isTrue();
        assertThat(errorReason.get())
                .as("Error reason must indicate unrecognized SSL message")
                .containsIgnoringCase("SSL");
    }
}
