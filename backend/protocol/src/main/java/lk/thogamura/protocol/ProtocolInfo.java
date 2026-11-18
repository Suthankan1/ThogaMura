package lk.thogamura.protocol;

/**
 * Shared protocol constants for ThogaMura Wire Protocol v1.
 *
 * <p>Frozen specification matching {@code docs/contracts/NETWORK_PROTOCOL_V1.md}.
 * Shared across all backend modules (network-server, api-gateway) and test suites.</p>
 */
public final class ProtocolInfo {

    // Wire Framing & Encoding
    public static final int WIRE_VERSION = 1;
    public static final int MAX_FRAME_BYTES = 65_536; // 64 KB
    public static final char FRAME_DELIMITER = '\n';

    // Default Network Ports
    public static final int DEFAULT_TLS_COMMAND_PORT = 7443;
    public static final int DEFAULT_NIO_NOTIFICATION_PORT = 7444;
    public static final int DEFAULT_GATEWAY_PORT = 8080;
    public static final int DEFAULT_SUPPLIER_MOCK_PORT = 8090;

    // Default Socket Timeouts (in milliseconds)
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 3_000;
    public static final int DEFAULT_READ_TIMEOUT_MS = 5_000;

    // Command Types (Request Envelope "type")
    public static final String COMMAND_PING = "PING";
    public static final String COMMAND_GET_PRODUCTS = "GET_PRODUCTS";
    public static final String COMMAND_REQUEST_STOCK = "REQUEST_STOCK";
    public static final String COMMAND_GET_REQUESTS = "GET_REQUESTS";
    public static final String COMMAND_MARK_PICKED = "MARK_PICKED";
    public static final String COMMAND_MARK_DISPATCHED = "MARK_DISPATCHED";
    public static final String COMMAND_GET_MOVEMENTS = "GET_MOVEMENTS";
    public static final String COMMAND_GET_SUPPLIER_STOCK = "GET_SUPPLIER_STOCK";

    // Response Types & Statuses (Result Envelope "type" and "status")
    public static final String TYPE_RESULT = "RESULT";
    public static final String STATUS_ACCEPTED = "ACCEPTED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_PONG = "PONG";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_PICKED = "PICKED";
    public static final String STATUS_DISPATCHED = "DISPATCHED";
    public static final String STATUS_AVAILABLE = "AVAILABLE";
    public static final String STATUS_UNAVAILABLE = "UNAVAILABLE";
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    // Standard Error Codes
    public static final String ERROR_VALIDATION = "VALIDATION_ERROR";
    public static final String ERROR_NOT_FOUND = "NOT_FOUND";
    public static final String ERROR_INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK";
    public static final String ERROR_INVALID_TRANSITION = "INVALID_TRANSITION";
    public static final String ERROR_DUPLICATE_ID_CONFLICT = "DUPLICATE_ID_CONFLICT";
    public static final String ERROR_UNSUPPORTED_VERSION = "UNSUPPORTED_VERSION";
    public static final String ERROR_SUPPLIER_UNAVAILABLE = "SUPPLIER_UNAVAILABLE";
    public static final String ERROR_SUPPLIER_TIMEOUT = "SUPPLIER_TIMEOUT";
    public static final String ERROR_SUPPLIER_UNKNOWN = "SUPPLIER_UNKNOWN";
    public static final String ERROR_INTERNAL = "INTERNAL_ERROR";

    // Java NIO Notification Feed Constants
    public static final String NOTIFICATION_TYPE_CHANGED = "CHANGED";

    private ProtocolInfo() {
    }
}
