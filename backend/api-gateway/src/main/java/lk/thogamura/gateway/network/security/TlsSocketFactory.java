package lk.thogamura.gateway.network.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;

/**
 * Custom {@link SocketFactory} providing validated JSSE {@link SSLSocket} instances
 * for the API Gateway's command client.
 *
 * <p>Enforces the following security rules:
 * <ul>
 *   <li>Loads CA public certificates from a dedicated PKCS#12 truststore (strictly no private keys).</li>
 *   <li>Enforces RFC 2818 endpoint identification (HTTPS hostname / IP SAN verification).</li>
 *   <li>Configures enabled TLS protocols (TLS 1.3 / TLS 1.2).</li>
 *   <li>Fails closed when truststore is missing, unreadable, or invalid (no trust-all managers).</li>
 * </ul>
 * </p>
 */
public class TlsSocketFactory extends SocketFactory {

    private static final Logger logger = LoggerFactory.getLogger(TlsSocketFactory.class);

    private final SSLSocketFactory delegate;
    private final String[] enabledProtocols;
    private final boolean hostnameVerification;

    public TlsSocketFactory(
            SSLSocketFactory delegate,
            String[] enabledProtocols,
            boolean hostnameVerification) {
        if (!hostnameVerification) {
            throw new IllegalArgumentException(
                    "Disabling TLS hostname verification is prohibited. RFC 2818 endpoint identification must remain active.");
        }
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.enabledProtocols = (enabledProtocols != null && enabledProtocols.length > 0)
                ? enabledProtocols.clone()
                : new String[]{"TLSv1.3", "TLSv1.2"};
        this.hostnameVerification = true;
    }

    public TlsSocketFactory(
            SSLContext sslContext,
            String[] enabledProtocols,
            boolean hostnameVerification) {
        this(Objects.requireNonNull(sslContext, "sslContext must not be null").getSocketFactory(),
                enabledProtocols,
                hostnameVerification);
    }

    /**
     * Creates a {@link TlsSocketFactory} from a PKCS#12 truststore file and configuration.
     *
     * @param truststorePath path to the truststore file
     * @param password truststore password
     * @param truststoreType truststore format (defaults to PKCS12)
     * @param enabledProtocols TLS protocols to enable (e.g. TLSv1.3, TLSv1.2)
     * @param hostnameVerification whether to enforce RFC 2818 endpoint identification
     * @return configured TLS socket factory
     * @throws GeneralSecurityException if truststore decryption or SSLContext initialization fails
     * @throws IOException if truststore file is missing or unreadable
     */
    public static TlsSocketFactory fromTruststore(
            Path truststorePath,
            char[] password,
            String truststoreType,
            String[] enabledProtocols,
            boolean hostnameVerification) throws GeneralSecurityException, IOException {

        Objects.requireNonNull(truststorePath, "truststorePath must not be null");
        if (!Files.exists(truststorePath)) {
            throw new FileNotFoundException("Gateway truststore file not found: " + truststorePath.toAbsolutePath());
        }

        String effectiveType = (truststoreType != null && !truststoreType.isBlank()) ? truststoreType : "PKCS12";
        char[] effectivePassword = (password != null) ? password : new char[0];

        KeyStore truststore = KeyStore.getInstance(effectiveType);
        try (InputStream in = new FileInputStream(truststorePath.toFile())) {
            truststore.load(in, effectivePassword);
        }

        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(truststore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, tmf.getTrustManagers(), new SecureRandom());

        String[] protocols = (enabledProtocols != null && enabledProtocols.length > 0)
                ? enabledProtocols
                : new String[]{"TLSv1.3", "TLSv1.2"};

        logger.info("Initialized TLS client socket factory with truststore '{}' (type: {}, hostnameVerification: {})",
                truststorePath, effectiveType, hostnameVerification);

        return new TlsSocketFactory(sslContext, protocols, hostnameVerification);
    }

    private Socket configure(Socket socket) {
        if (socket instanceof SSLSocket sslSocket) {
            if (enabledProtocols != null && enabledProtocols.length > 0) {
                sslSocket.setEnabledProtocols(enabledProtocols);
            }
            SSLParameters params = sslSocket.getSSLParameters();
            if (hostnameVerification) {
                params.setEndpointIdentificationAlgorithm("HTTPS");
            }
            sslSocket.setSSLParameters(params);
        }
        return socket;
    }

    @Override
    public Socket createSocket() throws IOException {
        return configure(delegate.createSocket());
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return configure(delegate.createSocket(host, port, localHost, localPort));
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return configure(delegate.createSocket(host, port));
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return configure(delegate.createSocket(address, port, localAddress, localPort));
    }

    public String[] getEnabledProtocols() {
        return enabledProtocols.clone();
    }

    public boolean isHostnameVerification() {
        return hostnameVerification;
    }
}
