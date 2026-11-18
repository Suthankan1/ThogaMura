package lk.thogamura.networkserver.integration.supplier;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real HTTP networking tests verifying {@link SupplierHttpSimulator} over loopback TCP sockets.
 *
 * <p>Validates Member 2 requirements (Issue #31):
 * <ul>
 *   <li>Real {@link HttpURLConnection} requests over actual TCP/IP stack.</li>
 *   <li>Known available stock returns 200 OK with deterministic quantities.</li>
 *   <li>Known zero quantity returns 200 OK (distinguishable from unknown SKU).</li>
 *   <li>Unknown SKU returns 404 SKU_NOT_FOUND.</li>
 *   <li>Supplier outage returns 503 SERVICE_UNAVAILABLE.</li>
 *   <li>Delayed response reproduces client read timeout without corrupting server.</li>
 *   <li>Malformed JSON payload test mode returns unparseable content.</li>
 *   <li>Safe shutdown releases port and fails subsequent connections closed.</li>
 * </ul>
 * </p>
 */
class SupplierHttpSimulatorTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private SupplierHttpSimulator simulator;
    private String baseUrl;

    @BeforeEach
    void setUp() throws IOException {
        // Bind to ephemeral port 0 on loopback 127.0.0.1 for isolation and parallel test safety
        simulator = new SupplierHttpSimulator(0);
        simulator.start();
        baseUrl = simulator.getBaseUrl();
    }

    @AfterEach
    void tearDown() {
        if (simulator != null) {
            simulator.close();
        }
    }

    @Test
    @DisplayName("T04: Known SKU with available units returns 200 OK with contract-compliant JSON")
    void shouldReturnKnownAvailableStockOverRealHttp() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008", "GET");
        conn.setRequestProperty("Accept", "application/json");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(200);
        assertThat(conn.getHeaderField("Content-Type")).contains("application/json");
        assertThat(conn.getHeaderField("Server")).isEqualTo("ThogaMura-MockSupplier/1.0");
        assertThat(conn.getHeaderField("X-Supplier-Source")).isEqualTo("MOCK_SUPPLIER");

        String body = readStream(conn.getInputStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("sku").asText()).isEqualTo("ITEM-008");
        assertThat(json.get("supplier").asText()).isEqualTo("Demo Supplier");
        assertThat(json.get("availableUnits").asInt()).isEqualTo(20);
        assertThat(json.get("estimatedRestockDays").asInt()).isEqualTo(3);

        String checkedAt = json.get("checkedAt").asText();
        assertThat(checkedAt).isNotBlank();
        Instant parsed = Instant.parse(checkedAt);
        assertThat(parsed).isNotNull();
    }

    @Test
    @DisplayName("T04: Known SKU with confirmed zero units returns 200 OK and reportedQuantity=0")
    void shouldReturnKnownZeroStockOverRealHttp() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008-ZERO", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(200);

        String body = readStream(conn.getInputStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("sku").asText()).isEqualTo("ITEM-008-ZERO");
        assertThat(json.get("availableUnits").asInt()).isEqualTo(0);
        assertThat(json.get("estimatedRestockDays").asInt()).isEqualTo(7);
    }

    @Test
    @DisplayName("T04: Query param mode=zero forces confirmed zero units for testing")
    void shouldReturnKnownZeroStockViaQueryParamMode() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008&mode=zero", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(200);

        String body = readStream(conn.getInputStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("sku").asText()).isEqualTo("ITEM-008");
        assertThat(json.get("availableUnits").asInt()).isEqualTo(0);
    }

    @Test
    @DisplayName("T05: Unknown SKU returns HTTP 404 Not Found with SKU_NOT_FOUND code")
    void shouldReturn404ForUnknownSku() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=NONEXISTENT_SKU_999", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(404);

        String body = readStream(conn.getErrorStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("code").asText()).isEqualTo("SKU_NOT_FOUND");
        assertThat(json.get("message").asText()).isEqualTo("Item is not cataloged by supplier");
    }

    @Test
    @DisplayName("T05: Special SKU ITEM-503 returns HTTP 503 SERVICE_UNAVAILABLE")
    void shouldReturn503WhenSupplierUnavailableViaSku() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-503", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(503);

        String body = readStream(conn.getErrorStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(json.get("message").asText()).isEqualTo("Supplier inventory system undergoing maintenance");
    }

    @Test
    @DisplayName("T05: Query param mode=unavailable returns HTTP 503 SERVICE_UNAVAILABLE")
    void shouldReturn503WhenSupplierUnavailableViaMode() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008&mode=unavailable", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(503);

        String body = readStream(conn.getErrorStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
    }

    @Test
    @DisplayName("T05: Global simulator outage toggle causes standard queries to return 503")
    void shouldReturn503WhenGlobalOutageToggled() throws Exception {
        simulator.setServiceUnavailable(true);

        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008", "GET");
        assertThat(conn.getResponseCode()).isEqualTo(503);

        String body = readStream(conn.getErrorStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);
        assertThat(json.get("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");

        // Restore service availability
        simulator.setServiceUnavailable(false);

        HttpURLConnection connRecovered = openConnection(baseUrl + "/v1/stock?sku=ITEM-008", "GET");
        assertThat(connRecovered.getResponseCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("T05: Delayed response triggers client read timeout exception across TCP")
    void shouldSimulateTimeoutViaDelayedResponse() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008&delayMs=500", "GET");
        // Finite client timeout shorter than server delay
        conn.setReadTimeout(150);

        assertThatThrownBy(conn::getResponseCode)
                .isInstanceOf(SocketTimeoutException.class);
    }

    @Test
    @DisplayName("T05: Delayed response succeeds when client deadline is longer than server delay")
    void shouldSucceedWhenDelayIsWithinClientTimeout() throws Exception {
        long start = System.currentTimeMillis();
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008&delayMs=120", "GET");
        conn.setReadTimeout(2000);

        int statusCode = conn.getResponseCode();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(statusCode).isEqualTo(200);
        assertThat(elapsed).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("T05: Malformed JSON test mode via SKU ITEM-MALFORMED produces unparseable JSON")
    void shouldReturnMalformedJsonPayloadWhenRequestedViaSku() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-MALFORMED", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(200);

        String body = readStream(conn.getInputStream());
        assertThat(body).contains("MALFORMED_JSON_PAYLOAD");

        assertThatThrownBy(() -> OBJECT_MAPPER.readTree(body))
                .isInstanceOf(JsonParseException.class);
    }

    @Test
    @DisplayName("T05: Malformed JSON test mode via query param mode=malformed produces unparseable JSON")
    void shouldReturnMalformedJsonPayloadWhenRequestedViaMode() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008&mode=malformed", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(200);

        String body = readStream(conn.getInputStream());
        assertThatThrownBy(() -> OBJECT_MAPPER.readTree(body))
                .isInstanceOf(JsonParseException.class);
    }

    @Test
    @DisplayName("T05: Global malformed payload toggle returns unparseable content")
    void shouldReturnMalformedJsonPayloadWhenGloballyToggled() throws Exception {
        simulator.setMalformedPayload(true);

        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008", "GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);

        String body = readStream(conn.getInputStream());
        assertThatThrownBy(() -> OBJECT_MAPPER.readTree(body))
                .isInstanceOf(JsonParseException.class);

        simulator.setMalformedPayload(false);
    }

    @Test
    @DisplayName("Missing sku query parameter returns HTTP 400 Bad Request")
    void shouldReturn400BadRequestWhenSkuParameterIsMissing() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(400);

        String body = readStream(conn.getErrorStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("code").asText()).isEqualTo("MISSING_PARAMETER");
    }

    @Test
    @DisplayName("Blank sku parameter returns HTTP 400 Bad Request")
    void shouldReturn400BadRequestWhenSkuParameterIsBlank() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=%20%20", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(400);

        String body = readStream(conn.getErrorStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("code").asText()).isEqualTo("MISSING_PARAMETER");
    }

    @Test
    @DisplayName("Non-GET method (e.g. POST) returns HTTP 405 Method Not Allowed")
    void shouldReturn405MethodNotAllowedForPost() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ITEM-008", "POST");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write("{}".getBytes(StandardCharsets.UTF_8));
        }

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(405);

        String body = readStream(conn.getErrorStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("code").asText()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("Service identification: /v1/status clearly identifies service as MOCK_SUPPLIER")
    void shouldIdentifyServiceAsMockSupplierOnStatusEndpoint() throws Exception {
        HttpURLConnection conn = openConnection(baseUrl + "/v1/status", "GET");

        int statusCode = conn.getResponseCode();
        assertThat(statusCode).isEqualTo(200);

        String body = readStream(conn.getInputStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("service").asText()).isEqualTo("MOCK_SUPPLIER");
        assertThat(json.get("status").asText()).isEqualTo("UP");
        assertThat(json.get("advisoryOnly").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("Dynamic catalog registration allows custom synthetic products in test scenarios")
    void shouldSupportDynamicCatalogStockRegistration() throws Exception {
        simulator.setStock("ORGANIC-CARDAMOM-500G", 88, 2);

        HttpURLConnection conn = openConnection(baseUrl + "/v1/stock?sku=ORGANIC-CARDAMOM-500G", "GET");
        assertThat(conn.getResponseCode()).isEqualTo(200);

        String body = readStream(conn.getInputStream());
        JsonNode json = OBJECT_MAPPER.readTree(body);

        assertThat(json.get("sku").asText()).isEqualTo("ORGANIC-CARDAMOM-500G");
        assertThat(json.get("availableUnits").asInt()).isEqualTo(88);
        assertThat(json.get("estimatedRestockDays").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("Request tracking: simulator accurately counts requests and tracks last queried SKU")
    void shouldTrackRequestCountAndLastRequestedSku() throws Exception {
        long initialCount = simulator.getRequestCount();

        HttpURLConnection conn1 = openConnection(baseUrl + "/v1/stock?sku=ITEM-008", "GET");
        conn1.getResponseCode();
        conn1.getInputStream().close();

        HttpURLConnection conn2 = openConnection(baseUrl + "/v1/stock?sku=ITEM-007", "GET");
        conn2.getResponseCode();
        conn2.getInputStream().close();

        assertThat(simulator.getRequestCount()).isEqualTo(initialCount + 2);
        assertThat(simulator.getLastRequestedSku()).isEqualTo("ITEM-007");
    }

    @Test
    @DisplayName("Safe shutdown: stopping server releases port and subsequent requests fail closed")
    void shouldRejectConnectionAfterSafeShutdown() throws Exception {
        int port = simulator.getPort();
        simulator.stop();

        // Attempting to connect to the stopped server must fail closed with ConnectException
        assertThatThrownBy(() -> {
            HttpURLConnection conn = openConnection("http://127.0.0.1:" + port + "/v1/stock?sku=ITEM-008", "GET");
            conn.setConnectTimeout(1000);
            conn.getResponseCode();
        }).isInstanceOf(ConnectException.class);
    }

    // --- Helper Methods ---

    private HttpURLConnection openConnection(String urlStr, String method) throws Exception {
        URI uri = URI.create(urlStr);
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setInstanceFollowRedirects(false);
        return conn;
    }

    private String readStream(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString().trim();
        }
    }
}
