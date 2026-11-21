package lk.thogamura.gateway.web;

import lk.thogamura.gateway.network.tcp.CommandCorrelationException;
import lk.thogamura.gateway.network.tcp.CommandExecutionException;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.protocol.ProtocolInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    @DisplayName("Should map ServerUnavailableException to 503 Service Unavailable with redacted message")
    void shouldMapServerUnavailableTo503() {
        ServerUnavailableException ex = new ServerUnavailableException("Connection refused to 192.168.1.50:7443 with path /etc/secrets", "req-123");
        ResponseEntity<Map<String, Object>> res = handler.handleServerUnavailable(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).containsEntry("code", "WAREHOUSE_UNAVAILABLE");
        assertThat(res.getBody()).containsEntry("requestId", "req-123");
        assertThat((String) res.getBody().get("message")).doesNotContain("192.168.1.50");
        assertThat((String) res.getBody().get("message")).doesNotContain("/etc/secrets");
        assertThat((String) res.getBody().get("message")).contains("Warehouse server is unreachable");
    }

    @Test
    @DisplayName("Should map CommandTimeoutException to 504 Gateway Timeout with redacted message")
    void shouldMapCommandTimeoutTo504() {
        CommandTimeoutException ex = new CommandTimeoutException("Read timed out on socket 192.168.1.50", "req-456");
        ResponseEntity<Map<String, Object>> res = handler.handleCommandTimeout(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(res.getBody()).containsEntry("code", "WAREHOUSE_TIMEOUT");
        assertThat(res.getBody()).containsEntry("requestId", "req-456");
        assertThat((String) res.getBody().get("message")).doesNotContain("192.168.1.50");
        assertThat((String) res.getBody().get("message")).contains("Command timed out awaiting warehouse response");
    }

    @Test
    @DisplayName("Should map CommandCorrelationException to 502 Bad Gateway with safe message")
    void shouldMapCommandCorrelationTo502() {
        CommandCorrelationException ex = new CommandCorrelationException("Mismatched internal payload", "exp-id", "recv-id");
        ResponseEntity<Map<String, Object>> res = handler.handleCommandCorrelation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody()).containsEntry("code", "CORRELATION_ERROR");
        assertThat(res.getBody()).containsEntry("expectedRequestId", "exp-id");
        assertThat(res.getBody()).containsEntry("receivedRequestId", "recv-id");
        assertThat((String) res.getBody().get("message")).contains("Invalid or mismatched response");
    }

    @Test
    @DisplayName("Should map CommandExecutionException conflicts to 409 Conflict")
    void shouldMapCommandExecutionConflictTo409() {
        CommandExecutionException stockEx = new CommandExecutionException(
                "Insufficient stock", "req-789", ProtocolInfo.ERROR_INSUFFICIENT_STOCK
        );
        ResponseEntity<Map<String, Object>> res = handler.handleCommandExecution(stockEx);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(res.getBody()).containsEntry("code", ProtocolInfo.ERROR_INSUFFICIENT_STOCK);

        CommandExecutionException dupEx = new CommandExecutionException(
                "Duplicate key conflict", "req-789", ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT
        );
        ResponseEntity<Map<String, Object>> dupRes = handler.handleCommandExecution(dupEx);
        assertThat(dupRes.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(dupRes.getBody()).containsEntry("code", ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT);
    }

    @Test
    @DisplayName("Should map generic CommandTransportException to 500 Internal Server Error with redacted message")
    void shouldMapGenericTransportTo500() {
        CommandTransportException ex = new CommandTransportException("Frame error on internal TLS pipeline /var/data/key.pem", "req-999");
        ResponseEntity<Map<String, Object>> res = handler.handleCommandTransport(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).containsEntry("code", ProtocolInfo.ERROR_INTERNAL);
        assertThat((String) res.getBody().get("message")).doesNotContain("/var/data/key.pem");
        assertThat((String) res.getBody().get("message")).contains("Internal transport error communicating with warehouse server");
    }

    @Test
    @DisplayName("Should map HttpMessageNotReadableException to 400 Bad Request with VALIDATION_ERROR")
    void shouldMapHttpMessageNotReadableTo400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException(
                "JSON parse error", new MockHttpInputMessage("invalid json".getBytes())
        );
        ResponseEntity<Map<String, Object>> res = handler.handleMessageNotReadable(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", ProtocolInfo.ERROR_VALIDATION);
        assertThat((String) res.getBody().get("message")).contains("Malformed JSON request body");
    }

    @Test
    @DisplayName("Should map MissingRequestHeaderException to 400 Bad Request with MISSING_HEADER")
    void shouldMapMissingHeaderTo400() throws Exception {
        java.lang.reflect.Method method = getClass().getDeclaredMethod("shouldMapMissingHeaderTo400");
        org.springframework.core.MethodParameter param = new org.springframework.core.MethodParameter(method, -1);
        MissingRequestHeaderException ex = new MissingRequestHeaderException("Idempotency-Key", param);
        ResponseEntity<Map<String, Object>> res = handler.handleMissingHeader(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", "MISSING_HEADER");
        assertThat((String) res.getBody().get("message")).contains("Idempotency-Key");
    }

    @Test
    @DisplayName("Should map MissingServletRequestParameterException to 400 Bad Request with MISSING_PARAMETER")
    void shouldMapMissingParameterTo400() {
        MissingServletRequestParameterException ex = new MissingServletRequestParameterException("sku", "String");
        ResponseEntity<Map<String, Object>> res = handler.handleMissingParameter(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(res.getBody()).containsEntry("code", "MISSING_PARAMETER");
        assertThat((String) res.getBody().get("message")).contains("sku");
    }

    @Test
    @DisplayName("Should map HttpRequestMethodNotSupportedException to 405 Method Not Allowed")
    void shouldMapMethodNotSupportedTo405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("GET");
        ResponseEntity<Map<String, Object>> res = handler.handleMethodNotSupported(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(res.getBody()).containsEntry("code", "METHOD_NOT_ALLOWED");
        assertThat((String) res.getBody().get("message")).contains("GET");
    }

    @Test
    @DisplayName("Should map NoResourceFoundException to 404 Not Found")
    void shouldMapNoResourceFoundTo404() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "/api/v1/nonexistent");
        ResponseEntity<Map<String, Object>> res = handler.handleNoResourceFound(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(res.getBody()).containsEntry("code", ProtocolInfo.ERROR_NOT_FOUND);
        assertThat((String) res.getBody().get("message")).contains("does not exist");
    }

    @Test
    @DisplayName("Should map unhandled unexpected exception to 500 without leaking stack trace or internal message")
    void shouldMapUnhandledExceptionTo500() {
        NullPointerException npe = new NullPointerException("Secret internal database pointer null");
        ResponseEntity<Map<String, Object>> res = handler.handleGeneralException(npe);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).containsEntry("code", ProtocolInfo.ERROR_INTERNAL);
        assertThat((String) res.getBody().get("message")).isEqualTo("An unexpected internal error occurred.");
    }
}
