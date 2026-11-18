package lk.thogamura.networkserver.integration.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SupplierCommandHandlerTest {

    private SupplierHttpClient mockSupplierClient;
    private SupplierCommandHandler handler;

    @BeforeEach
    void setUp() {
        mockSupplierClient = mock(SupplierHttpClient.class);
        handler = new SupplierCommandHandler(mockSupplierClient);
    }

    @Test
    @DisplayName("Should return Protocol v1 AVAILABLE result when supplier reports available stock")
    void shouldHandleAvailableStockSuccessfully() throws Exception {
        String requestId = UUID.randomUUID().toString();
        given(mockSupplierClient.checkAvailability("ITEM-008"))
                .willReturn(SupplierStockResponse.available("ITEM-008", "Demo Supplier", 20, 3, "2026-11-19T00:00:00Z"));

        String requestJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK,
                requestId,
                Map.of("sku", "ITEM-008")
        );
        JsonNode requestNode = TcpMessageCodec.parseJsonObject(requestJson);

        String responseJson = handler.handle(requestNode);
        JsonNode responseNode = TcpMessageCodec.parseJsonObject(responseJson);

        assertThat(responseNode.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
        assertThat(responseNode.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
        assertThat(responseNode.get("requestId").asText()).isEqualTo(requestId);
        assertThat(responseNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_AVAILABLE);

        JsonNode data = responseNode.get("data");
        assertThat(data.get("sku").asText()).isEqualTo("ITEM-008");
        assertThat(data.get("reportedQuantity").asInt()).isEqualTo(20);
        assertThat(data.get("supplier").asText()).isEqualTo("Demo Supplier");
        assertThat(data.get("source").asText()).isEqualTo("MOCK_SUPPLIER");
    }

    @Test
    @DisplayName("Should return Protocol v1 error when command data is missing 'sku'")
    void shouldReturnValidationErrorWhenSkuIsMissing() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String requestJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK,
                requestId,
                Map.of() // empty data
        );
        JsonNode requestNode = TcpMessageCodec.parseJsonObject(requestJson);

        String responseJson = handler.handle(requestNode);
        JsonNode responseNode = TcpMessageCodec.parseJsonObject(responseJson);

        assertThat(responseNode.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(responseNode.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
    }
}
