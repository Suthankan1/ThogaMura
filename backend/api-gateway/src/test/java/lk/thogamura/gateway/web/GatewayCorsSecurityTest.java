package lk.thogamura.gateway.web;

import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.gateway.sse.SseNotificationBroadcaster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({NetworkStatusController.class, EventsController.class})
@Import(GatewayCorsConfiguration.class)
class GatewayCorsSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SseNotificationBroadcaster broadcaster;

    @MockitoBean
    private TcpCommandClient tcpCommandClient;

    @Test
    @DisplayName("Preflight OPTIONS from approved origin http://localhost:3000 returns 200 with matching CORS header")
    void testPreflightFromApprovedLocalhostOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/network-status")
                        .header("Origin", "http://localhost:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"))
                .andExpect(header().string("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS"));
    }

    @Test
    @DisplayName("Preflight OPTIONS from approved origin http://127.0.0.1:3000 returns 200 with matching CORS header")
    void testPreflightFromApprovedIpOrigin() throws Exception {
        mockMvc.perform(options("/api/v1/network-status")
                        .header("Origin", "http://127.0.0.1:3000")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:3000"));
    }

    @Test
    @DisplayName("Preflight OPTIONS from untrusted origin http://malicious-site.com is rejected with 403 Forbidden")
    void testPreflightFromUntrustedOriginIsForbidden() throws Exception {
        mockMvc.perform(options("/api/v1/network-status")
                        .header("Origin", "http://malicious-site.com")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Simple GET from untrusted origin does not receive Access-Control-Allow-Origin header")
    void testSimpleGetFromUntrustedOriginHasNoCorsHeader() throws Exception {
        mockMvc.perform(get("/api/v1/network-status")
                        .header("Origin", "http://malicious-site.com")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("Same-origin request without Origin header succeeds without CORS headers")
    void testSameOriginRequestSucceeds() throws Exception {
        mockMvc.perform(get("/api/v1/network-status")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
