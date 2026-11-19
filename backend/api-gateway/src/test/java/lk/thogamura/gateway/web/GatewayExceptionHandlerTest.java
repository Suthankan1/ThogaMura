package lk.thogamura.gateway.web;

import lk.thogamura.gateway.network.tcp.CommandCorrelationException;
import lk.thogamura.gateway.network.tcp.CommandExecutionException;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.CommandTransportException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.protocol.ProtocolInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayExceptionHandlerTest {

    private final GatewayExceptionHandler handler = new GatewayExceptionHandler();

    @Test
    @DisplayName("Should map ServerUnavailableException to 503 Service Unavailable")
    void shouldMapServerUnavailableTo503() {
        ServerUnavailableException ex = new ServerUnavailableException("Connection refused", "req-123");
        ResponseEntity<Map<String, Object>> res = handler.handleServerUnavailable(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(res.getBody()).containsEntry("code", "WAREHOUSE_UNAVAILABLE");
        assertThat(res.getBody()).containsEntry("requestId", "req-123");
    }

    @Test
    @DisplayName("Should map CommandTimeoutException to 504 Gateway Timeout")
    void shouldMapCommandTimeoutTo504() {
        CommandTimeoutException ex = new CommandTimeoutException("Read timed out", "req-456");
        ResponseEntity<Map<String, Object>> res = handler.handleCommandTimeout(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(res.getBody()).containsEntry("code", "WAREHOUSE_TIMEOUT");
        assertThat(res.getBody()).containsEntry("requestId", "req-456");
    }

    @Test
    @DisplayName("Should map CommandCorrelationException to 502 Bad Gateway")
    void shouldMapCommandCorrelationTo502() {
        CommandCorrelationException ex = new CommandCorrelationException("Mismatched", "exp-id", "recv-id");
        ResponseEntity<Map<String, Object>> res = handler.handleCommandCorrelation(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(res.getBody()).containsEntry("code", "CORRELATION_ERROR");
        assertThat(res.getBody()).containsEntry("expectedRequestId", "exp-id");
        assertThat(res.getBody()).containsEntry("receivedRequestId", "recv-id");
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
    @DisplayName("Should map generic CommandTransportException to 500 Internal Server Error")
    void shouldMapGenericTransportTo500() {
        CommandTransportException ex = new CommandTransportException("Frame error", "req-999");
        ResponseEntity<Map<String, Object>> res = handler.handleCommandTransport(ex);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).containsEntry("code", ProtocolInfo.ERROR_INTERNAL);
    }
}
