package lk.thogamura.gateway.sse;

import lk.thogamura.gateway.network.notifications.NotificationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges Java NIO warehouse change notifications to connected HTTP SSE clients.
 *
 * <p>Implements {@link NotificationListener} to receive version notifications from the gateway's
 * Java NIO subscriber. For each valid notification, broadcasts a {@code stock-changed} event
 * containing strictly the version number to all active {@link SseEmitter} subscribers.</p>
 *
 * <p>Key architectural properties:
 * <ul>
 *   <li><b>Non-blocking NIO Thread:</b> Broadcasting is performed asynchronously via a dedicated
 *       bounded thread pool, guaranteeing that slow or stalled SSE clients never block the
 *       Java NIO selector/subscriber reading thread.</li>
 *   <li><b>Fault Isolation:</b> Any client disconnection or write failure (e.g. {@code IOException})
 *       immediately cleans up that specific emitter without interrupting delivery to other clients.</li>
 *   <li><b>Bounded Capacity:</b> Caps maximum concurrent SSE connections (default 100) and
 *       buffers pending invalidations in a bounded queue with oldest-discard policy under saturation.</li>
 *   <li><b>Clean Shutdown:</b> Completes all active emitters and releases executor resources upon
 *       Spring context shutdown.</li>
 * </ul>
 * </p>
 */
@Component
public class SseNotificationBroadcaster implements NotificationListener, DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(SseNotificationBroadcaster.class);

    public static final String EVENT_NAME = "stock-changed";

    private final int maxEmitters;
    private final long emitterTimeoutMs;
    private final ExecutorService executor;
    private final boolean customExecutor;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean(false);

    private final AtomicLong broadcastCount = new AtomicLong(0);
    private final AtomicLong lastBroadcastVersion = new AtomicLong(-1);

    @Autowired
    public SseNotificationBroadcaster(
            @Value("${thogamura.notifications.sse.max-emitters:100}") int maxEmitters,
            @Value("${thogamura.notifications.sse.emitter-timeout-ms:0}") long emitterTimeoutMs,
            @Value("${thogamura.notifications.sse.queue-capacity:256}") int queueCapacity) {
        this.maxEmitters = Math.max(1, maxEmitters);
        this.emitterTimeoutMs = emitterTimeoutMs;
        this.customExecutor = false;

        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "sse-broadcaster-" + counter.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };

        this.executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(16, queueCapacity)),
                threadFactory,
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
    }

    /**
     * Test-accessible constructor permitting dependency injection of a custom executor.
     */
    public SseNotificationBroadcaster(int maxEmitters, long emitterTimeoutMs, ExecutorService executor) {
        this.maxEmitters = Math.max(1, maxEmitters);
        this.emitterTimeoutMs = emitterTimeoutMs;
        this.executor = Objects.requireNonNull(executor, "executor cannot be null");
        this.customExecutor = true;
    }

    /**
     * Registers a new client connection, allocating an {@link SseEmitter} with the configured timeout.
     *
     * @return a newly registered, active {@link SseEmitter}
     * @throws ResponseStatusException if the broadcaster is shutting down or maximum capacity is reached
     */
    public SseEmitter registerEmitter() {
        SseEmitter emitter = (emitterTimeoutMs > 0) ? new SseEmitter(emitterTimeoutMs) : new SseEmitter(0L);
        registerEmitter(emitter);
        return emitter;
    }

    /**
     * Registers an existing {@link SseEmitter} instance and attaches lifecycle callbacks.
     *
     * @param emitter the emitter to register
     * @throws ResponseStatusException if the broadcaster is shutting down or maximum capacity is reached
     */
    public void registerEmitter(SseEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter cannot be null");
        if (isShutdown.get()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SSE broadcaster is shutting down");
        }
        if (emitters.size() >= maxEmitters) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Maximum SSE client capacity reached (" + maxEmitters + ")");
        }

        emitters.add(emitter);
        logger.debug("Registered new SSE client. Active clients: {}", emitters.size());

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> {
            removeEmitter(emitter);
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        });
        emitter.onError(e -> {
            removeEmitter(emitter);
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * Removes an emitter from the active subscriber set.
     */
    public void removeEmitter(SseEmitter emitter) {
        if (emitter != null && emitters.remove(emitter)) {
            logger.debug("Removed SSE client. Active clients: {}", emitters.size());
        }
    }

    /**
     * Consumes valid version notifications from the Java NIO subscriber.
     *
     * <p>Submits broadcasting work asynchronously to prevent blocking the NIO thread.</p>
     *
     * @param version the authoritative warehouse change version
     */
    @Override
    public void onNotification(long version) {
        if (version < 0) {
            logger.warn("Discarding invalid negative notification version: {}", version);
            return;
        }
        if (isShutdown.get()) {
            logger.debug("Broadcaster is shut down, ignoring notification for version {}", version);
            return;
        }
        try {
            executor.execute(() -> doBroadcast(version));
        } catch (Exception e) {
            logger.warn("Failed to dispatch SSE broadcast for version {}: {}", version, e.getMessage());
        }
    }

    /**
     * Performs synchronous iteration and delivery of the version event to all active emitters.
     *
     * <p>Package-private to facilitate deterministic testing without background scheduling.</p>
     */
    void doBroadcast(long version) {
        if (emitters.isEmpty()) {
            lastBroadcastVersion.set(version);
            broadcastCount.incrementAndGet();
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        String versionStr = Long.toString(version);

        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(EVENT_NAME)
                        .data(versionStr));
            } catch (Exception ex) {
                logger.debug("Failed to deliver SSE event to client (marking for removal): {}", ex.getMessage());
                deadEmitters.add(emitter);
                try {
                    emitter.completeWithError(ex);
                } catch (Exception ignored) {
                }
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            logger.debug("Cleaned up {} dead SSE emitters. Active clients: {}", deadEmitters.size(), emitters.size());
        }

        lastBroadcastVersion.set(version);
        broadcastCount.incrementAndGet();
        logger.debug("Broadcast stock-changed version={} to {} active clients", version, emitters.size());
    }

    /**
     * Completes all active emitters and shuts down the broadcast executor.
     */
    @Override
    public void destroy() {
        if (isShutdown.compareAndSet(false, true)) {
            logger.info("Shutting down SseNotificationBroadcaster...");
            closeAll();
            if (!customExecutor) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            logger.info("SseNotificationBroadcaster shut down successfully");
        }
    }

    /**
     * Completes and clears all active emitters.
     */
    public void closeAll() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        emitters.clear();
    }

    public int getActiveEmitterCount() {
        return emitters.size();
    }

    public long getBroadcastCount() {
        return broadcastCount.get();
    }

    public long getLastBroadcastVersion() {
        return lastBroadcastVersion.get();
    }

    public boolean isShutdown() {
        return isShutdown.get();
    }

    public int getMaxEmitters() {
        return maxEmitters;
    }

    public long getEmitterTimeoutMs() {
        return emitterTimeoutMs;
    }
}
