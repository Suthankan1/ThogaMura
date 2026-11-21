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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ProductController.class)
class ProductControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TcpCommandClient tcpCommandClient;

    @Test
    @DisplayName("GET /api/v1/products returns 200 OK with product catalog mapped from TCP response")
    void shouldReturnProductCatalog() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "ACCEPTED");
        response.put("requestId", "123e4567-e89b-12d3-a456-426614174000");

        ObjectNode data = response.putObject("data");
        ArrayNode products = data.putArray("products");

        ObjectNode p1 = products.addObject();
        p1.put("sku", "ITEM-007");
        p1.put("name", "Organic Carrots");
        p1.put("onHand", 10);
        p1.put("reserved", 2);
        p1.put("available", 8);

        ObjectNode p2 = products.addObject();
        p2.put("sku", "ITEM-008");
        p2.put("name", "Red Onions");
        p2.put("onHand", 5);
        p2.put("reserved", 5);
        p2.put("available", 0);

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_PRODUCTS), any(), isNull()))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/products").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].sku").value("ITEM-007"))
                .andExpect(jsonPath("$[0].name").value("Organic Carrots"))
                .andExpect(jsonPath("$[0].onHand").value(10))
                .andExpect(jsonPath("$[0].reserved").value(2))
                .andExpect(jsonPath("$[0].available").value(8))
                .andExpect(jsonPath("$[1].sku").value("ITEM-008"))
                .andExpect(jsonPath("$[1].available").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/products returns 503 WAREHOUSE_UNAVAILABLE when warehouse server is unreachable")
    void shouldReturn503WhenWarehouseUnreachable() throws Exception {
        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_PRODUCTS), any(), isNull()))
                .willThrow(new ServerUnavailableException("Warehouse offline", "req-1"));

        mockMvc.perform(get("/api/v1/products").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_UNAVAILABLE"));
    }

    @Test
    @DisplayName("GET /api/v1/products returns 504 WAREHOUSE_TIMEOUT when warehouse read times out")
    void shouldReturn504WhenWarehouseTimesOut() throws Exception {
        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_PRODUCTS), any(), isNull()))
                .willThrow(new CommandTimeoutException("Read timed out", "req-1"));

        mockMvc.perform(get("/api/v1/products").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("WAREHOUSE_TIMEOUT"));
    }
}
