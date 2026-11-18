package lk.thogamura.networkserver.notifications;

/**
 * Publisher interface for broadcasting inventory and fulfillment change notifications.
 *
 * <p>Demonstrates Member 3's Java NIO notification feed. Implementations broadcast
 * lightweight, version-only signals to connected branch gateways without leaking
 * business details.</p>
 */
public interface NotificationPublisher {

    /**
     * Publishes a version-only notification to all active subscribers.
     *
     * @param notification the validated change notification to broadcast
     */
    void publish(ChangeNotification notification);

    /**
     * Convenience overload to publish a notification for a given change version.
     *
     * @param version the monotonically increasing change version (>= 0)
     */
    default void publish(long version) {
        publish(ChangeNotification.of(version));
    }
}
