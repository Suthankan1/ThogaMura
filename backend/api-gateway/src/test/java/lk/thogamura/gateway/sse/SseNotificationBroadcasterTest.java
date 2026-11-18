package lk.thogamura.gateway.sse;

import lk.thogamura.gateway.network.notifications.NotificationListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class SseNotificationBroadcasterTest {

    private SseNotificationBroadcaster broadcaster;

    @BeforeEach
    void setUp() {
        broadcaster = new SseNotificationBroadcaster(10, 0L, 64);
    }

    @AfterEach
    void tearDown() {
        if (broadcaster != null) {
            broadcaster.destroy();
        }
    }

    @Test
    @DisplayName("Should broadcast stock-changed event with version-only data to a single connected emitter")
    void shouldBroadcastToSingleEmitter() throws Exception {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        broadcaster.registerEmitter(emitter);

        assertThat(broadcaster.getActiveEmitterCount()).isEqualTo(1);

        broadcaster.onNotification(42L);

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !emitter.getSentData().isEmpty());

        assertThat(emitter.getSentData()).hasSize(1);
        String eventString = emitter.getSentData().get(0);
        assertThat(eventString).contains("event:stock-changed");
        assertThat(eventString).contains("data:42");
        // Verify no business fields leaked
        assertThat(eventString).doesNotContain("sku");
        assertThat(eventString).doesNotContain("quantity");
        assertThat(eventString).doesNotContain("credentials");
    }

    @Test
    @DisplayName("Should broadcast the exact same notification to multiple simultaneous connected clients")
    void shouldBroadcastToMultipleClients() throws Exception {
        List<CapturingSseEmitter> emitters = List.of(
                new CapturingSseEmitter(),
                new CapturingSseEmitter(),
                new CapturingSseEmitter()
        );

        emitters.forEach(broadcaster::registerEmitter);
        assertThat(broadcaster.getActiveEmitterCount()).isEqualTo(3);

        broadcaster.onNotification(105L);

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> emitters.stream().allMatch(e -> !e.getSentData().isEmpty()));

        for (CapturingSseEmitter emitter : emitters) {
            assertThat(emitter.getSentData()).hasSize(1);
            assertThat(emitter.getSentData().get(0)).contains("event:stock-changed");
            assertThat(emitter.getSentData().get(0)).contains("data:105");
        }
        assertThat(broadcaster.getBroadcastCount()).isEqualTo(1L);
        assertThat(broadcaster.getLastBroadcastVersion()).isEqualTo(105L);
    }

    @Test
    @DisplayName("A disconnected/failing client should not break delivery to other clients and must be removed")
    void shouldIsolateDisconnectedClient() throws Exception {
        CapturingSseEmitter healthy1 = new CapturingSseEmitter();
        FailingSseEmitter failing = new FailingSseEmitter(new IOException("Broken pipe / Client abort"));
        CapturingSseEmitter healthy2 = new CapturingSseEmitter();

        broadcaster.registerEmitter(healthy1);
        broadcaster.registerEmitter(failing);
        broadcaster.registerEmitter(healthy2);

        assertThat(broadcaster.getActiveEmitterCount()).isEqualTo(3);

        broadcaster.onNotification(200L);

        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> !healthy1.getSentData().isEmpty() && !healthy2.getSentData().isEmpty());

        assertThat(healthy1.getSentData().get(0)).contains("data:200");
        assertThat(healthy2.getSentData().get(0)).contains("data:200");

        // Failing client was marked dead and removed
        await().atMost(2, TimeUnit.SECONDS)
                .until(() -> broadcaster.getActiveEmitterCount() == 2);
    }

    @Test
    @DisplayName("The NIO caller thread must not be blocked when an SSE client is slow")
    void shouldNotBlockCallerThreadWhenConsumerIsSlow() throws Exception {
        CountDownLatch slowClientStarted = new CountDownLatch(1);
        CountDownLatch allowSlowClientToFinish = new CountDownLatch(1);

        SseEmitter slowEmitter = new SseEmitter(0L) {
            @Override
            public void send(SseEventBuilder builder) throws IOException {
                slowClientStarted.countDown();
                try {
                    allowSlowClientToFinish.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        broadcaster.registerEmitter(slowEmitter);

        long start = System.currentTimeMillis();
        // This onNotification simulates the NIO subscriber thread
        broadcaster.onNotification(300L);
        long elapsed = System.currentTimeMillis() - start;

        // Calling onNotification must return almost immediately (< 100ms) without waiting for the slow consumer
        assertThat(elapsed).isLessThan(100);

        // Verify the slow client did start asynchronously
        boolean started = slowClientStarted.await(2, TimeUnit.SECONDS);
        assertThat(started).isTrue();

        // Release the slow client
        allowSlowClientToFinish.countDown();
    }

    @Test
    @DisplayName("Should reject registrations when maximum capacity is exceeded")
    void shouldRejectWhenCapacityReached() {
        SseNotificationBroadcaster boundedBroadcaster = new SseNotificationBroadcaster(2, 0L, 16);

        boundedBroadcaster.registerEmitter(new SseEmitter(0L));
        boundedBroadcaster.registerEmitter(new SseEmitter(0L));

        assertThat(boundedBroadcaster.getActiveEmitterCount()).isEqualTo(2);

        assertThatThrownBy(() -> boundedBroadcaster.registerEmitter(new SseEmitter(0L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Maximum SSE client capacity reached");

        boundedBroadcaster.destroy();
    }

    @Test
    @DisplayName("Should ignore negative versions and not broadcast")
    void shouldIgnoreNegativeVersions() {
        CapturingSseEmitter emitter = new CapturingSseEmitter();
        broadcaster.registerEmitter(emitter);

        broadcaster.onNotification(-1L);
        broadcaster.onNotification(-50L);

        assertThat(emitter.getSentData()).isEmpty();
        assertThat(broadcaster.getBroadcastCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should complete all emitters and terminate executor on destroy")
    void shouldCleanUpResourcesOnDestroy() {
        CapturingSseEmitter emitter1 = new CapturingSseEmitter();
        CapturingSseEmitter emitter2 = new CapturingSseEmitter();

        broadcaster.registerEmitter(emitter1);
        broadcaster.registerEmitter(emitter2);

        assertThat(broadcaster.getActiveEmitterCount()).isEqualTo(2);

        broadcaster.destroy();

        assertThat(broadcaster.isShutdown()).isTrue();
        assertThat(broadcaster.getActiveEmitterCount()).isEqualTo(0);
        assertThat(emitter1.isCompleted()).isTrue();
        assertThat(emitter2.isCompleted()).isTrue();

        // Subsequent notification should be discarded
        broadcaster.onNotification(999L);
        assertThat(broadcaster.getBroadcastCount()).isEqualTo(0L);
    }

    @Test
    @DisplayName("Should remove emitter upon explicit removal")
    void shouldRemoveEmitterUponRemoval() {
        SseEmitter emitter = new SseEmitter(0L);
        broadcaster.registerEmitter(emitter);
        assertThat(broadcaster.getActiveEmitterCount()).isEqualTo(1);

        broadcaster.removeEmitter(emitter);
        assertThat(broadcaster.getActiveEmitterCount()).isEqualTo(0);
    }

    /** Helper SseEmitter capturing the raw string representations sent to it. */
    private static class CapturingSseEmitter extends SseEmitter {
        private final List<String> sentData = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean(false);

        public CapturingSseEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            // SseEventBuilder build() returns a Set of ResponseBodyEmitter.DataWithMediaType
            Set<ResponseBodyEmitter.DataWithMediaType> data = builder.build();
            StringBuilder sb = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType d : data) {
                sb.append(d.getData());
            }
            sentData.add(sb.toString());
        }

        @Override
        public void complete() {
            completed.set(true);
            super.complete();
        }

        public List<String> getSentData() {
            return sentData;
        }

        public boolean isCompleted() {
            return completed.get();
        }
    }

    /** Helper SseEmitter throwing an IOException on send to simulate broken TCP connection. */
    private static class FailingSseEmitter extends SseEmitter {
        private final IOException exceptionToThrow;

        public FailingSseEmitter(IOException exceptionToThrow) {
            super(0L);
            this.exceptionToThrow = exceptionToThrow;
        }

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw exceptionToThrow;
        }
    }
}
