package lk.thogamura.networkserver.integration.supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing supplier connector beans.
 */
@Configuration
public class SupplierConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SupplierClientConfig supplierClientConfig(
            @Value("${thogamura.supplier.base-url:http://127.0.0.1:8090}") String baseUrl,
            @Value("${thogamura.supplier.connect-timeout-ms:3000}") int connectTimeoutMs,
            @Value("${thogamura.supplier.read-timeout-ms:5000}") int readTimeoutMs,
            @Value("${thogamura.supplier.max-response-bytes:16384}") int maxResponseBytes,
            @Value("${thogamura.supplier.allow-unapproved-urls:false}") boolean allowUnapprovedUrls) {
        return new SupplierClientConfig(baseUrl, connectTimeoutMs, readTimeoutMs, maxResponseBytes, allowUnapprovedUrls);
    }

    @Bean
    @ConditionalOnMissingBean
    public SupplierHttpClient supplierHttpClient(SupplierClientConfig config) {
        return new DefaultSupplierHttpClient(config);
    }

    @Bean
    @ConditionalOnMissingBean
    public SupplierCommandHandler supplierCommandHandler(SupplierHttpClient supplierHttpClient) {
        return new SupplierCommandHandler(supplierHttpClient);
    }
}
