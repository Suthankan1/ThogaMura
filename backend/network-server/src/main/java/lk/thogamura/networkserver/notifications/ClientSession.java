package lk.thogamura.networkserver.notifications;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.channels.WritableByteChannel;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages per-client state for a connected notification subscriber.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Maintains a bounded outbound queue of {@link ByteBuffer} instances.</li>
 *   <li>Protects server memory by detecting slow subscribers who fail to drain their queue.</li>
 *   <li>Correctly handles partial socket writes by keeping partially written buffers at the head
 *       of the queue until all bytes are transmitted.</li>
 *   <li>Tracks whether pending data requires {@code SelectionKey.OP_WRITE} to be registered.</li>
 * </ul>
 * </p>
 *
 * <p>All operations on this session are confined to the single NIO selector event loop thread.</p>
 */
public class ClientSession {
    private final SocketChannel channel;
    private final SocketAddress remoteAddress;
    private final int maxPendingMessages;
    private final Deque<ByteBuffer> outboundQueue = new ArrayDeque<>();
    private SelectionKey selectionKey;

    public ClientSession(SocketChannel channel, int maxPendingMessages) {
        this.channel = channel;
        this.maxPendingMessages = maxPendingMessages;
        SocketAddress addr = null;
        if (channel != null) {
            try {
                addr = channel.getRemoteAddress();
            } catch (IOException ignored) {
            }
        }
        this.remoteAddress = addr;
    }

    public SocketChannel getChannel() {
        return channel;
    }

    public SocketAddress getRemoteAddress() {
        return remoteAddress;
    }

    public SelectionKey getSelectionKey() {
        return selectionKey;
    }

    public void setSelectionKey(SelectionKey selectionKey) {
        this.selectionKey = selectionKey;
    }

    public int getPendingMessageCount() {
        return outboundQueue.size();
    }

    public int getMaxPendingMessages() {
        return maxPendingMessages;
    }

    public boolean hasPendingData() {
        return !outboundQueue.isEmpty();
    }

    /**
     * Enqueues a notification byte buffer for this client.
     *
     * @param buffer the buffer containing wire-encoded notification bytes
     * @return true if successfully enqueued; false if the client's outbound queue is full
     */
    public boolean enqueue(ByteBuffer buffer) {
        if (outboundQueue.size() >= maxPendingMessages) {
            return false;
        }
        outboundQueue.offer(buffer);
        return true;
    }

    /**
     * Attempts to write as much pending data to the channel as the OS socket send buffer will accept.
     *
     * <p>If a buffer cannot be fully written in one pass (a partial write), it remains at the head
     * of the queue with its {@code position} advanced, and this method returns {@code false}.</p>
     *
     * @param targetChannel the channel to write to
     * @return true if all pending buffers were completely transmitted; false if pending bytes remain
     * @throws IOException if a low-level socket error occurs
     */
    public boolean flush(WritableByteChannel targetChannel) throws IOException {
        while (!outboundQueue.isEmpty()) {
            ByteBuffer head = outboundQueue.peek();
            int written = targetChannel.write(head);
            if (written < 0) {
                throw new IOException("Socket write returned negative bytes: " + written);
            }
            if (head.hasRemaining()) {
                // Partial write: the socket send buffer is full; keep head and resume later.
                return false;
            }
            // Buffer was completely transmitted; remove from queue.
            outboundQueue.poll();
        }
        return true;
    }

    /**
     * Clears all pending buffers.
     */
    public void clear() {
        outboundQueue.clear();
    }

    @Override
    public String toString() {
        return "ClientSession{" +
                "remoteAddress=" + remoteAddress +
                ", pendingMessages=" + outboundQueue.size() + "/" + maxPendingMessages +
                '}';
    }
}
