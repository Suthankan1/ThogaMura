package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.protocol.ProtocolInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StockRequestController.class)
class StockRequestControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String VALID_IDEMPOTENCY_KEY = "5a3e07fd-158b-4d77-9b97-51844197ed9c";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TcpCommandClient tcpCommandClient;

    @Test
    @DisplayName("POST /api/v1/requests returns 201 Created with ACCEPTED status on successful reservation")
    void shouldCreateStockRequestSuccessfully() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "ACCEPTED");
        response.put("requestId", VALID_IDEMPOTENCY_KEY);

        ObjectNode data = response.putObject("data");
        data.put("sku", "ITEM-007");
        data.put("quantity", 8);

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_REQUEST_STOCK), eq(VALID_IDEMPOTENCY_KEY), any()))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/requests")
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"ITEM-007\",\"quantity\":8}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestId").value(VALID_IDEMPOTENCY_KEY))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.sku").value("ITEM-007"))
                .andExpect(jsonPath("$.quantity").value(8));
    }

    @Test
    @DisplayName("POST /api/v1/requests returns 409 Conflict when warehouse rejects for insufficient stock")
    void shouldReturn409WhenInsufficientStock() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "REJECTED");
        response.put("requestId", VALID_IDEMPOTENCY_KEY);

        ObjectNode data = response.putObject("data");
        data.put("sku", "ITEM-007");
        data.put("reason", "Requested quantity 8 exceeds available stock 2");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_REQUEST_STOCK), eq(VALID_IDEMPOTENCY_KEY), any()))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/requests")
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"ITEM-007\",\"quantity\":8}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"))
                .andExpect(jsonPath("$.message").value("Requested quantity 8 exceeds available stock 2"))
                .andExpect(jsonPath("$.requestId").value(VALID_IDEMPOTENCY_KEY));
    }

    @Test
    @DisplayName("POST /api/v1/requests returns 409 Conflict when duplicate Idempotency-Key has conflicting payload")
    void shouldReturn409OnDuplicateIdConflict() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "ERROR");
        response.put("status", "ERROR");
        response.put("requestId", VALID_IDEMPOTENCY_KEY);

        ObjectNode error = response.putObject("error");
        error.put("code", ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT);
        error.put("message", "Idempotency key previously used with different payload");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_REQUEST_STOCK), eq(VALID_IDEMPOTENCY_KEY), any()))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/requests")
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"ITEM-007\",\"quantity\":8}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ID_CONFLICT"))
                .andExpect(jsonPath("$.requestId").value(VALID_IDEMPOTENCY_KEY));
    }

    @Test
    @DisplayName("POST /api/v1/requests rejects missing or invalid Idempotency-Key header with 400 Bad Request")
    void shouldRejectInvalidIdempotencyKey() throws Exception {
        // Missing header
        mockMvc.perform(post("/api/v1/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"ITEM-007\",\"quantity\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MISSING_IDEMPOTENCY_KEY"));

        // Invalid non-v4 UUID
        mockMvc.perform(post("/api/v1/requests")
                        .header("Idempotency-Key", "not-a-uuid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"ITEM-007\",\"quantity\":8}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    @DisplayName("POST /api/v1/requests rejects negative quantity with 400 Bad Request")
    void shouldRejectNegativeQuantity() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"ITEM-007\",\"quantity\":-5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("GET /api/v1/requests returns 200 OK with list of stock requests")
    void shouldReturnRequestsList() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "ACCEPTED");
        response.put("requestId", "123e4567-e89b-12d3-a456-426614174000");

        ObjectNode data = response.putObject("data");
        ArrayNode requests = data.putArray("requests");

        ObjectNode r1 = requests.addObject();
        r1.put("requestId", VALID_IDEMPOTENCY_KEY);
        r1.put("sku", "ITEM-007");
        r1.put("quantity", 8);
        r1.put("status", "ACCEPTED");
        r1.put("createdAt", "2026-11-18T09:30:00Z");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_REQUESTS), any(), any()))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/requests").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].requestId").value(VALID_IDEMPOTENCY_KEY))
                .andExpect(jsonPath("$[0].sku").value("ITEM-007"))
                .andExpect(jsonPath("$[0].quantity").value(8))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));
    }

    @Test
    @DisplayName("POST /api/v1/requests rejects SKU containing invalid characters or newlines with 400 Bad Request")
    void shouldRejectInvalidSkuCharacters() throws Exception {
        mockMvc.perform(post("/api/v1/requests")
                        .header("Idempotency-Key", VALID_IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sku\":\"ITEM 007!@#\",\"quantity\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("contains invalid characters")));
    }

    @Test
    @DisplayName("GET /api/v1/requests rejects invalid status filter with 400 Bad Request")
    void shouldRejectInvalidStatusFilter() throws Exception {
        mockMvc.perform(get("/api/v1/requests?status=INVALID_STATUS_XYZ").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("Invalid status filter")));
    }

    @Test
    @DisplayName("GET /api/v1/requests accepts status=ALL as unrestricted query")
    void shouldAcceptAllStatusFilter() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "ACCEPTED");
        response.put("requestId", "123e4567-e89b-12d3-a456-426614174000");

        ObjectNode data = response.putObject("data");
        data.putArray("requests");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_REQUESTS), any(), any()))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/requests?status=ALL").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
