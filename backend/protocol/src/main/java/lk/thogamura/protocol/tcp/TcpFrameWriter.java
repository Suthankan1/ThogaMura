package lk.thogamura.protocol.tcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.protocol.ProtocolInfo;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Reusable streaming encoder for newline-delimited TCP frames over a Java {@link OutputStream}.
 *
 * <p>Enforces character encoding (UTF-8), delimiter termination ({@code \n}), and maximum frame
 * size limit ({@link ProtocolInfo#MAX_FRAME_BYTES}). Ensures immediate flushing over the network.</p>
 */
public class TcpFrameWriter implements AutoCloseable {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final OutputStream out;
    private final int maxFrameBytes;

    public TcpFrameWriter(OutputStream out) {
        this(out, ProtocolInfo.MAX_FRAME_BYTES);
    }

    public TcpFrameWriter(OutputStream out, int maxFrameBytes) {
        this.out = Objects.requireNonNull(out, "out must not be null");
        if (maxFrameBytes <= 0) {
            throw new IllegalArgumentException("maxFrameBytes must be positive: " + maxFrameBytes);
        }
        this.maxFrameBytes = maxFrameBytes;
    }

    /**
     * Writes raw UTF-8 payload bytes followed by a newline delimiter ({@code \n}) and flushes the stream.
     *
     * @param utf8Payload raw bytes of the frame payload (excluding newline delimiter)
     * @throws FrameTooLargeException if the frame size with delimiter exceeds {@code maxFrameBytes}
     * @throws IllegalArgumentException if payload contains internal newline delimiters
     * @throws IOException on network write failures
     */
    public synchronized void writeFrame(byte[] utf8Payload) throws IOException {
        Objects.requireNonNull(utf8Payload, "utf8Payload must not be null");

        // Validate internal delimiters
        for (int i = 0; i < utf8Payload.length; i++) {
            if (utf8Payload[i] == (byte) ProtocolInfo.FRAME_DELIMITER) {
                throw new IllegalArgumentException(
                        "Frame payload contains internal newline delimiter at byte offset " + i
                );
            }
        }

        long totalBytesWithDelimiter = (long) utf8Payload.length + 1;
        if (totalBytesWithDelimiter > maxFrameBytes) {
            throw new FrameTooLargeException(
                    "Outgoing frame of " + totalBytesWithDelimiter
                            + " bytes exceeds maximum allowed limit of " + maxFrameBytes + " bytes",
                    totalBytesWithDelimiter,
                    maxFrameBytes
            );
        }

        out.write(utf8Payload);
        out.write((byte) ProtocolInfo.FRAME_DELIMITER);
        out.flush();
    }

    /**
     * Writes a UTF-8 JSON string followed by a newline delimiter ({@code \n}) and flushes the stream.
     *
     * @param json payload JSON string
     * @throws IOException on framing or network write failures
     */
    public void writeFrame(String json) throws IOException {
        Objects.requireNonNull(json, "json must not be null");
        writeFrame(json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Serializes an arbitrary Java object as compact JSON, appends a newline delimiter, and flushes the stream.
     *
     * @param message POJO or JsonNode to serialize and send
     * @throws IOException on serialization, framing, or network write failures
     */
    public void writeMessage(Object message) throws IOException {
        Objects.requireNonNull(message, "message must not be null");
        try {
            String json = OBJECT_MAPPER.writeValueAsString(message);
            writeFrame(json);
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to serialize message object to JSON: " + e.getMessage(), e);
        }
    }

    public int getMaxFrameBytes() {
        return maxFrameBytes;
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
