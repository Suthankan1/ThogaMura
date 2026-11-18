package lk.thogamura.gateway.network.notifications;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Decodes newline-delimited frames from incoming ByteBuffers.
 *
 * <p>Handles:
 * <ul>
 *   <li>Split/fragmented TCP reads: accumulates incomplete frame bytes across read calls.</li>
 *   <li>Multiple frames in a single read: emits each frame sequentially.</li>
 *   <li>Carriage return handling: supports both LF (\\n) and CRLF (\\r\\n).</li>
 *   <li>Maximum frame length enforcement: rejects oversized frames to prevent memory exhaustion.</li>
 * </ul>
 * </p>
 */
public class NotificationFrameDecoder {
    private final int maxFrameBytes;
    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream();

    public NotificationFrameDecoder(int maxFrameBytes) {
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive: " + maxFrameBytes);
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Consumes bytes from the given ByteBuffer and emits complete, trimmed lines.
     * The input buffer is assumed to be prepared for reading (flipped).
     *
     * @param buffer the buffer containing newly read bytes
     * @param frameConsumer consumer called for each complete frame
     * @throws FrameTooLargeException if a frame exceeds maxFrameBytes without a newline
     */
    public void decode(ByteBuffer buffer, Consumer<String> frameConsumer) throws FrameTooLargeException {
        Objects.requireNonNull(buffer, "buffer cannot be null");
        Objects.requireNonNull(frameConsumer, "frameConsumer cannot be null");

        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b == '\n') {
                byte[] raw = accumulator.toByteArray();
                accumulator.reset();
                String line = new String(raw, StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) {
                    frameConsumer.accept(line);
                }
            } else if (b != '\r') {
                if (accumulator.size() >= maxFrameBytes) {
                    accumulator.reset();
                    throw new FrameTooLargeException("Frame size exceeded limit of " + maxFrameBytes + " bytes");
                }
                accumulator.write(b);
            }
        }
    }

    /**
     * Resets the internal accumulator (e.g. upon reconnection).
     */
    public void reset() {
        accumulator.reset();
    }

    /**
     * Returns the number of bytes currently accumulated waiting for a newline delimiter.
     */
    public int getAccumulatedBytes() {
        return accumulator.size();
    }
}
