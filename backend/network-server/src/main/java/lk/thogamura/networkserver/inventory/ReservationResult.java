package lk.thogamura.networkserver.inventory;

import lk.thogamura.networkserver.persistence.ProductStock;
import lk.thogamura.networkserver.persistence.StockRequestRecord;
import lk.thogamura.protocol.ProtocolInfo;

import java.util.Objects;
import java.util.UUID;

/**
 * Server-authoritative result of a stock reservation command.
 */
public record ReservationResult(
        UUID requestId,
        String status,
        boolean replayed,
        String responseJson,
        String errorCode,
        String errorMessage,
        StockRequestRecord requestRecord,
        ProductStock productStock
) {
    public ReservationResult {
        Objects.requireNonNull(requestId, "requestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(responseJson, "responseJson must not be null");
    }

    public boolean isAccepted() {
        return ProtocolInfo.STATUS_ACCEPTED.equals(status);
    }

    public boolean isRejected() {
        return ProtocolInfo.STATUS_REJECTED.equals(status);
    }

    public boolean isError() {
        return ProtocolInfo.STATUS_ERROR.equals(status);
    }

    public boolean isConflict() {
        return ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT.equals(errorCode);
    }

    public static ReservationResult accepted(
            UUID requestId,
            ProductStock stock,
            StockRequestRecord record,
            boolean replayed,
            String responseJson
    ) {
        return new ReservationResult(
                requestId,
                ProtocolInfo.STATUS_ACCEPTED,
                replayed,
                responseJson,
                null,
                null,
                record,
                stock
        );
    }

    public static ReservationResult rejected(
            UUID requestId,
            String errorCode,
            String errorMessage,
            StockRequestRecord record,
            boolean replayed,
            String responseJson
    ) {
        return new ReservationResult(
                requestId,
                ProtocolInfo.STATUS_REJECTED,
                replayed,
                responseJson,
                errorCode,
                errorMessage,
                record,
                null
        );
    }

    public static ReservationResult conflict(UUID requestId, String message, String responseJson) {
        return new ReservationResult(
                requestId,
                ProtocolInfo.STATUS_ERROR,
                false,
                responseJson,
                ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT,
                message,
                null,
                null
        );
    }

    public static ReservationResult notFound(UUID requestId, String message, String responseJson) {
        return new ReservationResult(
                requestId,
                ProtocolInfo.STATUS_ERROR,
                false,
                responseJson,
                ProtocolInfo.ERROR_NOT_FOUND,
                message,
                null,
                null
        );
    }

    public static ReservationResult validationError(UUID requestId, String message, String responseJson) {
        return new ReservationResult(
                requestId,
                ProtocolInfo.STATUS_ERROR,
                false,
                responseJson,
                ProtocolInfo.ERROR_VALIDATION,
                message,
                null,
                null
        );
    }
}
