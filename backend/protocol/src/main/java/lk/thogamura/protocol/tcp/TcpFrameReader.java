package lk.thogamura.protocol.tcp;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

/**
 * Reusable streaming decoder for newline-delimited TCP frames over a Java {@link InputStream}.
 *
 * <p>Handles TCP stream fragmentation (partial reads), message coalescing (multiple frames in
 * a single read), and strict memory bounding (enforcing {@link ProtocolInfo#MAX_FRAME_BYTES}).
 * Strips optional carriage return ({@code \r}) preceding the newline delimiter ({@code \n}).</p>
 */
public class TcpFrameReader implements AutoCloseable {

    public static final int DEFAULT_BUFFER_SIZE = 8192;

    private final InputStream in;
    private final int maxFrameBytes;
    private final byte[] readBuffer;
    private final ByteArrayOutputStream frameAccumulator;

    private int readBufferPos = 0;
    private int readBufferLimit = 0;
    private boolean eof = false;

    public TcpFrameReader(InputStream in) {
        this(in, ProtocolInfo.MAX_FRAME_BYTES, DEFAULT_BUFFER_SIZE);
    }

    public TcpFrameReader(InputStream in, int maxFrameBytes) {
        this(in, maxFrameBytes, DEFAULT_BUFFER_SIZE);
    }

    public TcpFrameReader(InputStream in, int maxFrameBytes, int bufferSize) {
        this.in = Objects.requireNonNull(in, "in must not be null");
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive: " + maxFrameBytes);
        }
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("bufferSize must be positive: " + bufferSize);
        }
        this.maxFrameBytes = maxFrameBytes;
        this.readBuffer = new byte[bufferSize];
        this.frameAccumulator = new ByteArrayOutputStream(Math.min(bufferSize, maxFrameBytes));
    }

    /**
     * Reads the next complete frame from the stream as raw bytes, stripped of delimiter and
     * optional carriage return.
     *
     * @return raw frame byte array, or {@code null} if the stream reached clean EOF at a message boundary
     * @throws FrameTooLargeException if the frame exceeds {@code maxFrameBytes} without encountering a newline
     * @throws TruncatedFrameException if the stream terminates unexpectedly with un-delimited bytes
     * @throws IOException on network I/O failures
     */
    public byte[] readNextFrameBytes() throws IOException {
        while (true) {
            // If the local read buffer is exhausted, refill it from the network
            if (readBufferPos >= readBufferLimit) {
                if (eof) {
                    if (frameAccumulator.size() > 0) {
                        int truncated = frameAccumulator.size();
                        frameAccumulator.reset();
                        throw new TruncatedFrameException(
                                "Stream terminated prematurely: accumulated " + truncated
                                        + " bytes without a trailing newline delimiter",
                                truncated
                        );
                    }
                    return null;
                }

                int read = in.read(readBuffer);
                if (read == -1) {
                    eof = true;
                    continue;
                }
                readBufferPos = 0;
                readBufferLimit = read;
            }

            // Scan readBuffer from readBufferPos for newline
            int nlIndex = -1;
            for (int i = readBufferPos; i < readBufferLimit; i++) {
                if (readBuffer[i] == (byte) ProtocolInfo.FRAME_DELIMITER) {
                    nlIndex = i;
                    break;
                }
            }

            if (nlIndex != -1) {
                int segmentLen = nlIndex - readBufferPos;
                long totalSizeWithDelimiter = (long) frameAccumulator.size() + segmentLen + 1;

                if (totalSizeWithDelimiter > maxFrameBytes) {
                    frameAccumulator.reset();
                    readBufferPos = nlIndex + 1; // skip past delimiter
                    throw new FrameTooLargeException(
                            "Incoming frame of " + totalSizeWithDelimiter
                                    + " bytes exceeds maximum allowed limit of " + maxFrameBytes + " bytes",
                            totalSizeWithDelimiter,
                            maxFrameBytes
                    );
                }

                frameAccumulator.write(readBuffer, readBufferPos, segmentLen);
                readBufferPos = nlIndex + 1; // advance past '\n'

                byte[] raw = frameAccumulator.toByteArray();
                frameAccumulator.reset();

                // Strip optional preceding '\r'
                if (raw.length > 0 && raw[raw.length - 1] == (byte) '\r') {
                    raw = Arrays.copyOf(raw, raw.length - 1);
                }
                return raw;
            } else {
                // Newline not in current buffer slice; accumulate segment
                int segmentLen = readBufferLimit - readBufferPos;
                long projectedSize = (long) frameAccumulator.size() + segmentLen;

                if (projectedSize > maxFrameBytes) {
                    frameAccumulator.reset();
                    readBufferPos = readBufferLimit; // discard remaining buffer
                    throw new FrameTooLargeException(
                            "Incoming frame exceeded maximum allowed limit of "
                                    + maxFrameBytes + " bytes without a newline delimiter",
                            projectedSize,
                            maxFrameBytes
                    );
                }

                frameAccumulator.write(readBuffer, readBufferPos, segmentLen);
                readBufferPos = readBufferLimit;
            }
        }
    }

    /**
     * Reads the next frame decoded as strict UTF-8.
     *
     * @return decoded frame string, or {@code null} on clean EOF
     * @throws MalformedFrameException if the frame contains invalid UTF-8 byte sequences
     * @throws IOException on framing or network errors
     */
    public String readNextFrameString() throws IOException {
        byte[] bytes = readNextFrameBytes();
        if (bytes == null) {
            return null;
        }
        return TcpMessageCodec.decodeStrictUtf8(bytes);
    }

    /**
     * Reads the next frame, strictly decoded as UTF-8, and parsed into a JSON root object.
     *
     * @return parsed Jackson {@link JsonNode}, or {@code null} on clean EOF
     * @throws MalformedFrameException on unparseable JSON or non-object root
     * @throws IOException on framing or network errors
     */
    public JsonNode readNextFrameJson() throws IOException {
        String json = readNextFrameString();
        if (json == null) {
            return null;
        }
        return TcpMessageCodec.parseJsonObject(json);
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
