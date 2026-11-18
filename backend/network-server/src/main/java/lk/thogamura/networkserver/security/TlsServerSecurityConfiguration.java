package lk.thogamura.networkserver.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.ServerSocketFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;

/**
 * Spring configuration providing the {@link ServerSocketFactory} bean for the warehouse command listener.
 */
@Configuration
@EnableConfigurationProperties(TlsServerProperties.class)
public class TlsServerSecurityConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(TlsServerSecurityConfiguration.class);

    @Bean
    @ConditionalOnProperty(prefix = "thogamura.tls.server", name = "enabled", havingValue = "true")
    public ServerSocketFactory tlsServerSocketFactory(TlsServerProperties properties)
            throws GeneralSecurityException, IOException {
        logger.info("Configuring TLS server socket factory using keystore at '{}'", properties.getKeystorePath());
        return TlsServerSocketFactory.fromKeystore(
                Path.of(properties.getKeystorePath()),
                properties.getKeystorePassword().toCharArray(),
                properties.getKeystoreType(),
                properties.getProtocolsArray()
        );
    }
}
