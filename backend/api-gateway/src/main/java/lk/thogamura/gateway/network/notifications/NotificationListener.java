package lk.thogamura.gateway.network.notifications;

/**
 * Application-level callback interface for receiving warehouse inventory/state change notifications.
 *
 * <p>Receives purely the change version number. Business details (SKUs, quantities, customer data)
 * are never transmitted through this feed. Connected gateways use this signal to invalidate
 * local state and trigger SSE invalidation events for browsers.</p>
 */
@FunctionalInterface
public interface NotificationListener {

    /**
     * Invoked when a valid version-only change notification is received from the warehouse.
     *
     * @param version the monotonically increasing change version (>= 0)
     */
    void onNotification(long version);
}
