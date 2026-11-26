package lk.thogamura.gateway.network.tcp;

import lk.thogamura.gateway.network.security.TlsSocketFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.SocketFactory;
import java.net.InetAddress;

/**
 * Spring configuration providing the {@link TcpCommandClient} bean.
 */
@Configuration
public class TcpCommandClientConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TcpCommandClientConfig tcpCommandClientConfig(
            @Value("${thogamura.command.client.host:${thogamura.warehouse.host:127.0.0.1}}") String host,
            @Value("${thogamura.command.client.port:${thogamura.warehouse.port:7443}}") int port,
            @Value("${thogamura.command.client.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${thogamura.command.client.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${thogamura.command.client.max-frame-bytes:65536}") int maxFrameBytes,
            @Value("${thogamura.command.client.max-retries:2}") int maxRetries,
            @Value("${thogamura.command.client.retry-backoff-ms:100}") long retryBackoffMs,
            @Value("${thogamura.command.client.retry-backoff-multiplier:2.0}") double retryBackoffMultiplier,
            @Value("${thogamura.command.client.max-retry-backoff-ms:1000}") long maxRetryBackoffMs,
            @Value("${thogamura.command.client.max-pooled-connections:8}") int maxPooledConnections) {
        return new TcpCommandClientConfig(
                host,
                port,
                connectTimeoutMs,
                readTimeoutMs,
                maxFrameBytes,
                maxRetries,
                retryBackoffMs,
                retryBackoffMultiplier,
                maxRetryBackoffMs,
                maxPooledConnections
        );
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public TcpCommandClient tcpCommandClient(
            TcpCommandClientConfig config,
            @org.springframework.beans.factory.annotation.Autowired(required = false) SocketFactory socketFactory) {
        boolean isTls = socketFactory instanceof TlsSocketFactory;
        if (!isTls && !isLoopback(config.getHost())) {
            throw new IllegalStateException(
                    "Plaintext TCP command transport is only permitted for loopback destinations (127.0.0.1, localhost). " +
                    "Remote destination '" + config.getHost() + "' requires validated TLS to be enabled (thogamura.tls.client.enabled=true)."
            );
        }
        return new TcpCommandClient(config, (socketFactory != null) ? socketFactory : SocketFactory.getDefault());
    }

    public static boolean isLoopback(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String trimmed = host.trim();
        if ("127.0.0.1".equals(trimmed) || "localhost".equalsIgnoreCase(trimmed) || "::1".equals(trimmed) || "0:0:0:0:0:0:0:1".equals(trimmed)) {
            return true;
        }
        try {
            InetAddress addr = InetAddress.getByName(trimmed);
            return addr.isLoopbackAddress();
        } catch (Exception e) {
            return false;
        }
    }
}
