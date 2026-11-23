package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;

final class WarehouseHttpErrors {
    private WarehouseHttpErrors() {
    }

    static ResponseEntity<Map<String, String>> invalidData() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "code", "INVALID_WAREHOUSE_RESPONSE",
                "message", "Warehouse returned incomplete or inconsistent data."
        ));
    }

    static boolean hasText(JsonNode row, String field) {
        return row.path(field).isTextual() && !row.path(field).textValue().isBlank();
    }

    static ResponseEntity<Map<String, String>> from(JsonNode response, String referenceName, String reference) {
        JsonNode error = response.path("error");
        String code = error.path("code").asText(ProtocolInfo.ERROR_INTERNAL);
        HttpStatus status = switch (code) {
            case ProtocolInfo.ERROR_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ProtocolInfo.ERROR_VALIDATION, ProtocolInfo.ERROR_UNSUPPORTED_VERSION -> HttpStatus.BAD_REQUEST;
            case ProtocolInfo.ERROR_INSUFFICIENT_STOCK, ProtocolInfo.ERROR_INVALID_TRANSITION,
                    ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT -> HttpStatus.CONFLICT;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String message = status.is5xxServerError()
                ? "Warehouse could not confirm the command outcome."
                : error.path("message").asText("Warehouse did not accept the command.");
        return ResponseEntity.status(status).body(Map.of(
                "code", status.is5xxServerError() ? ProtocolInfo.ERROR_INTERNAL : code,
                "message", message,
                referenceName, reference
        ));
    }
}
