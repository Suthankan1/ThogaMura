package lk.thogamura.networkserver.integration.supplier;

import lk.thogamura.protocol.ProtocolInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultSupplierHttpClientTest {

    private SupplierHttpSimulator simulator;
    private int port;
    private DefaultSupplierHttpClient client;

    @BeforeEach
    void setUp() throws IOException {
        // Start independent simulator on an ephemeral loopback port
        simulator = new SupplierHttpSimulator(0);
        simulator.start();
        port = simulator.getPort();

        SupplierClientConfig config = new SupplierClientConfig(
                "http://127.0.0.1:" + port,
                3000,
                5000,
                16384,
                java.util.Set.of()
        );
        client = new DefaultSupplierHttpClient(config);
    }

    @AfterEach
    void tearDown() {
        if (simulator != null) {
            simulator.close();
        }
    }

    @Test
    @DisplayName("Should return AVAILABLE with reported quantity 20 for known available SKU")
    void shouldReturnAvailableWhenSupplierHasPositiveStock() {
        SupplierStockResponse response = client.checkAvailability("ITEM-008");

        assertThat(response.status()).isEqualTo(ProtocolInfo.STATUS_AVAILABLE);
        assertThat(response.sku()).isEqualTo("ITEM-008");
        assertThat(response.reportedQuantity()).isEqualTo(20);
        assertThat(response.supplier()).isEqualTo("Demo Supplier");
        assertThat(response.source()).isEqualTo("MOCK_SUPPLIER");
        assertThat(response.code()).isNull();
    }

    @Test
    @DisplayName("Should return AVAILABLE with reported quantity 0 for confirmed zero units (not missing)")
    void shouldReturnAvailableWhenSupplierHasConfirmedZeroUnits() {
        SupplierStockResponse response = client.checkAvailability("ITEM-008-ZERO");

        assertThat(response.status()).isEqualTo(ProtocolInfo.STATUS_AVAILABLE);
        assertThat(response.sku()).isEqualTo("ITEM-008-ZERO");
        assertThat(response.reportedQuantity()).isEqualTo(0);
        assertThat(response.supplier()).isEqualTo("Demo Supplier");
        assertThat(response.code()).isNull();
    }

    @Test
    @DisplayName("Should return UNKNOWN with NOT_FOUND when SKU is not cataloged (HTTP 404)")
    void shouldReturnUnknownWhenSkuIsNotFound404() {
        SupplierStockResponse response = client.checkAvailability("UNKNOWN-ITEM-999");

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.sku()).isEqualTo("UNKNOWN-ITEM-999");
        assertThat(response.reportedQuantity()).isNull();
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_NOT_FOUND);
    }

    @Test
    @DisplayName("Should return UNAVAILABLE with SUPPLIER_UNAVAILABLE when supplier returns HTTP 503")
    void shouldReturnUnavailableWhenSupplierReturns503() {
        SupplierStockResponse response = client.checkAvailability("ITEM-503");

        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.sku()).isEqualTo("ITEM-503");
        assertThat(response.reportedQuantity()).isNull();
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE);
    }

    @Test
    @DisplayName("Should return UNAVAILABLE when target supplier is offline (Connection Refused)")
    void shouldReturnUnavailableWhenSupplierIsOfflineConnectionRefused() throws IOException {
        ServerSocket temp = new ServerSocket(0);
        int closedPort = temp.getLocalPort();
        temp.close();

        SupplierClientConfig offlineConfig = new SupplierClientConfig(
                "http://127.0.0.1:" + closedPort,
                1000,
                1000,
                16384,
                java.util.Set.of()
        );
        DefaultSupplierHttpClient offlineClient = new DefaultSupplierHttpClient(offlineConfig);

        SupplierStockResponse response = offlineClient.checkAvailability("ITEM-008");
        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE);
    }

    @Test
    @DisplayName("Should return UNAVAILABLE with SUPPLIER_TIMEOUT when supplier read times out")
    void shouldReturnUnavailableWhenSupplierInquiryTimesOut() {
        // Configure short read timeout (300ms) against delayed simulator endpoint
        SupplierClientConfig timeoutConfig = new SupplierClientConfig(
                "http://127.0.0.1:" + port,
                1000,
                300,
                16384,
                java.util.Set.of()
        );
        DefaultSupplierHttpClient timeoutClient = new DefaultSupplierHttpClient(timeoutConfig);

        SupplierStockResponse response = timeoutClient.checkAvailability("ITEM-DELAY");
        assertThat(response.status()).isEqualTo("UNAVAILABLE");
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_TIMEOUT);
    }

    @Test
    @DisplayName("Should return UNKNOWN with SUPPLIER_UNKNOWN when supplier returns malformed JSON")
    void shouldReturnUnknownWhenSupplierReturnsMalformedJson() {
        SupplierStockResponse response = client.checkAvailability("ITEM-MALFORMED");

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNKNOWN);
    }

    @Test
    @DisplayName("Should return UNKNOWN with SUPPLIER_UNKNOWN when response exceeds maximum allowed size (16 KB)")
    void shouldReturnUnknownWhenSupplierResponseExceedsMaxBounds() throws IOException {
        // Start a mock server that sends a flood of 20,000 bytes with HTTP 200 OK
        try (ServerSocket oversizedServer = new ServerSocket(0)) {
            int oversizedPort = oversizedServer.getLocalPort();

            Thread serverThread = new Thread(() -> {
                try (Socket socket = oversizedServer.accept();
                     OutputStream out = socket.getOutputStream()) {
                    byte[] largeData = new byte[20_000];
                    java.util.Arrays.fill(largeData, (byte) 'A');
                    String httpResponse = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/json\r\n" +
                            "Content-Length: " + largeData.length + "\r\n\r\n";
                    out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
                    out.write(largeData);
                    out.flush();
                } catch (Exception ignored) {}
            });
            serverThread.setDaemon(true);
            serverThread.start();

            SupplierClientConfig oversizedConfig = new SupplierClientConfig(
                    "http://127.0.0.1:" + oversizedPort,
                    1000,
                    2000,
                    16384, // 16 KB max
                    java.util.Set.of()
            );
            DefaultSupplierHttpClient clientWithLimits = new DefaultSupplierHttpClient(oversizedConfig);

            SupplierStockResponse response = clientWithLimits.checkAvailability("ITEM-008");
            assertThat(response.status()).isEqualTo("UNKNOWN");
            assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNKNOWN);
            assertThat(response.message()).contains("exceeded maximum allowed limit");
        }
    }

    @Test
    @DisplayName("Should return UNKNOWN with SUPPLIER_UNKNOWN when supplier response SKU does not match requested SKU (#65)")
    void shouldReturnUnknownWhenSupplierReturnsMismatchedSku() {
        SupplierStockResponse response = client.checkAvailability(SupplierHttpSimulator.SKU_SIMULATE_WRONG_SKU);

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNKNOWN);
        assertThat(response.message()).contains("Supplier response SKU mismatch");
    }

    @Test
    @DisplayName("Should return UNKNOWN with SUPPLIER_UNKNOWN when supplier response is missing required supplier field (#65)")
    void shouldReturnUnknownWhenSupplierResponseMissingSupplier() {
        SupplierStockResponse response = client.checkAvailability(SupplierHttpSimulator.SKU_SIMULATE_MISSING_SUPPLIER);

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNKNOWN);
        assertThat(response.message()).contains("Missing or empty required field 'supplier'");
    }

    @Test
    @DisplayName("Should return UNKNOWN with SUPPLIER_UNKNOWN when supplier response has malformed checkedAt timestamp (#65)")
    void shouldReturnUnknownWhenSupplierResponseHasMalformedTimestamp() {
        SupplierStockResponse response = client.checkAvailability(SupplierHttpSimulator.SKU_SIMULATE_BAD_TIMESTAMP);

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNKNOWN);
        assertThat(response.message()).contains("Invalid ISO-8601 timestamp in 'checkedAt'");
    }

    @Test
    @DisplayName("Should return UNKNOWN with SUPPLIER_UNKNOWN when supplier response has negative estimatedRestockDays (#65)")
    void shouldReturnUnknownWhenSupplierResponseHasNegativeRestockDays() {
        SupplierStockResponse response = client.checkAvailability(SupplierHttpSimulator.SKU_SIMULATE_NEGATIVE_RESTOCK);

        assertThat(response.status()).isEqualTo("UNKNOWN");
        assertThat(response.code()).isEqualTo(ProtocolInfo.ERROR_SUPPLIER_UNKNOWN);
        assertThat(response.message()).contains("Negative 'estimatedRestockDays'");
    }

    @Test
    @DisplayName("Should return VALIDATION_ERROR when SKU is blank or contains invalid characters")
    void shouldReturnValidationErrorWhenSkuIsInvalidOrBlank() {
        SupplierStockResponse blank = client.checkAvailability("   ");
        assertThat(blank.code()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);

        SupplierStockResponse injection = client.checkAvailability("ITEM;DROP TABLE;--");
        assertThat(injection.code()).isEqualTo(ProtocolInfo.ERROR_VALIDATION);
    }

    @Test
    @DisplayName("Should reject unapproved external supplier base URLs to prevent SSRF")
    void shouldRejectUnapprovedSupplierHostDueToSsrf() {
        SupplierClientConfig ssrfConfig = new SupplierClientConfig(
                "http://169.254.169.254/metadata",
                3000,
                5000,
                16384,
                java.util.Set.of()
        );

        assertThatThrownBy(ssrfConfig::validate)
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("approved HTTPS allowlist");
    }

    @Test
    @DisplayName("Should permit an explicitly allowlisted HTTPS origin")
    void shouldAllowConfiguredHttpsOrigin() {
        SupplierClientConfig testConfig = new SupplierClientConfig(
                "https://supplier.example.test:8443",
                3000,
                5000,
                16384,
                java.util.Set.of("https://supplier.example.test:8443")
        );
        testConfig.validate();

    }
}
