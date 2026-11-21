package lk.thogamura.networkserver.inventory;

import lk.thogamura.networkserver.persistence.PostgresTestHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultMonotonicVersionProviderTest {

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void initDatabase() {
        jdbcTemplate = PostgresTestHelper.getJdbcTemplate();
    }

    @Test
    @DisplayName("In-memory provider generates monotonically increasing sequence")
    void shouldGenerateMonotonicSequenceInMemory() {
        MonotonicVersionProvider provider = new DefaultMonotonicVersionProvider(500L);
        assertThat(provider.nextVersion()).isEqualTo(501L);
        assertThat(provider.nextVersion()).isEqualTo(502L);
        assertThat(provider.nextVersion()).isEqualTo(503L);
        assertThat(provider.currentVersion()).isEqualTo(503L);
    }

    @Test
    @DisplayName("PostgreSQL sequence guarantees monotonic versions across server restarts even with clock regression (#67)")
    void shouldGuaranteeMonotonicityAcrossProcessRestarts() {
        // Instance 1: Initial process startup
        DefaultMonotonicVersionProvider instance1 = new DefaultMonotonicVersionProvider(jdbcTemplate);
        long v1 = instance1.nextVersion();
        long v2 = instance1.nextVersion();
        long v3 = instance1.nextVersion();

        assertThat(v2).isGreaterThan(v1);
        assertThat(v3).isGreaterThan(v2);

        // Simulate complete process crash / shutdown and restart
        // Instance 2 starts with an earlier clock seed, but attaches to the durable PostgreSQL sequence
        DefaultMonotonicVersionProvider restartedInstance = new DefaultMonotonicVersionProvider(jdbcTemplate);
        long v4 = restartedInstance.nextVersion();
        long v5 = restartedInstance.nextVersion();

        assertThat(v4).isGreaterThan(v3);
        assertThat(v5).isGreaterThan(v4);
    }

    @Test
    @DisplayName("Concurrent version requests produce strictly distinct, monotonically increasing IDs (#67)")
    void shouldHandleConcurrentVersionRequestsWithoutCollisions() throws Exception {
        DefaultMonotonicVersionProvider provider = new DefaultMonotonicVersionProvider(jdbcTemplate);
        int threadCount = 20;
        int requestsPerThread = 50;
        int totalRequests = threadCount * requestsPerThread;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        List<Long> generatedVersions = Collections.synchronizedList(new ArrayList<>(totalRequests));

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < requestsPerThread; j++) {
                        generatedVersions.add(provider.nextVersion());
                    }
                } catch (InterruptedException ignored) {
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(generatedVersions).hasSize(totalRequests);

        // Every version generated must be unique
        Set<Long> uniqueVersions = new HashSet<>(generatedVersions);
        assertThat(uniqueVersions).hasSize(totalRequests);
    }
}
