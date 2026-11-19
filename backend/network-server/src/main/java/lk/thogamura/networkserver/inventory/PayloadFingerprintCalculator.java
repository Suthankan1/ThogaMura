package lk.thogamura.networkserver.inventory;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/**
 * SHA-256 canonical payload fingerprint calculator for Protocol v1 command idempotency.
 *
 * <p>Enforces deterministic canonical representation regardless of key order,
 * whitespace, or numeric formatting.</p>
 */
public final class PayloadFingerprintCalculator {

    private PayloadFingerprintCalculator() {
    }

    /**
     * Calculates the canonical SHA-256 fingerprint for a stock request command payload.
     * Normalized canonical format: {@code {"quantity":<quantity>,"sku":"<trimmed-sku>"}}
     *
     * @param sku product SKU
     * @param quantity requested quantity
     * @return 64-character lowercase hex SHA-256 fingerprint
     */
    public static String calculateStockRequestFingerprint(String sku, int quantity) {
        String normalizedSku = sku != null ? sku.trim() : "";
        String normalized = "{\"quantity\":" + quantity + ",\"sku\":\"" + normalizedSku + "\"}";
        return computeSha256Hex(normalized);
    }

    /**
     * Calculates the canonical SHA-256 fingerprint for a stockRequestId command payload.
     * Normalized canonical format: {@code {"stockRequestId":"<uuid>"}}
     *
     * @param stockRequestId stock request UUID
     * @return 64-character lowercase hex SHA-256 fingerprint
     */
    public static String calculateStockRequestIdFingerprint(java.util.UUID stockRequestId) {
        java.util.Objects.requireNonNull(stockRequestId, "stockRequestId must not be null");
        String normalized = "{\"stockRequestId\":\"" + stockRequestId + "\"}";
        return computeSha256Hex(normalized);
    }

    /**
     * Calculates the canonical fingerprint from a generic {@link JsonNode} payload.
     *
     * @param data JSON node representing the command payload
     * @return 64-character lowercase hex SHA-256 fingerprint
     */
    public static String calculateFingerprint(JsonNode data) {
        if (data == null || data.isNull() || data.isEmpty()) {
            return computeSha256Hex("{}");
        }
        if (data.has("sku") && data.has("quantity")) {
            return calculateStockRequestFingerprint(
                    data.get("sku").asText(),
                    data.get("quantity").asInt()
            );
        }
        if (data.has("stockRequestId")) {
            String idStr = data.get("stockRequestId").asText().trim();
            try {
                return calculateStockRequestIdFingerprint(java.util.UUID.fromString(idStr));
            } catch (IllegalArgumentException ignored) {
                // fallback to generic canonicalization
            }
        }

        // Generic canonicalization: sort keys alphabetically
        Map<String, String> sortedMap = new TreeMap<>();
        Iterator<String> fieldNames = data.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            sortedMap.put(fieldName, data.get(fieldName).asText());
        }

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : sortedMap.entrySet()) {
            if (!first) {
                sb.append(",");
            }
            sb.append("\"").append(entry.getKey()).append("\":\"").append(entry.getValue()).append("\"");
            first = false;
        }
        sb.append("}");

        return computeSha256Hex(sb.toString());
    }

    /**
     * Computes the SHA-256 hash of a string and returns it as a 64-character lowercase hex string.
     *
     * @param input UTF-8 string input
     * @return 64-character lowercase hex hash
     */
    public static String computeSha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
