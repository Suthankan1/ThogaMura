package lk.thogamura.gateway.network.notifications;

/**
 * Lifecycle states of the Gateway Java NIO subscriber connection.
 */
public enum SubscriberStatus {
    /** Subscriber is stopped or not yet initialized. */
    DISCONNECTED,
    /** Establishing TCP connection to the warehouse notification server. */
    CONNECTING,
    /** Successfully connected and listening for change frames. */
    CONNECTED,
    /** Connection was lost or failed; waiting before exponential backoff retry. */
    RECONNECTING,
    /** Subscriber has been permanently stopped and resources released. */
    STOPPED
}
