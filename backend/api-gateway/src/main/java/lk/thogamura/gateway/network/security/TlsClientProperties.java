package lk.thogamura.gateway.network.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Arrays;

/**
 * Configuration properties for the branch gateway Java TLS command transport.
 *
 * <p>Consumes standard ThogaMura TLS client configuration as defined in
 * {@code docs/TLS_CERTIFICATES.md} and ADR-004.</p>
 */
@ConfigurationProperties(prefix = "thogamura.tls.client")
public class TlsClientProperties {

    /**
     * Whether validated TLS encryption is enabled for the warehouse command client.
     */
    private boolean enabled = false;

    /**
     * Filesystem path to the PKCS#12 gateway truststore (strictly containing only trusted CAs).
     */
    private String truststorePath = ".certs/gateway-truststore.p12";

    /**
     * Password protecting the gateway truststore.
     */
    private String truststorePassword = "";

    /**
     * Truststore storage format (defaults to PKCS12).
     */
    private String truststoreType = "PKCS12";

    /**
     * Enforce strict RFC 2818 endpoint identification (HTTPS hostname / IP SAN verification).
     * Must remain true in all environments to prevent rogue server spoofing.
     */
    private boolean hostnameVerification = true;

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

    public String getTruststorePath() {
        return truststorePath;
    }

    public void setTruststorePath(String truststorePath) {
        this.truststorePath = truststorePath;
    }

    public String getTruststorePassword() {
        return truststorePassword;
    }

    public void setTruststorePassword(String truststorePassword) {
        this.truststorePassword = truststorePassword;
    }

    public String getTruststoreType() {
        return truststoreType;
    }

    public void setTruststoreType(String truststoreType) {
        this.truststoreType = truststoreType;
    }

    public boolean isHostnameVerification() {
        return hostnameVerification;
    }

    public void setHostnameVerification(boolean hostnameVerification) {
        if (!hostnameVerification) {
            throw new IllegalArgumentException(
                    "TLS hostname verification must remain enabled for the command transport");
        }
        this.hostnameVerification = hostnameVerification;
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
