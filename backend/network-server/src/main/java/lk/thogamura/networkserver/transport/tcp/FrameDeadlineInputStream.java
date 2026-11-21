package lk.thogamura.networkserver.transport.tcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * FilterInputStream that enforces both an idle socket read timeout and a total
 * frame-assembly deadline on a blocking Java {@link Socket}.
 *
 * <p><b>Idle Timeout vs. Total Frame Deadline:</b>
 * <ul>
 *   <li><b>Idle Read Timeout ({@code SO_TIMEOUT}):</b> The maximum allowed delay between
 *       consecutive network read events. Resets on every byte read from the OS socket buffer.</li>
 *   <li><b>Total Frame Deadline:</b> The maximum wall-clock elapsed time permitted to assemble
 *       a complete newline-delimited ({@code \n}) frame once the first byte of that frame arrives.
 *       Prevents slow-trickle DoS attacks where a client sends 1 byte every (SO_TIMEOUT - 100ms)
 *       to occupy worker threads indefinitely without completing frames.</li>
 * </ul>
 * </p>
 */
public class FrameDeadlineInputStream extends FilterInputStream {

    private final Socket socket;
    private final int idleTimeoutMs;
    private final int frameDeadlineMs;

    private boolean frameInProgress = false;
    private long frameDeadlineNanos = 0L;

    public FrameDeadlineInputStream(InputStream in, Socket socket, int idleTimeoutMs, int frameDeadlineMs) {
        super(Objects.requireNonNull(in, "in must not be null"));
        this.socket = Objects.requireNonNull(socket, "socket must not be null");
        this.idleTimeoutMs = idleTimeoutMs > 0 ? idleTimeoutMs : 5000;
        this.frameDeadlineMs = frameDeadlineMs > 0 ? frameDeadlineMs : 10000;
    }

    @Override
    public int read() throws IOException {
        byte[] single = new byte[1];
        int n = read(single, 0, 1);
        return n == -1 ? -1 : (single[0] & 0xFF);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (frameInProgress) {
            long remainingNanos = frameDeadlineNanos - System.nanoTime();
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
            if (remainingMs <= 0) {
                throw new SocketTimeoutException(
                        "Total frame deadline of " + frameDeadlineMs + "ms exceeded while assembling frame"
                );
            }
            int effectiveTimeout = (int) Math.min(idleTimeoutMs, Math.max(1L, remainingMs));
            socket.setSoTimeout(effectiveTimeout);
        } else {
            socket.setSoTimeout(idleTimeoutMs);
        }

        int bytesRead = super.read(b, off, len);
        if (bytesRead > 0) {
            updateFrameState(b, off, bytesRead);
        }
        return bytesRead;
    }

    private void updateFrameState(byte[] b, int off, int len) {
        int lastNewlineIndex = -1;
        for (int i = off; i < off + len; i++) {
            if (b[i] == (byte) '\n') {
                lastNewlineIndex = i;
            }
        }

        if (lastNewlineIndex != -1) {
            if (lastNewlineIndex == off + len - 1) {
                // Delimiter was the very last byte of this read chunk: frame ended, socket now idle
                frameInProgress = false;
            } else {
                // Additional bytes follow the newline: next frame has already started
                frameInProgress = true;
                frameDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(frameDeadlineMs);
            }
        } else {
            if (!frameInProgress) {
                // First byte of a new frame arrived
                frameInProgress = true;
                frameDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(frameDeadlineMs);
            }
        }
    }

    public boolean isFrameInProgress() {
        return frameInProgress;
    }

    public int getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    public int getFrameDeadlineMs() {
        return frameDeadlineMs;
    }
}
