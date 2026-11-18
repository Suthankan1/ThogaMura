package lk.thogamura.networkserver.concurrent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing the {@link InventoryWorkerPool} bean for concurrent task execution.
 */
@Configuration
public class WorkerPoolConfig {

    @Bean(destroyMethod = "close")
    public InventoryWorkerPool inventoryWorkerPool(
            @Value("${thogamura.concurrent.core-pool-size:8}") int corePoolSize,
            @Value("${thogamura.concurrent.max-pool-size:16}") int maxPoolSize,
            @Value("${thogamura.concurrent.queue-capacity:100}") int queueCapacity
    ) {
        return new InventoryWorkerPool(corePoolSize, maxPoolSize, queueCapacity);
    }
}
