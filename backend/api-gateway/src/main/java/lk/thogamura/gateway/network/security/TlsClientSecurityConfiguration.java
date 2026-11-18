package lk.thogamura.gateway.network.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.SocketFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Spring configuration providing the {@link SocketFactory} bean for the gateway command client.
 */
@Configuration
@EnableConfigurationProperties(TlsClientProperties.class)
public class TlsClientSecurityConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(TlsClientSecurityConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "thogamura.tls.client", name = "enabled", havingValue = "true")
    public SocketFactory tlsSocketFactory(TlsClientProperties properties)
            throws GeneralSecurityException, IOException {
        logger.info("Configuring TLS client socket factory using truststore at '{}'", properties.getTruststorePath());
        return TlsSocketFactory.fromTruststore(
                Path.of(properties.getTruststorePath()),
                properties.getTruststorePassword().toCharArray(),
                properties.getTruststoreType(),
                properties.getProtocolsArray(),
                properties.isHostnameVerification()
        );
    }
}
