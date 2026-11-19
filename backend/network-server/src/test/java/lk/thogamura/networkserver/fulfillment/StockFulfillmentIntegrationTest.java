package lk.thogamura.networkserver.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.networkserver.inventory.DefaultMonotonicVersionProvider;
import lk.thogamura.networkserver.inventory.DefaultStockReservationService;
import lk.thogamura.networkserver.inventory.MonotonicVersionProvider;
import lk.thogamura.networkserver.inventory.SyntheticInventoryFixtures;
import lk.thogamura.networkserver.notifications.NioNotificationServer;
import lk.thogamura.networkserver.persistence.CommandReceiptRepository;
import lk.thogamura.networkserver.persistence.JdbcCommandReceiptRepository;
import lk.thogamura.networkserver.persistence.JdbcProductStockRepository;
import lk.thogamura.networkserver.persistence.JdbcStockMovementRepository;
import lk.thogamura.networkserver.persistence.JdbcStockRequestRepository;
import lk.thogamura.networkserver.persistence.PostgresTestHelper;
import lk.thogamura.networkserver.persistence.ProductStockRepository;
import lk.thogamura.networkserver.persistence.StockMovementRepository;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Real end-to-end PostgreSQL 17 and TCP socket integration tests for Member 4's
 * fulfillment lifecycle: {@code ACCEPTED -> PICKED -> DISPATCHED}.
 *
 * <pre>
 *   [TCP Client Socket] --- REQUEST_STOCK, MARK_PICKED, MARK_DISPATCHED ---> [TcpCommandServer]
 *                                                                                    ↓
 *                                                                        [StockFulfillmentService]
 *                                                                                    ↓
 *                                                                         [PostgreSQL 17 Database]
 *                                                                                    ↓ (afterCommit)
 *                                                                         [NioNotificationServer]
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StockFulfillmentIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JdbcTemplate jdbcTemplate;
    private static PlatformTransactionManager transactionManager;
    private static ProductStockRepository productStockRepository;
    private static StockRequestRepository stockRequestRepository;
    private static StockMovementRepository stockMovementRepository;
    private static CommandReceiptRepository commandReceiptRepository;

    private NioNotificationServer nioServer;
    private int nioPort;
    private Socket nioSubscriberSocket;
    private BufferedReader nioReader;

    private DefaultStockReservationService reservationService;
    private DefaultStockFulfillmentService fulfillmentService;
    private DefaultCommandDispatcher dispatcher;
    private TcpCommandServer tcpServer;
    private int tcpPort;

    @BeforeAll
    static void initDatabase() {
        jdbcTemplate = PostgresTestHelper.getJdbcTemplate();
        transactionManager = PostgresTestHelper.getTransactionManager();
        productStockRepository = new JdbcProductStockRepository(jdbcTemplate);
        stockRequestRepository = new JdbcStockRequestRepository(jdbcTemplate);
        stockMovementRepository = new JdbcStockMovementRepository(jdbcTemplate);
        commandReceiptRepository = new JdbcCommandReceiptRepository(jdbcTemplate);
    }

    @BeforeEach
    void setUp() throws IOException {
        PostgresTestHelper.clearAllTables(jdbcTemplate);
        SyntheticInventoryFixtures.seed(jdbcTemplate);

        // 1. Start real NIO notification server
        nioServer = new NioNotificationServer(0);
        nioServer.start();
        nioPort = nioServer.getPort();

        // 2. Connect real NIO subscriber socket
        nioSubscriberSocket = new Socket("127.0.0.1", nioPort);
        nioSubscriberSocket.setSoTimeout(3000);
        nioReader = new BufferedReader(new InputStreamReader(nioSubscriberSocket.getInputStream(), StandardCharsets.UTF_8));
        await().atMost(Duration.ofSeconds(3)).until(() -> nioServer.getConnectedClientCount() == 1);

        // 3. Initialize services
        MonotonicVersionProvider versionProvider = new DefaultMonotonicVersionProvider(100L);
        reservationService = new DefaultStockReservationService(
                productStockRepository,
                stockRequestRepository,
                commandReceiptRepository,
                Optional.of(nioServer),
                versionProvider,
                transactionManager
        );

        fulfillmentService = new DefaultStockFulfillmentService(
                stockRequestRepository,
                productStockRepository,
                stockMovementRepository,
                commandReceiptRepository,
                Optional.of(nioServer),
                versionProvider,
                transactionManager
        );

        // 4. Start real TCP command server
        dispatcher = new DefaultCommandDispatcher(reservationService, null, fulfillmentService);
        tcpServer = new TcpCommandServer(new TcpCommandServerConfig("127.0.0.1", 0), dispatcher);
        tcpServer.start();
        tcpPort = tcpServer.getPort();
    }

    @AfterEach
    void tearDown() {
        if (nioSubscriberSocket != null && !nioSubscriberSocket.isClosed()) {
            try {
                nioSubscriberSocket.close();
            } catch (IOException ignored) {
            }
        }
        if (tcpServer != null && tcpServer.isRunning()) {
            tcpServer.stop();
        }
        if (nioServer != null && nioServer.isRunning()) {
            nioServer.stop();
        }
    }

    @Test
    @Order(1)
    @DisplayName("1. Complete lifecycle: ACCEPTED -> PICKED -> DISPATCHED atomically updates stock and creates one movement")
    void shouldExecuteFullFulfillmentLifecycleOverTcpSockets() throws Exception {
        // Step A: REQUEST_STOCK for 8 units
        UUID stockRequestId = UUID.randomUUID();
        String reqJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                stockRequestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );
        JsonNode resStock = sendCommand(tcpPort, reqJson);
        assertThat(resStock.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Consume NIO event for reservation
        String nio1 = nioReader.readLine();
        assertThat(nio1).contains("\"type\":\"CHANGED\"");

        // Step B: MARK_PICKED
        UUID pickCmdId = UUID.randomUUID();
        String pickJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED,
                pickCmdId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode resPick = sendCommand(tcpPort, pickJson);

        assertThat(resPick.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
        assertThat(resPick.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PICKED);
        assertThat(resPick.get("requestId").asText()).isEqualTo(pickCmdId.toString());
        assertThat(resPick.get("data").get("stockRequestId").asText()).isEqualTo(stockRequestId.toString());
        assertThat(resPick.get("data").get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PICKED);

        // Picking does not change inventory balances
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Verify stock_requests status updated to PICKED
        String statusAfterPick = jdbcTemplate.queryForObject(
                "SELECT status FROM stock_requests WHERE request_id = ?",
                String.class, stockRequestId
        );
        assertThat(statusAfterPick).isEqualTo(ProtocolInfo.STATUS_PICKED);

        // Consume NIO event for picking
        String nio2 = nioReader.readLine();
        assertThat(nio2).contains("\"type\":\"CHANGED\"");

        // Step C: MARK_DISPATCHED
        UUID dispatchCmdId = UUID.randomUUID();
        String dispatchJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_DISPATCHED,
                dispatchCmdId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode resDispatch = sendCommand(tcpPort, dispatchJson);

        assertThat(resDispatch.get("v").asInt()).isEqualTo(ProtocolInfo.WIRE_VERSION);
        assertThat(resDispatch.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);
        assertThat(resDispatch.get("requestId").asText()).isEqualTo(dispatchCmdId.toString());
        assertThat(resDispatch.get("data").get("stockRequestId").asText()).isEqualTo(stockRequestId.toString());
        assertThat(resDispatch.get("data").get("status").asText()).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);

        // Step D: Verify atomic database state changes
        // on_hand decremented by 8 (10 -> 2), reserved decremented by 8 (8 -> 0), available = 2
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

        // Request status is DISPATCHED
        String statusAfterDispatch = jdbcTemplate.queryForObject(
                "SELECT status FROM stock_requests WHERE request_id = ?",
                String.class, stockRequestId
        );
        assertThat(statusAfterDispatch).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);

        // Exactly one stock movement record created
        Integer movementCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE stock_request_id = ?",
                Integer.class, stockRequestId
        );
        assertThat(movementCount).isEqualTo(1);

        Map<String, Object> movement = jdbcTemplate.queryForMap(
                "SELECT sku, quantity_delta, reason FROM stock_movements WHERE stock_request_id = ?",
                stockRequestId
        );
        assertThat(movement.get("sku")).isEqualTo(SyntheticInventoryFixtures.SKU_ITEM_007);
        assertThat(((Number) movement.get("quantity_delta")).intValue()).isEqualTo(-8);
        assertThat(movement.get("reason")).isEqualTo("DISPATCH");

        // Consume NIO event for dispatch
        String nio3 = nioReader.readLine();
        assertThat(nio3).contains("\"type\":\"CHANGED\"");
    }

    @Test
    @Order(2)
    @DisplayName("2. Replaying identical pick or dispatch returns recorded outcome without duplicate mutation")
    void shouldReplayPickAndDispatchIdempotently() throws Exception {
        UUID stockRequestId = UUID.randomUUID();
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                stockRequestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        ));

        // Pick
        UUID pickCmdId = UUID.randomUUID();
        String pickJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED,
                pickCmdId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode pick1 = sendCommand(tcpPort, pickJson);
        assertThat(pick1.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PICKED);

        // Replay pick
        JsonNode pick2 = sendCommand(tcpPort, pickJson);
        assertThat(pick2.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PICKED);
        assertThat(pick2.get("requestId").asText()).isEqualTo(pickCmdId.toString());

        // Dispatch
        UUID dispatchCmdId = UUID.randomUUID();
        String dispatchJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_DISPATCHED,
                dispatchCmdId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode dispatch1 = sendCommand(tcpPort, dispatchJson);
        assertThat(dispatch1.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

        // Replay dispatch over fresh socket
        JsonNode dispatch2 = sendCommand(tcpPort, dispatchJson);
        assertThat(dispatch2.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);
        assertThat(dispatch2.get("requestId").asText()).isEqualTo(dispatchCmdId.toString());

        // Balances still 2, 0, 2 (NOT decremented again!)
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

        // Exactly one movement record
        Integer movementCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE stock_request_id = ?",
                Integer.class, stockRequestId
        );
        assertThat(movementCount).isEqualTo(1);
    }

    @Test
    @Order(3)
    @DisplayName("3. Reusing command requestId with different stockRequestId returns DUPLICATE_ID_CONFLICT")
    void shouldRejectDuplicateCommandIdWithDifferentPayload() throws Exception {
        UUID stockReq1 = UUID.randomUUID();
        UUID stockReq2 = UUID.randomUUID();

        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK, stockReq1.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 4)
        ));
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK, stockReq2.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 4)
        ));

        UUID cmdId = UUID.randomUUID();
        String pick1 = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, cmdId.toString(),
                Map.of("stockRequestId", stockReq1.toString())
        );
        JsonNode res1 = sendCommand(tcpPort, pick1);
        assertThat(res1.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_PICKED);

        // Reuse cmdId for stockReq2
        String pick2 = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, cmdId.toString(),
                Map.of("stockRequestId", stockReq2.toString())
        );
        JsonNode res2 = sendCommand(tcpPort, pick2);

        assertThat(res2.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(res2.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT);
        assertThat(res2.get("error").get("message").asText()).contains("previously processed with a different payload");
    }

    @Test
    @Order(4)
    @DisplayName("4. Attempting to dispatch an unpicked request returns INVALID_TRANSITION and preserves DB state")
    void shouldRejectDispatchWhenRequestNotPicked() throws Exception {
        UUID stockRequestId = UUID.randomUUID();
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK, stockRequestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        ));
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Attempt direct dispatch while status is still ACCEPTED
        UUID dispatchCmdId = UUID.randomUUID();
        String dispatchJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_DISPATCHED, dispatchCmdId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode response = sendCommand(tcpPort, dispatchJson);

        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(response.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_INVALID_TRANSITION);
        assertThat(response.get("error").get("message").asText()).contains("only PICKED requests can be dispatched");

        // DB state untouched
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        Integer movementCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE stock_request_id = ?",
                Integer.class, stockRequestId
        );
        assertThat(movementCount).isEqualTo(0);
    }

    @Test
    @Order(5)
    @DisplayName("5. Attempting to pick an already dispatched request returns INVALID_TRANSITION")
    void shouldRejectPickWhenAlreadyDispatched() throws Exception {
        UUID stockRequestId = UUID.randomUUID();
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK, stockRequestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        ));
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, UUID.randomUUID().toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        ));
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_DISPATCHED, UUID.randomUUID().toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        ));

        // Attempt pick on DISPATCHED request with new command ID
        UUID newPickCmd = UUID.randomUUID();
        String pickJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, newPickCmd.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode res = sendCommand(tcpPort, pickJson);

        assertThat(res.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(res.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_INVALID_TRANSITION);
        assertThat(res.get("error").get("message").asText()).contains("only ACCEPTED requests can be picked");
    }

    @Test
    @Order(6)
    @DisplayName("6. Requesting non-existent stockRequestId returns NOT_FOUND")
    void shouldReturnNotFoundForUnknownStockRequest() throws Exception {
        UUID unknownId = UUID.randomUUID();
        String pickJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, UUID.randomUUID().toString(),
                Map.of("stockRequestId", unknownId.toString())
        );
        JsonNode res = sendCommand(tcpPort, pickJson);

        assertThat(res.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(res.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_NOT_FOUND);
        assertThat(res.get("error").get("message").asText()).contains("Stock request '" + unknownId + "' not found");
    }

    @Test
    @Order(7)
    @DisplayName("7. Concurrent dispatch requests on same stock request serialize: exactly 1 succeeds, 1 rejected")
    void shouldPreventConcurrentDuplicateDispatch() throws Exception {
        UUID stockRequestId = UUID.randomUUID();
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK, stockRequestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        ));
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, UUID.randomUUID().toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        ));

        // 2 threads attempt to dispatch the same request concurrently with different command IDs
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(2);

        List<JsonNode> responses = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        for (int i = 0; i < 2; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    UUID cmdId = UUID.randomUUID();
                    String req = TcpMessageCodec.formatRequest(
                            ProtocolInfo.COMMAND_MARK_DISPATCHED, cmdId.toString(),
                            Map.of("stockRequestId", stockRequestId.toString())
                    );
                    JsonNode res = sendCommand(tcpPort, req);
                    responses.add(res);

                    String status = res.get("status").asText();
                    if (ProtocolInfo.STATUS_DISPATCHED.equals(status)) {
                        successCount.incrementAndGet();
                    } else if (ProtocolInfo.STATUS_ERROR.equals(status)) {
                        conflictCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = doneGate.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(responses).hasSize(2);

        // Exactly one dispatch succeeds, the other receives INVALID_TRANSITION
        assertThat(successCount.get()).isEqualTo(1);
        assertThat(conflictCount.get()).isEqualTo(1);

        // Inventory is decremented only once (10 - 8 = 2)
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

        // Exactly one movement record in database
        Integer movementCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE stock_request_id = ?",
                Integer.class, stockRequestId
        );
        assertThat(movementCount).isEqualTo(1);
    }

    @Test
    @Order(8)
    @DisplayName("8. Failure of NIO notification broadcaster never rolls back committed dispatch transaction")
    void shouldEnsureDispatchSucceedsEvenIfNioBroadcasterFails() throws Exception {
        UUID stockRequestId = UUID.randomUUID();
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK, stockRequestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        ));
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, UUID.randomUUID().toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        ));

        // Stop NIO notification server
        nioServer.stop();
        assertThat(nioServer.isRunning()).isFalse();

        // Dispatch over TCP
        UUID dispatchCmdId = UUID.randomUUID();
        String dispatchJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_DISPATCHED, dispatchCmdId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        JsonNode response = sendCommand(tcpPort, dispatchJson);

        // Must succeed with DISPATCHED
        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movements WHERE stock_request_id = ?",
                Integer.class, stockRequestId
        );
        assertThat(count).isEqualTo(1);
    }

    @Test
    @Order(9)
    @DisplayName("9. GET_REQUESTS and GET_MOVEMENTS return accurate query results over TCP")
    void shouldQueryRequestsAndMovementsOverTcp() throws Exception {
        UUID stockRequestId = UUID.randomUUID();
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK, stockRequestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        ));
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, UUID.randomUUID().toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        ));
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_DISPATCHED, UUID.randomUUID().toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        ));

        // Query GET_REQUESTS
        String getReqJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_REQUESTS, UUID.randomUUID().toString(),
                Map.of("status", "DISPATCHED")
        );
        JsonNode reqResult = sendCommand(tcpPort, getReqJson);

        assertThat(reqResult.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        JsonNode requests = reqResult.get("data").get("requests");
        assertThat(requests.isArray()).isTrue();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).get("requestId").asText()).isEqualTo(stockRequestId.toString());
        assertThat(requests.get(0).get("status").asText()).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);

        // Query GET_MOVEMENTS
        String getMovJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_GET_MOVEMENTS, UUID.randomUUID().toString(), null
        );
        JsonNode movResult = sendCommand(tcpPort, getMovJson);

        assertThat(movResult.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        JsonNode movements = movResult.get("data").get("movements");
        assertThat(movements.isArray()).isTrue();
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).get("stockRequestId").asText()).isEqualTo(stockRequestId.toString());
        assertThat(movements.get(0).get("quantityDelta").asInt()).isEqualTo(-8);
        assertThat(movements.get(0).get("reason").asText()).isEqualTo("DISPATCH");
    }

    @Test
    @Order(10)
    @DisplayName("10. Valid pick and dispatch outcomes persist across server restart")
    void shouldPersistPickAndDispatchAcrossServerRestart() throws Exception {
        UUID stockRequestId = UUID.randomUUID();
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK, stockRequestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        ));
        sendCommand(tcpPort, TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_PICKED, UUID.randomUUID().toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        ));
        UUID dispatchCmdId = UUID.randomUUID();
        String dispatchJson = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_MARK_DISPATCHED, dispatchCmdId.toString(),
                Map.of("stockRequestId", stockRequestId.toString())
        );
        sendCommand(tcpPort, dispatchJson);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

        // Stop current TCP server
        tcpServer.stop();
        assertThat(tcpServer.isRunning()).isFalse();

        // Start brand-new TCP server on dynamic port pointing to the same PostgreSQL DB
        DefaultCommandDispatcher newDispatcher = new DefaultCommandDispatcher(reservationService, null, fulfillmentService);
        TcpCommandServer restartedServer = new TcpCommandServer(new TcpCommandServerConfig("127.0.0.1", 0), newDispatcher);
        restartedServer.start();
        int newPort = restartedServer.getPort();

        try {
            // Replay dispatch command to restarted server
            JsonNode rebootRes = sendCommand(newPort, dispatchJson);
            assertThat(rebootRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_DISPATCHED);
            assertThat(rebootRes.get("requestId").asText()).isEqualTo(dispatchCmdId.toString());

            // Balances remain intact
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

            // Exactly 1 movement record survives reboot
            Integer movementCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_movements WHERE stock_request_id = ?",
                    Integer.class, stockRequestId
            );
            assertThat(movementCount).isEqualTo(1);
        } finally {
            restartedServer.stop();
        }
    }

    @Test
    @Order(11)
    @DisplayName("11. Transaction rollback atomicity on dispatch error: zero residual movements or stock decrements")
    void shouldRollbackAtomicallyWhenDispatchFailsMidTransaction() {
        UUID stockRequestId = UUID.randomUUID();
        UUID cmdId = UUID.randomUUID();

        // Stock request does not exist -> fails with NOT_FOUND inside transaction
        FulfillmentResult result = fulfillmentService.dispatchStock(cmdId, stockRequestId);

        assertThat(result.isError()).isTrue();
        assertThat(result.errorCode()).isEqualTo(ProtocolInfo.ERROR_NOT_FOUND);

        // Zero movements recorded
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_movements", Integer.class
        );
        assertThat(count).isEqualTo(0);

        // Initial balances untouched
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 0, 10);
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
