package lk.thogamura.networkserver.security;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.net.ServerSocketFactory;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TlsServerConfigurationTest {

    private static TlsTestCertificateHelper.CertBundle bundle;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TlsServerSecurityConfiguration.class);

    @BeforeAll
    static void generateCerts(@TempDir Path tempDir) throws Exception {
        bundle = TlsTestCertificateHelper.generateValidBundle(tempDir);
    }

    @Test
    @DisplayName("When TLS is disabled, no TlsServerSocketFactory bean is created")
    void testTlsDisabledByDefault() {
        contextRunner
                .withPropertyValues("thogamura.tls.server.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ServerSocketFactory.class);
                });
    }

    @Test
    @DisplayName("When TLS is enabled, TlsServerSocketFactory bean is created and configured")
    void testTlsEnabledCreatesFactory() {
        contextRunner
                .withPropertyValues(
                        "thogamura.tls.server.enabled=true",
                        "thogamura.tls.server.keystore-path=" + bundle.serverKeystore().toAbsolutePath(),
                        "thogamura.tls.server.keystore-password=" + new String(bundle.serverPassword()),
                        "thogamura.tls.server.keystore-type=PKCS12",
                        "thogamura.tls.server.protocols=TLSv1.3,TLSv1.2"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ServerSocketFactory.class);
                    ServerSocketFactory factory = context.getBean(ServerSocketFactory.class);
                    assertThat(factory).isInstanceOf(TlsServerSocketFactory.class);

                    TlsServerSocketFactory tlsFactory = (TlsServerSocketFactory) factory;
                    assertThat(tlsFactory.getEnabledProtocols()).containsExactly("TLSv1.3", "TLSv1.2");
                });
    }
}
