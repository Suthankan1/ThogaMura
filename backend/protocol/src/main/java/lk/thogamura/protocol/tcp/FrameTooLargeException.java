package lk.thogamura.protocol.tcp;

import java.io.IOException;

/**
 * Thrown when an incoming frame exceeds the maximum allowed size before encountering a delimiter.
 *
 * <p>Enforces the frozen 65,536-byte limit defined in {@code docs/contracts/NETWORK_PROTOCOL_V1.md}.
 * Prevents denial-of-service and memory exhaustion attacks from malicious or buggy clients.</p>
 */
public class FrameTooLargeException extends IOException {

    private final long bytesRead;
    private final int maxAllowed;

    public FrameTooLargeException(String message, long bytesRead, int maxAllowed) {
        super(message);
        this.bytesRead = bytesRead;
        this.maxAllowed = maxAllowed;
    }

    public long getBytesRead() {
        return bytesRead;
    }

    public int getMaxAllowed() {
        return maxAllowed;
    }
}
