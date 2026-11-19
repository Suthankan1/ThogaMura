package lk.thogamura.gateway.web;

import lk.thogamura.gateway.network.tcp.CommandCorrelationException;
import lk.thogamura.gateway.network.tcp.CommandExecutionException;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.protocol.ProtocolInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Global REST exception handler aligning gateway HTTP responses with {@code docs/contracts/HTTP_API_V1.md}.
 *
 * <p>Strictly distinguishes:
 * <ul>
 *   <li>Offline / disconnected warehouse server: {@code 503 Service Unavailable}</li>
 *   <li>Ambiguous socket timeouts: {@code 504 Gateway Timeout}</li>
 *   <li>Response correlation / protocol failures: {@code 502 Bad Gateway}</li>
 *   <li>Command business / execution conflicts: {@code 409 Conflict}</li>
 * </ul>
 */
@RestControllerAdvice
public class GatewayExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GatewayExceptionHandler.class);

    @ExceptionHandler(ServerUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleServerUnavailable(ServerUnavailableException e) {
        logger.warn("Warehouse server unavailable (requestId: '{}'): {}", e.getRequestId(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "WAREHOUSE_UNAVAILABLE");
        body.put("message", "Warehouse server is unreachable: " + e.getMessage());
        if (e.getRequestId() != null) {
            body.put("requestId", e.getRequestId());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(CommandTimeoutException.class)
    public ResponseEntity<Map<String, Object>> handleCommandTimeout(CommandTimeoutException e) {
        logger.warn("Warehouse command timed out (requestId: '{}'): {}", e.getRequestId(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "WAREHOUSE_TIMEOUT");
        body.put("message", "Command timed out awaiting warehouse response: " + e.getMessage());
        if (e.getRequestId() != null) {
            body.put("requestId", e.getRequestId());
        }
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(body);
    }

    @ExceptionHandler(CommandCorrelationException.class)
    public ResponseEntity<Map<String, Object>> handleCommandCorrelation(CommandCorrelationException e) {
        logger.error("Command correlation error: expected '{}' but received '{}'",
                e.getExpectedRequestId(), e.getReceivedRequestId());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "CORRELATION_ERROR");
        body.put("message", e.getMessage());
        body.put("expectedRequestId", e.getExpectedRequestId());
        body.put("receivedRequestId", e.getReceivedRequestId());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(CommandExecutionException.class)
    public ResponseEntity<Map<String, Object>> handleCommandExecution(CommandExecutionException e) {
        logger.warn("Command execution returned protocol error '{}' (requestId: '{}'): {}",
                e.getErrorCode(), e.getRequestId(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.getErrorCode());
        body.put("message", e.getMessage());
        if (e.getRequestId() != null) {
            body.put("requestId", e.getRequestId());
        }

        HttpStatus status = switch (e.getErrorCode()) {
            case ProtocolInfo.ERROR_INSUFFICIENT_STOCK,
                 ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT,
                 ProtocolInfo.ERROR_INVALID_TRANSITION -> HttpStatus.CONFLICT;
            case ProtocolInfo.ERROR_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ProtocolInfo.ERROR_VALIDATION,
                 ProtocolInfo.ERROR_UNSUPPORTED_VERSION -> HttpStatus.BAD_REQUEST;
            case ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case ProtocolInfo.ERROR_SUPPLIER_TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case ProtocolInfo.ERROR_SUPPLIER_UNKNOWN -> HttpStatus.BAD_GATEWAY;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };

        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(CommandTransportException.class)
    public ResponseEntity<Map<String, Object>> handleCommandTransport(CommandTransportException e) {
        logger.error("Command transport error (requestId: '{}'): {}", e.getRequestId(), e.getMessage(), e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ProtocolInfo.ERROR_INTERNAL);
        body.put("message", "Internal error communicating with warehouse server: " + e.getMessage());
        if (e.getRequestId() != null) {
            body.put("requestId", e.getRequestId());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
