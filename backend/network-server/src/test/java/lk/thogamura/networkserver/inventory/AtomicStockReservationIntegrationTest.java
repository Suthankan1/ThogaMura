package lk.thogamura.networkserver.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.networkserver.concurrent.InventoryWorkerPool;
import lk.thogamura.networkserver.notifications.ChangeNotification;
import lk.thogamura.networkserver.notifications.NotificationPublisher;
import lk.thogamura.networkserver.persistence.CommandReceiptRepository;
import lk.thogamura.networkserver.persistence.JdbcCommandReceiptRepository;
import lk.thogamura.networkserver.persistence.JdbcProductStockRepository;
import lk.thogamura.networkserver.persistence.JdbcStockRequestRepository;
import lk.thogamura.networkserver.persistence.PostgresTestHelper;
import lk.thogamura.networkserver.persistence.ProductStockRepository;
import lk.thogamura.networkserver.persistence.StockRequestRecord;
import lk.thogamura.networkserver.persistence.StockRequestRepository;
import lk.thogamura.protocol.ProtocolInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Integration test suite verifying atomic stock reservations and durable command receipts
 * against real PostgreSQL 17 matching Issue #10 requirements.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtomicStockReservationIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JdbcTemplate jdbcTemplate;
    private static PlatformTransactionManager transactionManager;
    private static ProductStockRepository productStockRepository;
    private static StockRequestRepository stockRequestRepository;
    private static CommandReceiptRepository commandReceiptRepository;

    private RecordingNotificationPublisher recordingPublisher;
    private MonotonicVersionProvider versionProvider;
    private DefaultStockReservationService reservationService;

    @BeforeAll
    static void initDatabase() {
        jdbcTemplate = PostgresTestHelper.getJdbcTemplate();
        transactionManager = PostgresTestHelper.getTransactionManager();
        productStockRepository = new JdbcProductStockRepository(jdbcTemplate);
        stockRequestRepository = new JdbcStockRequestRepository(jdbcTemplate);
        commandReceiptRepository = new JdbcCommandReceiptRepository(jdbcTemplate);
    }

    @BeforeEach
    void resetState() {
        PostgresTestHelper.clearAllTables(jdbcTemplate);
        SyntheticInventoryFixtures.seed(jdbcTemplate);

        recordingPublisher = new RecordingNotificationPublisher();
        versionProvider = new DefaultMonotonicVersionProvider(100L);
        reservationService = new DefaultStockReservationService(
                productStockRepository,
                stockRequestRepository,
                commandReceiptRepository,
                Optional.of(recordingPublisher),
                versionProvider,
                transactionManager
        );
    }

    @Test
    @Order(1)
    @DisplayName("1. Request 8 from 10 available succeeds: reserved=8, available=2, receipt recorded")
    void shouldAcceptReservationWhenStockSufficient() {
        UUID requestId = UUID.randomUUID();
        ReservationResult result = reservationService.reserveStock(
                requestId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );

        // Verify response result
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.replayed()).isFalse();
        assertThat(result.status()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertThat(result.responseJson()).contains("\"status\":\"ACCEPTED\"");
        assertThat(result.responseJson()).contains("\"quantity\":8");
        assertThat(result.responseJson()).contains("\"sku\":\"ITEM-007\"");

        // Direct database assertion on product_stock
        Map<String, Object> stockRow = jdbcTemplate.queryForMap(
                "SELECT on_hand, reserved, (on_hand - reserved) AS available FROM product_stock WHERE sku = ?",
                SyntheticInventoryFixtures.SKU_ITEM_007
        );
        assertThat(((Number) stockRow.get("on_hand")).intValue()).isEqualTo(10);
        assertThat(((Number) stockRow.get("reserved")).intValue()).isEqualTo(8);
        assertThat(((Number) stockRow.get("available")).intValue()).isEqualTo(2);

        // Direct database assertion on stock_requests
        Map<String, Object> requestRow = jdbcTemplate.queryForMap(
                "SELECT request_id, sku, quantity, status, decision_reason FROM stock_requests WHERE request_id = ?",
                requestId
        );
        assertThat(requestRow.get("sku")).isEqualTo("ITEM-007");
        assertThat(((Number) requestRow.get("quantity")).intValue()).isEqualTo(8);
        assertThat(requestRow.get("status")).isEqualTo("ACCEPTED");
        assertThat(requestRow.get("decision_reason")).isNull();

        // Direct database assertion on command_receipts
        Map<String, Object> receiptRow = jdbcTemplate.queryForMap(
                "SELECT command_request_id, command_type, result_json::text AS json FROM command_receipts WHERE command_request_id = ?",
                requestId
        );
        assertThat(receiptRow.get("command_type")).isEqualTo(ProtocolInfo.COMMAND_REQUEST_STOCK);
        assertThat((String) receiptRow.get("json")).contains("\"ACCEPTED\"");
    }

    @Test
    @Order(2)
    @DisplayName("2. Request above available quantity is rejected: stock unchanged, rejection receipt persisted")
    void shouldRejectReservationWhenQuantityExceedsAvailable() {
        UUID req1 = UUID.randomUUID();
        reservationService.reserveStock(req1, SyntheticInventoryFixtures.SKU_ITEM_007, 8);

        // Stock available is now 2. Second request asks for 8.
        UUID req2 = UUID.randomUUID();
        ReservationResult result2 = reservationService.reserveStock(
                req2, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );

        assertThat(result2.isRejected()).isTrue();
        assertThat(result2.errorCode()).isEqualTo(ProtocolInfo.ERROR_INSUFFICIENT_STOCK);
        assertThat(result2.errorMessage()).contains("exceeds available stock 2");
        assertThat(result2.responseJson()).contains("\"status\":\"REJECTED\"");
        assertThat(result2.responseJson()).contains("\"code\":\"INSUFFICIENT_STOCK\"");

        // Database assertion: stock reserved remains 8, available remains 2
        Map<String, Object> stockRow = jdbcTemplate.queryForMap(
                "SELECT on_hand, reserved, (on_hand - reserved) AS available FROM product_stock WHERE sku = ?",
                SyntheticInventoryFixtures.SKU_ITEM_007
        );
        assertThat(((Number) stockRow.get("on_hand")).intValue()).isEqualTo(10);
        assertThat(((Number) stockRow.get("reserved")).intValue()).isEqualTo(8);
        assertThat(((Number) stockRow.get("available")).intValue()).isEqualTo(2);

        // Database assertion: stock_requests records REJECTED with reason
        Map<String, Object> requestRow = jdbcTemplate.queryForMap(
                "SELECT status, decision_reason FROM stock_requests WHERE request_id = ?",
                req2
        );
        assertThat(requestRow.get("status")).isEqualTo("REJECTED");
        assertThat((String) requestRow.get("decision_reason")).contains("exceeds available stock 2");

        // Database assertion: command_receipts records REJECTED outcome for replay
        Map<String, Object> receiptRow = jdbcTemplate.queryForMap(
                "SELECT result_json::text AS json FROM command_receipts WHERE command_request_id = ?",
                req2
        );
        assertThat((String) receiptRow.get("json")).contains("\"REJECTED\"").contains("\"INSUFFICIENT_STOCK\"");
    }

    @Test
    @Order(3)
    @DisplayName("3. Database balances remain correct: available = on_hand - reserved >= 0 holds across steps")
    void shouldMaintainAccurateBalances() {
        // Reserve 3
        reservationService.reserveStock(UUID.randomUUID(), SyntheticInventoryFixtures.SKU_ITEM_007, 3);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 3, 7);

        // Reserve 5
        reservationService.reserveStock(UUID.randomUUID(), SyntheticInventoryFixtures.SKU_ITEM_007, 5);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Reserve 2
        reservationService.reserveStock(UUID.randomUUID(), SyntheticInventoryFixtures.SKU_ITEM_007, 2);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 10, 0);

        // Try to reserve 1 more -> rejected!
        ReservationResult rejected = reservationService.reserveStock(
                UUID.randomUUID(), SyntheticInventoryFixtures.SKU_ITEM_007, 1
        );
        assertThat(rejected.isRejected()).isTrue();
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 10, 0);
    }

    @Test
    @Order(4)
    @DisplayName("4. Two concurrent requests cannot oversubscribe stock: at most one accepted from 10 available")
    void shouldPreventOversubscriptionUnderConcurrentRequests() throws Exception {
        UUID reqA = UUID.randomUUID();
        UUID reqB = UUID.randomUUID();

        try (InventoryWorkerPool pool = new InventoryWorkerPool(2, 4, 10)) {
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(2);

            List<ReservationResult> results = Collections.synchronizedList(new ArrayList<>());

            pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(reservationService.reserveStock(reqA, SyntheticInventoryFixtures.SKU_ITEM_007, 8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneGate.countDown();
                }
            });

            pool.submit(() -> {
                try {
                    startGate.await();
                    results.add(reservationService.reserveStock(reqB, SyntheticInventoryFixtures.SKU_ITEM_007, 8));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneGate.countDown();
                }
            });

            // Release both threads simultaneously
            startGate.countDown();
            boolean completed = doneGate.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            assertThat(results).hasSize(2);
            long acceptedCount = results.stream().filter(ReservationResult::isAccepted).count();
            long rejectedCount = results.stream().filter(ReservationResult::isRejected).count();

            assertThat(acceptedCount).isEqualTo(1);
            assertThat(rejectedCount).isEqualTo(1);

            // Verify database balances: exactly 8 reserved, 2 available (never 16!)
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);
        }
    }

    @Test
    @Order(5)
    @DisplayName("5. Same request ID replays the original outcome for both ACCEPTED and REJECTED commands")
    void shouldReplayOriginalOutcomeOnDuplicateRequestId() {
        // Test replay of ACCEPTED request
        UUID acceptedId = UUID.randomUUID();
        ReservationResult firstAccepted = reservationService.reserveStock(
                acceptedId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );
        assertThat(firstAccepted.isAccepted()).isTrue();
        assertThat(firstAccepted.replayed()).isFalse();

        // Replay identical command
        ReservationResult replayedAccepted = reservationService.reserveStock(
                acceptedId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );
        assertThat(replayedAccepted.isAccepted()).isTrue();
        assertThat(replayedAccepted.replayed()).isTrue();
        assertThat(parseJsonNode(replayedAccepted.responseJson()))
                .isEqualTo(parseJsonNode(firstAccepted.responseJson()));

        // Stock reserved is STILL 8 (not 16!)
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Test replay of REJECTED request
        UUID rejectedId = UUID.randomUUID();
        ReservationResult firstRejected = reservationService.reserveStock(
                rejectedId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );
        assertThat(firstRejected.isRejected()).isTrue();
        assertThat(firstRejected.replayed()).isFalse();

        // Replay rejected command
        ReservationResult replayedRejected = reservationService.reserveStock(
                rejectedId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );
        assertThat(replayedRejected.isRejected()).isTrue();
        assertThat(replayedRejected.replayed()).isTrue();
        assertThat(parseJsonNode(replayedRejected.responseJson()))
                .isEqualTo(parseJsonNode(firstRejected.responseJson()));
    }

    @Test
    @Order(6)
    @DisplayName("6. Different payload with the same request ID returns DUPLICATE_ID_CONFLICT")
    void shouldReturnConflictWhenPayloadDiffersForSameRequestId() {
        UUID reqId = UUID.randomUUID();
        ReservationResult first = reservationService.reserveStock(
                reqId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );
        assertThat(first.isAccepted()).isTrue();

        // Attempt reuse of same requestId with DIFFERENT quantity (5 instead of 8)
        ReservationResult conflictQuantity = reservationService.reserveStock(
                reqId, SyntheticInventoryFixtures.SKU_ITEM_007, 5
        );
        assertThat(conflictQuantity.isError()).isTrue();
        assertThat(conflictQuantity.isConflict()).isTrue();
        assertThat(conflictQuantity.errorCode()).isEqualTo(ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT);
        assertThat(conflictQuantity.errorMessage()).contains("different payload");
        assertThat(conflictQuantity.responseJson()).contains("DUPLICATE_ID_CONFLICT");

        // Attempt reuse of same requestId with DIFFERENT SKU
        ReservationResult conflictSku = reservationService.reserveStock(
                reqId, SyntheticInventoryFixtures.SKU_ITEM_008, 8
        );
        assertThat(conflictSku.isError()).isTrue();
        assertThat(conflictSku.isConflict()).isTrue();
        assertThat(conflictSku.errorCode()).isEqualTo(ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT);

        // Stock reserved remains 8 from first request
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);
    }

    @Test
    @Order(7)
    @DisplayName("7. Server restart preserves durable receipts and avoids duplicate reservation")
    void shouldPreserveDurableReceiptsAcrossSimulatedRestart() {
        UUID reqId = UUID.randomUUID();
        ReservationResult initial = reservationService.reserveStock(
                reqId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );
        assertThat(initial.isAccepted()).isTrue();

        // Simulate complete server restart: instantiate brand new repositories and service
        ProductStockRepository freshStockRepo = new JdbcProductStockRepository(jdbcTemplate);
        StockRequestRepository freshRequestRepo = new JdbcStockRequestRepository(jdbcTemplate);
        CommandReceiptRepository freshReceiptRepo = new JdbcCommandReceiptRepository(jdbcTemplate);
        DefaultStockReservationService freshService = new DefaultStockReservationService(
                freshStockRepo,
                freshRequestRepo,
                freshReceiptRepo,
                Optional.empty(),
                new DefaultMonotonicVersionProvider(200L),
                transactionManager
        );

        // Replay command on the new service instance
        ReservationResult replayedAfterRestart = freshService.reserveStock(
                reqId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );

        assertThat(replayedAfterRestart.isAccepted()).isTrue();
        assertThat(replayedAfterRestart.replayed()).isTrue();
        assertThat(parseJsonNode(replayedAfterRestart.responseJson()))
                .isEqualTo(parseJsonNode(initial.responseJson()));

        // Stock balance untouched
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);
    }

    @Test
    @Order(8)
    @DisplayName("8. No partial database changes survive transaction rollback")
    void shouldRollbackCompletelyOnFailure() {
        UUID reqId = UUID.randomUUID();
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> txTemplate.execute(status -> {
            // Step 1: Update stock
            productStockRepository.updateReserved(SyntheticInventoryFixtures.SKU_ITEM_007, 8);

            // Step 2: Insert stock request
            stockRequestRepository.insert(StockRequestRecord.accepted(
                    reqId, "a".repeat(64), SyntheticInventoryFixtures.SKU_ITEM_007, 8
            ));

            // Step 3: Deliberately throw an exception before commit
            throw new RuntimeException("Simulated catastrophic crash before commit");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated catastrophic crash");

        // Verify that product_stock was NOT modified
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 0, 10);

        // Verify that stock_requests has no row
        Integer reqCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_requests WHERE request_id = ?",
                Integer.class, reqId
        );
        assertThat(reqCount).isEqualTo(0);

        // Verify that command_receipts has no row
        Integer receiptCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_receipts WHERE command_request_id = ?",
                Integer.class, reqId
        );
        assertThat(receiptCount).isEqualTo(0);
    }

    @Test
    @Order(9)
    @DisplayName("9. Exactly one effective reservation occurs under concurrent duplicate delivery (10 threads)")
    void shouldHandleConcurrentDuplicateDeliveriesIdempotently() throws Exception {
        UUID duplicateId = UUID.randomUUID();
        int threadCount = 10;

        try (InventoryWorkerPool pool = new InventoryWorkerPool(threadCount, threadCount, 20)) {
            CountDownLatch startGate = new CountDownLatch(1);
            CountDownLatch doneGate = new CountDownLatch(threadCount);

            List<ReservationResult> results = Collections.synchronizedList(new ArrayList<>());
            List<Future<?>> futures = new ArrayList<>();

            for (int i = 0; i < threadCount; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        startGate.await();
                        results.add(reservationService.reserveStock(
                                duplicateId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
                        ));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        doneGate.countDown();
                    }
                }));
            }

            // Release all 10 threads concurrently
            startGate.countDown();
            boolean completed = doneGate.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();

            for (Future<?> f : futures) {
                f.get();
            }

            // All 10 callers receive ACCEPTED with identical response JSON
            assertThat(results).hasSize(threadCount);
            for (ReservationResult res : results) {
                assertThat(res.isAccepted()).isTrue();
                assertThat(parseJsonNode(res.responseJson()).path("status").asText())
                        .isEqualTo("ACCEPTED");
            }

            // In DB: Reserved is EXACTLY 8 (NOT 80!)
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

            // In DB: Exactly 1 row in stock_requests
            Integer requestCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_requests WHERE request_id = ?",
                    Integer.class, duplicateId
            );
            assertThat(requestCount).isEqualTo(1);

            // In DB: Exactly 1 row in command_receipts
            Integer receiptCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM command_receipts WHERE command_request_id = ?",
                    Integer.class, duplicateId
            );
            assertThat(receiptCount).isEqualTo(1);
        }
    }

    @Test
    @Order(10)
    @DisplayName("10. Java NIO notification is published post-commit for ACCEPTED and REJECTED requests, but NOT on replay (#59)")
    void shouldPublishNotificationOnlyAfterCommitAndNotOnReplay() {
        // Step 1: Valid reservation (ACCEPTED) triggers notification publication
        UUID req1 = UUID.randomUUID();
        reservationService.reserveStock(req1, SyntheticInventoryFixtures.SKU_ITEM_007, 8);
        assertThat(recordingPublisher.getPublishCount()).isEqualTo(1);
        assertThat(recordingPublisher.getLastPublishedVersion()).isEqualTo(101L);

        // Step 2: Replay of same command must NOT publish an event
        reservationService.reserveStock(req1, SyntheticInventoryFixtures.SKU_ITEM_007, 8);
        assertThat(recordingPublisher.getPublishCount()).isEqualTo(1); // unchanged!

        // Step 3: Rejected reservation (insufficient stock) persists request and receipt and DOES publish an event (#59)
        UUID req2 = UUID.randomUUID();
        reservationService.reserveStock(req2, SyntheticInventoryFixtures.SKU_ITEM_007, 8);
        assertThat(recordingPublisher.getPublishCount()).isEqualTo(2);
        assertThat(recordingPublisher.getLastPublishedVersion()).isEqualTo(102L);

        // Step 4: Replay of same REJECTED command must NOT publish a duplicate event
        reservationService.reserveStock(req2, SyntheticInventoryFixtures.SKU_ITEM_007, 8);
        assertThat(recordingPublisher.getPublishCount()).isEqualTo(2); // unchanged!

        // Step 5: Validation error (non-positive quantity) does NOT persist or publish
        UUID req3 = UUID.randomUUID();
        reservationService.reserveStock(req3, SyntheticInventoryFixtures.SKU_ITEM_007, -1);
        assertThat(recordingPublisher.getPublishCount()).isEqualTo(2); // unchanged!
    }

    @Test
    @Order(11)
    @DisplayName("11. Best-effort Java NIO notification failure does NOT rollback committed reservation")
    void shouldNotRollbackWhenNotificationFails() {
        NotificationPublisher failingPublisher = notification -> {
            throw new RuntimeException("Simulated NIO network failure");
        };

        DefaultStockReservationService resilientService = new DefaultStockReservationService(
                productStockRepository,
                stockRequestRepository,
                commandReceiptRepository,
                Optional.of(failingPublisher),
                versionProvider,
                transactionManager
        );

        UUID reqId = UUID.randomUUID();
        ReservationResult result = resilientService.reserveStock(
                reqId, SyntheticInventoryFixtures.SKU_ITEM_007, 8
        );

        // Reservation still succeeded despite notification failure
        assertThat(result.isAccepted()).isTrue();
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Receipt is persisted in DB
        Integer receiptCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_receipts WHERE command_request_id = ?",
                Integer.class, reqId
        );
        assertThat(receiptCount).isEqualTo(1);
    }

    @Test
    @Order(12)
    @DisplayName("12. Unknown SKU returns NOT_FOUND error without database corruption")
    void shouldReturnNotFoundForNonExistentSku() {
        UUID reqId = UUID.randomUUID();
        ReservationResult result = reservationService.reserveStock(reqId, "UNKNOWN-SKU", 5);

        assertThat(result.isError()).isTrue();
        assertThat(result.errorCode()).isEqualTo(ProtocolInfo.ERROR_NOT_FOUND);
        assertThat(result.responseJson()).contains("\"code\":\"NOT_FOUND\"");

        // Foreign key integrity preserved: no row inserted in stock_requests
        Integer reqCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_requests WHERE request_id = ?",
                Integer.class, reqId
        );
        assertThat(reqCount).isEqualTo(0);
    }

    @Test
    @Order(13)
    @DisplayName("13. Negative or zero quantity returns VALIDATION_ERROR")
    void shouldReturnValidationErrorForNonPositiveQuantity() {
        UUID reqIdZero = UUID.randomUUID();
        ReservationResult resZero = reservationService.reserveStock(
                reqIdZero, SyntheticInventoryFixtures.SKU_ITEM_007, 0
        );
        assertThat(resZero.isError()).isTrue();
        assertThat(resZero.errorCode()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);

        UUID reqIdNeg = UUID.randomUUID();
        ReservationResult resNeg = reservationService.reserveStock(
                reqIdNeg, SyntheticInventoryFixtures.SKU_ITEM_007, -5
        );
        assertThat(resNeg.isError()).isTrue();
        assertThat(resNeg.errorCode()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
    }

    @Test
    @Order(14)
    @DisplayName("14. Processes raw JSON command frame matching Protocol v1 envelope")
    void shouldProcessRawJsonFrame() {
        UUID reqId = UUID.randomUUID();
        String frameJson = """
            {
              "v": 1,
              "type": "REQUEST_STOCK",
              "requestId": "%s",
              "data": {
                "sku": "ITEM-007",
                "quantity": 8
              }
            }
            """.formatted(reqId);

        ReservationResult result = reservationService.processReservationCommand(frameJson);
        assertThat(result.isAccepted()).isTrue();
        assertThat(result.requestId()).isEqualTo(reqId);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);
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

    private static JsonNode parseJsonNode(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse JSON: " + json, e);
        }
    }

    private static class RecordingNotificationPublisher implements NotificationPublisher {
        private final AtomicInteger publishCount = new AtomicInteger(0);
        private volatile long lastPublishedVersion = -1;

        @Override
        public void publish(ChangeNotification notification) {
            publishCount.incrementAndGet();
            this.lastPublishedVersion = notification.version();
        }

        public int getPublishCount() {
            return publishCount.get();
        }

        public long getLastPublishedVersion() {
            return lastPublishedVersion;
        }
    }
}
