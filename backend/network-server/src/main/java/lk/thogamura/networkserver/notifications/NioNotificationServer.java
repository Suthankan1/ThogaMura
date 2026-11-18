package lk.thogamura.networkserver.notifications;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking Java NIO notification server.
 *
 * <p>Implements Member 3's core assessed networking requirements (Issue #18):
 * <ul>
 *   <li>Single {@link Selector} event loop multiplexing multiple client connections.</li>
 *   <li>{@link ServerSocketChannel} for accepting incoming TCP connections in non-blocking mode.</li>
 *   <li>{@link SocketChannel} per connected subscriber in non-blocking mode.</li>
 *   <li>{@link SelectionKey} state management, registering {@code OP_WRITE} strictly when
 *       outbound data is pending to prevent busy-spin polling.</li>
 *   <li>{@link ByteBuffer} streaming for partial write safety and non-blocking I/O.</li>
 *   <li>Bounded per-client outbound queues to prevent slow subscribers from exhausting server memory.</li>
 *   <li>Safe client disconnection and clean server shutdown releasing all channel resources.</li>
 *   <li>Loopback-only binding by default for private demo network safety (Issue #33).</li>
 * </ul>
 * </p>
 */
public class NioNotificationServer implements NotificationPublisher, AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(NioNotificationServer.class);

    private final NotificationServerConfig config;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong lastPublishedVersion = new AtomicLong(0);
    private final AtomicLong rejectedConnections = new AtomicLong(0);

    private ServerSocketChannel serverChannel;
    private Selector selector;
    private Thread eventLoopThread;

    private final Map<SocketChannel, ClientSession> connectedClients = new ConcurrentHashMap<>();
    private final Queue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    public NioNotificationServer(NotificationServerConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    public NioNotificationServer() {
        this(NotificationServerConfig.defaultLocal());
    }

    public NioNotificationServer(int port) {
        this(NotificationServerConfig.ofPort(port));
    }

    public NioNotificationServer(String host, int port) {
        this(NotificationServerConfig.of(host, port));
    }

    /**
     * Starts the non-blocking NIO server and its background selector event loop.
     *
     * @throws IOException if the server channel cannot be opened or bound
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            throw new IllegalStateException("NIO notification server is already running");
        }
        if (closed.get()) {
            throw new IllegalStateException("NIO notification server has been closed and cannot be restarted");
        }

        this.selector = Selector.open();
        this.serverChannel = ServerSocketChannel.open();
        this.serverChannel.configureBlocking(false);

        InetSocketAddress bindAddress = new InetSocketAddress(config.host(), config.port());
        this.serverChannel.bind(bindAddress);
        this.serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        this.running.set(true);
        this.eventLoopThread = new Thread(this::runEventLoop, "nio-notification-eventloop");
        this.eventLoopThread.setDaemon(true);
        this.eventLoopThread.start();

        logger.info("Java NIO notification server started on {}:{} [bindMode={}, allowedClients={}, maxClients={}, maxPendingQueue={}]",
                config.host(), getPort(),
                config.isLoopback() ? "LOOPBACK" : "PRIVATE_LAN",
                config.allowedClientAddresses().isEmpty() ? "[LOOPBACK_ONLY]" : config.allowedClientAddresses(),
                config.maxClients(), config.maxPendingMessagesPerClient());
    }

    /**
     * Publishes a version-only notification to all active subscribers.
     * Thread-safe; can be called from any external thread.
     *
     * @param notification the validated change notification
     */
    @Override
    public void publish(ChangeNotification notification) {
        Objects.requireNonNull(notification, "notification cannot be null");
        if (!running.get()) {
            throw new IllegalStateException("Cannot publish notification: NIO notification server is not running");
        }

        lastPublishedVersion.set(notification.version());
        byte[] bytes = notification.toWireBytes();

        // Enqueue task for selector thread to preserve strict single-thread NIO channel ownership
        pendingTasks.offer(() -> broadcastOnSelectorThread(bytes));

        // Wake up selector in case it is currently blocked in select()
        if (selector != null && selector.isOpen()) {
            selector.wakeup();
        }
    }

    /**
     * Shuts down the NIO notification server, disconnecting all clients and closing channels.
     * Safe to invoke multiple times.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        closed.set(true);
        logger.info("Stopping Java NIO notification server...");

        // Wake up selector so the event loop thread exits
        if (selector != null && selector.isOpen()) {
            selector.wakeup();
        }

        if (eventLoopThread != null && eventLoopThread.isAlive()) {
            try {
                eventLoopThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for selector event loop thread to terminate");
            }
        }

        // Close all connected client channels and cancel selection keys
        for (ClientSession session : connectedClients.values()) {
            try {
                if (session.getSelectionKey() != null) {
                    session.getSelectionKey().cancel();
                }
                if (session.getChannel() != null) {
                    session.getChannel().close();
                }
            } catch (IOException e) {
                logger.debug("Error closing client channel during shutdown: {}", e.getMessage());
            }
        }
        connectedClients.clear();

        // Close the listening server channel
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                serverChannel.close();
            } catch (IOException e) {
                logger.warn("Error closing server socket channel: {}", e.getMessage());
            }
        }

        // Close the selector
        if (selector != null && selector.isOpen()) {
            try {
                selector.close();
            } catch (IOException e) {
                logger.warn("Error closing selector: {}", e.getMessage());
            }
        }

        logger.info("Java NIO notification server stopped successfully");
    }

    @Override
    public void close() {
        stop();
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getConnectedClientCount() {
        return connectedClients.size();
    }

    public long getLastPublishedVersion() {
        return lastPublishedVersion.get();
    }

    public long getRejectedConnectionCount() {
        return rejectedConnections.get();
    }

    public NotificationServerConfig getConfig() {
        return config;
    }

    public String getHost() {
        return config.host();
    }

    public int getPort() {
        if (serverChannel == null || !serverChannel.isOpen()) {
            return config.port();
        }
        try {
            SocketAddress local = serverChannel.getLocalAddress();
            if (local instanceof InetSocketAddress inet) {
                return inet.getPort();
            }
        } catch (IOException ignored) {
        }
        return config.port();
    }

    public InetSocketAddress getBoundAddress() {
        if (serverChannel != null && serverChannel.isOpen()) {
            try {
                SocketAddress local = serverChannel.getLocalAddress();
                if (local instanceof InetSocketAddress inet) {
                    return inet;
                }
            } catch (IOException ignored) {
            }
        }
        return new InetSocketAddress(config.host(), config.port());
    }

    ClientSession getSession(SocketChannel channel) {
        return connectedClients.get(channel);
    }

    Collection<ClientSession> getConnectedSessions() {
        return Collections.unmodifiableCollection(connectedClients.values());
    }

    Selector getSelector() {
        return selector;
    }

    /**
     * Main event loop for the Java NIO Selector.
     * Runs strictly on {@code nio-notification-eventloop} thread.
     */
    private void runEventLoop() {
        logger.info("Java NIO selector event loop started on {}", getBoundAddress());
        ByteBuffer discardBuffer = ByteBuffer.allocate(256);

        while (running.get()) {
            try {
                // Block until an I/O event is ready or selector.wakeup() is invoked
                selector.select();

                if (!running.get()) {
                    break;
                }

                // Process pending publish actions submitted by outside threads
                drainPendingTasks();

                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                if (selectedKeys.isEmpty()) {
                    continue;
                }

                Iterator<SelectionKey> it = selectedKeys.iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (key.isAcceptable()) {
                            handleAccept(key);
                        }
                        if (key.isValid() && key.isWritable()) {
                            handleWrite(key);
                        }
                        if (key.isValid() && key.isReadable()) {
                            handleRead(key, discardBuffer);
                        }
                    } catch (CancelledKeyException e) {
                        disconnectKey(key, "SelectionKey was cancelled");
                    } catch (IOException e) {
                        disconnectKey(key, "I/O error during key handling: " + e.getMessage());
                    } catch (Exception e) {
                        logger.error("Unexpected error handling key for channel: {}", key.channel(), e);
                        disconnectKey(key, "Unexpected error: " + e.getMessage());
                    }
                }
            } catch (ClosedSelectorException e) {
                break;
            } catch (Exception e) {
                if (running.get()) {
                    logger.error("Unexpected exception in NIO selector event loop", e);
                }
            }
        }
        logger.info("Java NIO selector event loop terminated");
    }

    /**
     * Drains tasks submitted to {@link #pendingTasks} by publishing threads.
     */
    private void drainPendingTasks() {
        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("Error executing pending task on NIO selector thread", e);
            }
        }
    }

    /**
     * Accepts a new client connection on the ServerSocketChannel without blocking.
     */
    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = ssc.accept();
        if (clientChannel == null) {
            return;
        }

        SocketAddress remoteAddress = null;
        try {
            remoteAddress = clientChannel.getRemoteAddress();
        } catch (IOException ignored) {
        }

        // Application-level client address restriction (Issue #33)
        if (!config.isClientAllowed(remoteAddress)) {
            rejectedConnections.incrementAndGet();
            logger.warn("Rejected unauthorized NIO notification connection from {}", remoteAddress);
            try {
                clientChannel.close();
            } catch (IOException ignored) {
            }
            return;
        }

        try {
            clientChannel.configureBlocking(false);
            clientChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
            if (config.socketSendBufferSize() > 0) {
                clientChannel.setOption(StandardSocketOptions.SO_SNDBUF, config.socketSendBufferSize());
            }

            if (connectedClients.size() >= config.maxClients()) {
                logger.warn("Max clients limit reached ({}); rejecting connection from {}",
                        config.maxClients(), clientChannel.getRemoteAddress());
                clientChannel.close();
                return;
            }

            ClientSession session = new ClientSession(clientChannel, config.maxPendingMessagesPerClient());
            // Register OP_READ so the selector detects client disconnection (EOF) immediately.
            // OP_WRITE is NOT registered yet because no outbound data is currently pending.
            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ, session);
            session.setSelectionKey(clientKey);
            connectedClients.put(clientChannel, session);

            logger.info("Accepted NIO client connection from {}, active subscribers: {}",
                    session.getRemoteAddress(), connectedClients.size());
        } catch (IOException e) {
            logger.warn("Failed to complete client connection setup: {}", e.getMessage());
            try {
                clientChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Handles OP_WRITE readiness when the socket send buffer can accept outbound data.
     */
    private void handleWrite(SelectionKey key) {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        try {
            boolean allFlushed = session.flush(channel);
            if (allFlushed) {
                // Outbound queue is empty. Disable OP_WRITE to avoid busy-spin polling!
                key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
            }
        } catch (IOException e) {
            logger.debug("Write failed for subscriber {}, disconnecting: {}",
                    session.getRemoteAddress(), e.getMessage());
            disconnectSession(session, "Socket write error: " + e.getMessage());
        }
    }

    /**
     * Handles OP_READ readiness to detect client socket closure (EOF).
     */
    private void handleRead(SelectionKey key, ByteBuffer discardBuffer) {
        ClientSession session = (ClientSession) key.attachment();
        SocketChannel channel = (SocketChannel) key.channel();
        try {
            discardBuffer.clear();
            int bytesRead = channel.read(discardBuffer);
            if (bytesRead == -1) {
                // Client cleanly closed its side of the connection (EOF)
                logger.info("Subscriber {} disconnected (EOF)", session.getRemoteAddress());
                disconnectSession(session, "EOF received");
            } else if (bytesRead > 0) {
                // The notification feed is strictly server-to-client; discard any inbound data
                logger.debug("Discarded {} inbound bytes from notification subscriber {}",
                        bytesRead, session.getRemoteAddress());
            }
        } catch (IOException e) {
            logger.debug("Read error on subscriber {}, disconnecting: {}",
                    session.getRemoteAddress(), e.getMessage());
            disconnectSession(session, "Socket read error: " + e.getMessage());
        }
    }

    /**
     * Broadcasts notification bytes to all connected clients.
     * Executed strictly on the selector thread to maintain single-threaded channel safety.
     */
    private void broadcastOnSelectorThread(byte[] messageBytes) {
        if (connectedClients.isEmpty()) {
            return;
        }

        List<ClientSession> slowClients = new ArrayList<>();

        for (ClientSession session : connectedClients.values()) {
            ByteBuffer buf = ByteBuffer.wrap(messageBytes);
            boolean enqueued = session.enqueue(buf);
            if (!enqueued) {
                // Outbound queue exceeded maxPendingMessagesPerClient -> slow subscriber
                slowClients.add(session);
                continue;
            }

            try {
                boolean flushed = session.flush(session.getChannel());
                SelectionKey key = session.getSelectionKey();
                if (key != null && key.isValid()) {
                    if (!flushed) {
                        // Data remains pending -> register OP_WRITE so selector notifies when ready
                        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                    } else {
                        // All bytes written -> ensure OP_WRITE is cleared
                        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
                    }
                }
            } catch (IOException e) {
                logger.debug("Immediate write failed for subscriber {}: {}",
                        session.getRemoteAddress(), e.getMessage());
                slowClients.add(session);
            }
        }

        // Clean up slow subscribers or broken connections
        for (ClientSession slow : slowClients) {
            logger.warn("Slow subscriber {} exceeded queue bound ({}) or failed write; closing channel",
                    slow.getRemoteAddress(), config.maxPendingMessagesPerClient());
            disconnectSession(slow, "Slow subscriber or write failure");
        }
    }

    /**
     * Disconnects a client session, cancels its selection key, and closes its channel.
     */
    private void disconnectSession(ClientSession session, String reason) {
        if (session == null) {
            return;
        }
        SelectionKey key = session.getSelectionKey();
        if (key != null) {
            key.cancel();
        }
        SocketChannel ch = session.getChannel();
        if (ch != null) {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
            connectedClients.remove(ch);
        }
        session.clear();
        logger.debug("Closed subscriber session for {} (reason: {})", session.getRemoteAddress(), reason);
    }

    /**
     * Helper to disconnect from a SelectionKey when session might need retrieval.
     */
    private void disconnectKey(SelectionKey key, String reason) {
        if (key == null) {
            return;
        }
        key.cancel();
        Object attachment = key.attachment();
        if (attachment instanceof ClientSession session) {
            disconnectSession(session, reason);
        } else if (key.channel() instanceof SocketChannel sc) {
            try {
                sc.close();
            } catch (IOException ignored) {
            }
            connectedClients.remove(sc);
        }
    }
}
