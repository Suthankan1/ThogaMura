package lk.thogamura.gateway.network.notifications;

import jdk.net.ExtendedSocketOptions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Gateway-side Java NIO client subscriber connecting to the central warehouse notification server.
 *
 * <p>Demonstrates Member 3's client-side Java NIO concepts (Issue #19A):
 * <ul>
 *   <li>{@link SocketChannel} non-blocking capable TCP connection to the central warehouse feed.</li>
 *   <li>{@link ByteBuffer} streaming I/O with partial read assembly and newline frame decoding.</li>
 *   <li>Automatic reconnection with bounded exponential backoff on network failures.</li>
 *   <li>Decoupled application notification listener callbacks (for future SSE invalidation).</li>
 *   <li>Graceful shutdown releasing channel resources without hanging.</li>
 * </ul>
 * </p>
 */
public class GatewayNioSubscriber implements AutoCloseable {
    private static final Logger logger = LoggerFactory.getLogger(GatewayNioSubscriber.class);

    private final NotificationSubscriberConfig config;
    private final List<NotificationListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object shutdownLock = new Object();

    private volatile SubscriberStatus status = SubscriberStatus.DISCONNECTED;
    private volatile SocketChannel currentChannel;
    private volatile Selector currentSelector;
    private Thread subscriberThread;

    // Metrics & Observable State
    private final AtomicLong lastReceivedVersion = new AtomicLong(-1);
    private final AtomicLong lastNotificationTimeMs = new AtomicLong(0);
    private final AtomicInteger notificationCount = new AtomicInteger(0);
    private final AtomicInteger reconnectCount = new AtomicInteger(0);

    public GatewayNioSubscriber(NotificationSubscriberConfig config) {
        this.config = Objects.requireNonNull(config, "config cannot be null");
    }

    public GatewayNioSubscriber() {
        this(NotificationSubscriberConfig.defaultLocal());
    }

    public GatewayNioSubscriber(int port) {
        this(NotificationSubscriberConfig.ofPort(port));
    }

    public GatewayNioSubscriber(String host, int port) {
        this(NotificationSubscriberConfig.of(host, port));
    }

    /**
     * Starts the background subscriber loop thread.
     */
    public synchronized void start() {
        if (running.get()) {
            throw new IllegalStateException("Gateway NIO subscriber is already running");
        }
        running.set(true);
        subscriberThread = new Thread(this::runSubscriberLoop, "gateway-nio-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        logger.info("Gateway NIO subscriber started for target {}:{}", config.host(), config.port());
    }

    /**
     * Stops the subscriber, closes open channels, and terminates background threads cleanly.
     */
    public synchronized void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        updateStatus(SubscriberStatus.STOPPED);
        closeChannel();

        synchronized (shutdownLock) {
            shutdownLock.notifyAll();
        }

        if (subscriberThread != null && subscriberThread.isAlive()) {
            subscriberThread.interrupt();
            try {
                subscriberThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while waiting for subscriber thread to finish");
            }
        }
        logger.info("Gateway NIO subscriber stopped successfully");
    }

    @Override
    public void close() {
        stop();
    }

    public void addListener(NotificationListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(NotificationListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    public SubscriberStatus getStatus() {
        return status;
    }

    public boolean isConnected() {
        return status == SubscriberStatus.CONNECTED;
    }

    public boolean isRunning() {
        return running.get();
    }

    public long getLastReceivedVersion() {
        return lastReceivedVersion.get();
    }

    public long getLastNotificationTimeMs() {
        return lastNotificationTimeMs.get();
    }

    public int getNotificationCount() {
        return notificationCount.get();
    }

    public int getReconnectCount() {
        return reconnectCount.get();
    }

    public NotificationSubscriberConfig getConfig() {
        return config;
    }

    /**
     * Main background loop managing connection, read streaming, and exponential backoff.
     */
    private void runSubscriberLoop() {
        Duration currentBackoff = config.initialBackoff();

        while (running.get()) {
            try {
                updateStatus(SubscriberStatus.CONNECTING);
                logger.info("Connecting to warehouse NIO notification feed at {}:{}...",
                        config.host(), config.port());

                Selector selector = Selector.open();
                this.currentSelector = selector;
                SocketChannel channel = SocketChannel.open();
                this.currentChannel = channel;
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
                setIfSupported(channel, ExtendedSocketOptions.TCP_KEEPIDLE, 10);
                setIfSupported(channel, ExtendedSocketOptions.TCP_KEEPINTERVAL, 2);
                setIfSupported(channel, ExtendedSocketOptions.TCP_KEEPCOUNT, 3);

                boolean connectedImmediately = channel.connect(new InetSocketAddress(config.host(), config.port()));
                if (!connectedImmediately) {
                    channel.register(selector, SelectionKey.OP_CONNECT);
                    long deadlineNanos = System.nanoTime() + config.connectTimeout().toNanos();
                    while (running.get() && !channel.isConnected()) {
                        long remainingMs = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()));
                        if (deadlineNanos - System.nanoTime() <= 0) {
                            throw new SocketTimeoutException("NIO connect timed out");
                        }
                        selector.select(remainingMs);
                        Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                        while (keys.hasNext()) {
                            SelectionKey key = keys.next();
                            keys.remove();
                            if (key.isValid() && key.isConnectable() && channel.finishConnect()) {
                                key.interestOps(SelectionKey.OP_READ);
                            }
                        }
                    }
                } else {
                    channel.register(selector, SelectionKey.OP_READ);
                }

                if (!running.get()) {
                    break;
                }

                updateStatus(SubscriberStatus.CONNECTED);
                logger.info("Connected to warehouse NIO notification feed at {}:{}",
                        config.host(), config.port());

                // Reset backoff upon successful connection
                currentBackoff = config.initialBackoff();

                ByteBuffer buffer = ByteBuffer.allocate(Math.min(config.maxFrameBytes(), 8192));
                NotificationFrameDecoder decoder = new NotificationFrameDecoder(config.maxFrameBytes());

                while (running.get()) {
                    selector.select();
                    if (!running.get()) {
                        break;
                    }
                    Iterator<SelectionKey> keys = selector.selectedKeys().iterator();
                    while (keys.hasNext()) {
                        SelectionKey key = keys.next();
                        keys.remove();
                        if (!key.isValid() || !key.isReadable()) {
                            continue;
                        }
                        int bytesRead = channel.read(buffer);
                        if (bytesRead == -1) {
                            throw new EOFException("Warehouse server closed notification channel (EOF)");
                        }
                        if (bytesRead > 0) {
                            buffer.flip();
                            decoder.decode(buffer, this::handleRawFrame);
                            buffer.compact();
                        }
                    }
                }
            } catch (ClosedByInterruptException e) {
                // Thread interruption during normal shutdown
                break;
            } catch (AsynchronousCloseException e) {
                // Channel closed by stop()
                if (!running.get()) {
                    break;
                }
                handleConnectionFailure(e, currentBackoff);
                currentBackoff = computeNextBackoff(currentBackoff);
            } catch (Exception e) {
                if (!running.get()) {
                    break;
                }
                handleConnectionFailure(e, currentBackoff);
                currentBackoff = computeNextBackoff(currentBackoff);
            } finally {
                closeChannel();
            }
        }
        updateStatus(SubscriberStatus.STOPPED);
        logger.info("Gateway NIO subscriber loop terminated");
    }

    private static void setIfSupported(SocketChannel channel, SocketOption<Integer> option, int value)
            throws IOException {
        if (channel.supportedOptions().contains(option)) channel.setOption(option, value);
    }

    private void handleRawFrame(String line) {
        try {
            ChangeNotification notification = ChangeNotification.parse(line);
            long version = notification.version();

            lastReceivedVersion.set(version);
            lastNotificationTimeMs.set(System.currentTimeMillis());
            notificationCount.incrementAndGet();

            logger.debug("Received warehouse change notification: version={}", version);
            notifyListeners(version);
        } catch (IllegalArgumentException e) {
            logger.warn("Discarding invalid or unauthorized notification frame '{}': {}", line, e.getMessage());
        }
    }

    private void notifyListeners(long version) {
        for (NotificationListener listener : listeners) {
            try {
                listener.onNotification(version);
            } catch (Exception e) {
                logger.error("Error invoking notification listener for version {}", version, e);
            }
        }
    }

    private void handleConnectionFailure(Exception e, Duration backoff) {
        reconnectCount.incrementAndGet();
        updateStatus(SubscriberStatus.RECONNECTING);
        logger.warn("NIO notification feed connection dropped or failed: {}. Retrying in {}ms...",
                e.getMessage(), backoff.toMillis());
        closeChannel();
        sleepWithBackoff(backoff);
    }

    private Duration computeNextBackoff(Duration current) {
        long nextMs = (long) (current.toMillis() * config.backoffMultiplier());
        long maxMs = config.maxBackoff().toMillis();
        return Duration.ofMillis(Math.min(nextMs, maxMs));
    }

    private void sleepWithBackoff(Duration duration) {
        synchronized (shutdownLock) {
            if (!running.get()) {
                return;
            }
            try {
                shutdownLock.wait(duration.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void closeChannel() {
        Selector sel = this.currentSelector;
        this.currentSelector = null;
        if (sel != null && sel.isOpen()) {
            try {
                sel.wakeup();
                sel.close();
            } catch (IOException ignored) {
            }
        }
        SocketChannel ch = this.currentChannel;
        this.currentChannel = null;
        if (ch != null && ch.isOpen()) {
            try {
                ch.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void updateStatus(SubscriberStatus newStatus) {
        this.status = newStatus;
    }
}
