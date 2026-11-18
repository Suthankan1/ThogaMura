package lk.thogamura.networkserver.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.networkserver.notifications.NioNotificationServer;
import lk.thogamura.networkserver.notifications.NotificationServerConfig;
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end integration test verifying that version-only Java NIO notifications
 * are published strictly after database transaction commit over real sockets.
 *
 * <pre>
 *   [Client Socket] --- TCP REQUEST_STOCK ---> [TcpCommandServer]
 *                                                    ↓
 *                                         [PostgreSQL Transaction]
 *                                                    ↓ (afterCommit)
 *                                         [NioNotificationServer] ---> [NIO Subscriber Socket]
 * </pre>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PostCommitNioNotificationIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static JdbcTemplate jdbcTemplate;
    private static PlatformTransactionManager transactionManager;
    private static ProductStockRepository productStockRepository;
    private static StockRequestRepository stockRequestRepository;
    private static CommandReceiptRepository commandReceiptRepository;

    private NioNotificationServer nioServer;
    private int nioPort;
    private Socket nioSubscriberSocket;
    private BufferedReader nioReader;

    private DefaultStockReservationService reservationService;
    private DefaultCommandDispatcher dispatcher;
    private TcpCommandServer tcpServer;
    private int tcpPort;

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

        // 1. Start real NIO notification server on port 0 (loopback)
        nioServer = new NioNotificationServer(0);
        nioServer.start();
        nioPort = nioServer.getPort();

        // 2. Connect a real NIO subscriber socket
        nioSubscriberSocket = new Socket("127.0.0.1", nioPort);
        nioSubscriberSocket.setSoTimeout(3000);
        nioReader = new BufferedReader(new InputStreamReader(nioSubscriberSocket.getInputStream(), StandardCharsets.UTF_8));

        // Wait for NIO server to register client session
        await().atMost(Duration.ofSeconds(3)).until(() -> nioServer.getConnectedClientCount() == 1);

        // 3. Wire DefaultStockReservationService with NIO server as publisher
        MonotonicVersionProvider versionProvider = new DefaultMonotonicVersionProvider(100L);
        reservationService = new DefaultStockReservationService(
                productStockRepository,
                stockRequestRepository,
                commandReceiptRepository,
                Optional.of(nioServer),
                versionProvider,
                transactionManager
        );

        // 4. Start real TCP command server on port 0
        dispatcher = new DefaultCommandDispatcher(reservationService);
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
    @DisplayName("1. Committed stock reservation produces version-only NIO notification with zero leaked details")
    void shouldPublishVersionOnlyNioNotificationPostCommit() throws Exception {
        UUID requestId = UUID.randomUUID();
        String request = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );

        // 1. Send command over TCP
        JsonNode response = sendCommand(tcpPort, request);
        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);

        // 2. Database state must be committed
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // 3. NIO subscriber receives single-line version-only notification
        String notificationLine = nioReader.readLine();
        assertThat(notificationLine).isNotNull();

        JsonNode notificationNode = OBJECT_MAPPER.readTree(notificationLine);
        assertThat(notificationNode.get("v").asInt()).isEqualTo(1);
        assertThat(notificationNode.get("type").asText()).isEqualTo("CHANGED");
        assertThat(notificationNode.get("version").asLong()).isEqualTo(101L);

        // 4. Strict privacy verification: NO business data leaked
        assertThat(notificationLine).doesNotContain(SyntheticInventoryFixtures.SKU_ITEM_007);
        assertThat(notificationLine).doesNotContain("quantity");
        assertThat(notificationLine).doesNotContain("requestId");
        assertThat(notificationLine).doesNotContain("reserved");
        assertThat(notificationLine).doesNotContain("on_hand");
    }

    @Test
    @Order(2)
    @DisplayName("2. Replaying identical command does NOT produce a false new NIO change notification")
    void shouldNotPublishNioNotificationOnDuplicateReplay() throws Exception {
        UUID requestId = UUID.randomUUID();
        String request = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );

        // First execution -> commits and publishes version 101
        JsonNode firstRes = sendCommand(tcpPort, request);
        assertThat(firstRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        String firstNotification = nioReader.readLine();
        assertThat(firstNotification).contains("\"version\":101");

        // Replay same command over TCP
        JsonNode replayRes = sendCommand(tcpPort, request);
        assertThat(replayRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);

        // Ensure NO new notification is received
        nioSubscriberSocket.setSoTimeout(500);
        assertThatThrownBy(() -> nioReader.readLine())
                .isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @Order(3)
    @DisplayName("3. Rejected stock reservation does NOT produce an NIO notification")
    void shouldNotPublishNioNotificationOnRejectedRequest() throws Exception {
        // Reserve 8 first
        UUID firstId = UUID.randomUUID();
        String firstReq = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                firstId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );
        sendCommand(tcpPort, firstReq);
        String firstNotification = nioReader.readLine();
        assertThat(firstNotification).contains("\"version\":101");

        // Now attempt to reserve 5 when available is 2 -> REJECTED
        UUID secondId = UUID.randomUUID();
        String secondReq = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                secondId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 5)
        );
        JsonNode secondRes = sendCommand(tcpPort, secondReq);
        assertThat(secondRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_REJECTED);

        // Ensure NO new notification is received
        nioSubscriberSocket.setSoTimeout(500);
        assertThatThrownBy(() -> nioReader.readLine())
                .isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @Order(4)
    @DisplayName("4. Conflicting request ID does NOT produce an NIO notification")
    void shouldNotPublishNioNotificationOnDuplicateConflict() throws Exception {
        UUID requestId = UUID.randomUUID();
        String initialReq = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );
        sendCommand(tcpPort, initialReq);
        String initialNotification = nioReader.readLine();
        assertThat(initialNotification).contains("\"version\":101");

        // Send same requestId with different quantity
        String conflictReq = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 3)
        );
        JsonNode conflictRes = sendCommand(tcpPort, conflictReq);
        assertThat(conflictRes.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ERROR);
        assertThat(conflictRes.get("error").get("code").asText()).isEqualTo(ProtocolInfo.ERROR_DUPLICATE_ID_CONFLICT);

        // Ensure NO new notification is received
        nioSubscriberSocket.setSoTimeout(500);
        assertThatThrownBy(() -> nioReader.readLine())
                .isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @Order(5)
    @DisplayName("5. Best-effort NIO failure does NOT rollback committed PostgreSQL transaction")
    void shouldEnsureStockReservationSucceedsEvenIfNioServerFails() throws Exception {
        // Stop the NIO notification server completely to simulate downstream broadcast failure
        nioServer.stop();
        assertThat(nioServer.isRunning()).isFalse();

        // Send a valid reservation request over TCP
        UUID requestId = UUID.randomUUID();
        String request = TcpMessageCodec.formatRequest(
                ProtocolInfo.COMMAND_REQUEST_STOCK,
                requestId.toString(),
                Map.of("sku", SyntheticInventoryFixtures.SKU_ITEM_007, "quantity", 8)
        );

        JsonNode response = sendCommand(tcpPort, request);

        // The transaction must still commit successfully and return ACCEPTED
        assertThat(response.get("status").asText()).isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
        assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

        // Durable receipt is present in PostgreSQL
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM command_receipts WHERE command_request_id = ?",
                Integer.class,
                requestId
        );
        assertThat(count).isEqualTo(1);
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
