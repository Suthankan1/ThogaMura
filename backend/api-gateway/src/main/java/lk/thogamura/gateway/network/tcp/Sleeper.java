package lk.thogamura.gateway.network.tcp;

/**
 * Functional abstraction for pause / sleep during backoff retries.
 *
 * <p>Enables deterministic, fast execution of retry policies in automated tests
 * without forcing real wall-clock delays.</p>
 */
@FunctionalInterface
public interface Sleeper {

    /**
     * Sleep for the specified duration in milliseconds.
     *
     * @param millis milliseconds to pause
     * @throws InterruptedException if interrupted while paused
     */
    void sleep(long millis) throws InterruptedException;
}
