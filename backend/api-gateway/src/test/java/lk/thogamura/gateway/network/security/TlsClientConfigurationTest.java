package lk.thogamura.gateway.network.security;

import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.gateway.network.tcp.TcpCommandClientConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.net.SocketFactory;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TlsClientConfigurationTest {

    private static TlsTestCertificateHelper.CertBundle bundle;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    TlsClientSecurityConfiguration.class,
                    TcpCommandClientConfiguration.class
            );

    @BeforeAll
    static void generateCerts(@TempDir Path tempDir) throws Exception {
        bundle = TlsTestCertificateHelper.generateValidBundle(tempDir);
    }

    @Test
    @DisplayName("When TLS client is disabled, standard plain SocketFactory is used")
    void testTlsClientDisabledByDefault() {
        contextRunner
                .withPropertyValues("thogamura.tls.client.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TlsSocketFactory.class);
                    assertThat(context).hasSingleBean(TcpCommandClient.class);
                    TcpCommandClient client = context.getBean(TcpCommandClient.class);
                    assertThat(client.getSocketFactory()).isEqualTo(SocketFactory.getDefault());
                });
    }

    @Test
    @DisplayName("When TLS client is enabled, TlsSocketFactory is created and injected into TcpCommandClient")
    void testTlsClientEnabledCreatesFactory() {
        contextRunner
                .withPropertyValues(
                        "thogamura.tls.client.enabled=true",
                        "thogamura.tls.client.truststore-path=" + bundle.gatewayTruststore().toAbsolutePath(),
                        "thogamura.tls.client.truststore-password=" + new String(bundle.trustPassword()),
                        "thogamura.tls.client.truststore-type=PKCS12",
                        "thogamura.tls.client.hostname-verification=true",
                        "thogamura.tls.client.protocols=TLSv1.3,TLSv1.2"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(SocketFactory.class);
                    SocketFactory factory = context.getBean(SocketFactory.class);
                    assertThat(factory).isInstanceOf(TlsSocketFactory.class);

                    TlsSocketFactory tlsFactory = (TlsSocketFactory) factory;
                    assertThat(tlsFactory.getEnabledProtocols()).containsExactly("TLSv1.3", "TLSv1.2");
                    assertThat(tlsFactory.isHostnameVerification()).isTrue();

                    assertThat(context).hasSingleBean(TcpCommandClient.class);
                    TcpCommandClient client = context.getBean(TcpCommandClient.class);
                    assertThat(client.getSocketFactory()).isSameAs(factory);
                });
    }

    @Test
    @DisplayName("Plaintext connection to a remote (non-loopback) host fails closed at startup")
    void testPlaintextFailsForNonLoopbackHost() {
        contextRunner
                .withPropertyValues(
                        "thogamura.command.client.host=192.168.1.100",
                        "thogamura.tls.client.enabled=false"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Plaintext TCP command transport is only permitted for loopback destinations");
                });
    }

    @Test
    @DisplayName("Remote (non-loopback) host succeeds when TLS is enabled")
    void testTlsClientEnabledWithRemoteHostSucceeds() {
        contextRunner
                .withPropertyValues(
                        "thogamura.command.client.host=192.168.1.100",
                        "thogamura.tls.client.enabled=true",
                        "thogamura.tls.client.truststore-path=" + bundle.gatewayTruststore().toAbsolutePath(),
                        "thogamura.tls.client.truststore-password=" + new String(bundle.trustPassword()),
                        "thogamura.tls.client.truststore-type=PKCS12",
                        "thogamura.tls.client.hostname-verification=true"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(TcpCommandClient.class);
                    TcpCommandClient client = context.getBean(TcpCommandClient.class);
                    assertThat(client.getSocketFactory()).isInstanceOf(TlsSocketFactory.class);
                });
    }

    @Test
    @DisplayName("Disabling TLS hostname-verification fails closed at startup")
    void testHostnameVerificationCannotBeDisabled() {
        contextRunner
                .withPropertyValues(
                        "thogamura.tls.client.enabled=true",
                        "thogamura.tls.client.truststore-path=" + bundle.gatewayTruststore().toAbsolutePath(),
                        "thogamura.tls.client.truststore-password=" + new String(bundle.trustPassword()),
                        "thogamura.tls.client.hostname-verification=false"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("Disabling TLS hostname verification");
                });
    }

    @Test
    @DisplayName("TlsSocketFactory constructor rejects hostnameVerification=false with IllegalArgumentException")
    void testTlsSocketFactoryConstructorRejectsDisabledHostnameVerification() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new TlsSocketFactory((javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault(),
                        new String[]{"TLSv1.3"}, false)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Disabling TLS hostname verification is prohibited");
    }
}
