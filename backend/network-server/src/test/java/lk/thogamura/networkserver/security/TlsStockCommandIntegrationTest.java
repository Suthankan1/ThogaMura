package lk.thogamura.networkserver.security;

import com.fasterxml.jackson.databind.JsonNode;
import lk.thogamura.networkserver.fulfillment.DefaultStockFulfillmentService;
import lk.thogamura.networkserver.inventory.DefaultMonotonicVersionProvider;
import lk.thogamura.networkserver.inventory.DefaultStockReservationService;
import lk.thogamura.networkserver.inventory.SyntheticInventoryFixtures;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that real stock commands cross JSSE TLS to PostgreSQL-backed inventory services.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TlsStockCommandIntegrationTest {

    private static TlsTestCertificateHelper.CertBundle certificateBundle;
    private static JdbcTemplate jdbcTemplate;
    private static PlatformTransactionManager transactionManager;
    private static ProductStockRepository productStockRepository;
    private static StockRequestRepository stockRequestRepository;
    private static StockMovementRepository stockMovementRepository;
    private static CommandReceiptRepository commandReceiptRepository;

    private TcpCommandServer server;
    private int serverPort;

    @BeforeAll
    static void initialize(@TempDir Path tempDir) throws Exception {
        certificateBundle = TlsTestCertificateHelper.generateValidBundle(tempDir);
        jdbcTemplate = PostgresTestHelper.getJdbcTemplate();
        transactionManager = PostgresTestHelper.getTransactionManager();
        productStockRepository = new JdbcProductStockRepository(jdbcTemplate);
        stockRequestRepository = new JdbcStockRequestRepository(jdbcTemplate);
        stockMovementRepository = new JdbcStockMovementRepository(jdbcTemplate);
        commandReceiptRepository = new JdbcCommandReceiptRepository(jdbcTemplate);
    }

    @BeforeEach
    void startTlsServer() throws Exception {
        PostgresTestHelper.clearAllTables(jdbcTemplate);
        SyntheticInventoryFixtures.seed(jdbcTemplate);

        DefaultMonotonicVersionProvider versionProvider =
                new DefaultMonotonicVersionProvider(100L);

        DefaultStockReservationService reservationService =
                new DefaultStockReservationService(
                        productStockRepository,
                        stockRequestRepository,
                        commandReceiptRepository,
                        Optional.empty(),
                        versionProvider,
                        transactionManager
                );

        DefaultStockFulfillmentService fulfillmentService =
                new DefaultStockFulfillmentService(
                        stockRequestRepository,
                        productStockRepository,
                        stockMovementRepository,
                        commandReceiptRepository,
                        Optional.empty(),
                        versionProvider,
                        transactionManager
                );

        DefaultCommandDispatcher dispatcher = new DefaultCommandDispatcher(
                reservationService,
                null,
                fulfillmentService,
                productStockRepository
        );

        TlsServerSocketFactory socketFactory = TlsServerSocketFactory.fromKeystore(
                certificateBundle.serverKeystore(),
                certificateBundle.serverPassword(),
                "PKCS12",
                new String[]{"TLSv1.3", "TLSv1.2"}
        );

        TcpCommandServerConfig config = new TcpCommandServerConfig(
                "127.0.0.1",
                0,
                50,
                ProtocolInfo.DEFAULT_READ_TIMEOUT_MS,
                50,
                ProtocolInfo.MAX_FRAME_BYTES,
                false,
                true
        );

        server = new TcpCommandServer(config, dispatcher, socketFactory);
        server.start();
        serverPort = server.getPort();
        assertThat(serverPort).isGreaterThan(0);
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    @DisplayName("Trusted TLS client executes reservation, fulfillment, and movement lifecycle")
    void shouldExecuteStockLifecycleOverTlsAndPostgres() throws Exception {
        try (SSLSocket socket = createTlsClientSocket();
             TcpFrameWriter writer = new TcpFrameWriter(socket.getOutputStream());
             TcpFrameReader reader = new TcpFrameReader(socket.getInputStream())) {

            JsonNode products = send(
                    writer,
                    reader,
                    TcpMessageCodec.formatRequest(
                            ProtocolInfo.COMMAND_GET_PRODUCTS,
                            UUID.randomUUID().toString(),
                            null
                    )
            );
            assertThat(products.get("status").asText())
                    .isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
            assertThat(products.get("data").get("products"))
                    .isNotEmpty();

            UUID stockRequestId = UUID.randomUUID();
            String reservationFrame = TcpMessageCodec.formatRequest(
                    ProtocolInfo.COMMAND_REQUEST_STOCK,
                    stockRequestId.toString(),
                    Map.of(
                            "sku", SyntheticInventoryFixtures.SKU_ITEM_007,
                            "quantity", 8
                    )
            );
            JsonNode reservation = send(writer, reader, reservationFrame);

            assertThat(reservation.get("status").asText())
                    .isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

            JsonNode reservationReplay = send(writer, reader, reservationFrame);
            JsonNode reservationReplayAgain = send(writer, reader, reservationFrame);

            assertThat(reservationReplay.get("status").asText())
                    .isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
            assertThat(reservationReplayAgain.get("status").asText())
                    .isEqualTo(ProtocolInfo.STATUS_ACCEPTED);
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

            UUID pickCommandId = UUID.randomUUID();
            JsonNode picked = send(
                    writer,
                    reader,
                    TcpMessageCodec.formatRequest(
                            ProtocolInfo.COMMAND_MARK_PICKED,
                            pickCommandId.toString(),
                            Map.of("stockRequestId", stockRequestId.toString())
                    )
            );
            assertThat(picked.get("status").asText())
                    .isEqualTo(ProtocolInfo.STATUS_PICKED);
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 10, 8, 2);

            UUID dispatchCommandId = UUID.randomUUID();
            JsonNode dispatched = send(
                    writer,
                    reader,
                    TcpMessageCodec.formatRequest(
                            ProtocolInfo.COMMAND_MARK_DISPATCHED,
                            dispatchCommandId.toString(),
                            Map.of("stockRequestId", stockRequestId.toString())
                    )
            );
            assertThat(dispatched.get("status").asText())
                    .isEqualTo(ProtocolInfo.STATUS_DISPATCHED);
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

            JsonNode dispatchedReplay = send(
                    writer,
                    reader,
                    TcpMessageCodec.formatRequest(
                            ProtocolInfo.COMMAND_MARK_DISPATCHED,
                            dispatchCommandId.toString(),
                            Map.of("stockRequestId", stockRequestId.toString())
                    )
            );
            assertThat(dispatchedReplay.get("status").asText())
                    .isEqualTo(ProtocolInfo.STATUS_DISPATCHED);
            assertStockBalances(SyntheticInventoryFixtures.SKU_ITEM_007, 2, 0, 2);

            UUID movementsCommandId = UUID.randomUUID();
            JsonNode movements = send(
                    writer,
                    reader,
                    TcpMessageCodec.formatRequest(
                            ProtocolInfo.COMMAND_GET_MOVEMENTS,
                            movementsCommandId.toString(),
                            null
                    )
            );
            assertThat(movements.get("status").asText())
                    .isEqualTo(ProtocolInfo.STATUS_ACCEPTED);

            JsonNode movementArray = movements.get("data").get("movements");
            assertThat(movementArray).isNotNull();
            assertThat(movementArray).hasSize(1);
            assertThat(movementArray.get(0).get("stockRequestId").asText())
                    .isEqualTo(stockRequestId.toString());
            assertThat(movementArray.get(0).get("quantityDelta").intValue())
                    .isEqualTo(-8);

            Integer persistedMovementCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM stock_movements WHERE stock_request_id = ?",
                    Integer.class,
                    stockRequestId
            );
            assertThat(persistedMovementCount).isEqualTo(1);
        }
    }

    private SSLSocket createTlsClientSocket() throws Exception {
        KeyStore truststore = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(
                certificateBundle.gatewayTruststore().toFile())) {
            truststore.load(in, certificateBundle.trustPassword());
        }

        TrustManagerFactory trustManagerFactory =
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(truststore);

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), new SecureRandom());

        SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
        socket.setSoTimeout(ProtocolInfo.DEFAULT_READ_TIMEOUT_MS);
        SSLParameters parameters = socket.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        socket.setSSLParameters(parameters);
        socket.connect(
                new InetSocketAddress("localhost", serverPort),
                ProtocolInfo.DEFAULT_CONNECT_TIMEOUT_MS
        );
        socket.startHandshake();
        return socket;
    }

    private JsonNode send(
            TcpFrameWriter writer,
            TcpFrameReader reader,
            String requestFrame
    ) throws IOException {
        writer.writeFrame(requestFrame);
        return reader.readNextFrameJson();
    }

    private void assertStockBalances(
            String sku,
            int expectedOnHand,
            int expectedReserved,
            int expectedAvailable
    ) {
        Map<String, Object> balances = jdbcTemplate.queryForMap(
                "SELECT on_hand, reserved, (on_hand - reserved) AS available " +
                        "FROM product_stock WHERE sku = ?",
                sku
        );

        assertThat(((Number) balances.get("on_hand")).intValue())
                .isEqualTo(expectedOnHand);
        assertThat(((Number) balances.get("reserved")).intValue())
                .isEqualTo(expectedReserved);
        assertThat(((Number) balances.get("available")).intValue())
                .isEqualTo(expectedAvailable);
    }
}
