package lk.thogamura.networkserver.integration.supplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lk.thogamura.protocol.ProtocolInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.HttpsURLConnection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Standard Java URL/HTTP networking client communicating with external supplier services.
 *
 * <p>Implemented by Member 2 using core JDK networking APIs:
 * <ul>
 *   <li>{@link java.net.URI} and {@link java.net.URL} for safe URI composition</li>
 *   <li>{@link java.net.HttpURLConnection} and {@link javax.net.ssl.HttpsURLConnection} for transport</li>
 *   <li>Explicit connect (3,000 ms) and read (5,000 ms) timeouts</li>
 *   <li>Bounded payload buffer (16 KB) preventing memory exhaustion</li>
 *   <li>SSRF protection via URL validation and redirect blocking ({@code setInstanceFollowRedirects(false)})</li>
 *   <li>Preserves HTTPS certificate trust chain validation</li>
 *   <li>Strictly read-only: never modifies central database stock or places orders</li>
 * </ul>
 * </p>
 */
public class DefaultSupplierHttpClient implements SupplierHttpClient {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSupplierHttpClient.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Pattern SKU_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final SupplierClientConfig config;

    public DefaultSupplierHttpClient(SupplierClientConfig config) {
        this.config = Objects.requireNonNull(config, "config must not be null");
    }

    public DefaultSupplierHttpClient() {
        this(new SupplierClientConfig());
    }

    @Override
    public SupplierStockResponse checkAvailability(String sku) {
        if (sku == null || sku.isBlank()) {
            return SupplierStockResponse.validationError(sku, "Query parameter 'sku' is required and must not be blank");
        }

        String trimmedSku = sku.trim();
        if (!SKU_PATTERN.matcher(trimmedSku).matches()) {
            return SupplierStockResponse.validationError(trimmedSku, "Query parameter 'sku' contains invalid characters: " + trimmedSku);
        }

        try {
            config.validate();
        } catch (SecurityException e) {
            logger.error("SSRF violation blocked for supplier base URL '{}': {}", config.getBaseUrl(), e.getMessage());
            return SupplierStockResponse.unavailable(trimmedSku, ProtocolInfo.ERROR_INTERNAL, "Supplier connector configuration is invalid");
        } catch (IllegalArgumentException e) {
            logger.error("Invalid supplier client configuration: {}", e.getMessage());
            return SupplierStockResponse.unavailable(trimmedSku, ProtocolInfo.ERROR_INTERNAL, "Supplier connector configuration is invalid");
        }

        HttpURLConnection conn = null;
        try {
            String encodedSku = URLEncoder.encode(trimmedSku, StandardCharsets.UTF_8);
            String urlString = config.getBaseUrl() + "/v1/stock?sku=" + encodedSku;
            URI uri = URI.create(urlString);
            URL url = uri.toURL();

            logger.debug("Executing supplier stock inquiry via Java URL: {}", url);

            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(config.getConnectTimeoutMs());
            conn.setReadTimeout(config.getReadTimeoutMs());
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "ThogaMura-Warehouse-SupplierClient/1.0");
            conn.setInstanceFollowRedirects(false); // Disallow SSRF redirect hops

            // Preserve standard HTTPS certificate validation
            if (conn instanceof HttpsURLConnection httpsConn) {
                // Ensure default hostname verifier and SSL socket factory are preserved
                logger.trace("Connecting via HttpsURLConnection with standard certificate validation");
            }

            conn.connect();
            int statusCode = conn.getResponseCode();
            logger.debug("Supplier HTTP inquiry for SKU '{}' returned status {}", trimmedSku, statusCode);

            InputStream in = (statusCode >= 200 && statusCode < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();

            byte[] responseBytes = readBoundedStream(in, config.getMaxResponseBytes());
            String responseJson = new String(responseBytes, StandardCharsets.UTF_8);

            return processHttpResponse(trimmedSku, statusCode, responseJson);

        } catch (SocketTimeoutException e) {
            logger.warn("Supplier inquiry timed out (connect={}ms, read={}ms) for SKU '{}': {}",
                    config.getConnectTimeoutMs(), config.getReadTimeoutMs(), trimmedSku, e.getMessage());
            return SupplierStockResponse.unavailable(
                    trimmedSku,
                    ProtocolInfo.ERROR_SUPPLIER_TIMEOUT,
                    "Supplier connection or read timed out after deadline"
            );
        } catch (ConnectException | UnknownHostException | NoRouteToHostException e) {
            logger.warn("Supplier service is unreachable at '{}' for SKU '{}': {}",
                    config.getBaseUrl(), trimmedSku, e.getMessage());
            return SupplierStockResponse.unavailable(
                    trimmedSku,
                    ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE,
                    "External supplier service is offline or unreachable: " + e.getMessage()
            );
        } catch (SupplierPayloadTooLargeException e) {
            logger.error("Supplier response exceeded max bounds ({} bytes) for SKU '{}'",
                    config.getMaxResponseBytes(), trimmedSku);
            return SupplierStockResponse.unknown(
                    trimmedSku,
                    ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                    "Supplier response payload exceeded maximum allowed limit of " + config.getMaxResponseBytes() + " bytes"
            );
        } catch (IOException e) {
            logger.error("I/O error communicating with supplier service for SKU '{}': {}",
                    trimmedSku, e.getMessage(), e);
            return SupplierStockResponse.unavailable(
                    trimmedSku,
                    ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE,
                    "I/O error communicating with supplier: " + e.getMessage()
            );
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private SupplierStockResponse processHttpResponse(String sku, int statusCode, String responseJson) {
        if (statusCode == HttpURLConnection.HTTP_OK) { // 200 OK
            try {
                JsonNode root = OBJECT_MAPPER.readTree(responseJson);
                if (root == null || !root.isObject()) {
                    return SupplierStockResponse.unknown(
                            sku,
                            ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                            "Supplier response root must be a JSON object"
                    );
                }

                // 1. Validate 'sku' presence and exact equality with requested SKU
                if (!root.hasNonNull("sku") || !root.get("sku").isTextual() || root.get("sku").asText().isBlank()) {
                    return SupplierStockResponse.unknown(
                            sku,
                            ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                            "Missing or empty required field 'sku' in supplier response"
                    );
                }
                String responseSku = root.get("sku").asText();
                if (!sku.equals(responseSku)) {
                    return SupplierStockResponse.unknown(
                            sku,
                            ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                            "Supplier response SKU mismatch: requested '" + sku + "' but response was for '" + responseSku + "'"
                    );
                }

                // 2. Validate 'supplier' presence
                if (!root.hasNonNull("supplier") || !root.get("supplier").isTextual() || root.get("supplier").asText().isBlank()) {
                    return SupplierStockResponse.unknown(
                            sku,
                            ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                            "Missing or empty required field 'supplier' in supplier response"
                    );
                }
                String supplier = root.get("supplier").asText();

                // 3. Validate 'availableUnits'
                if (!root.hasNonNull("availableUnits") || !root.get("availableUnits").isInt()) {
                    return SupplierStockResponse.unknown(
                            sku,
                            ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                            "Missing or invalid integer field 'availableUnits' in supplier response"
                    );
                }
                int availableUnits = root.get("availableUnits").asInt();
                if (availableUnits < 0) {
                    return SupplierStockResponse.unknown(
                            sku,
                            ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                            "Negative 'availableUnits' reported by supplier: " + availableUnits
                    );
                }

                // 4. Validate optional 'estimatedRestockDays' (non-negative integer when supplied)
                Integer restockDays = null;
                if (root.has("estimatedRestockDays") && !root.get("estimatedRestockDays").isNull()) {
                    if (!root.get("estimatedRestockDays").isInt()) {
                        return SupplierStockResponse.unknown(
                                sku,
                                ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                                "Invalid integer field 'estimatedRestockDays' in supplier response"
                        );
                    }
                    int parsedRestock = root.get("estimatedRestockDays").asInt();
                    if (parsedRestock < 0) {
                        return SupplierStockResponse.unknown(
                                sku,
                                ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                                "Negative 'estimatedRestockDays' reported by supplier: " + parsedRestock
                        );
                    }
                    restockDays = parsedRestock;
                }

                // 5. Validate 'checkedAt' as ISO-8601 timestamp
                if (!root.hasNonNull("checkedAt") || !root.get("checkedAt").isTextual() || root.get("checkedAt").asText().isBlank()) {
                    return SupplierStockResponse.unknown(
                            sku,
                            ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                            "Missing or empty required field 'checkedAt' in supplier response"
                    );
                }
                String checkedAt = root.get("checkedAt").asText();
                try {
                    Instant.parse(checkedAt);
                } catch (Exception e) {
                    return SupplierStockResponse.unknown(
                            sku,
                            ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                            "Invalid ISO-8601 timestamp in 'checkedAt' supplier response: " + checkedAt
                    );
                }

                return SupplierStockResponse.available(sku, supplier, availableUnits, restockDays, checkedAt);

            } catch (JsonProcessingException e) {
                logger.error("Malformed JSON in 200 OK supplier response for SKU '{}': {}", sku, e.getMessage());
                return SupplierStockResponse.unknown(
                        sku,
                        ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                        "Malformed JSON received from supplier: " + e.getOriginalMessage()
                );
            }
        } else if (statusCode == HttpURLConnection.HTTP_NOT_FOUND) { // 404 Not Found
            return SupplierStockResponse.unknown(
                    sku,
                    ProtocolInfo.ERROR_NOT_FOUND,
                    "Item is not cataloged by supplier"
            );
        } else if (statusCode == HttpURLConnection.HTTP_UNAVAILABLE) { // 503 Service Unavailable
            return SupplierStockResponse.unavailable(
                    sku,
                    ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE,
                    "External supplier service is currently undergoing maintenance or unavailable"
            );
        } else if (statusCode == HttpURLConnection.HTTP_BAD_GATEWAY) { // 502
            return SupplierStockResponse.unknown(
                    sku,
                    ProtocolInfo.ERROR_SUPPLIER_UNKNOWN,
                    "Supplier upstream gateway error (HTTP 502)"
            );
        } else if (statusCode == HttpURLConnection.HTTP_GATEWAY_TIMEOUT) { // 504
            return SupplierStockResponse.unavailable(
                    sku,
                    ProtocolInfo.ERROR_SUPPLIER_TIMEOUT,
                    "Supplier upstream gateway timeout (HTTP 504)"
            );
        } else {
            logger.warn("Unexpected HTTP status code {} returned by supplier for SKU '{}'", statusCode, sku);
            return SupplierStockResponse.unavailable(
                    sku,
                    ProtocolInfo.ERROR_SUPPLIER_UNAVAILABLE,
                    "Supplier service returned unexpected HTTP status: " + statusCode
            );
        }
    }

    private byte[] readBoundedStream(InputStream in, int maxBytes) throws IOException, SupplierPayloadTooLargeException {
        if (in == null) {
            return new byte[0];
        }
        try (in) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
            byte[] buffer = new byte[1024];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new SupplierPayloadTooLargeException(
                            "Incoming supplier payload exceeded limit of " + maxBytes + " bytes"
                    );
                }
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }

    public SupplierClientConfig getConfig() {
        return config;
    }

    private static class SupplierPayloadTooLargeException extends Exception {
        public SupplierPayloadTooLargeException(String message) {
            super(message);
        }
    }
}
