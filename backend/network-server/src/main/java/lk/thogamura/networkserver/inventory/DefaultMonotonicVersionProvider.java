package lk.thogamura.networkserver.inventory;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe monotonic version provider backed by {@link AtomicLong} seeded from current system time.
 */
@Component
public class DefaultMonotonicVersionProvider implements MonotonicVersionProvider {

    private final AtomicLong counter;

    public DefaultMonotonicVersionProvider() {
        // Seed with current time in milliseconds to guarantee increasing sequence across server restarts
        this.counter = new AtomicLong(System.currentTimeMillis());
    }

    public DefaultMonotonicVersionProvider(long initialVersion) {
        if (initialVersion < 0) {
            throw new IllegalArgumentException("initialVersion must be non-negative: " + initialVersion);
        }
        this.counter = new AtomicLong(initialVersion);
    }

    @Override
    public long nextVersion() {
        return counter.incrementAndGet();
    }

    @Override
    public long currentVersion() {
        return counter.get();
    }
}
