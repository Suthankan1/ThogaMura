package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lk.thogamura.gateway.network.tcp.CommandTimeoutException;
import lk.thogamura.gateway.network.tcp.ServerUnavailableException;
import lk.thogamura.gateway.network.tcp.TcpCommandClient;
import lk.thogamura.protocol.ProtocolInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SupplierController.class)
class SupplierControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TcpCommandClient tcpCommandClient;

    @Test
    @DisplayName("GET /api/v1/suppliers/availability?sku=ITEM-008 should return 200 OK with available stock")
    void shouldReturnAvailableStock() throws Exception {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("v", 1);
        result.put("type", "RESULT");
        result.put("status", "AVAILABLE");

        ObjectNode data = result.putObject("data");
        data.put("sku", "ITEM-008");
        data.put("status", "AVAILABLE");
        data.put("supplier", "Demo Supplier");
        data.put("reportedQuantity", 20);
        data.put("estimatedRestockDays", 3);
        data.put("source", "MOCK_SUPPLIER");
        data.put("checkedAt", "2026-11-19T00:00:00Z");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK), eq(Map.of("sku", "ITEM-008"))))
                .willReturn(result);

        mockMvc.perform(get("/api/v1/suppliers/availability")
                        .param("sku", "ITEM-008")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("ITEM-008"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.reportedQuantity").value(20))
                .andExpect(jsonPath("$.supplier").value("Demo Supplier"))
                .andExpect(jsonPath("$.source").value("MOCK_SUPPLIER"));
    }

    @Test
    @DisplayName("GET /api/v1/suppliers/availability?sku=ITEM-008-ZERO should return 200 OK with confirmed zero units")
    void shouldReturnConfirmedZeroUnits() throws Exception {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("v", 1);
        result.put("type", "RESULT");
        result.put("status", "AVAILABLE");

        ObjectNode data = result.putObject("data");
        data.put("sku", "ITEM-008-ZERO");
        data.put("status", "AVAILABLE");
        data.put("supplier", "Demo Supplier");
        data.put("reportedQuantity", 0);
        data.put("source", "MOCK_SUPPLIER");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK), eq(Map.of("sku", "ITEM-008-ZERO"))))
                .willReturn(result);

        mockMvc.perform(get("/api/v1/suppliers/availability")
                        .param("sku", "ITEM-008-ZERO")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("ITEM-008-ZERO"))
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.reportedQuantity").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/suppliers/availability?sku=UNKNOWN-999 should return 404 Not Found")
    void shouldReturnNotFoundWhenSkuMissing() throws Exception {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("v", 1);
        result.put("type", "RESULT");
        result.put("status", "UNKNOWN");

        ObjectNode data = result.putObject("data");
        data.put("sku", "UNKNOWN-999");
        data.put("code", ProtocolInfo.ERROR_NOT_FOUND);
        data.put("message", "Item is not cataloged by supplier");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK), eq(Map.of("sku", "UNKNOWN-999"))))
                .willReturn(result);

        mockMvc.perform(get("/api/v1/suppliers/availability")
                        .param("sku", "UNKNOWN-999")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("Item is not cataloged by supplier"));
    }

    @Test
    @DisplayName("GET /api/v1/suppliers/availability?sku=ITEM-503 should return 503 Service Unavailable")
    void shouldReturn503WhenSupplierUnavailable() throws Exception {
        ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.put("v", 1);
        result.put("type", "RESULT");
        result.put("status", "UNAVAILABLE");

        ObjectNode data = result.putObject("data");
        data.put("sku", "ITEM-503");
        data.put("code", ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE);
        data.put("message", "External supplier service is currently unreachable");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK), eq(Map.of("sku", "ITEM-503"))))
                .willReturn(result);

        mockMvc.perform(get("/api/v1/suppliers/availability")
                        .param("sku", "ITEM-503")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SUPPLIER_UNAVAILABLE"));
    }

    @Test
    @DisplayName("GET /api/v1/suppliers/availability without sku parameter should return 400 Bad Request")
    void shouldReturnBadRequestWhenSkuParameterMissing() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers/availability")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Query parameter 'sku' is required and must match SKU format"));
    }

    @Test
    @DisplayName("GET /api/v1/suppliers/availability with invalid characters should return 400 Bad Request")
    void shouldReturnBadRequestWhenSkuHasInvalidCharacters() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers/availability")
                        .param("sku", "INVALID;SKU!")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("Should return 504 Gateway Timeout when socket read expires")
    void shouldReturn504OnTimeout() throws Exception {
        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK), any()))
                .willThrow(new CommandTimeoutException("Read timed out", "req-123"));

        mockMvc.perform(get("/api/v1/suppliers/availability")
                        .param("sku", "ITEM-008")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_TIMEOUT"))
                .andExpect(jsonPath("$.message").value("Warehouse command timed out awaiting response. Please retry."));
    }

    @Test
    @DisplayName("Should return 503 Service Unavailable when warehouse server is offline")
    void shouldReturn503WhenServerOffline() throws Exception {
        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK), any()))
                .willThrow(new ServerUnavailableException("Connection refused", "req-123"));

        mockMvc.perform(get("/api/v1/suppliers/availability")
                        .param("sku", "ITEM-008")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("Warehouse server is unreachable. Please verify warehouse connectivity and retry."));
    }
}
