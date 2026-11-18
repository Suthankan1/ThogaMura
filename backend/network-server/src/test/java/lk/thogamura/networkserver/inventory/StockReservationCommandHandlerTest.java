package lk.thogamura.networkserver.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.networkserver.transport.tcp.DefaultCommandDispatcher;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StockReservationCommandHandlerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private StockReservationService reservationService;
    private StockReservationCommandHandler handler;
    private DefaultCommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        reservationService = mock(StockReservationService.class);
        handler = new StockReservationCommandHandler(reservationService);
        dispatcher = new DefaultCommandDispatcher(reservationService);
    }

    @Test
    @DisplayName("Should extract command fields and invoke reservation service")
    void shouldDispatchValidRequestStockCommand() throws Exception {
        UUID requestId = UUID.randomUUID();
        String acceptedJson = TcpMessageCodec.formatResultResponse(
                requestId.toString(),
                ProtocolInfo.STATUS_ACCEPTED,
                Map.of("requestId", requestId.toString(), "sku", "ITEM-007", "quantity", 8, "status", ProtocolInfo.STATUS_ACCEPTED)
        );

        ReservationResult mockResult = ReservationResult.accepted(
                requestId, null, null, false, acceptedJson
        );
        when(reservationService.reserveStock(any(ReservationCommand.class))).thenReturn(mockResult);

        String requestJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", "ITEM-007", "quantity", 8)
        );
        JsonNode requestNode = OBJECT_MAPPER.readTree(requestJson);

        String responseJson = handler.handle(requestNode);

        ArgumentCaptor<ReservationCommand> cmdCaptor = ArgumentCaptor.forClass(ReservationCommand.class);
        verify(reservationService).reserveStock(cmdCaptor.capture());
        ReservationCommand captured = cmdCaptor.getValue();

        assertThat(captured.requestId()).isEqualTo(requestId);
        assertThat(captured.sku()).isEqualTo("ITEM-007");
        assertThat(captured.quantity()).isEqualTo(8);

        JsonNode responseNode = OBJECT_MAPPER.readTree(responseJson);
        assertThat(responseNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(responseNode.get("requestId").asText()).isEqualTo(requestId.toString());
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when data object is missing")
    void shouldRejectWhenDataObjectMissing() throws Exception {
        UUID requestId = UUID.randomUUID();
        String json = "{\"v\":1,\"type\":\"REQUEST_STOCK\",\"requestId\":\"" + requestId + "\"}";
        JsonNode node = OBJECT_MAPPER.readTree(json);

        String response = handler.handle(node);
        JsonNode resNode = OBJECT_MAPPER.readTree(response);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(resNode.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        assertThat(resNode.get("error").get("message").asText()).contains("Missing required 'data' object");
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when sku is missing or empty")
    void shouldRejectWhenSkuMissingOrBlank() throws Exception {
        UUID requestId = UUID.randomUUID();
        String json = "{\"v\":1,\"type\":\"REQUEST_STOCK\",\"requestId\":\"" + requestId + "\",\"data\":{\"quantity\":8}}";
        JsonNode node = OBJECT_MAPPER.readTree(json);

        String response = handler.handle(node);
        JsonNode resNode = OBJECT_MAPPER.readTree(response);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(resNode.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        assertThat(resNode.get("error").get("message").asText()).contains("Missing required field 'sku'");
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when quantity is missing or non-integer")
    void shouldRejectWhenQuantityMissingOrNonInteger() throws Exception {
        UUID requestId = UUID.randomUUID();
        String json = "{\"v\":1,\"type\":\"REQUEST_STOCK\",\"requestId\":\"" + requestId + "\",\"data\":{\"sku\":\"ITEM-007\"}}";
        JsonNode node = OBJECT_MAPPER.readTree(json);

        String response = handler.handle(node);
        JsonNode resNode = OBJECT_MAPPER.readTree(response);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(resNode.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        assertThat(resNode.get("error").get("message").asText()).contains("Missing or non-integer field 'quantity'");
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when quantity is negative or zero")
    void shouldRejectWhenQuantityNotPositive() throws Exception {
        UUID requestId = UUID.randomUUID();
        String json = "{\"v\":1,\"type\":\"REQUEST_STOCK\",\"requestId\":\"" + requestId + "\",\"data\":{\"sku\":\"ITEM-007\",\"quantity\":0}}";
        JsonNode node = OBJECT_MAPPER.readTree(json);

        String response = handler.handle(node);
        JsonNode resNode = OBJECT_MAPPER.readTree(response);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(resNode.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
        assertThat(resNode.get("error").get("message").asText()).contains("Requested quantity must be positive");
        verifyNoInteractions(reservationService);
    }

    @Test
    @DisplayName("DefaultCommandDispatcher routes REQUEST_STOCK through registered handler")
    void shouldRouteThroughDefaultCommandDispatcher() throws Exception {
        UUID requestId = UUID.randomUUID();
        String acceptedJson = TcpMessageCodec.formatResultResponse(
                requestId.toString(),
                ProtocolInfo.STATUS_ACCEPTED,
                Map.of("requestId", requestId.toString(), "sku", "ITEM-007", "quantity", 5, "status", ProtocolInfo.STATUS_ACCEPTED)
        );
        when(reservationService.reserveStock(any(ReservationCommand.class))).thenReturn(
                ReservationResult.accepted(requestId, null, null, false, acceptedJson)
        );

        String requestJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", "ITEM-007", "quantity", 5)
        );
        JsonNode requestNode = OBJECT_MAPPER.readTree(requestJson);

        String response = dispatcher.dispatch(requestNode);
        JsonNode resNode = OBJECT_MAPPER.readTree(response);

        assertThat(resNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(resNode.get("requestId").asText()).isEqualTo(requestId.toString());
        verify(reservationService).reserveStock(any(ReservationCommand.class));
    }
}
