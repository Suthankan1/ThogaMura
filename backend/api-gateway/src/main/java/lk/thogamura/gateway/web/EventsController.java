package lk.thogamura.gateway.web;

import lk.thogamura.gateway.sse.SseNotificationBroadcaster;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events (SSE) controller for real-time stock-change invalidation signals.
 *
 * <p>Exposes {@code GET /api/v1/events} yielding {@code text/event-stream}.
 * Streams {@code stock-changed} events containing solely the change version number.
 * Conforms strictly to {@code docs/contracts/HTTP_API_V1.md}.
 * No SKU, quantity, customer, or credential data is ever transmitted.</p>
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(origins = "*")
public class EventsController {

    private final SseNotificationBroadcaster broadcaster;

    public EventsController(SseNotificationBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    /**
     * Subscribes a client to the warehouse invalidation event stream.
     *
     * @return an open {@link SseEmitter} streaming {@code stock-changed} events
     */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe() {
        return broadcaster.registerEmitter();
    }
}
