package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.thogamura.gateway.network.notifications.NotificationSubscriberLifecycle;
import lk.thogamura.gateway.network.notifications.SubscriberStatus;
import lk.thogamura.gateway.network.tcp.CommandCorrelationException;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.gateway.network.tcp.TcpCommandClientConfig;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SseNotificationBroadcaster sseBroadcaster;

    @MockitoBean
    private NotificationSubscriberLifecycle subscriberLifecycle;

    @MockitoBean
    private TcpCommandClient tcpCommandClient;

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

    @Test
    @DisplayName("GET /api/v1/ping returns 200 OK with PONG when warehouse TCP server responds")
    void shouldReturnPongWhenWarehouseTcpServerResponds() throws Exception {
        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", 7443);
        given(tcpCommandClient.getConfig()).willReturn(config);

        ObjectNode pong = OBJECT_MAPPER.createObjectNode();
        pong.put("v", 1);
        pong.put("type", "RESULT");
        pong.put("status", "PONG");
        pong.put("requestId", "5a3e07fd-158b-4d77-9b97-51844197ed9c");
        ObjectNode data = pong.putObject("data");
        data.put("serverTime", "2026-11-19T01:00:00Z");

        given(tcpCommandClient.ping()).willReturn(pong);

        mockMvc.perform(get("/api/v1/ping")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PONG"))
                .andExpect(jsonPath("$.requestId").value("5a3e07fd-158b-4d77-9b97-51844197ed9c"))
                .andExpect(jsonPath("$.serverTime").value("2026-11-19T01:00:00Z"))
                .andExpect(jsonPath("$.warehouseHost").value("127.0.0.1"))
                .andExpect(jsonPath("$.warehousePort").value(7443));
    }

    @Test
    @DisplayName("GET /api/v1/ping returns 503 Service Unavailable when warehouse server is down")
    void shouldReturn503WhenWarehouseServerIsUnavailable() throws Exception {
        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", 7443);
        given(tcpCommandClient.getConfig()).willReturn(config);

        given(tcpCommandClient.ping()).willThrow(new ServerUnavailableException(
                "Warehouse server at 127.0.0.1:7443 is unavailable",
                "test-req-id"
        ));

        mockMvc.perform(get("/api/v1/ping")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.code").value("WAREHOUSE_UNAVAILABLE"))
                .andExpect(jsonPath("$.warehouseHost").value("127.0.0.1"))
                .andExpect(jsonPath("$.warehousePort").value(7443));
    }

    @Test
    @DisplayName("GET /api/v1/ping returns 504 Gateway Timeout when socket read times out")
    void shouldReturn504WhenSocketTimesOut() throws Exception {
        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", 7443);
        given(tcpCommandClient.getConfig()).willReturn(config);

        given(tcpCommandClient.ping()).willThrow(new CommandTimeoutException(
                "Read timed out after 5000ms awaiting response",
                "test-req-id"
        ));

        mockMvc.perform(get("/api/v1/ping")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value("TIMEOUT"))
                .andExpect(jsonPath("$.code").value("WAREHOUSE_TIMEOUT"))
                .andExpect(jsonPath("$.warehouseHost").value("127.0.0.1"))
                .andExpect(jsonPath("$.warehousePort").value(7443));
    }

    @Test
    @DisplayName("GET /api/v1/ping returns 502 Bad Gateway when response requestId is mismatched")
    void shouldReturn502WhenRequestIdIsMismatched() throws Exception {
        TcpCommandClientConfig config = new TcpCommandClientConfig("127.0.0.1", 7443);
        given(tcpCommandClient.getConfig()).willReturn(config);

        given(tcpCommandClient.ping()).willThrow(new CommandCorrelationException(
                "Mismatched requestId",
                "expected-id",
                "received-id"
        ));

        mockMvc.perform(get("/api/v1/ping")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("ERROR"))
                .andExpect(jsonPath("$.code").value("CORRELATION_ERROR"))
                .andExpect(jsonPath("$.warehouseHost").value("127.0.0.1"))
                .andExpect(jsonPath("$.warehousePort").value(7443));
    }
}
