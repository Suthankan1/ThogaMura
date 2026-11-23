package lk.thogamura.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

@WebMvcTest(FulfillmentController.class)
class FulfillmentControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String VALID_STOCK_REQUEST_ID = "5a3e07fd-158b-4d77-9b97-51844197ed9c";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TcpCommandClient tcpCommandClient;

    @Test
    @DisplayName("POST /api/v1/requests/{id}/pick returns 200 OK with PICKED status on successful pick")
    void shouldMarkRequestAsPicked() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "PICKED");
        response.put("requestId", "123e4567-e89b-12d3-a456-426614174000");

        ObjectNode data = response.putObject("data");
        data.put("stockRequestId", VALID_STOCK_REQUEST_ID);
        data.put("status", "PICKED");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_MARK_PICKED), any(), any()))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/requests/" + VALID_STOCK_REQUEST_ID + "/pick")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockRequestId").value(VALID_STOCK_REQUEST_ID))
                .andExpect(jsonPath("$.status").value("PICKED"));
    }

    @Test
    @DisplayName("POST /api/v1/requests/{id}/dispatch returns 200 OK with DISPATCHED status on successful dispatch")
    void shouldMarkRequestAsDispatched() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "DISPATCHED");
        response.put("requestId", "123e4567-e89b-12d3-a456-426614174000");

        ObjectNode data = response.putObject("data");
        data.put("stockRequestId", VALID_STOCK_REQUEST_ID);
        data.put("status", "DISPATCHED");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_MARK_DISPATCHED), any(), any()))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/requests/" + VALID_STOCK_REQUEST_ID + "/dispatch")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockRequestId").value(VALID_STOCK_REQUEST_ID))
                .andExpect(jsonPath("$.status").value("DISPATCHED"));
    }

    @Test
    @DisplayName("POST /api/v1/requests/{id}/pick returns 409 Conflict when warehouse rejects invalid transition")
    void shouldReturn409WhenInvalidPickTransition() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "ERROR");
        response.put("requestId", "123e4567-e89b-12d3-a456-426614174000");

        ObjectNode error = response.putObject("error");
        error.put("code", "INVALID_TRANSITION");
        error.put("message", "Request must be in ACCEPTED state to be picked, but was DISPATCHED");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_MARK_PICKED), any(), any()))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/requests/" + VALID_STOCK_REQUEST_ID + "/pick")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_TRANSITION"))
                .andExpect(jsonPath("$.stockRequestId").value(VALID_STOCK_REQUEST_ID));
    }

    @Test
    void shouldUseTheSameCommandIdAfterASecondHttpAttempt() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode().put("status", "PICKED");
        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_MARK_PICKED), any(), any()))
                .willReturn(response);

        String path = "/api/v1/requests/" + VALID_STOCK_REQUEST_ID + "/pick";
        mockMvc.perform(post(path)).andExpect(status().isOk());
        mockMvc.perform(post(path)).andExpect(status().isOk());

        var commandIds = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(tcpCommandClient, org.mockito.Mockito.times(2))
                .sendCommand(eq(ProtocolInfo.COMMAND_MARK_PICKED), commandIds.capture(), any());
        org.assertj.core.api.Assertions.assertThat(commandIds.getAllValues()).containsOnly(commandIds.getValue());
        org.assertj.core.api.Assertions.assertThat(java.util.UUID.fromString(commandIds.getValue()).version()).isEqualTo(4);
    }

    @Test
    void shouldReturn404WhenPickTargetDoesNotExist() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode().put("status", "ERROR");
        response.putObject("error").put("code", "NOT_FOUND").put("message", "Request does not exist");
        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_MARK_PICKED), any(), any()))
                .willReturn(response);

        mockMvc.perform(post("/api/v1/requests/" + VALID_STOCK_REQUEST_ID + "/pick"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/v1/movements returns 200 OK with list of stock movements")
    void shouldReturnMovementsList() throws Exception {
        ObjectNode response = OBJECT_MAPPER.createObjectNode();
        response.put("v", 1);
        response.put("type", "RESULT");
        response.put("status", "ACCEPTED");
        response.put("requestId", "123e4567-e89b-12d3-a456-426614174000");

        ObjectNode data = response.putObject("data");
        ArrayNode movements = data.putArray("movements");

        ObjectNode m1 = movements.addObject();
        m1.put("id", 1L);
        m1.put("stockRequestId", VALID_STOCK_REQUEST_ID);
        m1.put("sku", "ITEM-007");
        m1.put("quantityDelta", -8);
        m1.put("reason", "DISPATCH");
        m1.put("occurredAt", "2026-11-18T09:45:00Z");

        given(tcpCommandClient.sendCommand(eq(ProtocolInfo.COMMAND_GET_MOVEMENTS), any(), any()))
                .willReturn(response);

        mockMvc.perform(get("/api/v1/movements").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].stockRequestId").value(VALID_STOCK_REQUEST_ID))
                .andExpect(jsonPath("$[0].sku").value("ITEM-007"))
                .andExpect(jsonPath("$[0].quantityDelta").value(-8))
                .andExpect(jsonPath("$[0].reason").value("DISPATCH"));
    }
}
