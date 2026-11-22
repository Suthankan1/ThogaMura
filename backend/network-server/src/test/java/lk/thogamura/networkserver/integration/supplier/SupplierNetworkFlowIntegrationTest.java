package lk.thogamura.networkserver.integration.supplier;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.networkserver.transport.tcp.DefaultCommandDispatcher;
import lk.thogamura.networkserver.transport.tcp.TcpCommandServer;
import lk.thogamura.networkserver.transport.tcp.TcpCommandServerConfig;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test demonstrating the full 3-hop supplier networking pipeline:
 *
 * <pre>
 * [Gateway Client] -- Java TCP Socket (:port) --> [Warehouse NetworkServer]
 *                                                        │
 *                                                 java.net.HttpURLConnection (HTTP GET)
 *                                                        ▼
 *                                            [SupplierHttpSimulator (:8090)]
 * </pre>
 */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class SupplierNetworkFlowIntegrationTest {

    private SupplierHttpSimulator supplierSimulator;
    private int supplierPort;

    private TcpCommandServer commandServer;
    private int commandPort;

    @BeforeEach
    void setUp() throws IOException {
        // 1. Start independent HTTP Supplier Simulator on loopback ephemeral port
        supplierSimulator = new SupplierHttpSimulator(0);
        supplierSimulator.start();
        supplierPort = supplierSimulator.getPort();

        // 2. Configure Supplier HTTP client pointing to the simulator
        SupplierClientConfig supplierConfig = new SupplierClientConfig(
                "http://127.0.0.1:" + supplierPort,
                3000,
                5000,
                16384,
                java.util.Set.of()
        );
        DefaultSupplierHttpClient supplierHttpClient = new DefaultSupplierHttpClient(supplierConfig);
        SupplierCommandHandler supplierCommandHandler = new SupplierCommandHandler(supplierHttpClient);

        DefaultCommandDispatcher dispatcher = new DefaultCommandDispatcher(supplierCommandHandler);

        // 3. Start warehouse TCP Command Server
        TcpCommandServerConfig serverConfig = new TcpCommandServerConfig("127.0.0.1", 0);
        commandServer = new TcpCommandServer(serverConfig, dispatcher);
        commandServer.start();
        commandPort = commandServer.getPort();
    }

    @AfterEach
    void tearDown() {
        if (commandServer != null) {
            commandServer.stop();
        }
        if (supplierSimulator != null) {
            supplierSimulator.close();
        }
    }

    @Test
    @DisplayName("Demonstrates full network round-trip: TCP socket -> HttpURLConnection -> 200 OK Available")
    void shouldPropagateSupplierStockThroughTcpSocket() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String commandPayload = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK,
                requestId,
                Map.of("sku", "ITEM-008")
        );

        try (Socket socket = new Socket("127.0.0.1", commandPort);
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            writer.writeFrame(commandPayload);

            JsonNode response = reader.readNextFrameJson();
            assertThat(response).isNotNull();
            assertThat(response.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
            assertThat(response.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
            assertThat(response.get("requestId").asText()).isEqualTo(requestId);
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_AVAILABLE);

            JsonNode data = response.get("data");
            assertThat(data.get("sku").asText()).isEqualTo("ITEM-008");
            assertThat(data.get("reportedQuantity").asInt()).isEqualTo(20);
            assertThat(data.get("supplier").asText()).isEqualTo("Demo Supplier");
            assertThat(data.get("source").asText()).isEqualTo("MOCK_SUPPLIER");
            assertThat(data.hasNonNull("checkedAt")).isTrue();
        }
    }

    @Test
    @DisplayName("Demonstrates confirmed zero units propagation through TCP socket without missing/unknown confusion")
    void shouldPropagateConfirmedZeroUnitsThroughTcpSocket() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String commandPayload = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK,
                requestId,
                Map.of("sku", "ITEM-008-ZERO")
        );

        try (Socket socket = new Socket("127.0.0.1", commandPort);
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            writer.writeFrame(commandPayload);

            JsonNode response = reader.readNextFrameJson();
            assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_AVAILABLE);

            JsonNode data = response.get("data");
            assertThat(data.get("sku").asText()).isEqualTo("ITEM-008-ZERO");
            assertThat(data.get("reportedQuantity").asInt()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("Demonstrates 404 Unknown SKU propagation through TCP socket")
    void shouldPropagateUnknownSkuThroughTcpSocket() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String commandPayload = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK,
                requestId,
                Map.of("sku", "UNKNOWN-ITEM-999")
        );

        try (Socket socket = new Socket("127.0.0.1", commandPort);
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            writer.writeFrame(commandPayload);

            JsonNode response = reader.readNextFrameJson();
            assertThat(response.get("status").asText()).isEqualTo("UNKNOWN");

            JsonNode data = response.get("data");
            assertThat(data.get("code").asText()).isEqualTo(ProtocolInfo.ERROR_NOT_FOUND);
        }
    }

    @Test
    @DisplayName("Demonstrates 503 Outage propagation through TCP socket")
    void shouldPropagateOutageThroughTcpSocket() throws Exception {
        String requestId = UUID.randomUUID().toString();
        String commandPayload = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_SUPPLIER_STOCK,
                requestId,
                Map.of("sku", "ITEM-503")
        );

        try (Socket socket = new Socket("127.0.0.1", commandPort);
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            writer.writeFrame(commandPayload);

            JsonNode response = reader.readNextFrameJson();
            assertThat(response.get("status").asText()).isEqualTo("UNAVAILABLE");

            JsonNode data = response.get("data");
            assertThat(data.get("code").asText()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE);
        }
    }
}
