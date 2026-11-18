package lk.thogamura.protocol.tcp;

import java.io.IOException;

/**
 * Thrown when an incoming frame cannot be decoded or parsed according to Protocol v1 rules.
 *
 * <p>Carries the wire error code (e.g. {@code VALIDATION_ERROR}) and optional correlated
 * {@code requestId} if extracted prior to validation failure.</p>
 */
public class MalformedFrameException extends IOException {

    private final String errorCode;
    private final String requestId;

    public MalformedFrameException(String message, String errorCode) {
        this(message, errorCode, null, null);
    }

    public MalformedFrameException(String message, String errorCode, String requestId) {
        this(message, errorCode, requestId, null);
    }

    public MalformedFrameException(String message, String errorCode, String requestId, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.requestId = requestId;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getRequestId() {
        return requestId;
    }
}
