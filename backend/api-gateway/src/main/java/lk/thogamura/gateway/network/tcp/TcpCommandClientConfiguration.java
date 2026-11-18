package lk.thogamura.gateway.network.tcp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.net.SocketFactory;

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
            @Value("${thogamura.command.client.max-frame-bytes:65536}") int maxFrameBytes) {
        return new TcpCommandClientConfig(host, port, connectTimeoutMs, readTimeoutMs, maxFrameBytes);
    }

    @Bean
    @ConditionalOnMissingBean
    public TcpCommandClient tcpCommandClient(TcpCommandClientConfig config) {
        return new TcpCommandClient(config, SocketFactory.getDefault());
    }
}
