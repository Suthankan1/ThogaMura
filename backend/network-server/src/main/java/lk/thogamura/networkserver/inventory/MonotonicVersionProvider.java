package lk.thogamura.networkserver.inventory;

/**
 * Provider for monotonically increasing change version numbers for Java NIO notification invalidation signals.
 */
public interface MonotonicVersionProvider {

    /**
     * Generates and returns the next monotonically increasing change version (>= 0).
     *
     * @return next version number
     */
    long nextVersion();

    /**
     * Returns the current change version without incrementing.
     *
     * @return current version number
     */
    long currentVersion();
}
