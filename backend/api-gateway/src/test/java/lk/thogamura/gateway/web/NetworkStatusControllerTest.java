package lk.thogamura.gateway.web;

import lk.thogamura.gateway.network.notifications.NotificationSubscriberLifecycle;
import lk.thogamura.gateway.network.notifications.SubscriberStatus;
import lk.thogamura.gateway.sse.SseNotificationBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(NetworkStatusController.class)
class NetworkStatusControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SseNotificationBroadcaster sseBroadcaster;

    @MockitoBean
    private NotificationSubscriberLifecycle subscriberLifecycle;

    @Test
    @DisplayName("GET /api/v1/network-status should report NIO feed and SSE status accurately")
    void shouldReturnNetworkStatus() throws Exception {
        given(subscriberLifecycle.getStatus()).willReturn(SubscriberStatus.CONNECTED);
        given(subscriberLifecycle.isConnected()).willReturn(true);
        given(sseBroadcaster.getActiveEmitterCount()).willReturn(3);
        given(sseBroadcaster.getBroadcastCount()).willReturn(12L);
        given(sseBroadcaster.getLastBroadcastVersion()).willReturn(42L);

        mockMvc.perform(get("/api/v1/network-status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.centralHost").value("127.0.0.1"))
                .andExpect(jsonPath("$.nioPort").value(7444))
                .andExpect(jsonPath("$.nioFeed.status").value("CONNECTED"))
                .andExpect(jsonPath("$.nioFeed.connected").value(true))
                .andExpect(jsonPath("$.sse.activeClients").value(3))
                .andExpect(jsonPath("$.sse.broadcastCount").value(12))
                .andExpect(jsonPath("$.sse.lastBroadcastVersion").value(42))
                .andExpect(jsonPath("$.tlsCommand.status").value("NOT_IMPLEMENTED"));
    }
}
