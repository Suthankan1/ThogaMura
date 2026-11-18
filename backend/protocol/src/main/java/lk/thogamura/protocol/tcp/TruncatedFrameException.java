package lk.thogamura.protocol.tcp;

import java.io.EOFException;

/**
 * Thrown when an input stream terminates unexpectedly while in the middle of reading a frame.
 */
public class TruncatedFrameException extends EOFException {

    private final int accumulatedBytes;

    public TruncatedFrameException(String message, int accumulatedBytes) {
        super(message);
        this.accumulatedBytes = accumulatedBytes;
    }

    public int getAccumulatedBytes() {
        return accumulatedBytes;
    }
}
