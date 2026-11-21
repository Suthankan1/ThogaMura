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
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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
        body.put("message", "Warehouse server is unreachable. Please verify warehouse connectivity and retry.");
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
        body.put("message", "Command timed out awaiting warehouse response. Please retry.");
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
        body.put("message", "Invalid or mismatched response received from warehouse server.");
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
        body.put("message", "Internal transport error communicating with warehouse server.");
        if (e.getRequestId() != null) {
            body.put("requestId", e.getRequestId());
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleMessageNotReadable(HttpMessageNotReadableException e) {
        logger.warn("Malformed HTTP request body: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ProtocolInfo.ERROR_VALIDATION);
        body.put("message", "Malformed JSON request body syntax.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, Object>> handleMissingHeader(MissingRequestHeaderException e) {
        logger.warn("Missing required request header: {}", e.getHeaderName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "MISSING_HEADER");
        body.put("message", "Required header '" + e.getHeaderName() + "' is missing.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParameter(MissingServletRequestParameterException e) {
        logger.warn("Missing required query parameter: {}", e.getParameterName());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "MISSING_PARAMETER");
        body.put("message", "Required parameter '" + e.getParameterName() + "' is missing.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        logger.warn("Parameter type mismatch for parameter '{}': {}", e.getName(), e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ProtocolInfo.ERROR_VALIDATION);
        body.put("message", "Invalid parameter value for '" + e.getName() + "'.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        logger.warn("HTTP method not supported: {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "METHOD_NOT_ALLOWED");
        body.put("message", "HTTP method '" + e.getMethod() + "' is not supported for this endpoint.");
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(NoResourceFoundException e) {
        logger.warn("Resource not found: {}", e.getResourcePath());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ProtocolInfo.ERROR_NOT_FOUND);
        body.put("message", "Requested resource or endpoint does not exist.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneralException(Exception e) {
        logger.error("Unhandled exception processing gateway request: {}", e.getMessage(), e);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", ProtocolInfo.ERROR_INTERNAL);
        body.put("message", "An unexpected internal error occurred.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
