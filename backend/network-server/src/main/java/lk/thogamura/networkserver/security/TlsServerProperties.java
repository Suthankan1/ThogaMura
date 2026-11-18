package lk.thogamura.networkserver.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;

/**
 * Configuration properties for the warehouse Java TLS command listener.
 *
 * <p>Consumes standard ThogaMura TLS server configuration as defined in
 * {@code docs/TLS_CERTIFICATES.md} and ADR-004.</p>
 */
@ConfigurationProperties(prefix = "thogamura.tls.server")
public class TlsServerProperties {

    /**
     * Whether validated TLS encryption is enabled on the warehouse command port.
     */
    private boolean enabled = false;

    /**
     * Filesystem path to the PKCS#12 server keystore.
     */
    private String keystorePath = ".certs/server-keystore.p12";

    /**
     * Password protecting the server keystore.
     */
    private String keystorePassword = "";

    /**
     * Keystore storage format (defaults to PKCS12).
     */
    private String keystoreType = "PKCS12";

    /**
     * Comma-separated list of enabled TLS protocol versions.
     */
    private String protocols = "TLSv1.3,TLSv1.2";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeystorePath() {
        return keystorePath;
    }

    public void setKeystorePath(String keystorePath) {
        this.keystorePath = keystorePath;
    }

    public String getKeystorePassword() {
        return keystorePassword;
    }

    public void setKeystorePassword(String keystorePassword) {
        this.keystorePassword = keystorePassword;
    }

    public String getKeystoreType() {
        return keystoreType;
    }

    public void setKeystoreType(String keystoreType) {
        this.keystoreType = keystoreType;
    }

    public String getProtocols() {
        return protocols;
    }

    public void setProtocols(String protocols) {
        this.protocols = protocols;
    }

    public String[] getProtocolsArray() {
        if (protocols == null || protocols.isBlank()) {
            return new String[]{"TLSv1.3", "TLSv1.2"};
        }
        return Arrays.stream(protocols.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
