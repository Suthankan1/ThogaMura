package lk.thogamura.gateway.sse;

import lk.thogamura.gateway.network.notifications.NotificationListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Delivers version-only invalidations without doing HTTP writes on the NIO subscriber thread. */
@Component
public class SseNotificationBroadcaster implements NotificationListener, DisposableBean {
    public static final String EVENT_NAME = "stock-changed";
    public static final String CONNECTED_EVENT_NAME = "connected";
    private static final long HEARTBEAT = -1;

    private static final Logger logger = LoggerFactory.getLogger(SseNotificationBroadcaster.class);

    private final int maxEmitters;
    private final long emitterTimeoutMs;
    private final ExecutorService writers;
    private final ScheduledExecutorService heartbeatScheduler;
    private final Map<SseEmitter, ClientWriter> emitters = new ConcurrentHashMap<>();
    private final AtomicBoolean isShutdown = new AtomicBoolean();
    private final AtomicLong broadcastCount = new AtomicLong();
    private final AtomicLong lastBroadcastVersion = new AtomicLong(-1);

    public SseNotificationBroadcaster(
            @Value("${thogamura.notifications.sse.max-emitters:100}") int maxEmitters,
            @Value("${thogamura.notifications.sse.emitter-timeout-ms:0}") long emitterTimeoutMs,
            @Value("${thogamura.notifications.sse.queue-capacity:256}") int queueCapacity) {
        this.maxEmitters = Math.max(1, maxEmitters);
        this.emitterTimeoutMs = Math.max(0, emitterTimeoutMs);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                this.maxEmitters, this.maxEmitters, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(Math.max(1, queueCapacity)),
                Thread.ofPlatform().daemon().name("sse-writer-", 0).factory()
        );
        pool.allowCoreThreadTimeOut(true);
        this.writers = pool;
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon().name("sse-heartbeat").factory());
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 15, 15, TimeUnit.SECONDS);
    }

    public SseEmitter registerEmitter() {
        SseEmitter emitter = new SseEmitter(emitterTimeoutMs);
        registerEmitter(emitter);
        return emitter;
    }

    public synchronized void registerEmitter(SseEmitter emitter) {
        Objects.requireNonNull(emitter, "emitter cannot be null");
        if (isShutdown.get()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "SSE broadcaster is shutting down");
        }
        if (emitters.size() >= maxEmitters) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Maximum SSE client capacity reached (" + maxEmitters + ")");
        }

        emitter.onCompletion(() -> removeEmitter(emitter));
        emitter.onTimeout(() -> {
            removeEmitter(emitter);
            finish(emitter, null);
        });
        emitter.onError(error -> {
            removeEmitter(emitter);
            finish(emitter, error);
        });

        // Flush the initial SSE response as soon as Spring attaches the stream.
        try {
            emitter.send(SseEmitter.event().name(CONNECTED_EVENT_NAME).data("connected"));
            emitters.put(emitter, new ClientWriter(emitter));
        } catch (Exception error) {
            finish(emitter, error);
        }
    }

    public void removeEmitter(SseEmitter emitter) {
        if (emitter == null) return;
        ClientWriter client = emitters.remove(emitter);
        if (client != null) client.close();
    }

    @Override
    public void onNotification(long version) {
        if (version < 0 || isShutdown.get()) return;
        lastBroadcastVersion.accumulateAndGet(version, Math::max);
        emitters.values().forEach(client -> client.offer(version));
        broadcastCount.incrementAndGet();
    }

    void sendHeartbeat() {
        if (!isShutdown.get()) emitters.values().forEach(client -> client.offer(HEARTBEAT));
    }

    private static void finish(SseEmitter emitter, Throwable error) {
        try {
            if (error == null) emitter.complete();
            else emitter.completeWithError(error);
        } catch (RuntimeException ignored) {
            logger.debug("SSE connection was already closed", ignored);
        }
    }

    @Override
    public void destroy() {
        if (!isShutdown.compareAndSet(false, true)) return;
        heartbeatScheduler.shutdownNow();
        writers.shutdownNow();
        closeAll();
    }

    public synchronized void closeAll() {
        for (SseEmitter emitter : emitters.keySet()) {
            removeEmitter(emitter);
            finish(emitter, null);
        }
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

    private final class ClientWriter {
        private final SseEmitter emitter;
        private boolean scheduled;
        private boolean closed;
        private Long pending;
        private long greatestVersion = -1;

        ClientWriter(SseEmitter emitter) {
            this.emitter = emitter;
        }

        synchronized void offer(long version) {
            if (closed) return;
            if (version == HEARTBEAT) {
                if (pending != null) return;
            } else {
                if (version <= greatestVersion) return;
                greatestVersion = version;
            }
            pending = version;
            if (scheduled) return;

            scheduled = true;
            try {
                writers.execute(this::drain);
            } catch (RejectedExecutionException error) {
                // A full writer queue retains at most its admitted tasks. This client can reconnect.
                removeEmitter(emitter);
                finish(emitter, error);
            }
        }

        synchronized void close() {
            closed = true;
            pending = null;
        }

        private void drain() {
            while (true) {
                long next;
                synchronized (this) {
                    if (closed || pending == null) {
                        scheduled = false;
                        return;
                    }
                    next = pending;
                    pending = null;
                }
                try {
                    emitter.send(next == HEARTBEAT
                            ? SseEmitter.event().comment("ping")
                            : SseEmitter.event().name(EVENT_NAME).data(Long.toString(next)));
                } catch (Exception error) {
                    removeEmitter(emitter);
                    finish(emitter, error);
                    return;
                }
            }
        }
    }
}
