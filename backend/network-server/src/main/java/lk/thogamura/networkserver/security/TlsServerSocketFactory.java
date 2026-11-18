package lk.thogamura.networkserver.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ServerSocketFactory;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Custom {@link ServerSocketFactory} providing validated JSSE {@link SSLServerSocket} instances
 * for the central warehouse command listener.
 *
 * <p>Enforces the following security rules:
 * <ul>
 *   <li>Loads server certificate chain and private key from standard PKCS#12 keystore.</li>
 *   <li>Configures enabled TLS protocols (TLS 1.3 / TLS 1.2).</li>
 *   <li>Strictly fails closed when keystore is missing, unreadable, or invalid.</li>
 *   <li>Strictly prohibits plaintext fallback on the command port.</li>
 * </ul>
 * </p>
 */
public class TlsServerSocketFactory extends ServerSocketFactory {

    private static final Logger logger = LoggerFactory.getLogger(TlsServerSocketFactory.class);

    private final SSLServerSocketFactory delegate;
    private final String[] enabledProtocols;

    public TlsServerSocketFactory(SSLServerSocketFactory delegate, String[] enabledProtocols) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.enabledProtocols = (enabledProtocols != null && enabledProtocols.length > 0)
                ? enabledProtocols.clone()
                : new String[]{"TLSv1.3", "TLSv1.2"};
    }

    public TlsServerSocketFactory(SSLContext sslContext, String[] enabledProtocols) {
        this(Objects.requireNonNull(sslContext, "sslContext must not be null").getServerSocketFactory(), enabledProtocols);
    }

    /**
     * Creates a {@link TlsServerSocketFactory} from a PKCS#12 keystore file and credentials.
     *
     * @param keystorePath path to the server keystore file
     * @param password keystore password
     * @param keystoreType keystore type (defaults to PKCS12)
     * @param enabledProtocols TLS protocols to enable (e.g. TLSv1.3, TLSv1.2)
     * @return configured TLS server socket factory
     * @throws GeneralSecurityException if keystore decryption or SSLContext initialization fails
     * @throws IOException if keystore file is missing or unreadable
     */
    public static TlsServerSocketFactory fromKeystore(
            Path keystorePath,
            char[] password,
            String keystoreType,
            String[] enabledProtocols) throws GeneralSecurityException, IOException {

        Objects.requireNonNull(keystorePath, "keystorePath must not be null");
        if (!Files.exists(keystorePath)) {
            throw new FileNotFoundException("Server keystore file not found: " + keystorePath.toAbsolutePath());
        }

        String effectiveType = (keystoreType != null && !keystoreType.isBlank()) ? keystoreType : "PKCS12";
        char[] effectivePassword = (password != null) ? password : new char[0];

        KeyStore keystore = KeyStore.getInstance(effectiveType);
        try (InputStream in = new FileInputStream(keystorePath.toFile())) {
            keystore.load(in, effectivePassword);
        }

        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keystore, effectivePassword);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(kmf.getKeyManagers(), null, new SecureRandom());

        String[] protocols = (enabledProtocols != null && enabledProtocols.length > 0)
                ? enabledProtocols
                : new String[]{"TLSv1.3", "TLSv1.2"};

        logger.info("Initialized TLS server socket factory with keystore '{}' (type: {}) and protocols: {}",
                keystorePath, effectiveType, Arrays.toString(protocols));

        return new TlsServerSocketFactory(sslContext, protocols);
    }

    private ServerSocket configure(ServerSocket socket) {
        if (socket instanceof SSLServerSocket sslSocket) {
            if (enabledProtocols != null && enabledProtocols.length > 0) {
                sslSocket.setEnabledProtocols(enabledProtocols);
            }
        }
        return socket;
    }

    @Override
    public ServerSocket createServerSocket() throws IOException {
        return configure(delegate.createServerSocket());
    }

    @Override
    public ServerSocket createServerSocket(int port) throws IOException {
        return configure(delegate.createServerSocket(port));
    }

    @Override
    public ServerSocket createServerSocket(int port, int backlog) throws IOException {
        return configure(delegate.createServerSocket(port, backlog));
    }

    @Override
    public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException {
        return configure(delegate.createServerSocket(port, backlog, ifAddress));
    }

    public String[] getEnabledProtocols() {
        return enabledProtocols.clone();
    }
}
