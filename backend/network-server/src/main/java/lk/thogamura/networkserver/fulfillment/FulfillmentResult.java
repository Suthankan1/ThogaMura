package lk.thogamura.networkserver.fulfillment;

import java.util.Objects;
import java.util.UUID;

/**
 * Result value object representing the outcome of a fulfillment command operation.
 */
public record FulfillmentResult(
        UUID commandRequestId,
        UUID stockRequestId,
        String status,
        boolean replayed,
        String responseJson,
        String errorCode,
        String errorMessage
) {
    public FulfillmentResult {
        Objects.requireNonNull(commandRequestId, "commandRequestId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(responseJson, "responseJson must not be null");
    }

    public boolean isSuccess() {
        return errorCode == null;
    }

    public boolean isError() {
        return errorCode != null;
    }

    public static FulfillmentResult success(
            UUID commandRequestId,
            UUID stockRequestId,
            String status,
            boolean replayed,
            String responseJson
    ) {
        return new FulfillmentResult(commandRequestId, stockRequestId, status, replayed, responseJson, null, null);
    }

    public static FulfillmentResult error(
            UUID commandRequestId,
            UUID stockRequestId,
            String errorCode,
            String errorMessage,
            String errorJson
    ) {
        return new FulfillmentResult(commandRequestId, stockRequestId, "ERROR", false, errorJson, errorCode, errorMessage);
    }
}
