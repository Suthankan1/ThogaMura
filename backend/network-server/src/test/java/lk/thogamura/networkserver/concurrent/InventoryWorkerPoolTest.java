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

    @Test
    @DisplayName("Handles queue saturation with predictable CallerRunsPolicy backpressure")
    void shouldHandleQueueSaturationWithPredictableBackpressure() throws Exception {
        // Pool: 1 worker, 1 queue slot.
        // Task 1 occupies worker thread.
        // Task 2 fills the queue.
        // Task 3 is forced to run on the caller thread via CallerRunsPolicy.
        try (InventoryWorkerPool pool = new InventoryWorkerPool(1, 1, 1)) {
            CountDownLatch task1Started = new CountDownLatch(1);
            CountDownLatch releaseTask1 = new CountDownLatch(1);
            CountDownLatch task2Done = new CountDownLatch(1);

            List<String> executionThreads = Collections.synchronizedList(new ArrayList<>());

            // Task 1: blocks the sole worker thread
            pool.submit(() -> {
                executionThreads.add(Thread.currentThread().getName());
                task1Started.countDown();
                try {
                    releaseTask1.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(task1Started.await(5, TimeUnit.SECONDS)).isTrue();

            // Task 2: enqueued into the 1-slot queue
            pool.submit(() -> {
                executionThreads.add(Thread.currentThread().getName());
                task2Done.countDown();
            });

            // Task 3: worker is busy, queue is full -> must execute on calling thread (main)
            String callerThreadName = Thread.currentThread().getName();
            pool.submit(() -> {
                executionThreads.add(Thread.currentThread().getName());
            });

            // The saturated task must have run synchronously on caller thread
            assertThat(executionThreads).contains(callerThreadName);

            // Unblock worker thread and await completion of task 2
            releaseTask1.countDown();
            assertThat(task2Done.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(executionThreads).hasSize(3);
        }
    }

    @Test
    @DisplayName("Gracefully shuts down and cleans up worker resources")
    void shouldGracefullyShutdownAndCleanupResources() throws Exception {
        InventoryWorkerPool pool = new InventoryWorkerPool(2, 2, 5);
        CountDownLatch inFlightDone = new CountDownLatch(1);

        pool.submit(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ignored) {
            }
            inFlightDone.countDown();
        });

        assertThat(pool.isShutdown()).isFalse();
        pool.close(); // Triggers graceful shutdown and awaits termination

        assertThat(pool.isShutdown()).isTrue();
        assertThat(pool.isTerminated()).isTrue();
        assertThat(inFlightDone.await(2, TimeUnit.SECONDS)).isTrue();
    }
}
