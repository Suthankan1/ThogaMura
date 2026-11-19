package lk.thogamura.networkserver.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.networkserver.persistence.CommandReceiptRepository;
import lk.thogamura.networkserver.persistence.JdbcCommandReceiptRepository;
import lk.thogamura.networkserver.persistence.JdbcProductStockRepository;
import lk.thogamura.networkserver.persistence.JdbcStockRequestRepository;
import lk.thogamura.networkserver.persistence.PostgresTestHelper;
import lk.thogamura.networkserver.persistence.ProductStockRepository;
import lk.thogamura.networkserver.persistence.StockRequestRepository;
import lk.thogamura.networkserver.transport.tcp.DefaultCommandDispatcher;
import lk.thogamura.networkserver.transport.tcp.TcpCommandServer;
import lk.thogamura.networkserver.transport.tcp.TcpCommandServerConfig;
import lk.thogamura.protocol.ProtocolInfo;
import lk.thogamura.protocol.tcp.TcpFrameReader;
import lk.thogamura.protocol.tcp.TcpFrameWriter;
import lk.thogamura.protocol.tcp.TcpMessageCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test verifying atomic stock reservations over real Java TCP sockets
 * against real PostgreSQL 17 matching Issue #10 requirements.
 *
 * <pre>
 *   [Test Client Socket] --- Real OS TCP Socket ---> [TcpCommandServer]
 *                                                           ↓
 *                                                [StockReservationService]
 *                                                           ↓
 *                                                 [PostgreSQL 17 Database]
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TcpAtomicReservationSocketIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JdbcTemplate jdbcTemplate;
    private static PlatformTransactionManager transactionManager;
    private static ProductStockRepository productStockRepository;
    private static StockRequestRepository stockRequestRepository;
    private static CommandReceiptRepository commandReceiptRepository;

    private DefaultStockReservationService reservationService;
    private DefaultCommandDispatcher dispatcher;
    private TcpCommandServer server;
    private int serverPort;

    @BeforeAll
    static void initDatabase() {
        jdbcTemplate = PostgresTestHelper.getJdbcTemplate();
        transactionManager = PostgresTestHelper.getTransactionManager();
        productStockRepository = new JdbcProductStockRepository(jdbcTemplate);
        stockRequestRepository = new JdbcStockRequestRepository(jdbcTemplate);
        commandReceiptRepository = new JdbcCommandReceiptRepository(jdbcTemplate);
    }

    @BeforeEach
    void setUp() throws IOException {
        PostgresTestHelper.clearAllTables(jdbcTemplate);
        SyntheticInventoryFixtures.seed(jdbcTemplate);

        MonotonicVersionProvider versionProvider = new DefaultMonotonicVersionProvider(100L);
        reservationService = new DefaultStockReservationService(
                productStockRepository,
                stockRequestRepository,
                commandReceiptRepository,
                Optional.empty(),
                versionProvider,
                transactionManager
        );

        dispatcher = new DefaultCommandDispatcher(reservationService);
        TcpCommandServerConfig config = new TcpCommandServerConfig("127.0.0.1", 0);
        server = new TcpCommandServer(config, dispatcher);
        server.start();
        serverPort = server.getPort();

        assertThat(serverPort).isGreaterThan(0);
        assertThat(server.isRunning()).isTrue();
    }

    @AfterEach
    void tearDown() {
        if (server != null && server.isRunning()) {
            server.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. Request 8 from 10 available returns ACCEPTED over TCP and mutates PostgreSQL reserved=8")
    void shouldAcceptReservationAndMutatePostgresOverRealTcpSocket() throws Exception {
        UUID requestId = UUID.randomUUID();
        String request = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );

        JsonNode response = sendCommand(serverPort, request);

        // 1. Verify wire protocol response
        assertThat(response).isNotNull();
        assertThat(response.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
        assertThat(response.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(response.get("requestId").asText()).isEqualTo(requestId.toString());

        JsonNode data = response.get("data");
        assertThat(data).isNotNull();
        assertThat(data.get("sku").asText()).isEqualTo(SyntheticInventoryFixtures.SKU_ITEM_007);
        assertThat(data.get("quantity").asInt()).isEqualTo(8);
        assertThat(data.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);

        // 2. Verify persisted PostgreSQL state
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        Integer receiptCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_receipts WHERE command_request_id = ? AND result_json::text LIKE '%ACCEPTED%'",
                Integer.class,
                requestId
        );
        assertThat(receiptCount).isEqualTo(1);

        Integer requestCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_requests WHERE request_id = ? AND status = 'ACCEPTED' AND quantity = 8",
                Integer.class,
                requestId
        );
        assertThat(requestCount).isEqualTo(1);
    }

    @Test
    @Order(2)
    @DisplayName("2. Request exceeding available stock returns REJECTED over TCP and preserves database state")
    void shouldRejectReservationWhenExceedingAvailableStockOverRealTcpSocket() throws Exception {
        // First reserve 8 from 10
        UUID firstId = UUID.randomUUID();
        String firstReq = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                firstId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );
        JsonNode firstRes = sendCommand(serverPort, firstReq);
        assertThat(firstRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Now request 5 when available is 2
        UUID secondId = UUID.randomUUID();
        String secondReq = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                secondId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 5)
        );
        JsonNode secondRes = sendCommand(serverPort, secondReq);

        // 1. Verify wire protocol response
        assertThat(secondRes).isNotNull();
        assertThat(secondRes.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
        assertThat(secondRes.get("type").asText()).isEqualTo(ProtocolInfo.TYPE_RESULT);
        assertThat(secondRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_REJECTED);
        assertThat(secondRes.get("requestId").asText()).isEqualTo(secondId.toString());

        JsonNode errNode = secondRes.get("error");
        assertThat(errNode).isNotNull();
        assertThat(errNode.get("code").asText()).isEqualTo(ProtocolInfo.ERROR_INSUFFICIENT_STOCK);
        assertThat(errNode.get("message").asText()).contains("exceeds available stock");

        // 2. Verify stock balances untouched
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // 3. Verify durable rejection receipt recorded
        Integer receiptCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_receipts WHERE command_request_id = ? AND result_json::text LIKE '%REJECTED%'",
                Integer.class,
                secondId
        );
        assertThat(receiptCount).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("3. Same requestId and payload replays original outcome over TCP without duplicate reservation")
    void shouldReplayOriginalOutcomeWhenResendingIdenticalRequestIdAndPayloadOverTcp() throws Exception {
        UUID requestId = UUID.randomUUID();
        String request = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );

        // Send first time
        JsonNode firstRes = sendCommand(serverPort, request);
        assertThat(firstRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Resend identical command over a fresh socket connection
        JsonNode replayRes = sendCommand(serverPort, request);
        assertThat(replayRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(replayRes.get("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(replayRes.get("data").get("sku").asText()).isEqualTo(SyntheticInventoryFixtures.SKU_ITEM_007);
        assertThat(replayRes.get("data").get("quantity").asInt()).isEqualTo(8);

        // Balance must remain 8 (NOT 16)
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Single receipt exists
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_receipts WHERE command_request_id = ?",
                Integer.class,
                requestId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(4)
    @DisplayName("4. Same requestId with different payload returns DUPLICATE_ID_CONFLICT error over TCP")
    void shouldReturnDuplicateIdConflictWhenReusingRequestIdWithDifferentPayloadOverTcp() throws Exception {
        UUID requestId = UUID.randomUUID();
        String originalReq = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );

        JsonNode firstRes = sendCommand(serverPort, originalReq);
        assertThat(firstRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Reusing same requestId with quantity 5 instead of 8
        String conflictingReq = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 5)
        );

        JsonNode conflictRes = sendCommand(serverPort, conflictingReq);
        assertThat(conflictRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(conflictRes.get("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(conflictRes.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT);
        assertThat(conflictRes.get("error").get("message").asText()).contains("previously processed with a different payload");

        // Stock balances remain unaffected
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);
    }

    @Test
    @Order(5)
    @DisplayName("5. Concurrent TCP socket clients cannot oversubscribe stock")
    void shouldPreventOversubscriptionUnderConcurrentSocketRequests() throws Exception {
        int threadCount = 20;
        int requestedQuantityPerThread = 1; // 20 threads requesting 1 each against 10 available
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        List<JsonNode> responses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger acceptedCount = new AtomicInteger();
        AtomicInteger rejectedCount = new AtomicInteger();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    UUID reqId = UUID.randomUUID();
                    String req = TcpMessageCodec.formatRequest(
                            ProtocolInfo.COMMAND_REQUEST_STOCK,
                            reqId.toString(),
                            Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", requestedQuantityPerThread)
                    );
                    JsonNode res = sendCommand(serverPort, req);
                    responses.add(res);

                    String status = res.get("status").asText();
                    if (ProtocolInfo.STATUS_ACCEPTED.equals(status)) {
                        acceptedCount.incrementAndGet();
                    } else if (ProtocolInfo.STATUS_REJECTED.equals(status)) {
                        rejectedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Release all client threads simultaneously
        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(responses).hasSize(threadCount);

        // Exactly 10 succeed, exactly 10 are rejected
        assertThat(acceptedCount.get()).isEqualTo(10);
        assertThat(rejectedCount.get()).isEqualTo(10);

        // Invariant holds: on_hand=10, reserved=10, available=0
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 10, 0);

        Integer totalReceipts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_receipts", Integer.class
        );
        assertThat(totalReceipts).isEqualTo(threadCount);
    }

    @Test
    @Order(6)
    @DisplayName("6. Server restart does not erase command receipts and replays correctly from PostgreSQL")
    void shouldPreserveCommandReceiptsAndReplayAccuratelyAcrossServerRestart() throws Exception {
        UUID requestId = UUID.randomUUID();
        String request = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );

        // Send to server 1
        JsonNode firstRes = sendCommand(serverPort, request);
        assertThat(firstRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Simulate server shutdown / restart
        server.stop();
        assertThat(server.isRunning()).isFalse();

        // Start a brand-new TcpCommandServer instance pointing to the same persistent PostgreSQL database
        DefaultCommandDispatcher newDispatcher = new DefaultCommandDispatcher(reservationService);
        TcpCommandServer newServer = new TcpCommandServer(
                new TcpCommandServerConfig("127.0.0.1", 0),
                newDispatcher
        );
        newServer.start();
        int newPort = newServer.getPort();

        try {
            // Send same command to the restarted server
            JsonNode rebootRes = sendCommand(newPort, request);

            assertThat(rebootRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
            assertThat(rebootRes.get("requestId").asText()).isEqualTo(requestId.toString());
            assertThat(rebootRes.get("data").get("sku").asText()).isEqualTo(SyntheticInventoryFixtures.SKU_ITEM_007);
            assertThat(rebootRes.get("data").get("quantity").asInt()).isEqualTo(8);

            // Verify database state is untouched
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);
        } finally {
            newServer.stop();
        }
    }

    @Test
    @Order(7)
    @DisplayName("7. Lost response after committed reservation: Retry with identical requestId returns persisted decision without double-reserving")
    void shouldHandleLostResponseAfterCommittedReservationAndRecoverViaSameIdRetry() throws Exception {
        UUID requestId = UUID.randomUUID();
        String request = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );

        // Client 1 sends request, but terminates the socket before reading the response (simulating lost ACK / network drop)
        try (Socket socket1 = new Socket("127.0.0.1", serverPort);
             TcpFrameWriter writer1 = new TcpFrameWriter(socket1.getOutputStream())) {
            writer1.writeFrame(request);
            // Allow the server thread brief time to process and commit the transaction in PostgreSQL
            Thread.sleep(150);
            // Abruptly close socket1 without reading the response!
            socket1.close();
        }

        // Verify the database transaction committed: on_hand=10, reserved=8, available=2
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Verify command receipt was written to PostgreSQL
        Integer receiptCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_receipts WHERE command_request_id = ?",
                Integer.class,
                requestId
        );
        assertThat(receiptCount).isEqualTo(1);

        // Now client retries over a fresh TCP socket using the IDENTICAL requestId and unchanged payload
        JsonNode retryRes = sendCommand(serverPort, request);

        // Verify the response is the persisted original decision
        assertThat(retryRes).isNotNull();
        assertThat(retryRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(retryRes.get("requestId").asText()).isEqualTo(requestId.toString());
        assertThat(retryRes.get("data").get("sku").asText()).isEqualTo(SyntheticInventoryFixtures.SKU_ITEM_007);
        assertThat(retryRes.get("data").get("quantity").asInt()).isEqualTo(8);

        // Critical: Verify stock was NOT reserved twice! reserved remains 8 (NOT 16!), available remains 2
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Verify only 1 stock request record exists in PostgreSQL
        Integer stockRequestCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_requests WHERE request_id = ?",
                Integer.class,
                requestId
        );
        assertThat(stockRequestCount).isEqualTo(1);
    }

    private JsonNode sendCommand(int port, String requestJson) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port);
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {
            socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
            writer.writeFrame(requestJson);
            return reader.readNextFrameJson();
        }
    }

    private void assertStockBalances(String sku, int expectedOnHand, int expectedReserved, int expectedAvailable) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT on_hand, reserved, (on_hand - reserved) AS available FROM product_stock WHERE sku = ?",
                sku
        );
        assertThat(((Number) row.get("on_hand")).intValue()).isEqualTo(expectedOnHand);
        assertThat(((Number) row.get("reserved")).intValue()).isEqualTo(expectedReserved);
        assertThat(((Number) row.get("available")).intValue()).isEqualTo(expectedAvailable);
    }
}
