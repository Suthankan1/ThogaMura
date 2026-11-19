package lk.thogamura.networkserver.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.networkserver.persistence.StockMovementRecord;
import lk.thogamura.networkserver.persistence.StockRequestRecord;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockFulfillmentCommandHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StockFulfillmentService fulfillmentService;
    private StockFulfillmentCommandHandler handler;

    @BeforeEach
    void setUp() {
        fulfillmentService = mock(StockFulfillmentService.class);
        handler = new StockFulfillmentCommandHandler(fulfillmentService);
    }

    @Test
    @DisplayName("Should extract command fields and invoke pickStock for MARK_PICKED")
    void shouldDispatchValidMarkPickedCommand() throws Exception {
        UUID commandRequestId = UUID.randomUUID();
        UUID stockRequestId = UUID.randomUUID();

        String expectedJson = TcpMessageCodec.formatResultResponse(
                commandRequestId.toString(),
                ProtocolInfo.STATUS_PICKED,
                Map.of("stockRequestId", stockRequestId.toString(), "status", ProtocolInfo.STATUS_PICKED)
        );

        when(fulfillmentService.pickStock(eq(commandRequestId), eq(stockRequestId)))
                .thenReturn(FulfillmentResult.success(commandRequestId, stockRequestId, ProtocolInfo.STATUS_PICKED, false, expectedJson));

        String requestJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED,
                commandRequestId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode requestNode = OBJECT_MAPPER.readTree(requestJson);

        String responseJson = handler.handle(requestNode);

        verify(fulfillmentService).pickStock(commandRequestId, stockRequestId);

        JsonNode responseNode = OBJECT_MAPPER.readTree(responseJson);
        assertThat(responseNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PICKED);
        assertThat(responseNode.get("requestId").asText()).isEqualTo(commandRequestId.toString());
        assertThat(responseNode.get("data").get("stockRequestId").asText()).isEqualTo(stockRequestId.toString());
    }

    @Test
    @DisplayName("Should extract command fields and invoke dispatchStock for MARK_DISPATCHED")
    void shouldDispatchValidMarkDispatchedCommand() throws Exception {
        UUID commandRequestId = UUID.randomUUID();
        UUID stockRequestId = UUID.randomUUID();

        String expectedJson = TcpMessageCodec.formatResultResponse(
                commandRequestId.toString(),
                ProtocolInfo.STATUS_DISPATCHED,
                Map.of("stockRequestId", stockRequestId.toString(), "status", ProtocolInfo.STATUS_DISPATCHED)
        );

        when(fulfillmentService.dispatchStock(eq(commandRequestId), eq(stockRequestId)))
                .thenReturn(FulfillmentResult.success(commandRequestId, stockRequestId, ProtocolInfo.STATUS_DISPATCHED, false, expectedJson));

        String requestJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_DISPATCHED,
                commandRequestId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode requestNode = OBJECT_MAPPER.readTree(requestJson);

        String responseJson = handler.handle(requestNode);

        verify(fulfillmentService).dispatchStock(commandRequestId, stockRequestId);

        JsonNode responseNode = OBJECT_MAPPER.readTree(responseJson);
        assertThat(responseNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);
        assertThat(responseNode.get("requestId").asText()).isEqualTo(commandRequestId.toString());
        assertThat(responseNode.get("data").get("stockRequestId").asText()).isEqualTo(stockRequestId.toString());
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when requestId is missing or blank")
    void shouldRejectWhenRequestIdMissing() throws Exception {
        String json = "{\"v\":1,\"type\":\"MARK_PICKED\",\"data\":{\"stockRequestId\":\"" + UUID.randomUUID() + "\"}}";
        JsonNode node = OBJECT_MAPPER.readTree(json);

        String response = handler.handle(node);
        JsonNode resNode = OBJECT_MAPPER.readTree(response);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(resNode.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        assertThat(resNode.get("error").get("message").asText()).contains("Missing required field 'requestId'");
        verifyNoInteractions(fulfillmentService);
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when stockRequestId is missing or not a valid UUID")
    void shouldRejectWhenStockRequestIdMissingOrInvalid() throws Exception {
        UUID commandRequestId = UUID.randomUUID();
        String json = "{\"v\":1,\"type\":\"MARK_PICKED\",\"requestId\":\"" + commandRequestId + "\",\"data\":{}}";
        JsonNode node = OBJECT_MAPPER.readTree(json);

        String response = handler.handle(node);
        JsonNode resNode = OBJECT_MAPPER.readTree(response);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(resNode.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        assertThat(resNode.get("error").get("message").asText()).contains("Missing or invalid 'stockRequestId'");
        verifyNoInteractions(fulfillmentService);
    }

    @Test
    @DisplayName("Should handle GET_REQUESTS and return list of requests")
    void shouldHandleGetRequests() throws Exception {
        UUID commandRequestId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();
        StockRequestRecord record = StockRequestRecord.accepted(reqId, "a".repeat(64), "ITEM-007", 8);

        when(fulfillmentService.getRequests(any())).thenReturn(List.of(record));

        String requestJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_REQUESTS,
                commandRequestId.toString(),
                Map.of("status", "ACCEPTED")
        );
        JsonNode requestNode = OBJECT_MAPPER.readTree(requestJson);

        String responseJson = handler.handle(requestNode);
        JsonNode resNode = OBJECT_MAPPER.readTree(responseJson);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(resNode.get("data").get("requests")).hasSize(1);
        assertThat(resNode.get("data").get("requests").get(0).get("requestId").asText()).isEqualTo(reqId.toString());
    }

    @Test
    @DisplayName("Should handle GET_MOVEMENTS and return list of movements")
    void shouldHandleGetMovements() throws Exception {
        UUID commandRequestId = UUID.randomUUID();
        UUID reqId = UUID.randomUUID();
        StockMovementRecord movement = new StockMovementRecord(1L, reqId, "ITEM-007", -8, "DISPATCH", Instant.now());

        when(fulfillmentService.getMovements()).thenReturn(List.of(movement));

        String requestJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_MOVEMENTS,
                commandRequestId.toString(),
                null
        );
        JsonNode requestNode = OBJECT_MAPPER.readTree(requestJson);

        String responseJson = handler.handle(requestNode);
        JsonNode resNode = OBJECT_MAPPER.readTree(responseJson);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(resNode.get("data").get("movements")).hasSize(1);
        assertThat(resNode.get("data").get("movements").get(0).get("quantityDelta").asInt()).isEqualTo(-8);
    }
}
