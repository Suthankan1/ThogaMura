package lk.thogamura.networkserver.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryWorkerPoolTest {

    @Test
    @DisplayName("Validates pool configuration boundaries")
    void shouldValidateConfigurationBoundaries() {
        assertThatThrownBy(() -> new InventoryWorkerPool(0, 4, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryWorkerPool(4, 2, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InventoryWorkerPool(2, 4, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Executes tasks concurrently on named inventory worker threads")
    void shouldExecuteTasksOnNamedThreads() throws Exception {
        try (InventoryWorkerPool pool = new InventoryWorkerPool(4, 8, 20)) {
            int taskCount = 10;
            CountDownLatch latch = new CountDownLatch(taskCount);
            List<String> threadNames = Collections.synchronizedList(new ArrayList<>());

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < taskCount; i++) {
                futures.add(pool.submit(() -> {
                    threadNames.add(Thread.currentThread().getName());
                    latch.countDown();
                }));
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            for (Future<?> f : futures) {
                f.get();
            }

            assertThat(threadNames).hasSize(taskCount);
            for (String name : threadNames) {
                assertThat(name).startsWith("inventory-worker-");
            }
        }
    }

    @Test
    @DisplayName("Reports accurate queue capacity and metrics")
    void shouldReportQueueMetrics() {
        try (InventoryWorkerPool pool = new InventoryWorkerPool(2, 4, 50)) {
            assertThat(pool.getQueueCapacity()).isEqualTo(50);
            assertThat(pool.getQueueSize()).isEqualTo(0);
        }
    }
}
