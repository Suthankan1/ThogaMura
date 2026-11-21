package lk.thogamura.gateway.web;

import lk.thogamura.gateway.sse.SseNotificationBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EventsController.class)
@Import(GatewayCorsConfiguration.class)
class EventsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SseNotificationBroadcaster broadcaster;

    @Test
    @DisplayName("GET /api/v1/events should return an active text/event-stream SseEmitter")
    void shouldSubscribeToEventsStream() throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        given(broadcaster.registerEmitter()).willReturn(emitter);

        mockMvc.perform(get("/api/v1/events")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        org.mockito.Mockito.verify(broadcaster).registerEmitter();
    }

    @Test
    @DisplayName("GET /api/v1/events should support CORS for approved frontend origin")
    void shouldAllowCrossOriginRequestsForApprovedOrigin() throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        given(broadcaster.registerEmitter()).willReturn(emitter);

        mockMvc.perform(get("/api/v1/events")
                        .header("Origin", "http://localhost:3000")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
    }

    @Test
    @DisplayName("GET /api/v1/events should reject or omit CORS headers for untrusted origin")
    void shouldRejectCORSForUntrustedOrigin() throws Exception {
        SseEmitter emitter = new SseEmitter(0L);
        given(broadcaster.registerEmitter()).willReturn(emitter);

        mockMvc.perform(get("/api/v1/events")
                        .header("Origin", "http://untrusted-origin.evil.com")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("GET /api/v1/events should return 503 SERVICE_UNAVAILABLE when broadcaster capacity is reached")
    void shouldReturnServiceUnavailableWhenCapacityReached() throws Exception {
        given(broadcaster.registerEmitter())
                .willThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Maximum SSE client capacity reached (100)"));

        mockMvc.perform(get("/api/v1/events")
                        .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                .andExpect(status().isServiceUnavailable());
    }
}
